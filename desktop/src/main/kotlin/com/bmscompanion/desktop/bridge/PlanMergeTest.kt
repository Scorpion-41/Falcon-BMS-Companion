package com.bmscompanion.desktop.bridge

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.rememberNavController
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.mission.AttackCue
import com.bmscompanion.app.data.mission.AttackOverlay
import com.bmscompanion.app.data.mission.BannerKind
import com.bmscompanion.app.data.mission.BriefOverview
import com.bmscompanion.app.data.mission.BriefSteerpoint
import com.bmscompanion.app.data.mission.Briefing
import com.bmscompanion.app.data.mission.CampFlight
import com.bmscompanion.app.data.mission.CampFlightRow
import com.bmscompanion.app.data.mission.CampKind
import com.bmscompanion.app.data.mission.CampPlace
import com.bmscompanion.app.data.mission.CampRef
import com.bmscompanion.app.data.mission.CommEntry
import com.bmscompanion.app.data.mission.Dtc
import com.bmscompanion.app.data.mission.DtcComm
import com.bmscompanion.app.data.mission.DtcPoint
import com.bmscompanion.app.data.mission.DtcPpt
import com.bmscompanion.app.data.mission.Live
import com.bmscompanion.app.data.mission.LinkState
import com.bmscompanion.app.data.mission.MergedMission
import com.bmscompanion.app.data.mission.MissionData
import com.bmscompanion.app.data.mission.MissionLink
import com.bmscompanion.app.data.mission.MissionRoute
import com.bmscompanion.app.data.mission.NavPoint
import com.bmscompanion.app.data.mission.PackageFlight
import com.bmscompanion.app.data.mission.PlanItemSource
import com.bmscompanion.app.data.mission.PlanMerge
import com.bmscompanion.app.data.mission.PlanOverlay
import com.bmscompanion.app.data.mission.PlanSource
import com.bmscompanion.app.data.mission.PlanState
import com.bmscompanion.app.data.mission.Preset
import com.bmscompanion.app.data.mission.TextBlock
import com.bmscompanion.app.ui.components.MapState
import com.bmscompanion.app.ui.screens.mission.MissionBriefingPane
import com.bmscompanion.app.ui.screens.mission.MissionEnv
import com.bmscompanion.app.ui.screens.mission.MissionMapPane
import com.bmscompanion.app.ui.screens.mission.airbases
import com.bmscompanion.app.ui.screens.mission.resolveTheater
import com.bmscompanion.app.ui.screens.mission.steerpoints
import com.bmscompanion.app.ui.theme.BmsTheme
import com.bmscompanion.app.ui.theme.Hud
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.math.hypot

/**
 * `--planmergetest out.txt [<picture folder>] [<fixture root>]`: the merge rules of Send to Mission (R3-IMPORT P3, R3-PLAN
 * A11-A12) in the shared code every device runs, and the Map, the Briefing and the banner drawn with a plan.
 *
 * The rules are checked on a made-up mission whose every slot is chosen to tell the sources apart: each briefing row
 * and field unchanged after the merge; the plan's own steerpoints appended as PLAN rows; JET > PLAN > CARTRIDGE >
 * ROUTE slot by slot; a slot the plan cleared reported, not dropped; the five cases of the A12 table; the route line
 * joining flight-plan points only; PPT markers and labels; lines grouped and never joined; presets and the Plan card.
 *
 * With a fixture root (a copy of the BMS folder: `User/Briefings/briefing.txt`, the pilot's cartridge beside its `.pop`,
 * `Data/Campaign/Auto Save.ini`), the real 4.38.1 files go through the same merge: the route is BMS's mission file,
 * the recon bank 15-22 is not on it (the old leg to Pulandian), and the home field is STPT 1. With a picture folder too,
 * the Map (whole route, and close in), the Briefing, the "From your save" Briefing and the banner's states are drawn
 * headless with a plan. Nothing is written anywhere but the report and the pictures.
 */
object PlanMergeTest {
    fun run(pictures: File?, more: List<String>): String {
        val sb = StringBuilder()
        var fails = 0
        fun check(name: String, ok: Boolean, detail: String = "") {
            if (!ok) fails++
            sb.appendLine((if (ok) "PASS " else "FAIL ") + name + if (detail.isNotEmpty()) " — $detail" else "")
        }
        sb.appendLine("--planmergetest: PlanMerge (app/.../data/mission/PlanMerge.kt), shared code")
        sb.appendLine()
        try {
            rules(::check, sb)
        } catch (e: Throwable) {
            check("rules ran without an exception", false, "${e::class.simpleName}: ${e.message}")
        }
        val root = more.firstOrNull()?.let(::File)
        var real: MissionData? = null
        if (root != null) {
            sb.appendLine()
            sb.appendLine("== the fixture's real files ($root)")
            try {
                real = fixture(root, ::check, sb)
            } catch (e: Throwable) {
                check("fixture read without an exception", false, "${e::class.simpleName}: ${e.message}")
            }
        } else sb.appendLine("\n(no fixture root given: the real-file part and the pictures are skipped)")
        if (pictures != null && real != null) {
            sb.appendLine()
            sb.appendLine("== pictures ($pictures)")
            try {
                pictures(pictures, real, ::check, sb)
            } catch (e: Throwable) {
                check("pictures drawn without an exception", false, "${e::class.simpleName}: ${e.message}")
            }
        }
        sb.appendLine()
        sb.appendLine(if (fails == 0) "ALL PASS" else "FAIL: $fails check(s) failed")
        return sb.toString()
    }

    // ------------------------------------------------------------------ the made-up mission

    private val rows = listOf(
        BriefSteerpoint(1, "Takeoff", "04:21:00z", null, null, "---", "2000", "Takeoff", null, null),
        BriefSteerpoint(2, "--", "04:30:00z", "40", "310", "450", "20.0M", "Nav", "Wedge", null),
        BriefSteerpoint(3, "Attack", "04:34:15z", "30", "300", "480", "20.0M", "Attack", "Wedge", "Target area"),
        BriefSteerpoint(4, "--", "04:45:00z", "30", "120", "450", "20.0M", "Nav", null, null),
        BriefSteerpoint(5, "Land", "05:10:00z", "50", "120", "300", "2000", "Land", null, "Alternate Kunsan"),
    )
    private val briefing = Briefing(
        generated = "9/25/2026 22:40:54",
        overview = BriefOverview(flight = "Viper1", mission = "Strike", packageId = "1234", packageType = "Strike"),
        situation = "Hostile forces massing.",
        `package` = listOf(PackageFlight(callsign = "Viper1", flightId = "1235", primary = true, role = "Strike", aircraft = "F-16CM-50", count = 2)),
        threats = listOf(TextBlock("Surface-to-Air Threats:", listOf("SA-3 near the target"))),
        steerpoints = rows,
        comms = listOf(CommEntry(agency = "Dep Osan Tower", callsign = "Osan Tower", uhf = "308.800", uhfCh = 2)),
        roe = listOf("Weapons free"),
        alternate = "Kunsan",
    )
    private fun pt(n: Int, x: Double, y: Double, action: Int, name: String? = null) = DtcPoint(n = n, x = x, y = y, altFt = 100.0, action = action, isTarget = action == -1, name = name)
    private val disk = Dtc(
        modified = 1_000,
        steerpoints = listOf(pt(4, 400_000.0, 400_000.0, 0), pt(5, 500_000.0, 500_000.0, 7), pt(15, 1_500_000.0, 1_500_000.0, -1, "Recon A"), pt(16, 1_600_000.0, 1_600_000.0, -1, "Recon B")),
        ppts = listOf(
            DtcPpt(56, 300_000.0, 300_000.0, 0.0, 12.0, "SA3", code = "SA3", rangeFt = 72913.39),
            DtcPpt(57, 310_000.0, 310_000.0, 0.0, 0.0, "AWC", code = "AWC", rangeFt = 0.1, marker = true),
            // as an older PC sends it: the code in name, the range in nm, a 0.1-ft marker read as 0.1 nm
            DtcPpt(58, 320_000.0, 320_000.0, 0.0, 50.0, "10"),
            DtcPpt(60, 330_000.0, 330_000.0, 0.0, 0.1, "TNK"),
        ),
        lines = listOf(pt(0, 10_000.0, 10_000.0, 0), pt(1, 11_000.0, 11_000.0, 0), pt(2, 12_000.0, 12_000.0, 0), pt(6, 20_000.0, 20_000.0, 0), pt(7, 21_000.0, 21_000.0, 0), DtcPoint(n = 13)),
        uhf = listOf(Preset(1, "225.000", "Guard"), Preset(3, "270.100", "Package")),
        vhf = listOf(Preset(1, "121.500")),
    )
    private val route = MissionRoute(
        file = "Test.ini", save = "Test.cam", kind = CampKind.CAMPAIGN, modified = 900,
        steerpoints = listOf(pt(1, 100_000.0, 100_000.0, 1), pt(2, 200_000.0, 200_000.0, 0), pt(3, 300_000.0, 300_000.0, 12), pt(4, 410_000.0, 410_000.0, 0), pt(5, 510_000.0, 510_000.0, 7), pt(15, 1_500_000.0, 1_500_000.0, -1)),
        bullseyeX = 777_000.0, bullseyeY = 888_000.0,
    )
    private val planDtc = disk.copy(
        modified = 2_000,
        steerpoints = listOf(
            pt(4, 400_000.0, 400_000.0, 0),            // the same as the cartridge
            pt(5, 505_000.0, 505_000.0, 7),            // moved 7,000 ft
            pt(6, 600_000.0, 600_000.0, 0),            // new: a plan row on the route
            pt(15, 1_500_000.0, 1_500_000.0, -1, "Recon A"),
            pt(20, 2_000_000.0, 2_000_000.0, -1, "New target"), // new: a plan target, not on the route
            // 16 cleared
        ),
        ppts = listOf(
            DtcPpt(56, 300_000.0, 300_000.0, 0.0, 12.0, "SA-3", code = "SA3", rangeFt = 72913.39),
            DtcPpt(57, 310_000.0, 310_000.0, 0.0, 0.0, "AWACS", code = "AWC", rangeFt = 0.1, marker = true),
            DtcPpt(59, 340_000.0, 340_000.0, 0.0, 50.0, "SA-10", code = "10", rangeFt = 303805.79),
            // 58 and 60 cleared
        ),
        lines = listOf(pt(0, 10_000.0, 10_000.0, 0), pt(1, 11_000.0, 11_000.0, 0), pt(2, 12_000.0, 12_000.0, 0), pt(12, 30_000.0, 30_000.0, 0), pt(13, 31_000.0, 31_000.0, 0), pt(14, 32_000.0, 32_000.0, 0)),
        uhf = listOf(Preset(1, "225.000", "Guard"), Preset(3, "251.000", "Package")),
        laserTgp = 1688, laserLst = 1688, bingoLbs = 3000, alowFt = 500, mslFloorFt = 10_000,
        ewsNames = listOf("MAN 1", "MAN 2", "MAN 3", "MAN 4", "SLOW", "FAST"),
        comm = DtcComm(comm1 = 3, tacan = "94X"),
    )
    private val plan = PlanOverlay(
        id = 5_000, source = PlanSource.PLANNER, from = "Android", theater = "korea", callsign = "Viper1", packageId = "1234",
        briefing = briefing.generated, dtc = planDtc, notInJet = listOf("UHF 3"),
        attack = AttackOverlay("TOSS", listOf(AttackCue("TGT", 300_000.0, 300_000.0), AttackCue("IP", 280_000.0, 290_000.0, AttackCue.Kind.IP)), theater = "korea"),
    )
    private val data = MissionData(version = "t", briefingModified = 3_000, briefing = briefing, dtc = disk, route = route, plan = plan)

    private fun rules(check: (String, Boolean, String) -> Unit, sb: StringBuilder) {
        fun c(name: String, ok: Boolean, detail: String = "") = check(name, ok, detail)
        // ---- no plan: the baseline
        val base = PlanMerge.merge(data.copy(plan = null))
        sb.appendLine("== no plan (briefing + cartridge + BMS's mission file)")
        c("baseline: the briefing is the printed one, unchanged", base.briefing == briefing)
        c("baseline: slot sources ROUTE/ROUTE/ROUTE/CARTRIDGE/CARTRIDGE for STPT 1-5",
            base.allSteerpoints.filter { it.n in 1..5 }.map { it.source } == listOf(PlanItemSource.ROUTE, PlanItemSource.ROUTE, PlanItemSource.ROUTE, PlanItemSource.CARTRIDGE, PlanItemSource.CARTRIDGE),
            base.allSteerpoints.filter { it.n in 1..5 }.joinToString { "${it.n}=${it.source}" })
        c("baseline: the route line joins the five briefing rows only", base.steerpoints.map { it.n } == listOf(1, 2, 3, 4, 5), base.steerpoints.joinToString { "${it.n}" })
        c("baseline: the recon bank (15, 16) is marked but not joined", base.allSteerpoints.filter { it.n >= 15 }.all { !it.onRoute && it.isTarget && it.hasPos })
        c("baseline: Targets card = STPT 15, 16", base.targets.map { it.n } == listOf(15, 16), base.targets.joinToString { "${it.n}" })
        c("baseline: nothing is marked PLAN or NOT IN JET", base.allSteerpoints.none { it.source == PlanItemSource.PLAN || it.notInJet } && base.ppts.none { it.notInJet } && base.bannerKind == null)
        val p57 = base.ppts.first { it.n == 57 }
        val p58 = base.ppts.first { it.n == 58 }
        val p60 = base.ppts.first { it.n == 60 }
        c("PPT: AWC is a marker, no ring, labelled AWACS", p57.marker && p57.rangeFt == 0.0 && p57.name == "AWACS", "$p57")
        c("PPT: an older PC's '10' / 50 nm reads SA-10, a 50 nm ring", p58.name == "SA-10" && !p58.marker && kotlin.math.abs(p58.rangeNm - 50.0) < 0.01, "$p58")
        c("PPT: an older PC's TNK at 0.1 nm is a marker (was a 0.1-nm ring)", p60.marker && p60.name == "Tanker", "$p60")
        c("PPT: SA3 labelled SA-3, 12 nm", base.ppts.first { it.n == 56 }.let { it.name == "SA-3" && kotlin.math.abs(it.rangeNm - 12.0) < 0.01 })
        c("lines: two lines of 3 and 2 points, the zero point left out", base.lines.map { l -> l.first().line to l.size } == listOf(1 to 3, 2 to 2), base.lines.joinToString { "L${it.first().line}×${it.size}" })
        c("bullseye from the save's header before 3D", base.saveBullseye == (777_000.0 to 888_000.0))
        c("home = STPT 1 (the take-off), not the first cartridge point", base.home?.n == 1 && base.home?.x == 100_000.0)

        // ---- the plan, before 3D
        val m = PlanMerge.merge(data)
        sb.appendLine("== plan applied, before 3D")
        c("briefing object unchanged by the merge", m.briefing == briefing && m.briefing === briefing)
        val rowsKept = rows.all { r ->
            val s = m.allSteerpoints.firstOrNull { it.n == r.n }
            s != null && s.desc == r.desc && s.time == r.time && s.cas == r.cas && s.altText == r.alt && s.actionText == r.action && s.comments == r.comments
        }
        c("every briefing row's desc/time/CAS/alt/action/comments unchanged", rowsKept)
        val s5 = m.allSteerpoints.first { it.n == 5 }
        c("STPT 5 moved in the plan: PLAN, NOT IN JET, shown at the plan's point, a line to the cartridge's",
            s5.source == PlanItemSource.PLAN && s5.notInJet && s5.x == 505_000.0 && s5.jetX == 500_000.0, "$s5")
        val s4 = m.allSteerpoints.first { it.n == 4 }
        c("STPT 4 the same in plan and cartridge: CARTRIDGE, in the jet", s4.source == PlanItemSource.CARTRIDGE && !s4.notInJet)
        val s6 = m.allSteerpoints.first { it.n == 6 }
        c("STPT 6 only in the plan: appended as a PLAN row, on the route, titled by its action", s6.planRow && s6.onRoute && s6.source == PlanItemSource.PLAN && s6.title == "Nav" && s6.notInJet, "$s6")
        val s20 = m.allSteerpoints.first { it.n == 20 }
        c("STPT 20 a plan target: a PLAN row, not on the route, in the Targets card", s20.planRow && !s20.onRoute && m.targets.any { it.n == 20 && it.source == PlanItemSource.PLAN })
        c("route line = rows 1-5 then the plan's STPT 6", m.steerpoints.map { it.n } == listOf(1, 2, 3, 4, 5, 6), m.steerpoints.joinToString { "${it.n}" })
        val s16 = m.allSteerpoints.first { it.n == 16 }
        c("STPT 16 cleared in the plan: reported (still shown, cleared)", s16.cleared && s16.hasPos && m.targets.any { it.n == 16 && it.cleared })
        c("PPT 59 only in the plan: PLAN, NOT IN JET, SA-10", m.ppts.first { it.n == 59 }.let { it.source == PlanItemSource.PLAN && it.notInJet && it.name == "SA-10" })
        c("PPT 56 the same: CARTRIDGE", m.ppts.first { it.n == 56 }.source == PlanItemSource.CARTRIDGE)
        c("PPT 58 and 60 cleared: reported", m.ppts.filter { it.cleared }.map { it.n } == listOf(58, 60))
        c("line 3 only in the plan: PLAN, NOT IN JET; line 1 the same: CARTRIDGE; line 2 cleared",
            m.lines.map { "${it.first().line}${it.first().source.name.first()}${if (it.first().notInJet) "*" else ""}${if (it.first().cleared) "x" else ""}" } == listOf("1C", "2Cx", "3P*"),
            m.lines.joinToString { "${it.first().line}:${it.first().source}/${it.first().notInJet}/${it.first().cleared}" })
        val u3 = m.presets.first { it.band == "UHF" && it.ch == 3 }
        c("UHF 3 changed: 251.000, marked, was 270.100", u3.changed && u3.freq == "251.000" && u3.was == "270.100" && u3.notInJet)
        c("UHF 1 unchanged: not marked", m.presets.first { it.band == "UHF" && it.ch == 1 }.let { !it.changed && it.source == PlanItemSource.CARTRIDGE })
        c("Plan card: laser, bingo, ALOW, MSL floor, EWS, TACAN from the plan", listOf("laserTgp", "bingo", "alow", "mslFloor", "ews", "tacan").all { k -> m.settings.any { it.key == k && it.source == PlanItemSource.PLAN } },
            m.settings.joinToString { "${it.key}=${it.value}" })
        c("Plan card: the WDP-only keys carry 'not tested'", m.settings.filter { it.key in setOf("laserTgp", "bingo", "tacan") }.all { it.untested } && m.settings.first { it.key == "ews" }.untested.not())
        c("notInJet = the PC's list and the client's marks", listOf("STPT 5", "STPT 6", "STPT 20", "PPT 59", "LINE 3", "UHF 3").all { it in m.notInJet }, m.notInJet.joinToString())
        c("attack taken from the plan", m.attack?.page == "TOSS")
        c("banner APPLIED, says what is not in the jet", m.bannerKind == BannerKind.APPLIED && m.banner!!.contains("not in the jet") && m.banner!!.contains("Save to DTC"), m.banner ?: "")
        c("the Plan chip off = the merge without the plan: no PLAN anywhere", PlanMerge.merge(data.copy(plan = null)).let { off -> off.allSteerpoints.none { it.source == PlanItemSource.PLAN } && off.attack == null && off.lines.none { l -> l.any { it.source == PlanItemSource.PLAN } } })
        c("steerpoints() (the views' list) carries the marks", steerpoints(m).first { it.n == 5 }.let { it.notInJet && it.fromPlan && it.otherX == 500_000.0 })

        // ---- in 3D: the jet wins
        val live = Live(flying = true, navPoints = listOf(
            NavPoint(1, "WP", 100_000.0, 100_000.0), NavPoint(2, "WP", 200_020.0, 200_000.0), NavPoint(3, "WP", 300_000.0, 300_000.0),
            NavPoint(4, "WP", 400_000.0, 400_000.0), NavPoint(5, "WP", 500_000.0, 500_000.0),
            NavPoint(56, "PT", 300_000.0, 300_000.0, name = "SA3", rangeNm = 12.0),
            NavPoint(31, "L1", 10_000.0, 10_000.0), NavPoint(32, "L1", 11_000.0, 11_000.0), NavPoint(33, "L1", 12_000.0, 12_000.0),
        ))
        val j = PlanMerge.merge(data, live)
        sb.appendLine("== plan applied, in 3D")
        val j5 = j.allSteerpoints.first { it.n == 5 }
        c("STPT 5 in 3D: the jet's position, NOT IN JET, the plan's drawn beside it", j5.source == PlanItemSource.JET && j5.x == 500_000.0 && j5.notInJet && j5.planX == 505_000.0, "$j5")
        c("STPT 4 in 3D: JET, in the jet", j.allSteerpoints.first { it.n == 4 }.let { it.source == PlanItemSource.JET && !it.notInJet })
        c("PPT 56 in 3D: the jet's", j.ppts.first { it.n == 56 }.source == PlanItemSource.JET)
        c("line 1 in 3D: the jet's L1", j.lines.first().first().source == PlanItemSource.JET)
        c("in 3D the save's bullseye is still offered, the views use the live one first", j.inJet)

        // ---- precedence, one slot per rung
        sb.appendLine("== precedence JET > PLAN > CARTRIDGE > ROUTE, one slot each")
        val ladder = MissionData(
            briefing = briefing,
            dtc = Dtc(steerpoints = listOf(pt(2, 2.0e5 + 100, 2.0e5, 0), pt(3, 3.0e5 + 100, 3.0e5, 0), pt(4, 4.0e5 + 100, 4.0e5, 0))),
            route = MissionRoute(steerpoints = listOf(pt(1, 1.0e5, 1.0e5, 1), pt(2, 2.0e5, 2.0e5, 0), pt(3, 3.0e5, 3.0e5, 0), pt(4, 4.0e5, 4.0e5, 0))),
            plan = plan.copy(dtc = Dtc(steerpoints = listOf(pt(3, 3.0e5 + 200, 3.0e5, 0), pt(4, 4.0e5 + 200, 4.0e5, 0))), notInJet = emptyList()),
        )
        val lj = PlanMerge.merge(ladder, Live(flying = true, navPoints = listOf(NavPoint(4, "WP", 4.0e5 + 300, 4.0e5))))
        val got = (1..4).map { n -> lj.allSteerpoints.first { it.n == n }.let { "${it.n}:${it.source}@${(it.x - n * 1.0e5).toInt()}" } }
        sb.appendLine("   " + got.joinToString("  "))
        c("route only → ROUTE; + cartridge → CARTRIDGE; + plan → PLAN; + jet → JET, each at its own position",
            got == listOf("1:ROUTE@0", "2:CARTRIDGE@100", "3:PLAN@200", "4:JET@300"))

        // ---- the A12 table
        sb.appendLine("== A12: which briefing, applied or parked")
        val saveBrief = Briefing(overview = BriefOverview(flight = "Jaguar2", mission = "OCA", packageId = "4321"), steerpoints = listOf(BriefSteerpoint(1, "Takeoff"), BriefSteerpoint(2, "Strike")), origin = "save")
        val flight = CampFlight(row = CampFlightRow(id = "12/3", number = 4322, callsign = "Jaguar2"), packageNumber = 4321, briefing = saveBrief, home = CampPlace("airbase", name = "Kunsan AB"))
        val savePlan = plan.copy(callsign = "Jaguar2", packageId = "4321", flight = flight, ref = CampRef("Korea KTO", "My save.cam", "12/3"))
        val a = PlanMerge.merge(data)
        c("a. printed briefing for the same flight → applied over the printed briefing", a.bannerKind == BannerKind.APPLIED && a.briefing === briefing && !a.fromSave)
        val b = PlanMerge.merge(data.copy(briefing = null, plan = savePlan))
        c("b. no printed briefing, a flight from a save → the save's briefing, 'From your save'", b.briefing == saveBrief && b.fromSave && b.bannerKind == BannerKind.SAVE_ONLY && b.saveFile == "My save.cam" && b.banner!!.contains("No printed briefing"), b.banner ?: "")
        c("b. the save's flight has no comm ladder: its home base is the departure", airbases(null, b).departure == "Kunsan AB", "${airbases(null, b)}")
        val cc = PlanMerge.merge(data.copy(plan = savePlan.copy(note = "The printed briefing is older than your save.")))
        c("c. printed briefing for another flight, older (PC applied) → the save's flight, the sentence", cc.briefing == saveBrief && cc.bannerKind == BannerKind.SAVE_OLDER && cc.printed === briefing &&
            cc.banner!!.contains("older than your save") && cc.banner!!.contains("Viper1") && cc.banner!!.contains("Jaguar2"), cc.banner ?: "")
        val parked = PlanMerge.merge(data.copy(plan = savePlan.copy(state = PlanState.PARKED, note = "this briefing is for Viper1")))
        c("d. printed briefing for another flight, newer → parked: nothing merged, the banner says why",
            parked.plan == null && parked.parked != null && parked.bannerKind == BannerKind.PARKED && parked.allSteerpoints == base.allSteerpoints && parked.ppts == base.ppts && parked.banner!!.contains("set aside"), parked.banner ?: "")
        val none = PlanMerge.merge(MissionData(dtc = disk, plan = plan.copy(callsign = null, packageId = null, briefing = null)))
        c("e. no briefing and no flight → applied, cartridge items only", none.briefing == null && none.bannerKind == BannerKind.NO_BRIEFING &&
            none.targets.any { it.n == 20 } && none.ppts.any { it.n == 59 } && none.lines.isNotEmpty() && none.presets.any { it.changed }, none.banner ?: "")
        c("e. with no briefing the route line still joins the flight-plan points (not the targets)", none.steerpoints.none { it.isTarget } && none.steerpoints.any { it.n == 6 })

        // ---- the PPT label fallback
        c("labels: SA3→SA-3, 10→SA-10, AWC→AWACS, a resolved label kept", PlanMerge.pptLabel("SA3", null, 56) == "SA-3" && PlanMerge.pptLabel("10", null, 56) == "SA-10" &&
            PlanMerge.pptLabel("AWC", "AWC", 57) == "AWACS" && PlanMerge.pptLabel("SA-10", "10", 58) == "SA-10" && PlanMerge.pptLabel(null, null, 61) == "PPT 61")
    }

    // ------------------------------------------------------------------ the fixture's real files

    private fun fixture(root: File, check: (String, Boolean, String) -> Unit, sb: StringBuilder): MissionData {
        fun c(name: String, ok: Boolean, detail: String = "") = check(name, ok, detail)
        val brief = BriefingParser.parse(File(root, "User/Briefings/briefing.txt").readText(Charsets.ISO_8859_1))
        // the pilot's cartridge is the .ini beside the pilot file (.pop); its name is the callsign, never printed here
        val pop = File(root, "User/Config").listFiles { f -> f.name.endsWith(".pop", ignoreCase = true) }?.firstOrNull()
        val cart = pop?.let { File(it.parentFile, it.nameWithoutExtension + ".ini") }?.takeIf { it.isFile }
        c("fixture: the pilot's cartridge found beside the pilot file", cart != null)
        val dtc = cart?.let { DtcParser.parse(it) } ?: Dtc()
        val missionIni = File(root, "Data/Campaign/Auto Save.ini")
        val routeDtc = DtcParser.parse(missionIni)
        val route = MissionRoute(file = missionIni.name, save = "Auto Save.cam", kind = CampKind.CAMPAIGN, modified = missionIni.lastModified(),
            steerpoints = routeDtc.steerpoints, ppts = routeDtc.ppts, lines = routeDtc.lines, weaponTargets = routeDtc.weaponTargets)
        val data = MissionData(version = "fixture", briefingModified = 1, briefing = brief, dtc = dtc, route = route)
        val m = PlanMerge.merge(data)
        sb.appendLine("   briefing: ${brief.overview.flight} package ${brief.overview.packageId}, ${brief.steerpoints.size} rows; cartridge: ${dtc.steerpoints.size} points (${dtc.steerpoints.joinToString { "${it.n}" }}); mission file: ${route.steerpoints.size} points")
        sb.appendLine("   route line: " + m.steerpoints.joinToString { "${it.n} ${it.actionText ?: it.action}" })
        c("fixture: the route line is the briefing's ${brief.steerpoints.size} rows, all placed from BMS's mission file",
            m.steerpoints.map { it.n } == brief.steerpoints.map { it.n } && m.routeLine.size == brief.steerpoints.size && m.routeLine.all { it.source == PlanItemSource.ROUTE })
        c("fixture: actions 1 0 12 12 0 7 4 7 (the printed briefing's order)", m.steerpoints.map { it.action } == listOf(1, 0, 12, 12, 0, 7, 4, 7), m.steerpoints.joinToString { "${it.action}" })
        val bank = m.allSteerpoints.filter { it.n in 15..22 }
        c("fixture: the recon bank 15-22 is marked as targets and not joined (no leg to Pulandian)", bank.size == 8 && bank.all { it.isTarget && !it.onRoute }, bank.joinToString { "${it.n}:${it.name?.trim()?.take(20)}" })
        c("fixture: no point of the route line is in the Pulandian bank", m.routeLine.none { (it.name ?: "").contains("Pulandian", true) })
        val oldFirst = dtc.steerpoints.firstOrNull()
        c("fixture: home = STPT 1 (Osan's take-off), where the old Taxi page took the cartridge's first point (STPT ${oldFirst?.n})",
            m.home?.n == 1 && hypot(m.home!!.x - 1_162_752.9, m.home!!.y - 1_539_950.6) < 5.0 && oldFirst?.n != 1)
        runCatching {
            val all = runBlocking { Repo.index().theaters }
            val th = resolveTheater(all, "Korea KTO")
            val set = th?.let { runBlocking { Repo.airportSet(it.airportSet) } }
            val home = m.home
            val field = if (set != null && home != null) set.airports.minByOrNull { hypot(it.x - home.x, it.y - home.y) } else null
            c("fixture: the Taxi page's home field is Osan", field?.name?.contains("Osan", true) == true, field?.name ?: "no airport set")
        }.onFailure { c("fixture: airport set read", false, "${it::class.simpleName}: ${it.message}") }
        return data
    }

    // ------------------------------------------------------------------ pictures

    private fun pictures(dir: File, real: MissionData, check: (String, Boolean, String) -> Unit, sb: StringBuilder) {
        dir.mkdirs()
        val all = runBlocking { Repo.index().theaters }
        val th = resolveTheater(all, "Korea KTO") ?: error("no Korea theater in the app's data")
        val set = runBlocking { Repo.airportSet(th.airportSet) }
        val route = real.route!!.steerpoints.associateBy { it.n }
        val s3 = route.getValue(3)
        val s4 = route.getValue(4)
        val s5 = route.getValue(5)
        // a plan as the Planner would send it: STPT 5 moved, an SA-3 by the CAP, an AWACS marker, a three-point line,
        // UHF 3 changed, and a TOSS attack around STPT 3
        val disk = real.dtc ?: Dtc()
        val planDtc = disk.copy(
            steerpoints = disk.steerpoints.filter { it.n != 5 } + DtcPoint(5, s5.x + 30_000.0, s5.y - 20_000.0, 0.0, 0, name = "Nav"),
            ppts = listOf(
                DtcPpt(56, (s3.x + s4.x) / 2 + 20_000, (s3.y + s4.y) / 2 + 30_000, 0.0, 12.0, "SA-3", code = "SA3", rangeFt = 72913.39),
                DtcPpt(57, s3.x - 60_000, s3.y + 20_000, 0.0, 0.0, "AWACS", code = "AWC", rangeFt = 0.1, marker = true),
            ),
            lines = listOf(DtcPoint(0, s3.x + 15_000, s3.y - 25_000, line = 1), DtcPoint(1, s3.x + 30_000, s3.y + 5_000, line = 1), DtcPoint(2, s4.x + 35_000, s4.y + 20_000, line = 1)),
            uhf = disk.uhf.map { if (it.ch == 3) it.copy(freq = "251.000") else it },
            laserTgp = 1688, laserLst = 1688, bingoLbs = 3000, alowFt = 500, mslFloorFt = 10_000, ewsNames = listOf("MAN 1", "MAN 2", "MAN 3", "MAN 4", "SLOW", "FAST"),
            comm = DtcComm(comm1 = 3, tacan = "94X", ils = "109.30", ilsCrs = 92),
        )
        val tgt = AttackCue("TGT", s3.x, s3.y, AttackCue.Kind.TARGET)
        val attack = AttackOverlay(
            "TOSS", listOf(tgt, AttackCue("IP", s3.x - 50_000, s3.y + 40_000, AttackCue.Kind.IP), AttackCue("PUP", s3.x - 18_000, s3.y + 14_000, AttackCue.Kind.PUP)),
            runIn = listOf(s3.x - 50_000 to s3.y + 40_000, s3.x - 18_000 to s3.y + 14_000, s3.x to s3.y), theater = th.id,
        )
        val brief = real.briefing!!
        val plan = PlanOverlay(
            id = System.currentTimeMillis() - 3_600_000, source = PlanSource.PLANNER, from = "PC", theater = th.id, callsign = brief.overview.flight,
            packageId = brief.overview.packageId, briefing = brief.generated, dtc = planDtc, attack = attack,
            notInJet = listOf("STPT 5", "PPT 56", "PPT 57", "LINE 1", "UHF 3"), canUndo = true,
        )
        val withPlan = real.copy(plan = plan)
        val merged = PlanMerge.merge(withPlan)
        check("pictures: the plan merges (SA-3 ring, marker, line, STPT 5 not in the jet)",
            merged.ppts.any { it.n == 56 && !it.marker && it.notInJet } && merged.ppts.any { it.marker } && merged.lines.isNotEmpty() &&
                merged.allSteerpoints.first { it.n == 5 }.notInJet, merged.notInJet.joinToString())

        val env = { nav: androidx.navigation.NavHostController -> MissionEnv(nav, th, set) }
        // the views read MissionLink's flows: the test puts its mission there (nothing polls: no link is set up)
        setFlow("_state", LinkState.Online("test"))
        setFlow("_live", null)
        setFlow("_contacts", null)
        try {
            setFlow("_mission", withPlan)
            val all5 = merged.routeLine + merged.allSteerpoints.filter { it.hasPos }
            val north = all5.maxOf { it.x }; val south = all5.minOf { it.x }; val east = all5.maxOf { it.y }; val west = all5.minOf { it.y }
            // the whole route and the recon bank far to the north-west: no leg between them
            shot(dir, "map-overview", 1400, 900, 1f, sb, check) {
                val st = MapState().apply { initialized = true; flyTo((north + south) / 2, (east + west) / 2, 1.6f) }
                MissionMapPane(env(rememberNavController()), st, null, {}, {})
            }
            // close in: the SA-3 ring, the AWACS marker, line 1, STPT 5 hollow with its line, the TOSS attack
            val cx = merged.routeLine.filter { it.n in 2..5 }.map { it.x }.average()
            val cy = merged.routeLine.filter { it.n in 2..5 }.map { it.y }.average()
            shot(dir, "map-detail", 1400, 900, 1f, sb, check) {
                val st = MapState().apply { initialized = true; flyTo(cx, cy, 9f) }
                MissionMapPane(env(rememberNavController()), st, null, {}, {})
            }
            // the same with the Plan chip off: the map without the plan
            val was = com.bmscompanion.app.ui.screens.mission.MapLayers.plan
            com.bmscompanion.app.ui.screens.mission.MapLayers.plan = false
            try {
                shot(dir, "map-detail-plan-off", 1400, 900, 1f, sb, check) {
                    val st = MapState().apply { initialized = true; flyTo(cx, cy, 9f) }
                    MissionMapPane(env(rememberNavController()), st, null, {}, {})
                }
            } finally {
                com.bmscompanion.app.ui.screens.mission.MapLayers.plan = was
            }
            // the Briefing with the plan merged in, at a PC's and a phone's width
            shot(dir, "briefing-plan", 1400, 1500, 1f, sb, check) {
                Column(Modifier.fillMaxSize()) { Box(Modifier.weight(1f)) { MissionBriefingPane(env(rememberNavController())) { _, _, _ -> } } }
            }
            shot(dir, "briefing-plan-phone", 400, 1800, 2f, sb, check) {
                Column(Modifier.fillMaxSize()) { Box(Modifier.weight(1f)) { MissionBriefingPane(env(rememberNavController())) { _, _, _ -> } } }
            }
            // the WDP-only path: no printed briefing, the plan's flight from a save
            val saveBrief = brief.copy(generated = null, situation = null, comms = emptyList(), weather = null, roe = emptyList(), emergency = emptyList(), sections = emptyList(), origin = "save",
                overview = brief.overview.copy(flight = "Jaguar2", mission = "OCA Strike", packageId = "4321"))
            val flight = CampFlight(row = CampFlightRow(id = "12/3", number = 4322, callsign = "Jaguar2", f16 = true), packageNumber = 4321, briefing = saveBrief, home = CampPlace("airbase", name = "Osan AB"))
            val savePlan = plan.copy(callsign = "Jaguar2", packageId = "4321", flight = flight, ref = CampRef("Korea KTO", "Auto Save.cam", "12/3"))
            setFlow("_mission", real.copy(briefing = null, plan = savePlan))
            shot(dir, "briefing-from-save", 1400, 1300, 1f, sb, check) {
                Column(Modifier.fillMaxSize()) { Box(Modifier.weight(1f)) { MissionBriefingPane(env(rememberNavController())) { _, _, _ -> } } }
            }
            // (the banner over the Mission section went with Send to Mission in 1.3.8: the source line took its place)
        } finally {
            setFlow("_mission", null)
            setFlow("_state", LinkState.Idle)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun setFlow(field: String, value: Any?) {
        val f = MissionLink::class.java.getDeclaredField(field)
        f.isAccessible = true
        (f.get(MissionLink) as MutableStateFlow<Any?>).value = value
    }

    private fun shot(dir: File, name: String, wDp: Int, hDp: Int, density: Float, sb: StringBuilder, check: (String, Boolean, String) -> Unit, content: @Composable () -> Unit) {
        val scene = ImageComposeScene((wDp * density).toInt(), (hDp * density).toInt(), Density(density)) {
            CompositionLocalProvider(LocalConfiguration provides Configuration(wDp, hDp)) {
                BmsTheme {
                    Box(Modifier.fillMaxSize().background(Hud.Bg)) { content() }
                }
            }
        }
        try {
            var t = 0L
            repeat(60) { scene.render(t); t += 50_000_000; Thread.sleep(40) }   // the map's tiles and the data load asynchronously
            val file = File(dir, "$name.png")
            file.writeBytes(scene.render(t).encodeToData(EncodedImageFormat.PNG)!!.bytes)
            check("picture $name.png (${wDp}×$hDp dp)", file.length() > 1000, "${file.length()} bytes")
        } catch (e: Throwable) {
            check("picture $name", false, "${e::class.simpleName}: ${e.message}")
        } finally {
            scene.close()
        }
    }
}
