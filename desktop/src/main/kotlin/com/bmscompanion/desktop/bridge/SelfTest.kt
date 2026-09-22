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
 * - `--selftest out.txt`: shared memory struct sizes and parser output on the demo data;
 * - `--dumpstrings out.txt`: raw StringData ids and values from a running BMS (to verify the id table);
 * - `--eztest <EZBoards folder> out.txt`: runs EZBoards exactly like the app button does (use a copy of the folder).
 * - `--api <path>[,<path>…] out.txt`: API responses (e.g. /api/info,/api/mission) from Falcon BMS on this PC, one per line.
 * - `--maprender <theater> <folder> [xFt,yFt]`: map styles and landmarks rendered to PNGs (see MapRender).
 * - `--updatetest out.txt [download]`: what the About page sees on GitHub — the releases newer than this build, the
 *   file this platform would fetch and the checksum it would check it against. With `download`, it fetches that
 *   file and verifies it, without installing anything.
 */
object SelfTest {
    fun run(args: Array<String>): Boolean {
        val json = Bridge.json
        when {
            args.size == 2 && args[0] == "--selftest" -> {
                val demo = DemoSource()
                val (fd, fd2) = SharedMemoryReader.structSizes
                File(args[1]).writeText(buildString {
                    appendLine("sizeof(FlightData)=$fd sizeof(FlightData2)=$fd2")
                    appendLine(json.encodeToString(Briefing.serializer(), BriefingParser.parse(demo.briefingText)))
                    appendLine(json.encodeToString(Live.serializer(), demo.live()))
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
            args.size == 3 && args[0] == "--eztest" -> File(args[2]).writeText(json.encodeToString(EzRun.serializer(), EzBoardsRunner().generate(args[1], auto = false)))
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
            args.size == 2 && args[0] == "--atotest" -> {
                val install = BmsInstall().apply { refresh(Bridge.settings.value.BmsDirOverride) }
                val source = PlannedRoutes.sourceFor(install, install.theater)
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
                // several paths separated by commas: one response per line
                File(args[2]).writeText(args[1].split(',').joinToString("\n") { Bridge.handle(ApiRequest("GET", it)).body.toString(Charsets.UTF_8) })
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
            else -> return false
        }
        return true
    }
}
