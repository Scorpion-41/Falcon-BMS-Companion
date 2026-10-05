package com.bmscompanion.desktop.bridge

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Density
import androidx.navigation.compose.rememberNavController
import com.bmscompanion.app.data.AirportSet
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.Theater
import com.bmscompanion.app.data.mission.AttackCue
import com.bmscompanion.app.data.mission.AttackOverlay
import com.bmscompanion.app.data.mission.BoardSlot
import com.bmscompanion.app.data.mission.BriefOverview
import com.bmscompanion.app.data.mission.BriefSteerpoint
import com.bmscompanion.app.data.mission.Briefing
import com.bmscompanion.app.data.mission.CampKind
import com.bmscompanion.app.data.mission.CommEntry
import com.bmscompanion.app.data.mission.Dtc
import com.bmscompanion.app.data.mission.DtcComm
import com.bmscompanion.app.data.mission.DtcPoint
import com.bmscompanion.app.data.mission.DtcPpt
import com.bmscompanion.app.data.mission.LinkState
import com.bmscompanion.app.data.mission.MergedMission
import com.bmscompanion.app.data.mission.MissionData
import com.bmscompanion.app.data.mission.MissionLink
import com.bmscompanion.app.data.mission.MissionRoute
import com.bmscompanion.app.data.mission.PackageFlight
import com.bmscompanion.app.data.mission.PlanItemSource
import com.bmscompanion.app.data.mission.PlanMerge
import com.bmscompanion.app.data.mission.PlanOverlay
import com.bmscompanion.app.data.mission.PlanSource
import com.bmscompanion.app.data.mission.Preset
import com.bmscompanion.app.data.mission.SupportEntry
import com.bmscompanion.app.data.mission.TextBlock
import com.bmscompanion.app.ui.Kneeboard
import com.bmscompanion.app.ui.KneeboardFrame
import com.bmscompanion.app.ui.board.BoardKind
import com.bmscompanion.app.ui.board.BoardSheetPreview
import com.bmscompanion.app.ui.components.MapState
import com.bmscompanion.app.ui.screens.mission.DashCard
import com.bmscompanion.app.ui.screens.mission.DashItem
import com.bmscompanion.app.ui.screens.mission.DashLayouts
import com.bmscompanion.app.ui.screens.mission.DashWidth
import com.bmscompanion.app.ui.screens.mission.MissionCommsPane
import com.bmscompanion.app.ui.screens.mission.MissionDashboardPane
import com.bmscompanion.app.ui.screens.mission.MissionEnv
import com.bmscompanion.app.ui.screens.mission.plannedStations
import com.bmscompanion.app.ui.screens.mission.preplannedPoints
import com.bmscompanion.app.ui.screens.mission.presetNote
import com.bmscompanion.app.ui.screens.mission.radioSettings
import com.bmscompanion.app.ui.screens.mission.refuelStation
import com.bmscompanion.app.ui.screens.mission.resolveTheater
import com.bmscompanion.app.ui.screens.mission.supportAssets
import com.bmscompanion.app.ui.theme.BmsTheme
import com.bmscompanion.app.ui.theme.Hud
import com.bmscompanion.app.ui.theme.HudSkin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.hypot

/**
 * `--planviewsrender <folder> [<fixture root>]`: the Mission views besides the Map and the Briefing — Comms, the
 * Dashboard, the Support card and the VR boards (MAP, FLIGHT, COMMS, SUPPORT, THREATS, BRIEFING) — drawn headless
 * with a plan sent from the Planner, and the rules behind their marks checked in the shared code every device runs.
 *
 * With a fixture root (a copy of the BMS folder: `User/Briefings/briefing.txt`, the pilot's cartridge beside its `.pop`,
 * `Data/Campaign/Auto Save.ini`) the mission is the real 4.38.1 one; without, a made-up Korea mission of the same
 * shape (the same route, ladder and support section, no personal names). The plan moves STPT 5, adds a target STPT 23,
 * an SA-3 PPT and an AWACS marker, a line, moves the tanker's UHF preset from 13 to 14, sets the UHF preset tuned at
 * load, a TACAN and an ILS, a Mode 3 code, laser codes, bingo, ALOW and the EWS names, and carries a TOSS attack.
 *
 * The pictures are written to `<folder>`; each one is also measured for the Planner's ink (PlanInk: magenta on a
 * screen, plum on paper), so a mark that is not drawn fails the check rather than passing unseen, and the Comms page
 * with no plan is measured for having none. Nothing is written anywhere else but the dashboard layout of page 5 in the
 * program's own settings (its APPDATA).
 */
object PlanViewsRender {
    /** PlanInk on a dark screen and on a paper board (MissionPlan.kt). */
    internal const val INK_SCREEN = 0xFF5CE1
    internal const val INK_PAPER = 0xB0209A

    fun run(folder: File, more: List<String>): String {
        val sb = StringBuilder()
        var fails = 0
        fun check(name: String, ok: Boolean, detail: String = "") {
            if (!ok) fails++
            sb.appendLine((if (ok) "PASS " else "FAIL ") + name + if (detail.isNotEmpty()) " — $detail" else "")
        }
        sb.appendLine("--planviewsrender: Comms, Dashboard, Support and the VR boards with a plan (M2)")
        sb.appendLine()
        folder.mkdirs()
        try {
            val all = runBlocking { Repo.index().theaters }
            val th = resolveTheater(all, "Korea KTO") ?: error("no Korea theater in the app's data")
            val set = runBlocking { Repo.airportSet(th.airportSet) }
            val geo = th.mapId?.let { id -> runCatching { runBlocking { Repo.geo(id) } }.getOrNull() }
            val root = more.firstOrNull()?.let(::File)?.takeIf { it.isDirectory }
            val base = if (root != null) {
                sb.appendLine("mission: the fixture's real files ($root)")
                fromFixture(root)
            } else {
                sb.appendLine("mission: made up (no fixture root given), the same shape as the 4.38.1 fixture")
                madeUp()
            }
            val plan = planFor(base, th.id)
            val withPlan = base.copy(plan = plan)
            val merged = PlanMerge.merge(withPlan)
            sb.appendLine("   briefing ${base.briefing?.overview?.flight} package ${base.briefing?.overview?.packageId}: ${base.briefing?.steerpoints?.size} rows, ${base.briefing?.comms?.size} ladder rows; plan not in jet: ${merged.notInJet.joinToString()}")
            sb.appendLine()
            rules(base, withPlan, merged, set, geo, ::check, sb)
            sb.appendLine()
            sb.appendLine("== pictures ($folder)")
            pictures(folder, th, set, base, withPlan, ::check, sb)
        } catch (e: Throwable) {
            check("ran without an exception", false, "${e::class.simpleName}: ${e.message}")
        }
        sb.appendLine()
        sb.appendLine(if (fails == 0) "ALL PASS" else "FAIL: $fails check(s) failed")
        return sb.toString()
    }

    // ------------------------------------------------------------------ the mission

    /** BMS's route of the 4.38.1 fixture (Auto Save.ini): take-off at Osan, two CAPs, Refuel at STPT 7. */
    private val ROUTE = listOf(
        DtcPoint(1, 1162752.875, 1539950.625, 42.0, 1), DtcPoint(2, 1146353.0, 1274272.25, 20000.0, 0),
        DtcPoint(3, 1136513.125, 1162752.875, 20000.0, 12), DtcPoint(4, 1100433.25, 1234912.5, 20000.0, 12),
        DtcPoint(5, 1126673.125, 1362831.75, 20000.0, 0), DtcPoint(6, 1162752.875, 1539950.625, 42.0, 7),
        DtcPoint(7, 1149633.0, 2090987.25, 20000.0, 4), DtcPoint(8, 1120113.125, 1543230.625, 48.0, 7),
    )

    private fun fromFixture(root: File): MissionData {
        val brief = BriefingParser.parse(File(root, "User/Briefings/briefing.txt").readText(Charsets.ISO_8859_1))
        // the pilot's cartridge is the .ini beside the pilot file (.pop); its name is the callsign, never printed here
        val pop = File(root, "User/Config").listFiles { f -> f.name.endsWith(".pop", ignoreCase = true) }?.firstOrNull()
        val cart = pop?.let { File(it.parentFile, it.nameWithoutExtension + ".ini") }?.takeIf { it.isFile }
        val dtc = cart?.let { DtcParser.parse(it) } ?: Dtc()
        val ini = File(root, "Data/Campaign/Auto Save.ini")
        val r = DtcParser.parse(ini)
        val route = MissionRoute(file = ini.name, save = "Auto Save.cam", kind = CampKind.CAMPAIGN, modified = ini.lastModified(),
            steerpoints = r.steerpoints, ppts = r.ppts, lines = r.lines, weaponTargets = r.weaponTargets)
        return MissionData(version = "fixture", briefingModified = 1, briefing = brief, dtc = dtc, route = route)
    }

    private fun madeUp(): MissionData {
        val words = listOf("Takeoff" to "Takeoff", "--" to "Nav", "CAP" to "CAP", "CAP" to "CAP", "--" to "Nav", "Land" to "Land", "Refuel" to "Refuel", "Land" to "Land")
        val rows = ROUTE.mapIndexed { i, p ->
            BriefSteerpoint(p.n, words[i].first, "04:%02d:00z".format(20 + i * 5), null, null, null, if (p.altFt > 1000) "20.0M" else "---", words[i].second, null,
                if (p.n == 8) "Alternate" else if (p.n == 7) "Tanker station area" else null)
        }
        val brief = Briefing(
            generated = "9/25/2026 22:40:54",
            overview = BriefOverview(flight = "Viper1", mission = "BARCAP", packageId = "7288", packageType = "Escort"),
            situation = "Hostile forces are expected to probe the border.",
            `package` = listOf(PackageFlight(callsign = "Viper1", flightId = "7289", primary = true, role = "BARCAP", aircraft = "F-16CM-52", count = 2, takeoff = "04:20:00z")),
            threats = listOf(TextBlock("Surface-to-Air Threats:", listOf("SA-3 and SA-2 sites along the coast"))),
            steerpoints = rows,
            comms = listOf(
                CommEntry("Dep Tower:", "Osan Tower", "308.800", 3, "122.100", 3, "Departure Airbase", "a"),
                CommEntry("Check-In:", "Magic4", "371.775", 5, null, null, "AWACS: Global Check-In", "b"),
                CommEntry("Tactical:", "Magic4", "384.875", 6, null, null, "AWACS: Package Comms", "b"),
                CommEntry("Tanker / Aar:", "Copper2", "356.850", 13, null, null, "Boom Operator (TCN: 059Y)", "b"),
                CommEntry("Arr Tower:", "Osan Tower", "308.800", 3, "122.100", 3, "Recovery Airbase", "c"),
                CommEntry("Alt Tower:", "Pyeongtaek Tower", "257.800", 11, "122.500", 11, "Alternate Airbase", "c"),
            ),
            support = listOf(
                SupportEntry("Magic4", "AWACS", "1 E-3", "Friendly AWACS aircraft will be operating 17 nm northeast of Sejong City. Available for air detection and warning."),
                SupportEntry("Copper2", "Tanker", "1 KC-130", "Friendly tankers will be operating 21 nm southwest of Yongin-si City. Available for mid-air refueling. (TCN: 059Y)"),
            ),
            roe = listOf("Visually ID unknown aircraft before engaging, unless authorized by AWACS."),
            alternate = "Pyeongtaek",
        )
        val freqs = listOf("272.700", "253.700", "308.800", "306.300", "371.775", "384.875", "306.300", "308.800", "253.700", "363.100", "257.800", "229.700", "356.850", "339.750")
        val names = listOf("Base ops", "DEP Ground", "DEP Tower", "DEP Approach", "AWACS Check-in", "Tactical", "ARR Approach", "ARR Tower", "ARR Ground", "ALT Approach", "ALT Tower", null, "Tanker", null)
        val dtc = Dtc(
            modified = 1_000,
            steerpoints = listOf(DtcPoint(15, 1569052.125, 988445.4375, 1913.0, -1, true, "EW Site Puryu-gogae 1")),
            uhf = freqs.mapIndexed { i, f -> Preset(i + 1, f, names[i]) },
            vhf = listOf(Preset(3, "122.100", "Tower"), Preset(11, "122.500", "ALT Tower")),
            iff = mapOf("Mode1 Code" to "43", "Mode2 Code" to "6530", "Mode3A Code" to "0554", "Mode4 Key" to "1"),
            comm = DtcComm(comm1 = 1, comm2 = 6),
        )
        val route = MissionRoute(file = "Auto Save.ini", save = "Auto Save.cam", kind = CampKind.CAMPAIGN, modified = 900, steerpoints = ROUTE)
        return MissionData(version = "made-up", briefingModified = 1, briefing = brief, dtc = dtc, route = route)
    }

    /**
     * The plan a pilot would send from the Planner: every kind of mark these views give, on this mission's own
     * geometry (positions are offsets from its steerpoints).
     */
    private fun planFor(base: MissionData, theater: String): PlanOverlay {
        val r = base.route!!.steerpoints.associateBy { it.n }
        val s3 = r.getValue(3); val s4 = r.getValue(4); val s5 = r.getValue(5)
        val disk = base.dtc ?: Dtc()
        val uhf = disk.uhf.map {
            when (it.ch) {
                13 -> it.copy(freq = "290.000", comment = "Package")      // the tanker's preset now holds another frequency
                14 -> it.copy(freq = "356.850", comment = "Copper2 AAR")  // and the tanker's frequency moved here
                else -> it
            }
        }.let { list -> if (list.none { it.ch == 14 }) list + Preset(14, "356.850", "Copper2 AAR") else list }
        val planDtc = disk.copy(
            modified = 2_000,
            steerpoints = disk.steerpoints.filter { it.n != 5 } +
                DtcPoint(5, s5.x + 30_000.0, s5.y - 20_000.0, 20_000.0, 0, name = "Nav") +
                DtcPoint(23, (s3.x + s4.x) / 2 - 25_000.0, (s3.y + s4.y) / 2 - 15_000.0, 0.0, -1, true, "SA-3 site"),
            ppts = listOf(
                DtcPpt(56, (s3.x + s4.x) / 2 - 25_000.0, (s3.y + s4.y) / 2 - 15_000.0, 0.0, 12.0, "SA-3", code = "SA3", rangeFt = 72913.39),
                DtcPpt(57, s3.x - 60_000.0, s3.y + 20_000.0, 0.0, 0.0, "AWACS", code = "AWC", rangeFt = 0.1, marker = true),
            ),
            lines = listOf(DtcPoint(0, s3.x + 15_000, s3.y - 25_000, line = 1), DtcPoint(1, s3.x + 30_000, s3.y + 5_000, line = 1), DtcPoint(2, s4.x + 35_000, s4.y + 20_000, line = 1)),
            uhf = uhf,
            iff = disk.iff + ("Mode3A Code" to "4321"),
            comm = DtcComm(comm1 = 5, comm2 = disk.comm?.comm2, tacan = "94X", ils = "109.30", ilsCrs = 92),
            laserTgp = 1688, laserLst = 1688, bingoLbs = 3000, alowFt = 500, mslFloorFt = 10_000,
            ewsNames = listOf("MAN 1", "MAN 2", "MAN 3", "MAN 4", "SLOW", "FAST"),
        )
        val attack = AttackOverlay(
            "TOSS", listOf(AttackCue("TGT", s3.x, s3.y, AttackCue.Kind.TARGET), AttackCue("IP", s3.x - 50_000, s3.y + 40_000, AttackCue.Kind.IP), AttackCue("PUP", s3.x - 18_000, s3.y + 14_000, AttackCue.Kind.PUP)),
            runIn = listOf(s3.x - 50_000 to s3.y + 40_000, s3.x - 18_000 to s3.y + 14_000, s3.x to s3.y), theater = theater,
        )
        val b = base.briefing
        return PlanOverlay(
            id = System.currentTimeMillis() - 3_600_000, source = PlanSource.PLANNER, from = "Browser", theater = theater, callsign = b?.overview?.flight,
            packageId = b?.overview?.packageId, briefing = b?.generated, dtc = planDtc, attack = attack, canUndo = true,
        )
    }

    // ------------------------------------------------------------------ the rules behind the marks

    private fun rules(
        base: MissionData, withPlan: MissionData, merged: MergedMission, set: AirportSet?, geo: com.bmscompanion.app.data.GeoLayers?,
        check: (String, Boolean, String) -> Unit, sb: StringBuilder,
    ) {
        fun c(name: String, ok: Boolean, detail: String = "") = check(name, ok, detail)
        val comms = merged.briefing?.comms.orEmpty()
        sb.appendLine("== Comms")
        val notes = comms.associate { it.agency to presetNote(it, merged.presets) }
        val tankerRow = comms.firstOrNull { it.uhfCh == 13 }
        c("a ladder row on preset 13 gets \"UHF preset 13 is now 290.000\"", tankerRow != null && notes[tankerRow.agency]?.startsWith("UHF preset 13 is now 290.000") == true, "${tankerRow?.agency}: ${tankerRow?.let { notes[it.agency] }}")
        c("rows on presets the plan did not change get no note", comms.filter { it.uhfCh != 13 && it.vhfCh != 13 }.all { notes[it.agency] == null },
            notes.filterValues { it != null }.keys.joinToString())
        c("the ladder is the briefing's, unchanged", merged.briefing?.comms == base.briefing?.comms)
        val p13 = merged.presets.firstOrNull { it.band == "UHF" && it.ch == 13 }
        val p14 = merged.presets.firstOrNull { it.band == "UHF" && it.ch == 14 }
        c("presets 13 and 14 are the plan's, marked, with the cartridge's frequency kept", p13?.changed == true && p13.notInJet && p13.was == "356.850" && p14?.changed == true && p14.freq == "356.850",
            "13 ${p13?.freq} was ${p13?.was}; 14 ${p14?.freq} was ${p14?.was}")
        c("a preset the plan left alone is not marked", merged.presets.filter { it.ch !in setOf(13, 14) && it.band == "UHF" }.none { it.changed })
        val radio = radioSettings(merged)
        c("TACAN and ILS rows: the plan's, and marked as WDP keys not tested in 4.38.1", radio.any { it.key == "tacan" && it.value == "94X" && it.untested && it.source == PlanItemSource.PLAN } &&
            radio.any { it.key == "ils" && it.value.startsWith("109.30") && it.untested }, radio.joinToString { "${it.key}=${it.value}${if (it.untested) " (untested)" else ""}" })
        c("the UHF preset tuned at load is the plan's (5)", radio.any { it.key == "comm1" && it.value.startsWith("preset 5") && it.source == PlanItemSource.PLAN })
        val noPlan = PlanMerge.merge(base)
        c("with no plan nothing is marked", noPlan.presets.none { it.changed } && noPlan.briefing?.comms.orEmpty().none { presetNote(it, noPlan.presets) != null })

        sb.appendLine("== Support")
        val refuel = refuelStation(merged)
        val s7 = base.route?.steerpoints?.firstOrNull { it.action == 4 }
        c("your route's Refuel steerpoint (action 4) is the tanker station", refuel != null && s7 != null && hypot(refuel.x - s7.x, refuel.y - s7.y) < 1.0 && refuel.fromText.endsWith("steerpoint ${s7.n}"),
            refuel?.fromText ?: "none")
        val uhf = merged.presets.filter { it.band == "UHF" }.map { Preset(it.ch, it.freq, it.comment) }
        val changed = merged.presets.filter { it.band == "UHF" && it.changed }.map { it.ch }.toSet()
        val stations = plannedStations(merged, set, geo)
        val assets = supportAssets(merged.briefing, null, emptyList(), emptyList(), merged.saveBullseye ?: (1_300_000.0 to 1_500_000.0), uhf, stations, refuel, changed)
        val tanker = assets.firstOrNull { it.role == "Tanker" }
        sb.appendLine("   " + assets.joinToString(" | ") { "${it.role} ${it.callsign} ch ${it.uhfCh}${if (it.uhfChFromPlan) " (moved)" else ""} station ${it.station?.fromText} loc ${it.stationLoc}" })
        c("the tanker's station is the Refuel steerpoint, not the sentence", tanker?.station?.fromText?.contains("Refuel steerpoint") == true, tanker?.station?.fromText ?: "none")
        c("the tanker's channel follows its frequency to the plan's preset 14", tanker?.uhfCh == 14 && tanker.uhfChFromPlan, "ch ${tanker?.uhfCh}")
        c("the station is given from bullseye", tanker?.stationLoc?.matches(Regex("\\d{3}/\\d+")) == true, tanker?.stationLoc ?: "none")
        val awacs = assets.firstOrNull { it.role == "AWACS" }
        c("the AWACS station comes from the briefing's sentence (a town of this theater)", awacs?.station?.fromText?.contains("northeast of", true) == true, awacs?.station?.fromText ?: "not resolved")
        // without BMS's route (not believed) and without the plan: the sentence places the tanker
        val bare = PlanMerge.merge(base.copy(route = null))
        val bareAssets = supportAssets(bare.briefing, null, emptyList(), emptyList(), null, bare.presets.filter { it.band == "UHF" }.map { Preset(it.ch, it.freq, it.comment) }, plannedStations(bare, set, geo), refuelStation(bare))
        val bareTanker = bareAssets.firstOrNull { it.role == "Tanker" }
        c("with no route the tanker is placed by the sentence, on its briefed preset 13", bareTanker?.station?.fromText?.contains("southwest of", true) == true && bareTanker.uhfCh == 13 && !bareTanker.uhfChFromPlan,
            "${bareTanker?.station?.fromText} ch ${bareTanker?.uhfCh}")

        sb.appendLine("== Dashboard and boards")
        c("the Dashboard offers a Plan card", DashCard.entries.any { it == DashCard.PLAN })
        val rings = preplannedPoints(merged).filter { !it.marker && it.rangeNm > 0 }
        c("Threat rings: the plan's SA-3 (marked), not the AWACS marker", rings.any { it.n == 56 && it.fromPlan && it.notInJet } && rings.none { it.n == 57 }, rings.joinToString { "${it.n} ${it.name}" })
        val printed = merged.briefing?.steerpoints.orEmpty().map { it.n }.toSet()
        val flight = merged.allSteerpoints.filter { it.n in printed || it.planRow || (printed.isEmpty() && it.onRoute) }
        c("FLIGHT board: the briefing's rows, then the plan's own (STPT 23, P*)", flight.map { it.n } == printed.sorted() + 23 && flight.last().planRow && flight.last().notInJet, flight.joinToString { "${it.n}${if (it.planRow) "P" else ""}" })
        c("FLIGHT board: STPT 5 moved by the plan is marked P*", flight.firstOrNull { it.n == 5 }?.let { it.source == PlanItemSource.PLAN && it.notInJet } == true)
        c("FLIGHT board: the recon bank (15-22) stays off the flight plan", flight.none { it.n in 15..22 })
        c("the map boards draw the plan's attack from the mission", merged.attack?.page == "TOSS")
    }

    // ------------------------------------------------------------------ pictures

    private fun pictures(dir: File, th: Theater, set: AirportSet?, base: MissionData, withPlan: MissionData, check: (String, Boolean, String) -> Unit, sb: StringBuilder) {
        val env = { nav: androidx.navigation.NavHostController -> MissionEnv(nav, th, set) }
        // the views read MissionLink's flows: the test puts its mission there (nothing polls: no link is set up)
        setFlow("_state", LinkState.Online("test"))
        setFlow("_live", null)
        setFlow("_contacts", null)
        try {
            setFlow("_mission", base)
            shot(dir, "comms-no-plan", 1400, 1900, 1f, sb, check) { MissionCommsPane(env(rememberNavController())) }
            inkCheck(dir, "comms-no-plan", INK_SCREEN, expect = false, check)

            setFlow("_mission", withPlan)
            shot(dir, "comms", 1400, 2150, 1f, sb, check) { MissionCommsPane(env(rememberNavController())) }
            inkCheck(dir, "comms", INK_SCREEN, expect = true, check)
            shot(dir, "comms-phone", 400, 3500, 2f, sb, check) { MissionCommsPane(env(rememberNavController())) }
            inkCheck(dir, "comms-phone", INK_SCREEN, expect = true, check)

            // the Dashboard's page 5, laid out with the cards the plan touches
            val cards = listOf(
                DashItem(DashCard.MISSION), DashItem(DashCard.PLAN), DashItem(DashCard.STEERPOINTS), DashItem(DashCard.THREATS),
                DashItem(DashCard.PRESETS), DashItem(DashCard.COMMS, DashWidth.M), DashItem(DashCard.SUPPORT, DashWidth.M),
                DashItem(DashCard.FUEL), DashItem(DashCard.TIME),
            )
            val was = DashLayouts.page
            DashLayouts.set("wide", 4, cards)
            DashLayouts.set("narrow", 4, cards)
            DashLayouts.choose(4)
            try {
                shot(dir, "dashboard", 1500, 2700, 1f, sb, check) { MissionDashboardPane(env(rememberNavController()), MapState(), null, {}, {}) }
                inkCheck(dir, "dashboard", INK_SCREEN, expect = true, check)
                shot(dir, "dashboard-phone", 400, 4200, 2f, sb, check) { MissionDashboardPane(env(rememberNavController()), MapState(), null, {}, {}) }
                inkCheck(dir, "dashboard-phone", INK_SCREEN, expect = true, check)
            } finally {
                DashLayouts.choose(was)
            }

            // the VR boards: paper, laid out at "Normal" print on a 1000 x 1600 board, as the headset's browser does
            boards(true)
            try {
                val slot = BoardSlot(1, "map", mapOf("follow" to "0"))
                board(dir, "board-map-wide", BoardKind.MAP, slot, 1, env, sb, check)
                inkCheck(dir, "board-map-wide", INK_SCREEN, expect = true, check)   // the map is drawn in screen inks
                board(dir, "board-map-route", BoardKind.MAP, slot, 2, env, sb, check)
                inkCheck(dir, "board-map-route", INK_SCREEN, expect = true, check)
                board(dir, "board-flight", BoardKind.FLIGHT, BoardSlot(2, "flight"), 0, env, sb, check)
                inkCheck(dir, "board-flight", INK_PAPER, expect = true, check)
                board(dir, "board-comms", BoardKind.COMMS, BoardSlot(3, "comms"), 0, env, sb, check)
                inkCheck(dir, "board-comms", INK_PAPER, expect = true, check)
                // the ladder runs onto a second sheet, where the table of the presets the plan changed is
                board(dir, "board-comms-2", BoardKind.COMMS, BoardSlot(3, "comms"), 1, env, sb, check)
                board(dir, "board-support", BoardKind.SUPPORT, BoardSlot(4, "support"), 0, env, sb, check)
                board(dir, "board-threats", BoardKind.THREATS, BoardSlot(5, "threats"), 0, env, sb, check)
                inkCheck(dir, "board-threats", INK_PAPER, expect = true, check)
                board(dir, "board-briefing", BoardKind.BRIEFING, BoardSlot(6, "briefing"), 0, env, sb, check)
                // and the flight board with no plan: no mark at all
                setFlow("_mission", base)
                board(dir, "board-flight-no-plan", BoardKind.FLIGHT, BoardSlot(2, "flight"), 0, env, sb, check)
                inkCheck(dir, "board-flight-no-plan", INK_PAPER, expect = false, check)
            } finally {
                boards(false)
            }
        } finally {
            setFlow("_mission", null)
            setFlow("_state", LinkState.Idle)
        }
    }

    /** The board's skin, as the browser's /kneeboard page sets it — without writing the setting to the prefs file. */
    @Suppress("UNCHECKED_CAST")
    internal fun boards(on: Boolean) {
        val f = Kneeboard::class.java.getDeclaredField("on\$delegate")
        f.isAccessible = true
        (f.get(Kneeboard) as MutableState<Boolean>).value = on
        HudSkin.board = on
        HudSkin.night = false
    }

    internal fun board(dir: File, name: String, kind: BoardKind, slot: BoardSlot, page: Int, env: (androidx.navigation.NavHostController) -> MissionEnv, sb: StringBuilder, check: (String, Boolean, String) -> Unit) {
        val wPx = 1000
        val hPx = 1600
        val d = Kneeboard.densityFor(wPx.toFloat(), 1f)
        shot(dir, name, (wPx / d).toInt(), (hPx / d).toInt(), d, sb, check) {
            KneeboardFrame(enabled = true, chrome = false, sections = emptyList(), current = "") {
                BoardSheetPreview(kind, slot, env(rememberNavController()), page)
            }
        }
    }

    /**
     * Counts the pixels of the Planner's ink in a picture: a view that marks the plan has some, one with no plan has
     * none. The tolerance is the anti-aliasing of small type; the threshold a few glyphs' worth.
     */
    internal fun inkCheck(dir: File, name: String, ink: Int, expect: Boolean, check: (String, Boolean, String) -> Unit) {
        val img = runCatching { ImageIO.read(File(dir, "$name.png")) }.getOrNull()
        if (img == null) { check("$name: ink measured", false, "no picture"); return }
        val tr = (ink shr 16) and 0xFF; val tg = (ink shr 8) and 0xFF; val tb = ink and 0xFF
        var n = 0
        for (y in 0 until img.height) for (x in 0 until img.width) {
            val p = img.getRGB(x, y)
            if (abs(((p shr 16) and 0xFF) - tr) < 28 && abs(((p shr 8) and 0xFF) - tg) < 28 && abs((p and 0xFF) - tb) < 28) n++
        }
        if (expect) check("$name: the plan's marks are drawn (PlanInk pixels)", n >= 40, "$n pixels")
        else check("$name: nothing in the plan's ink without a plan", n < 40, "$n pixels")
    }

    @Suppress("UNCHECKED_CAST")
    internal fun setFlow(field: String, value: Any?) {
        val f = MissionLink::class.java.getDeclaredField(field)
        f.isAccessible = true
        (f.get(MissionLink) as MutableStateFlow<Any?>).value = value
    }

    internal fun shot(dir: File, name: String, wDp: Int, hDp: Int, density: Float, sb: StringBuilder, check: (String, Boolean, String) -> Unit, content: @Composable () -> Unit) {
        val scene = ImageComposeScene((wDp * density).toInt(), (hDp * density).toInt(), Density(density)) {
            CompositionLocalProvider(LocalConfiguration provides Configuration(wDp, hDp)) {
                BmsTheme {
                    Box(Modifier.fillMaxSize().background(Hud.Bg)) { content() }
                }
            }
        }
        try {
            var t = 0L
            repeat(60) { scene.render(t); t += 50_000_000; Thread.sleep(40) }   // map tiles, the threat list and the towns load asynchronously
            val file = File(dir, "$name.png")
            file.writeBytes(scene.render(t).encodeToData(EncodedImageFormat.PNG)!!.bytes)
            check("picture $name.png (${wDp}×$hDp dp at ${"%.2f".format(density)})", file.length() > 1000, "${file.length()} bytes")
        } catch (e: Throwable) {
            check("picture $name", false, "${e::class.simpleName}: ${e.message}")
        } finally {
            scene.close()
        }
    }
}
