package com.bmscompanion.desktop.bridge

import com.bmscompanion.app.data.FileRequest
import com.bmscompanion.app.data.PcFiles
import com.bmscompanion.app.data.Platform
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.mission.CampRef
import com.bmscompanion.app.data.mission.Dtc
import com.bmscompanion.app.data.mission.MissionLink
import com.bmscompanion.app.data.wdp.DtcIni
import com.bmscompanion.app.ui.screens.wdp.AptSchedule
import com.bmscompanion.app.ui.screens.wdp.AptScheduleWindow
import com.bmscompanion.app.ui.screens.wdp.CampaignBrowser
import com.bmscompanion.app.ui.screens.wdp.CardFile
import com.bmscompanion.app.ui.screens.wdp.DataCardWiring
import com.bmscompanion.app.ui.screens.wdp.DtcFiles
import com.bmscompanion.app.ui.screens.wdp.DtcWiring
import com.bmscompanion.app.ui.screens.wdp.WdpAttackMap
import com.bmscompanion.app.ui.screens.wdp.WdpDialogs
import com.bmscompanion.app.ui.screens.wdp.WdpFiles
import com.bmscompanion.app.ui.screens.wdp.WdpMessage
import com.bmscompanion.app.ui.screens.wdp.WdpMission
import com.bmscompanion.app.ui.screens.wdp.WdpPicture
import com.bmscompanion.app.ui.screens.wdp.scheduleImage
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * `--wdpfilestest out.txt <scratch folder>`: the Planner's file buttons as WDP's, in-process against the settings' BMS
 * folder (a copy holding the last save) and a scratch folder. The file windows are answered by [PcFiles.testAnswer]
 * (no window is drawn); every file goes through the PC's own routes (`/api/files`), as on a remote device.
 *
 * WDP-compatibility is checked two ways: what the app writes is compared byte for byte with what WDP's own writes
 * give — `WritePrivateProfileString` one key at a time into a new file, which Windows lays out as each section in the
 * order it was first written and each key in the order it was written ([windowsIni]) — and files laid out as WDP
 * writes them, with the liberties Windows' reader allows (another case, spaces round `=`, quotes, a section of
 * another program's first), are read back into the boxes WDP's own loads put them in.
 */
object WdpFilesTest {
    fun run(scratch: File): String {
        val sb = StringBuilder()
        fun line(s: String) { sb.appendLine(s) }
        fun check(what: String, ok: Boolean, detail: String = "") = line((if (ok) "ok   " else "FAIL ") + what + if (detail.isNotEmpty()) "  [$detail]" else "")

        Bridge.startForCheck()
        MissionLink.useThisPc()
        Platform.encodeJpeg = { img, q -> com.bmscompanion.desktop.skiaJpeg(img, q) }
        Platform.decodeImage = { bytes -> com.bmscompanion.desktop.skiaDecode(bytes) }
        val dir = File(scratch, "wdpfiles-test").absoluteFile
        dir.deleteRecursively()
        dir.mkdirs()
        line("BMS folder (the settings' copy): ${Bridge.install.baseDir}")
        line("scratch: ${dir.path}")
        val answers = ArrayList<String?>()
        val asked = ArrayList<FileRequest>()
        PcFiles.testAnswer = { req -> asked += req; if (answers.isEmpty()) null else answers.removeAt(0) }
        fun answer(f: File) { answers += f.path }
        fun msg(): String = (WdpDialogs.stack.lastOrNull() as? WdpMessage)?.text.orEmpty()
        fun lastAsked(): String = asked.lastOrNull()?.let { "\"${it.title}\" in ${it.startDir}, ${it.filters.joinToString { f -> f.label }}, name ${it.fileName}" } ?: "none"
        fun latin(f: File) = String(f.readBytes(), Charsets.ISO_8859_1)

        try {
            // ---------------------------------------------------------------- 0. WDP's folders
            line("")
            line("== 0. WDP's folders on the PC: the Planner's own, in the BMS folder's User")
            val planner = PcFileRoutes.placeDir("planner")
            val bmsBase = Bridge.install.baseDir?.let(::File)
            check("@planner is <BMS>\\User\\BMS Companion Planner, never WDP's folder or Documents",
                bmsBase != null && planner?.path == File(File(bmsBase, "User"), "BMS Companion Planner").path, planner?.path.orEmpty())
            val keepCards = Bridge.settings.value.PlannerDataCardDir
            runBlocking { MissionLink.filesSetDataCards(null) }
            check("@datacards is its DataCards (no folder chosen)", PcFileRoutes.placeDir("datacards")?.path == File(planner, "DataCards").path, PcFileRoutes.placeDir("datacards")?.path.orEmpty())
            // (the pilot's own Documents\\BMS Companion Planner is not copied into the BMS copy by a check: tested below on scratch folders)
            val keepLegacyRun = PcFileRoutes.legacyPlanner
            PcFileRoutes.legacyPlanner = { null }
            val made = try { runBlocking { WdpFiles.folder(WdpFiles.files("EWS")) } } finally { PcFileRoutes.legacyPlanner = keepLegacyRun }
            check("CheckForDir: @planner\\Files\\EWS made", made == null && File(planner, "Files\\EWS").isDirectory, made.orEmpty())
            val layout = PcFileRoutes.SKELETON.filter { !File(planner, it).isDirectory }
            check("the Planner's folder is laid out as WDP's (${PcFileRoutes.SKELETON.size} folders)", layout.isEmpty(), "missing: $layout")
            // the first use copies what an earlier build kept in Documents, once, keeping what is there: on scratch folders
            run {
                val old = File(dir, "legacy-planner")
                File(old, "DataCards\\M\\1").mkdirs()
                File(old, "DataCards\\M\\1\\Viper.bdc").writeText("[Mission]\r\nName=old\r\n")
                File(old, "Files\\EWS").mkdirs()
                File(old, "Files\\EWS\\Mine.ews").writeText("[EWS]\r\n")
                val root = File(dir, "planner-root")
                File(root, "Files\\EWS").mkdirs()
                File(root, "Files\\EWS\\Mine.ews").writeText("kept")
                val keepLegacy = PcFileRoutes.legacyPlanner
                try {
                    PcFileRoutes.legacyPlanner = { old }
                    PcFileRoutes.preparePlanner(root)
                } finally { PcFileRoutes.legacyPlanner = keepLegacy }
                check("first use: an earlier build's files copied in (a file already there kept, the old folder left whole)",
                    File(root, "DataCards\\M\\1\\Viper.bdc").readText().contains("Name=old") && File(root, "Files\\EWS\\Mine.ews").readText() == "kept" &&
                        File(old, "DataCards\\M\\1\\Viper.bdc").isFile && PcFileRoutes.SKELETON.all { File(root, it).isDirectory })
            }
            // the DataCards folder is a setting (WDP's Settings → DataCard directory), from any device
            val chosen = File(dir, "My Cards")
            val set = runBlocking { MissionLink.filesSetDataCards(chosen.path) }
            check("POST /api/files/planner?datacards= sets the DataCards folder", set.error == null && set.value?.dataCardsSet == true &&
                set.value?.dataCards == chosen.path && PcFileRoutes.placeDir("datacards")?.path == chosen.path, "${set.error} ${set.value}")
            val bad = runBlocking { MissionLink.filesSetDataCards("\\\\server\\share") }
            check("a network path is refused, the setting kept", bad.status == 400 && PcFileRoutes.placeDir("datacards")?.path == chosen.path, "${bad.status} ${bad.error}")
            // the Planner writes into Falcon BMS only in its own folder: User\Config (BMS's) is refused, a folder in the Planner's is not
            val cfg = runBlocking { MissionLink.filesSetDataCards("@config") }
            check("a folder inside Falcon BMS (User\\Config) is refused, the setting kept", cfg.status == 400 && PcFileRoutes.placeDir("datacards")?.path == chosen.path, "${cfg.status} ${cfg.error}")
            val inPlanner = runBlocking { MissionLink.filesSetDataCards(File(planner, "Cards 2").path) }
            check("a folder inside the Planner's own is taken", inPlanner.error == null && inPlanner.value?.dataCards == File(planner, "Cards 2").path, "${inPlanner.error} ${inPlanner.value}")
            val back = runBlocking { MissionLink.filesSetDataCards("") }
            val got = runBlocking { MissionLink.filesPlanner() }.value
            check("blank puts back the default; GET /api/files/planner says so", back.value?.dataCardsSet == false && got != null &&
                got.dataCards == File(planner, "DataCards").path && got.dataCardsDefault == got.dataCards && got.plannerExists && got.planner == planner?.path, "$got")
            if (keepCards != null) runBlocking { MissionLink.filesSetDataCards(keepCards) }

            // ---------------------------------------------------------------- the mission: the last save's flight
            val theaters = runBlocking { Repo.index().theaters }
            val korea = theaters.firstOrNull { it.id == "korea-kto" }
            // the save's own player flight (the settings' copy holds the last save as Auto Save.cam), and the pilot's
            // own cartridge in the copy's User\Config
            val list = runBlocking { MissionLink.campaignFiles(false) }.value
            val t = list?.theaters?.firstOrNull { th -> th.files.any { it.name == "Auto Save.cam" } }
            val ato = t?.let { runBlocking { MissionLink.campaignAto(it.name, "Auto Save.cam") }.value }
            val row = ato?.packages?.flatMap { it.flights }?.firstOrNull { it.player }
            val flight = if (t != null && row != null) runBlocking { MissionLink.campaignFlight(t.name, "Auto Save.cam", row.id) }.value else null
            val callsign = flight?.row?.callsign.orEmpty()
            // the card writes a flight's callsign as WDP does, "Satan 7" for BMS's "Satan7" (FillPackages), and names
            // the flight's DataCards folder and backup after it
            val cardCallsign = DataCardWiring.wdpCallsign(callsign).orEmpty()
            val pack = ato?.packages?.firstOrNull { p -> p.flights.any { it.id == row?.id } }?.number?.toString().orEmpty()
            check("the last save's player flight (Auto Save.cam)", flight != null && callsign.isNotEmpty(), "${t?.name} $callsign, package $pack")
            val mission = WdpMission(briefing = flight?.briefing, theater = korea, flight = flight, ref = if (t != null && row != null) CampRef(t.name, "Auto Save.cam", row.id) else null)
            val card = DataCardWiring()
            // the ATIS checks below read inches (A2992); the card starts on the pilot's own M/SM choice
            card.plan.blnKmSm = false
            val pilotCart = Bridge.install.callsign?.let { File(Bridge.install.configDir ?: "", "$it.ini") }?.takeIf { it.isFile }
            val cartText = pilotCart?.let { latin(it) }
            runBlocking { card.prepare(mission) { cartText } }
            fun v(n: String) = card.values(emptyList()).values[n].orEmpty()
            // the save's own weather file is read after prepare and lands on the window's thread
            // (DataCardWiring.saveWeather); Reload WX below runs on this one, so let the read land first
            run { val t0 = System.currentTimeMillis(); while (card.readingSaveWeather && System.currentTimeMillis() - t0 < 15_000) Thread.sleep(100) }
            javax.swing.SwingUtilities.invokeAndWait { }
            check("the card is on the flight, from Osan", v("lblCallsign1") == cardCallsign && v("lblDepName").startsWith("Osan"), "${v("lblCallsign1")} ${v("lblDepName")}")

            // ---------------------------------------------------------------- 1. Reload WX
            line("")
            line("== 1. Reload WX (btnWeather_Click): an .fmap or a .twx on the PC")
            val camp = File(Bridge.install.baseDir, "Data\\Campaign")
            val atisBefore = v("lblAtis1") + " / " + v("lblAtis2")
            line("     ATIS before: $atisBefore")
            fun reload(f: File): String {
                WdpDialogs.stack.clear()
                answer(f)
                runBlocking { card.reloadWx() }
                return v("lblAtis1") + " / " + v("lblAtis2")
            }
            val poor = reload(File(camp, "Poor.fmap"))
            val req = asked.last()
            check("the window is WDP's: \"Load WX FMAP File\", FMAP then TWX, in the save's own theater's campaign folder on the save's .twx",
                req.title == "Load WX FMAP File" && req.filters.map { it.label } == listOf("FMAP(*.fmap)", "TWX(*.twx)") &&
                    (req.startDir == "@campaign" || req.startDir?.startsWith("@campaign:") == true) && req.fileName == "Auto Save.twx", lastAsked())
            line("     ATIS from Poor.fmap: $poor")
            check("Poor.fmap changes the ATIS", poor != atisBefore && poor.startsWith("RKSO "), poor)
            check("the card says where its weather comes from", msg().contains("Poor.fmap") && v("pnlWx.lines").contains("from Poor.fmap"), msg().take(60))
            // the departure's own cell, read here straight from the file
            val osan = korea?.let { runBlocking { Repo.airportSet(it.airportSet) } }?.airports?.firstOrNull { it.name == "Osan AB" }
            val map = Fmap.read(File(camp, "Poor.fmap"))
            if (osan != null && map != null && korea != null) {
                val col = floor(osan.y / korea.sizeFt * map.cols).toInt()
                val row = map.rows - 1 - floor(osan.x / korea.sizeFt * map.rows).toInt()
                val cell = row * map.cols + col
                val temp = map.float(Fmap.Field.TEMPERATURE, cell).roundToInt()
                val qnh = map.float(Fmap.Field.PRESSURE, cell).toDouble()
                val inches = (qnh * 0.0295300 * 100.0).roundToInt()
                val want = (if (temp < 0) "M" + (-temp).toString().padStart(2, '0') else temp.toString().padStart(2, '0')) + "/"
                check("the ATIS is Osan's own cell (col $col, row $row): ${temp}°C, A$inches", v("lblAtis2").startsWith(want) && v("lblAtis2").contains("A$inches"), v("lblAtis2"))
            } else check("Osan and Poor.fmap for the cell check", false, "${osan?.name} ${map != null}")
            check("Force QNH from the map", v("txtForceQnh").isNotBlank(), v("txtForceQnh"))
            val fair = reload(File(camp, "Fair.fmap"))
            check("Fair.fmap gives other weather", fair != poor, fair)
            val v8 = reload(File(camp, "Save0.fmap"))
            check("a version 8 map (Save0.fmap) is read, with its visibility", v8.startsWith("RKSO ") && msg().contains("Save0.fmap"), v8)
            val twx = reload(File(camp, "Auto Save.twx"))
            line("     ATIS from Auto Save.twx: $twx")
            check("the save's own .twx (BMS 4.38, version 8, four-type model): the current type's table", msg().contains("Auto Save.twx") && twx.startsWith("RKSO "), msg().take(80))
            val twxBytes = File(camp, "Auto Save.twx").readBytes()
            val raw = Twx.read(twxBytes)
            val tw = (raw as? Twx.Read.Ok)?.twx
            // version 8 counts the type from 0 (0 sunny … 3 inclement): handed on as WDP's 1-4
            val type0 = java.nio.ByteBuffer.wrap(twxBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(24)
            check("Twx: version 8 read at its own offsets (model 2, type counted from 0, QNH 1020/1013/1005/994, 29/23/21/19 °C, heading model 1 = no direction)",
                tw != null && tw.version == 8 && tw.model == 2 && tw.condition == type0 + 1 && !tw.windHeld && tw.types.map { it.qnhMb[0] } == listOf(1020, 1013, 1005, 994) &&
                    tw.types.map { it.tempC[0] } == listOf(29, 23, 21, 19), (raw as? Twx.Read.Bad)?.why ?: tw.toString())
            for (name in listOf("Save0.twx", "Instant.twx", "TESTING.twx")) {
                val f = File(camp, name)
                if (!f.isFile) continue
                val r = Twx.read(f.readBytes())
                check("Twx reads $name (${f.length()} bytes)", r is Twx.Read.Ok, (r as? Twx.Read.Bad)?.why ?: (r as Twx.Read.Ok).twx.let { "v${it.version} model ${it.model} type ${it.condition}" })
            }
            WdpDialogs.stack.clear()
            answers.clear()
            runBlocking { card.reloadWx() }
            check("Cancel leaves the card's weather as it was", v("lblAtis1") + " / " + v("lblAtis2") == twx, v("lblAtis1"))

            // ---------------------------------------------------------------- 2. Codewords and Package Timing
            line("")
            line("== 2. Save/Load Codewords and Package Timing (.ini)")
            for ((timing, keys) in listOf(false to WdpFiles.CODEWORD_KEYS, true to WdpFiles.TIMING_KEYS)) {
                val section = if (timing) "PackageTiming" else "Codewords"
                keys.forEachIndexed { i, (_, box) -> card.plan.type(box, "$section $i") }
                val f = File(dir, "$section.ini")
                answer(f)
                runBlocking { card.saveIniFile(timing) }
                check("$section: the window is WDP's", asked.last().title == (if (timing) "Save Package Timing" else "Save Codewords") &&
                    asked.last().startDir == WdpFiles.DATACARDS && asked.last().fileName == "$section.ini" && asked.last().filters.single().label == "(*.ini)", lastAsked())
                val want = windowsIni(keys.mapIndexed { i, (k, _) -> Triple(section, k, "$section $i") })
                check("$section.ini is what WDP's INIWrite writes, byte for byte (${keys.size} keys)", f.isFile && latin(f) == want, if (f.isFile) latin(f).take(60) else "no file")
                keys.forEach { (_, box) -> card.plan.type(box, "") }
                answer(f)
                runBlocking { card.loadIniFile(timing) }
                check("$section: loaded back into every box", keys.withIndex().all { (i, kv) -> v(kv.second) == "$section $i" }, v(keys[0].second))
                // a file laid out as WDP writes it, read as Windows reads it
                val g = File(dir, "$section-wdp.ini")
                g.writeBytes(("[Other]\r\nA=1\r\n[" + section.uppercase() + "]\r\n" + keys[0].first.lowercase() + " = Alpha \r\n" + keys[1].first + "='Bravo'\r\n").toByteArray(Charsets.ISO_8859_1))
                answer(g)
                runBlocking { card.loadIniFile(timing) }
                check("$section: a WDP file read as Windows reads it (case, spaces, quotes; a missing key empties its box)",
                    v(keys[0].second) == "Alpha" && v(keys[1].second) == "Bravo" && v(keys[2].second) == "", "${v(keys[0].second)} ${v(keys[1].second)} '${v(keys[2].second)}'")
            }

            // ---------------------------------------------------------------- 3. Backup / Load DataCard (.bdc)
            line("")
            line("== 3. Backup DataCard / Load DataCard (.bdc)")
            card.values(listOf("pnlPage_2", "pnlBrief"))
            card.plan.type("txtJoker", "3100"); card.plan.type("txtALOW", "450"); card.plan.type("txtRoe1", "Test ROE")
            val bdc = File(dir, "$callsign.bdc")
            answer(bdc)
            runBlocking { card.saveDataCardFile("TestMission", pack) }
            val flightDir = File(planner, "DataCards\\TestMission\\$pack\\$cardCallsign")
            check("Backup DataCard: DataCards\\TestMission\\$pack\\$cardCallsign made, WDP's window there on $cardCallsign",
                flightDir.isDirectory && asked.last().title == "Backup DataCard" && asked.last().startDir == WdpFiles.DATACARDS + "\\TestMission\\$pack\\$cardCallsign" &&
                    asked.last().fileName == cardCallsign && asked.last().filters.single().label == "Backup DataCard (*.bdc)", lastAsked())
            val text = if (bdc.isFile) latin(bdc) else ""
            val sections = Regex("^\\[([^]]+)]", RegexOption.MULTILINE).findAll(text).map { it.groupValues[1] }.toList()
            // every section WDP writes, in WDP's order; [Map] is only WDP's own map position, which the app leaves out
            val wantSections = CardFile.TABLE.map { it.section }.distinct() - "Map"
            check("the file's sections are WDP's, in WDP's order (${wantSections.size})", sections == wantSections, sections.joinToString(","))
            check("Loadout Joker=3100, ALOW=450; Roe Roe1=Test ROE; Mission Name, PackageNr",
                DtcIni.read(text, "Loadout", "Joker") == "3100" && DtcIni.read(text, "Loadout", "ALOW") == "450" && DtcIni.read(text, "Roe", "Roe1") == "Test ROE" &&
                    DtcIni.read(text, "Mission", "PackageNr") == pack, DtcIni.read(text, "Loadout", "Joker"))
            check("WDP's values the app does not know are left out (Theater, the loadout's weapon ids, the attack, the map)",
                DtcIni.read(text, "Mission", "Theater").isEmpty() && DtcIni.read(text, "Loadout", "Station1").isEmpty() && !text.contains("[Map]"), "")
            // byte for byte: every key in WDP's order within its section, and Windows' own layout of those writes
            val entries = WdpFiles.entriesOf(text)
            val order = CardFile.TABLE.withIndex().associate { (i, e) -> (e.section + "|" + e.key).uppercase() to i }
            val inOrder = entries.groupBy { it.first }.values.all { keys -> keys.map { order[(it.first + "|" + it.second).uppercase()] ?: -1 }.let { ix -> ix.all { it >= 0 } && ix == ix.sorted() } }
            check("the .bdc is what WDP's INIWrite writes, byte for byte (${entries.size} keys, each in WDP's order)", inOrder && windowsIni(entries) == text, "")
            card.plan.type("txtJoker", "9999"); card.plan.type("txtRoe1", "changed")
            answer(bdc)
            runBlocking { card.loadDataCardFile() }
            check("Load DataCard: WDP's window, in the flight's folder now it is there", asked.last().title == "Load backup DataCard File" &&
                asked.last().startDir == WdpFiles.DATACARDS + "\\TestMission\\$pack\\$cardCallsign", lastAsked())
            check("Load DataCard puts the boxes back (Joker 3100, Roe1)", v("txtJoker") == "3100" && v("txtRoe1") == "Test ROE", "${v("txtJoker")} ${v("txtRoe1")}")
            // a .bdc laid out as WDP writes it (Windows' layout of WDP's own keys)
            val wdpBdc = File(dir, "wdp.bdc")
            wdpBdc.writeBytes(windowsIni(listOf(Triple("Mission", "Theater", "0"), Triple("Loadout", "Joker", "4200"), Triple("Loadout", "Bingo", "2100"),
                Triple("Roe", "Roe1", "From WDP"), Triple("Flight", "Lead_Note", "1688"))).toByteArray(Charsets.ISO_8859_1))
            answer(wdpBdc)
            runBlocking { card.loadDataCardFile() }
            check("a WDP-written .bdc loads (Joker 4200, Bingo 2100, Roe1)", v("txtJoker") == "4200" && v("txtBingo") == "2100" && v("txtRoe1") == "From WDP",
                "${v("txtJoker")} ${v("txtBingo")} ${v("txtRoe1")}")
            answer(File(dir, "again.bdc"))
            runBlocking { card.saveDataCardFile("TestMission", pack) }
            check("…and its Mission Theater goes back out in the next backup", DtcIni.read(latin(File(dir, "again.bdc")), "Mission", "Theater") == "0", "")

            // ---------------------------------------------------------------- 4. the DTC page's backup files
            line("")
            line("== 4. DTC: Load / Backup (EWS), Save and Open Callsign.ini, Personal PPT")
            val cart = File(dir, "Cartridge.ini")
            pilotCart?.copyTo(cart, overwrite = true)
            WdpDtcFixture.file = cart
            WdpDialogs.stack.clear()
            val dtc = DtcWiring(WdpDtcFixture.source())
            dtc.onMission(WdpMission(dtc = Dtc(modified = 1)))
            check("the DTC page has a cartridge", dtc.fileName == "Fixture.ini", dtc.fileName.orEmpty())
            val ews = File(dir, "Mine.ews")
            answer(ews)
            runBlocking { dtc.saveBackup(DtcFiles.Part.EWS) }
            val b = DtcFiles.backup(DtcFiles.Part.EWS)
            check("Backup (EWS): WDP's window, \"Save EWS backup files\" in Files\\EWS, ews files (*.ews)",
                asked.last().title == b.saveTitle && asked.last().startDir == WdpFiles.files("EWS") && asked.last().filters.single().label == "ews files (*.ews)", lastAsked())
            val ewsText = if (ews.isFile) latin(ews) else ""
            val cartText2 = latin(cart)
            val keys = WdpFiles.entriesOf(ewsText)
            fun same(a: String, b: String) = a == b || (a.toDoubleOrNull() != null && a.toDoubleOrNull() == b.toDoubleOrNull())
            // WDP's SaveCallsign_EWS writes every key it knows; each one the cartridge has comes out with its value
            val cartKeys = WdpFiles.entriesOf(cartText2).filter { it.first.equals("EWS", true) }
            check("the .ews is the cartridge's [EWS], key for key (${keys.size} keys written, ${cartKeys.size} in the cartridge)",
                ewsText.startsWith("[EWS]\r\n") && keys.all { it.first == "EWS" } && cartKeys.isNotEmpty() &&
                    cartKeys.all { (_, k, value) -> same(DtcIni.read(ewsText, "EWS", k), value.trim()) },
                cartKeys.firstOrNull { (_, k, value) -> !same(DtcIni.read(ewsText, "EWS", k), value.trim()) }?.let { "${it.second}: ${it.third} vs ${DtcIni.read(ewsText, "EWS", it.second)}" }.orEmpty())
            check("…laid out as WDP's INIWrite lays out a new file", ewsText == windowsIni(keys), "")
            // a WDP file without its header (WDP's CheckEwsHeader reads it as if it had one)
            val chaffKey = keys.firstOrNull { it.second.contains("Chaff BQ", ignoreCase = true) }?.second ?: "PGM 0 Chaff BQ"
            val headless = File(dir, "headless.ews")
            headless.writeBytes(keys.joinToString("") { (_, k, value) -> k + "=" + (if (k == chaffKey) "7" else value) + "\r\n" }.toByteArray(Charsets.ISO_8859_1))
            answer(headless)
            runBlocking { dtc.openBackup(DtcFiles.Part.EWS) }
            val again = File(dir, "again.ews")
            answer(again)
            runBlocking { dtc.saveBackup(DtcFiles.Part.EWS) }
            check("a WDP .ews with no [EWS] line loads ($chaffKey=7), and the next backup carries it",
                again.isFile && DtcIni.read(latin(again), "EWS", chaffKey) == "7", if (again.isFile) DtcIni.read(latin(again), "EWS", chaffKey) else "no file")
            // Systems carries WDP's HARM section, as WDP's SaveSystems writes it
            val sts = File(dir, "Mine.sts")
            answer(sts)
            runBlocking { dtc.saveBackup(DtcFiles.Part.SYSTEMS) }
            // WDP's SaveSystems: Hud, ICP, Cockpit View, OTW, Weapons, HARM, Laser — each only where the SYSTEMS tab includes it
            val stsSections = if (sts.isFile) Regex("^\\[([^]]+)]", RegexOption.MULTILINE).findAll(latin(sts)).map { it.groupValues[1] }.toList() else emptyList()
            check("Backup (SYSTEMS) writes WDP's systems sections, HARM among them", "HARM" in stsSections &&
                stsSections.all { s -> DtcFiles.Part.SYSTEMS.sections.any { it.equals(s, true) } }, stsSections.joinToString(","))
            // Save Callsign.ini File to another file: WDP writes it directly and it becomes the page's file
            val other = File(dir, "Other.ini")
            answer(other)
            runBlocking { dtc.saveCallsignFile() }
            check("Save Callsign.ini File: WDP's window (\"Save Callsign.ini File\", @config, Callsign DTC File|*.ini)",
                asked.last().title == "Save Callsign.ini File" && asked.last().startDir == "@config" && asked.last().filters.single().label == "Callsign DTC File", lastAsked())
            check("…the whole cartridge written there, no copy first, and it is the page's cartridge now",
                other.isFile && DtcIni.read(latin(other), "EWS", chaffKey) == "7" && dtc.fileName == "Other.ini" &&
                    dir.listFiles().orEmpty().none { it.name.startsWith("Other") && it.name != "Other.ini" }, dtc.fileName.orEmpty())
            answer(other)
            runBlocking { dtc.openCallsignFile() }
            check("Open Callsign.ini File from outside User\\Config reads it where it is", dtc.fileName == "Other.ini" && asked.last().title == "Open Callsign.ini File", dtc.fileName.orEmpty())
            val wdpPpi = File(dir, "wdp.ppi")
            wdpPpi.writeBytes("SA2\t164055.124511719\tSA-2\r\nAAA 48608.92578125 AAA\r\n".toByteArray(Charsets.ISO_8859_1))
            WdpDialogs.stack.clear()
            answer(wdpPpi)
            runBlocking { dtc.loadPersonalPpt() }
            check("a WDP .ppi (tabs as WDP's ReadPPT takes them) loads", WdpDialogs.stack.none { it is WdpMessage }, msg())
            val ppi = File(dir, "Mine.ppi")
            answer(ppi)
            runBlocking { dtc.savePersonalPpt() }
            val ppiLines = if (ppi.isFile) latin(ppi).split("\r\n").filter { it.isNotBlank() } else emptyList()
            check("Save Personal PPT files: WDP's window and WDP's lines (code range name)",
                asked.last().title == "Save Personal PPT files" && asked.last().startDir == WdpFiles.files("PPT\\Personal") && ppiLines.isNotEmpty() &&
                    ppiLines.all { it.split(' ').size >= 3 && it.split(' ')[1].toDoubleOrNull() != null && !it.split(' ')[1].contains('E') } &&
                    ppiLines.size == 2 && ppiLines[0].startsWith("SA2 164055.12"), ppiLines.joinToString(" | "))

            // ---------------------------------------------------------------- 5. pictures
            line("")
            line("== 5. Save Map (attack pages) and the Airport Schedule's Save JPG")
            val img = androidx.compose.ui.graphics.ImageBitmap(435, 435)
            val jpg = WdpPicture.jpeg(img)
            check("pictures are JPEG, as WDP saves them", jpg.size > 4 && jpg[0] == 0xFF.toByte() && jpg[1] == 0xD8.toByte(), "${jpg.size} bytes")
            val toss = File(dir, "TOSS.jpg")
            answer(toss)
            runBlocking { WdpAttackMap("TOSS").saveMapFile(jpg) }
            check("Save TOSS map: WDP's window (\"Save TOSS map\", SavedMaps, JPEG|*.jpg, TOSS) and the file",
                asked.last().title == "Save TOSS map" && asked.last().startDir == WdpFiles.SAVED_MAPS && asked.last().fileName == "TOSS" && toss.isFile && toss.readBytes().contentEquals(jpg), lastAsked())
            val sched = AptSchedule("Osan AB (RKSO)", emptyList())
            val w = AptScheduleWindow(sched, card = AptScheduleWindow.ScheduleCard("TestMission", pack, callsign) { _, _ -> })
            val sheet = scheduleImage(sched, null)
            check("the schedule sheet is WDP's 600 x 1200", sheet.width == 600 && sheet.height == 1200, "${sheet.width}x${sheet.height}")
            val sj = File(dir, "OsanSchedule.jpg")
            answer(sj)
            runBlocking { w.saveJpg(WdpPicture.jpeg(sheet), "TestMission", pack, callsign) }
            check("Save Airport Schedule: WDP's window (\"Save Airport Schedule\", the flight's DataCards folder, <field>Schedule.jpg) and the file where it was saved",
                asked.last().title == "Save Airport Schedule" && asked.last().fileName == "Osan ABSchedule.jpg" && sj.isFile, lastAsked())

            // ---------------------------------------------------------------- 6. Open mission's Browse…
            line("")
            line("== 6. Open mission… Browse (\"Open Campaign or TE\")")
            answer(File(camp, "Auto Save.cam"))
            val opened = runBlocking { CampaignBrowser.browse() }
            check("a save picked in the theater's campaign folder opens as a row of the list", opened?.endsWith("|Auto Save.cam") == true &&
                asked.last().title == "Open Campaign or TE" && asked.last().filters.map { it.label } == listOf("all", "(*.cam)", "(*.tac)", "(*.trn)"), opened.orEmpty())
            WdpDialogs.stack.clear()
            val outside = File(dir, "Elsewhere.cam")
            outside.writeBytes(ByteArray(16))
            answer(outside)
            val refused = runBlocking { CampaignBrowser.browse() }
            check("a save elsewhere is named, not opened", refused?.contains("not a theater's campaign folder") == true, refused.orEmpty().take(60))
        } catch (e: Throwable) {
            line("FAIL the check threw: ${e::class.simpleName}: ${e.message}")
            e.stackTrace.take(12).forEach { line("       at $it") }
        } finally {
            PcFiles.testAnswer = null
            WdpDtcFixture.file = null
        }
        return sb.toString()
    }

    /**
     * What `WritePrivateProfileString` leaves in a new file after these writes, one after another: each section in the
     * order it was first written, `[section]` on its own line, and its keys in the order they were first written, a
     * key written again keeping its place and taking the last value — every line ending CR LF. (Windows' own behaviour,
     * checked against the real API by `wdpref page Dtc`'s `ini` rows.)
     */
    fun windowsIni(entries: List<Triple<String, String, String>>): String {
        val sections = LinkedHashMap<String, LinkedHashMap<String, String>>()
        val names = HashMap<String, String>()
        for ((s, k, v) in entries) {
            val sk = s.uppercase()
            val sec = sections.getOrPut(sk) { names[sk] = s; LinkedHashMap() }
            val existing = sec.keys.firstOrNull { it.equals(k, ignoreCase = true) }
            if (existing != null) sec[existing] = v else sec[k] = v
        }
        val out = StringBuilder()
        for ((sk, keys) in sections) {
            out.append('[').append(names[sk]).append("]\r\n")
            for ((k, v) in keys) out.append(k).append('=').append(v).append("\r\n")
        }
        return out.toString()
    }
}
