package com.bmscompanion.desktop.bridge

import java.io.File

/**
 * `--camsource out.txt <root> [callsign]`: for each theater, the save the old code picked and the one the new code picks.
 * The callsign (e.g. Cyborg6) names the Korea KTO flight whose route stands in for the pilot's plan in the live check.
 *
 * Up to 1.3.8 the planned tracks ([PlannedRoutes]), the team table ([TeamRelations]) and the weather maps
 * ([WeatherStore]) each guessed a theater's folder from folder names. Now all three ask the theater definitions
 * ([Theaters]). This check runs both over a BMS folder (the round-3 fixture, or any copy) and prints, per theater:
 * the save each picks, the class table and string table each reads, how many flights and which tanker and AWACS the
 * new pick holds, the team table it gives, and which weather folders each finds. It reads only.
 *
 * It demands Korea KTO → `Data\Campaign\Auto Save.cam` (on the fixture; anywhere, a save in Korea KTO's own campaign
 * folder and never `TvT_Start.cam`), every theater's pick in its own campaign folder, the folders the old guess
 * missed found, `341 RELOCATE` read, the live paths ([PlannedRoutes.current], [TeamRelations.current]) reading Korea
 * KTO's own save, and every weather folder the old scan found still found. A line starting with FAIL makes the check
 * exit 1.
 */
object CamSourceTest {
    fun run(root: File, more: List<String>): String = buildString {
        var fails = 0
        fun check(name: String, ok: Boolean, detail: String = "") {
            appendLine((if (ok) "PASS " else "FAIL ") + name + (if (detail.isEmpty()) "" else "  | $detail"))
            if (!ok) fails++
        }
        val base = root.absoluteFile
        val set = Theaters.at(base)
        val rootPath = Theaters.canonical(base)
        fun rel(f: File?): String {
            if (f == null) return "-"
            val p = runCatching { f.canonicalPath }.getOrDefault(f.absolutePath)
            return if (p.lowercase().startsWith(rootPath)) p.substring(rootPath.length).trimStart('\\', '/') else p
        }
        appendLine("root: ${base.path}")
        appendLine("theaters: ${set.all.size}; error: ${set.error ?: "none"}")
        if (set.all.isEmpty()) {
            check("the theater definitions are read", false, set.error ?: "no theaters")
            return@buildString
        }
        val install = BmsInstall().apply { refresh(base.path) }
        appendLine("install as the program sees it: ${install.baseDir}; registry curTheater '${install.theater}'")
        appendLine()

        // the tab-only split the string table had before, for "old"
        fun tabSplit(f: File?): Map<Int, String> {
            val map = HashMap<Int, String>()
            runCatching {
                f?.readLines(Charsets.ISO_8859_1)?.forEach { line ->
                    val tab = line.indexOf('\t')
                    if (tab > 0) {
                        val id = line.substring(0, tab).trim().toIntOrNull()
                        val text = line.substring(tab + 1).trim()
                        if (id != null && text.isNotEmpty()) map[id] = text
                    }
                }
            }
            return map
        }
        fun support(r: PlannedRoute): Boolean {
            val n = r.missionName.orEmpty()
            return n.contains("REFUEL", true) || n.contains("AWACS", true) || n.contains("AEW", true) || n.contains("ABCCC", true)
        }

        class Row(val t: Theaters.Theater, val old: PlannedRoutes.Source?, val new: PlannedRoutes.Source?)
        val rows = set.all.map { t ->
            Row(t, PlannedRoutes.legacySourceFor(base, t.name), PlannedRoutes.sourceFor(install, t.name))
        }

        appendLine("%-3s %-22s %-58s %-58s".format("#", "theater", "old pick", "new pick"))
        rows.forEachIndexed { i, r ->
            appendLine("%-3d %-22s %-58s %-58s".format(i, r.t.name, rel(r.old?.file), rel(r.new?.file)))
        }
        appendLine()

        // per theater: what the new pick holds, and how each reads it
        val routesOf = HashMap<String, List<PlannedRoute>>()
        for (r in rows) {
            appendLine("--- ${r.t.name}")
            val src = r.new
            if (src == null) { appendLine("    new pick: none (no save that is not a start in ${rel(set.campaignDir(r.t))})"); continue }
            val place = PlannedRoutes.placeOf(src, install)
            val newTable = set.classFile(r.t, "Falcon4_CT.xml")
            val oldTable = r.old?.let { PlannedRoutes.legacyClassTable(it.theaterDir, install) }
            appendLine("    theater of the new pick: ${place.theater?.name ?: "NOT FOUND"}; class table new ${rel(newTable)}, old ${rel(oldTable)}")
            val names = set.strings(r.t)
            val oldNames = tabSplit(set.stringsFile(r.t))
            val routes = runCatching { PlannedRoutes.read(src, install) }.getOrDefault(emptyList())
            routesOf[r.t.name] = routes
            val named = routes.count { names[300 + it.mission] != null }
            val namedOld = routes.count { oldNames[300 + it.mission] != null }
            appendLine("    strings ${rel(set.stringsFile(r.t))}: ${names.size} (split on tabs only: ${oldNames.size}); flights ${routes.size}, mission named ${named} (tabs only: $namedOld)")
            val sup = routes.filter { support(it) }
            appendLine("    tanker/AWACS in it: " + (if (sup.isEmpty()) "none" else sup.joinToString { "${it.callsign ?: "?"} ${it.missionName}" }))
            val teams = runCatching { TeamRelations.read(src.file) }.getOrDefault(emptyList())
            val oldTeams = r.old?.let { o -> runCatching { TeamRelations.read(o.file) }.getOrDefault(emptyList()) }.orEmpty()
            appendLine("    teams new: ${teams.size} ${teams.map { it.name }.filter { it.isNotEmpty() }}; old (${r.old?.file?.name ?: "-"}): ${oldTeams.size} ${oldTeams.map { it.name }.filter { it.isNotEmpty() }}")
        }
        appendLine()

        // ---- the demands
        val kto = set.byName("Korea KTO")
        val ktoRow = rows.firstOrNull { it.t == kto }
        val ktoDir = set.campaignDir(kto)
        val ktoPick = ktoRow?.new?.file
        check(
            "Korea KTO's pick is in its own campaign folder, not a start, not TvT_Start.cam",
            ktoPick != null && ktoDir != null && Theaters.canonical(ktoPick.parentFile) == Theaters.canonical(ktoDir) &&
                !ktoPick.name.equals("TvT_Start.cam", true) && !Theaters.isStart(ktoPick),
            "new ${rel(ktoPick)}, old ${rel(ktoRow?.old?.file)}",
        )
        // On the round-3 fixture the newest save of the base theater is the played campaign's Auto Save
        val autoSave = ktoDir?.let { d -> Theaters.resolveFile(d, "Auto Save.cam") }
        val newestThere = ktoDir?.listFiles { f -> f.isFile && (f.name.endsWith(".cam", true) || f.name.endsWith(".tac", true)) }
            ?.maxByOrNull { it.lastModified() }
        if (autoSave != null && newestThere != null && Theaters.canonical(newestThere) == Theaters.canonical(autoSave)) {
            check("Korea KTO -> Data\\Campaign\\Auto Save.cam (the fixture's played campaign)", rel(ktoPick).equals("Data\\Campaign\\Auto Save.cam", true), rel(ktoPick))
        } else {
            appendLine("     (this root's newest Korea KTO save is ${newestThere?.name ?: "none"}: the fixture's Auto Save demand does not apply)")
        }
        appendLine("     the old code picked ${rel(ktoRow?.old?.file)} for Korea KTO")
        val ktoRoutes = kto?.let { routesOf[it.name] }.orEmpty()
        check("Korea KTO's pick holds flights (the old pick held none)", ktoRoutes.isNotEmpty(), "${ktoRoutes.size} flights; tanker/AWACS ${ktoRoutes.filter { support(it) }.mapNotNull { it.callsign }}")
        val ktoTeams = ktoPick?.let { runCatching { TeamRelations.read(it) }.getOrDefault(emptyList()) }.orEmpty()
        check("Korea KTO's team table comes from its own save", ktoTeams.isNotEmpty() && ktoTeams.none { it.name.equals("GREFOR", true) },
            ktoTeams.map { it.name }.filter { it.isNotEmpty() }.toString())

        // The two live paths, as Bridge calls them: the planned tracks believed once a flight plan lines up with a
        // flight in the save (a callsign after the root picks the flight, else the first with three points), and the
        // map's sides from the same save.
        val flight = more.firstOrNull()?.let { c -> ktoRoutes.firstOrNull { it.callsign.equals(c, true) } }
            ?: ktoRoutes.firstOrNull { it.points.size >= 3 }
        if (kto != null && flight != null) {
            val live = PlannedRoutes.current(install, kto.name, flight.points.map { it.x to it.y })
            check("the live planned-routes path believes Korea KTO's save for ${flight.callsign}'s plan", live.isNotEmpty(),
                "${live.size} flights, tanker/AWACS ${live.filter { support(it) }.mapNotNull { it.callsign }}")
        } else {
            check("a flight of Korea KTO to stand in for the pilot's plan", false, "none")
        }
        val liveTeams = kto?.let { TeamRelations.current(install, it.name) }.orEmpty()
        check("the live team path reads the same table", liveTeams.map { it.name } == ktoTeams.map { it.name }, liveTeams.map { it.name }.filter { it.isNotEmpty() }.toString())

        // every theater with a save that is not a start gets one from its own campaign folder
        val wrongFolder = rows.filter { r ->
            val f = r.new?.file ?: return@filter false
            val d = set.campaignDir(r.t) ?: return@filter true
            Theaters.canonical(f.parentFile) != Theaters.canonical(d)
        }
        val missing = rows.filter { r -> r.new == null && set.saves(r.t, withTraining = false).any { !Theaters.isStart(it) } }
        check("every theater's pick is in its own campaign folder", wrongFolder.isEmpty(), wrongFolder.joinToString { it.t.name })
        check("every theater with a save that is not a start has a pick", missing.isEmpty(), missing.joinToString { it.t.name })
        check("no pick is a start", rows.none { r -> r.new?.file?.let { Theaters.isStart(it) } == true })

        // the folders the old guess could not see: Korea 2012's six (two levels down) and LKTO's Campaign+
        val oldPlaces = listOf(Theaters.canonical(File(set.data, "Campaign"))) +
            (set.data.listFiles { f -> f.isDirectory && f.name.startsWith("Add-On", true) }?.map { Theaters.canonical(File(it, "Campaign")) }.orEmpty())
        val unseen = rows.filter { r -> set.campaignDir(r.t)?.let { Theaters.canonical(it) !in oldPlaces } ?: false }
        appendLine("     campaign folders the old guess could not look in: ${unseen.size} (${unseen.joinToString { it.t.name }})")
        val unseenWithSaves = unseen.filter { set.saves(it.t, withTraining = false).any { f -> !Theaters.isStart(f) } }
        check("those theaters now get a pick from their own folder", unseenWithSaves.all { it.new != null }, unseenWithSaves.joinToString { "${it.t.name} -> ${rel(it.new?.file)}" })

        // the string table: split on the first whitespace
        val strings = set.strings(kto)
        check("Strings.txt split on the first whitespace (341 RELOCATE)", strings[341] == "RELOCATE", "341='${strings[341]}', tabs only: '${tabSplit(set.stringsFile(kto))[341]}'")

        // with no theater named, every theater is considered and the newest save wins
        val any = PlannedRoutes.sourceFor(install, null)
        val newestAll = rows.mapNotNull { it.new?.file }.maxByOrNull { it.lastModified() }
        check("no theater named: the newest pick of all theaters", any?.file?.let { f -> newestAll != null && Theaters.canonical(f) == Theaters.canonical(newestAll) } ?: (newestAll == null), rel(any?.file))

        // ---- weather maps: the same theaters, from the same definitions
        appendLine()
        val data = set.data
        val store = WeatherStore(BmsInstall(), dataOverride = data)
        val wxNew = store.theaterDirs()
        val wxOld = store.oldTheaterDirs()
        appendLine("weather folders (new: every theater's campaign folder; old: Data\\Campaign and Add-On *\\Campaign holding a map): new ${wxNew.size}, old ${wxOld.size}")
        wxNew.forEach { (id, name, dir) -> appendLine("    new %-22s %-24s %s".format(id, name, rel(dir))) }
        wxOld.forEach { (id, name, dir) -> appendLine("    old %-22s %-24s %s".format(id, name, rel(dir))) }
        val withMaps = set.all.mapNotNull { set.campaignDir(it) }.distinctBy { Theaters.canonical(it) }
            .filter { d -> WeatherStore.Model.entries.any { File(d, it.file).isFile } }
        if (withMaps.isEmpty()) {
            appendLine("     (no weather models in this root — the round-3 fixture has none; r3b-C3's copy adds them)")
        } else {
            val newDirs = wxNew.map { Theaters.canonical(it.third) }.toSet()
            val lost = wxOld.filter { Theaters.canonical(it.third) !in newDirs }
            check("every weather folder the old scan found is still found", lost.isEmpty(), lost.joinToString { it.second })
            check("every campaign folder with weather models is found", withMaps.all { Theaters.canonical(it) in newDirs },
                withMaps.filter { Theaters.canonical(it) !in newDirs }.joinToString { rel(it) })
            val gained = wxNew.filter { n -> wxOld.none { Theaters.canonical(it.third) == Theaters.canonical(n.third) } }
            appendLine("     found now and not before: ${gained.size} (${gained.joinToString { it.second }})")
            check("weather ids are the app's theater ids", wxNew.all { (id, name, _) -> id == Theaters.slug(name) })
            // a page that still asks with an old id ("korea-the-base-theater", "lkto") gets the folder it meant
            val wrongOld = wxOld.filter { (oldId, _, dir) ->
                val shown = store.state(detail = oldId).theaters.firstOrNull { it.models.isNotEmpty() }
                shown == null || Theaters.canonical(File(shown.dir)) != Theaters.canonical(dir)
            }
            check("an old weather id still reaches the folder it named", wrongOld.isEmpty(), wrongOld.joinToString { it.first })
            // and the store as the program builds it (the install's folder, no override) lists the same theaters
            val live = WeatherStore(install).state().theaters.map { it.id }
            check("the program's own weather store lists the same theaters", live == wxNew.map { it.first }, "${live.size} theaters")
        }

        appendLine()
        appendLine(if (fails == 0) "ALL PASS" else "FAIL: $fails check(s) failed")
    }
}
