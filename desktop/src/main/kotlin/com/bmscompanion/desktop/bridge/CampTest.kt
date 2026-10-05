package com.bmscompanion.desktop.bridge

import java.io.File

/**
 * `--camtest out.txt <root> [more…]`: every campaign, TE and training save of every theater walked whole with
 * [CampaignArchive] (R3-CAM §4.7). Read-only: it never writes under [root] (a copy of a BMS folder — the round-3
 * fixture — or any install).
 *
 * The walk part (items 1-8, batch C1):
 * 1. every theater definition resolves, its campaign folder exists, and its class tables are found (and whether from
 *    its own `objectdir` or `Data/TerrData/Objects`);
 * 2. each file's directory lies inside it (bytes after it are reported) and its `.ver` is a number;
 * 3. the campaign header parses, with at most 4 bytes left unread;
 * 4. the unit part walks **exactly**: it ends on its last byte, with the header's record count, every record's type
 *    copy equal to its type;
 * 5. an objective part, when there is one, walks exactly;
 * 6. start ⇔ objectives ⇔ no flights, with the exceptions listed (on this install, Korea TvT's `TvT_Start.cam`);
 * 7. the links hold: every flight's package exists and lists it, every package element is a flight, every flight's
 *    squadron exists, and every callsign, mission and aircraft name resolves (Strings lines split by a space too);
 * 8. every played file names a start file that exists beside it, and its waypoint targets resolve to a unit of the
 *    save or an objective of that start file (the share is reported).
 * Then the totals against R3-CAM's inventory of the install the fixture was copied from (19 theaters, 517 files, 169
 * starts, 347 with an ATO, 5,562 flights, 4,336 packages, 15,658 of 15,734 targets); `--any-install` after the root
 * skips that comparison for another install. And the time a warm read and parse takes (target: under 5 ms a file).
 *
 * Items 9-12 (batch C2, [items9to12]): the take-off points against the app's airports, the listing and the briefed
 * flight's briefing made from the save against the printed one (`User/Briefings/briefing.txt` under [root]), the
 * mission file beside each save, the timing, and the refusals. `--theater=<name>` names the theater BMS is set to
 * (default: the registry's `curTheater`).
 *
 * `--camdump <file> out.txt [BMS folder] [callsign] [--card]`: one save, package by package — each flight with its
 * aircraft, mission, squadron, times, loadout, TACAN, and route with every waypoint's target; the team part (ground and
 * air actions, mottos); and for the named flight (or the player's) the briefing texts made of the save
 * ([CampaignBriefing]: situation, station or target area, Pkg-Mission, ROE, emergency) and WDP's intelligence. The BMS
 * folder is found by walking up from the file when it is not given; a callsign (or part of one) keeps only the
 * packages that hold it. `--card` then fills the Planner's Briefing page for that flight in-process, as Open mission…
 * does with no printed briefing for it, and lists its boxes.
 *
 * A report line starting with "FAIL" makes the check exit 1 ([SelfTest]).
 */
object CampTest {
    /** R3-CAM's inventory of this install, 2026-09-28 (the round-3 fixture is a copy of it). */
    private const val EXPECT_THEATERS = 19
    private const val EXPECT_FILES = 517
    private const val EXPECT_STARTS = 169
    private const val EXPECT_ATO = 347
    private const val EXPECT_FLIGHTS = 5562
    private const val EXPECT_PACKAGES = 4336
    private const val EXPECT_TARGETS = 15734
    private const val EXPECT_RESOLVED = 15658
    private val EXPECT_EXCEPTIONS = setOf("Korea TvT/TvT_Start.cam")

    /** The one take-off waypoint of this install whose target is neither a unit nor an objective (R3-CAM §0), in every Korea-based theater's copy. */
    private const val EXPECT_NO_TAKEOFF_TARGET = "TR_BMS_20_Harpoon.trn Chalice1"

    /** The aircraft that take off from the objectives that are not fields (helicopter and army bases), by name. */
    private val HELICOPTERS = listOf("AH-", "UH-", "CH-", "MH-", "HH-", "OH-", "SH-", "MD-500", "Mi-", "Ka-", "Lynx", "Puma", "Gazelle", "Sea King", "NH90", "EC", "AS")

    /** What one theater's files added up to. */
    private class Tally {
        var files = 0; var exact = 0; var starts = 0; var ato = 0; var flights = 0; var packages = 0
        var objExact = 0; var objFiles = 0; var targets = 0; var resolved = 0; var unitTargets = 0
        var pltExact = 0; var pltFiles = 0; var spaceSplit = 0; var played = 0; var playedWithStart = 0
        var linkIssues = 0; var readErrors = 0; var cmpBad = 0
        var ms = 0.0
        fun add(o: Tally) {
            files += o.files; exact += o.exact; starts += o.starts; ato += o.ato; flights += o.flights; packages += o.packages
            objExact += o.objExact; objFiles += o.objFiles; targets += o.targets; resolved += o.resolved; unitTargets += o.unitTargets
            pltExact += o.pltExact; pltFiles += o.pltFiles; spaceSplit += o.spaceSplit; played += o.played; playedWithStart += o.playedWithStart
            linkIssues += o.linkIssues; readErrors += o.readErrors; cmpBad += o.cmpBad; ms += o.ms
        }
    }

    fun run(root: File, more: List<String>): String = buildString {
        var fails = 0
        fun check(name: String, ok: Boolean, detail: String = "") {
            appendLine((if (ok) "PASS " else "FAIL ") + name + if (detail.isNotEmpty()) "  | $detail" else "")
            if (!ok) fails++
        }
        val inventory = "--any-install" !in more

        val set = Theaters.at(root)
        appendLine("--camtest: every save of every theater walked whole (CampaignArchive), read-only")
        appendLine("root: ${root.path}")
        appendLine("theaters: ${set.all.size}; error: ${set.error ?: "none"}")
        appendLine()

        fun rel(f: File?): String = f?.let { d ->
            val data = set.data.canonicalPath
            val c = d.canonicalPath
            if (c.startsWith(data, ignoreCase = true)) c.substring(data.length).trimStart('\\', '/') else d.path
        } ?: "-"

        val total = Tally()
        val exceptions = ArrayList<String>()
        val walkFails = ArrayList<String>()
        val linkLines = ArrayList<String>()
        val noStart = ArrayList<String>()
        val tails = ArrayList<String>()
        val cmpUnread = HashMap<Int, Int>()
        val unresolvedSamples = ArrayList<String>()
        val allFiles = ArrayList<Pair<File, CampaignArchive.Names>>()
        val rows = ArrayList<String>()
        val oddHomes = ArrayList<String>()
        val cheapDiffers = ArrayList<String>()

        for (t in set.all) {
            val tally = Tally()
            val campDir = set.campaignDir(t)
            appendLine("== ${t.name}  (campaign folder ${rel(campDir)})")
            // 1. the definition, the folder, the class tables
            check("  [1] ${t.name}: campaign folder exists", campDir != null, t.campaignDir ?: "no campaigndir line")
            val names = CampaignArchive.names(set, t)
            val own = set.objectDir(t)?.let { Theaters.canonical(it) }
            val tables = names.tables.entries.joinToString(" ") { (k, f) ->
                k.removePrefix("Falcon4_").removeSuffix(".xml") + "=" + when {
                    f == null -> "none"
                    own != null && Theaters.canonical(f.parentFile) == own -> "own"
                    else -> "base"
                }
            }
            check("  [1] ${t.name}: class tables found ($tables), strings ${names.stringsFile?.let { rel(it) } ?: "none"}",
                names.error == null && names.tables.values.all { it != null } && names.strings.isNotEmpty(), names.error ?: "")
            // the lines of Strings.txt a tab-only split loses ("341 RELOCATE")
            val tabOnly = tabOnlyIds(names.stringsFile)

            for (f in set.saves(t).sortedBy { it.name.lowercase() }) {
                tally.files++
                allFiles += f to names
                val save = CampaignArchive.read(f, names)
                tally.ms += save.readMs
                val tag = "${t.name}/${f.name}"
                val dir = save.directory
                // 2. the directory and the version
                if (dir == null || dir.error != null || save.version == null) {
                    tally.readErrors++
                    walkFails += "$tag: ${save.error ?: "no version"}"
                    rows += "  %-38s  %s".format(f.name, "UNREADABLE: ${save.error}")
                    continue
                }
                if (dir.tail > 0) tails += "$tag ${dir.tail}"
                // the cheap read a listing uses (the header and what follows the directory) must agree with the whole file
                val cheap = CampaignArchive.directoryOf(f)
                if (cheap.error != null || cheap.parts != dir.parts || cheap.tail != dir.tail) cheapDiffers += "$tag: ${cheap.error ?: "${cheap.parts.size} parts, tail ${cheap.tail}"}"
                // 3. the header
                val h = save.header
                if (h == null) { tally.cmpBad++; walkFails += "$tag: header: ${save.error}" }
                else {
                    cmpUnread[h.unread] = (cmpUnread[h.unread] ?: 0) + 1
                    if (h.unread !in 0..4) { tally.cmpBad++; walkFails += "$tag: ${h.unread} bytes of the campaign header unread" }
                }
                // 4. the units
                val uni = save.uni
                if (uni?.exact == true) tally.exact++
                else walkFails += "$tag: units ${uni?.units?.size}/${uni?.records}, end ${uni?.end}/${uni?.unpacked}: ${uni?.stop ?: save.error}"
                // 5. the objectives
                var objText = ""
                if (save.hasObj) {
                    tally.objFiles++
                    val obj = CampaignArchive.objectives(f)
                    if (obj?.exact == true) tally.objExact++
                    else walkFails += "$tag: objectives ${obj?.objectives?.size}/${obj?.records}, end ${obj?.end}/${obj?.unpacked}: ${obj?.stop ?: "not read"}"
                    objText = " obj ${obj?.objectives?.size}/${obj?.records}${if (obj?.exact == true) "" else " INEXACT"}"
                }
                // the pilot list, read without WDP's extra byte
                save.plt?.let { p -> tally.pltFiles++; if (p.exact) tally.pltExact++ else walkFails += "$tag: pilot list ends at ${p.end} of ${p.size}" }
                // 6. start <=> objectives <=> no flights
                val flights = save.flights
                if (save.hasObj) tally.starts++
                if (flights.isNotEmpty()) tally.ato++
                tally.flights += flights.size
                tally.packages += save.packages.size
                if (save.hasObj != flights.isEmpty()) exceptions += "$tag (objectives: ${save.hasObj}, flights: ${flights.size})"
                // 7. the links
                val issues = links(save, names, tabOnly, tally, oddHomes, tag)
                if (issues.isNotEmpty()) { tally.linkIssues += issues.size; linkLines += "$tag: " + issues.take(5).joinToString("; ") + if (issues.size > 5) " (+${issues.size - 5})" else "" }
                // 8. played files: the start file, and what the targets are
                var tgtText = ""
                if (!save.hasObj) {
                    tally.played++
                    val start = CampaignArchive.startFile(save)
                    if (start == null) noStart += "$tag -> ${h?.scenario}"
                    else {
                        tally.playedWithStart++
                        val objs = CampaignArchive.objectives(start)
                        var n = 0; var ok = 0; var units = 0
                        for (fl in flights) for (w in fl.waypoints) {
                            val id = w.target ?: continue
                            if (save.unit(id) != null) { units++; continue }
                            n++
                            if (objs?.get(id) != null) ok++
                            else if (unresolvedSamples.size < 12) unresolvedSamples += "$tag ${names.callsign(fl)} target $id (action ${w.action})"
                        }
                        tally.targets += n; tally.resolved += ok; tally.unitTargets += units
                        if (n + units > 0) tgtText = " tgt $ok/$n+${units}u"
                    }
                }
                val player = save.playerFlights.mapNotNull { names.callsign(it) }.joinToString(",")
                rows += "  %-38s v%-3s %7d B  %s units %d/%d%s%s  fl %d pk %d%s%s%s  %.1f ms".format(
                    f.name, save.version, save.size, if (h != null) "cmp+${h.unread}" else "cmp?",
                    uni?.units?.size ?: 0, uni?.records ?: 0, if (uni?.exact == true) " exact" else " INEXACT",
                    objText, flights.size, save.packages.size, if (save.start) " START" else "",
                    tgtText, if (player.isNotEmpty()) " player $player" else "", save.readMs,
                )
            }
            rows.forEach { appendLine(it) }
            rows.clear()
            appendLine("  -> ${tally.files} files, ${tally.exact} exact, ${tally.starts} starts, ${tally.ato} with an ATO, ${tally.flights} flights, ${tally.packages} packages, targets ${tally.resolved}/${tally.targets} (+${tally.unitTargets} units), %.0f ms".format(tally.ms))
            appendLine()
            total.add(tally)
        }

        appendLine("== totals")
        appendLine("files ${total.files}; exact unit walks ${total.exact}; starts (objectives) ${total.starts}, objective walks exact ${total.objExact}/${total.objFiles}; with an ATO ${total.ato}")
        appendLine("flights ${total.flights}; packages ${total.packages}; pilot lists exact ${total.pltExact}/${total.pltFiles}")
        appendLine("played files ${total.played}, with their start file ${total.playedWithStart}; waypoint targets on objectives ${total.resolved}/${total.targets} resolved (%.1f %%), ${total.unitTargets} on units".format(if (total.targets == 0) 0.0 else 100.0 * total.resolved / total.targets))
        appendLine("campaign header bytes left unread: " + cmpUnread.toSortedMap().entries.joinToString { "${it.key}: ${it.value} files" })
        appendLine("bytes after the directory: " + (if (tails.isEmpty()) "none" else tails.joinToString("; ")))
        appendLine("flights whose squadron is another kind of unit: " + (if (oddHomes.isEmpty()) "none" else oddHomes.joinToString("; ")))
        appendLine("mission names read only because Strings.txt is split on a space as well as a tab: ${total.spaceSplit} flights")
        if (unresolvedSamples.isNotEmpty()) appendLine("unresolved targets (first ${unresolvedSamples.size}): " + unresolvedSamples.joinToString("; "))
        appendLine("start <=> objectives <=> no flights, exceptions: " + (if (exceptions.isEmpty()) "none" else exceptions.joinToString("; ")))
        appendLine()

        check("[1] every theater definition read", set.error == null && set.all.isNotEmpty(), set.error ?: "${set.all.size}")
        check("[2] every file's directory lies inside it and its version is a number", total.readErrors == 0, walkFails.filter { "UNREADABLE" in it || "version" in it }.take(5).joinToString("; "))
        check("[2] the directory read alone (header + tail, as a listing reads it) agrees with the whole file", cheapDiffers.isEmpty(), cheapDiffers.take(5).joinToString("; "))
        check("[3] every campaign header parses with at most 4 bytes unread", total.cmpBad == 0, walkFails.filter { "header" in it }.take(5).joinToString("; "))
        check("[4] every unit part walks exactly (${total.exact}/${total.files})", total.exact == total.files, walkFails.filter { "units" in it }.take(5).joinToString("; "))
        check("[5] every objective part walks exactly (${total.objExact}/${total.objFiles})", total.objExact == total.objFiles, walkFails.filter { "objectives" in it }.take(5).joinToString("; "))
        check("pilot lists end on their last byte without WDP's extra byte (fix D48) (${total.pltExact}/${total.pltFiles})", total.pltExact == total.pltFiles, walkFails.filter { "pilot" in it }.take(5).joinToString("; "))
        check("[6] start <=> objectives <=> no flights, exceptions ${if (inventory) "= $EXPECT_EXCEPTIONS" else "reported"}",
            !inventory || exceptions.map { it.substringBefore(" (") }.toSet() == EXPECT_EXCEPTIONS, exceptions.joinToString("; "))
        check("[7] links: packages, elements, squadrons (a unit), callsigns, missions, aircraft (${total.linkIssues} issues)", total.linkIssues == 0, linkLines.take(5).joinToString(" || "))
        check("[8] every played file names a start file beside it (${total.playedWithStart}/${total.played})", noStart.isEmpty(), noStart.take(5).joinToString("; "))
        if (inventory) {
            check("inventory: $EXPECT_THEATERS theaters", set.all.size == EXPECT_THEATERS, "${set.all.size}")
            check("inventory: $EXPECT_FILES files", total.files == EXPECT_FILES, "${total.files}")
            check("inventory: $EXPECT_FILES exact walks", total.exact == EXPECT_FILES, "${total.exact}")
            check("inventory: $EXPECT_STARTS starts", total.starts == EXPECT_STARTS, "${total.starts}")
            check("inventory: $EXPECT_ATO files with an ATO", total.ato == EXPECT_ATO, "${total.ato}")
            check("inventory: $EXPECT_FLIGHTS flights", total.flights == EXPECT_FLIGHTS, "${total.flights}")
            check("inventory: $EXPECT_PACKAGES packages", total.packages == EXPECT_PACKAGES, "${total.packages}")
            check("inventory: $EXPECT_RESOLVED of $EXPECT_TARGETS objective targets resolved", total.targets == EXPECT_TARGETS && total.resolved == EXPECT_RESOLVED, "${total.resolved} of ${total.targets}")
        }

        // the time a warm read and parse takes: every file again, now that the tables are loaded and the code is warm
        val times = ArrayList<Pair<Double, File>>()
        for ((f, names) in allFiles) {
            val s = CampaignArchive.read(f, names)
            times += s.readMs to f
        }
        if (times.isNotEmpty()) {
            val mean = times.sumOf { it.first } / times.size
            val max = times.maxByOrNull { it.first }!!
            val biggest = allFiles.map { it.first }.maxByOrNull { it.length() }!!
            val biggestMs = times.first { it.second == biggest }.first
            appendLine()
            appendLine("warm read + parse: %d files in %.0f ms, mean %.2f ms, slowest %.2f ms (%s), largest file %s (%d B) %.2f ms".format(
                times.size, times.sumOf { it.first }, mean, max.first, max.second.name, biggest.name, biggest.length(), biggestMs))
            check("timing: a warm read and parse under 5 ms a file on average", mean < 5.0, "%.2f ms".format(mean))
        }

        // ---- items 9-12 (take-off points, the briefed flight, the mission ini, the listing's timing): batch C2 ----
        val cur = more.firstOrNull { it.startsWith("--theater=") }?.substringAfter('=')
            ?: runCatching { BmsInstall().apply { refresh(null) }.theater }.getOrNull()
        items9to12(root, set, allFiles, inventory, cur) { name, ok, detail -> check(name, ok, detail) }

        appendLine()
        appendLine(if (fails == 0) "ALL PASS (items 1-12)" else "$fails FAIL line(s) (items 1-12)")
    }

// ================================================================ items 9-12 (batch C2)

    /**
     * Items 9-12 of R3-CAM §4.7: what the network routes (`/api/campaign/files`, `/ato`, `/flight`, answered by
     * [CampaignFiles]) give for this folder.
     * 9. every flight's take-off waypoint names an airbase of the app's own airport set for its theater (by `campId`) or
     *    a task force (a carrier), and the waypoint (its cell's middle) lies within 1 nm of that airport;
     * 10. the listing marks the files holding the printed briefing's flight (callsign + package # + flight #), and the
     *    briefing the PC makes of that flight from the save equals the printed one: take-off and TOT, and every
     *    steerpoint's time and altitude — and the rest of the steerpoint table as far as the save says it;
     * 11. a mission file (`<save>.ini`) that is not newer than its save is the route of one of the save's flights, every
     *    steerpoint within 1 ft of the waypoint's cell middle;
     * 12. timing: every theater listed in under a second once warm, the largest ATO in under 50 ms.
     * [cur] is the theater BMS is set to (the registry's `curTheater`, or `--theater=<name>`).
     */
    private fun StringBuilder.items9to12(
        root: File, set: Theaters.TheaterSet, files: List<Pair<File, CampaignArchive.Names>>, inventory: Boolean, cur: String?,
        check: (String, Boolean, String) -> Unit,
    ) {
        appendLine()
        appendLine("== items 9-12 (batch C2): what /api/campaign/files, /ato and /flight answer for this folder (CampaignFiles)")
        appendLine("theater BMS is set to: ${cur ?: "(none: the registry has no curTheater)"}")

        // ---------------------------------------------------------------- 9. take-off points
        // What a flight takes off from, by what the class table calls it: an objective of type 1 (airbase) or 2
        // (airstrip) is a field of the app's airport set; type 3 (a helicopter or army base) is not in that set and only
        // helicopters use it; a task force is a ship; a squadron is a unit based off the map (Kadena's tankers and AWACS,
        // whose take-off point is on the edge of the map, in the air). Found on this install's 5,560 take-offs.
        var takeoffs = 0; var noTakeoff = 0; var near = 0; var worst = 0.0
        val far = ArrayList<String>(); val fieldNotApp = ArrayList<String>(); val noTarget = ArrayList<String>()
        val noApp = LinkedHashSet<String>()
        val kinds = java.util.TreeMap<String, Int>()
        val helo = java.util.TreeMap<String, Int>()
        fun count(m: MutableMap<String, Int>, k: String) { m[k] = (m[k] ?: 0) + 1 }
        for ((f, names) in files) {
            val t = names.theater ?: continue
            val airports = CampaignFiles.airports(t.appId)
            if (airports.isEmpty()) { noApp += t.name; continue }
            val save = CampaignArchive.read(f, names)
            if (save.hasObj) continue
            val objs = CampaignArchive.startFile(save)?.let { CampaignArchive.objectives(it) }
            for (fl in save.flights) {
                val w = fl.waypoints.firstOrNull { it.action == 1 } ?: run { noTakeoff++; null } ?: continue
                takeoffs++
                val p = CampaignArchive.place(save, objs, w.target)
                val tag = "${t.name}/${f.name} ${names.callsign(fl) ?: fl.id}"
                val ct = p?.let { names.ct(it.type) }
                when {
                    p == null -> { count(kinds, "no target"); noTarget += "$tag target ${w.target}" }
                    p.kind == "unit" -> count(kinds, "unit: " + (save.unit(p.id)?.kind?.name?.lowercase() ?: "?"))
                    ct?.type == 1 || ct?.type == 2 -> {
                        count(kinds, "objective type ${ct.type} (${if (ct.type == 1) "airbase" else "airstrip"})")
                        val a = p.campId?.let { airports[it] }
                        if (a == null) { fieldNotApp += "$tag: ${p.name} (campId ${p.campId})"; continue }
                        val nm = kotlin.math.hypot(a.x - w.north, a.y - w.east) / 6076.12
                        worst = maxOf(worst, nm)
                        if (nm <= 1.0) near++ else far += "$tag: ${a.name} %.2f nm".format(nm)
                    }
                    else -> {
                        count(kinds, "objective type ${ct?.type ?: "?"} (${if (p.campId != null && p.campId in airports) "in" else "not in"} the app's airports)")
                        count(helo, names.aircraft(fl.type) ?: "?")
                    }
                }
            }
        }
        appendLine("[9] take-off waypoints: $takeoffs (flights with none: $noTakeoff); by what they take off from: $kinds")
        appendLine("    airbases and airstrips: $near within 1 nm of the app's field (worst %.2f nm), ${far.size} further, ${fieldNotApp.size} not in the app's airports".format(worst))
        appendLine("    the aircraft taking off from other objectives (helicopter and army bases): $helo")
        if (noApp.isNotEmpty()) appendLine("    theaters with no airport set in the app (skipped): $noApp")
        if (far.isNotEmpty()) appendLine("    more than 1 nm: " + far.take(10).joinToString("; ") + if (far.size > 10) " (+${far.size - 10})" else "")
        if (fieldNotApp.isNotEmpty()) appendLine("    not an app airport: " + fieldNotApp.take(10).joinToString("; "))
        if (noTarget.isNotEmpty()) appendLine("    no target: " + noTarget.take(10).joinToString("; "))
        val helicoptersOnly = helo.keys.all { a -> HELICOPTERS.any { a.startsWith(it, ignoreCase = true) } }
        check("[9] every take-off from an airbase or airstrip is a field of the app's airport set (campId), within 1 nm ($near)",
            near > 0 && far.isEmpty() && fieldNotApp.isEmpty() && noApp.isEmpty(), "further ${far.size}, not in the app's set ${fieldNotApp.size}, theaters without airports ${noApp.size}")
        check("[9] only helicopters take off from the other objectives (${helo.values.sum()}: ${helo.keys})", helicoptersOnly, helo.keys.filter { a -> HELICOPTERS.none { a.startsWith(it, true) } }.joinToString())
        check("[9] a take-off with no target is only the known one (${if (inventory) EXPECT_NO_TAKEOFF_TARGET else "reported"})",
            !inventory || noTarget.map { it.substringAfter('/').substringBefore(" target") }.toSet() == setOf(EXPECT_NO_TAKEOFF_TARGET), noTarget.take(3).joinToString("; "))

        // ---------------------------------------------------------------- 10. the briefed flight
        var briefedIni: Pair<String, Boolean>? = null
        val bf = Theaters.resolveFile(root, "User\\Briefings\\briefing.txt")
        val printed = bf?.let { f -> runCatching { BriefingParser.parse(f.readBytes().toString(Charsets.UTF_8).removePrefix("\uFEFF")) }.getOrNull() }
        val ctx = CampaignFiles.Context(set, cur, printed, bf?.lastModified() ?: 0L)
        val key = ctx.key
        appendLine()
        appendLine("[10] printed briefing: ${bf?.path ?: "none"}; its flight: ${key?.let { "${it.callsign}, package ${it.packageId}, flight ${it.flightId}" } ?: "none"}")
        val t0 = System.nanoTime()
        val listing = CampaignFiles.list(ctx, all = false)
        val coldMs = (System.nanoTime() - t0) / 1e6
        appendLine("listing (all=0): ${listing.theaters.size} theaters, ${listing.theaters.sumOf { it.files.size }} files, current ${listing.current}, error ${listing.error ?: "none"}; cold %.0f ms".format(coldMs))
        listing.theaters.forEachIndexed { i, th ->
            val top = th.files.firstOrNull()
            appendLine("  %2d %-22s %-8s %-48s %3d files; top: %s".format(
                i + 1, th.name, th.appTheater ?: "-", th.folder, th.files.size,
                top?.let { "${it.name} (${it.kind}${if (it.stock) ", stock" else ""}${if (it.briefed) ", BRIEFED" else ""}, flights ${it.flights}, player ${it.player ?: "-"}, clock ${it.clock?.let(CampaignFiles::clockShort) ?: "-"}, \"${it.title ?: ""}\")" } ?: "-",
            ) + (th.error?.let { "  ERROR $it" } ?: ""))
        }
        val briefedFiles = listing.theaters.flatMap { th -> th.files.filter { it.briefed }.map { th to it } }
        appendLine("files marked as holding the briefed flight: " + briefedFiles.joinToString { (th, f) -> "${th.name}/${f.name}" }.ifEmpty { "none" })
        val curName = set.current(cur)?.name
        check("[10] the listing names every theater (${listing.theaters.size} of ${set.all.size}) and puts the one BMS is set to first (${listing.theaters.firstOrNull()?.name})",
            listing.theaters.size == set.all.size && (curName == null || listing.theaters.firstOrNull()?.name == curName), "current $curName")
        check("[10] no start or template in the listing without all=1",
            listing.theaters.all { th -> th.files.none { it.start } }, listing.theaters.flatMap { th -> th.files.filter { it.start }.map { "${th.name}/${it.name}" } }.take(5).joinToString())
        check("[10] files within a theater newest first (sortTime = the later of created and modified, or modified where a bulk copy set the created time)",
            listing.theaters.all { th -> th.files.zipWithNext().all { (a, b) -> a.sortTime >= b.sortTime } && th.files.all { it.sortTime == maxOf(it.created, it.modified) || it.sortTime == it.modified } }, "")
        val bulkRows = listing.theaters.sumOf { th -> th.files.count { it.created > it.modified && it.sortTime == it.modified } }
        appendLine("files whose created time is a bulk copy's (ordered by modified): $bulkRows")
        // the rule itself: five files created within two minutes are a bulk copy, one file on its own is not
        val now = System.currentTimeMillis()
        val bulk = CampaignFiles.bulkCopied(listOf(now, now + 1_000, now + 30_000, now + 60_000, now + 100_000, now - 86_400_000L * 3))
        check("[10] bulk-copy rule: five files created within two minutes are a bulk copy, a file copied in on its own days apart is not",
            bulk.size == 5 && (now - 86_400_000L * 3) !in bulk && CampaignFiles.sortTime(now - 86_400_000L * 3, now - 86_400_000L * 30, bulk) == now - 86_400_000L * 3 &&
                CampaignFiles.sortTime(now, now - 86_400_000L * 30, bulk) == now - 86_400_000L * 30, "$bulk")
        if (inventory) {
            val top = listing.theaters.firstOrNull()
            check("[10] inventory: Korea KTO first, Auto Save.cam on top and marked briefed, 19 groups",
                top?.name == "Korea KTO" && top.files.firstOrNull()?.name == "Auto Save.cam" && top.files.firstOrNull()?.briefed == true && listing.theaters.size == 19,
                "${top?.name}/${top?.files?.firstOrNull()?.name} briefed ${top?.files?.firstOrNull()?.briefed}")
        }
        val allListing = CampaignFiles.list(ctx, all = true)
        val starts = allListing.theaters.flatMap { th -> th.files.filter { it.start }.map { th to it } }
        appendLine("listing (all=1): ${allListing.theaters.sumOf { it.files.size }} files, of which starts ${starts.size}; stock ${allListing.theaters.sumOf { th -> th.files.count { it.stock } }}")
        appendLine("  e.g. " + starts.take(3).joinToString(" | ") { (th, f) -> "${th.name}/${f.name}: ${f.stockWhy}" })
        allListing.theaters.flatMap { th -> th.files.filter { it.stock && !it.start }.map { th to it } }.firstOrNull()?.let { (th, f) -> appendLine("  a stock mission: ${th.name}/${f.name}: ${f.stockWhy}") }
        check("[10] with all=1 every start is listed, marked stock with its reason (${starts.size})",
            starts.isNotEmpty() && starts.all { (_, f) -> f.stock && !f.stockWhy.isNullOrBlank() } && (!inventory || starts.size == EXPECT_STARTS + 1),
            "${starts.size} starts (expected ${EXPECT_STARTS + 1} with TvT_Start)")

        val briefedTop = briefedFiles.firstOrNull()
        if (printed == null || key == null) check("[10] a printed briefing to compare with", false, "none at User\\Briefings\\briefing.txt")
        else if (briefedTop == null) check("[10] a file holds the printed briefing's flight", false, "none of the listed files does")
        else {
            val (th, file) = briefedTop
            val ato = (CampaignFiles.ato(ctx, th.name, file.name) as? CampaignFiles.Answer.Ok)?.value
            val row = ato?.packages?.flatMap { it.flights }?.firstOrNull { it.briefed }
            val flight = row?.let { (CampaignFiles.flight(ctx, th.name, file.name, it.id) as? CampaignFiles.Answer.Ok)?.value }
            val made = flight?.briefing
            appendLine()
            appendLine("the briefed flight in ${th.name}/${file.name}: ${row?.let { "${it.callsign} #${it.number} (${it.id}), package ${ato.packages.first { p -> p.flights.contains(it) }.number}, ${it.count}x ${it.aircraft}, ${it.squadron} @ ${it.base}" } ?: "not found"}")
            if (ato != null) {
                appendLine("ATO: ${ato.packages.size} packages, ${ato.packages.sumOf { it.flights.size }} flights; teams " + ato.teams.joinToString { "${it.n} ${it.name}${if (it.allied) " (allied)" else ""}" })
                ato.notes.forEach { appendLine("  ATO note: $it") }
            }
            if (flight != null && made != null) {
                appendLine("route: " + flight.route.joinToString { "${it.n} ${it.desc ?: "--"} a${it.action}" })
                appendLine("home ${flight.home?.let { "${it.name} [${it.kind} ${it.campId}]" }}, landing ${flight.landing?.let { "${it.name} [${it.kind} ${it.campId}]" }}, alternate ${flight.alternate?.let { "${it.name} [${it.kind} ${it.campId}]" }}")
                appendLine("loadouts: " + flight.loadouts.joinToString(" / ") { l -> l.stores.joinToString { "${it.qty}x ${it.name}" } })
                appendLine("fuel ${flight.fuelLb}, laser ${flight.laser}; package flights ${flight.packageFlights.map { it.callsign }}")
                appendLine("support: " + flight.support.joinToString { s -> "${s.callsign} (${s.role}) ${s.track.size} points, station ${s.track.count { it.station }}" })
                appendLine("mission file: ${flight.missionIni}")
                briefedIni = (flight.missionIni?.file ?: "none") to (flight.missionIni?.matches == true)
                flight.notes.forEach { appendLine("  note: $it") }
                val pe = printed.`package`.firstOrNull { it.primary }
                val me = made.`package`.firstOrNull { it.primary }
                appendLine("T/O printed ${pe?.takeoff} / made ${me?.takeoff}; TOT printed ${pe?.target} / made ${me?.target}")
                check("[10] the made briefing's flight, package and flight number are the printed ones",
                    made.overview.flight == printed.overview.flight && made.overview.packageId == printed.overview.packageId && me?.flightId == pe?.flightId && made.origin == "save",
                    "${made.overview.flight}/${made.overview.packageId}/${me?.flightId}")
                check("[10] T/O and TOT equal the printed briefing's", pe != null && me != null && pe.takeoff == me.takeoff && pe.target == me.target, "")
                val pr = printed.steerpoints
                val mr = made.steerpoints
                appendLine("steerpoints printed ${pr.size}, made ${mr.size}:")
                val cols = listOf<Pair<String, (com.bmscompanion.app.data.mission.BriefSteerpoint) -> String?>>(
                    "desc" to { it.desc }, "time" to { it.time }, "dist" to { it.dist }, "heading" to { it.heading }, "alt" to { it.alt },
                    "action" to { it.action }, "formation" to { it.formation }, "comments" to { it.comments },
                )
                val diffs = HashMap<String, Int>()
                for (i in 0 until maxOf(pr.size, mr.size)) {
                    val a = pr.getOrNull(i); val b = mr.getOrNull(i)
                    val bad = cols.filter { (_, get) -> a == null || b == null || get(a) != get(b) }.map { it.first }
                    bad.forEach { diffs[it] = (diffs[it] ?: 0) + 1 }
                    appendLine("  %2d printed %-80s".format(i + 1, a?.let { cols.joinToString(" | ") { (_, g) -> g(it) ?: "--" } } ?: "-"))
                    appendLine("     made    %-80s%s".format(b?.let { cols.joinToString(" | ") { (_, g) -> g(it) ?: "--" } } ?: "-", if (bad.isEmpty()) "" else "   DIFFERS: $bad"))
                }
                check("[10] every steerpoint's time and altitude equal the printed briefing's (${pr.size} rows)",
                    pr.size == mr.size && (diffs["time"] ?: 0) == 0 && (diffs["alt"] ?: 0) == 0, "differ: $diffs")
                check("[10] and the rest of the table (action word, distance, heading, height change, formation, comment) as well",
                    pr.size == mr.size && diffs.isEmpty(), "differ: $diffs (CAS is not made: the save has no speed)")
                val po = printed.ordnance.firstOrNull()?.aircraft.orEmpty()
                val mo = made.ordnance.firstOrNull()?.aircraft.orEmpty()
                fun noGun(s: List<com.bmscompanion.app.data.mission.Store>) = s.filter { !Regex("^\\d+(\\.\\d+)?\\s*mm\\b").containsMatchIn(it.name) }.map { "${it.qty}x ${it.name}" }
                appendLine("ordnance printed " + po.joinToString(" / ") { a -> "${a.name}: " + a.stores.joinToString { "${it.qty}x ${it.name}" } })
                appendLine("ordnance made    " + mo.joinToString(" / ") { a -> "${a.name}: " + a.stores.joinToString { "${it.qty}x ${it.name}" } })
                check("[10] the ordnance per aircraft equals the printed one (the gun without its round count)",
                    po.size == mo.size && po.indices.all { po[it].name == mo[it].name && noGun(po[it].stores) == noGun(mo[it].stores) }, "")
                // the texts BMS's briefing scripts word out of the campaign, made from the save (CampaignBriefing). The
                // printed file's quotation marks are Windows-1252 bytes read as UTF-8, so only printable ASCII is compared.
                fun norm(s: String?) = s?.replace(Regex("[^\\x20-\\x7E\\s]"), "")?.replace(Regex("\\s+"), " ")?.trim()
                fun row(b: com.bmscompanion.app.data.mission.Briefing, key: String) =
                    b.sections.firstOrNull { it.title.contains("Overview", true) }?.rows?.firstOrNull { it.firstOrNull()?.trim()?.trimEnd(':') == key }?.getOrNull(1)?.trim()
                appendLine("situation printed: ${norm(printed.situation)}")
                appendLine("situation made:    ${norm(made.situation)}")
                check("[10] the situation made of the save is the printed one", norm(made.situation) == norm(printed.situation) && !made.situation.isNullOrBlank(), "")
                val areas = listOf("Station Area", "Time on Station", "Target Area", "Time on Target").filter { row(printed, it) != null }
                check("[10] the Pkg-Mission line and the Mission Overview's ${areas.joinToString()} are the printed ones",
                    made.overview.packageMission == printed.overview.packageMission && areas.isNotEmpty() && areas.all { row(made, it) == row(printed, it) } &&
                        made.overview.targetArea == printed.overview.targetArea,
                    "made ${made.overview.packageMission} / " + areas.joinToString { "$it ${row(made, it)}" } + " / target area ${made.overview.targetArea}")
                check("[10] the rules of engagement are the printed ones", made.roe.map(::norm) == printed.roe.map(::norm) && made.roe.isNotEmpty(), "made ${made.roe}")
                fun blocks(b: com.bmscompanion.app.data.mission.Briefing) = b.emergency.map { norm(it.title) + ": " + it.lines.joinToString(" / ") { l -> norm(l) ?: "" } }
                blocks(made).zip(blocks(printed)).forEach { (m, p) -> appendLine("emergency made    $m" + if (m != p) "\nemergency printed $p" else "") }
                check("[10] the emergency procedures (distress call, CSAR, the alternate and where it is) are the printed ones", blocks(made) == blocks(printed), "")
                check("[10] WDP's intelligence is made for the flight (the team part read)", flight.intel != null && flight.intel!!.ground.isNotEmpty(), "${flight.intel}")
                appendLine()
                appendLine("CampFlight JSON (${Bridge.json.encodeToString(com.bmscompanion.app.data.mission.CampFlight.serializer(), flight).length} characters):")
                appendLine(Bridge.json.encodeToString(com.bmscompanion.app.data.mission.CampFlight.serializer(), flight))
                // the JSON a client decodes is the Briefing the Mission views read
                val back = runCatching { Bridge.json.decodeFromString(com.bmscompanion.app.data.mission.CampFlight.serializer(), Bridge.json.encodeToString(com.bmscompanion.app.data.mission.CampFlight.serializer(), flight)) }.getOrNull()
                check("[10] the CampFlight JSON decodes back with its briefing (origin save)", back?.briefing?.origin == "save" && back.briefing?.steerpoints?.size == mr.size, "")
            } else check("[10] the briefed flight's ATO and flight answer", false, "ato ${ato != null}, flight ${flight != null}")
        }

        // ---------------------------------------------------------------- 11. the mission file beside a save
        var inis = 0; var older = 0; var olderMatched = 0; var newerMatched = 0; var olderPlayer = 0
        val explained = java.util.TreeMap<String, Int>()
        var maxDev = 0.0
        val unmatched = ArrayList<String>(); val newer = ArrayList<String>()
        for ((f, names) in files) {
            val ini = f.absoluteFile.parentFile?.listFiles()?.firstOrNull { it.isFile && it.name.equals(f.nameWithoutExtension + ".ini", true) } ?: continue
            val save = CampaignArchive.read(f, names)
            if (save.hasObj || save.flights.isEmpty()) continue
            inis++
            val m = save.flights.firstOrNull { CampaignFiles.iniCheck(f, it, save.modified).matches }
            val tag = "${names.theater?.name}/${ini.name}"
            if (ini.lastModified() > f.lastModified()) {
                if (m != null) newerMatched++
                newer += "$tag (${if (m != null) "the route of ${names.callsign(m)}" else "no flight's route"})"
                continue
            }
            older++
            if (m == null) {
                // why not: how many route slots it sets, the flight it comes nearest to, and how much older than the save it is
                val pts = CampaignFiles.iniTargets(ini)
                val set = (0 until CampaignFiles.MOST_STPTS).count { i -> pts[i]?.let { (x, y) -> x != 0.0 || y != 0.0 } == true }
                fun hits(fl: CampaignArchive.Flight) = fl.waypoints.take(CampaignFiles.MOST_STPTS).withIndex().count { (i, w) ->
                    pts[i]?.let { (x, y) -> kotlin.math.abs(x - w.north) <= CampaignFiles.MATCH_FT && kotlin.math.abs(y - w.east) <= CampaignFiles.MATCH_FT } == true
                }
                val best = save.flights.maxByOrNull { hits(it) }
                val days = (f.lastModified() - ini.lastModified()) / 86_400_000.0
                val n = best?.waypoints?.size?.coerceAtMost(CampaignFiles.MOST_STPTS) ?: 0
                val h = best?.let { hits(it) } ?: 0
                // why BMS's own file is not the route: a route with some steerpoints moved (the pilot's DTC edits, or the
                // mission's author placing them), a file left from an earlier load (older than the save by more than a
                // day), one with no route at all, or a save with no player flight (the file is written for the player's)
                val why = when {
                    set == 0 -> "no route"
                    h * 2 > n && h >= 2 -> "edited: ${n - h} of $n steerpoints moved"
                    days > 1.0 -> "left from an earlier load"
                    save.playerFlights.isEmpty() -> "no player flight in the save"
                    else -> null
                }
                if (why != null) explained[why.substringBefore(':')] = (explained[why.substringBefore(':')] ?: 0) + 1
                unmatched += (if (why == null) "UNEXPLAINED " else "") + "$tag: ${why ?: "?"} (route slots set $set; nearest ${best?.let { names.callsign(it) }} $h of ${best?.waypoints?.size}; " +
                    "player flight ${save.playerFlights.firstOrNull()?.let { names.callsign(it) } ?: "none"}; %.1f days older than the save)".format(days)
                continue
            }
            olderMatched++
            if (m.hasPlayer) olderPlayer++
            val pts = CampaignFiles.iniTargets(ini)
            m.waypoints.take(CampaignFiles.MOST_STPTS).forEachIndexed { i, w ->
                pts[i]?.let { (x, y) -> maxDev = maxOf(maxDev, kotlin.math.abs(x - w.north), kotlin.math.abs(y - w.east)) }
            }
        }
        appendLine()
        appendLine("[11] mission files beside played saves: $inis; not newer than the save $older, of which the route of a flight $olderMatched (the player's $olderPlayer), largest gap %.3f ft; newer than the save ${newer.size} ($newerMatched still a flight's route)".format(maxDev))
        if (newer.isNotEmpty()) appendLine("    newer: " + newer.take(12).joinToString("; ") + if (newer.size > 12) " (+${newer.size - 12})" else "")
        if (unmatched.isNotEmpty()) appendLine("    not a flight's route:\n      " + unmatched.joinToString("\n      "))
        appendLine("    the others, by why: $explained")
        check("[11] a mission file that is a flight's route puts every steerpoint within 1 ft of the waypoint's cell middle ($olderMatched files, %.3f ft)".format(maxDev),
            olderMatched > 0 && maxDev <= 1.0, "")
        check("[11] a mission file not newer than its save that is not a flight's route is explained (edited, left from an earlier load, no route, no player flight)",
            unmatched.none { it.startsWith("UNEXPLAINED") }, unmatched.filter { it.startsWith("UNEXPLAINED") }.take(5).joinToString("; "))
        briefedIni?.let { (file, ok) -> check("[11] the briefed save's mission file ($file) is the briefed flight's route", ok, "") }

        // ---------------------------------------------------------------- 12. timing
        val w0 = System.nanoTime(); val warm = CampaignFiles.list(ctx, all = false); val warmMs = (System.nanoTime() - w0) / 1e6
        val a0 = System.nanoTime(); CampaignFiles.list(ctx, all = true); val allWarmMs = (System.nanoTime() - a0) / 1e6
        val filesJson = Bridge.json.encodeToString(com.bmscompanion.app.data.mission.CampFiles.serializer(), warm).length
        appendLine()
        appendLine("[12] listing every theater: cold %.0f ms (above), warm %.0f ms (all=0), %.0f ms (all=1); JSON %d characters".format(coldMs, warmMs, allWarmMs, filesJson))
        check("[12] every theater listed in under 1 s once warm", warmMs < 1000.0 && allWarmMs < 1000.0, "%.0f / %.0f ms".format(warmMs, allWarmMs))
        val biggest = files.map { it.first }.filter { f -> !CampaignArchive.directoryOf(f).has("obj") }.maxByOrNull { it.length() }
        val bigTheater = biggest?.let { set.owning(it.absoluteFile.parentFile) }
        if (biggest != null && bigTheater != null) {
            val b0 = System.nanoTime(); val first = CampaignFiles.ato(ctx, bigTheater.name, biggest.name); val coldAto = (System.nanoTime() - b0) / 1e6
            val b1 = System.nanoTime(); val again = CampaignFiles.ato(ctx, bigTheater.name, biggest.name); val warmAto = (System.nanoTime() - b1) / 1e6
            val value = (again as? CampaignFiles.Answer.Ok)?.value
            val json = value?.let { Bridge.json.encodeToString(com.bmscompanion.app.data.mission.CampAto.serializer(), it).length } ?: 0
            val fl = value?.packages?.flatMap { it.flights }?.firstOrNull()
            val f0 = System.nanoTime(); val fa = fl?.let { CampaignFiles.flight(ctx, bigTheater.name, biggest.name, it.id) }; val flMs = (System.nanoTime() - f0) / 1e6
            appendLine("largest ATO: ${bigTheater.name}/${biggest.name} (${biggest.length()} B): ${value?.packages?.size} packages, ${value?.packages?.sumOf { it.flights.size }} flights; cold %.1f ms, warm %.1f ms; JSON %d characters; one flight (%s) %.1f ms"
                .format(coldAto, warmAto, json, fl?.callsign, flMs))
            check("[12] the largest ATO in under 50 ms (warm)", first is CampaignFiles.Answer.Ok && warmAto < 50.0, "%.1f ms".format(warmAto))
            check("[12] its flights answer", fa is CampaignFiles.Answer.Ok, (fa as? CampaignFiles.Answer.Refused)?.sentence ?: "")
        } else check("[12] a played file to time", false, "none")

        // ---------------------------------------------------------------- refusals (never a path, never an exception)
        val refusals = listOf(
            "no theater" to CampaignFiles.ato(ctx, null, "Auto Save.cam"),
            "unknown theater" to CampaignFiles.ato(ctx, "Atlantis", "Auto Save.cam"),
            "a path" to CampaignFiles.ato(ctx, set.all.first().name, "..\\..\\User\\Config\\x.ini"),
            "a folder" to CampaignFiles.ato(ctx, set.all.first().name, "C:/Windows/win.ini"),
            "not in the listing" to CampaignFiles.ato(ctx, set.all.first().name, "Nothing Here.cam"),
            "no flight id" to CampaignFiles.flight(ctx, set.all.first().name, set.saves(set.all.first()).firstOrNull()?.name, "x"),
            "no such flight" to CampaignFiles.flight(ctx, set.all.first().name, set.saves(set.all.first()).firstOrNull { !CampaignArchive.directoryOf(it).has("obj") }?.name, "1/99"),
            "no BMS folder" to CampaignFiles.ato(CampaignFiles.Context(null, null, null, 0L), "Korea KTO", "Auto Save.cam"),
        )
        appendLine()
        refusals.forEach { (what, a) -> appendLine("refusal, $what: " + if (a is CampaignFiles.Answer.Refused) "${a.status} \"${a.sentence}\"" else "ANSWERED") }
        check("[refusals] a bad theater, file or flight is a 400 with a sentence, no BMS folder a 409",
            refusals.all { (what, a) -> a is CampaignFiles.Answer.Refused && a.sentence.isNotBlank() && a.status == (if (what == "no BMS folder") 409 else 400) }, "")
    }

    /** Item 7 for one save: what does not link up, in short phrases. Counts the space-split mission names into [tally]. */
    private fun links(
        save: CampaignArchive.Save, names: CampaignArchive.Names, tabOnly: Set<Int>?, tally: Tally, oddHomes: MutableList<String>, tag: String,
    ): List<String> {
        val out = ArrayList<String>()
        for (p in save.packages) for (e in p.elements) {
            val u = save.unit(e)
            if (u == null) out += "package ${p.campId} element $e missing"
            else if (u !is CampaignArchive.Flight) out += "package ${p.campId} element $e is a ${u.kind.name.lowercase()}"
        }
        for (f in save.flights) {
            val cs = names.callsign(f)
            val p = save.unit(f.packageId)
            when {
                p == null -> out += "${cs ?: f.id} has no package ${f.packageId}"
                p !is CampaignArchive.Package -> out += "${cs ?: f.id}'s package ${f.packageId} is a ${p.kind.name.lowercase()}"
                f.id !in p.elements -> out += "package ${p.campId} does not list ${cs ?: f.id}"
            }
            // the flight's unit must exist; it is nearly always a squadron, and what else it can be is reported
            val home = save.homeOf(f)
            if (home == null) out += "${cs ?: f.id} has no squadron ${f.squadronId}"
            else if (home !is CampaignArchive.Squadron) oddHomes += "$tag ${cs ?: f.id}: ${home.kind.name.lowercase()} ${home.id} (${names.unitClass(home.type) ?: home.type})"
            if (cs == null) out += "${f.id} callsign word ${f.callsignId} not in Strings"
            if (names.mission(f.mission) == null) out += "${cs ?: f.id} mission ${f.mission} not in Strings"
            else if (tabOnly != null && (CampaignArchive.MISSION_STRINGS + f.mission) !in tabOnly) tally.spaceSplit++
            if (names.aircraft(f.type) == null) out += "${cs ?: f.id} type ${f.type} has no aircraft name"
        }
        return out
    }

    /** The ids a tab-only split of Strings.txt finds (the old reading), to count what the whitespace split adds. */
    private fun tabOnlyIds(file: File?): Set<Int>? = file?.let {
        runCatching {
            it.readLines(Charsets.ISO_8859_1).mapNotNull { line ->
                val t = line.indexOf('\t')
                if (t > 0) line.substring(0, t).trim().toIntOrNull() else null
            }.toSet()
        }.getOrNull()
    }

    // ================================================================ --camdump

    fun dump(file: File, more: List<String>): String = buildString {
        val rootArg = more.map(::File).firstOrNull { it.isDirectory }
        val filter = more.firstOrNull { !File(it).isDirectory && !it.startsWith("--") }?.lowercase()
        val root = rootArg ?: findRoot(file)
        if (!file.isFile) {
            appendLine("FAIL: ${file.path} is not a file.")
            return@buildString
        }
        val set = root?.let { Theaters.at(it) }
        if (set == null || set.all.isEmpty()) {
            appendLine("FAIL: no Falcon BMS folder (Data\\TerrData\\TheaterDefinition\\theater.lst) above ${file.path}; give it after out.txt.")
            return@buildString
        }
        val owner = set.owning(file.absoluteFile.parentFile)
        val t = owner ?: set.all.first()
        val names = CampaignArchive.names(set, t)
        val save = CampaignArchive.read(file, names)
        val h = save.header
        val start = CampaignArchive.startFile(save)
        val objs = start?.let { CampaignArchive.objectives(it) }

        fun place(id: CampaignArchive.VuId?, building: Int? = null): String {
            if (id == null || id.isNone) return "-"
            val p = CampaignArchive.place(save, objs, id, building) ?: return "$id (not found)"
            val kind = if (p.kind == "unit") (save.unit(p.id)?.kind?.name?.lowercase() ?: "unit") else "objective"
            return "${p.name ?: names.unitClass(p.type) ?: "?"} [$kind ${p.id}, campId ${p.campId}${p.building?.let { ", building $it" } ?: ""}] N %.0f E %.0f".format(p.north, p.east)
        }
        fun flightName(id: CampaignArchive.VuId?): String =
            save.flight(id)?.let { f -> "${names.callsign(f) ?: "?"} (${names.mission(f.mission) ?: f.mission}, ${f.id})" } ?: if (id == null || id.isNone) "-" else "$id"

        appendLine("# ${file.name}  v${save.version}  ${save.size} B  modified ${java.time.Instant.ofEpochMilli(save.modified)}")
        appendLine("# theater: ${t.name}${if (owner == null) " (the file is not in a campaign folder; the base theater's names are used)" else ""}; class tables " +
            names.tables.entries.joinToString(" ") { (k, f) -> "${k.removePrefix("Falcon4_").removeSuffix(".xml")}=${f?.parentFile?.name ?: "none"}" })
        if (h != null) {
            appendLine("# header: theater \"${h.theaterName}\", scenario \"${h.scenario}\" (start file: ${start?.name ?: "not found"}), saved as \"${h.saveFile}\", title \"${h.uiName}\", clock ${CampaignArchive.clock(h.currentTime)} (day ${h.currentDay}), ${h.unread} bytes unread")
            val sq = h.squadron(h.playerSquadron)
            appendLine("# player squadron ${h.playerSquadron}${sq?.let { " = ${it.name} @ ${it.airbase}" } ?: ""}; bullseye X ${h.bullseyeX} Y ${h.bullseyeY} (cells, as WDP reads them: X east, Y north)")
            appendLine("# teams: " + h.teams.filter { it.name.isNotBlank() }.joinToString { "${it.n} ${it.name}" })
        }
        // the team part: what the situation BMS prints is made of (ground and air actions), and the mottos
        fun objName(id: CampaignArchive.VuId) = if (id.isNone) "-" else CampaignArchive.place(save, objs, id)?.name ?: "$id"
        val teamPart = save.teams
        if (teamPart == null) appendLine("# team part: not read (${if (save.directory?.has("tea") == true) "not believed" else "none"})")
        else teamPart.filter { it.name.isNotBlank() }.forEach { t ->
            appendLine("# team ${t.n} ${t.name} (controlled by ${t.controller}; stance ${t.stance.joinToString("")}): ground type ${t.ground.type} at ${CampaignFiles.hms(t.ground.time)} toward ${objName(t.ground.objective)}" +
                "; defensive air type ${t.defensive.type} toward ${objName(t.defensive.objective)}; offensive air type ${t.offensive.type} toward ${objName(t.offensive.objective)}" +
                (t.motto.takeIf { it.isNotBlank() }?.let { "; motto \"${it.replace(Regex("\\s+"), " ")}\"" } ?: ""))
        }
        h?.teams?.filter { it.motto.isNotBlank() }?.forEach { appendLine("# header motto of team ${it.n}: \"${it.motto.replace(Regex("\\s+"), " ")}\"") }
        appendLine("# primary objectives (.pol): ${save.primaryObjectives?.size ?: "none"}")
        val kinds = save.units.groupingBy { it.kind.name.lowercase() }.eachCount()
        appendLine("# units ${save.units.size}/${save.uni?.records} ${if (save.exact) "exact" else "INEXACT: ${save.uni?.stop}"}: $kinds; objectives of the start file: ${objs?.objectives?.size ?: "-"}")
        appendLine("# flights with a player slot: " + save.playerFlights.joinToString { "${names.callsign(it)} (seats ${it.playerSeats})" }.ifEmpty { "none" })
        save.errors.forEach { appendLine("FAIL: $it") }
        appendLine()

        var shown = 0
        for (p in save.packages) {
            val flights = save.flightsOf(p)
            if (filter != null && flights.none { (names.callsign(it) ?: "").lowercase().contains(filter) }) continue
            shown++
            val req = p.request
            appendLine("PACKAGE ${p.campId}  id ${p.id}  owner ${h?.team(p.owner)?.name ?: p.owner} (team ${p.owner})  mission ${names.mission(req.mission) ?: req.mission}" +
                (p.takeoff?.let { "  takeoff ${CampaignArchive.clock(it)}" } ?: "") + (req.tot?.let { "  TOT ${CampaignArchive.clock(it)}" } ?: "") +
                "  target ${place(req.target)}")
            appendLine("  support: tanker ${flightName(p.tanker)}; AWACS ${flightName(p.awacs)}; JSTARS ${flightName(p.jstars)}; ECM ${flightName(p.ecm)}; interceptor ${flightName(p.interceptor)}")
            for (f in flights) {
                val sq = h?.squadron(f.squadronId)
                val tacan = f.tacanChannel.firstOrNull()?.takeIf { it > 0 }?.let { "$it${f.tacanBand.firstOrNull()?.toChar() ?: ' '}" }
                appendLine("  FLIGHT ${names.callsign(f) ?: "?"}  #${f.campId}  id ${f.id}  ${f.aircraftCount}x ${names.aircraft(f.type) ?: "?"} (${names.unitClass(f.type) ?: f.type})" +
                    "  ${names.mission(f.mission) ?: f.mission}: \"${names.task(f.mission) ?: ""}\"")
                appendLine("    squadron ${sq?.name ?: f.squadronId} @ ${sq?.airbase ?: place((save.squadronOf(f))?.airbase)}; team ${h?.team(f.owner)?.name ?: f.owner}" +
                    "; T/O ${f.waypoints.firstOrNull()?.let { CampaignArchive.clock(it.depart) } ?: "-"}; TOT ${CampaignArchive.clock(f.tot)}; mission over ${CampaignArchive.clock(f.missionOver)}" +
                    "; current wp ${f.core.currentWp}; player slots ${f.playerSlots}; fuel ${f.fuelInitial}; laser ${f.laser}; TACAN ${tacan ?: "-"}")
                f.loadouts.forEachIndexed { i, l ->
                    appendLine("    loadout[$i] " + l.stores.joinToString(", ") { (id, n) -> "${n}x ${names.weapon(id) ?: "#$id"}" })
                    // per weapon, the way the briefing's Ordnance adds it up for one aircraft
                    val sum = LinkedHashMap<Int, Int>()
                    l.stores.forEach { (id, n) -> sum[id] = (sum[id] ?: 0) + n }
                    appendLine("      = " + sum.entries.joinToString(", ") { (id, n) -> "${n}x ${names.weapon(id) ?: "#$id"}" })
                }
                f.waypoints.forEachIndexed { i, w ->
                    appendLine("    wp%-2d %-10s N %8.0f E %8.0f alt %6.0f  arr %s dep %s  act %d  ract %d  form 0x%x  target %s%s".format(
                        i + 1, names.action(w.action) ?: "?", w.north, w.east, w.altFt, CampaignArchive.clock(w.arrive), CampaignArchive.clock(w.depart),
                        w.action, w.routeAction, w.formation, place(w.target, w.building),
                        w.designated?.withIndex()?.filter { !it.value.id.isNone }?.joinToString("") { (seat, d) -> "; seat ${seat + 1}: ${place(d.id, d.building)}" } ?: "",
                    ))
                }
                // what BMS's briefing scripts word out of the save for this flight (CampaignBriefing), and WDP's intel
                if (filter != null || f.hasPlayer) {
                    val words = CampaignBriefing.of(save, names, f, p, objs, CampaignFiles.landings(f).second)
                    appendLine("    briefing texts made of the save (${save.kind}; mission context ${req.context}, flight's ${f.missionContext}):")
                    words.situation?.split("\n\n")?.forEach { appendLine("      situation: $it") } ?: appendLine("      situation: -")
                    words.overview?.rows?.forEach { appendLine("      overview: ${it.joinToString(" ")}") }
                    appendLine("      pkg-mission: ${words.packageMission ?: "-"}")
                    if (words.roe.isEmpty()) appendLine("      roe: -") else words.roe.forEach { appendLine("      roe: $it") }
                    words.emergency.forEach { b -> appendLine("      emergency, ${b.title}: ${b.lines.joinToString(" / ")}") }
                    val i = words.intel
                    if (i == null) appendLine("      intel: - (the team part was not read)")
                    else appendLine("      intel (WDP's lists): ground [${i.ground.joinToString()}]; fighters [${i.fighters.joinToString()}]; fighter-bombers [${i.fighterBombers.joinToString()}]" +
                        "; bombers [${i.bombers.joinToString()}]; support [${i.support.joinToString()}]; helos [${i.helos.joinToString()}]")
                }
            }
        }
        appendLine()
        appendLine("packages shown $shown of ${save.packages.size}; flights ${save.flights.size}; read + parse %.1f ms".format(save.readMs))
        if (!save.exact) appendLine("FAIL: the unit part did not walk exactly: ${save.uni?.stop}")
        if (more.any { it == "--card" }) append(cardPage(file, set, t, save, names, filter))
    }

    /**
     * `--card` (with a callsign): the Planner's Briefing page and ROE box for that flight of the save, filled in-process
     * the way the Planner fills them after Open mission… when BMS has printed no briefing for the flight — the flight
     * as `/api/campaign/flight` answers it, loaded into a DataCard wiring with an empty cartridge. Text only.
     */
    private fun cardPage(
        file: File, set: Theaters.TheaterSet, t: Theaters.Theater, save: CampaignArchive.Save, names: CampaignArchive.Names, filter: String?,
    ): String = buildString {
        appendLine()
        val f = save.flights.firstOrNull { (names.callsign(it) ?: "").equals(filter, ignoreCase = true) }
            ?: save.flights.firstOrNull { filter != null && (names.callsign(it) ?: "").lowercase().contains(filter) }
        if (f == null) { appendLine("CARD: no flight is called \"${filter ?: ""}\"."); return@buildString }
        val ctx = CampaignFiles.Context(set, t.name, null, 0L)
        val cf = when (val a = CampaignFiles.flight(ctx, t.name, file.name, f.id.toString())) {
            is CampaignFiles.Answer.Ok -> a.value
            is CampaignFiles.Answer.Refused -> { appendLine("CARD: the flight route refused: ${a.status} ${a.sentence}"); return@buildString }
        }
        val theater = kotlinx.coroutines.runBlocking { com.bmscompanion.app.data.Repo.index().theaters.firstOrNull { it.id == t.appId } }
        val mission = com.bmscompanion.app.ui.screens.wdp.WdpMission(
            briefing = cf.briefing, theater = theater, flight = cf,
            ref = com.bmscompanion.app.data.mission.CampRef(t.name, file.name, cf.row.id),
        )
        val w = com.bmscompanion.app.ui.screens.wdp.DataCardWiring()
        kotlinx.coroutines.runBlocking { w.prepare(mission) { "" } }
        val v = w.values(emptyList())
        appendLine("THE PLANNER'S BRIEFING PAGE for ${cf.row.callsign} of ${file.name} (${cf.kind}), as Open mission… fills it with no printed briefing for this flight:")
        val boxes = listOf("lblBrfAirbase", "lblBrfAircraft", "lblBrfSquad", "txtBrfDescription", "txtBrfYourTask", "lblBrfTakeOff", "lblBrfTot", "txtBrfYourDescription") +
            (1..5).map { "txtBrfSituation$it" } + (1..9).map { "txtBrfIntel$it" } + (1..10).map { "txtBrfObjective$it" } + (1..5).map { "txtRoe$it" } +
            listOf("lblAtis1", "pnlWx.lines")
        for (n in boxes) appendLine("  %-22s %s".format(n, v[n]?.replace("\n", " | ") ?: "(none)"))
    }

    /** The BMS folder above [file]: the first parent that holds `Data/TerrData/TheaterDefinition/theater.lst`. */
    private fun findRoot(file: File): File? {
        var dir: File? = file.absoluteFile.parentFile
        while (dir != null) {
            if (Theaters.resolveFile(dir, "Data\\TerrData\\TheaterDefinition\\theater.lst") != null) return dir
            dir = dir.parentFile
        }
        return null
    }
}
