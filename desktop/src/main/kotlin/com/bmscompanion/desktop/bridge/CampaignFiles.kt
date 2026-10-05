package com.bmscompanion.desktop.bridge

import com.bmscompanion.app.data.mission.ownFlight
import com.bmscompanion.app.data.Airport
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.mission.BriefOverview
import com.bmscompanion.app.data.mission.BriefSteerpoint
import com.bmscompanion.app.data.mission.Briefing
import com.bmscompanion.app.data.mission.CampAto
import com.bmscompanion.app.data.mission.CampAtoTargets
import com.bmscompanion.app.data.mission.CampBriefKey
import com.bmscompanion.app.data.mission.CampDeparture
import com.bmscompanion.app.data.mission.CampDepartures
import com.bmscompanion.app.data.mission.CampFeatures
import com.bmscompanion.app.data.mission.CampFile
import com.bmscompanion.app.data.mission.CampFiles
import com.bmscompanion.app.data.mission.CampFlight
import com.bmscompanion.app.data.mission.CampFlightRow
import com.bmscompanion.app.data.mission.CampIniCheck
import com.bmscompanion.app.data.mission.CampLoadout
import com.bmscompanion.app.data.mission.CampObjectives
import com.bmscompanion.app.data.mission.CampPackage
import com.bmscompanion.app.data.mission.CampPackageRoute
import com.bmscompanion.app.data.mission.CampPlace
import com.bmscompanion.app.data.mission.CampSite
import com.bmscompanion.app.data.mission.CampSupport
import com.bmscompanion.app.data.mission.CampTeam
import com.bmscompanion.app.data.mission.CampTheater
import com.bmscompanion.app.data.mission.CampWaypoint
import com.bmscompanion.app.data.mission.OrdnanceAircraft
import com.bmscompanion.app.data.mission.OrdnanceFlight
import com.bmscompanion.app.data.mission.PackageFlight
import com.bmscompanion.app.data.mission.Store
import com.bmscompanion.app.data.mission.TrackPoint
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Falcon BMS's campaign, TE and training saves as the Planner's Open mission window and flight picker see them, on
 * any device (`/api/campaign/files`, `/ato`, `/flight`; docs/PROTOCOL.md, "Planner integration").
 *
 * **The listing** ([list]) is every theater of `theater.lst` with the saves directly in its `campaigndir` (never its
 * subfolders: our weather backup and BMS's `WeatherMapsUpdates` live there). A file is looked at in two steps: its
 * archive directory alone (a few hundred bytes: the kind of file and whether it is a start), then — for a file that is
 * listed — a whole read and parse ([CampaignArchive]) for its clock, campaign name, flight and package counts, the
 * flight with a player slot and whether it holds the printed briefing's flight. That second step is cached by path,
 * size and time, so a second listing reads nothing but the directories. A file modified in the last
 * [Theaters.SETTLE_MS] is left out until BMS has finished writing it. Files are ordered newest first by the later of
 * created and modified: Windows keeps a copied file's modified time and gives it a new created time, so a TE a friend
 * just sent comes out on top — except where a bulk copy set the created times ([bulkCopied]), which say nothing about
 * the files. Theaters: the one BMS is set to first, then by each theater's newest file.
 *
 * **Campaign starts** (R3-PLAN A7, [CampaignStarts]) are the files BMS's campaigns and TEs begin from — an objectives
 * part, no flights, or a start's name (`Save<n>`, `Te_New*`, `Instant`). They are left out of the listing unless
 * `all=1`, and then listed as starts ([CampFile.start], with the reason in [CampFile.stockWhy]) that the Planner does
 * not open, and never written by any route. Every other save opens and saves as it does in WDP, the TEs and trainings
 * that ship with BMS (`TE_BMS_*`, `TR_BMS_*`) included; those are only marked [CampFile.stock], so the list can fold
 * them away under *Show BMS's own missions*.
 *
 * **The ATO** ([ato]) is every package with its flights, the teams and which are on the player's side (the save's own
 * team table, [TeamRelations]), and the sentences the Planner shows about the file (R3-CAM §4.5).
 *
 * **The flight** ([flight]) is its route (each waypoint at its cell's middle), each seat's designated target, the
 * loadouts with the theater's weapon names, fuel and laser codes per aircraft, its bases as the app's airport ids,
 * the package's other flights, the tanker/AWACS/JSTARS/ECM tracks it is tied to, whether the mission file beside the
 * save is its route, and a [Briefing] made of it ([Briefing.origin] = "save", R3-PLAN A9): overview, steerpoint rows
 * worded the way BMS prints them (checked against the printed briefing by `--camtest` item 10), package and ordnance,
 * and the texts BMS's briefing scripts word out of the campaign — situation, station or target area, rules of
 * engagement, emergency procedures — with WDP's intelligence lists beside it ([CampaignBriefing]). What only a printed
 * briefing has — weather, comm ladder, the threats it picked along the route, TACANs, support — stays empty, and so
 * does a gun's round count: the save holds 51 where the briefing prints 510, which is not established.
 *
 * Read only: files are read whole with no lock kept, and nothing here opens a file for writing. Nothing throws: a
 * file that cannot be read carries its sentence ([CampFile.error], a note), a request that cannot be answered comes
 * back as [Answer.Refused] with the status the route answers.
 */
object CampaignFiles {
    /** How far a mission file's steerpoint may sit from the waypoint's cell middle and still be that waypoint (ft). */
    const val MATCH_FT = 2.0

    /** The steerpoints a cartridge (and a mission file) holds for the route: STPT 1-24. */
    const val MOST_STPTS = 24

    /** BMS works a leg's length out in grid kilometres and prints it over 1.852 (29.8 nm, where feet over 6,076 give 29.7). */
    private const val KM_PER_NM = 1.852

    // BMS's waypoint flags, as 4.38.1's saves carry them next to the printed briefing (Cyborg6's route in the round-3
    // fixture; the sample briefing's strike route): the leg repeats back to the previous point, the alternate field,
    // and whether the height change at this point is flown at once ("immediate") or at the next ("delayed").
    private const val WPF_REPEAT = 0x40
    private const val WPF_ALTERNATE = 0x400
    private const val WPF_ALT_IMMEDIATE = 0x100000

    // Strings.txt: the words the briefing's steerpoint table is made of
    private const val S_LAND_ALTERNATE = 237
    private const val S_HOLD = 244
    private const val S_RETURN = 247
    private const val S_DESCEND = 1600
    private const val S_CLIMB = 1601
    private const val S_DELAYED = 1602
    private const val S_IMMEDIATE = 1603
    private const val S_COMMENT = 1650

    /** BMS's action numbers: take-off and landing. */
    private const val ACT_TAKEOFF = 1
    private const val ACT_LAND = 7
    /** BMS's action numbers a support flight's station starts at: ELINT (AWACS, JSTARS) and tanker (WDP's `Tankers`, `Awacs`, `JSTAR`). */
    private const val ACT_ELINT = 20
    private const val ACT_TANKER = 24
    /** The missions WDP's DataCard lists as support ([sideSupport]): AWACS, JSTARS and tanker. */
    private val SIDE_SUPPORT = mapOf(26 to "AWACS", 27 to "JSTARS", 28 to "Tanker")

    const val NO_BMS = "No Falcon BMS folder was found on this PC. Set it in BMS Companion's settings on the PC."

    /** What a route answers: the value, or a refusal with its HTTP status and the sentence the page shows. */
    sealed class Answer<out T> {
        class Ok<T>(val value: T) : Answer<T>()
        class Refused(val status: Int, val sentence: String) : Answer<Nothing>()
    }

    /**
     * What a request is answered against: the theaters of the BMS folder, the theater BMS is set to (the registry's
     * `curTheater`), the printed briefing and its file time, and the time "now" for the settle rule.
     */
    class Context(
        val set: Theaters.TheaterSet?,
        val curTheater: String?,
        val briefing: Briefing?,
        val briefingTime: Long,
        val now: Long = System.currentTimeMillis(),
    ) {
        val key: CampBriefKey? by lazy { briefKey(briefing, briefingTime) }
    }

    /** The PC as it is: the BMS folder the settings or the registry name, and the briefing BMS last printed. */
    fun context(): Context {
        val b = runCatching { Bridge.currentBriefing() }.getOrNull()
        return Context(Theaters.of(Bridge.install), Bridge.install.theater, b, if (b == null) 0L else Bridge.briefingModified)
    }

    /**
     * Which flight the printed briefing is for: its callsign, package number and flight number — the package table's row
     * with the briefing's own callsign. The row BMS marks "x" is the package's **primary** flight, which is not always
     * the pilot's: in a support package it is the tanker (package 3474 of the last Korea save marks Texaco4 in a briefing
     * printed for Satan7), and taking it first matched no flight of the save. The primary row is only the fallback.
     */
    fun briefKey(b: Briefing?, printed: Long): CampBriefKey? {
        if (b == null || b.origin != null) return null
        val cs = b.overview.flight?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val pkg = b.overview.packageId?.trim()?.let { Regex("\\d+").find(it)?.value?.toIntOrNull() }
        val own = b.ownFlight(cs)
        val flt = own?.flightId?.trim()?.toIntOrNull()
        return CampBriefKey(cs, pkg, flt, printed)
    }

    private fun holds(key: CampBriefKey?, callsign: String?, pkg: Int?, flt: Int?): Boolean =
        key != null && callsign != null && callsign.equals(key.callsign, ignoreCase = true) &&
            (key.packageId == null || key.packageId == pkg) && (key.flightId == null || key.flightId == flt)

    // ================================================================ the listing

    /** What the listing keeps of one parsed file (cached by path, size and time). */
    private class Summary(
        val stamp: String,
        val version: Int?, val title: String?, val clock: Long?, val flights: Int?, val packages: Int?,
        val player: String?, val error: String?, val hasObj: Boolean,
        /** callsign, package number and flight number of every flight, for the briefed mark */
        val flightKeys: List<Triple<String, Int?, Int>>,
    )

    private val summaries = ConcurrentHashMap<String, Summary>()

    /** Every theater and the saves in its campaign folder; starts and templates only with [all]. */
    fun list(ctx: Context, all: Boolean): CampFiles {
        val set = ctx.set ?: return CampFiles(error = NO_BMS, briefing = ctx.key)
        if (set.all.isEmpty()) return CampFiles(error = set.error ?: "Falcon BMS's theater list names no theaters.", briefing = ctx.key)
        val cur = set.current(ctx.curTheater)
        val apps = appTheaters()
        val theaters = set.all.map { t -> theater(set, t, t === cur, all, ctx, apps) }
        val ordered = theaters.sortedWith(
            compareByDescending<CampTheater> { it.current }
                .thenByDescending { th -> th.files.maxOfOrNull { it.sortTime } ?: Long.MIN_VALUE }
                .thenBy { th -> set.all.indexOfFirst { it.name == th.name } },
        )
        return CampFiles(theaters = ordered, current = cur?.name, briefing = ctx.key)
    }

    private fun theater(set: Theaters.TheaterSet, t: Theaters.Theater, current: Boolean, all: Boolean, ctx: Context, apps: Set<String>): CampTheater {
        val app = t.appId.takeIf { it in apps || apps.isEmpty() }
        val dir = set.campaignDir(t)
            ?: return CampTheater(
                name = t.name, appTheater = app, folder = "Data\\" + (t.campaignDir ?: ""), current = current,
                error = if (t.campaignDir == null) "The theater definition of ${t.name} names no campaign folder."
                else "The campaign folder of ${t.name} (Data\\${t.campaignDir}) is not there.",
            )
        val names = CampaignArchive.names(set, t)
        val saves = set.saves(t).map { f -> f to attempt(null) { Files.readAttributes(f.toPath(), BasicFileAttributes::class.java) } }
        val bulk = bulkCopied(saves.mapNotNull { it.second?.creationTime()?.toMillis() })
        val files = saves.mapNotNull { (f, attrs) -> attempt(null) { fileRow(f, attrs, bulk, names, all, ctx) } }
            .sortedWith(compareByDescending<CampFile> { it.sortTime }.thenByDescending { it.modified }.thenBy { it.name.lowercase() })
        return CampTheater(name = t.name, appTheater = app, folder = relative(set.root, dir), current = current, files = files, error = names.error)
    }

    /**
     * The created times a bulk copy gave: [BULK_FILES] or more files of one folder created within [BULK_MS] of each
     * other (Falcon BMS copied to a new disk, a backup put back). Windows sets a copy's created time to the moment it
     * is copied, so after a bulk copy the created times say only in what order the files were copied, and the files
     * are ordered by when they were last written instead. A TE a friend sent, copied in on its own, keeps its created
     * time and comes out on top.
     */
    fun bulkCopied(created: List<Long>): Set<Long> {
        val sorted = created.sorted()
        val out = HashSet<Long>()
        var lo = 0
        for (hi in sorted.indices) {
            while (sorted[hi] - sorted[lo] > BULK_MS) lo++
            if (hi - lo + 1 >= BULK_FILES) for (i in lo..hi) out += sorted[i]
        }
        return out
    }

    /** A bulk copy: this many files of one folder ... */
    const val BULK_FILES = 5

    /** ... created within this long of each other. */
    const val BULK_MS = 120_000L

    /** The time a file is ordered by: the later of created and modified, unless its created time is a bulk copy's. */
    fun sortTime(created: Long, modified: Long, bulk: Set<Long>): Long = if (created in bulk) modified else maxOf(created, modified)

    private fun fileRow(f: File, attrs: BasicFileAttributes?, bulk: Set<Long>, names: CampaignArchive.Names, all: Boolean, ctx: Context): CampFile? {
        val modified = attrs?.lastModifiedTime()?.toMillis() ?: attempt(0L) { f.lastModified() }
        val created = attrs?.creationTime()?.toMillis() ?: modified
        val size = attrs?.size() ?: attempt(0L) { f.length() }
        if (ctx.now - modified in 0 until Theaters.SETTLE_MS) return null   // BMS may still be writing it
        val kind = CampaignArchive.kindOfName(f.name)
        val base = f.nameWithoutExtension
        val row = CampFile(name = f.name, kind = kind, modified = modified, created = created, sortTime = sortTime(created, modified, bulk), size = size)
        // the directory alone says whether it is a start: a start is left out without reading the rest
        val dir = CampaignArchive.directoryOf(f)
        if (dir.error != null) return row.copy(error = "${f.name} is not a save BMS can read: ${dir.error}")
        val hasObj = dir.has("obj")
        if (hasObj && !all) return null
        val s = summary(f, names, size, modified)
        // a start is refused by Open mission (greyed, this reason on tap); nothing else is
        val why = stockWhy(base, hasObj, if (s.error == null) s.flights else null)
        val start = why != null
        if (start && !all) return null
        val key = ctx.key
        val briefed = key != null && s.flightKeys.any { (cs, pkg, flt) -> holds(key, cs, pkg, flt) }
        return row.copy(
            start = start, stock = start || SHIPPED_MISSION.matches(base.trim()), stockWhy = why, title = s.title, clock = s.clock, flights = s.flights,
            packages = s.packages, player = s.player, briefed = briefed, version = s.version, error = s.error,
        )
    }

    private fun summary(f: File, names: CampaignArchive.Names, size: Long, modified: Long): Summary {
        val stamp = "$size|$modified|${names.key}"
        val path = f.absolutePath.lowercase()
        summaries[path]?.let { if (it.stamp == stamp) return it }
        val save = CampaignArchive.read(f, names)
        val keys = ArrayList<Triple<String, Int?, Int>>()
        var player: String? = null
        for (fl in save.flights) {
            val cs = names.callsign(fl)
            if (player == null && fl.hasPlayer) player = cs
            if (cs != null) keys += Triple(cs, save.packageOf(fl)?.campId, fl.campId)
        }
        val s = Summary(
            stamp = stamp, version = save.version, title = save.header?.title, clock = save.header?.currentTime,
            flights = if (save.uni != null) save.flights.size else null, packages = if (save.uni != null) save.packages.size else null,
            player = player, error = save.error, hasObj = save.hasObj, flightKeys = keys,
        )
        summaries[path] = s
        return s
    }

    /** The missions and trainings that ship with BMS: opened and saved like any other, only folded away in the list. */
    private val SHIPPED_MISSION = Regex("(TE|TR)_BMS_.*", RegexOption.IGNORE_CASE)

    /**
     * Why a file is a campaign start that Open mission does not open, in a sentence, or null for every other save.
     * [flights] is null when the file could not be read.
     */
    fun stockWhy(base: String, hasObj: Boolean, flights: Int?): String? {
        // one rule with the TE save's ([CampaignStarts], CartridgeStore.kt), so what the list refuses to open and what
        // Save to DTC never writes can never disagree: a start's name, an objectives part, or no flights
        val startName = CampaignStarts.byName("$base.tac") != null
        if (!startName && !hasObj && flights != 0) return null
        val n = base.lowercase()
        return when {
            hasObj && Regex("save\\d+").matches(n) ->
                "$base is a campaign start: it has no flights until the campaign has run. Start it in Falcon BMS and save, or open Auto Save."
            hasObj && n.startsWith("te_new") ->
                "$base is a TE template: it has no flights until you build a TE on it in Falcon BMS and save it under your own name."
            hasObj && n == "instant" -> "$base is Falcon BMS's Instant Action start: it has no flights to plan."
            hasObj -> "$base is a campaign start or template: it has no flights until Falcon BMS has run it and it has been saved."
            flights == 0 -> "$base has no flights: there is nothing in it to plan."
            else -> CampaignStarts.byName("$base.tac")?.let { "$it: the Planner does not open campaign starts. Start it in Falcon BMS and save, then open your save." }
        }
    }

    // ================================================================ one file

    private class Target(val set: Theaters.TheaterSet, val t: Theaters.Theater, val file: File, val cur: Theaters.Theater?)

    /** The theater and file a request names, checked against the listing: never a path from the client. */
    private fun resolve(ctx: Context, theater: String?, file: String?): Answer<Target> {
        val set = ctx.set ?: return Answer.Refused(409, NO_BMS)
        val want = theater?.trim()
        if (want.isNullOrEmpty()) return Answer.Refused(400, "Say which theater: theater=<a name from /api/campaign/files>.")
        val t = set.all.firstOrNull { it.name.equals(want, ignoreCase = true) } ?: set.byName(want)
            ?: return Answer.Refused(400, "There is no theater called \"$want\" in Falcon BMS's theater list.")
        val name = file?.trim()
        if (name.isNullOrEmpty()) return Answer.Refused(400, "Say which file: file=<a name from /api/campaign/files>.")
        if (name.contains('\\') || name.contains('/') || name.contains(':') || name.contains(".."))
            return Answer.Refused(400, "A file name only, without a folder: \"$name\" is not one.")
        set.campaignDir(t) ?: return Answer.Refused(409, "The campaign folder of ${t.name} (Data\\${t.campaignDir ?: "?"}) is not there.")
        val f = set.saves(t).firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: return Answer.Refused(400, "There is no save called \"$name\" in the campaign folder of ${t.name}.")
        if (ctx.now - attempt(0L) { f.lastModified() } in 0 until Theaters.SETTLE_MS)
            return Answer.Refused(409, "Falcon BMS is still writing ${f.name}; try again in a moment.")
        return Answer.Ok(Target(set, t, f, set.current(ctx.curTheater)))
    }

    /** One save's whole air tasking order. */
    fun ato(ctx: Context, theater: String?, file: String?): Answer<CampAto> {
        val target = when (val r = resolve(ctx, theater, file)) { is Answer.Refused -> return r; is Answer.Ok -> r.value }
        return attempt<Answer<CampAto>>(Answer.Refused(409, "Could not read ${target.file.name}.")) { atoOf(ctx, target) }
    }

    /**
     * WDP's ATO Target List of a save ([CampaignAtoTargets]): the targets the flights of [team]'s side are tasked to
     * attack; [team] is a team number, 1-7 (the team of the flight the Planner has open), else the pilot's own team
     * as the flight picker takes it (the briefed flight's, a player flight's, the player squadron's).
     */
    fun atoTargets(ctx: Context, theater: String?, file: String?, team: String?): Answer<CampAtoTargets> {
        val target = when (val r = resolve(ctx, theater, file)) { is Answer.Refused -> return r; is Answer.Ok -> r.value }
        val want = team?.trim()?.takeIf { it.isNotEmpty() }
        val side = want?.let { it.toIntOrNull()?.takeIf { n -> n in 1..7 } ?: return Answer.Refused(400, "team is a team number, 1 to 7: \"$it\" is not one.") }
        return attempt<Answer<CampAtoTargets>>(Answer.Refused(409, "Could not read ${target.file.name}.")) { atoTargetsOf(ctx, target, side) }
    }

    private fun atoTargetsOf(ctx: Context, g: Target, team: Int?): Answer<CampAtoTargets> {
        val names = CampaignArchive.names(g.set, g.t)
        val save = CampaignArchive.cached(g.file, names)
        if (save.uni == null) return Answer.Refused(409, save.error ?: "Could not read the flights of ${g.file.name}.")
        val objs = CampaignArchive.startFile(save)?.let { CampaignArchive.objectives(it) }
        val side = team ?: ownTeam(save, names, ctx.key)
        return Answer.Ok(CampaignAtoTargets.of(save, names, objs, side, g.t.name, g.file.name))
    }

    private fun atoOf(ctx: Context, g: Target): Answer<CampAto> {
        val names = CampaignArchive.names(g.set, g.t)
        val save = CampaignArchive.cached(g.file, names)
        val h = save.header
        if (h == null && save.uni == null) return Answer.Refused(409, save.error ?: "Could not read ${g.file.name}.")
        val objs = CampaignArchive.startFile(save)?.let { CampaignArchive.objectives(it) }
        val key = ctx.key
        val own = ownTeam(save, names, key)
        val rel = attempt(emptyList()) { TeamRelations.read(g.file) }
        fun allied(n: Int): Boolean {
            if (own == null) return false
            if (n == own) return true
            val s = rel.firstOrNull { it.number == own }?.stance?.getOrNull(n)
            return s == TeamRelations.ALLIED || s == TeamRelations.FRIENDLY
        }
        val used = (save.flights.map { it.owner } + save.packages.map { it.owner }).toSet()
        val teams = (h?.teams.orEmpty().map { it.n to it.name.trim() } + used.filter { u -> h?.teams?.none { it.n == u } != false }.map { it to "" })
            .filter { (n, name) -> name.isNotEmpty() || n in used }
            .distinctBy { it.first }
            .map { (n, name) -> CampTeam(n, name.ifEmpty { "Team $n" }, allied(n)) }
            .sortedWith(compareByDescending<CampTeam> { it.n == own }.thenByDescending { it.allied }.thenBy { it.n })
        val packages = save.packages.mapNotNull { p ->
            val flights = save.flightsOf(p)
            if (flights.isEmpty()) return@mapNotNull null
            val rows = flights.map { row(save, names, it, key, objs) }
            fun sup(id: CampaignArchive.VuId?) = save.flight(id)?.id?.toString()
            CampPackage(
                id = p.id.toString(), number = p.campId, owner = p.owner,
                mission = names.mission(p.request.mission) ?: flights.firstNotNullOfOrNull { names.mission(it.mission) },
                takeoff = p.takeoff?.takeIf { it > 0 } ?: rows.map { it.takeoff }.filter { it > 0 }.minOrNull(),
                target = placeName(save, objs, p.request.target) ?: rows.firstNotNullOfOrNull { it.target },
                flights = rows, tanker = sup(p.tanker), awacs = sup(p.awacs), jstars = sup(p.jstars), ecm = sup(p.ecm),
            )
        }.sortedWith(compareBy<CampPackage> { it.takeoff ?: Long.MAX_VALUE }.thenBy { it.number })
        val notes = fileNotes(ctx, g, save, names)
        return Answer.Ok(
            CampAto(
                theater = g.t.name, file = g.file.name, modified = save.modified, clock = h?.currentTime ?: 0L,
                version = save.version ?: 0, title = h?.title, teams = teams, packages = packages, notes = notes,
            ),
        )
    }

    /** The team the pilot is on in this save: the briefed flight's, else a player flight's, else the player squadron's. */
    private fun ownTeam(save: CampaignArchive.Save, names: CampaignArchive.Names, key: CampBriefKey?): Int? {
        save.flights.firstOrNull { holds(key, names.callsign(it), save.packageOf(it)?.campId, it.campId) }?.let { return it.owner }
        save.playerFlights.firstOrNull()?.let { return it.owner }
        save.unit(save.header?.playerSquadron)?.let { return it.owner }
        return null
    }

    /** One flight as a line of the picker. */
    private fun row(save: CampaignArchive.Save, names: CampaignArchive.Names, f: CampaignArchive.Flight, key: CampBriefKey?, objs: CampaignArchive.ObjWalk?): CampFlightRow {
        val cs = names.callsign(f) ?: "Flight ${f.campId}"
        val sq = save.header?.squadron(f.squadronId)
        val home = save.homeOf(f)
        val aircraft = names.aircraft(f.type) ?: names.unitClass(f.type) ?: "?"
        return CampFlightRow(
            id = f.id.toString(), number = f.campId, callsign = cs,
            mission = names.mission(f.mission) ?: "Mission ${f.mission}", task = names.task(f.mission),
            aircraft = aircraft, count = f.aircraftCount,
            squadron = sq?.name?.takeIf { it.isNotBlank() } ?: (home?.takeIf { it !is CampaignArchive.Squadron }?.let { names.unitClass(it.type) }),
            base = sq?.airbase?.takeIf { it.isNotBlank() } ?: placeName(save, objs, (home as? CampaignArchive.Squadron)?.airbase)
                ?: f.waypoints.firstOrNull { it.action == ACT_TAKEOFF }?.let { placeName(save, objs, it.target) },
            team = f.owner, takeoff = takeoffMs(f), tot = f.tot, player = f.hasPlayer,
            briefed = holds(key, cs, save.packageOf(f)?.campId, f.campId),
            f16 = aircraft.contains("F-16", ignoreCase = true),
            target = f.waypoints.firstOrNull { it.target != null && !it.target.isNone && it.action != ACT_TAKEOFF && it.action != ACT_LAND }
                ?.let { placeName(save, objs, it.target, it.building) },
        )
    }

    /** When a flight takes off: its take-off waypoint's departure (else its first waypoint's). */
    fun takeoffMs(f: CampaignArchive.Flight): Long =
        (f.waypoints.firstOrNull { it.action == ACT_TAKEOFF } ?: f.waypoints.firstOrNull())?.let { if (it.depart > 0) it.depart else it.arrive } ?: 0L

    private fun placeName(save: CampaignArchive.Save, objs: CampaignArchive.ObjWalk?, id: CampaignArchive.VuId?, building: Int? = null): String? =
        CampaignArchive.place(save, objs, id, building)?.name?.trim()?.takeIf { it.isNotEmpty() }

    /** One flight of a save, with everything the Planner uses of it and a [Briefing] made of it. */
    fun flight(ctx: Context, theater: String?, file: String?, flight: String?): Answer<CampFlight> {
        val target = when (val r = resolve(ctx, theater, file)) { is Answer.Refused -> return r; is Answer.Ok -> r.value }
        val id = CampaignArchive.VuId.parse(flight)
            ?: return Answer.Refused(400, "Say which flight: flight=<num/creator>, the id /api/campaign/ato gave (\"${flight ?: ""}\" is not one).")
        return attempt<Answer<CampFlight>>(Answer.Refused(409, "Could not read ${target.file.name}.")) { flightOf(ctx, target, id) }
    }

    /** A save's objectives, for the DataCard's target list (WDP's Target Selection; [CampaignTargets.objectives]). */
    fun objectives(ctx: Context, theater: String?, file: String?): Answer<CampObjectives> {
        val g = when (val r = resolve(ctx, theater, file)) { is Answer.Refused -> return r; is Answer.Ok -> r.value }
        return attempt<Answer<CampObjectives>>(Answer.Refused(409, "Could not read the objectives of ${g.file.name}.")) {
            Answer.Ok(CampaignTargets.objectives(g.set, g.t, g.file))
        }
    }

    /** One objective's buildings ([CampaignTargets.features]); [objective] is the "num/creator" /objectives gave. */
    fun features(ctx: Context, theater: String?, file: String?, objective: String?): Answer<CampFeatures> {
        val g = when (val r = resolve(ctx, theater, file)) { is Answer.Refused -> return r; is Answer.Ok -> r.value }
        return attempt<Answer<CampFeatures>>(Answer.Refused(409, "Could not read the buildings of ${g.file.name}.")) {
            CampaignTargets.features(g.set, g.t, g.file, objective)?.let { Answer.Ok(it) }
                ?: Answer.Refused(400, "There is no objective ${objective ?: ""} in the start file of ${g.file.name}.")
        }
    }

    private fun flightOf(ctx: Context, g: Target, id: CampaignArchive.VuId): Answer<CampFlight> {
        val names = CampaignArchive.names(g.set, g.t)
        val save = CampaignArchive.cached(g.file, names)
        if (save.uni == null) return Answer.Refused(409, save.error ?: "Could not read the flights of ${g.file.name}.")
        val f = save.flight(id) ?: return Answer.Refused(400, "There is no flight $id in ${g.file.name}.")
        val objs = CampaignArchive.startFile(save)?.let { CampaignArchive.objectives(it) }
        val airports = airports(g.t.appId)
        val key = ctx.key
        val pkg = save.packageOf(f)
        fun place(pid: CampaignArchive.VuId?, building: Int? = null): CampPlace? =
            CampaignArchive.place(save, objs, pid, building)?.let { campPlace(it, airports) }

        val route = f.waypoints.mapIndexed { i, w ->
            CampWaypoint(
                n = i + 1, x = w.north, y = w.east, altFt = w.altFt, arriveMs = w.arrive, departMs = w.depart,
                action = w.action, desc = names.action(w.action)?.takeUnless { it == "--" }, routeAction = w.routeAction,
                formation = w.formation and 0xF, spacing = (w.formation shr 4) and 0xF,
                target = w.target?.takeUnless { it.isNone }?.let { place(it, w.building) },
                designated = w.designated?.map { d -> if (d.id.isNone) null else place(d.id, d.building) }.orEmpty(),
            )
        }
        val seats = f.planeStats.indices.filter { f.planeStats[it] != 0 }.ifEmpty { listOf(0) }
        val loadouts = f.loadouts.map { l -> CampLoadout(l.stores.map { (wid, n) -> store(names, wid, n) }) }
        val takeoffWp = f.waypoints.firstOrNull { it.action == ACT_TAKEOFF }
        val (landingWp, alternateWp) = landings(f)
        val home = takeoffWp?.target?.let { place(it) }
            ?: (save.homeOf(f) as? CampaignArchive.Squadron)?.airbase?.let { place(it) }
        val landing = landingWp?.target?.let { place(it) }
        val alternate = alternateWp?.target?.let { place(it) }
        val others = pkg?.let { p -> save.flightsOf(p).filter { it !== f } }.orEmpty().map { row(save, names, it, key, objs) }
        // the other flights' routes, for WDP's Coordination Card (FillPackages / FillCommCardPackages): times, altitudes and
        // actions, each waypoint at its cell's middle as [route] is; no targets (the card never names them)
        val packageRoutes = pkg?.let { p -> save.flightsOf(p).filter { it !== f } }.orEmpty().map { g ->
            CampPackageRoute(g.id.toString(), g.waypoints.mapIndexed { i, w ->
                CampWaypoint(
                    n = i + 1, x = w.north, y = w.east, altFt = w.altFt, arriveMs = w.arrive, departMs = w.depart,
                    action = w.action, desc = names.action(w.action)?.takeUnless { it == "--" }, routeAction = w.routeAction,
                    formation = w.formation and 0xF, spacing = (w.formation shr 4) and 0xF,
                )
            })
        }
        val support = pkg?.let { p ->
            listOf("Tanker" to p.tanker, "AWACS" to p.awacs, "JSTARS" to p.jstars, "ECM" to p.ecm).mapNotNull { (role, sid) ->
                val s = save.flight(sid) ?: return@mapNotNull null
                supportOf(role, names, s)
            }
        }.orEmpty()
        val sideSupport = attempt(emptyList<CampSupport>()) { sideSupport(save, names, f) }
        val ini = iniCheck(g.file, f, save.modified)
        val texts = CampaignBriefing.of(save, names, f, pkg, objs, alternateWp)
        val (airDefences, ships) = attempt(emptyList<CampSite>() to emptyList<CampSite>()) { CampaignBriefing.sites(save, names, f) }
        val bull = attempt(null) { MissionDtcFile.bullseye(save.header) }
        val briefing = briefingOf(save, names, f, pkg, objs, alternate, texts)
        val notes = fileNotes(ctx, g, save, names, flight = f, ini = ini)
        return Answer.Ok(
            CampFlight(
                row = row(save, names, f, key, objs), packageNumber = pkg?.campId ?: 0, route = route, loadouts = loadouts,
                fuelLb = seats.mapNotNull { f.fuelInitial.getOrNull(it) }, laser = seats.mapNotNull { f.laser.getOrNull(it) },
                home = home, landing = landing, alternate = alternate, packageFlights = others, support = support,
                missionIni = ini, briefing = briefing, notes = notes, kind = save.kind, intel = texts.intel,
                // only what the flight's side has spotted ever leaves the PC: no cheating (MissionPicture)
                airDefences = airDefences.filter { it.spotted }, ships = ships.filter { it.spotted }, bullseyeX = bull?.first, bullseyeY = bull?.second,
                clock = save.header?.currentTime, currentWp = f.core.currentWp,
                departures = attempt<CampDepartures?>(null) { departures(save, names, objs, f) },
                packageRoutes = packageRoutes,
                // WDP's Bullseye(): the header's name 1 is "Bullseye", any other "Rose"
                bullseyeName = bull?.let { if (save.header?.bullseyeName == 1) "Bullseye" else "Rose" },
                sideSupport = sideSupport,
                fuelBurnt = f.fuelBurnt,
            ),
        )
    }

    /**
     * WDP's support tables for [f]'s DataCard (`fclsMain.Tankers`, `Awacs`, `JSTAR`; [CampFlight.sideSupport]): every
     * flight of the save on a tanker (mission 28), AWACS (26) or JSTARS (27) mission that is on [f]'s side, in the
     * save's order. On [f]'s side is WDP's `CheckOwnSideFlt`: the team that controls [f]'s team (Korea: ROK controls the
     * U.S. team) is allied or friendly toward the flight's team — or it is [f]'s own team, which WDP's test would drop
     * where a team is neutral toward itself (LHTO).
     */
    private fun sideSupport(save: CampaignArchive.Save, names: CampaignArchive.Names, f: CampaignArchive.Flight): List<CampSupport> {
        val teams = save.teams
        val ctrl = teams?.firstOrNull { it.n == f.owner }?.controller ?: f.owner
        val stance = teams?.firstOrNull { it.n == ctrl }?.stance
        fun ours(owner: Int) = owner == f.owner || stance?.getOrNull(owner).let { it == TeamRelations.ALLIED || it == TeamRelations.FRIENDLY }
        return save.flights.mapNotNull { s ->
            val role = SIDE_SUPPORT[s.mission] ?: return@mapNotNull null
            if (s === f || !ours(s.owner)) null else supportOf(role, names, s)
        }
    }

    /**
     * A support flight as the Planner shows it: its callsign, route (the station marked as [track] marks it), vehicle,
     * the TACAN the save gives it (from save version 108; the tanker's own channel, as WDP reads `TacanChannel[0]` and
     * `TacanBand[0]`) and WDP's station leg: the first waypoint of the tanker action (24) for a tanker, of ELINT (20)
     * for the others (`Tankers`, `Awacs`, `JSTAR`).
     */
    private fun supportOf(role: String, names: CampaignArchive.Names, s: CampaignArchive.Flight): CampSupport {
        val ch = s.tacanChannel.getOrNull(0)?.takeIf { it > 0 }
        val band = s.tacanBand.getOrNull(0)?.takeIf { it in 'A'.code..'Z'.code }?.toChar() ?: 'Y'
        val act = if (role == "Tanker") ACT_TANKER else ACT_ELINT
        return CampSupport(
            role, names.callsign(s) ?: "Flight ${s.campId}", track(s),
            aircraft = names.aircraft(s.type) ?: names.unitClass(s.type),
            tacan = ch?.let { "$it$band" },
            leg = s.waypoints.indexOfFirst { it.action == act },
        )
    }

    /**
     * WDP's Airport Schedule for [f] (`fclsAptSchedule.CreateAptSchedule`, [CampDepartures]): the flights of the whole
     * save whose first waypoint is tasked against the same place as [f]'s — WDP compares the ids' numbers
     * (`GetObjNr(TargetID.num_)`) — the first 100 of them in the save's order, those with a departure time, sorted by
     * it (a stable sort: WDP picks the first of equal times each round). WDP prints the last flight of the save's
     * table as an empty line (its loop stops one short); the app prints it. Null when [f]'s first waypoint names no
     * place.
     */
    fun departures(save: CampaignArchive.Save, names: CampaignArchive.Names, objs: CampaignArchive.ObjWalk?, f: CampaignArchive.Flight): CampDepartures? {
        val home = f.waypoints.firstOrNull()?.target?.takeUnless { it.isNone } ?: return null
        val matched = save.flights.filter { g -> g.waypoints.firstOrNull()?.target?.let { !it.isNone && it.num == home.num } == true }.take(100)
        val rows = matched.filter { it.waypoints[0].depart != 0L }.sortedBy { it.waypoints[0].depart }.map { g ->
            val sq = save.squadronOf(g)
            CampDeparture(
                depart = g.waypoints[0].depart,
                aircraft = names.aircraft(g.type) ?: names.unitClass(g.type) ?: "",
                callsign = names.callsign(g) ?: "",
                squadron = sq?.core?.nameId?.let { "$it" + CampaignArchive.ordinal(names, it) } ?: "",
                packageNumber = save.packageOf(g)?.campId,
                mission = names.mission(g.mission) ?: "",
                own = g === f,
            )
        }
        // the field's name as WDP titles it: the objective's (or the carrier's), else the squadron's airbase
        val name = CampaignArchive.place(save, objs, home)?.name?.trim()?.takeIf { it.isNotEmpty() }
            ?: save.header?.squadron(f.squadronId)?.airbase?.trim()?.takeIf { it.isNotEmpty() }
            ?: ""
        val field = name.replace("Airbase", "").replace("Highwaystrip", "").replace("Airstrip", "").trim()
        return CampDepartures(field = field, rows = rows)
    }

    private fun campPlace(p: CampaignArchive.Place, airports: Map<Int, Airport>): CampPlace {
        val kind = when {
            p.kind == "unit" -> "unit"
            p.building != null -> "feature"
            p.campId != null && airports.containsKey(p.campId) -> "airbase"
            else -> "objective"
        }
        return CampPlace(kind = kind, campId = p.campId, name = p.name?.trim()?.takeIf { it.isNotEmpty() }, building = p.building, x = p.north, y = p.east)
    }

    /**
     * One store. A gun is listed with no round count (1: the gun itself): the save holds 51 for an F-16's M61 where
     * the briefing prints 510, and why is not established.
     */
    private fun store(names: CampaignArchive.Names, id: Int, n: Int): Store {
        val name = names.weapon(id) ?: "#$id"
        return Store(if (isGun(name)) 1 else n, name)
    }

    private fun isGun(name: String) = Regex("^\\d+(\\.\\d+)?\\s*mm\\b", RegexOption.IGNORE_CASE).containsMatchIn(name.trim())

    /** A support flight's route, the leg it holds on marked the way [Bridge.supportTracks] marks it. */
    private fun track(s: CampaignArchive.Flight): List<TrackPoint> {
        val w = s.waypoints
        if (w.isEmpty()) return emptyList()
        val longest = w.indices.maxByOrNull { w[it].depart - w[it].arrive } ?: 0
        val holds = w[longest].depart > w[longest].arrive
        val legs = setOf(longest, (longest - 1).coerceAtLeast(0))
        return w.mapIndexed { i, p ->
            TrackPoint(x = p.north, y = p.east, altFt = p.altFt, station = holds && i in legs, arriveMs = p.arrive, departMs = p.depart)
        }
    }

    // ================================================================ the mission file beside the save

    /**
     * Whether `<save>.ini` beside the save is [f]'s route: every one of its steerpoints 1-24 that the route has within
     * [MATCH_FT] of the waypoint's cell middle. BMS rewrites that file under whatever save is loaded, so a file beside
     * a save can hold another flight's route, or one WDP zeroed.
     */
    fun iniCheck(saveFile: File, f: CampaignArchive.Flight, saveModified: Long): CampIniCheck {
        val want = saveFile.nameWithoutExtension + ".ini"
        val ini = attempt(null) { saveFile.absoluteFile.parentFile?.listFiles()?.firstOrNull { it.isFile && it.name.equals(want, ignoreCase = true) } }
            ?: return CampIniCheck(file = want, matches = false, reason = "There is no mission file ($want) beside this save.")
        val modified = attempt(0L) { ini.lastModified() }
        val points = attempt(null) { iniTargets(ini) }
            ?: return CampIniCheck(file = ini.name, modified = modified, matches = false, reason = "The mission file ${ini.name} could not be read.")
        val n = minOf(f.waypoints.size, MOST_STPTS)
        val route = (0 until n).map { points[it] }
        val when_ = "${ini.name}, ${day(modified)}"
        if (route.all { it == null || (it.first == 0.0 && it.second == 0.0) })
            return CampIniCheck(ini.name, modified, false, "The mission file beside this save ($when_) holds no route.")
        val moved = (0 until n).filterNot { i ->
            val p = points[i]
            p != null && abs(p.first - f.waypoints[i].north) <= MATCH_FT && abs(p.second - f.waypoints[i].east) <= MATCH_FT
        }
        val hits = n - moved.size
        return when {
            moved.isEmpty() -> CampIniCheck(ini.name, modified, true, null)
            // most of it is this route: the rest was moved in BMS's DTC, by WDP, or by the mission's author
            hits * 2 > n && hits >= 2 -> CampIniCheck(
                ini.name, modified, false,
                "The mission file beside this save ($when_) is this flight's route with ${moved.size} of $n steerpoints moved " +
                    "(STPT ${moved.joinToString { (it + 1).toString() }}): edited in Falcon BMS's DTC, by WDP or by the mission's author.",
            )
            else -> CampIniCheck(ini.name, modified, false, "The mission file beside this save ($when_) is for another flight; its steerpoints are not used.")
        }
    }

    /** `[STPT] target_n=x, y, z, action, name` of a mission file: n → (north, east). */
    fun iniTargets(ini: File): Map<Int, Pair<Double, Double>> {
        val out = HashMap<Int, Pair<Double, Double>>()
        var inStpt = false
        for (raw in ini.readLines(Charsets.ISO_8859_1)) {
            val line = raw.trim()
            if (line.startsWith("[")) { inStpt = line.equals("[STPT]", ignoreCase = true); continue }
            if (!inStpt) continue
            val m = Regex("^target_(\\d+)\\s*=\\s*([^,]+),\\s*([^,]+)", RegexOption.IGNORE_CASE).find(line) ?: continue
            val n = m.groupValues[1].toIntOrNull() ?: continue
            val x = m.groupValues[2].trim().toDoubleOrNull() ?: continue
            val y = m.groupValues[3].trim().toDoubleOrNull() ?: continue
            if (x.isFinite() && y.isFinite()) out[n] = x to y
        }
        return out
    }

    // ================================================================ the save as a briefing

    /**
     * [f] made into a [Briefing], worded the way BMS prints its steerpoint table: the action word (Strings 350 +
     * action), the time of arrival (a take-off's departure), distance and true heading from the previous point for a
     * timed point, altitude in thousands of feet ("21.0M"), the height change at this point ("Climb immediate",
     * "Descend delayed": Strings 1600-1603), the formation, and the comment (Strings 1650 + action; a hold's
     * "Hold  (Departure: …)" or, on a repeating leg, "Return to previous steerpoint (Departure: …)"; the alternate's
     * "Land (Alternate)"). CAS is left out: the save has no speed, and the briefing's is not the leg's distance over
     * its time. The situation, the station or target area, the Pkg-Mission line, the rules of engagement and the
     * emergency procedures are [texts] ([CampaignBriefing]; made here when not given).
     */
    fun briefingOf(
        save: CampaignArchive.Save, names: CampaignArchive.Names, f: CampaignArchive.Flight, pkg: CampaignArchive.Package?,
        objs: CampaignArchive.ObjWalk?, alternate: CampPlace?, texts: CampaignBriefing.Texts? = null,
    ): Briefing {
        val words = texts ?: CampaignBriefing.of(save, names, f, pkg, objs, landings(f).second)
        val cs = names.callsign(f) ?: "Flight ${f.campId}"
        val s = names.strings
        fun str(id: Int, fallback: String) = s[id] ?: fallback
        val w = f.waypoints
        val rows = w.mapIndexed { i, p ->
            val prev = w.getOrNull(i - 1)
            val next = w.getOrNull(i + 1)
            val timed = p.arrive > 0
            val legOk = timed && prev != null
            val dN = if (prev != null) p.north - prev.north else 0.0
            val dE = if (prev != null) p.east - prev.east else 0.0
            val dist = if (legOk) String.format(Locale.ROOT, "%.1f", hypot(dN, dE) / CampaignArchive.FEET_PER_CELL / KM_PER_NM) else null
            val heading = if (legOk) {
                val h = Math.toDegrees(atan2(dE, dN)).let { if (it < 0) it + 360 else it }.roundToInt()
                (if (h == 0) 360 else h).toString()
            } else null
            val alt = if (p.altFt > 0) String.format(Locale.ROOT, "%.1fM", p.altFt / 1000.0) else null
            val change = if (p.action == ACT_LAND || next == null || next.altFt == p.altFt) null else {
                val dir = if (next.altFt > p.altFt) str(S_CLIMB, "Climb ") else str(S_DESCEND, "Descend ")
                val how = if (p.flags and WPF_ALT_IMMEDIATE.toLong() != 0L) str(S_IMMEDIATE, "immediate") else str(S_DELAYED, "delayed")
                dir.trim() + " " + how.trim()
            }
            val comment = when {
                p.depart > p.arrive && p.arrive > 0 ->
                    // BMS's own string 244 is "Hold " with a space the string table reader trims, hence "Hold  (Departure"
                    (if (p.flags and WPF_REPEAT.toLong() != 0L) str(S_RETURN, "Return to previous steerpoint") else str(S_HOLD, "Hold").trimEnd() + " ") +
                        " (Departure: ${hms(p.depart)}z)"
                p.action == ACT_LAND && p.flags and WPF_ALTERNATE.toLong() != 0L -> str(S_LAND_ALTERNATE, "Land (Alternate)")
                // a Nav point's comment is its route action's, as its word is ("Engage enemy air defenses")
                else -> s[S_COMMENT + (if (p.action == 0 && p.routeAction > 0) p.routeAction else p.action)]
            }?.takeUnless { it.trim() == "--" }
            BriefSteerpoint(
                // a Nav point prints its route action, as BMS's briefing does (DtcRoute.printedWord)
                n = i + 1, desc = names.action(if (p.action == 0 && p.routeAction > 0) p.routeAction else p.action)?.takeUnless { it == "--" },
                time = if (timed) "${hms(p.arrive)}z" else null,
                dist = dist, heading = heading, cas = null, alt = alt, action = change,
                formation = formationName(p.formation and 0xF), comments = comment,
            )
        }
        val flights = pkg?.let { save.flightsOf(it) }.orEmpty().ifEmpty { listOf(f) }
        val elements = flights.map { e ->
            val ecs = names.callsign(e) ?: "Flight ${e.campId}"
            PackageFlight(
                callsign = ecs, flightId = e.campId.toString(), primary = e === f, role = names.mission(e.mission),
                aircraft = names.aircraft(e.type) ?: names.unitClass(e.type), count = e.aircraftCount, task = names.task(e.mission),
                takeoff = takeoffMs(e).takeIf { it > 0 }?.let { "${hms(it)}z" },
                push = e.waypoints.firstOrNull { it.action == 2 && it.arrive > 0 }?.let { "${hms(it.arrive)}z" },
                target = e.tot.takeIf { it > 0 }?.let { "${hms(it)}z" },
            )
        }
        val seats = f.planeStats.indices.filter { f.planeStats[it] != 0 }.ifEmpty { listOf(0) }
        val ordnance = if (f.loadouts.isEmpty()) emptyList() else listOf(
            OrdnanceFlight(
                flight = cs,
                aircraft = seats.map { seat ->
                    val l = if (f.loadouts.size == 1) f.loadouts[0] else f.loadouts.getOrElse(seat) { f.loadouts[0] }
                    val sum = LinkedHashMap<Int, Int>()
                    l.stores.forEach { (wid, n) -> sum[wid] = (sum[wid] ?: 0) + n }
                    OrdnanceAircraft("$cs${seat + 1}", sum.map { (wid, n) -> store(names, wid, n) })
                },
            ),
        )
        return Briefing(
            overview = BriefOverview(
                flight = cs, mission = names.mission(f.mission), packageId = pkg?.campId?.toString(),
                packageMission = words.packageMission ?: names.task(f.mission),
                // where the printed briefing puts it ("4 nm south of Tirana"), else what the package is against
                targetArea = words.targetArea ?: pkg?.request?.target?.takeUnless { it.isNone }?.let { placeName(save, objs, it) },
                tot = f.tot.takeIf { it > 0 }?.let { "${hms(it)}z" },
            ),
            situation = words.situation,
            `package` = elements,
            steerpoints = rows,
            ordnance = ordnance,
            roe = words.roe,
            emergency = words.emergency,
            alternate = alternate?.name,
            sections = listOfNotNull(words.overview),
            origin = "save",
        )
    }

    /**
     * A flight's landing and alternate waypoints: the first landing not flagged alternate (else the first landing), and
     * the landing flagged alternate (else any other landing).
     */
    fun landings(f: CampaignArchive.Flight): Pair<CampaignArchive.Waypoint?, CampaignArchive.Waypoint?> {
        val lands = f.waypoints.filter { it.action == ACT_LAND }
        val landing = lands.firstOrNull { it.flags and WPF_ALTERNATE.toLong() == 0L } ?: lands.firstOrNull()
        val alternate = lands.firstOrNull { it !== landing && it.flags and WPF_ALTERNATE.toLong() != 0L } ?: lands.firstOrNull { it !== landing }
        return landing to alternate
    }

    /** The formation word BMS prints for the low four bits of a waypoint's formation byte (WDP's `FormationString`). */
    fun formationName(n: Int): String? = when (n) {
        0 -> "Spread"; 1 -> "Wedge"; 2 -> "Trail"; 3 -> "Ladder"; 4 -> "Stack"; 5 -> "ResCell"; 6 -> "Box"
        7 -> "ArrowHead"; 8 -> "Fluid"; 9 -> "Vic"; 10 -> "EchelonRight"; 11 -> "Finger 4"; 12 -> "LineAbreast"
        13 -> "EchelonLeft"; 14 -> "Diamond"
        else -> null
    }

    // ================================================================ the sentences (R3-CAM §4.5)

    private fun fileNotes(
        ctx: Context, g: Target, save: CampaignArchive.Save, names: CampaignArchive.Names,
        flight: CampaignArchive.Flight? = null, ini: CampIniCheck? = null,
    ): List<String> {
        val out = ArrayList<String>()
        val base = g.file.nameWithoutExtension
        val cur = g.cur
        if (cur != null && cur !== g.t)
            out += "This file is from ${g.t.name}; Falcon BMS is set to ${cur.name}. The maps and airfields follow the file."
        // a campaign start (the list does not open one, but a note says why if one is asked for): the listing's sentence
        stockWhy(base, save.hasObj, if (save.uni != null) save.flights.size else null)?.let { out += it }
        val key = ctx.key
        val briefed = key?.let { k -> save.flights.firstOrNull { holds(k, names.callsign(it), save.packageOf(it)?.campId, it.campId) } }
        if (key != null && briefed == null && save.flights.isNotEmpty()) {
            val brief = "${key.callsign}${key.packageId?.let { ", package $it" } ?: ""}"
            // which of the two is newer decides what to say: an Auto Save written after the last PRINT is a new
            // mission, and the printed briefing is the stale one — not the save
            val saved = attempt(0L) { g.file.lastModified() }
            out += if (key.printed > 0 && saved > key.printed)
                "The printed briefing is for an earlier mission ($brief). This save is newer: plan from it, or press PRINT in BMS for this mission's briefing."
            else
                "This file doesn't hold the flight in the printed briefing ($brief). It is older than that briefing, or from another campaign: save the campaign in BMS, or pick another file."
        }
        if (key != null && briefed != null && save.modified < key.printed) {
            val el = ctx.briefing?.`package`?.firstOrNull { it.primary } ?: ctx.briefing?.`package`?.firstOrNull { it.callsign.equals(key.callsign, true) }
            val saveTo = takeoffMs(briefed).takeIf { it > 0 }?.let { "${hms(it)}z" }
            val saveTot = briefed.tot.takeIf { it > 0 }?.let { "${hms(it)}z" }
            val differ = el == null || el.takeoff?.trim() != saveTo || el.target?.trim() != saveTot
            if (differ) out += "Saved before the briefing was printed (${clockOf(save.modified)} vs ${clockOf(key.printed)}): what changed since is not in this file. Save the campaign in BMS to bring it in."
        }
        if (flight != null) {
            val cs = names.callsign(flight) ?: "Flight ${flight.campId}"
            if (key != null && flight !== briefed) {
                val samePkg = key.packageId != null && key.packageId == save.packageOf(flight)?.campId
                out += "You're planning $cs; the printed briefing is for ${key.callsign} (${if (samePkg) "same package" else "another package"})."
            }
            val clock = save.header?.currentTime ?: 0L
            val to = takeoffMs(flight)
            val landed = flight.waypoints.firstOrNull { it.action == ACT_LAND && it.arrive > 0 }?.arrive
            when {
                landed != null && clock > landed ->
                    out += "In this save $cs has already flown (clock ${clockShort(clock)}, landed ${hms(landed).substring(0, 5)})."
                to > 0 && clock > to ->
                    out += "$cs is airborne in this save (clock ${clockShort(clock)}, took off ${hms(to).substring(0, 5)})."
            }
            if (ini != null && !ini.matches && ini.reason != null && ini.modified > 0) out += ini.reason
            if (flight.waypoints.size > MOST_STPTS)
                out += "$cs has ${flight.waypoints.size} waypoints; the cartridge holds $MOST_STPTS (STPT 1–$MOST_STPTS)."
        }
        save.errors.forEach { out += if (it.startsWith("Could not")) it else "Could not read ${g.file.name}: ${it.trimEnd('.')}." }
        return out
    }

    // ================================================================ the app's own theaters and airports

    private var appIds: Set<String>? = null
    private val airportCache = ConcurrentHashMap<String, Map<Int, Airport>>()

    /** The app's theater ids (`data/index.json`), so a theater the app has no maps for gets no id. */
    private fun appTheaters(): Set<String> = appIds ?: attempt(emptySet<String>()) {
        kotlinx.coroutines.runBlocking { Repo.index().theaters.map { it.id }.toSet() }
    }.also { if (it.isNotEmpty()) appIds = it }

    /** The app's airports of theater [appId], by id (a campaign objective's `campId`). */
    fun airports(appId: String?): Map<Int, Airport> {
        if (appId.isNullOrEmpty()) return emptyMap()
        airportCache[appId]?.let { return it }
        val map = attempt(emptyMap<Int, Airport>()) {
            kotlinx.coroutines.runBlocking {
                val t = Repo.index().theaters.firstOrNull { it.id == appId } ?: return@runBlocking emptyMap()
                Repo.airportSet(t.airportSet).airports.associateBy { it.id }
            }
        }
        if (map.isNotEmpty()) airportCache[appId] = map
        return map
    }

    // ================================================================ small things

    /** Campaign ms as a time of day, "04:21:00": whole seconds, cut rather than rounded, as BMS prints them. */
    fun hms(ms: Long): String {
        val s = (ms / 1000) % 86400
        return String.format(Locale.ROOT, "%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
    }

    /** Campaign ms as "D1 04:16". */
    fun clockShort(ms: Long): String {
        val s = ms / 1000
        return String.format(Locale.ROOT, "D%d %02d:%02d", s / 86400 + 1, (s % 86400) / 3600, (s % 3600) / 60)
    }

    private val hhmm = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
    private val dayFmt = DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.ENGLISH)

    private fun clockOf(fileTime: Long) = attempt("?") { hhmm.format(Instant.ofEpochMilli(fileTime).atZone(ZoneId.systemDefault())) }
    private fun day(fileTime: Long) = attempt("?") { dayFmt.format(Instant.ofEpochMilli(fileTime).atZone(ZoneId.systemDefault())) }

    /** [dir] relative to the BMS folder [root] ("Data\\Add-On Hellas WCP\\Campaign"), as the window shows it. */
    private fun relative(root: File, dir: File): String {
        val r = attempt(root.absolutePath) { root.canonicalPath }.trimEnd('\\', '/')
        val d = attempt(dir.absolutePath) { dir.canonicalPath }
        return if (d.length > r.length && d.startsWith(r, ignoreCase = true)) d.substring(r.length).trimStart('\\', '/').replace('/', '\\') else d
    }

    private inline fun <T> attempt(fallback: T, body: () -> T): T = try { body() } catch (_: Throwable) { fallback }
}
