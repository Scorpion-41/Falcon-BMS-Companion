package com.bmscompanion.desktop.bridge

import com.bmscompanion.app.data.mission.Briefing
import com.bmscompanion.app.data.mission.MissionPicture
import com.bmscompanion.app.data.mission.TargetCatalog
import com.bmscompanion.app.data.mission.TargetCategory
import com.bmscompanion.app.data.mission.TargetGroup
import com.bmscompanion.app.data.mission.TargetItem
import com.bmscompanion.app.data.mission.TargetSet
import kotlinx.serialization.builtins.serializer
import java.io.File
import java.security.MessageDigest

/**
 * Pictures of the mission's targets, drawn from Falcon BMS's own 3-D models (docs/PROTOCOL.md, "Target pictures").
 *
 * - **Which targets** ([missionSet]): the flight's own targets (its waypoints' target and each seat's designated one:
 *   a unit's vehicles, an objective's buildings), **only those an enemy team holds** ([flightTargetsOf]: never the
 *   flight's take-off, landing or alternate field, nor anything of its own or an allied side), and the mission's threats — the air defences of `MissionData.ground`
 *   (the printed briefing's Threat Analysis placed in the save, or the spotted ones along the route; [MissionGrounds],
 *   in WDP mode the snapshot's), each as its battalion ([TargetModels.battalion]: the launcher, its radars and the rest
 *   of the unit from BMS's unit table), and the spotted ships. Read only.
 * - **Each picture** ([picture]): the thing's class-table entry → its model folder → `Model_0.bml` ([BmlModel]) with the
 *   missiles it shows on its slots → [TargetRender], two views and two looks, a JPEG.
 * - **Nothing is shipped and little is kept**: pictures are drawn when first asked for, kept in memory (a few MB) and
 *   in a capped folder ([cacheDir], `%LOCALAPPDATA%\BMS Companion\Target pictures`, [DISK_CAP] bytes, the least recently
 *   used deleted first). A model or table that changes gives a new key. Nothing here throws to a caller.
 */
object TargetPictures {
    /** Bumped whenever the drawing changes, so a cached picture of an older drawing is never served. */
    private const val RENDER_VERSION = 2
    const val DISK_CAP = 50L shl 20
    private const val MEMORY_CAP = 12L shl 20
    const val DEFAULT_WIDTH = 640

    /** A developer check points the cache at a scratch folder. */
    @Volatile var cacheOverride: File? = null

    /** Where pictures are kept between runs. */
    val cacheDir: File
        get() = cacheOverride ?: File(
            System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() } ?: (System.getProperty("user.home") + "\\AppData\\Local"),
            "BMS Companion\\Target pictures",
        )

    // ================================================================================================ routes

    /** `/api/targets/...` (see docs/PROTOCOL.md). */
    fun handle(req: ApiRequest): ApiResponse {
        val path = req.path.trimEnd('/')
        return try {
            when (path) {
                "/api/targets/mission" -> json(TargetSet.serializer(), missionSet())
                "/api/targets/catalog" -> json(TargetCatalog.serializer(), catalog(req.query["theater"]))
                "/api/targets/system" -> json(TargetGroup.serializer(), system(req.query["theater"], req.query["name"].orEmpty()))
                "/api/targets/picture" -> {
                    val width = req.query["w"]?.toIntOrNull()?.coerceIn(160, 1600) ?: DEFAULT_WIDTH
                    when (val a = picture(req.query["theater"], req.query["key"].orEmpty(), TargetRender.View.of(req.query["view"]), TargetRender.Look.of(req.query["look"]), width)) {
                        is Answer.Ok -> ApiResponse(200, "image/jpeg", a.bytes)
                        is Answer.Refused -> ApiResponse.json("""{"error":${Bridge.json.encodeToString(String.serializer(), a.why)}}""", a.status)
                    }
                }
                else -> ApiResponse.notFound()
            }
        } catch (e: Throwable) {
            BridgeLog.warn("${req.path}: ${e.message}")
            ApiResponse.json("""{"error":"The target pictures could not be made."}""", 500)
        }
    }

    private fun <T> json(s: kotlinx.serialization.KSerializer<T>, v: T) = ApiResponse.json(Bridge.json.encodeToString(s, v))

    sealed class Answer {
        class Ok(val bytes: ByteArray) : Answer()
        class Refused(val status: Int, val why: String) : Answer()
    }

    // ================================================================================================ the theater

    /** A developer check reads a copy of a BMS folder instead of the install. */
    @Volatile var setOverride: Theaters.TheaterSet? = null

    private fun theaterSet(): Theaters.TheaterSet? = setOverride ?: Theaters.of(Bridge.install)

    /** The theater the mission is in: WDP mode's snapshot's, else the one BMS's mission file is in, else BMS's current. */
    private fun missionTheater(set: Theaters.TheaterSet): Theaters.Theater? {
        if (MissionSource.wdp) MissionSource.populated()?.theater?.let { set.byName(it) }?.let { return it }
        runCatching { MissionDtcFile.verdict().theater }.getOrNull()?.let { set.byName(it) }?.let { return it }
        return set.current(Bridge.install.theater)
    }

    private fun tablesFor(theater: String?): Pair<TargetModels.Tables?, String?> {
        val set = theaterSet() ?: return null to "Falcon BMS's folder is not known on this PC."
        val t = theater?.takeIf { it.isNotBlank() }?.let { set.byName(it) } ?: missionTheater(set)
        val tables = TargetModels.tables(set, t) ?: return null to "No class table (Falcon4_CT.xml) was found for ${t?.name ?: "this theater"}."
        return tables to null
    }

    // ================================================================================================ lists

    private fun item(k: TargetModels.Kind, role: String? = null, count: Int? = null) =
        TargetItem(key = "ct${k.ct}", name = k.name, role = role, count = count, category = k.category)

    /** Every ground target of [theater] (the mission's when null), by category. */
    fun catalog(theater: String?): TargetCatalog {
        val (tables, why) = tablesFor(theater)
        if (tables == null) return TargetCatalog(error = why)
        return TargetCatalog(
            theater = tables.theater?.name.orEmpty(),
            categories = TargetModels.catalog(tables).map { (c, list) -> TargetCategory(c, list.map { item(it) }) },
        )
    }

    /**
     * One system's battalion by its name ("SA-6 (2K12)", "SA-6"), for the Threats page; [names] may hold several
     * separated by `|` (the TacRef name, the guide's, its aliases), tried in order.
     */
    fun system(theater: String?, names: String): TargetGroup {
        val list = names.split('|').map { it.trim() }.filter { it.isNotEmpty() }
        val name = list.firstOrNull().orEmpty()
        val (tables, why) = tablesFor(theater)
        if (tables == null) return TargetGroup(title = name, kind = "threat", note = why)
        val k = list.firstNotNullOfOrNull { TargetModels.byName(tables, it) }
            ?: return TargetGroup(title = name, kind = "threat", note = "Falcon BMS's tables have no vehicle of that name in ${tables.theater?.name ?: "this theater"}.")
        return TargetGroup(title = k.name, kind = "threat", items = TargetModels.battalion(tables, k).map { item(it.kind, it.role, it.count) })
    }

    /** The mission's targets and threats, in either mode; the pictures of the first views are drawn in the background. */
    fun missionSet(): TargetSet {
        val set = theaterSet() ?: return TargetSet(error = "Falcon BMS's folder is not known on this PC.")
        val t = missionTheater(set)
        val tables = TargetModels.tables(set, t) ?: return TargetSet(error = "No class table was found for ${t?.name ?: "this theater"}.")
        val wdp = MissionSource.wdp
        val data = if (wdp) MissionSource.mission() else null
        val briefing: Briefing? = if (wdp) data?.briefing else Bridge.currentBriefing()
        val ground = if (wdp) data?.ground else MissionGrounds.forBriefing()
        val missionKey = if (wdp) "wdp-${MissionSource.populated()?.at ?: 0}" else "ez-${Bridge.briefingModified}"
        if (wdp && MissionSource.populated() == null) return TargetSet(theater = t?.name.orEmpty(), mission = missionKey, error = "Populate from Planner first.")
        if (!wdp && briefing == null) return TargetSet(theater = t?.name.orEmpty(), mission = missionKey, error = "Print the briefing in Falcon BMS first.")

        val groups = ArrayList<TargetGroup>()
        groups += flightTargets(set, t, tables, wdp, briefing)

        // the threats: each system once, in the briefing's order, as its battalion
        val rows = MissionPicture.briefedThreats(briefing)
        val systems = LinkedHashMap<String, String?>()
        // (sameSystem takes an empty name as any system, so blank ones are left out and keys compared exactly)
        for (s in ground?.airDefences.orEmpty()) if (MissionPicture.systemKey(s.system).isNotEmpty()) systems.putIfAbsent(s.system, null)
        for (r in rows) {
            val k = MissionPicture.systemKey(r.system)
            if (k.isNotEmpty() && systems.keys.none { MissionPicture.systemKey(it) == k }) systems[r.system] = null
        }
        for (name in systems.keys) {
            val k = TargetModels.byName(tables, name) ?: continue
            val where = rows.filter { MissionPicture.sameSystem(it.system, name) }.map { it.where }.filter { it.isNotBlank() }.distinct()
            val sites = ground?.airDefences.orEmpty().count { it.system == name }
            val note = (if (where.isNotEmpty()) where.joinToString("; ") else null)
                ?: if (sites > 1) "$sites sites" else null
            groups += TargetGroup(title = k.name, kind = "threat", note = note, items = TargetModels.battalion(tables, k).map { item(it.kind, it.role, it.count) })
        }
        for (name in ground?.ships.orEmpty().map { it.system }.distinct()) {
            val k = TargetModels.byName(tables, name) ?: continue
            groups += TargetGroup(title = k.name, kind = "ship", items = listOf(item(k, "ship", null)))
        }
        val out = TargetSet(theater = t?.name.orEmpty(), mission = missionKey, groups = groups.filter { it.items.isNotEmpty() })
        prewarm(out)
        return out
    }

    /**
     * The flight's own targets: the save's flight (EZBoards mode: the save that provably holds the printed flight,
     * [MissionDtcFile.briefedFlight]; WDP mode: the populated one) — each waypoint's target and each seat's designated
     * target, a unit as its vehicles, an objective as its buildings (the one building when the waypoint names one).
     */
    private fun flightTargets(set: Theaters.TheaterSet, t: Theaters.Theater?, tables: TargetModels.Tables, wdp: Boolean, briefing: Briefing?): List<TargetGroup> = try {
        val names = CampaignArchive.names(set, t)
        val (saveFile, flight) = if (wdp) {
            val p = MissionSource.populated()
            val dir = set.campaignDir(set.byName(p?.theater) ?: t)
            val file = p?.save?.takeIf { it.isNotBlank() }?.let { s -> dir?.let { Theaters.resolveFile(it, s) } }
            val save = file?.let { CampaignArchive.cached(it, names) }
            val id = p?.flight?.let { CampaignArchive.VuId.parse(it) }
            file to (save?.let { s -> id?.let { s.flight(it) } }?.let { s0 -> save to s0 })
        } else {
            val v = MissionDtcFile.verdict()
            val file = v.save
            val save = file?.let { CampaignArchive.cached(it, names) }
            file to (save?.let { s -> briefing?.let { MissionDtcFile.briefedFlight(s, it) } }?.let { save to it })
        }
        if (saveFile == null || flight == null) emptyList()
        else {
            val (save, f) = flight
            flightTargetsOf(save, f, tables).filter { it.kept }.map { it.group }.take(6)
        }
    } catch (e: Throwable) {
        BridgeLog.info("The flight's targets could not be read for the target pictures: ${e.message}")
        emptyList()
    }

    private const val ACT_TAKEOFF = 1
    private const val ACT_LAND = 7

    /**
     * Whether team [other] is an enemy of team [own] in [save]: hostile or at war in the stances of [own]'s team or of
     * the team that controls it (in Korea ROK controls the U.S. team), and not on its side by [SupportSide.ours].
     * Null when the save's team table was not read.
     */
    fun enemy(save: CampaignArchive.Save, own: Int, other: Int): Boolean? {
        if (other == own) return false
        val teams = save.teams ?: return null
        if (SupportSide.ours(save, own, other)) return false
        val mine = teams.firstOrNull { it.n == own }
        val ctrl = teams.firstOrNull { it.n == (mine?.controller ?: own) }
        return listOfNotNull(mine, ctrl).any { t -> t.stance.getOrNull(other).let { it == TeamRelations.HOSTILE || it == TeamRelations.WAR } }
    }

    /** One place a waypoint of the flight points at, as a picture group, and whether the side rule keeps it ([why] if not). */
    class FlightTarget(val group: TargetGroup, val owner: Int?, val action: Int, val kept: Boolean, val why: String?)

    /**
     * Every place [f]'s waypoints point at (each waypoint's target and each seat's designated one) that has a model,
     * once each — and whether it is a target: only a place held by an **enemy** team ([enemy]: the unit's owner, an
     * objective's owner now from the save's `.obd` changes, else the start file's). The take-off and landing waypoints
     * point at the flight's own fields, and a support or escort waypoint at friendly things; none of those is kept.
     * Without the save's team table, a place is kept only when it is not the flight's own team's and its waypoint is
     * neither a take-off nor a landing.
     */
    fun flightTargetsOf(save: CampaignArchive.Save, f: CampaignArchive.Flight, tables: TargetModels.Tables): List<FlightTarget> {
        val objs = CampaignArchive.startFile(save)?.let { CampaignArchive.objectives(it) }
        val deltas = runCatching { CampaignArchive.deltas(save.file) }.getOrNull()
        // each place once: where it was first found, or where it is first kept when an earlier find was not
        val seen = HashMap<String, Int>()
        val out = ArrayList<FlightTarget>()
        fun add(id: CampaignArchive.VuId?, building: Int?, stpt: Int, action: Int) {
            val p = CampaignArchive.place(save, objs, id, building) ?: return
            val ct = p.type - 100
            val key = "${p.kind}|${p.campId}|${p.building}"
            val earlier = seen[key]
            if (earlier != null && (out[earlier].kept || action == ACT_TAKEOFF || action == ACT_LAND)) return
            val items = when (p.kind) {
                "unit" -> TargetModels.unitMembers(tables, ct).map { item(it.kind, "unit", it.count) }
                else -> if (tables.objectiveOcd.containsKey(ct)) TargetModels.objectiveFeatures(tables, ct, p.building).map { item(it, "building") } else emptyList()
            }
            if (items.isEmpty()) return
            // a unit as the briefing names it ("500th Tank Battalion"), not its bare class ("Tank")
            val unitName = if (p.kind == "unit") save.unit(id)?.let { CampaignArchive.unitName(save, it) } else null
            val title = (unitName ?: p.name)?.trim()?.takeIf { it.isNotEmpty() } ?: return
            val owner = if (p.kind == "unit") save.unit(id)?.owner else (deltas?.get(id)?.owner ?: objs?.get(id)?.owner)
            val enemy = owner?.let { enemy(save, f.owner, it) }
            val why = when {
                // the field's own waypoints: the take-off and landing points name the flight's fields, and their seats'
                // "designated" places are leftovers (a village of the enemy's on a training flight's take-off at Gunsan)
                action == ACT_TAKEOFF || action == ACT_LAND -> "a take-off or landing waypoint's"
                owner == null -> "owner unknown"
                enemy == true -> null
                enemy == false -> "not an enemy's (team $owner)"
                owner == f.owner -> "the flight's own team's"
                else -> null
            }
            val found = FlightTarget(TargetGroup(title = title, kind = "target", note = "STPT $stpt", items = items), owner, action, why == null, why)
            if (earlier != null) { if (found.kept) out[earlier] = found }
            else { seen[key] = out.size; out += found }
        }
        f.waypoints.forEachIndexed { i, w ->
            if (w.target != null) add(w.target, w.building.takeIf { it != 255 }, i + 1, w.action)
            w.designated?.forEach { d -> if (!d.id.isNone) add(d.id, d.building.takeIf { it != 255 }, i + 1, w.action) }
        }
        return out
    }

    @Volatile private var warmed: String? = null

    /** Draws the mission's first pictures in the background, so the Briefing card has them by the time it is opened. */
    private fun prewarm(s: TargetSet) {
        val id = s.theater + "|" + s.mission + "|" + s.groups.sumOf { it.items.size }
        if (warmed == id) return
        warmed = id
        val keys = s.groups.flatMap { g -> g.items.map { it.key } }.distinct()
        Thread({
            for (k in keys) for (v in TargetRender.View.entries) {
                if (warmed != id) return@Thread
                picture(s.theater, k, v, TargetRender.Look.PLAIN, DEFAULT_WIDTH)
            }
        }, "target-pictures").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }

    // ================================================================================================ pictures

    private val renderLock = Any()
    private val memory = object : LinkedHashMap<String, ByteArray>(64, 0.75f, true) {}
    private var memoryBytes = 0L
    private val models = object : LinkedHashMap<String, BmlModel>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, BmlModel>?) = size > 12
    }

    /** Timings of the last pictures drawn (ms: read, draw, encode), for the developer check. */
    @Volatile var lastTiming: String = ""

    /** The picture of [key] ("ct16") of [theater]'s tables (the mission's when null). */
    fun picture(theater: String?, key: String, view: TargetRender.View, look: TargetRender.Look, width: Int): Answer {
        val ct = key.removePrefix("ct").toIntOrNull() ?: return Answer.Refused(400, "Which target? (key=ct<n>)")
        val (tables, why) = tablesFor(theater)
        if (tables == null) return Answer.Refused(503, why ?: "No tables.")
        val kind = tables.kinds[ct] ?: return Answer.Refused(404, "Class $ct has no model in ${tables.theater?.name ?: "this theater"}.")
        val file = TargetModels.modelFile(tables, kind.gfx) ?: return Answer.Refused(404, "The model of ${kind.name} (${kind.gfx}) is not in this install.")
        val slotFiles = kind.slotModels.mapValues { (_, g) -> TargetModels.modelFile(tables, g) }.filterValues { it != null }.mapValues { it.value!! }
        val height = width * 5 / 8
        val cacheKey = listOf(
            "v$RENDER_VERSION", CampaignArchive.key(file), slotFiles.entries.sortedBy { it.key }.joinToString(",") { "${it.key}:${CampaignArchive.key(it.value)}" },
            view.id, look.id, width.toString(), kind.name,
        ).joinToString("#")
        synchronized(memory) { memory[cacheKey] }?.let { return Answer.Ok(it) }
        val disk = diskFile(cacheKey)
        try {
            if (disk.isFile) {
                val bytes = disk.readBytes()
                disk.setLastModified(System.currentTimeMillis())
                remember(cacheKey, bytes)
                return Answer.Ok(bytes)
            }
        } catch (_: Throwable) { /* drawn again */ }
        synchronized(renderLock) {
            synchronized(memory) { memory[cacheKey] }?.let { return Answer.Ok(it) }
            val t0 = System.nanoTime()
            val model = model(file) ?: return Answer.Refused(422, "The model of ${kind.name} could not be read: ${BmlModel.lastError ?: "unknown layout"}.")
            val slots = slotFiles.mapNotNull { (s, f) -> model(f)?.let { s to it } }.toMap()
            val tris = BmlModel.FloatList()
            model.triangles(tris, slots)
            val t1 = System.nanoTime()
            if (tris.size < 9) return Answer.Refused(422, "The model of ${kind.name} has no surfaces to draw.")
            val img = TargetRender.render(tris.data, tris.size, view, look, width, height, kind.name, seed = (kind.ct * 31L + view.ordinal))
            val t2 = System.nanoTime()
            val bytes = TargetRender.jpeg(img)
            val t3 = System.nanoTime()
            lastTiming = "read %.0f ms, draw %.0f ms, encode %.0f ms, %d triangles".format((t1 - t0) / 1e6, (t2 - t1) / 1e6, (t3 - t2) / 1e6, tris.size / 9)
            remember(cacheKey, bytes)
            store(disk, bytes)
            return Answer.Ok(bytes)
        }
    }

    private fun model(f: File): BmlModel? {
        val k = CampaignArchive.key(f)
        synchronized(models) { models[k] }?.let { return it }
        val m = BmlModel.read(f) ?: return null
        synchronized(models) { models[k] = m }
        return m
    }

    private fun remember(key: String, bytes: ByteArray) = synchronized(memory) {
        memory.put(key, bytes)?.let { memoryBytes -= it.size }
        memoryBytes += bytes.size
        val it = memory.entries.iterator()
        while (memoryBytes > MEMORY_CAP && it.hasNext()) {
            val e = it.next()
            memoryBytes -= e.value.size
            it.remove()
        }
    }

    private fun diskFile(key: String): File {
        val hex = MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(cacheDir, "$hex.jpg")
    }

    /** Writes a picture to the folder (a temporary file, then a move) and keeps the folder under [DISK_CAP]. */
    private fun store(f: File, bytes: ByteArray) {
        try {
            val dir = f.parentFile
            if (!dir.isDirectory && !dir.mkdirs()) return
            val tmp = File(dir, f.name + ".tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
            trim(dir, DISK_CAP)
        } catch (_: Throwable) {
            // a picture not kept is drawn again next time
        }
    }

    /** Deletes the least recently used pictures in [dir] until it holds at most 80 % of [cap] (once it is over [cap]). */
    fun trim(dir: File, cap: Long) {
        val files = dir.listFiles { x -> x.isFile && x.name.endsWith(".jpg") }?.toList().orEmpty()
        var total = files.sumOf { it.length() }
        if (total <= cap) return
        for (x in files.sortedBy { it.lastModified() }) {
            if (total <= cap * 8 / 10) break
            val n = x.length()
            if (x.delete()) total -= n
        }
    }

    /** Forgets what is in memory (a check that measures drawing). */
    fun forgetMemory() = synchronized(memory) { memory.clear(); memoryBytes = 0; synchronized(models) { models.clear() } }
}
