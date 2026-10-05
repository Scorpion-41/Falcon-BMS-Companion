package com.bmscompanion.desktop.bridge

import com.bmscompanion.app.data.mission.Board
import com.bmscompanion.app.data.mission.Briefing
import com.bmscompanion.app.data.mission.EzRun
import com.bmscompanion.app.data.mission.Live
import com.bmscompanion.app.data.mission.NavPoint
import com.bmscompanion.app.data.mission.Voice
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * Developer checks, run as `"BMS Companion.exe" --selftest out.txt` (or `./gradlew :desktop:run --args="--selftest out.txt"`):
 * - `--selftest out.txt`: shared memory struct sizes, and the briefing parser on a real printed briefing kept as
 *   a fixture (`resources/bridge/sample_briefing.txt`, with the names taken out);
 * - `--dumpstrings out.txt`: raw StringData ids and values from a running BMS (to verify the id table);
 * - `--eztest <EZBoards folder> out.txt`: runs EZBoards exactly like the app button does (use a copy of the folder).
 * - `--cfgtest <a copy of the BMS folder> out.txt`: the Config page's backup, profiles and edits, checked on disk.
 * - `--api <path>[,<path>…] out.txt`: API responses (e.g. /api/info,/api/mission) from Falcon BMS on this PC, one per line.
 * - `--maprender <theater> <folder> [xFt,yFt]`: map styles and landmarks rendered to PNGs (see MapRender).
 * - `--wdprender <folder> [briefing.txt] [cartridge.ini]`: every Planner page drawn to a PNG, designer and with the
 *   mission loaded, plus a report of what each wiring filled (see WdpRender).
 * - `--wdptypetest <folder> [briefing.txt] [cartridge.ini]`: every box of the Planner typed into key by key in a
 *   headless scene, each checked against what a Windows TextBox shows, with a picture of each (see WdpTypeTest).
 * - `--mfdbench out.txt [folder]`: what the MFD pictures cost on this PC, the first 1.3.8 test build's reader against today's (see MfdBench).
 * - `--mfdrender <folder>`: the MFDs card in every state, at a phone's, a tablet's and a PC's width (see MfdRender).
 * - `--updatetest out.txt [download]`: what the About page sees on GitHub — the releases newer than this build, the
 *   file this platform would fetch and the checksum it would check it against. With `download`, it fetches that
 *   file and verifies it, without installing anything.
 * - `--missiontest <a copy of the BMS folder> <scratch APPDATA> out.txt`: the Mission section's two modes, EZBoards
 *   mode and WDP mode, Populate from Planner and the snapshot it keeps, in-process (see MissionSourceTest).
 *
 * Round 3, the Planner's integration (R3-PLAN; each check in its own file, owned by the batch that builds it — the
 * lines here are fixed). A report line starting with `FAIL` makes the process exit 1; too few arguments print the
 * usage and exit 2 instead of opening the app's window:
 * - `--theatertest out.txt <root>` (TheaterTest), `--contracttest out.txt <fixture copy>` (ContractTest);
 * - `--camtest out.txt <root>`, `--camdump <file> out.txt` (CampTest), `--camsource out.txt <root>` (CamSourceTest);
 * - `--atotargettest <copy> out.txt [save] [theater]` (AtoTargetsTest: the Planner's ATO Target List);
 * - `--plantest <parse|route|store> <fixture copy> [<scratch APPDATA>] out.txt` (PlanTest);
 * - `--tesavetest <copy of a Data/Campaign> out.txt` (TeSaveTest);
 * - `--ddstest out.txt [<fixture>]` (DdsTest), `--kbprinttest <copy> out.txt [page.png…]` (KbPrintTest);
 * - `--planmergetest out.txt [<picture folder>]` (PlanMergeTest), `--planviewsrender <folder>` (PlanViewsRender);
 * - `--planneroutcome <shell|browser|print|printpages|guide|tanker|all> <outDir> [more…]` (PlannerOutcome);
 * - `--mapinteltest out.txt <a copy of a BMS folder> [--theater=<name>] [<save> [<flight>]]` (MapIntelTest: the Map
 *   page's units, search radars, airfield owners, JSTARS and magnetic variation).
 * Every one may take more arguments after those, which are handed to it as they are.
 *
 * Every developer check runs with the write guard on ([DevGuard]): a route that would write into a BMS folder refuses
 * unless that folder is plainly a copy.
 */
object SelfTest {
    /** What the process exits with once a check has run: 1 when its report has a FAIL line, 2 for a usage error. */
    @Volatile var exitCode = 0

    /** Arguments the program itself takes; they are not developer checks. */
    private val APP_FLAGS = setOf("--tray", "--route")

    /** The round-3 checks and how to call them, for the usage line when they are called with too few arguments. */
    private val ROUND3 = mapOf(
        "--theatertest" to "--theatertest out.txt <root>",
        "--contracttest" to "--contracttest out.txt <fixture copy>",
        "--camtest" to "--camtest out.txt <fixture root>",
        "--camdump" to "--camdump <file> out.txt",
        "--atotargettest" to "--atotargettest <a copy of a BMS folder> out.txt [save] [theater]",
        "--camsource" to "--camsource out.txt <fixture root>",
        "--plantest" to "--plantest <parse|route|store> <fixture copy> [<scratch APPDATA>] out.txt",
        "--tesavetest" to "--tesavetest <copy of a Data/Campaign> out.txt",
        "--ddstest" to "--ddstest out.txt [<fixture>]",
        "--kbprinttest" to "--kbprinttest <copy> out.txt [page.png…]",
        "--planmergetest" to "--planmergetest out.txt [<picture folder>]",
        "--planviewsrender" to "--planviewsrender <folder>",
        "--planneroutcome" to "--planneroutcome <shell|browser|print|printpages|guide|tanker|all> <outDir> [more…]",
        "--filestest" to "--filestest out.txt <scratch folder> [<picture folder>]",
        "--wdpfilestest" to "--wdpfilestest out.txt <scratch folder>",
        "--zoomtest" to "--zoomtest out.txt",
        "--mapinteltest" to "--mapinteltest out.txt <a copy of a BMS folder> [--theater=<name>] [<save> [<flight>]]",
        "--attackmaptest" to "--attackmaptest <a copy of the BMS folder> out.txt",
        "--radiotest" to "--radiotest out.txt <a copy of the BMS folder>",
        "--hostiletest" to "--hostiletest out.txt",
    )

    /** Writes a check's report, and marks the run failed when a line of it starts with FAIL. */
    private fun report(out: File, text: String) {
        runCatching { out.absoluteFile.parentFile?.mkdirs() }
        out.writeText(text)
        val failed = text.lineSequence().count { it.startsWith("FAIL") }
        println("${out.path}: " + if (failed == 0) "no FAIL lines" else "$failed FAIL line(s)")
        if (failed > 0 && exitCode == 0) exitCode = 1
    }

    /** What a check that is not built yet reports: a FAIL line naming the batch that builds it. */
    fun notBuilt(check: String, batch: String): String = "FAIL: $check is not built yet (batch $batch).\n"

    fun run(args: Array<String>): Boolean {
        if (args.isEmpty() || !args[0].startsWith("--") || args[0] in APP_FLAGS) return false
        // A developer check from here on: nothing it does may write the real install (DevGuard). Cleared again below
        // when the arguments turn out not to be a check, so the program a pilot runs never has it.
        System.setProperty(DevGuard.PROPERTY, "1")
        val json = Bridge.json
        when {
            args.size == 2 && args[0] == "--selftest" -> {
                // A real printed briefing, with the names taken out, kept as the parser's fixture: the thing worth
                // checking is that a file BMS actually wrote still reads the same way after a change.
                val sample = SelfTest::class.java.getResourceAsStream("/bridge/sample_briefing.txt")
                    ?.readBytes()?.toString(Charsets.UTF_8).orEmpty()
                val (fd, fd2) = SharedMemoryReader.structSizes
                File(args[1]).writeText(buildString {
                    appendLine("sizeof(FlightData)=$fd sizeof(FlightData2)=$fd2")
                    appendLine("sample briefing: ${sample.length} characters")
                    appendLine(json.encodeToString(Briefing.serializer(), BriefingParser.parse(sample)))
                    SharedMemoryReader.parseNavPoint("NP:56,PT,2910660.5,258538.0,0.0,52.1;PT:\"SA-5\",364567.0,0;")?.let { appendLine(json.encodeToString(NavPoint.serializer(), it)) }
                    appendLine(json.encodeToString(Voice.serializer(), SharedMemoryReader.parseVoice("Ouranos5|PAXX,None,Dragnet5,Larissa,Larissa,Nea Anchialos")))
                    System.getenv("BMSC_TEST_BOARD_HTML")?.let(::File)?.takeIf { it.isFile }?.let { appendLine(json.encodeToString(Board.serializer(), EzBoardsRunner.parseHtml(it.readText()))) }
                })
            }
            args.size == 2 && args[0] == "--dumpstrings" -> {
                val snap = SharedMemoryReader.read()
                File(args[1]).writeText(buildString {
                    appendLine("available=${snap.available} flying=${snap.flying} version=${snap.version}")
                    snap.strings.toSortedMap().forEach { (id, v) -> appendLine("${id.toString().padStart(3)} ${StringId.name(id).padEnd(24)} $v") }
                    snap.navPointStrings.forEach { appendLine("NP $it") }
                    appendLine("hsiBits=0x${snap.hsiBits.toString(16).uppercase().padStart(8, '0')} lightBits=0x${snap.lightBits.toString(16).uppercase().padStart(8, '0')} pilotsOnline=${snap.pilotsOnline} status=${snap.pilotStatus.joinToString(",")} ded0=${snap.live.ded.firstOrNull()}")
                })
            }
            // --osbtest out.txt : both MFD bezels as BMS is publishing them right now, index by index. Run it with
            // BMS in 3D and an MFD on a page you can see, and the legends here have to be the legends on the glass —
            // which is the only way to check that the ring runs clockwise from the top left, five to a side.
            args.size == 2 && args[0] == "--osbtest" -> {
                val l = SharedMemoryReader.read().live
                File(args[1]).writeText(buildString {
                    appendLine("left=${l.mfdLeft.size} right=${l.mfdRight.size} (20 each when BMS is publishing them)")
                    listOf("LEFT" to l.mfdLeft, "RIGHT" to l.mfdRight).forEach { (name, keys) ->
                        appendLine()
                        appendLine("$name MFD")
                        val side = { i: Int -> if (i < 5) "top" else if (i < 10) "right" else if (i < 15) "bottom" else "left" }
                        keys.forEachIndexed { i, k ->
                            appendLine("  OSB ${(i + 1).toString().padStart(2)} ${side(i).padEnd(7)} ${if (k.inverted) "[X]" else "[ ]"} '${k.a}' / '${k.b}'")
                        }
                    }
                })
            }
            // --mfdkeystest out.txt [BMS folder] : each MFD rocker half -> BMS callback -> key and modifiers, from the
            // key file the pilot file names (BmsKeys.rockerReport). Reads only; no key is ever sent.
            args.size in 2..3 && args[0] == "--mfdkeystest" -> {
                val install = BmsInstall().apply { refresh(args.getOrNull(2)) }
                report(File(args[1]), BmsKeys.rockerReport(install))
            }
            // --wxtest <a copy of a Data folder> out.txt : the whole life of the Weather page against a folder that
            // is not the real install — back up, write, read back, restore — with every file compared byte for byte
            // against what it started as. Reversibility is the promise this feature makes, so it is what is tested.
            // the real install, checking on the file that nothing but the edited keys moved.
            // --wdpporttest <reference.csv> out.txt : the ported ballistics against the real program’s answers
            args.size == 3 && args[0] == "--wdpporttest" -> File(args[2]).writeText(wdpPortTest(File(args[1]), File(args[2])))
            // --wdppagetest <page> <reference> out.txt : any ported WDP page against the real one (WdpPageTests.kt)
            args.size == 4 && args[0] == "--wdppagetest" -> File(args[3]).writeText(wdpPageTest(args[1], File(args[2]), File(args[3])))
            // --acmicoords <recording> out.txt [theater id] : the Planner's latitude and longitude against the ones BMS
            // writes into its own ACMI recording, for every theater (AcmiCoords.kt, D26); reads the recording only
            args.size in 3..4 && args[0] == "--acmicoords" -> File(args[2]).writeText(AcmiCoords.run(File(args[1]), args.getOrNull(3)))
            // --wdptosstest <toss.tsv> out.txt : the ported TOSS page against the real one, label for label
            args.size == 3 && args[0] == "--wdptosstest" -> File(args[2]).writeText(wdpTossTest(File(args[1]), File(args[2])))
            // --wdptest <the Weapon Delivery Planner folder> out.txt : the remote, against the real program
            args.size == 3 && args[0] == "--wdptest" -> File(args[2]).writeText(wdpTest(File(args[1]), File(args[2]))) 
            args.size == 3 && args[0] == "--wxtest" -> File(args[2]).writeText(wxTest(File(args[1])))
            // --wxgentest <reference.tsv> out.txt : the weather generator against WeatherGen's own model, run on the
            // JVM by tools/wxref/wxref.clj (WxGenTest.kt)
            args.size == 3 && args[0] == "--wxgentest" -> File(args[2]).writeText(WxGenTest.run(File(args[1])))
            // --rttselftest <folder> out.txt : the display reader against a texture this check publishes itself,
            // so everything but BMS's own pixel format is proved without BMS running at all.
            args.size == 3 && args[0] == "--rttselftest" -> File(args[2]).writeText(RttSelfTest.run(File(args[1])))
            // --mfdbench out.txt [folder] : what the MFD pictures cost, the first 1.3.8 test build's reader against today's, on a texture
            // this check publishes itself (MfdBench.kt); the folder gets each format's picture to look at
            args.size >= 2 && args[0] == "--mfdbench" -> File(args[1]).writeText(MfdBench.run(args.getOrNull(2)?.let(::File)))
            // --mfdrender <folder> : the MFDs card in every state the glass explains, at three widths (MfdRender.kt)
            args.size >= 2 && args[0] == "--mfdrender" -> File(args[1], "mfdrender.txt").also { File(args[1]).mkdirs() }.writeText(MfdRender.run(File(args[1])))
            // --rtttest <folder> : every cockpit display Falcon BMS is exporting, written out as PNGs, plus what
            // the layout says. With BMS in 3D this is the whole of the display export proved in one command: the
            // pictures either are the cockpit or they are not, and a wrong row pitch shears them visibly.
            args.size == 3 && args[0] == "--rtttest" -> {
                val dir = File(args[1]).apply { mkdirs() }
                val state = RttTextures.state()
                val report = StringBuilder()
                report.appendLine("available=${state.available} settingOn=${state.settingOn} reason=${state.reason}")
                report.appendLine("texture ${state.width} x ${state.height}")
                for (a in state.areas) report.appendLine("  ${a.id.padEnd(9)} ${a.label.padEnd(10)} ${a.width} x ${a.height}")
                for (d in RttTextures.Display.entries) {
                    val png = RttTextures.png(d)
                    if (png == null) { report.appendLine("${d.id}: nothing"); continue }
                    File(dir, "${d.id}.png").writeBytes(png)
                    report.appendLine("${d.id}: ${png.size} bytes -> ${d.id}.png")
                }
                report.appendLine()
                report.append(RttTextures.diagnose())
                File(args[2]).writeText(report.toString())
            }
            args.size == 3 && args[0] == "--eztest" -> File(args[2]).writeText(json.encodeToString(EzRun.serializer(), EzBoardsRunner().generate(args[1], auto = false)))
            // --cfgtest <a copy of the BMS folder> out.txt : the Config page's whole life against a folder that is
            // not the real install. Every step is checked by reading the files back, because this is the one part of
            // the program that writes into a BMS folder and the only proof that matters is what is on disk.
            args.size == 3 && args[0] == "--cfgtest" -> File(args[2]).writeText(cfgTest(File(args[1])))
            // --cartridgetest <a folder holding a copy of a cartridge> out.txt : the Planner DTC page's writer
            // (CartridgeStore) against a copy — written at once as WDP writes it, only the edited keys, idempotent
            args.size == 3 && args[0] == "--cartridgetest" -> File(args[2]).writeText(cartridgeTest(File(args[1])))
            // --atotest out.txt : what the mission file BMS is flying says the other flights will do
            // --axistest <save.cam> <dtc.ini> out.txt : which way round the campaign grid runs, checked against a
            // file we already read in the app's own convention
            args.size == 4 && args[0] == "--axistest" -> {
                val install = BmsInstall().apply { refresh(Bridge.settings.value.BmsDirOverride) }
                val save = File(args[1])
                val dtc = File(args[2])
                val routes = PlannedRoutes.read(PlannedRoutes.Source(save, save.parentFile.parentFile), install)
                val stpts = dtc.readLines().mapNotNull { line ->
                    val v = line.substringAfter("=", "").split(",").map { it.trim() }
                    if (!line.startsWith("target_") || v.size < 3) null
                    else (v[0].toDoubleOrNull() ?: return@mapNotNull null) to (v[1].toDoubleOrNull() ?: return@mapNotNull null)
                }.filter { it.first != 0.0 || it.second != 0.0 }
                fun near(a: Double, b: Double) = kotlin.math.abs(a - b) < 2.0 * 6076.12
                var asRead = 0
                var swapped = 0
                for ((sx, sy) in stpts) {
                    if (routes.any { r -> r.points.any { near(it.x, sx) && near(it.y, sy) } }) asRead++
                    if (routes.any { r -> r.points.any { near(it.y, sx) && near(it.x, sy) } }) swapped++
                }
                File(args[3]).writeText(buildString {
                    appendLine("routes: ${routes.size}, steerpoints: ${stpts.size}")
                    appendLine("matches as read (x,y): $asRead")
                    appendLine("matches swapped (y,x): $swapped")
                })
            }
            // --trackstest <save.cam> <dtc.ini> out.txt : the whole path, on a save and a flight plan that belong together
            args.size == 4 && args[0] == "--trackstest" -> {
                val install = BmsInstall().apply { refresh(Bridge.settings.value.BmsDirOverride) }
                val save = File(args[1])
                val routes = PlannedRoutes.read(PlannedRoutes.Source(save, save.parentFile.parentFile), install)
                val own = File(args[2]).readLines().mapNotNull { line ->
                    if (!line.startsWith("target_")) return@mapNotNull null
                    val v = line.substringAfter("=", "").split(",").map { it.trim() }
                    if (v.size < 3) null else (v[0].toDoubleOrNull() ?: 0.0) to (v[1].toDoubleOrNull() ?: 0.0)
                }.filter { it.first != 0.0 || it.second != 0.0 }
                val mine = routes.count { r -> own.count { (x, y) -> r.points.any { kotlin.math.hypot(it.x - x, it.y - y) < 1.5 * 6076.12 } } >= 3 }
                val support = routes.filter {
                    val n = it.missionName.orEmpty()
                    n.contains("REFUEL", true) || n.contains("AWACS", true) || n.contains("AEW", true) || n.contains("ABCCC", true)
                }
                // Your own flight plan and the campaign's copy of it are the same places twice over, so the gap
                // between them is whatever the reading gets wrong — a constant gap is a constant error.
                val gaps = own.mapNotNull { (x, y) ->
                    routes.flatMap { it.points }.minByOrNull { kotlin.math.hypot(it.x - x, it.y - y) }
                        ?.takeIf { kotlin.math.hypot(it.x - x, it.y - y) < 3.0 * 6076.12 }
                        ?.let { Triple(it.x - x, it.y - y, kotlin.math.hypot(it.x - x, it.y - y)) }
                }
                File(args[3]).writeText(buildString {
                    appendLine("routes: ${routes.size}, flights matching your plan: $mine")
                    if (gaps.isNotEmpty()) {
                        val dx = gaps.sumOf { it.first } / gaps.size
                        val dy = gaps.sumOf { it.second } / gaps.size
                        appendLine("steerpoints matched: ${gaps.size} of ${own.size}")
                        appendLine("mean gap: x %+.0f ft (%+.2f nm), y %+.0f ft (%+.2f nm), distance %.0f ft (%.2f nm)"
                            .format(dx, dx / 6076.12, dy, dy / 6076.12, gaps.sumOf { it.third } / gaps.size, gaps.sumOf { it.third } / gaps.size / 6076.12))
                        appendLine("each: " + gaps.joinToString(", ") { "%.2f nm".format(it.third / 6076.12) })
                        appendLine("half a grid cell would be ${"%.0f".format(0.5 * 1000.0 * 3.27998)} ft (${"%.2f".format(0.5 * 1000.0 * 3.27998 / 6076.12)} nm)")
                    }
                    appendLine("tanker and AWACS tracks: ${support.size}")
                    support.take(4).forEach { r ->
                        val hold = r.points.filter { it.action == 8 }
                        appendLine("--- ${r.missionName}: ${r.points.size} points, ${hold.size} on station")
                        r.points.forEachIndexed { i, p ->
                            appendLine("    %d  x %.0f  y %.0f  %.0f ft  action %d  arrive %d  depart %d".format(i, p.x, p.y, p.altFt, p.action, p.arriveMs, p.departMs))
                        }
                    }
                })
            }
            // --basetest out.txt : the take-off and landing points of every planned route against the airfields
            // we already know the positions of. A route that starts and ends at a runway is read right; a constant
            // gap on hundreds of them is a constant error in the reading.
            args.size == 2 && args[0] == "--basetest" -> {
                val install = BmsInstall().apply { refresh(Bridge.settings.value.BmsDirOverride) }
                val source = PlannedRoutes.sourceFor(install, install.theater)
                val out = File(args[1])
                if (source == null) { out.writeText("no campaign save found"); return true }
                val routes = PlannedRoutes.read(source, install)
                val theater = kotlinx.coroutines.runBlocking {
                    val all = com.bmscompanion.app.data.Repo.index().theaters
                    all.firstOrNull { it.name.contains(install.theater ?: "~", true) || (install.theater ?: "").contains(it.name, true) } ?: all.first()
                }
                val fields = kotlinx.coroutines.runBlocking { com.bmscompanion.app.data.Repo.airportSet(theater.airportSet) }.airports
                fun nearest(x: Double, y: Double): Triple<String, Double, Double>? =
                    fields.minByOrNull { kotlin.math.hypot(it.x - x, it.y - y) }?.let { Triple(it.name, it.x - x, it.y - y) }
                fun report(sb: StringBuilder, what: String, pts: List<Pair<Double, Double>>) {
                    val gaps = pts.mapNotNull { (x, y) -> nearest(x, y) }
                    val near = gaps.filter { kotlin.math.hypot(it.second, it.third) < 10 * 6076.12 }
                    sb.appendLine("$what: ${pts.size} points, ${near.size} within 10 nm of a field")
                    if (near.isEmpty()) return
                    val dx = near.sumOf { it.second } / near.size
                    val dy = near.sumOf { it.third } / near.size
                    val dist = near.sumOf { kotlin.math.hypot(it.second, it.third) } / near.size
                    sb.appendLine("  mean offset to the field: x %+.0f ft (%+.2f nm), y %+.0f ft (%+.2f nm); mean distance %.0f ft (%.2f nm)"
                        .format(dx, dx / 6076.12, dy, dy / 6076.12, dist, dist / 6076.12))
                    sb.appendLine("  closest few: " + near.sortedBy { kotlin.math.hypot(it.second, it.third) }.take(6)
                        .joinToString(", ") { "${it.first} %.2f nm".format(kotlin.math.hypot(it.second, it.third) / 6076.12) })
                }
                out.writeText(buildString {
                    appendLine("theater ${theater.name}, ${fields.size} fields, ${routes.size} routes")
                    appendLine("half a grid cell = %.0f ft (%.2f nm)".format(0.5 * 1000.0 * 3.27998, 0.5 * 1000.0 * 3.27998 / 6076.12))
                    report(this, "first point of each route", routes.mapNotNull { it.points.firstOrNull()?.let { p -> p.x to p.y } })
                    report(this, "second point of each route", routes.mapNotNull { it.points.getOrNull(1)?.let { p -> p.x to p.y } })
                    report(this, "last point of each route", routes.mapNotNull { it.points.lastOrNull()?.let { p -> p.x to p.y } })
                    appendLine()
                    appendLine("first points with x == 0: ${routes.count { it.points.firstOrNull()?.x == 0.0 }} of ${routes.size}")
                    appendLine("first points with y == 0: ${routes.count { it.points.firstOrNull()?.y == 0.0 }} of ${routes.size}")
                })
            }
            // --carriertest out.txt : every base of every theater, from what BMS gives for it (the first word of its
            // name, the comm ladder, the DED TACAN) back to the base — the carrier home plate that always came out as the Carl Vinson
            args.size == 2 && args[0] == "--carriertest" -> File(args[1]).writeText(CarrierTest.run())
            // --teamtest out.txt : the parts of the current save, and the team part laid out as bytes — to find who is
            // allied with whom, which the Tacview stream does not say (its "Coalition" is the country).
            args.size == 2 && args[0] == "--teamtest" -> {
                val install = BmsInstall().apply { refresh(Bridge.settings.value.BmsDirOverride) }
                val source = PlannedRoutes.sourceFor(install, install.theater)
                val out = File(args[1])
                if (source == null) { out.writeText("no campaign save found"); return true }
                val blob = source.file.readBytes()
                val parts = MissionArchive.parts(blob)
                out.writeText(buildString {
                    appendLine("file: ${source.file.name} (${blob.size} bytes), theater ${install.theater}")
                    parts.forEach { appendLine("  ${it.name}  at ${it.at}  ${it.length} bytes") }
                    val teams = TeamRelations.read(source.file)
                    val word = mapOf(0 to "-", 1 to "ALLIED", 2 to "friendly", 3 to "neutral", 4 to "hostile", 5 to "WAR")
                    appendLine()
                    appendLine("teams read: ${teams.size}")
                    teams.forEach { t ->
                        appendLine("  %d %-14s %s".format(t.number, t.name, teams.joinToString("  ") { o -> "%s=%s".format(o.name, word[t.stance[o.number]]) }))
                    }
                    parts.filter { it.name.endsWith(".tea", true) }.forEach { p ->
                        val b = blob.copyOfRange(p.at, p.at + p.length)
                        appendLine()
                        appendLine("=== ${p.name} raw, ${b.size} bytes")
                        for (row in b.indices step 32) {
                            val hex = (row until minOf(row + 32, b.size)).joinToString(" ") { "%02x".format(b[it].toInt() and 0xFF) }
                            val txt = (row until minOf(row + 32, b.size)).joinToString("") { val c = b[it].toInt() and 0xFF; if (c in 32..126) c.toChar().toString() else "." }
                            appendLine("%6d  %-95s  %s".format(row, hex, txt))
                        }
                    }
                })
            }
            // --sidetest <recording.txt> <callsign> <stop at seconds> out.txt : a recording replayed through the Tacview
            // client up to that moment, with ownship where that callsign was, and the picture it gives — who is
            // friendly, who hostile, who neutral, who is in your flight. Sides come from the current save's alliances.
            args.size == 5 && args[0] == "--sidetest" -> {
                val install = BmsInstall().apply { refresh(Bridge.settings.value.BmsDirOverride) }
                val teams = TeamRelations.current(install, install.theater)
                val client = TacviewClient().apply { relation = { from, toward -> TeamRelations.stance(teams, from, toward) } }
                val stopAt = args[3].toDouble()
                var ownId: String? = null
                File(args[1]).useLines { lines ->
                    for (line in lines) {
                        if (line.startsWith("#") && (line.substring(1).toDoubleOrNull() ?: 0.0) > stopAt) break
                        if (ownId == null && line.contains("CallSign=${args[2]},")) ownId = line.substringBefore(',')
                        client.feed(line)
                    }
                }
                // where that callsign is now, from the client's own reading of it
                val probe = client.snapshot(null, null).contacts.firstOrNull { it.id == ownId }
                val pic = client.snapshot(probe?.x, probe?.y)
                File(args[4]).writeText(buildString {
                    appendLine("teams from the save: ${teams.size}; own ${args[2]} = id $ownId")
                    val air = pic.contacts.filter { it.kind == "air" || it.kind == "heli" }
                    fun side(c: com.bmscompanion.app.data.mission.Contact) = when {
                        c.own -> "OWN"; c.wingman -> "WINGMAN"; c.friendly -> "friendly"; c.neutral -> "neutral"; else -> "HOSTILE"
                    }
                    air.groupBy { "${it.coalition} -> ${side(it)}" }.toSortedMap().forEach { (k, v) -> appendLine("  %-28s %d".format(k, v.size)) }
                    appendLine("own: " + air.filter { it.own }.joinToString { it.group ?: it.id })
                    appendLine("wingmen: " + air.filter { it.wingman }.joinToString { it.group ?: it.id })
                    // and what the old rule — same coalition name — would have called hostile that is not
                    val wrong = air.filter { !it.own && it.friendly && !it.coalition.equals(pic.contacts.firstOrNull { o -> o.own }?.coalition, true) }
                    appendLine("allies of another nation, hostile under the old rule: ${wrong.size} (${wrong.map { it.coalition }.distinct()})")
                })
            }
            // --teamfile out.txt <save> [<save>…] : each save's team table, or why it could not be read
            args.size >= 3 && args[0] == "--teamfile" -> {
                val word = mapOf(0 to "-", 1 to "ALLY", 2 to "frnd", 3 to "neut", 4 to "host", 5 to "WAR")
                File(args[1]).writeText(buildString {
                    args.drop(2).forEach { path ->
                        val f = File(path)
                        val teams = runCatching { TeamRelations.read(f) }.getOrDefault(emptyList())
                        appendLine("=== ${f.parentFile?.parentFile?.name}/${f.name}: ${if (teams.isEmpty()) "NOT READ" else "${teams.size} teams"}")
                        teams.forEach { t ->
                            appendLine("  %d %-18s %s".format(t.number, t.name, teams.joinToString(" ") { o -> word[t.stance[o.number]] ?: "?" }))
                        }
                    }
                })
            }
            // --supporttest <save> <your flight callsign, e.g. Tiger1> out.txt : your route taken from the save by callsign,
            // then every tanker and AWACS put through the same tests Bridge.supportTracks applies, with the reason for
            // each one kept or dropped — for "why was the AWACS a circle and not a track".
            args.size == 4 && args[0] == "--supporttest" -> {
                val install = BmsInstall().apply { refresh(Bridge.settings.value.BmsDirOverride) }
                val save = File(args[1])
                val routes = PlannedRoutes.read(PlannedRoutes.Source(save, save.parentFile.parentFile), install)
                fun hm(ms: Long) = "%02d:%02d".format((ms / 3_600_000) % 24, (ms / 60_000) % 60)
                File(args[3]).writeText(buildString {
                    appendLine("save ${save.name}: ${routes.size} routes")
                    appendLine("mission names in it: " + routes.mapNotNull { it.missionName }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.joinToString { "${it.key} (${it.value})" })
                    appendLine("callsigns: " + routes.mapNotNull { it.callsign }.sorted().joinToString(" "))
                    val mine = routes.firstOrNull { it.callsign.equals(args[2], true) }
                    if (mine == null) { appendLine("no route with callsign ${args[2]}"); return@buildString }
                    val timed = mine.points.filter { it.arriveMs > 0 }
                    val from = timed.minOf { it.arriveMs }
                    val to = timed.maxOf { maxOf(it.arriveMs, it.departMs) }
                    appendLine("yours ${mine.callsign} ${mine.missionName}: ${hm(from)}-${hm(to)}, ${mine.points.size} points")
                    routes.filter { r ->
                        val n = r.missionName.orEmpty()
                        n.contains("REFUEL", true) || n.contains("AWACS", true) || n.contains("AEW", true) || n.contains("ABCCC", true) ||
                            n.contains("EW", true) || n.contains("C2", true) || n.contains("JSTARS", true)
                    }.forEach { r ->
                        val n = r.missionName.orEmpty()
                        val role = when {
                            n.contains("REFUEL", true) -> "Tanker"
                            n.contains("AWACS", true) || n.contains("AEW", true) || n.contains("ABCCC", true) -> "AWACS"
                            else -> "NOT RECOGNISED"
                        }
                        val longest = r.points.indices.maxByOrNull { r.points[it].departMs - r.points[it].arriveMs } ?: 0
                        val st = r.points[longest]
                        val overlap = !(st.departMs < from || st.arriveMs > to)
                        val verdict = if (role == "NOT RECOGNISED") "DROPPED: mission name" else if (!overlap) "DROPPED: not on station while you fly" else "KEPT"
                        appendLine("--- ${r.callsign} '$n' -> $role; station at point $longest ${hm(st.arriveMs)}-${hm(st.departMs)}; $verdict")
                        r.points.forEachIndexed { i, p -> appendLine("      %d  %s-%s  action %d  %.0f ft".format(i, hm(p.arriveMs), hm(p.departMs), p.action, p.altFt)) }
                    }
                })
            }
            // --anytype <save> out.txt : routes read at every double-written type, flight or not by the class table
            args.size == 3 && args[0] == "--anytype" -> {
                val install = BmsInstall().apply { refresh(Bridge.settings.value.BmsDirOverride) }
                val save = File(args[1])
                val dir = save.parentFile.parentFile
                val types = PlannedRoutes.flightTypes(dir, install).orEmpty()
                val names = PlannedRoutes.missionNames(dir, install)
                val blob = save.readBytes()
                val uni = MissionArchive.parts(blob).first { it.name.endsWith(".uni", true) }
                val body = MissionArchive.contents(blob, uni)!!.bytes
                File(args[2]).writeText(buildString {
                    appendLine("class table flight types: ${types.size} (range ${types.minOrNull()}..${types.maxOrNull()})")
                    var at = 0
                    var outside = 0
                    while (at + 12 < body.size) {
                        val ty = MissionArchive.int16(body, at)
                        if (ty in 1..9000 && MissionArchive.int16(body, at + 10) == ty && ty !in types) {
                            PlannedRoutes.routeAt(body, at, names)?.let { r ->
                                outside++
                                appendLine("  type $ty NOT in the flight types: ${r.callsign} '${r.missionName}' ${r.points.size} points")
                            }
                        }
                        at++
                    }
                    appendLine("routes read at types the table does not call flights: $outside")
                })
            }
            // --atotest out.txt [save] : the current theater's newest save, or the one named
            args.size in 2..3 && args[0] == "--atotest" -> {
                val install = BmsInstall().apply { refresh(Bridge.settings.value.BmsDirOverride) }
                val source = args.getOrNull(2)?.let { File(it) }?.let { PlannedRoutes.Source(it, it.parentFile.parentFile) }
                    ?: PlannedRoutes.sourceFor(install, install.theater)
                val out = File(args[1])
                if (source == null) {
                    out.writeText("no campaign save or engagement found under ${install.baseDir}")
                } else {
                    val routes = PlannedRoutes.read(source, install)
                    out.writeText(buildString {
                        appendLine("theater: ${install.theater} -> ${source.theaterDir.name}")
                        appendLine("file: ${source.file.path} (${source.file.length()} bytes)")
                        appendLine("flights with a route: ${routes.size}")
                        appendLine(PlannedRoutes.describe(source, install))
                        routes.groupingBy { it.missionName ?: "mission ${it.mission}" }.eachCount()
                            .entries.sortedByDescending { it.value }.take(20)
                            .forEach { appendLine("  ${it.value} x ${it.key}") }
                        routes.filter { (it.missionName ?: "").contains("REFUEL", true) || (it.missionName ?: "").contains("AWACS", true) }
                            .take(4).forEach { r ->
                                appendLine("--- ${r.missionName} (${r.mission}), ${r.points.size} points")
                                r.points.forEach { p ->
                                    appendLine("    x %.0f  y %.0f  %.0f ft  arrive %d  action %d".format(p.x, p.y, p.altFt, p.arriveMs, p.action))
                                }
                            }
                    })
                }
            }
            args.size == 3 && args[0] == "--api" -> {
                Bridge.start()
                Thread.sleep(2500) // first tick: files and Tacview
                // several paths separated by commas: one response per line. A path may carry a query
                // (`/api/cfg/lines?kind=user&profile=1`), which is split off here the way the server does it —
                // without that, every route that reads a parameter answered "not found".
                File(args[2]).writeText(
                    args[1].split(',').joinToString("\n") { p ->
                        val query = p.substringAfter('?', "").split('&').filter { '=' in it }
                            .associate { it.substringBefore('=') to it.substringAfter('=') }
                        Bridge.handle(ApiRequest("GET", p.substringBefore('?'), query)).body.toString(Charsets.UTF_8)
                    },
                )
                Bridge.stop()
            }
            args.size >= 2 && args[0] == "--updatetest" -> {
                com.bmscompanion.app.data.Platform.fetchText = { url -> com.bmscompanion.desktop.fetchTextFromWeb(url) }
                com.bmscompanion.app.data.Platform.installer = com.bmscompanion.desktop.PcInstaller
                com.bmscompanion.app.data.Platform.nowMillis = { System.currentTimeMillis() }
                val out = StringBuilder()
                kotlinx.coroutines.runBlocking {
                    val updates = com.bmscompanion.app.data.update.Updates
                    // --updatetest out.txt [download] [<version to pretend to be>]
                    args.drop(2).firstOrNull { it.firstOrNull()?.isDigit() == true }?.let { updates.baseline = it }
                    updates.check(force = true)
                    val state = updates.state.value
                    out.appendLine("running=${com.bmscompanion.app.AppVersion.NAME} comparing against ${updates.baseline} error=${state.error}")
                    out.appendLine("newer=${state.newer.size}")
                    state.newer.forEach { r ->
                        val asset = updates.assetFor(r)
                        out.appendLine("  ${r.version} (${r.date}) \"${r.title}\" notes=${r.body?.length ?: 0} chars")
                        out.appendLine("    asset=${asset?.name} ${asset?.size} bytes digest=${asset?.digest}")
                    }
                    // the same path the About page takes, stopping short of running the installer
                    if (args.getOrNull(2) == "download") {
                        val r = state.latest
                        if (r == null) out.appendLine("nothing newer to download") else {
                            // what the About page would be showing while it runs, one line per change
                            val watcher = launch {
                                var last = ""
                                while (true) {
                                    updates.state.value.progress?.let { p ->
                                        val line = "    ${p.stage} ${p.text}"
                                        if (line != last) { out.appendLine(line); last = line }
                                    }
                                    delay(700)
                                }
                            }
                            val name = updates.fetch(r)
                            watcher.cancel()
                            out.appendLine("fetched=$name verified=${name != null}")
                            out.appendLine("in the cache: ${com.bmscompanion.desktop.PcInstaller.cachedFiles()}")
                            com.bmscompanion.desktop.PcInstaller.clearCache()
                            out.appendLine("cache cleared: ${com.bmscompanion.desktop.PcInstaller.cachedFiles()}")
                        }
                    }
                }
                File(args[1]).writeText(out.toString())
            }
            args.size >= 3 && args[0] == "--maprender" -> MapRender.run(args[1], File(args[2]),
                args.getOrNull(3)?.split(',')?.let { it[0].toDouble() to it[1].toDouble() })
            // --wdptypetest <folder> [briefing.txt] [cartridge.ini] : typing into every box, key by key (WdpTypeTest.kt)
            args.size >= 2 && args[0] == "--wdptypetest" -> WdpTypeTest.run(File(args[1]), args.getOrNull(2)?.let(::File), args.getOrNull(3)?.let(::File))
            // --wdpclicktest <out.txt> [briefing.txt] [cartridge.ini] [page] : every control pressed (WdpClickTest.kt)
            args.size >= 2 && args[0] == "--wdpclicktest" -> WdpClickTest.run(File(args[1]), args.getOrNull(2)?.let(::File), args.getOrNull(3)?.let(::File), args.getOrNull(4))
            // --dtcfromtest <a copy of the BMS folder> out.txt : the DTC page's From mission… on the copy's briefed flight (DtcFromMissionTest.kt)
            args.size == 3 && args[0] == "--dtcfromtest" -> report(File(args[2]), DtcFromMissionTest.run(File(args[1])))
            // --wdpmaptest <a copy of the BMS folder> <out folder> : the Planner's Map page, its taps and its pictures (WdpMapTest.kt)
            args.size == 3 && args[0] == "--wdpmaptest" -> report(File(args[2], "wdpmap.txt"), WdpMapTest.run(File(args[1]), File(args[2])))
            // --attackmaptest <a copy of the BMS folder> out.txt : one attack, drawn one way on every map; Send to DataCard and
            // the two saves byte for byte; IP STPT at the VRP (AttackMapTest.kt)
            args.size == 3 && args[0] == "--attackmaptest" -> report(File(args[2]), AttackMapTest.run(File(args[1])))
            // --zoomtest out.txt : wheel, keys, double click and pinch zoom on every map, headless (ZoomTest.kt)
            args.size == 2 && args[0] == "--zoomtest" -> report(File(args[1]), ZoomTest.run())
            // --hostiletest out.txt : hostile contacts off by default and kept back at the source; the setting, its route and
            // its migration (HostileTest.kt; run it with a scratch APPDATA, whose settings it changes and puts back)
            args.size == 2 && args[0] == "--hostiletest" -> report(File(args[1]), HostileTest.run())
            // --mappicturerender <a copy of the BMS folder> <out folder> : the Mission map and the Planner's Map page of the
            // same flight, side by side (MissionPictureRender.kt)
            args.size == 3 && args[0] == "--mappicturerender" -> report(File(args[2], "mappicture.txt"), MissionPictureRender.run(File(args[1]), File(args[2])))
            // --missiontest <a copy of the BMS folder> <scratch APPDATA> out.txt : EZBoards mode and WDP mode, Populate from
            // Planner, the snapshot kept (MissionSourceTest.kt)
            args.size == 4 && args[0] == "--missiontest" -> report(File(args[3]), MissionSourceTest.run(listOf(args[1], args[2])))
            args[0] == "--missiontest" -> {
                System.err.println("usage: \"BMS Companion.exe\" --missiontest <a copy of the BMS folder> <scratch APPDATA> out.txt")
                exitCode = 2
            }
            // --samebrieftest <a copy of the BMS folder> <scratch APPDATA> out.txt : part 12 of --missiontest alone — the
            // printed flight populated in WDP mode shows the same briefing as EZBoards mode (SameBriefingTest.kt)
            args.size == 4 && args[0] == "--samebrieftest" -> report(File(args[3]), MissionSourceTest.run(listOf(args[1], args[2]), only = "same"))
            args[0] == "--samebrieftest" -> {
                System.err.println("usage: \"BMS Companion.exe\" --samebrieftest <a copy of the BMS folder> <scratch APPDATA> out.txt")
                exitCode = 2
            }
            // --wdpoutcome <page|all> <outDir> [briefing.txt] [cartridge.ini] [WDP source folder] : 2 to 5 arguments after the
            // flag — what every Planner control does, with a picture of the page after each action (WdpOutcome.kt)
            args.size in 3..6 && args[0] == "--wdpoutcome" -> { WdpOutcome.run(args.drop(1)) }
            // --wdpguicompare <folder> <briefing> <cartridge> <WDP Setup.ini> <stpt> <tN,tE,tZ> <ipN,ipE,ipZ> [HeightMap.raw] : the pages in WDP's window state (WdpGuiCompare.kt)
            args.size >= 8 && args[0] == "--wdpguicompare" -> File(args[1], "wdpguicompare.txt").also { File(args[1]).mkdirs() }.writeText(WdpGuiCompare.run(args.drop(1)))
            // --wdprender <folder> [briefing.txt] [cartridge.ini] : every Planner page drawn headless (WdpRender.kt)
            args.size >= 2 && args[0] == "--wdprender" -> {
                val dir = File(args[1])
                val report = WdpRender.run(dir, args.getOrNull(2)?.let(::File), args.getOrNull(3)?.let(::File))
                File(dir, "wdprender.txt").writeText(report)
            }
            // --wxrender <folder> : the Weather page's generator drawn headless at three widths and four times (WxRender.kt)
            args.size >= 2 && args[0] == "--wxrender" -> {
                val dir = File(args[1])
                val report = WxRender.run(dir)
                if (dir.isDirectory) File(dir, "wxrender.txt").writeText(report) else println(report)
            }
            // --deckrender <folder> : every carrier deck drawn headless, and the jet's place on each (DeckRender.kt)
            args.size >= 2 && args[0] == "--deckrender" -> {
                val dir = File(args[1])
                File(dir.also { it.mkdirs() }, "deckrender.txt").writeText(DeckRender.run(dir))
            }
            // --taxirender <folder> : the Taxi page at tablet, phone and PC shapes, and the room its cards get (TaxiRender.kt)
            args.size >= 2 && args[0] == "--taxirender" -> {
                val dir = File(args[1])
                report(File(dir.also { it.mkdirs() }, "taxirender.txt"), TaxiRender.run(dir))
            }
            // --divertrender <folder> : the airfield page's divert block — its coordinates against the KTO AIP's, and the
            // page at phone, tablet and PC sizes (DivertRender.kt)
            args.size >= 2 && args[0] == "--divertrender" -> {
                val dir = File(args[1])
                report(File(dir.also { it.mkdirs() }, "divertrender.txt"), DivertRender.run(dir))
            }
            // --docshots <a copy of the BMS folder> <out folder> [shot…] : the whole app drawn headless for the README's
            // pictures, the copy read through the built-in bridge (DocShotsRender.kt)
            args.size >= 3 && args[0] == "--docshots" -> File(args[2]).let { d ->
                d.mkdirs(); report(File(d, "docshots.txt"), DocShotsRender.run(File(args[1]), d, args.drop(3)))
            }
            // --divertcheck out.txt <a BMS folder> [airports.csv] : the divert block for every theater and field — coordinates,
            // radios, the ship rule, BMS's AIPs read from the folder's Docs (read only), OurAirports as a sanity check (DivertCheck.kt)
            args.size in 3..4 && args[0] == "--divertcheck" -> report(File(args[1]), DivertCheck.run(File(args[2]), args.getOrNull(3)?.let { File(it) }))
            // ---- round 3: the Planner's integration (see the list above; these lines are fixed) ----
            args.size >= 3 && args[0] == "--theatertest" -> report(File(args[1]), TheaterTest.run(File(args[2]), args.drop(3)))
            args.size >= 3 && args[0] == "--contracttest" -> report(File(args[1]), ContractTest.run(File(args[2]), args.drop(3)))
            args.size >= 3 && args[0] == "--camtest" -> report(File(args[1]), CampTest.run(File(args[2]), args.drop(3)))
            args.size >= 3 && args[0] == "--camdump" -> report(File(args[2]), CampTest.dump(File(args[1]), args.drop(3)))
            // --atotargettest <a copy of a BMS folder> out.txt [save] [theater] : the ATO Target List from a save (AtoTargetsTest.kt)
            args.size >= 3 && args[0] == "--atotargettest" -> report(File(args[2]), AtoTargetsTest.run(File(args[1]), args.drop(3)))
            args.size >= 3 && args[0] == "--camsource" -> report(File(args[1]), CamSourceTest.run(File(args[2]), args.drop(3)))
            // --plantest <part> <inputs…> out.txt : the report goes to the last argument
            args.size >= 4 && args[0] == "--plantest" -> report(File(args.last()), PlanTest.run(args[1], args.toList().subList(2, args.size - 1)))
            args.size >= 3 && args[0] == "--tesavetest" -> report(File(args[2]), TeSaveTest.run(File(args[1]), args.drop(3)))
            args.size >= 2 && args[0] == "--ddstest" -> report(File(args[1]), DdsTest.run(args.getOrNull(2)?.let(::File), args.drop(3)))
            args.size >= 3 && args[0] == "--kbprinttest" -> report(File(args[2]), KbPrintTest.run(File(args[1]), args.drop(3)))
            args.size >= 2 && args[0] == "--planmergetest" -> report(File(args[1]), PlanMergeTest.run(args.getOrNull(2)?.let(::File), args.drop(3)))
            args.size >= 2 && args[0] == "--planviewsrender" -> File(args[1]).let { d ->
                d.mkdirs(); report(File(d, "planviewsrender.txt"), PlanViewsRender.run(d, args.drop(2)))
            }
            args.size >= 3 && args[0] == "--planneroutcome" -> File(args[2]).let { d ->
                d.mkdirs(); report(File(d, "planneroutcome.txt"), PlannerOutcome.run(args[1], d, args.drop(3)))
            }
            // the Planner's file windows: /api/files, PcFiles, Windows' dialog built unshown, the remote window drawn
            args.size >= 3 && args[0] == "--filestest" -> report(File(args[1]), PcFilesTest.run(File(args[2]), args.getOrNull(3)?.let(::File)))
            // --wdpfilestest out.txt <scratch folder> : the Planner's file buttons as WDP's, in WDP's formats (WdpFilesTest.kt)
            args.size >= 3 && args[0] == "--wdpfilestest" -> report(File(args[1]), WdpFilesTest.run(File(args[2])))
            // --mapinteltest out.txt <a copy of a BMS folder> [--theater=<name>] [<save> [<flight>]] : the Map page's
            // /api/campaign/mapintel and /magvar read from a copy, read only (MapIntelTest.kt)
            args.size >= 3 && args[0] == "--mapinteltest" -> report(File(args[1]), MapIntelTest.run(File(args[2]), args.drop(3)))
            // --radiotest out.txt <a copy of the BMS folder> : BMS's debug log read for the radio, the taxi automation's
            // decisions, and deleting old logs on a scratch folder of the copy (RadioTest.kt)
            args.size == 3 && args[0] == "--radiotest" -> report(File(args[1]), RadioTest.run(File(args[2])))
            // a round-3 check with too few arguments: say how to call it, rather than falling through to the app window
            args[0] in ROUND3 -> {
                System.err.println("usage: \"BMS Companion.exe\" ${ROUND3[args[0]]}")
                exitCode = 2
            }
            else -> {
                System.clearProperty(DevGuard.PROPERTY)
                return false
            }
        }
        if (exitCode != 0) kotlin.system.exitProcess(exitCode)
        return true
    }

    /**
     * The Config page's whole life, against a copy of a BMS folder.
     *
     * Refuses to run on anything that looks like a live install — a folder holding `Falcon BMS.exe` — because the
     * BMS folder is read only to this program and a check that writes into it is exactly the accident this rule
     * exists to prevent. Copy `User/Config` into an empty folder and point this at that.
     */
    /**
     * The Weather page's whole life against a copy of a Data folder.
     *
     * The promise this feature makes is that everything it does can be undone, so that is what is checked, and it
     * is checked on the bytes: the four maps are hashed before anything happens, backed up, written with a weather
     * that is nothing like what was there, read back to confirm the numbers arrived, written a second time to
     * confirm edits do not compound, and then restored — and only a hash equal to the one taken at the start
     * counts as restored.
     */
    private fun wxTest(data: File): String = buildString {
        fun sha(f: File) = java.security.MessageDigest.getInstance("SHA-256").digest(f.readBytes())
            .joinToString("") { "%02x".format(it) }

        if (File(data, "../Bin/x64/Falcon BMS.exe").let { it.isFile } || File(data, "../Falcon BMS.exe").isFile) {
            appendLine("REFUSED: that looks like a real Falcon BMS install. Point this at a copy.")
            return@buildString
        }
        // This writes, so it runs only on a copy under the temp folder: a check whose folder argument came out
        // wrong once wrote into a real cartridge. Walk up as well, in case the copy was taken of a whole install.
        run {
            val temp = File(System.getProperty("java.io.tmpdir")).canonicalPath.trimEnd('\\', '/')
            if (!data.canonicalPath.startsWith(temp + File.separator, ignoreCase = true)) {
                appendLine("REFUSED: $data is not under the temp folder ($temp). Copy a Data folder there first.")
                return@buildString
            }
            var up: File? = data.canonicalFile
            repeat(5) {
                if (up != null && File(up, "Bin/x64/Falcon BMS.exe").isFile) {
                    appendLine("REFUSED: $data is inside a real Falcon BMS install. Point this at a copy.")
                    return@buildString
                }
                up = up?.parentFile
            }
        }
        val store = WeatherStore(BmsInstall(), dataOverride = data)
        var state = store.state()
        appendLine("available=${state.available} theaters=${state.theaters.size} error=${state.error}")
        val th = state.theaters.firstOrNull()
        if (th == null) { appendLine("FAIL: no theater with weather maps under ${data.path}"); return@buildString }
        appendLine("theater: ${th.name} (${th.id}) backedUp=${th.backedUp}")
        for (m in th.models) appendLine("  ${m.id.padEnd(10)} readable=${m.readable} edited=${m.edited} ${m.weather}")

        val campaign = File(th.dir)
        val before = WeatherStore.Model.entries.filter { File(campaign, it.file).isFile }
            .associate { it.id to sha(File(campaign, it.file)) }
        appendLine()
        appendLine("hashes before: " + before.entries.joinToString { "${it.key}=${it.value.take(12)}" })

        var failures = 0
        fun check(name: String, ok: Boolean, detail: String = "") {
            appendLine((if (ok) "ok   " else "FAIL ") + name + (if (detail.isEmpty()) "" else "  — " + detail))
            if (!ok) failures++
        }

        state = store.backUp(th.id)
        check("backup taken", state.theaters.first().backedUp, state.error ?: "")
        val backupDir = File(campaign, WeatherStore.BACKUP_DIR)
        check("backup folder holds the originals", before.keys.all { id ->
            val m = WeatherStore.Model.of(id)!!
            File(backupDir, m.file).isFile && sha(File(backupDir, m.file)) == before[id]
        })
        check("a plain-English README is there for a pilot with no app", File(backupDir, "README.txt").isFile)

        // a weather nothing like the stock one, so nothing can pass by accident
        val wanted = com.bmscompanion.app.data.mission.Wx(
            type = 3, tempC = -7.0, pressureMb = 987.0, visibilityKm = 3.5,
            cloudBaseFt = 1200.0, cover = 7, cloudSize = 4.5, shower = true, windDirDeg = 275.0, windKts = 23.0, windAloftKts = 96.0, veerDeg = 4.0,
        )
        val first = WeatherStore.Model.entries.first { before.containsKey(it.id) }
        state = store.set(th.id, first.id, wanted)
        check("write accepted", state.error == null, state.error ?: "")
        var got = state.theaters.first().models.first { it.id == first.id }
        check("marked as yours", got.edited)
        fun near(a: Double, b: Double, tol: Double = 0.15) = kotlin.math.abs(a - b) <= tol
        check("cloud base read back", near(got.weather.cloudBaseFt, wanted.cloudBaseFt), "${got.weather.cloudBaseFt}")
        check("visibility read back", near(got.weather.visibilityKm, wanted.visibilityKm), "${got.weather.visibilityKm}")
        check("temperature read back", near(got.weather.tempC, wanted.tempC), "${got.weather.tempC}")
        check("pressure read back", near(got.weather.pressureMb, wanted.pressureMb), "${got.weather.pressureMb}")
        check("wind direction read back", near(got.weather.windDirDeg, wanted.windDirDeg), "${got.weather.windDirDeg}")
        check("surface wind read back in knots", near(got.weather.windKts, wanted.windKts), "${got.weather.windKts}")
        check("wind aloft read back in knots", near(got.weather.windAloftKts, wanted.windAloftKts), "${got.weather.windAloftKts}")
        check("type read back", got.weather.type == wanted.type, "${got.weather.type}")
        // cover goes into the file as BMS's code and comes back as the middle of its okta range: 7 oktas is BKN,
        // written as 9 and read back as 6
        check(
            "cover read back as the same category",
            com.bmscompanion.app.data.weather.WxCover.ofOktas(got.weather.cover) == com.bmscompanion.app.data.weather.WxCover.ofOktas(wanted.cover),
            "${got.weather.cover}",
        )
        check("towering cumulus read back under both names", got.weather.towering && got.weather.shower)
        check("file is still the size BMS made it", File(campaign, first.file).length() == File(backupDir, first.file).length())

        // and the same write again: an edit that started from the last edit would drift
        val afterOne = sha(File(campaign, first.file))
        store.set(th.id, first.id, wanted)
        check("writing the same weather twice gives the same file", sha(File(campaign, first.file)) == afterOne)
        // a different one, then back: also identical, because every write starts from the original
        store.set(th.id, first.id, wanted.copy(tempC = 30.0, windKts = 2.0))
        store.set(th.id, first.id, wanted)
        check("edits do not compound", sha(File(campaign, first.file)) == afterOne)

        // Every cell, not just the first one.
        //
        // The wind is the reason this check exists: it is stored as ten levels per cell rather than as ten
        // grids, and reading it the other way returns the right answer for cell zero and nonsense everywhere
        // else. A check that only looked at the first cell passed for weeks while the wind was being scrambled
        // across the whole theater, so this one reads the middle of the map and the last cell as well.
        run {
            val f = File(campaign, first.file)
            val raw = f.readBytes()
            val hdr = 44
            fun f32(i: Int): Float {
                var bits = 0
                for (k in 3 downTo 0) bits = (bits shl 8) or (raw[hdr + i * 4 + k].toInt() and 0xFF)
                return Float.fromBits(bits)
            }
            fun i32(i: Int): Int {
                var v = 0
                for (k in 3 downTo 0) v = (v shl 8) or (raw[hdr + i * 4 + k].toInt() and 0xFF)
                return v
            }
            val cols = 59; val rows = 59; val cells = cols * rows
            val kmh = { kts: Double -> kts * 1.852 }
            var bad = 0
            var first10 = ""
            for (cell in listOf(0, 1, 37, cells / 2, cells - 1)) {
                if (i32(0 * cells + cell) != wanted.type) { bad++; if (first10.isEmpty()) first10 = "type at cell " + cell }
                if (kotlin.math.abs(f32(23 * cells + cell) - wanted.cloudBaseFt) > 1) { bad++; if (first10.isEmpty()) first10 = "cloud base at cell " + cell }
                if (kotlin.math.abs(f32(27 * cells + cell) - wanted.visibilityKm) > 0.1) { bad++; if (first10.isEmpty()) first10 = "visibility at cell " + cell }
                if (kotlin.math.abs(f32(25 * cells + cell) - wanted.cloudSize) > 0.05) { bad++; if (first10.isEmpty()) first10 = "cloud size at cell " + cell }
                // BKN is BMS's code 9, not 7 oktas; towering cumulus is array 26
                if (i32(24 * cells + cell) != 9) { bad++; if (first10.isEmpty()) first10 = "cover code at cell " + cell + ": " + i32(24 * cells + cell) }
                if (i32(26 * cells + cell) != 1) { bad++; if (first10.isEmpty()) first10 = "towering at cell " + cell }
                // the wind: ten levels for this cell, ramped from the surface to aloft
                for (lv in 0 until 10) {
                    val t = lv.toDouble() / 9.0
                    val want = kmh(wanted.windKts + (wanted.windAloftKts - wanted.windKts) * t)
                    val got = f32(3 * cells + cell * 10 + lv).toDouble()
                    if (kotlin.math.abs(got - want) > 0.5) {
                        bad++
                        if (first10.isEmpty()) first10 = "wind speed cell " + cell + " level " + lv + ": " + got + " wanted " + want
                    }
                    val wantDir = ((wanted.windDirDeg + wanted.veerDeg * lv) % 360.0 + 360.0) % 360.0
                    val gotDir = f32(13 * cells + cell * 10 + lv).toDouble()
                    if (kotlin.math.abs(gotDir - wantDir) > 0.5) {
                        bad++
                        if (first10.isEmpty()) first10 = "wind dir cell " + cell + " level " + lv + ": " + gotDir + " wanted " + wantDir
                    }
                }
            }
            check("every sampled cell holds the weather that was written, wind included", bad == 0, first10)
        }

        state = store.restore(th.id, null)
        check("restore reported no error", state.error == null, state.error ?: "")
        for ((id, hash) in before) {
            check("$id is byte for byte what it was", sha(File(campaign, WeatherStore.Model.of(id)!!.file)) == hash)
        }
        check("nothing is marked as yours any more", store.state().theaters.first().models.none { it.edited })

        // and by hand, the way the README says: copy the backups over the top
        store.set(th.id, first.id, wanted)
        for ((id, _) in before) {
            val m = WeatherStore.Model.of(id)!!
            // onto the file's own name on disk (BMS ships "Sunny.fmap"), as copying in Explorer would
            File(backupDir, m.file).copyTo(File(campaign, m.file).let { if (it.exists()) it.canonicalFile else it }, overwrite = true)
        }
        check("restoring by hand works too", before.all { (id, hash) ->
            sha(File(campaign, WeatherStore.Model.of(id)!!.file)) == hash
        })

        wxGeneratedTest(store, th.id, campaign, before.mapKeys { WeatherStore.Model.of(it.key)!!.file }, ::sha) { name, ok, detail ->
            check(name, ok, detail)
        }

        // A ready-made map changed by something else — a BMS update shipping new maps — is not blamed on this app,
        // and a new copy is taken without losing the old one (the app's record is what tells the two apart).
        appendLine()
        appendLine("a ready-made map changed by a BMS update, not by this app:")
        run {
            val f = File(campaign, first.file).let { if (it.exists()) it.canonicalFile else it }
            val shipped = f.readBytes()
            val updated = shipped.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
            f.writeBytes(updated)
            var s = store.state(detail = th.id)
            var m = s.theaters.first { it.id == th.id }.models.first { it.id == first.id }
            check("it differs from its copy", m.differs)
            check("… and is not blamed on this app (no Restore offered)", !m.edited)
            check("… and the copy's date is given", m.copiedAt != null)
            s = store.refreshBackup(th.id, null)
            check("a new copy is taken", s.error == null, s.error ?: "")
            m = s.theaters.first { it.id == th.id }.models.first { it.id == first.id }
            check("after it the map matches its copy", !m.differs && !m.edited)
            check("the copy is the file as it is now", sha(File(backupDir, first.file)) == sha(f))
            val older = File(backupDir, WeatherStore.OLDER).listFiles()?.flatMap { it.listFiles()?.toList().orEmpty() }.orEmpty()
            check("the earlier copy is kept in ${WeatherStore.OLDER}", older.any { it.name.equals(first.file, true) && sha(it) == before[first.id] })
            // and one this app wrote is still its own to undo, never taken as a new copy
            store.set(th.id, first.id, wanted)
            s = store.refreshBackup(th.id, first.id)
            m = s.theaters.first { it.id == th.id }.models.first { it.id == first.id }
            check("a map this app wrote is refused a new copy, and offered Restore", s.error != null && m.edited, s.error ?: "no error")
            // the copy back as it was taken, and the live map too: the rest of this check found them so
            File(backupDir, first.file).writeBytes(shipped)
            f.writeBytes(shipped)
            check("… and put back", !store.state(detail = th.id).theaters.first { it.id == th.id }.models.first { it.id == first.id }.differs)
        }

        // A backup folder an earlier version made keeps that version's README until the page lists the theater.
        run {
            val readme = File(backupDir, "README.txt")
            readme.writeText("BMS Companion — weather backup\n(the words an earlier version wrote)\n")
            WeatherStore(BmsInstall(), dataOverride = data).state()
            val text = readme.readText()
            check("listing the theater brings an earlier version's README up to date", text.contains("Map Model") && text.contains(WeatherStore.OLDER))
        }

        // Theaters without the four ready-made maps (Hellas: no map at all; Hellas WCP: SAT maps; LHTO: update maps
        // only) are offered too, the grid taken from another version 5 map or BMS's usual 59 x 59.
        appendLine()
        val bare = store.state().theaters.filter { !it.stockMaps && it.blocked == null }
        if (bare.isEmpty()) appendLine("(no theater without the four ready-made maps in this copy: that part skipped)")
        for (b in bare) {
            val bs = store.state(detail = b.id).theaters.first { it.id == b.id }
            appendLine("${b.name}: grid from ${bs.gridFrom}, BMS update maps ${bs.bmsUpdates}")
            check("${b.name} is offered, and waits to be switched on", !bs.backedUp)
            var s = store.backUp(b.id)
            check("${b.name}: switched on, with its README", s.error == null && s.theaters.first { it.id == b.id }.backedUp &&
                File(File(bs.dir, WeatherStore.BACKUP_DIR), "README.txt").isFile, s.error ?: "")
            s = store.writeGenerated(b.id, "Test Bare", com.bmscompanion.app.data.weather.WxGenParams(seed = 7.0))
            val out = Fmap.read(File(bs.dir, "BMSC Test Bare.fmap"))
            check("${b.name}: a generated map is written, version 5, 59 x 59", s.error == null && out != null && out.writable && out.cols == 59 && out.rows == 59, s.error ?: "")
            s = store.removeGenerated(b.id, "Test Bare")
            check("${b.name}: and removed again", s.error == null && !File(bs.dir, "BMSC Test Bare.fmap").exists(), s.error ?: "")
        }
        val blocked = store.state().theaters.filter { it.blocked != null }
        blocked.forEach { appendLine("listed, not writable: ${it.name} — ${it.blocked}") }

        appendLine()
        appendLine(if (failures == 0) "PASS — everything this writes can be undone" else "FAIL — $failures check(s)")
    }

    /**
     * The DTC page's writer against a folder standing in for `User/Config`, holding a copy of one cartridge. The
     * edits are made the way the page makes them — the cartridge loaded twice, a steerpoint and an EWS program
     * changed on one, WDP's save run over both and the difference taken — and every claim is checked on disk. The
     * cartridge is written the way WDP writes it: at once, with no copy taken first and nothing to switch on.
     */
    private fun cartridgeTest(dir: File): String = buildString {
        var failures = 0
        fun check(what: String, ok: Boolean) { if (!ok) failures++; appendLine("${if (ok) "ok  " else "FAIL"} $what") }
        var up: File? = dir
        repeat(4) { if (up != null && File(up, "Bin/x64/Falcon BMS.exe").isFile) { appendLine("REFUSED: $dir is inside a real Falcon BMS install. Point this at a copy."); return@buildString }; up = up?.parentFile }
        // this writes, so it runs only on a copy under the temp folder: a run whose folder argument came out wrong
        // once wrote into a real cartridge
        val temp = File(System.getProperty("java.io.tmpdir")).canonicalPath.trimEnd('\\', '/')
        if (!dir.canonicalPath.startsWith(temp + File.separator, ignoreCase = true)) {
            appendLine("REFUSED: $dir is not under the temp folder ($temp). Copy the cartridge there first."); return@buildString
        }
        val cart = dir.listFiles { f -> f.isFile && f.extension.equals("ini", true) }?.firstOrNull()
            ?: run { appendLine("REFUSED: no .ini cartridge in $dir"); return@buildString }
        // the folder the second part makes: a run over it has already changed the cartridge
        if (File(dir, "earlier-version").exists()) { appendLine("REFUSED: this has run here before; copy the cartridge again."); return@buildString }
        val callsign = cart.nameWithoutExtension
        val store = CartridgeStore(BmsInstall(), configOverride = dir)
        val original = cart.readBytes()
        val text = String(original, Charsets.ISO_8859_1)
        fun names(d: File) = d.walkTopDown().filter { it != d }.map { it.relativeTo(d).path }.sorted().toList()
        val namesBefore = names(dir)

        // the edits, as the page makes them
        fun page() = com.bmscompanion.app.data.wdp.DtcPage().also { p ->
            p.m.minorPart = 38; p.m.build = 40000; p.load(); p.getCampFile(text, cart.name)
        }
        val base = page()
        val edited = page()
        edited.m.stpt[1].falconY += 1000f
        edited.m.ews.program!![0].chaffBQ = 7
        val a = com.bmscompanion.app.data.wdp.DtcSave.saveCallsign(text, base.m) { base.applyHarm() }
        val b = com.bmscompanion.app.data.wdp.DtcSave.saveCallsign(text, edited.m) { edited.applyHarm() }
        val edits = com.bmscompanion.app.data.wdp.DtcEdits.between(a.text, b.text)
        appendLine("edits: " + edits.joinToString { "[${it.section}] ${it.key}=${it.value}" })
        check("the untouched page gives no edits", com.bmscompanion.app.data.wdp.DtcEdits.between(a.text, a.text).isEmpty())
        check("two changes give two keys: STPT target_1 and EWS PGM 0 Chaff BQ",
            edits.map { it.key.lowercase() }.toSet() == setOf("target_1", "pgm 0 chaff bq"))

        var s = store.state(callsign)
        check("the cartridge is found and read whole", s.available && s.text == text)
        check("its path is the file's, for the page to name", s.path == cart.path)
        check("a save with no edits changes nothing", store.save(callsign, emptyList()).error == null && cart.readBytes().contentEquals(original))

        // the first save writes at once, as WDP's Save DTC does
        s = store.save(callsign, edits)
        check("the first save goes through at once, with no backup step: ${s.message ?: s.error}", s.error == null)
        val after = cart.readText(Charsets.ISO_8859_1)
        val beforeLines = text.split("\r\n")
        val afterLines = after.split("\r\n")
        val changed = beforeLines.indices.count { it >= afterLines.size || beforeLines[it] != afterLines[it] }
        check("exactly two lines changed ($changed), nothing added or removed", changed == 2 && beforeLines.size == afterLines.size)
        check("the new steerpoint reads back", com.bmscompanion.app.data.wdp.DtcIni.read(after, "STPT", "target_1") == edits.first { it.key == "target_1" }.value)
        check("no temporary file is left behind", dir.listFiles()!!.none { it.name.endsWith(".bmsc-new") })
        check("no backup folder, README or copy is made: the folder holds what it held (${names(dir).size} entries)", names(dir) == namesBefore)

        store.save(callsign, edits)
        check("saving the same edits again changes nothing", cart.readText(Charsets.ISO_8859_1) == after)
        check("a name with a path in it is refused", !store.state("..\\$callsign").available)

        // A backup folder an earlier build left on the pilot's disk: never read, never written, never deleted
        appendLine()
        appendLine("a backup folder left by an earlier build:")
        val old = File(dir, "earlier-version").apply { mkdirs() }
        val oldCart = File(old, cart.name).apply { writeBytes(original) }
        val oldBack = File(old, "BMS Companion Backup").apply { mkdirs() }
        // the copy differs from the live file, so a copy that had been put back would show
        val oldBytes = original + "\r\n; as an earlier build kept it\r\n".toByteArray(Charsets.ISO_8859_1)
        File(oldBack, cart.name).writeBytes(oldBytes)
        File(oldBack, "README.txt").writeText("BMS Companion — data cartridge backup\r\n")
        File(oldBack, "bms-companion-cartridge.txt").writeText("2026-01-01 00:00:00  the Planner switched saving on for ${cart.name}\r\n")
        val oldSnap = names(oldBack).associateWith { File(oldBack, it).readBytes().contentHashCode() }
        val os = CartridgeStore(BmsInstall(), configOverride = old)
        val o = os.save(callsign, edits)
        check("the save goes through there too: ${o.message ?: o.error}", o.error == null && !oldCart.readBytes().contentEquals(original))
        check("… onto the live file, not the old copy", o.text == oldCart.readText(Charsets.ISO_8859_1) && o.text?.contains("as an earlier build kept it") != true)
        check("the old folder is exactly as it was: copy, README and record untouched",
            names(oldBack).associateWith { File(oldBack, it).readBytes().contentHashCode() } == oldSnap)
        appendLine(if (failures == 0) "PASS" else "FAIL — $failures check(s)")
    }

    private fun cfgTest(root: File): String = buildString {
        if (File(root, "Falcon BMS.exe").isFile || File(root, "Bin/x64/Falcon BMS.exe").isFile) {
            appendLine("REFUSED: $root holds Falcon BMS.exe, so it is a real install. Point this at a copy.")
            return@buildString
        }
        val install = BmsInstall().apply { refresh(root.path) }
        if (install.baseDir == null) { appendLine("REFUSED: $root is not a folder."); return@buildString }
        val cfg = BmsConfig(install)
        val dir = File(root, "User\\Config")
        val live = File(dir, "Falcon BMS User.cfg")
        val back = File(dir, "BackUp")
        fun check(what: String, ok: Boolean) = appendLine("${if (ok) "ok  " else "FAIL"} $what")

        // A folder this has already run against has a BackUp, three profiles and a profile selected, and the checks
        // below are written for a fresh copy. Copy the folder again rather than reading a pass that means nothing.
        if (back.exists()) {
            appendLine("REFUSED: $back already exists, so this has run here before. Copy User/Config again first.")
            return@buildString
        }
        appendLine("folder: $root")
        appendLine("before: live=${live.isFile} backup=${File(back, "Falcon BMS User.cfg").isFile}")
        // the check's own read, which a locked-down folder refuses as well; the product's reads are guarded already
        val beforeText = live.takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }.orEmpty()
        val launcher = "LAUNCHER OVERRIDES BEGIN HERE" in beforeText
        appendLine("the file has a launcher block: $launcher")

        // --- the copy, and the copy taken twice
        var s = cfg.backUp()
        // A folder Windows will not let us write is the ordinary case for a Program Files install, and the only
        // right answer is a sentence saying so. Nothing may throw, and the page may not simply go quiet.
        if (!s.userBackedUp) {
            appendLine("no backup was taken; the reason given: ${s.error ?: "(none)"}")
            check("it said why instead of throwing", s.error != null)
            appendLine(if ("FAIL" in this) "SOMETHING FAILED" else "all checks passed (read-only folder)")
            return@buildString
        }
        check("backup taken", s.userBackedUp)
        check("three profiles laid down", s.user.profiles == listOf(true, true, true))
        val origText = File(back, "Falcon BMS User.cfg").readText()
        check("profile 1 is what was there", File(back, "User Profile 1.cfg").readText() == beforeText)
        check("profiles 2 and 3 hold no settings", cfg.read(BmsConfig.Kind.USER, 2).lines.isEmpty())
        // pressing the button again must not replace the original with whatever the file says now
        live.writeText(beforeText + "\r\nset g_bScratch 1\r\n")
        s = cfg.backUp()
        check("a second backup leaves the original alone", File(back, "Falcon BMS User.cfg").readText() == origText)
        live.writeText(beforeText)

        // --- one setting, in a profile that is not the one in use
        val key = "g_bRealisticAvionics"
        var f = cfg.set(BmsConfig.Kind.USER, 2, key, "0")
        check("the line is in profile 2", f.lines.any { it.key == key && it.value == "0" })
        check("profile 2 holds only that line", f.lines.size == 1)
        check("the live file is untouched", live.readText() == beforeText)

        // --- clearing it takes the line out again
        f = cfg.set(BmsConfig.Kind.USER, 2, key, null)
        check("clearing removes the line", f.lines.none { it.key == key })

        // --- switching to profile 2, and what that does to the live file
        cfg.set(BmsConfig.Kind.USER, 2, key, "0")
        s = cfg.select(BmsConfig.Kind.USER, 2)
        check("profile 2 is now the one in use", s.user.selected == 2)
        val afterSelect = live.readText()
        check("the live file has the setting", Regex("(?m)^set $key 0\\s*$").containsMatchIn(afterSelect))
        check(
            "the launcher's block survived",
            !launcher || ("LAUNCHER OVERRIDES BEGIN HERE" in afterSelect &&
                afterSelect.substringAfter("LAUNCHER OVERRIDES BEGIN HERE") == beforeText.substringAfter("LAUNCHER OVERRIDES BEGIN HERE")),
        )
        check("a new line went above the launcher's block", !launcher || afterSelect.indexOf("set $key 0") < afterSelect.indexOf("LAUNCHER OVERRIDES BEGIN HERE"))

        // --- editing the profile in use reaches the live file straight away
        cfg.set(BmsConfig.Kind.USER, 2, key, "1")
        check("the live file followed the edit", Regex("(?m)^set $key 1\\s*$").containsMatchIn(live.readText()))

        // --- copying one profile onto another
        f = cfg.copy(BmsConfig.Kind.USER, 1, 3)
        check("profile 3 now holds profile 1's settings", f.lines.map { it.key } == cfg.read(BmsConfig.Kind.USER, 1).lines.map { it.key })

        // --- and the way back
        f = cfg.restore(BmsConfig.Kind.USER, 2)
        check("restoring profile 2 gives back the original", File(back, "User Profile 2.cfg").readText() == origText)
        check("and the live file with it", live.readText().trimEnd() == origText.trimEnd())
        check("nothing of ours is left in it", f.lines.none { it.key == key } || key in origText)

        appendLine()
        appendLine("state: ${Bridge.json.encodeToString(com.bmscompanion.app.data.mission.CfgState.serializer(), cfg.state())}")
        appendLine("profile 1 holds ${cfg.read(BmsConfig.Kind.USER, 1).lines.size} lines")
        appendLine(if ("FAIL" in this) "SOMETHING FAILED" else "all checks passed")
    }
}
