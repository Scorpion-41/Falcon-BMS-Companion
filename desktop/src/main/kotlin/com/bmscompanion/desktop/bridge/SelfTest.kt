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
 * - `--updatetest out.txt [download]`: what the About page sees on GitHub — the releases newer than this build, the
 *   file this platform would fetch and the checksum it would check it against. With `download`, it fetches that
 *   file and verifies it, without installing anything.
 */
object SelfTest {
    fun run(args: Array<String>): Boolean {
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
            args.size == 3 && args[0] == "--eztest" -> File(args[2]).writeText(json.encodeToString(EzRun.serializer(), EzBoardsRunner().generate(args[1], auto = false)))
            // --cfgtest <a copy of the BMS folder> out.txt : the Config page's whole life against a folder that is
            // not the real install. Every step is checked by reading the files back, because this is the one part of
            // the program that writes into a BMS folder and the only proof that matters is what is on disk.
            args.size == 3 && args[0] == "--cfgtest" -> File(args[2]).writeText(cfgTest(File(args[1])))
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
            else -> return false
        }
        return true
    }

    /**
     * The Config page's whole life, against a copy of a BMS folder.
     *
     * Refuses to run on anything that looks like a live install — a folder holding `Falcon BMS.exe` — because the
     * BMS folder is read only to this program and a check that writes into it is exactly the accident this rule
     * exists to prevent. Copy `User/Config` into an empty folder and point this at that.
     */
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
