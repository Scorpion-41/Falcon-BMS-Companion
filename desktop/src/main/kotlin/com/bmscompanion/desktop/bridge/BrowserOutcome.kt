@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)

package com.bmscompanion.desktop.bridge

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import com.bmscompanion.app.data.mission.CampFile
import com.bmscompanion.app.data.mission.CampKind
import com.bmscompanion.app.data.mission.CampRef
import com.bmscompanion.app.data.mission.CartridgeEdit
import com.bmscompanion.app.data.mission.LinkState
import com.bmscompanion.app.data.mission.MissionLink
import com.bmscompanion.app.ui.screens.wdp.CampSort
import com.bmscompanion.app.ui.screens.wdp.CampaignBrowser
import com.bmscompanion.app.ui.screens.wdp.FlightPicker
import com.bmscompanion.app.ui.screens.wdp.PlannerMissionState
import com.bmscompanion.app.ui.screens.wdp.PlannerRecent
import com.bmscompanion.app.ui.screens.wdp.PlannerWindow
import com.bmscompanion.app.ui.screens.wdp.PlannerWindowHost
import com.bmscompanion.app.ui.screens.wdp.PlannerWindows
import com.bmscompanion.app.ui.screens.wdp.TreeItem
import com.bmscompanion.app.ui.screens.wdp.WdpPage
import com.bmscompanion.app.ui.screens.wdp.WdpPlanner
import com.bmscompanion.app.ui.screens.wdp.WdpProbe
import com.bmscompanion.app.ui.screens.wdp.WdpSession
import com.bmscompanion.app.ui.screens.wdp.treeItems
import com.bmscompanion.app.ui.theme.Hud
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.awt.event.KeyEvent as AwtKey
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * `--planneroutcome browser <outDir> <a copy of a BMS folder>`: **Open mission…** and the flight picker, in-process,
 * the way the PC window runs them ("This PC": the bridge answering in the same process), on a copy (R3-PLAN U3).
 *
 * 1. **Not linked**: both windows say the files are on the PC and list nothing.
 * 2. **The list**: every theater of the copy's `theater.lst` is a group; the one BMS is set to comes first and open,
 *    with the newest save on top marked as the printed briefing's; the sort switch reorders; the search finds a save
 *    in a closed group; *Show BMS's own missions* shows BMS's missions (which open like any other) and the campaign
 *    starts, which are greyed and give their reason instead of opening.
 * 3. **The briefed save** (pressed in the list): the picker opens on the briefed flight; a package with flights of two
 *    teams is under both; Find package and the ATO Target List work; seat Lead; **Plan this flight** hands the flight
 *    to the Planner, and the DataCard shows its callsign, package and steerpoints while TOSS opens on the default TGT
 *    STPT.
 * 4. **A save of another theater** (Hellas WCP's `SAT 001.tac`, found with the search): the Planner switches to that
 *    theater, and the PC's sentence says so.
 * 5. **A TE that ships with BMS** (`TE_BMS_03`): it opens and is planned like the pilot's own, and a Save to DTC with
 *    it saves the cartridge and writes its mission file too, as WDP does (or says why that file was not written — never
 *    because the TE ships with BMS).
 * 6. **Back to BMS briefing**, **Recent**, and the two windows at a phone's size.
 *
 * Pictures go to `<outDir>/browser/`. Step 5's save writes the cartridge in the copy's `User/Config` and TE_BMS_03's
 * mission file in its campaign folder, which is why this runs on a copy only.
 */
object BrowserOutcome {
    private const val W = 1280
    private const val H = 800

    fun run(outDir: File, more: List<String>): String {
        val out = StringBuilder()
        var fails = 0
        fun line(s: String) { out.appendLine(s) }
        fun check(ok: Boolean, what: String, detail: String = "") {
            if (!ok) fails++
            line((if (ok) "PASS " else "FAIL ") + what + if (detail.isNotEmpty()) " ($detail)" else "")
        }
        val pics = File(outDir, "browser").apply { mkdirs() }
        val root = more.getOrNull(0)?.let(::File) ?: Bridge.settings.value.BmsDirOverride?.let(::File)
        if (root == null || !root.isDirectory) return "FAIL: give a copy of a BMS folder: --planneroutcome browser <outDir> <copy>\n"
        DevGuard.why(root)?.let { return "FAIL: $it — the browser check saves a cartridge, so it runs on a copy only.\n" }

        line("Open mission… and the flight picker, in-process (U3)")
        line("copy: ${root.name}")
        line("")

        val campaignBefore = campaignHashes(root)
        // the one file part 5's Save to DTC writes on purpose (the TE's own mission file, as WDP does), when the PC says so
        val savedOnPurpose = HashSet<String>()
        val before = Bridge.settings.value
        val wasRunning = Bridge.running
        val wasProbe = WdpProbe.on
        var acquired = 0
        WdpProbe.on = true
        WdpProbe.clear()
        // a clean start: no plan from a save, no recent files, the list as it opens the first time
        PlannerMissionState.backToBriefing()
        PlannerRecent.clear()
        CampaignBrowser.files = null; CampaignBrowser.query = ""; CampaignBrowser.showStock = false; CampaignBrowser.sort = CampSort.NEWEST
        CampaignBrowser.open.clear(); CampaignBrowser.why = null
        WdpSession.page = WdpPage.DATACARD.name
        val stage = Stage(W, H, planner = true)
        try {
            // ---------------------------------------------------------------- 1. not linked
            line("== 1. Not linked")
            MissionLink.forget()
            PlannerWindows.show(PlannerWindow.OPEN_MISSION)
            stage.frames(10)
            check(MissionLink.state.value !is LinkState.Online, "the link is down", "${MissionLink.state.value}")
            check(stage.has("OpenMission/NotLinked"), "Open mission says the files are on the PC")
            check(stage.names("OpenMission/Theater/").isEmpty() && stage.names("OpenMission/File/").isEmpty(), "and lists nothing")
            stage.shot(File(pics, "01-not-linked.png"))
            FlightPicker.show(CampRef("Korea KTO", "Auto Save.cam"))
            stage.frames(6)
            check(stage.has("FlightPicker/NotLinked") && stage.names("FlightPicker/Flight/").isEmpty(), "the flight picker says so too, and lists nothing")
            PlannerWindows.close()
            line("")

            // ---------------------------------------------------------------- 2. the list
            line("== 2. The list (This PC, the bridge on the copy)")
            Bridge.update { it.copy(BmsDirOverride = root.path, AutoEzBoardsOnPrint = false, EzBoardsDir = null) }
            Bridge.startForCheck()
            MissionLink.useThisPc()
            MissionLink.acquire(); acquired++
            val loadsBefore = CampaignBrowser.loads
            PlannerWindows.show(PlannerWindow.OPEN_MISSION)
            val t0 = System.currentTimeMillis()
            val listed = stage.until(60_000) { MissionLink.state.value is LinkState.Online && CampaignBrowser.loads > loadsBefore && !CampaignBrowser.loading }
            val files = CampaignBrowser.files
            check(listed && files != null && files.error == null && CampaignBrowser.error == null, "the list came back",
                "${System.currentTimeMillis() - t0} ms incl. the link; ${files?.error ?: CampaignBrowser.error ?: ""}")
            if (files == null) throw IllegalStateException("no listing")
            stage.until(20_000) { MissionLink.mission.value != null }
            val theaterList = Theaters.of(Bridge.install)?.all.orEmpty()
            check(files.theaters.size == theaterList.size && files.theaters.size == 19, "one group per theater of theater.lst", "${files.theaters.size} groups, ${theaterList.size} theaters")
            val first = files.theaters.first()
            check(first.current && first.name == files.current && first.name == "Korea KTO", "the theater BMS is set to comes first", "${first.name}, current ${files.current}")
            stage.frames(6)
            val koreaRows = stage.namesInOrder("OpenMission/File/Korea KTO/")
            check(koreaRows.firstOrNull() == "Auto Save.cam", "…open, with the newest save on top", koreaRows.take(4).joinToString())
            val auto = first.files.firstOrNull { it.name == "Auto Save.cam" }
            check(auto?.briefed == true, "Auto Save.cam is marked as the printed briefing's", "briefed ${auto?.briefed}")
            val others = files.theaters.drop(1).filter { t -> stage.names("OpenMission/File/${t.name}/").isNotEmpty() }
            check(others.isEmpty(), "every other group is closed", others.joinToString { it.name })
            check(first.files.none { it.start }, "no campaign start is listed while BMS's own missions are folded away", "")
            stage.shot(File(pics, "02-list.png"))

            // the sort switch
            stage.click("OpenMission/Sort/NAME")
            val byName = stage.namesInOrder("OpenMission/File/Korea KTO/")
            val wantName = first.files.filter { !it.stock && !it.start }.map { it.name }.sortedBy { it.lowercase() }
            check(byName.isNotEmpty() && byName == wantName.take(byName.size), "Sort: Name puts them in name order", byName.take(4).joinToString())
            stage.click("OpenMission/Sort/CREATED")
            val byCreated = stage.namesInOrder("OpenMission/File/Korea KTO/")
            val wantCreated = first.files.filter { !it.stock && !it.start }.sortedWith(compareByDescending<CampFile> { it.created }.thenBy { it.name.lowercase() }).map { it.name }
            check(byCreated == wantCreated.take(byCreated.size), "Sort: Created orders by created time, and explains itself", byCreated.take(3).joinToString())
            stage.shot(File(pics, "03-sort-created.png"))
            stage.click("OpenMission/Sort/NEWEST")
            check(stage.namesInOrder("OpenMission/File/Korea KTO/").firstOrNull() == "Auto Save.cam", "Sort: Newest again", "")

            // the search finds a save in a closed group — typed into the box key by key, as a pilot types it
            CampaignBrowser.query = ""
            stage.frames(2)
            val typedSearch = stage.typeInto("OpenMission/Search", "SAT 001")
            stage.frames(6)
            check(typedSearch && CampaignBrowser.query == "SAT 001", "typing “SAT 001” into the search box key by key: the box keeps every key", "'${CampaignBrowser.query}'")
            check(stage.has("OpenMission/File/Hellas WCP/SAT 001.tac") && stage.names("OpenMission/File/Korea KTO/").isEmpty(),
                "the search finds Hellas WCP's SAT 001.tac and opens its group", stage.names("OpenMission/File/").joinToString())
            stage.shot(File(pics, "04-search.png"))
            stage.clearBox()
            stage.frames(4)
            check(CampaignBrowser.query.isEmpty() && stage.namesInOrder("OpenMission/File/Korea KTO/").firstOrNull() == "Auto Save.cam",
                "Ctrl+A, Backspace empties the search box and the whole list is back", "'${CampaignBrowser.query}'")
            CampaignBrowser.query = ""

            // BMS's own missions
            val loads2 = CampaignBrowser.loads
            stage.click("OpenMission/ShowStock")
            stage.until(60_000) { CampaignBrowser.loads > loads2 && !CampaignBrowser.loading }
            stage.frames(6)
            val korea2 = CampaignBrowser.files?.theaters?.firstOrNull { it.name == "Korea KTO" }
            val starts = korea2?.files.orEmpty().filter { it.start }
            check(starts.isNotEmpty() && starts.all { it.stock }, "Show BMS's own missions lists the campaign starts too", "${starts.size} in Korea KTO: ${starts.take(4).joinToString { it.name }}")
            val te03 = korea2?.files?.firstOrNull { it.name.startsWith("TE_BMS_03", true) }
            check(te03 != null && te03.stock && !te03.start && te03.stockWhy == null, "and BMS's TEs, which open like any other",
                "${te03?.name} stock ${te03?.stock}, start ${te03?.start}: ${te03?.stockWhy}")
            CampaignBrowser.query = "Save0"
            stage.frames(6)
            val save0 = "OpenMission/File/Korea KTO/Save0.cam"
            check(stage.has(save0), "Save0.cam is listed", stage.names("OpenMission/File/").take(4).joinToString())
            stage.click(save0)
            check(PlannerWindows.open == PlannerWindow.OPEN_MISSION && CampaignBrowser.why == "Korea KTO|Save0.cam" && stage.has("OpenMission/Why"),
                "a campaign start cannot be opened: it gives its reason instead", "${PlannerWindows.open}, why ${CampaignBrowser.why}")
            stage.shot(File(pics, "05-start-refused.png"))
            CampaignBrowser.query = ""
            val loads3 = CampaignBrowser.loads
            stage.click("OpenMission/ShowStock")
            stage.until(30_000) { CampaignBrowser.loads > loads3 && !CampaignBrowser.loading }
            line("")

            // ---------------------------------------------------------------- 3. the briefed save
            line("== 3. The briefed save: Auto Save.cam")
            val brief = MissionLink.mission.value?.briefing
            val briefedCallsign = brief?.overview?.flight?.trim().orEmpty()
            stage.frames(4)
            stage.click("OpenMission/File/Korea KTO/Auto Save.cam")
            check(PlannerWindows.open == PlannerWindow.FLIGHT_PICKER && PlannerWindows.arg == "Korea KTO|Auto Save.cam", "pressing it opens the flight picker on it", "${PlannerWindows.open} ${PlannerWindows.arg}")
            stage.until(60_000) { FlightPicker.ato != null && FlightPicker.selected?.let { FlightPicker.flights[it] } != null }
            val ato = FlightPicker.ato
            check(ato != null && ato.packages.isNotEmpty(), "the ATO came back", "${ato?.packages?.size} packages, ${ato?.packages?.sumOf { it.flights.size }} flights, teams ${ato?.teams?.joinToString { it.name + if (it.allied) "*" else "" }}")
            val sel = FlightPicker.row(FlightPicker.selected)
            val selPkg = FlightPicker.packageOf(FlightPicker.selected)
            check(sel != null && sel.briefed && sel.callsign.equals(briefedCallsign, true), "it opens on the briefed flight", "${sel?.callsign} (briefing: $briefedCallsign), package ${selPkg?.number}")
            check(selPkg?.number == 7288, "…in package 7288", "${selPkg?.number}")
            stage.frames(6)
            check(sel != null && stage.has("FlightPicker/Flight/${sel.id}"), "…shown in the tree", "")
            check(stage.has("FlightPicker/Source"), "the picker says where the steerpoints will come from", "")
            stage.shot(File(pics, "06-picker.png"))
            // a package with flights of two teams is under both (fix D44)
            if (ato != null) {
                val mixed = ato.packages.firstOrNull { p -> p.flights.map { it.team }.distinct().size > 1 }
                if (mixed == null) line("  (no package in this save has flights of two teams)")
                else {
                    stage.onUi { FlightPicker.expandAll(true) }
                    val items = treeItems(ato).filterIsInstance<TreeItem.Pkg>().filter { it.pkg.id == mixed.id }
                    check(items.size == mixed.flights.map { it.team }.distinct().size && items.any { it.owner != null },
                        "package ${mixed.number} (flights of ${mixed.flights.map { it.team }.distinct().size} teams) is under each of them, marked with its owner",
                        items.joinToString { "${it.team.name}" + (it.owner?.let { o -> " (package of ${o.name})" } ?: "") })
                    stage.onUi { FlightPicker.expandAll(false) }
                }
            }
            // Find package: the number typed into the Pkg box key by key, then Enter (the box's Search key)
            FlightPicker.findText = ""
            stage.onUi { FlightPicker.expandAll(false) }
            stage.frames(4)
            check(!stage.has("FlightPicker/Package/7288"), "Collapse all: package 7288 is folded away", "")
            val typedPkg = stage.typeInto("FlightPicker/Package", "72a88")
            check(typedPkg && FlightPicker.findText == "7288", "typing “72a88” into the Pkg box key by key: it keeps the digits, and only the digits", "'${FlightPicker.findText}'")
            stage.press(AwtKey.VK_ENTER, '\n')
            stage.frames(4)
            check(stage.has("FlightPicker/Package/7288") && FlightPicker.findNote == null, "Enter in the Pkg box finds package 7288: it opens and shows it",
                "${FlightPicker.findNote ?: ""} on screen: ${stage.namesInOrder("FlightPicker/Package/").take(5).joinToString()}; open: ${FlightPicker.openPackages.filterValues { it }.keys.take(4)}")
            stage.onUi { FlightPicker.expandAll(false) }
            stage.frames(4)
            stage.click("FlightPicker/Find")
            stage.frames(4)
            check(stage.has("FlightPicker/Package/7288") && FlightPicker.findNote == null, "Find package 7288 opens and shows it",
                "${FlightPicker.findNote ?: ""} on screen: ${stage.namesInOrder("FlightPicker/Package/").take(5).joinToString()}; open: ${FlightPicker.openPackages.filterValues { it }.keys.take(4)}")
            stage.shot(File(pics, "06b-find.png"))
            FlightPicker.findText = "99999"
            stage.click("FlightPicker/Find")
            check(FlightPicker.findNote?.contains("99999") == true, "Find package with a number that is not there says so", "${FlightPicker.findNote}")
            FlightPicker.findText = ""; FlightPicker.findNote = null
            // the ATO Target List
            stage.click("FlightPicker/TargetList")
            stage.frames(4)
            check(stage.has("FlightPicker/Targets") && stage.names("FlightPicker/Target/").isNotEmpty(), "the ATO Target List lists the side's packages and targets", "${stage.names("FlightPicker/Target/").size} on screen")
            stage.shot(File(pics, "07-targets.png"))
            stage.click("FlightPicker/TargetList")
            // the seat, then plan
            stage.click("FlightPicker/Seat/Lead")
            check(FlightPicker.seat == 0, "seat Lead", "${FlightPicker.seat}")
            val wing = stage.click("FlightPicker/Seat/Wing")
            check(wing && FlightPicker.seat == (if ((sel?.count ?: 1) > 1) 1 else 0), "seat Wing (only when the flight has a wingman)", "count ${sel?.count}, seat ${FlightPicker.seat}")
            stage.click("FlightPicker/Seat/Lead")
            stage.click("FlightPicker/Plan")
            // WDP's Precision STPT question: a campaign always asks it, and Enter answers its default, No
            val asked = stage.until(20_000) { precisionAsked() }
            check(asked, "Plan this flight asks WDP's question \"Did you save Precision STPT in the DTC for THIS flight in BMS?\"", "")
            if (asked) stage.press(AwtKey.VK_ENTER)
            stage.until(20_000) { PlannerWindows.open == null && PlannerMissionState.fromSave }
            check(PlannerMissionState.precision == false, "Enter answers No, the default: every steerpoint from the mission file", "${PlannerMissionState.precision}")
            val f = PlannerMissionState.flight
            check(PlannerWindows.open == null && PlannerMissionState.fromSave, "Plan this flight hands it to the Planner and closes the window", "${PlannerWindows.open}")
            check(f?.row?.callsign.equals(briefedCallsign, true) && f?.packageNumber == 7288 && f.route.size == 8,
                "the Planner plans $briefedCallsign of package 7288, 8 waypoints", "${f?.row?.callsign}, ${f?.packageNumber}, ${f?.route?.size} waypoints")
            check(PlannerMissionState.seat == 0 && PlannerMissionState.theater == "Korea KTO" && PlannerMissionState.kind == CampKind.CAMPAIGN &&
                PlannerMissionState.ref == CampRef("Korea KTO", "Auto Save.cam", sel?.id ?: "?") && f?.briefing?.origin == "save",
                "seat Lead, Korea KTO, a campaign, its ref, a briefing made from the save", "${PlannerMissionState.label}; origin ${f?.briefing?.origin}")
            // the pages
            WdpSession.page = WdpPage.DATACARD.name
            val applied = stage.until(60_000) { WdpSession.appliedMission?.flight?.row?.id == sel?.id }
            stage.frames(20)
            val m = WdpSession.appliedMission
            val v = WdpSession.dataCard.values(WdpPage.DATACARD.hiddenHere())
            val actions = (1..24).mapNotNull { n -> v["lblAction$n"]?.takeIf { it.isNotBlank() } }
            // the card prints the callsign as WDP does, its number apart ("Cyborg 6", DataCardWiring.wdpCallsign)
            check(applied && (v["lblCallsign1"] ?: "").replace(" ", "").contains(briefedCallsign.replace(" ", ""), true), "the DataCard shows $briefedCallsign", "lblCallsign1 '${v["lblCallsign1"]}'")
            check((v["lblPackage1"] ?: "").contains("7288"), "…package 7288", "lblPackage1 '${v["lblPackage1"]}'")
            check(actions.size == 8, "…and 8 steerpoints in its flight plan", "${actions.size}: ${actions.joinToString("/")}")
            line("  the pages: " + (m?.let { WdpFixtureMission.describe(it) } ?: "no mission"))
            stage.shot(File(pics, "08-datacard.png"))
            WdpSession.page = WdpPage.TOSS.name
            stage.frames(20)
            val tgt = WdpSession.toss.values(WdpPage.TOSS.hiddenHere())["numWaypoint"]
            // a flight with no strike steerpoint and Precision STPT No has no default (WdpMission.defaultStpt: "the pages
            // then keep what they show"); with one, TOSS must open on it
            if (m?.defaultStpt != null) check(tgt == m.defaultStpt.toString(), "TOSS opens on the default TGT STPT", "numWaypoint $tgt, default ${m.defaultStpt}")
            else check(m != null && (tgt?.toIntOrNull() ?: 0) in 1..25, "TOSS keeps its own TGT STPT (the flight has no strike steerpoint, so no default)", "numWaypoint $tgt")
            stage.shot(File(pics, "09-toss.png"))
            check(PlannerRecent.items.firstOrNull() == CampRef("Korea KTO", "Auto Save.cam"), "Recent holds it", PlannerRecent.items.joinToString { it.file })
            line("")

            // ---------------------------------------------------------------- 4. another theater
            line("== 4. A save of another theater: Hellas WCP's SAT 001.tac")
            WdpSession.page = WdpPage.DATACARD.name
            PlannerWindows.show(PlannerWindow.OPEN_MISSION)
            stage.frames(6)
            check(stage.has("OpenMission/BackToBriefing"), "while a save is planned, the list offers Back to BMS briefing", "")
            CampaignBrowser.query = "SAT 001"
            stage.frames(6)
            stage.click("OpenMission/File/Hellas WCP/SAT 001.tac")
            stage.until(60_000) { FlightPicker.ato?.file == "SAT 001.tac" }
            val hato = FlightPicker.ato
            val pick = FlightPicker.row(FlightPicker.selected)
                ?: hato?.packages?.flatMap { it.flights }?.let { rows -> rows.firstOrNull { it.player } ?: rows.firstOrNull { it.f16 } ?: rows.firstOrNull() }
            line("  the picker opened on: ${FlightPicker.row(FlightPicker.selected)?.callsign ?: "no flight (none briefed, none with a player slot)"}; planning ${pick?.callsign}")
            if (pick != null && FlightPicker.selected != pick.id) {
                // open its branch the way Find package does, then press it
                stage.onUi { FlightPicker.reveal(FlightPicker.packageOf(pick.id)!!) }
                stage.frames(4)
                stage.click("FlightPicker/Flight/${pick.id}")
            }
            stage.until(30_000) { FlightPicker.selected?.let { FlightPicker.flights[it] } != null }
            val hNotes = FlightPicker.selected?.let { FlightPicker.flights[it]?.value?.notes }.orEmpty()
            stage.shot(File(pics, "10-picker-hellas.png"))
            stage.click("FlightPicker/Plan")
            // a TE asks the Precision STPT question only when its mission file is beside it: answered No where it asks
            if (stage.until(5_000) { precisionAsked() }) stage.press(AwtKey.VK_ENTER)
            stage.until(20_000) { PlannerWindows.open == null && PlannerMissionState.ref?.file == "SAT 001.tac" }
            check(PlannerMissionState.theater == "Hellas WCP" && PlannerMissionState.kind == CampKind.TE,
                "the Planner plans ${pick?.callsign} of SAT 001.tac in Hellas WCP (a TE of the pilot's own)", "${PlannerMissionState.label}, kind ${PlannerMissionState.kind}")
            val line4 = PlannerMissionState.notes.firstOrNull { it.startsWith("This file is from Hellas WCP") }
            check(line4 != null && hNotes.any { it == line4 }, "with the line: \"${line4 ?: "(missing)"}\"", "")
            val switched = stage.until(60_000) { WdpSession.theaterName == "Hellas WCP" && WdpSession.theater?.name == "Hellas WCP" && WdpSession.appliedMission?.flight?.row?.id == pick?.id }
            check(switched && WdpSession.appliedMission?.theater?.name == "Hellas WCP", "the pages work in Hellas WCP's theater",
                "theater ${WdpSession.theater?.name}, name '${WdpSession.theaterName}', applied flight ${WdpSession.appliedMission?.flight?.row?.id} (picked ${pick?.id}), applied theater ${WdpSession.appliedMission?.theater?.name}")
            // the cartridge is made for BMS's mission in Korea KTO: its positions are not Hellas feet
            val bmsTheater = MissionLink.info.value?.bms?.theater
            val hf = PlannerMissionState.flight
            val pickerLine = hf?.let { com.bmscompanion.app.ui.screens.wdp.plannerMission(PlannerMissionState.missionData(MissionLink.mission.value, "Hellas WCP", bmsTheater), null, it).sourceLine }
            check(!PlannerMissionState.cartridgeApplies("Hellas WCP", bmsTheater) && pickerLine?.contains("your DTC") == false,
                "the picker plans a save of another theater without the cartridge's Korea positions", "BMS on $bmsTheater; $pickerLine")
            val pagesDtc = WdpSession.appliedMission?.slots?.count { it.source == com.bmscompanion.app.ui.screens.wdp.StptSource.DTC && (it.point.x != 0.0 || it.point.y != 0.0) } ?: -1
            line((if (pagesDtc == 0) "PASS" else "WAIT (U2: plannerMission to take PlannerMissionState.missionData(data))") +
                " the pages place no steerpoint of the Korea cartridge in Hellas ($pagesDtc from the DTC; ${WdpSession.appliedMission?.sourceLine})")
            stage.frames(10)
            stage.shot(File(pics, "11-datacard-hellas.png"))
            CampaignBrowser.query = ""
            line("")

            // ---------------------------------------------------------------- 5. a TE that ships with BMS
            line("== 5. A TE that ships with BMS: TE_BMS_03")
            PlannerWindows.show(PlannerWindow.OPEN_MISSION)
            val loads5 = CampaignBrowser.loads
            stage.click("OpenMission/ShowStock")
            stage.until(60_000) { CampaignBrowser.loads > loads5 && !CampaignBrowser.loading }
            CampaignBrowser.query = "TE_BMS_03"
            stage.frames(6)
            val teName = stage.names("OpenMission/File/Korea KTO/").firstOrNull()
            check(teName != null && teName.startsWith("TE_BMS_03", true), "Show BMS's own missions + search finds it", "$teName")
            if (teName != null) {
                stage.click("OpenMission/File/Korea KTO/$teName")
                stage.until(60_000) { FlightPicker.ato?.file == teName }
                val tato = FlightPicker.ato
                val tpick = FlightPicker.row(FlightPicker.selected)
                    ?: tato?.packages?.flatMap { it.flights }?.let { rows -> rows.firstOrNull { it.player } ?: rows.firstOrNull { it.f16 } ?: rows.firstOrNull() }
                if (tpick != null && FlightPicker.selected != tpick.id) {
                    stage.onUi { FlightPicker.reveal(FlightPicker.packageOf(tpick.id)!!) }
                    stage.frames(4)
                    stage.click("FlightPicker/Flight/${tpick.id}")
                }
                stage.until(30_000) { FlightPicker.selected?.let { FlightPicker.flights[it] } != null }
                stage.shot(File(pics, "12-picker-stock-te.png"))
                stage.click("FlightPicker/Plan")
                // its mission file is beside it, so it asks the Precision STPT question too: answered No, as in part 4
                if (stage.until(5_000) { precisionAsked() }) stage.press(AwtKey.VK_ENTER)
                stage.until(20_000) { PlannerWindows.open == null && PlannerMissionState.ref?.file == teName }
                check(PlannerMissionState.fromSave && PlannerMissionState.kind == CampKind.TE,
                    "it opens and is planned like the pilot's own", "${PlannerMissionState.label}")
                // a Save to DTC with it: the cartridge is saved, and the TE's own mission file too, as WDP writes it
                val ini = File(Theaters.of(Bridge.install)!!.campaignDir(Theaters.of(Bridge.install)!!.byName("Korea KTO")!!), teName.substringBeforeLast('.') + ".ini")
                val iniBefore = if (ini.isFile) sha(ini.readBytes()) else "none"
                val saved = runBlocking {
                    val text = MissionLink.cartridge()?.text.orEmpty()
                    val edit = stptEdit(text)
                    MissionLink.cartridgeSave(null, listOfNotNull(edit), te = PlannerMissionState.ref)
                }
                val iniAfter = if (ini.isFile) sha(ini.readBytes()) else "none"
                val written = saved?.mission?.written == true
                if (written) savedOnPurpose += ini.canonicalPath
                check(saved != null && saved.error == null && saved.mission != null && saved.mission?.reason?.contains("ships with Falcon BMS") != true,
                    "a Save to DTC saves the cartridge and the TE's mission file, as WDP does (or the PC says why not, never \"ships with BMS\")",
                    "${saved?.error ?: ""} written $written ${saved?.mission?.reason ?: ""}")
                check(written == (iniBefore != iniAfter), "${ini.name} changed exactly when the PC said it was written",
                    if (iniBefore == "none") "no such file" else "${iniBefore.take(12)} -> ${iniAfter.take(12)}")
            }
            CampaignBrowser.query = ""
            val loads5b = CampaignBrowser.loads
            if (!PlannerWindows.isOpen) PlannerWindows.show(PlannerWindow.OPEN_MISSION)
            stage.frames(4)
            stage.click("OpenMission/ShowStock")
            stage.until(30_000) { CampaignBrowser.loads > loads5b && !CampaignBrowser.loading }
            line("")

            // ---------------------------------------------------------------- 6. back, recent, a phone
            line("== 6. Back to BMS briefing, Recent, a phone")
            stage.frames(4)
            check(PlannerRecent.items.map { it.file }.take(3) == listOfNotNull(teName, "SAT 001.tac", "Auto Save.cam"),
                "Recent lists the files opened, the last first", PlannerRecent.items.joinToString { it.file })
            check(stage.names("OpenMission/Recent/").isNotEmpty(), "and the list shows them", stage.names("OpenMission/Recent/").joinToString())
            stage.shot(File(pics, "13-recent.png"))
            stage.click("OpenMission/BackToBriefing")
            check(!PlannerMissionState.fromSave && PlannerMissionState.flight == null && PlannerMissionState.ref == null, "Back to BMS briefing: the Planner plans the printed briefing again", PlannerMissionState.source)
            val back = stage.until(60_000) { WdpSession.appliedMission != null && WdpSession.appliedMission?.flight == null }
            check(back && WdpSession.appliedMission?.briefing?.origin == null, "…and the pages are given it", "flight ${WdpSession.appliedMission?.flight?.row?.callsign}")
            PlannerWindows.close()
            stage.frames(4)
            stage.close()

            val phone = Stage(400, 800, planner = false)
            try {
                PlannerWindows.show(PlannerWindow.OPEN_MISSION)
                phone.frames(12)
                // narrower than one row, the order, BMS's own missions, Refresh, Browse and the guide scroll sideways on
                // the row under the search, as Recent's files do on theirs (CampaignBrowserWindow): those may reach past
                // the edge; everything else must be inside the screen
                val sideways = listOf("Sort/", "ShowStock", "Refresh", "Browse", "Guide", "Recent/").map { "planner/OpenMission/$it" }
                val outside = phone.onUi { WdpProbe.rects.filter { (k, r) -> k.startsWith("planner/OpenMission") && sideways.none { k.startsWith(it) } && !(r.left >= -0.5f && r.right <= 400.5f) }.keys.toList() }
                check(phone.has("OpenMission/File/Korea KTO/Auto Save.cam") && outside.isEmpty(), "a phone's list: every control inside the screen (the sideways rows aside)", outside.take(5).joinToString())
                phone.shot(File(pics, "14-phone-list.png"))
                phone.click("OpenMission/File/Korea KTO/Auto Save.cam")
                phone.until(30_000) { FlightPicker.ato?.file == "Auto Save.cam" && FlightPicker.selected?.let { FlightPicker.flights[it] } != null }
                phone.frames(8)
                // the tools row (Pkg, Find package, Expand/Collapse all, ATO Target List) scrolls sideways (FlightPickerWindow)
                val sideways2 = listOf("Package", "Find", "ExpandAll", "CollapseAll", "TargetList").map { "planner/FlightPicker/$it" }
                val outside2 = phone.onUi { WdpProbe.rects.filter { (k, r) -> k.startsWith("planner/FlightPicker") && k !in sideways2 && !(r.left >= -0.5f && r.right <= 400.5f) }.keys.toList() }
                check(phone.has("FlightPicker/Plan") && phone.has("FlightPicker/Seat/Lead") && outside2.isEmpty(), "a phone's picker: the seat and Plan this flight on screen, inside it (the tools row aside)", outside2.take(5).joinToString())
                phone.shot(File(pics, "15-phone-picker.png"))
                PlannerWindows.close()
            } finally { phone.close() }
            line("")

            // ---------------------------------------------------------------- 7. every window, from a phone to a big screen
            line("== 7. The Planner's windows from a phone to a 2560 x 1440 screen")
            val sizes = listOf(400 to 800, 860 to 393, 1280 to 800, 1920 to 1080, 2560 to 1440)
            val wins = listOf(PlannerWindow.OPEN_MISSION, PlannerWindow.FLIGHT_PICKER, PlannerWindow.PRINT, PlannerWindow.GUIDE)
            val grown = HashMap<PlannerWindow, MutableList<Float>>()
            for ((w, h) in sizes) {
                val s = Stage(w, h, planner = false)
                try {
                    for (win in wins) {
                        if (win == PlannerWindow.FLIGHT_PICKER) FlightPicker.show(CampRef("Korea KTO", "Auto Save.cam")) else PlannerWindows.show(win)
                        s.until(20_000) {
                            when (win) {
                                PlannerWindow.OPEN_MISSION -> s.has("OpenMission/File/Korea KTO/Auto Save.cam")
                                PlannerWindow.FLIGHT_PICKER -> s.has("FlightPicker/Plan")
                                PlannerWindow.PRINT -> s.names("Print/Half/").isNotEmpty()
                                else -> s.has("Guide/Close")
                            }
                        }
                        s.frames(6)
                        val frame = s.onUi { WdpProbe.rects["planner/${win.probe}"] }
                        val parts = s.onUi { WdpProbe.rects.filterKeys { it.startsWith("planner/${win.probe}/") }.values.toList() }
                        val inside = frame != null && frame.left >= -0.5f && frame.top >= -0.5f && frame.right <= w + 0.5f && frame.bottom <= h + 0.5f
                        // what a pilot needs to see without scrolling the window itself: its close, and its main button
                        val main = when (win) {
                            // the list's own Close went in 1.3.8: the title bar's × is the one
                            PlannerWindow.OPEN_MISSION -> "OpenMission/Close"
                            PlannerWindow.FLIGHT_PICKER -> "FlightPicker/Plan"
                            PlannerWindow.PRINT -> "Print/Print"
                            else -> "Guide/Close"
                        }
                        val mainRect = s.onUi { WdpProbe.rects["planner/$main"] }
                        val reachable = mainRect != null && mainRect.bottom <= h + 0.5f && mainRect.right <= w + 0.5f && mainRect.top >= 0f
                        check(inside && reachable && parts.isNotEmpty(), "${w}x$h: ${win.title} fits the screen, with ${main.substringAfter('/')} on it",
                            frame?.let { "window ${it.width.toInt()}x${it.height.toInt()} at ${it.left.toInt()},${it.top.toInt()}" } ?: "no window")
                        if (frame != null) grown.getOrPut(win) { ArrayList() } += frame.width * frame.height
                        s.shot(File(pics, "20-size-${w}x$h-${win.probe}.png"))
                        PlannerWindows.close()
                        s.frames(2)
                    }
                } finally { s.close() }
            }
            for ((win, areas) in grown) {
                check(areas.size == sizes.size && areas.last() > areas[areas.size - 2] * 1.1f,
                    "${win.title} grows with the window (2560 x 1440 gives it more room than 1920 x 1080)", areas.joinToString(" → ") { "${(it / 1000).toInt()}k px²" })
            }
            line("")

            // ---------------------------------------------------------------- the copy's campaign folders
            val campaignAfter = campaignHashes(root)
            val changed = (campaignBefore.keys + campaignAfter.keys).filter { campaignBefore[it] != campaignAfter[it] && it !in savedOnPurpose }
            check(changed.isEmpty(), "nothing else in the copy's campaign folders was written (${campaignBefore.size} files; part 5's TE file aside: ${savedOnPurpose.size})", changed.take(5).joinToString())
        } catch (e: Throwable) {
            fails++
            line("FAIL: threw ${e::class.java.simpleName}: ${e.message}")
            e.stackTrace.take(14).forEach { line("    at $it") }
        } finally {
            runCatching { stage.close() }
            PlannerWindows.close()
            PlannerMissionState.backToBriefing()
            repeat(acquired) { runCatching { MissionLink.release() } }
            WdpProbe.on = wasProbe
            Bridge.update { before }
            if (!wasRunning) Bridge.stop()
        }
        line("")
        line(if (fails == 0) "ALL PASS" else "$fails FAIL")
        return out.toString()
    }

    /** One `[STPT]` edit the save can carry: the cartridge's last target steerpoint, 10 ft higher. */
    /** WDP's Precision STPT question is the box on top (the flight picker's Plan this flight asks it). */
    private fun precisionAsked(): Boolean =
        (com.bmscompanion.app.ui.screens.wdp.WdpDialogs.stack.lastOrNull() as? com.bmscompanion.app.ui.screens.wdp.WdpMessage)
            ?.text?.startsWith("Did you save Precision STPT") == true

    private fun stptEdit(text: String): CartridgeEdit? {
        var inStpt = false
        var last: Pair<String, String>? = null
        for (raw in text.lines()) {
            val l = raw.trim()
            if (l.startsWith("[")) { inStpt = l.equals("[STPT]", true); continue }
            if (!inStpt) continue
            val m = Regex("^(target_\\d+)\\s*=\\s*(.*)$", RegexOption.IGNORE_CASE).find(l) ?: continue
            last = m.groupValues[1] to m.groupValues[2]
        }
        val (key, value) = last ?: return null
        val parts = value.split(',').toMutableList()
        if (parts.size < 3) return null
        val z = parts[2].trim().toDoubleOrNull() ?: return null
        parts[2] = " " + (z + 10).toString()
        return CartridgeEdit("STPT", key, parts.joinToString(","))
    }

    private fun sha(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /** SHA-256 of every file in every theater's campaign folder of [root]. */
    private fun campaignHashes(root: File): Map<String, String> {
        val set = Theaters.at(root)
        val dirs = set.all.mapNotNull { set.campaignDir(it) }.distinct()
        return dirs.flatMap { d -> d.listFiles().orEmpty().filter { it.isFile } }
            .associate { it.canonicalPath to sha(it.readBytes()) }
    }

    /** A scene of its own, whose composition, frames and coroutines all run on one thread, as a window's do. */
    private class Stage(val w: Int, val h: Int, planner: Boolean) : AutoCloseable {
        private val exec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "browser-ui").apply { isDaemon = true } }
        private val ui = exec.asCoroutineDispatcher()
        private var nanos = 0L
        private var closed = false
        val scene: ImageComposeScene

        init {
            scene = onUi {
                ImageComposeScene(w, h, Density(1f), coroutineContext = ui) {
                    // the app's own theme, as every window has it (text fields take their colours from it)
                    com.bmscompanion.app.ui.theme.BmsTheme {
                        Box(Modifier.fillMaxSize().background(Hud.Bg)) {
                            if (planner) Planner()
                            PlannerWindowHost()
                        }
                    }
                }
            }
        }

        fun <T> onUi(block: () -> T): T = exec.submit(Callable { block() }).get()

        fun frame(): org.jetbrains.skia.Image = onUi {
            Snapshot.sendApplyNotifications()
            nanos += 16_666_667L
            scene.render(nanos)
        }

        fun frames(n: Int, sleepMs: Long = 15) = repeat(n) { frame().close(); Thread.sleep(sleepMs) }

        fun until(timeoutMs: Long, cond: () -> Boolean): Boolean {
            val t0 = System.currentTimeMillis()
            while (System.currentTimeMillis() - t0 < timeoutMs) {
                frame().close()
                if (cond()) return true
                Thread.sleep(20)
            }
            return false
        }

        fun shot(file: File) {
            frames(4)
            val img = frame()
            img.encodeToData(EncodedImageFormat.PNG)?.bytes?.let { file.writeBytes(it) }
            img.close()
        }

        fun has(probe: String): Boolean = onUi { WdpProbe.rects.containsKey("planner/$probe") }

        /** The names after [prefix] of the controls on screen whose probe starts with it. */
        fun names(prefix: String): List<String> = onUi { WdpProbe.rects.keys.filter { it.startsWith("planner/$prefix") }.map { it.removePrefix("planner/$prefix") } }

        /** The same, top to bottom. */
        fun namesInOrder(prefix: String): List<String> = onUi {
            WdpProbe.rects.entries.filter { it.key.startsWith("planner/$prefix") && it.value.top < h && it.value.bottom > 0 }
                .sortedBy { it.value.top }.map { it.key.removePrefix("planner/$prefix") }
        }

        // ---- the keyboard, as the PC window hands a key to Compose: pressed, typed, released, with the AWT event inside
        private val keySrc = java.awt.Canvas()
        private var keyMs = 0L

        private fun key(id: Int, code: Int, ch: Char, mods: Int = 0) {
            val loc = if (id == AwtKey.KEY_TYPED) AwtKey.KEY_LOCATION_UNKNOWN else AwtKey.KEY_LOCATION_STANDARD
            keyMs += 20
            val e = AwtKey(keySrc, id, keyMs, mods, code, ch, loc)
            val type = when (id) {
                AwtKey.KEY_PRESSED -> androidx.compose.ui.input.key.KeyEventType.KeyDown
                AwtKey.KEY_RELEASED -> androidx.compose.ui.input.key.KeyEventType.KeyUp
                else -> androidx.compose.ui.input.key.KeyEventType.Unknown
            }
            val k = if (id == AwtKey.KEY_TYPED) androidx.compose.ui.input.key.Key.Unknown else androidx.compose.ui.input.key.Key(code, loc)
            val cp = if (ch == AwtKey.CHAR_UNDEFINED) 0 else ch.code
            val ke = androidx.compose.ui.input.key.KeyEvent(k, type, cp, e.isControlDown, e.isMetaDown, e.isAltDown, e.isShiftDown, e)
            onUi { scene.sendKeyEvent(ke) }
        }

        /** One character typed, with a frame after it, as a person types. */
        fun type(c: Char) {
            val code = AwtKey.getExtendedKeyCodeForChar(c.code)
            val shift = c.isUpperCase()
            val mods = if (shift) java.awt.event.InputEvent.SHIFT_DOWN_MASK else 0
            if (shift) key(AwtKey.KEY_PRESSED, AwtKey.VK_SHIFT, AwtKey.CHAR_UNDEFINED, mods)
            key(AwtKey.KEY_PRESSED, code, c, mods)
            key(AwtKey.KEY_TYPED, AwtKey.VK_UNDEFINED, c, mods)
            key(AwtKey.KEY_RELEASED, code, c, mods)
            if (shift) key(AwtKey.KEY_RELEASED, AwtKey.VK_SHIFT, AwtKey.CHAR_UNDEFINED, 0)
            frame().close()
        }

        /** A key that types no letter (Enter, Backspace), with the control character it types, if any. */
        fun press(code: Int, typed: Char? = null) {
            key(AwtKey.KEY_PRESSED, code, typed ?: AwtKey.CHAR_UNDEFINED)
            if (typed != null) key(AwtKey.KEY_TYPED, AwtKey.VK_UNDEFINED, typed)
            key(AwtKey.KEY_RELEASED, code, typed ?: AwtKey.CHAR_UNDEFINED)
            frame().close()
        }

        /** Ctrl+A, then Backspace: what a pilot does to empty a box. */
        fun clearBox() {
            val ctrl = java.awt.event.InputEvent.CTRL_DOWN_MASK
            key(AwtKey.KEY_PRESSED, AwtKey.VK_CONTROL, AwtKey.CHAR_UNDEFINED, ctrl)
            key(AwtKey.KEY_PRESSED, AwtKey.VK_A, 1.toChar(), ctrl)
            key(AwtKey.KEY_TYPED, AwtKey.VK_UNDEFINED, 1.toChar(), ctrl)
            key(AwtKey.KEY_RELEASED, AwtKey.VK_A, 1.toChar(), ctrl)
            key(AwtKey.KEY_RELEASED, AwtKey.VK_CONTROL, AwtKey.CHAR_UNDEFINED, 0)
            frame().close()
            press(AwtKey.VK_BACK_SPACE, '\b')
        }

        /** Clicks the box named [probe] and types [text] into it key by key; false when the box is not on screen. */
        fun typeInto(probe: String, text: String): Boolean {
            if (!click(probe)) return false
            for (c in text) type(c)
            frames(3)
            return true
        }

        /** Presses the control named [probe] ("OpenMission/Refresh"); false when it is not on screen. */
        fun click(probe: String): Boolean {
            frames(2)
            val r = onUi { WdpProbe.rects["planner/$probe"] } ?: return false
            val at = Offset(r.center.x, r.center.y)
            onUi {
                scene.sendPointerEvent(PointerEventType.Move, at)
                scene.sendPointerEvent(PointerEventType.Press, at, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
            }
            frame().close()
            onUi { scene.sendPointerEvent(PointerEventType.Release, at, buttons = PointerButtons(), button = PointerButton.Primary) }
            frames(4)
            return true
        }

        override fun close() {
            if (closed) return
            closed = true
            runCatching { onUi { scene.close() } }
            exec.shutdown()
        }
    }
}

/** The Planner as the Mission section's tab shows it: the mission the link has, in the save's theater when one is planned. */
@Composable
private fun Planner() {
    val data by MissionLink.mission.collectAsState()
    val info by MissionLink.info.collectAsState()
    val save = PlannerMissionState.theater?.takeIf { PlannerMissionState.fromSave && PlannerMissionState.flight != null }
    WdpPlanner(data, save ?: info?.bms?.theater, Modifier.fillMaxSize())
}
