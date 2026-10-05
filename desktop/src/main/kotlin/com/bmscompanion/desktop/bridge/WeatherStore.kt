package com.bmscompanion.desktop.bridge

import com.bmscompanion.app.data.mission.WeatherState
import com.bmscompanion.app.data.mission.Wx
import com.bmscompanion.app.data.mission.WxMap
import com.bmscompanion.app.data.mission.WxModel
import com.bmscompanion.app.data.mission.WxTheater
import com.bmscompanion.app.data.weather.CampaignTime
import com.bmscompanion.app.data.weather.WxCover
import com.bmscompanion.app.data.weather.WxGenParams
import java.io.File
import java.security.MessageDigest
import com.bmscompanion.app.data.weather.WxModel as WxGenModel

/**
 * Choosing the weather, by writing weather maps where Falcon BMS lists them.
 *
 * **How BMS decides a mission's weather.** Each save (campaign, TE or training) picks one of BMS's three weather
 * models on its Weather screen (Weather → WEATHER tab → Weather Model): **Probabilistic**, **Deterministic** or
 * **Map Model**, and keeps the choice and its settings in `<save>.twx` beside the save. The first two need no map:
 * the whole theater is in one of four weather *types* (Sunny, Fair, Poor, Inclement) at a time, each type's values
 * coming from a table inside that `.twx`. Only under Map Model does BMS fly a map: the one picked from the list of
 * `.fmap` files in the theater's campaign folder, of which it keeps its own copy, `<save>.fmap`, once the weather is
 * saved; with MAPS AUTO UPDATE on it also loads `WeatherMapsUpdates/<DHHMM>.fmap` as the clock reaches each name.
 * `Sunny.fmap`, `Fair.fmap`, `Poor.fmap` and `Inclement.fmap` are four **ready-made maps** in that list ([Model]),
 * one type in every cell: not the models, and not where a save's four types are set.
 *
 * So this program writes maps only: a generated `BMSC <name>.fmap` beside BMS's own, and update maps in
 * `WeatherMapsUpdates` — never a `.twx`, a save or a save's own `.fmap`. A map reaches a mission when the pilot picks
 * it under Map Model and saves the weather in BMS. Those files are inside the install, so there is no way to offer
 * this without writing there. What there is a way to do is make it **completely reversible, by anyone, at any later
 * date, with or without this program**, and that is what shapes everything below. The rules, and they are the whole
 * design:
 *
 * 1. **The originals are copied once and never touched again.** [backUp] puts BMS's four ready-made maps into
 *    `Campaign/BMS Companion Backup/` and refuses to overwrite a copy that is already there; a series of update maps
 *    copies each BMS map it is about to replace into `BMS Companion Backup/WeatherMapsUpdates/` the same way, one
 *    file at a time (the whole folder is 290 MB in Korea). That folder is inside the BMS install, beside the files
 *    it protects — not in `%APPDATA%` — so reinstalling this app, moving to another PC or coming back in two years
 *    all still find it. The folder also gets a `README.txt` in plain English saying what happened and how to undo it
 *    **by hand**, because a pilot should never need this program to get their install back.
 * 2. **An edit always starts from the original, never from the last edit.** Every write of a ready-made map (only
 *    [set] and [setMap], which the app no longer calls) reads the pristine copy, changes only the fields it
 *    understands and writes that out; a generated map is built whole from its parameters. So edits never compound,
 *    and a wrong value is undone by setting a right one.
 * 3. **Nothing is written in place.** New bytes go to a temporary file beside the target and are moved across in
 *    one step, so a write that fails half way cannot leave a torn weather map where BMS expects one.
 * 4. **Every file written outside the four ready-made maps is listed before it is written**, in `written.txt` in the
 *    backup folder: "added" for a file that was not there (delete it to undo), "replaced" for one of BMS's own (its
 *    original is in the backup folder). Listing it first means even a write cut short is known about.
 * 5. **A record of what changed lives with the backup** (`bms-companion-weather.txt`). The checksum of each original
 *    tells [state], from the disk alone, which of the four ready-made maps differ from the copy; the record tells
 *    whether this program is the reason ([WxModel.edited]) or something else changed them, such as a BMS update
 *    ([WxModel.differs], [refreshBackup]).
 *
 * Nothing here throws: Falcon BMS is often installed where Windows will not let an ordinary program write, and a
 * button that silently does nothing is the failure this guards against. Every step goes through [attempt] and the
 * reason travels back in [WeatherState.error] for the page to show.
 */
class WeatherStore(
    private val install: BmsInstall,
    /** A stand-in for the install's Data folder, so --wxtest can run the whole of this against a copy. */
    private val dataOverride: File? = null,
) {

    /**
     * The four ready-made maps BMS ships in a theater's campaign folder and lists under Map Model, one weather type in
     * every cell, in the order of its four types. Not BMS's weather models (Probabilistic, Deterministic, Map Model),
     * and not where a save's four types are set: that is each save's own `.twx`.
     */
    enum class Model(val id: String, val label: String, val file: String, val type: Int) {
        SUNNY("sunny", "Sunny", "SUNNY.fmap", 1),
        FAIR("fair", "Fair", "FAIR.fmap", 2),
        POOR("poor", "Poor", "POOR.fmap", 3),
        INCLEMENT("inclement", "Inclement", "INCLEMENT.fmap", 4),
        ;

        companion object {
            fun of(id: String?) = entries.firstOrNull { it.id.equals(id, ignoreCase = true) }
        }
    }

    companion object {
        const val BACKUP_DIR = "BMS Companion Backup"
        private const val RECORD = "bms-companion-weather.txt"
        /**
         * Inside the backup folder: the generator's settings each generated map was made from, one JSON file per map
         * ("BMSC <name>.json"), so any device can open that weather again (`GET /api/weather/params`). They are the
         * app's notes, not Falcon BMS's files: nothing in BMS reads them, and deleting them changes nothing in BMS.
         */
        const val SETTINGS_DIR = "Generated settings"
        /** What this program wrote outside the four ready-made maps, one "added" or "replaced" line per file. */
        const val MANIFEST = "written.txt"
        /** BMS's Maps Auto Update folder (Technical Manual 13.6). */
        const val UPDATES = "WeatherMapsUpdates"
        /** Every generated map is named so, so it can never be mistaken for one of BMS's. */
        const val PREFIX = "BMSC "
        /**
         * "Minimum interval for auto loaded weather maps is now 55 minutes to guarantee correct interpolation. Maps
         * below minimum interval will be disregarded." (Technical Manual 13.6)
         */
        const val MIN_STEP = 55
        /** Ten days of hourly maps, about 94 MB: far more than a mission needs, and a bound on a slip of the finger. */
        const val MAX_SERIES = 240

        /** BMS stores wind in km/h; a pilot reads knots. */
        fun ktsOf(kmh: Double) = kmh / Fmap.KMH_PER_KT
        fun kmhOf(kts: Double) = kts * Fmap.KMH_PER_KT

        /** Inside the backup folder: earlier copies set aside when the pilot took a new one ([refreshBackup]). */
        const val OLDER = "Older copies"

        private val README = """
            BMS Companion — weather backup
            ==============================

            This folder holds copies of Falcon BMS's own weather maps for this theater, taken before BMS Companion
            wrote anything here: the four ready-made maps (Sunny, Fair, Poor and Inclement .fmap, which BMS lists
            under Weather > Map Model), and any update map BMS Companion wrote over. Nothing in here is ever
            overwritten. If a copy is ever replaced by a newer one (after a BMS update changed the maps), the
            earlier copy is moved into the folder "$OLDER", never deleted.

            How BMS uses these maps: every campaign or TE picks one of BMS's three weather models on its Weather
            screen (Weather > WEATHER tab > Weather Model): Probabilistic, Deterministic or Map Model. The choice
            and the settings of the four weather types are kept in that save's own .twx file, which BMS Companion
            never writes. Only under Map Model does BMS fly a map: the one picked from the list, of which it then
            keeps its own copy beside the save. BMS Companion only adds maps to that list: "BMSC <name>.fmap" in
            the folder above, and, for a series, update maps in WeatherMapsUpdates.

            To fly a BMSC map: in BMS, for the campaign or TE, Weather > WEATHER tab > Weather Model: Map Model >
            pick "BMSC <name>" > MAPS AUTO UPDATE on for a series, off for a single map > SAVE WTH (a TE must have
            been saved once first), then save the TE or campaign.

            To put Falcon BMS back the way it was, WITHOUT BMS Companion and without any other tool:

              1. Copy the .fmap files in this folder back into the folder above this one, replacing the files
                 that are there. (These are the four ready-made maps: Sunny, Fair, Poor, Inclement.)

              2. If there is a WeatherMapsUpdates folder in here, copy the .fmap files in it back into the
                 WeatherMapsUpdates folder above, replacing the files that are there. BMS Companion only copies a
                 map here just before it writes over it, so these are exactly the maps it replaced.

              3. If there is a file called $MANIFEST in this folder, open it. Every line starting "added" names a
                 file BMS Companion created where there was none before — a generated map called
                 "BMSC <name>.fmap", or an update map in WeatherMapsUpdates. Those files can simply be deleted.
                 Lines starting "replaced" are the maps of step 2.

            That is all there is to it. You can then delete this folder if you like. A mission that already
            saved a BMSC map as its weather keeps its own copy of it: pick another map (or another weather model)
            for it in BMS and save again.

            The folder "$SETTINGS_DIR" in here, if there is one, holds for each generated map the settings BMS
            Companion made it from, so the app can open that weather again on any device. Falcon BMS never reads
            them; they need no restoring and can be deleted. So can "$OLDER".

            A note on update maps: Falcon BMS loads the maps in WeatherMapsUpdates, by their day-and-time names,
            in ANY mission flown in this theater with MAPS AUTO UPDATE switched on on the Weather page. BMS's own
            hourly maps there go on loading before, after and between the times of a BMS Companion series.

            If there is a file called $RECORD here, it lists what BMS Companion wrote and when.
        """.trimIndent()
    }

    // ---------------------------------------------------------------- where the maps are

    /**
     * One theater a weather map can be written for: its campaign folder, the name the page shows and the id the page
     * sends back. [oldId] is the id this program gave the folder before theaters came from their definitions
     * ("korea-the-base-theater"), still accepted from a page that asks with it. [blocked] is why nothing can be written
     * for it, in a sentence: such a theater is listed so the pilot sees it and why, and is never written.
     */
    private class Place(val campaign: File, val name: String, val id: String, val oldId: String, val blocked: String? = null)

    /**
     * Every theater in the install, in the order of BMS's theater list: each one's campaign folder is where BMS lists
     * the maps its Map Model offers, so each can be given a generated map — whether or not it ships BMS's four
     * ready-made maps. Hellas ships no map at all, Hellas WCP only its SAT maps and LHTO only update maps, and until
     * 1.3.8 they were left out because a generated map took its grid from a ready-made one ([template] now finds
     * another, or uses BMS's usual 59 x 59). A theater whose campaign folder is not there is listed with the reason.
     *
     * Each theater's maps are in its campaign folder, and that folder comes from its theater definition
     * ([Theaters]) — the same place BMS itself looks. Guessing `Data\Campaign` and `Data\Add-On *\Campaign` missed the
     * six theaters of `Add-On Korea 2012`, whose campaign folders are two levels down, and LKTO's second theater
     * (`Campaign+`). A folder without theater definitions (a bare copy of a Data folder, as `--wxtest` uses) is still
     * scanned the old way.
     */
    private fun places(): List<Place> {
        val data = dataOverride ?: install.baseDir?.let { File(it, "Data") } ?: return emptyList()
        if (!data.isDirectory) return emptyList()
        val set = data.absoluteFile.parentFile?.let { Theaters.at(it) }
            // the definitions of *this* Data folder: an override named otherwise must not borrow its neighbour's
            ?.takeIf { it.all.isNotEmpty() && Theaters.canonical(it.data) == Theaters.canonical(data) }
        if (set == null) return oldPlaces(data)
        // the ids the old scan gave, for a page that still asks with one (only the folders it could see have one)
        val old = oldPlaces(data).associate { Theaters.canonical(it.campaign) to it.id }
        val seen = HashMap<String, String>()
        return set.all.map { t ->
            val c = set.campaignDir(t)
                ?: return@map Place(
                    File(data, t.campaignDir.orEmpty()), t.name, t.appId, "",
                    blocked = t.campaignDir?.let { "Its campaign folder, Data\\$it, is not in this install." }
                        ?: "Its theater definition names no campaign folder.",
                )
            // two theaters sharing one campaign folder share its maps: the first in the list names it
            seen[Theaters.canonical(c)]?.let { first ->
                return@map Place(c, t.name, t.appId, "", blocked = "It keeps its maps in $first's campaign folder: pick $first.")
            }
            seen[Theaters.canonical(c)] = t.name
            Place(c, t.name, t.appId, old[Theaters.canonical(c)] ?: "")
        }
    }

    /** The old scan: `Data\Campaign` and each `Data\Add-On *\Campaign`, named after the folder. */
    private fun oldPlaces(data: File): List<Place> {
        val dirs = ArrayList<File>()
        dirs += data
        data.listFiles { f -> f.isDirectory && f.name.startsWith("Add-On", true) }?.let { dirs += it.sortedBy { d -> d.name } }
        return dirs.mapNotNull { File(it, "Campaign").takeIf { c -> c.isDirectory && hasMaps(c) } }
            .map { c -> Place(c, oldNameOf(c), oldIdOf(c), oldIdOf(c)) }
    }

    /**
     * For the old scan only, which has no theater definitions to say which folders are campaign folders: one that
     * holds any weather map, or BMS's update-map folder (`Add-On Korea 2012\Campaign` holds only more folders).
     */
    private fun hasMaps(campaign: File) = attempt({ false }) {
        Model.entries.any { File(campaign, it.file).isFile } ||
            campaign.listFiles { f -> f.isFile && f.name.endsWith(".fmap", true) }?.isNotEmpty() == true ||
            File(campaign, UPDATES).isDirectory
    }

    private fun oldNameOf(campaign: File): String {
        val parent = campaign.parentFile?.name ?: return "Theater"
        return if (parent.equals("Data", true)) "Korea (the base theater)" else parent.removePrefix("Add-On ").trim()
    }

    private fun oldIdOf(campaign: File) = oldNameOf(campaign).lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

    /** Every theater folder in the install a weather map can be written for, as (id, name, campaign folder). */
    internal fun theaterDirs(): List<Triple<String, String, File>> =
        places().filter { it.blocked == null }.map { Triple(it.id, it.name, it.campaign) }

    /** Only for `--camsource`: what the scan before theater definitions found, as (id, name, campaign folder). */
    internal fun oldTheaterDirs(): List<Triple<String, String, File>> {
        val data = dataOverride ?: install.baseDir?.let { File(it, "Data") } ?: return emptyList()
        return if (data.isDirectory) oldPlaces(data).map { Triple(it.id, it.name, it.campaign) } else emptyList()
    }

    /** The campaign folder of theater [id] — never of one listed only to say why it cannot be written ([Place.blocked]). */
    private fun dirFor(id: String?): File? {
        val all = places().filter { it.blocked == null }
        return (all.firstOrNull { it.id == id } ?: all.firstOrNull { it.oldId.isNotEmpty() && it.oldId == id })?.campaign
    }

    private fun backupDir(campaign: File) = File(campaign, BACKUP_DIR)
    private fun original(campaign: File, m: Model) = File(backupDir(campaign), m.file)
    private fun live(campaign: File, m: Model) = File(campaign, m.file)

    // ---------------------------------------------------------------- what the page needs to know

    /**
     * Everything the page needs.
     *
     * Only the theater being looked at has its four maps decoded: the rest are listed by name so they can be picked,
     * and reading forty megabytes to fill in numbers nobody is looking at would make the page slow to open on a
     * install with a dozen add-on theaters.
     */
    fun state(error: String? = null, detail: String? = null, withMap: Boolean = false): WeatherState = attempt({ WeatherState(error = it) }) {
        val places = places()
        val current = currentTheaterId(places)
        // a page may still ask with the id an older version gave the folder
        val asked = detail?.let { d -> (places.firstOrNull { it.id == d } ?: places.firstOrNull { it.oldId.isNotEmpty() && it.oldId == d })?.id ?: d }
        val wanted = asked ?: current ?: places.firstOrNull { it.blocked == null }?.id
        WeatherState(
            available = places.any { it.blocked == null },
            current = current,
            theaters = places.map { p ->
                val c = p.campaign
                if (p.blocked != null) return@map WxTheater(id = p.id, name = p.name, dir = c.path, blocked = p.blocked)
                // a backup folder an earlier version made still has that version's words: brought up to date
                refreshReadme(c)
                val written = manifest(c).values
                val stock = Model.entries.any { live(c, it).isFile }
                val here = p.id == wanted
                val updates = if (here) bmsUpdates(c, written) else null
                val ours = if (here) writtenByUs(c) else emptyMap()
                WxTheater(
                    id = p.id,
                    name = p.name,
                    dir = c.path,
                    // with none of the four ready-made maps there is nothing to copy: the folder beside the campaign
                    // files, with its README, is what switches the theater on
                    backedUp = if (stock) Model.entries.all { m -> !live(c, m).isFile || original(c, m).isFile } else backupDir(c).isDirectory,
                    models = if (!here) emptyList() else Model.entries.mapNotNull { m -> modelOf(c, m, withMap, ours) },
                    added = written.filter { it.added }.map { it.path },
                    replaced = written.filter { !it.added }.map { it.path },
                    stockMaps = stock,
                    gridFrom = if (here) templateFile(c)?.let { it.relativeTo(c).path.replace('\\', '/') } ?: "built-in" else null,
                    bmsUpdates = updates?.size,
                    bmsUpdatesFirst = updates?.minByOrNull { it.minutes },
                    bmsUpdatesLast = updates?.maxByOrNull { it.minutes },
                )
            },
            error = error,
        )
    }

    /**
     * BMS's own update maps in the theater's `WeatherMapsUpdates`: every `<DHHMM>.fmap` there this program did not add
     * (the ones it wrote over are still at BMS's times), as the times they load at.
     */
    private fun bmsUpdates(campaign: File, written: Collection<Written>): List<CampaignTime> = attempt({ emptyList() }) {
        val added = written.filter { it.added && it.path.startsWith("$UPDATES/", ignoreCase = true) }
            .map { it.path.substringAfterLast('/').lowercase() }.toSet()
        (File(campaign, UPDATES).listFiles { f -> f.isFile && f.name.endsWith(".fmap", true) } ?: emptyArray())
            .filter { it.name.lowercase() !in added }
            .mapNotNull { CampaignTime.ofFmapName(it.name) }
    }

    /** Campaign folders whose README this run has already brought up to date. */
    private val readmeChecked = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * Brings the README in this theater's backup folder up to date, once a run. A folder an earlier version made keeps
     * that version's words until something is written, and those words were wrong: they called the four ready-made
     * maps "the four weather models" and promised a record that may not be there. Only the README, only in the app's
     * own backup folder (within the backup rule), written the safe way, never throwing — and never while a developer
     * check runs against a folder that is not a copy ([DevGuard]).
     */
    private fun refreshReadme(campaign: File) {
        val back = backupDir(campaign)
        if (!back.isDirectory || !readmeChecked.add(Theaters.canonical(campaign))) return
        if (DevGuard.refusal(back.path) != null) return
        attempt<Unit>({ }) {
            val readme = File(back, "README.txt")
            if (!readme.isFile || readme.readText() != README) writeSafely(readme, README.toByteArray())
        }
    }

    /**
     * The theater BMS says it is flying: the theater definition's own name (trimmed, without case), else — for a
     * folder named the old way — matched on words, because the names are written freely.
     */
    private fun currentTheaterId(places: List<Place>): String? {
        if (dataOverride != null) return null
        val theater = runCatching { SharedMemoryReader.read().live.theater }.getOrNull()?.trim() ?: return null
        if (theater.isEmpty()) return null
        places.firstOrNull { it.name.equals(theater, true) || it.id == Theaters.slug(theater) }?.let { return it.id }
        val want = theater.lowercase().split(' ', '-', '_', '.').filter { it.length > 3 }
        if (want.isEmpty()) return null
        return places.firstOrNull { p -> want.any { p.name.lowercase().contains(it) } }?.id
    }

    /** One ready-made map as it stands; [ours] is [writtenByUs] for its theater. */
    private fun modelOf(campaign: File, m: Model, withMap: Boolean, ours: Map<String, String?>): WxModel? {
        val f = live(campaign, m)
        if (!f.isFile) return null
        val differs = changed(campaign, m)
        // only a difference this program's own record accounts for is blamed on it, and offered Restore
        val edited = differs && isOurs(campaign, m, ours)
        val copiedAt = original(campaign, m).takeIf { it.isFile }?.lastModified()
        val map = Fmap.read(f)
            ?: return WxModel(id = m.id, name = m.label, readable = false, edited = edited, differs = differs, copiedAt = copiedAt)
        val grid = gridOf(map)
        return WxModel(
            id = m.id,
            name = m.label,
            readable = true,
            edited = edited,
            differs = differs,
            copiedAt = copiedAt,
            uniform = grid.zones.size <= 1,
            // the weather of the largest area, which is what a one-weather map's whole self is
            weather = grid.zones.getOrElse(largestZone(grid)) { Wx() },
            map = if (withMap) grid else null,
        )
    }

    /**
     * What counts as "the same weather" when the palette is built.
     *
     * Two cells differing in the second decimal of a wind speed are the same weather to anybody looking at a map,
     * and treating them as different fills the palette with eight shades of identical and paints the theater in a
     * checkerboard. So cells are grouped on a deliberately coarse key — the values rounded to what a pilot would
     * read off a briefing — while the palette keeps the first cell's own numbers.
     */
    private fun coarse(w: Wx): String = listOf(
        w.type,
        w.cover,
        (w.cloudSize * 2).toInt(),
        if (w.towering) 1 else 0,
        (w.cloudBaseFt / 250).toInt(),
        (w.visibilityKm / 2).toInt(),
        (w.tempC / 2).toInt(),
        (w.pressureMb / 2).toInt(),
        (w.windDirDeg / 10).toInt(),
        (w.windKts / 5).toInt(),
        (w.windAloftKts / 10).toInt(),
    ).joinToString(":")

    /**
     * The whole map as a short palette and a cell index.
     *
     * Each ready-made map is one weather everywhere, so this usually comes back with a single zone; a campaign map
     * that varies across the theater comes back with as many as [WxMap.MAX_ZONES].
     */
    private fun gridOf(map: Fmap): WxMap {
        val zones = ArrayList<Wx>()
        val index = HashMap<String, Int>()
        val cells = IntArray(map.cells)
        for (c in 0 until map.cells) {
            val wx = weatherOf(map, c)
            val key = coarse(wx)
            var z = index[key]
            if (z == null) {
                if (zones.size < WxMap.MAX_ZONES) {
                    z = zones.size
                    zones += wx
                    index[key] = z
                } else {
                    z = nearestZone(zones, wx)      // a map with more shades than the palette holds: snap to the closest
                }
            }
            cells[c] = z
        }
        return WxMap(
            cols = map.cols, rows = map.rows, zones = zones, cells = cells.toList(),
            airmassDirDeg = ((map.moveHeading % 360) + 360) % 360,
            airmassSpeed = Math.round(map.moveSpeed * 10.0) / 10.0,
            contrailFt = map.contrails,
            stratusFt = map.stratus,
        )
    }

    private fun largestZone(grid: WxMap): Int {
        if (grid.zones.size <= 1) return 0
        val counts = IntArray(grid.zones.size)
        for (z in grid.cells) if (z in counts.indices) counts[z]++
        return counts.indices.maxByOrNull { counts[it] } ?: 0
    }

    /** Which palette entry a weather is closest to, on the things that show on a map. */
    private fun nearestZone(zones: List<Wx>, wx: Wx): Int {
        var best = 0
        var bestD = Double.MAX_VALUE
        zones.forEachIndexed { i, z ->
            val d = kotlin.math.abs(z.cloudBaseFt - wx.cloudBaseFt) / 1000.0 +
                kotlin.math.abs(z.visibilityKm - wx.visibilityKm) / 10.0 +
                kotlin.math.abs(z.windKts - wx.windKts) / 10.0 +
                kotlin.math.abs((z.type - wx.type).toDouble()) * 2.0
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }

    /**
     * One cell as the app speaks: cover in oktas (the file's code 0/1/5/9/13 turned into the middle of its range),
     * and the towering-cumulus flag in both its names so an older client still sees it.
     */
    private fun weatherOf(map: Fmap, cell: Int = 0): Wx {
        val towering = map.int(Fmap.Field.TOWERING, cell) != 0
        return Wx(
            type = map.int(Fmap.Field.TYPE, cell),
            cloudSize = round1(map.float(Fmap.Field.CLOUD_SIZE, cell).toDouble()),
            shower = towering,
            towering = towering,
            tempC = round1(map.float(Fmap.Field.TEMPERATURE, cell).toDouble()),
            pressureMb = round1(map.float(Fmap.Field.PRESSURE, cell).toDouble()),
            visibilityKm = round1(map.visibilityKm(cell).toDouble()),
            cloudBaseFt = round1(map.float(Fmap.Field.CLOUD_BASE, cell).toDouble()),
            cover = map.cover(cell).oktas,
            windDirDeg = round1(map.windDir(cell, 0).toDouble()),
            windKts = round1(ktsOf(map.windSpeed(cell, 0).toDouble())),
            windAloftKts = round1(ktsOf(map.windSpeed(cell, Fmap.Field.LEVELS - 1).toDouble())),
            veerDeg = round1(
                (map.windDir(cell, 1) - map.windDir(cell, 0)).toDouble().let {
                    if (it < -180) it + 360 else if (it > 180) it - 360 else it
                },
            ),
        )
    }

    private fun round1(v: Double) = Math.round(v * 10.0) / 10.0

    /** Whether the live file differs from the copy taken of BMS's own — for whatever reason. */
    private fun changed(campaign: File, m: Model): Boolean = attempt({ false }) {
        val orig = original(campaign, m)
        val f = live(campaign, m)
        if (!orig.isFile || !f.isFile) return@attempt false
        if (orig.length() != f.length()) return@attempt true
        sha(orig) != sha(f)
    }

    /**
     * The ready-made maps this program's own record says it wrote last, by file name upper-cased ("FAIR.FMAP"): a
     * "wrote FAIR.fmap" line in `bms-companion-weather.txt` not followed by a "restored" or "took a new copy" line
     * naming it. Every version that wrote one of the four has kept that record, in those words ([set], [setMap],
     * [restore]). A map that differs from its copy with no such line was changed by something else — a BMS update
     * shipping new maps, another tool — and Restore would put the older copy back over it; [refreshBackup] is the
     * answer to that one. (`written.txt` never lists the four: it holds the files written outside them.)
     *
     * Each name comes with the checksum of the bytes written, when the line carries one ("sha256 …", written since
     * 1.3.8's second test build), else null: a map put back by hand as the README says, then changed by a BMS update,
     * no longer has the bytes this program wrote, and [isOurs] says so.
     */
    private fun writtenByUs(campaign: File): Map<String, String?> = attempt({ emptyMap() }) {
        val f = File(backupDir(campaign), RECORD)
        if (!f.isFile) return@attempt emptyMap()
        val names = Model.entries.map { it.file }
        val ours = HashMap<String, String?>()
        for (line in f.readLines()) {
            // "2026-09-28 07:02:52  wrote FAIR.fmap — 1 area(s) painted across the theater"
            val what = line.substringAfter("  ", "").trim()
            // a generated map may be called "BMSC Sunny.fmap": its lines are about that map, not BMS's
            if (what.contains(PREFIX, ignoreCase = true)) continue
            val named = names.filter { n ->
                Regex("(^|[\\s,])" + Regex.escape(n) + "($|[\\s,])", RegexOption.IGNORE_CASE).containsMatchIn(what)
            }.map { it.uppercase() }
            val hash = Regex("sha256 ([0-9a-f]{64})").find(what)?.groupValues?.get(1)
            when {
                what.startsWith("wrote ", ignoreCase = true) -> named.forEach { ours[it] = hash }
                what.startsWith("restored ", ignoreCase = true) || what.startsWith("took a new copy", ignoreCase = true) -> named.forEach { ours.remove(it) }
            }
        }
        ours
    }

    /**
     * Whether ready-made map [m] is, as it stands, what this program last wrote into it: named by the record
     * ([writtenByUs]) and, where the record kept the checksum, still those bytes. A record from before checksums were
     * kept is taken at its word.
     */
    private fun isOurs(campaign: File, m: Model, record: Map<String, String?>): Boolean {
        val key = m.file.uppercase()
        if (key !in record) return false
        val hash = record[key] ?: return true
        return attempt({ false }) { sha(live(campaign, m)) == hash }
    }

    /** SHA-256 of [bytes] in hex, as the record keeps it beside a "wrote" line. */
    private fun shaOf(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /**
     * Checksums, kept while the file has not moved.
     *
     * Telling an edited map from a shipped one means hashing both, and the page asks for the whole state every time
     * it opens or anything is pressed — which across a dozen theaters is forty megabytes of reading for an answer
     * that only changes when a file does. The key is the file's own length and timestamp, so a write of any kind
     * invalidates it and there is nothing to keep in step by hand.
     */
    private val hashes = HashMap<String, Pair<String, String>>()

    private fun sha(f: File): String {
        val stamp = "${f.length()}:${f.lastModified()}"
        hashes[f.path]?.let { (at, hash) -> if (at == stamp) return hash }
        val hash = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
        hashes[f.path] = stamp to hash
        return hash
    }

    // ---------------------------------------------------------------- the one-time copy

    /**
     * Puts BMS's own maps safely aside, once.
     *
     * A copy that already exists is left exactly as it is — that is the whole point of it, and it is the reason a
     * second run of this cannot lose the originals however many times the weather has been changed since. In a
     * theater without the four ready-made maps (Hellas, Hellas WCP, LHTO) there is nothing to copy: this makes the
     * folder and its README, which is what switches the theater on.
     */
    fun backUp(theaterId: String?): WeatherState = attempt({ state(it) }) {
        val campaign = dirFor(theaterId) ?: return@attempt state("No such theater on this PC.")
        backupFolder(campaign)?.let { return@attempt state(it) }
        var failed: String? = null
        for (m in Model.entries) {
            val from = live(campaign, m)
            val to = original(campaign, m)
            if (!from.isFile || to.isFile) continue     // never replace a copy that is already taken
            val e = attempt<String?>({ it }) { copySafely(from, to); null }
            if (e != null && failed == null) failed = e
        }
        state(failed, theaterId)
    }

    /**
     * Takes a new copy of the ready-made maps that differ from their copy although this program did not write them —
     * a BMS update shipped new ones, or another tool changed them — so that the copy is BMS's own again and Restore
     * can never put the older map back over the newer. [modelId] names one map; with none, every such map.
     *
     * The copy being replaced is not lost: it moves first into `Older copies/<date time>/` in the backup folder, so
     * the rule that nothing in there is ever overwritten still holds. A map the record says this program wrote
     * ([writtenByUs]) is left alone — Restore is the answer for that one — and so is one that matches its copy.
     */
    fun refreshBackup(theaterId: String?, modelId: String?): WeatherState = attempt({ state(it) }) {
        val campaign = dirFor(theaterId) ?: return@attempt state("No such theater on this PC.")
        if (modelId != null && modelId.isNotEmpty() && Model.of(modelId) == null) return@attempt state("No such weather map.")
        val models = Model.of(modelId)?.let { listOf(it) } ?: Model.entries.toList()
        val ours = writtenByUs(campaign)
        // one folder per press, named so Windows accepts it and a person can read it
        val stamp = java.time.LocalDateTime.now().withNano(0).toString().replace('T', ' ').replace(':', '-')
        val keep = File(File(backupDir(campaign), OLDER), stamp)
        var failed: String? = null
        val done = ArrayList<String>()
        for (m in models) {
            val orig = original(campaign, m)
            if (!orig.isFile || !live(campaign, m).isFile || !changed(campaign, m)) continue
            if (isOurs(campaign, m, ours)) {
                // said only when that map was asked for by name; "every such map" simply passes it by
                if (models.size == 1 && failed == null) failed = "BMS Companion wrote ${m.file}; Restore puts BMS's own back instead."
                continue
            }
            val e = attempt<String?>({ it }) {
                if (!keep.isDirectory && !keep.mkdirs()) return@attempt "The folder ${keep.path} could not be made."
                copySafely(orig, File(keep, orig.name))
                copySafely(live(campaign, m), orig)
                null
            }
            if (e == null) done += m.file else if (failed == null) failed = e
        }
        if (done.isNotEmpty()) {
            record(campaign, "took a new copy of " + done.joinToString(", ") + " as the files are now; the earlier copy is in $OLDER/$stamp")
            backupFolder(campaign)?.let { if (failed == null) failed = it }
        }
        state(failed, theaterId)
    }

    /** Makes the backup folder and its README, or says in words why it could not. */
    private fun backupFolder(campaign: File): String? {
        val back = backupDir(campaign)
        if (!back.isDirectory && !back.mkdirs()) {
            return "The backup folder could not be made in ${campaign.path}. Falcon BMS is often installed where " +
                "Windows will not let an ordinary program write: start BMS Companion as administrator, or " +
                "install BMS somewhere else."
        }
        val readme = File(back, "README.txt")
        return attempt<String?>({ it }) {
            if (!readme.isFile || readme.readText() != README) writeSafely(readme, README.toByteArray())
            null
        }
    }

    // ---------------------------------------------------------------- writing a weather

    /**
     * Writes one weather into one of BMS's four ready-made maps (the route `set`, which the app no longer calls; the
     * `model` it takes names one of [Model], not one of BMS's weather models).
     *
     * The bytes start as the **original** file every time, not as whatever is there now, so setting a value twice
     * gives the same file as setting it once and nothing this program does not understand ever drifts.
     */
    fun set(theaterId: String?, modelId: String?, wx: Wx): WeatherState = attempt({ state(it) }) {
        val campaign = dirFor(theaterId) ?: return@attempt state("No such theater on this PC.")
        val model = Model.of(modelId) ?: return@attempt state("No such weather model.")
        val orig = original(campaign, model)
        if (!orig.isFile) return@attempt state("That theater's weather has not been backed up yet — press the backup button first.")
        val map = Fmap.read(orig)?.takeIf { it.writable } ?: return@attempt state("${model.file} is not a weather map this version writes.")

        for (c in 0 until map.cells) paint(map, c, wx)
        val bytes = map.toBytes()
        writeSafely(live(campaign, model), bytes)
        // the checksum lets [isOurs] tell this write from whatever changes the file later
        record(campaign, "wrote ${model.file} — one weather everywhere, sha256 ${shaOf(bytes)}")
        state(detail = theaterId, withMap = true)
    }

    /**
     * Writes a whole painted map: a palette, and which entry each cell holds.
     *
     * Same rule as [set] — the bytes start as the untouched original, so whatever this program is not asked to change
     * is still BMS's own however many times the map has been painted.
     */
    fun setMap(theaterId: String?, modelId: String?, grid: WxMap): WeatherState = attempt({ state(it) }) {
        val campaign = dirFor(theaterId) ?: return@attempt state("No such theater on this PC.")
        val model = Model.of(modelId) ?: return@attempt state("No such weather model.")
        val orig = original(campaign, model)
        if (!orig.isFile) return@attempt state("That theater's weather has not been backed up yet — press the backup button first.")
        val map = Fmap.read(orig)?.takeIf { it.writable } ?: return@attempt state("${model.file} is not a weather map this version writes.")
        if (grid.zones.isEmpty()) return@attempt state("That map has no weather in it.")
        if (grid.cols != map.cols || grid.rows != map.rows) {
            return@attempt state("That map is ${grid.cols} x ${grid.rows} and this theater's is ${map.cols} x ${map.rows}.")
        }
        for (c in 0 until map.cells) {
            paint(map, c, grid.zones.getOrElse(grid.cells.getOrElse(c) { 0 }) { grid.zones[0] })
        }
        // the whole map's own settings: which way the weather is moving, and the high cloud and contrail layers
        map.moveHeading = ((grid.airmassDirDeg % 360) + 360) % 360
        map.moveSpeed = grid.airmassSpeed.coerceIn(0.0, 150.0).toFloat()
        val alt = { ft: Int -> ft.coerceIn(0, 60000) }
        when (grid.contrailFt.size) {
            Fmap.CONTRAIL_LAYERS -> map.contrails = grid.contrailFt.map(alt)
            // a client from before 1.3.8 sends the six header altitudes it read, the first two being the stratus
            6 -> { map.stratus = grid.contrailFt.take(2).map(alt); map.contrails = grid.contrailFt.drop(2).map(alt) }
        }
        if (grid.stratusFt.size == 2) map.stratus = grid.stratusFt.map(alt)
        val bytes = map.toBytes()
        writeSafely(live(campaign, model), bytes)
        record(campaign, "wrote ${model.file} — ${grid.zones.size} area(s) painted across the theater, sha256 ${shaOf(bytes)}")
        state(detail = theaterId, withMap = true)
    }

    /** One cell set to one weather. Everything the format does not explain is left exactly as the original had it. */
    private fun paint(map: Fmap, cell: Int, raw: Wx) {
        // BMS's own editor will not let you draw a poor cell with scattered cloud, so neither does this
        val wx = raw.legal()
        map.setInt(Fmap.Field.TYPE, cell, wx.type.coerceIn(1, 4))
        map.setFloat(Fmap.Field.CLOUD_SIZE, cell, wx.cloudSize.coerceIn(0.0, 5.0).toFloat())
        map.setInt(Fmap.Field.TOWERING, cell, if (wx.towering || wx.shower) 1 else 0)
        map.setFloat(Fmap.Field.TEMPERATURE, cell, wx.tempC.coerceIn(-60.0, 60.0).toFloat())
        map.setFloat(Fmap.Field.PRESSURE, cell, wx.pressureMb.coerceIn(950.0, 1060.0).toFloat())
        map.setFloat(Fmap.Field.VISIBILITY, cell, wx.visibilityKm.coerceIn(0.0, 60.0).toFloat())
        map.setFloat(Fmap.Field.CLOUD_BASE, cell, wx.cloudBaseFt.coerceIn(0.0, 10000.0).toFloat())
        // the app counts oktas; the file holds BMS's code (0/1/5/9/13)
        map.setCover(cell, WxCover.ofOktas(wx.cover))
        // Ten levels of wind, ramped the way BMS's own maps are: the speed rises evenly from the surface to the
        // top level and the direction veers by a fixed amount each step.
        val surface = kmhOf(wx.windKts.coerceIn(0.0, 150.0))
        val aloft = kmhOf(wx.windAloftKts.coerceIn(0.0, 150.0))
        for (k in 0 until Fmap.Field.LEVELS) {
            val t = k.toDouble() / (Fmap.Field.LEVELS - 1)
            map.setWindSpeed(cell, k, (surface + (aloft - surface) * t).toFloat())
            val dir = ((wx.windDirDeg + wx.veerDeg * k) % 360.0 + 360.0) % 360.0
            map.setWindDir(cell, k, dir.toFloat())
        }
    }

    /** Puts one model — or, with no model, all four — back exactly as Falcon BMS shipped them. */
    fun restore(theaterId: String?, modelId: String?): WeatherState = attempt({ state(it) }) {
        val campaign = dirFor(theaterId) ?: return@attempt state("No such theater on this PC.")
        val models = Model.of(modelId)?.let { listOf(it) } ?: Model.entries.toList()
        var failed: String? = null
        for (m in models) {
            val orig = original(campaign, m)
            if (!orig.isFile) continue
            val e = attempt<String?>({ it }) { writeSafely(live(campaign, m), orig.readBytes()); null }
            if (e != null && failed == null) failed = e
        }
        record(campaign, "restored " + models.joinToString(", ") { it.file })
        state(failed, theaterId, withMap = true)
    }

    // ---------------------------------------------------------------- generated weather

    /**
     * A generated weather as a map of its own, `Campaign/BMSC <name>.fmap`, which BMS lists beside its own under
     * Weather → Map Model. A new file costs BMS nothing — its ready-made maps stay as they are — and deleting it undoes
     * it; it is listed as "added" in the manifest all the same, so the README can say so. It reaches a mission only
     * when the pilot picks it under Map Model and saves the weather in BMS, and a mission that saved it keeps its own
     * copy (`<save>.fmap`): writing this map again does not change that mission.
     */
    fun writeGenerated(theaterId: String?, name: String?, params: WxGenParams): WeatherState = attempt({ state(it) }) {
        val campaign = dirFor(theaterId) ?: return@attempt state("No such theater on this PC.")
        val n = cleanName(name) ?: return@attempt state(NAME_RULE)
        val template = template(campaign)
        mismatch(template, params)?.let { return@attempt state(it) }
        backupFolder(campaign)?.let { return@attempt state(it) }
        val bytes = build(template, params) ?: return@attempt state("The weather could not be built for this theater's grid.")
        place(campaign, "$PREFIX$n.fmap", bytes)?.let { return@attempt state(it, theaterId) }
        keepSettings(campaign, "$PREFIX$n", params)
        record(campaign, "wrote $PREFIX$n.fmap — generated, seed ${params.seed.toLong()}, ${params.current.label}")
        state(detail = theaterId)
    }

    /**
     * A series for BMS's Maps Auto Update: the first map as `BMSC <name>.fmap` (the one to pick under Map Model),
     * and one map every [stepMin] minutes from [from] to [to] as `WeatherMapsUpdates/<DHHMM>.fmap`, the names BMS
     * loads them by (Technical Manual 13.6.1).
     *
     * As WeatherGen's own `save-weather-files` does it, each map is the pattern **at that time** — it evolves — and
     * every header carries the movement heading and speed, so BMS drifts each map itself between updates.
     *
     * The update folder holds BMS's own hourly maps (741 in Korea, Day 1 01:00 to Day 31 21:00; LHTO 380, Balkans
     * 334) and a series writes over any of the same name, so each one is copied into the backup folder, once, just
     * before its first overwrite. [restoreSeries] undoes the lot. Only those of the same name: BMS's own go on loading
     * before the series, after it, and between its steps when they are not on the hour — and BMS disregards a map less
     * than [MIN_STEP] minutes after the one before, which a step off the hour can make one of ours. The folder is the
     * theater's, not a mission's: a series reaches every mission there flown with MAPS AUTO UPDATE on.
     */
    fun writeSeries(
        theaterId: String?,
        name: String?,
        params: WxGenParams,
        from: CampaignTime?,
        to: CampaignTime?,
        stepMin: Int?,
    ): WeatherState = attempt({ state(it) }) {
        val campaign = dirFor(theaterId) ?: return@attempt state("No such theater on this PC.")
        val n = cleanName(name) ?: return@attempt state(NAME_RULE)
        if (from == null || to == null) return@attempt state("The series needs a start and an end time.")
        val step = stepMin ?: params.movement.stepMin.toInt()
        if (step < MIN_STEP) {
            return@attempt state("BMS ignores update maps less than $MIN_STEP minutes apart; choose a step of $MIN_STEP or more.")
        }
        if (to.minutes < from.minutes) return@attempt state("The series ends before it starts.")
        val count = (to.minutes - from.minutes) / step + 1
        if (count > MAX_SERIES) return@attempt state("That is $count maps; the most one series may hold is $MAX_SERIES.")
        val template = template(campaign)
        mismatch(template, params)?.let { return@attempt state(it) }
        backupFolder(campaign)?.let { return@attempt state(it) }
        val updates = File(campaign, UPDATES)
        if (!updates.isDirectory && !updates.mkdirs()) return@attempt state("The folder ${updates.path} could not be made.")

        var written = 0
        for (i in 0 until count) {
            val t = from.plusMinutes((i * step).toDouble())
            val bytes = build(template, params.copy(current = t))
                ?: return@attempt state("The weather could not be built for this theater's grid.")
            if (i == 0) {
                place(campaign, "$PREFIX$n.fmap", bytes)?.let { return@attempt state(it, theaterId) }
                keepSettings(campaign, "$PREFIX$n", params.copy(current = t))
            }
            place(campaign, "$UPDATES/${t.fmapName}", bytes)?.let {
                record(campaign, "series $PREFIX$n: stopped after $written of $count maps — $it")
                return@attempt state("Wrote $written of $count maps, then: $it", theaterId)
            }
            written++
        }
        record(campaign, "wrote series $PREFIX$n.fmap + $count update maps, ${from.label} to ${to.label} every $step min")
        state(detail = theaterId)
    }

    /**
     * Undoes every update map this program wrote: BMS's own go back from the backup, the ones that had no original are
     * deleted. The backup copies themselves stay, as every original does.
     */
    fun restoreSeries(theaterId: String?): WeatherState = attempt({ state(it) }) {
        val campaign = dirFor(theaterId) ?: return@attempt state("No such theater on this PC.")
        val man = manifest(campaign)
        var failed: String? = null
        var undone = 0
        for (e in man.values.filter { it.path.startsWith("$UPDATES/", ignoreCase = true) }) {
            val err = undo(campaign, e)
            if (err == null) { man.remove(e.path.lowercase()); undone++ } else if (failed == null) failed = err
        }
        saveManifest(campaign, man)
        record(campaign, "restored $undone update map(s)")
        state(failed, theaterId)
    }

    /** Deletes a generated map this program added (or, with no [name], every one of them). */
    fun removeGenerated(theaterId: String?, name: String?): WeatherState = attempt({ state(it) }) {
        val campaign = dirFor(theaterId) ?: return@attempt state("No such theater on this PC.")
        val man = manifest(campaign)
        val wanted = name?.let { cleanName(it) ?: return@attempt state(NAME_RULE) }
        val targets = man.values.filter { e ->
            !e.path.contains('/') && e.path.startsWith(PREFIX, ignoreCase = true) &&
                (wanted == null || e.path.equals("$PREFIX$wanted.fmap", ignoreCase = true))
        }
        if (wanted != null && targets.isEmpty()) return@attempt state("BMS Companion did not write a map called $PREFIX$wanted.fmap here.")
        var failed: String? = null
        for (e in targets) {
            val err = undo(campaign, e)
            if (err == null) {
                man.remove(e.path.lowercase())
                // the settings it was made from go with it: they would otherwise offer a weather that is not there
                attempt<Unit>({ }) { settingsFile(campaign, e.path.removeSuffix(".fmap")).let { if (it.exists()) it.delete() } }
            } else if (failed == null) failed = err
        }
        saveManifest(campaign, man)
        record(campaign, "removed " + targets.joinToString(", ") { it.path })
        state(failed, theaterId)
    }

    // ---------------------------------------------------------------- the settings a generated map was made from

    private val settingsJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun settingsFile(campaign: File, map: String) = File(File(backupDir(campaign), SETTINGS_DIR), "$map.json")

    /**
     * Keeps [params] beside the map [map] ("BMSC <name>") was made from them. A note, not the map: a failure here costs
     * only the chance to open that weather again, so it is recorded and the map stands.
     */
    private fun keepSettings(campaign: File, map: String, params: WxGenParams) {
        val e = attempt<String?>({ it }) {
            val f = settingsFile(campaign, map)
            f.parentFile?.let { if (!it.isDirectory && !it.mkdirs()) return@attempt "The folder ${it.path} could not be made." }
            val text = settingsJson.encodeToString(
                com.bmscompanion.app.ui.screens.editor.WxGenSaved.serializer(),
                com.bmscompanion.app.ui.screens.editor.WxGenSaved(map, params),
            )
            writeSafely(f, text.toByteArray())
            null
        }
        if (e != null) record(campaign, "the settings of $map.fmap were not kept: $e")
    }

    /**
     * The settings a generated map of [theaterId] was made from: the map [name] ("BMSC Frontal day", "Frontal day" or
     * with ".fmap"), or with no name the one written last among the maps still there. Null when there is none — a map
     * an earlier version wrote kept no settings.
     */
    internal fun savedParams(theaterId: String?, name: String?): com.bmscompanion.app.ui.screens.editor.WxGenSaved? = attempt({ null }) {
        val campaign = dirFor(theaterId) ?: return@attempt null
        val dir = File(backupDir(campaign), SETTINGS_DIR)
        val wanted = name?.let { cleanName(it) ?: return@attempt null }
        val candidates = (dir.listFiles { f -> f.isFile && f.name.endsWith(".json", true) } ?: emptyArray())
            .filter { f ->
                val map = f.name.removeSuffix(".json")
                // only while the map itself is still there, and only a map of this program's own
                map.startsWith(PREFIX, true) && File(campaign, "$map.fmap").isFile &&
                    (wanted == null || map.equals("$PREFIX$wanted", true))
            }
            .sortedByDescending { it.lastModified() }
        for (f in candidates) {
            val saved = runCatching {
                settingsJson.decodeFromString(com.bmscompanion.app.ui.screens.editor.WxGenSaved.serializer(), f.readText())
            }.getOrNull() ?: continue
            return@attempt saved.copy(name = f.name.removeSuffix(".json"))
        }
        null
    }

    /** [savedParams] as the JSON `GET /api/weather/params` answers with, or null for a 404. */
    fun savedParamsJson(theaterId: String?, name: String?): String? = savedParams(theaterId, name)?.let {
        settingsJson.encodeToString(com.bmscompanion.app.ui.screens.editor.WxGenSaved.serializer(), it)
    }

    /** Puts one listed file back the way it was: the original over it, or gone if there was none. */
    private fun undo(campaign: File, e: Written): String? = attempt({ it }) {
        val target = File(campaign, e.path)
        if (e.added) {
            if (target.exists() && !target.delete()) return@attempt "${target.path} could not be deleted."
        } else {
            val orig = File(backupDir(campaign), e.path)
            if (!orig.isFile) return@attempt "The original of ${e.path} is not in the backup folder."
            writeSafely(target, orig.readBytes())
        }
        null
    }

    private val NAME_RULE = "Give the map a name of letters, digits, spaces, - and _ (at most 40)."

    private fun cleanName(name: String?): String? {
        val n = name?.trim().orEmpty().removePrefix(PREFIX).removeSuffix(".fmap").trim()
        return n.takeIf { it.isNotEmpty() && it.length <= 40 && it.all { c -> c.isLetterOrDigit() || c == ' ' || c == '-' || c == '_' } }
    }

    /**
     * The map a generated one takes its version and grid size from ([templateFile]), or — in a campaign folder with no
     * version 5 map at all (Hellas) — an empty 59 x 59 version 5 map, BMS's grid in every 4.38.1 theater
     * ([Fmap.blank]). Nothing else of it is used: [Fmap.generated] writes every array and every header field.
     */
    private fun template(campaign: File): Fmap =
        templateFile(campaign)?.let { Fmap.read(it) }?.takeIf { it.writable } ?: Fmap.blank()

    /**
     * The file [template] takes the grid from, or null for the built-in one: the untouched copy of one of the four
     * ready-made maps if there is one, else the live file, else any other version 5 map in the campaign folder (Hellas
     * WCP ships only its SAT maps; BMS's own before this program's) or in its `WeatherMapsUpdates` (LHTO ships only
     * those). Only each file's first twelve bytes and its length are read.
     */
    private fun templateFile(campaign: File): File? {
        Model.entries.flatMap { listOf(original(campaign, it), live(campaign, it)) }.firstOrNull { version5(it) }?.let { return it }
        fun maps(dir: File) = attempt({ emptyList<File>() }) {
            (dir.listFiles { f -> f.isFile && f.name.endsWith(".fmap", true) } ?: emptyArray()).sortedBy { it.name.lowercase() }
        }
        val here = maps(campaign).sortedBy { it.name.startsWith(PREFIX, ignoreCase = true) }
        // a folder of a few hundred maps that are all some other version is not read to the end
        return (here.take(20) + maps(File(campaign, UPDATES)).take(20)).firstOrNull { version5(it) }
    }

    /** A version 5 map whose length agrees with its grid, from its header alone. */
    private fun version5(f: File): Boolean = attempt({ false }) {
        if (!f.isFile || f.length() < Fmap.HEADER + 4) return@attempt false
        val head = ByteArray(12)
        java.io.RandomAccessFile(f, "r").use { it.readFully(head) }
        val b = java.nio.ByteBuffer.wrap(head).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val cols = b.getInt(4)
        val rows = b.getInt(8)
        b.getInt(0) == Fmap.VERSION && cols in 1..1024 && rows in 1..1024 &&
            f.length() == Fmap.HEADER + Fmap.Field.COUNT.toLong() * cols * rows * 4
    }

    private fun mismatch(template: Fmap, p: WxGenParams): String? =
        if (template.cols == p.cols && template.rows == p.rows) null
        else "This theater's weather grid is ${template.cols} x ${template.rows}, and the weather was made for ${p.cols} x ${p.rows}."

    private fun build(template: Fmap, p: WxGenParams): ByteArray? =
        Fmap.generated(template, WxGenModel.grid(p), p)?.toBytes()

    /**
     * Writes one file outside the four ready-made maps, listing it first.
     *
     * A file already there that this program did not write is either one of BMS's update maps — copied into the
     * backup once, then written over — or anything else, which is refused: a generated map must never take the
     * place of a file this program cannot put back.
     */
    private fun place(campaign: File, rel: String, bytes: ByteArray): String? = attempt({ it }) {
        val target = File(campaign, rel)
        val man = manifest(campaign)
        if (man[rel.lowercase()] == null) {
            if (target.exists()) {
                if (!rel.startsWith("$UPDATES/")) {
                    return@attempt "There is already a file called ${target.name}, and BMS Companion did not write it. Choose another name."
                }
                val copy = File(backupDir(campaign), rel)
                if (!copy.isFile) {
                    copy.parentFile?.let { if (!it.isDirectory && !it.mkdirs()) return@attempt "The folder ${it.path} could not be made." }
                    copySafely(target, copy)
                }
                man[rel.lowercase()] = Written(rel, added = false)
            } else {
                man[rel.lowercase()] = Written(rel, added = true)
            }
            saveManifest(campaign, man)
        }
        writeSafely(target, bytes)
        null
    }

    // ---------------------------------------------------------------- the manifest

    private class Written(val path: String, val added: Boolean)

    private fun manifestFile(campaign: File) = File(backupDir(campaign), MANIFEST)

    /** Keyed by the lower-cased path, because Windows is not case sensitive and the pilot may rename by hand. */
    private fun manifest(campaign: File): LinkedHashMap<String, Written> {
        val out = LinkedHashMap<String, Written>()
        val f = manifestFile(campaign)
        if (!f.isFile) return out
        runCatching {
            f.readLines().forEach { line ->
                val kind = line.substringBefore('\t').trim()
                val path = line.substringAfter('\t', "").trim().replace('\\', '/')
                if (path.isEmpty() || (kind != "added" && kind != "replaced")) return@forEach
                out[path.lowercase()] = Written(path, added = kind == "added")
            }
        }
        return out
    }

    private fun saveManifest(campaign: File, man: Map<String, Written>) {
        // nothing listed and nothing to list: no reason to make a backup folder just to say so
        if (man.isEmpty() && !manifestFile(campaign).isFile) return
        val text = buildString {
            appendLine("# Files BMS Companion wrote in the folder above this one. See README.txt.")
            appendLine("# added     = there was no such file before: delete it to undo")
            appendLine("# replaced  = one of Falcon BMS's own: its original is in this folder, at the same path")
            for (e in man.values) appendLine((if (e.added) "added" else "replaced") + "\t" + e.path)
        }
        writeSafely(manifestFile(campaign), text.toByteArray())
    }

    // ---------------------------------------------------------------- doing it safely

    /** Appends one line to the note that lives with the backup. Losing it costs nothing; it is a record, not state. */
    private fun record(campaign: File, what: String) = attempt<Unit>({ }) {
        val f = File(backupDir(campaign), RECORD)
        val stamp = java.time.LocalDateTime.now().withNano(0).toString().replace('T', ' ')
        f.appendText("$stamp  $what\n")
    }

    /**
     * Writes somewhere else first and moves it into place.
     *
     * Writing over a file truncates it before the new bytes go in, so a write that fails half way — a full disk, a
     * drive pulled out, the machine going down — would leave Falcon BMS with half a weather map. The move is the
     * only moment the real file changes, and it either happens or it does not.
     */
    private fun writeSafely(target: File, bytes: ByteArray) {
        // Windows names a moved file as the move says, so writing "SUNNY.fmap" over BMS's "Sunny.fmap" would rename
        // it; the file's own name on disk is kept, so a restore gives back the very same file, name included
        val file = if (target.exists()) target.canonicalFile else target
        val tmp = File(file.parentFile, file.name + ".bmsc-new")
        try {
            tmp.writeBytes(bytes)
            val from = tmp.toPath()
            val to = file.toPath()
            try {
                java.nio.file.Files.move(
                    from, to,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                java.nio.file.Files.move(from, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            runCatching { if (tmp.exists()) tmp.delete() }
        }
    }

    /** The backup copy, written the same careful way and then read back before it is trusted. */
    private fun copySafely(from: File, to: File) {
        val bytes = from.readBytes()
        writeSafely(to, bytes)
        if (!to.isFile || to.length() != from.length()) error("The copy of ${from.name} did not come out the right size.")
    }

    /**
     * Runs [body], and turns anything it throws into a plain sentence.
     *
     * Falcon BMS is often installed in Program Files or on a read-only drive, and a pilot needs to be told that
     * rather than left pressing a button that does nothing.
     */
    private fun <T> attempt(onError: (String) -> T, body: () -> T): T = try {
        body()
    } catch (e: Throwable) {
        onError(reason(e))
    }

    private fun reason(e: Throwable): String = when (e) {
        is java.nio.file.AccessDeniedException ->
            "Windows would not let BMS Companion write to ${e.file}. Falcon BMS is usually installed somewhere that " +
                "needs administrator rights: start BMS Companion as administrator, or install BMS somewhere else."
        is java.io.FileNotFoundException -> "${e.message} could not be opened."
        else -> e.message ?: e::class.java.simpleName
    }
}
