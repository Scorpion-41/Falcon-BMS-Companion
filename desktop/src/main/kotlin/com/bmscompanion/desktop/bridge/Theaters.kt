package com.bmscompanion.desktop.bridge

import java.io.File

/**
 * Falcon BMS's theaters as BMS itself defines them: `Data/TerrData/TheaterDefinition/theater.lst` names one `.tdf` per
 * line (a path under `Data`, the base theater first), and each `.tdf` gives the theater's `name`, its `campaigndir`
 * (where its campaigns, TEs and trainings are), its `objectdir` (its class tables) and its `3ddatadir` (where the
 * cockpit's kneeboard pages are read from).
 *
 * Everything that has to find a theater's files asks here — the campaign browser, the planned tracks, the team
 * table, the weather maps and the kneeboard printer — because guessing from folder names misses too much: the six
 * theaters of `Add-On Korea 2012` keep their campaigns two levels down, `Add-On LKTO` has two campaign folders
 * (`Campaign` and `Campaign+`), a campaign pack such as Hellas WCP borrows another theater's objects, and LHTO, EF2000
 * and half the Korean add-ons read their kneeboard pages from another theater's folder. Names are written freely:
 * "Carrier War " and "Korea 2012  " carry trailing spaces, and folders differ in case (`Add-on KTO 80s…`,
 * `Add-On Israel\campaign`), so names are trimmed and every path is matched without regard to case.
 *
 * Read only, and nothing throws: a missing list or an unreadable definition is an [TheaterSet.error], not an exception.
 * The set is cached per BMS folder and read again when `theater.lst` or any definition changes.
 */
object Theaters {
    /** How long a save must have been left alone before it is read: BMS may still be writing it. */
    const val SETTLE_MS = 1500L

    /** One theater, as its definition gives it. Paths are as the `.tdf` writes them, relative to `Data`. */
    data class Theater(
        /** the `name` line, trimmed ("Carrier War") */
        val name: String,
        /** the `name` line as written ("Carrier War ") */
        val rawName: String,
        /** the line of `theater.lst` that names it, relative to `Data` */
        val listed: String,
        /** position in `theater.lst`: 0 is the base theater */
        val index: Int,
        val tdf: File,
        val campaignDir: String?,
        val objectDir: String?,
        val threeDDataDir: String?,
        val terrainDir: String?,
        val artDir: String?,
    ) {
        /** the app's own theater id (`data/index.json`), made from the name the way the extractor makes it */
        val appId: String get() = slug(name)
    }

    /** Every theater of one BMS folder. [error] says why the list is short or empty. */
    class TheaterSet(val root: File, val data: File, val theaters: List<Theater>, val error: String?) {
        val all: List<Theater> get() = theaters

        /** The theater named [name]: trimmed and compared without case; the app's id works too. */
        fun byName(name: String?): Theater? {
            val want = name?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return theaters.firstOrNull { it.name.equals(want, ignoreCase = true) }
                ?: theaters.firstOrNull { it.appId == want.lowercase() }
                ?: theaters.firstOrNull { it.appId == slug(want) }
        }

        /** The theater BMS is set to: the registry's `curTheater` (or shared memory's name), else the base theater. */
        fun current(curTheater: String?): Theater? = byName(curTheater)

        fun campaignDir(t: Theater?): File? = t?.campaignDir?.let { resolveDir(data, it) }
        fun objectDir(t: Theater?): File? = t?.objectDir?.let { resolveDir(data, it) }
        fun threeDDataDir(t: Theater?): File? = t?.threeDDataDir?.let { resolveDir(data, it) }

        /** `<3ddatadir>/KoreaObj`, where the F-16's kneeboard pages 7982-7997 are. */
        fun kneeboardDir(t: Theater?): File? = threeDDataDir(t)?.let { resolveDir(it, "KoreaObj") }

        /** `<3ddatadir>/KoreaObj_HiRes`, the twin some theaters keep. */
        fun kneeboardHiResDir(t: Theater?): File? = threeDDataDir(t)?.let { resolveDir(it, "KoreaObj_HiRes") }

        /** The F-16 kneeboard page files that exist for [t]: page n (1-16) is `798{1+n}.dds`. */
        fun kneeboardPages(t: Theater?): List<File> {
            val dir = kneeboardDir(t) ?: return emptyList()
            // KoreaObj holds thousands of files: ask for the sixteen by name (Windows matches without case)
            return (7982..7997).mapNotNull { n -> File(dir, "$n.dds").takeIf { it.isFile } }
        }

        /**
         * One class table ([file], e.g. `Falcon4_CT.xml`) for [t]: from its `objectdir` when that folder has it, else
         * from `Data/TerrData/Objects`. Per file, because a theater may ship only some of the four (Korea TvT has two).
         */
        fun classFile(t: Theater?, file: String): File? {
            val places = listOfNotNull(objectDir(t), resolveDir(data, "TerrData\\Objects"))
            for (dir in places) listing(dir).firstOrNull { it.isFile && it.name.equals(file, true) }?.let { return it }
            return null
        }

        /**
         * The campaign string table (`Strings.txt`) of [t]: its `campaigndir`'s own, else `Data/Campaign`'s. A line is a
         * number, a run of whitespace, and the text — split on the first run, tab or space, because some lines use a
         * space ("341 RELOCATE") and splitting on tabs alone lost them.
         */
        fun strings(t: Theater?): Map<Int, String> {
            val file = stringsFile(t) ?: return emptyMap()
            val key = "${file.path}|${file.lastModified()}|${file.length()}"
            stringCache[key]?.let { return it }
            val map = HashMap<Int, String>()
            attempt(Unit) {
                file.readLines(Charsets.ISO_8859_1).forEach { raw ->
                    val line = raw.trimStart()
                    val cut = line.indexOfFirst { it == ' ' || it == '\t' }
                    if (cut <= 0) return@forEach
                    val id = line.substring(0, cut).toIntOrNull() ?: return@forEach
                    val text = line.substring(cut).trim()
                    if (text.isNotEmpty()) map[id] = text
                }
            }
            stringCache[key] = map
            return map
        }

        fun stringsFile(t: Theater?): File? =
            listOfNotNull(campaignDir(t), resolveDir(data, "Campaign"))
                .firstNotNullOfOrNull { dir -> listing(dir).firstOrNull { it.isFile && it.name.equals("Strings.txt", true) } }

        /** The campaigns, TEs and trainings directly in [t]'s `campaigndir` (never its subfolders), newest first. */
        fun saves(t: Theater?, withTraining: Boolean = true): List<File> =
            campaignDir(t)?.let { dir ->
                listing(dir).filter {
                    it.isFile && (it.name.endsWith(".cam", true) || it.name.endsWith(".tac", true) ||
                        (withTraining && it.name.endsWith(".trn", true)))
                }.sortedByDescending { it.lastModified() }
            }.orEmpty()

        /**
         * The save BMS most likely wrote last for [t]: the newest `.cam` or `.tac` directly in its `campaigndir` that has
         * settled ([SETTLE_MS]) and is not a start. A start (a campaign start, `Instant`, a TE template) holds the
         * objectives (`.obj`) and no flights, so it can never be the mission being flown; it is told by its structure,
         * not by its name. Null when there is none.
         */
        fun newestSave(t: Theater?, now: Long = System.currentTimeMillis()): File? =
            saves(t, withTraining = false).firstOrNull { f -> now - f.lastModified() >= SETTLE_MS && !isStart(f) }

        /** The theater whose `campaigndir` is [dir] (compared without case), for a file found some other way. */
        fun owning(dir: File): Theater? {
            val want = canonical(dir)
            return theaters.firstOrNull { t -> campaignDir(t)?.let { canonical(it) == want } == true }
        }
    }

    // ---------------------------------------------------------------- the cache

    private val sets = HashMap<String, Pair<String, TheaterSet>>()
    private val stringCache = java.util.concurrent.ConcurrentHashMap<String, Map<Int, String>>()
    private val startCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /** The theaters of the BMS folder [root] (the folder holding `Data`). */
    @Synchronized
    fun at(root: File): TheaterSet {
        val data = resolveDir(root, "Data") ?: File(root, "Data")
        val lst = resolveFile(data, "TerrData\\TheaterDefinition\\theater.lst")
        // cheap to work out, and it moves whenever the list or a definition does
        val stamp = buildString {
            append(lst?.lastModified() ?: 0L)
            lst?.let { l -> readLines(l).forEach { line -> resolveFile(data, line)?.let { append('|').append(it.lastModified()) } } }
        }
        val key = canonical(root)
        sets[key]?.let { (s, set) -> if (s == stamp) return set }
        val set = read(root, data, lst)
        sets[key] = stamp to set
        return set
    }

    /** The theaters of the BMS folder [install] points at (the settings' override, else the registry's), or null. */
    fun of(install: BmsInstall): TheaterSet? = install.baseDir?.let { at(File(it)) }

    fun all(install: BmsInstall): List<Theater> = of(install)?.all.orEmpty()

    /** The theater BMS is set to (the registry's `curTheater`). */
    fun current(install: BmsInstall): Theater? = of(install)?.current(install.theater)

    fun campaignDir(install: BmsInstall, name: String?): File? = of(install)?.let { it.campaignDir(it.byName(name)) }
    fun threeDDataDir(install: BmsInstall, name: String?): File? = of(install)?.let { it.threeDDataDir(it.byName(name)) }
    fun classFile(install: BmsInstall, name: String?, file: String): File? = of(install)?.let { it.classFile(it.byName(name), file) }
    fun strings(install: BmsInstall, name: String?): Map<Int, String> = of(install)?.let { it.strings(it.byName(name)) }.orEmpty()
    fun newestSave(install: BmsInstall, name: String?): File? = of(install)?.let { it.newestSave(it.byName(name)) }

    private fun read(root: File, data: File, lst: File?): TheaterSet {
        if (!data.isDirectory) return TheaterSet(root, data, emptyList(), "There is no Data folder in ${root.path}.")
        if (lst == null) {
            // no list: the base theater's own definition is all there is
            val base = resolveDir(data, "TerrData\\TheaterDefinition")?.let { listing(it) }.orEmpty()
                .filter { it.isFile && it.name.endsWith(".tdf", true) }
            val theaters = base.mapIndexedNotNull { i, f -> parse(f, "TerrData\\TheaterDefinition\\${f.name}", i) }
            return TheaterSet(root, data, theaters, "Falcon BMS's theater list (Data\\TerrData\\TheaterDefinition\\theater.lst) was not found.")
        }
        val missing = ArrayList<String>()
        val theaters = ArrayList<Theater>()
        readLines(lst).forEach { line ->
            val tdf = resolveFile(data, line)
            if (tdf == null) { missing += line; return@forEach }
            parse(tdf, line, theaters.size)?.let { theaters += it } ?: run { missing += line }
        }
        val error = if (missing.isEmpty()) null else "Theater definitions not found or not readable: " + missing.joinToString("; ")
        return TheaterSet(root, data, theaters, error)
    }

    /** The `.tdf` paths `theater.lst` lists, in order: blank lines and comments skipped. */
    private fun readLines(lst: File): List<String> = attempt(emptyList()) {
        lst.readLines(Charsets.ISO_8859_1).map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("//") && !it.startsWith(";") }
    }

    /** One definition: `key value` lines, the key without case, the first of each kept. */
    private fun parse(tdf: File, listed: String, index: Int): Theater? = attempt(null) {
        val keys = HashMap<String, String>()
        tdf.readLines(Charsets.ISO_8859_1).forEach { raw ->
            val line = raw.trimStart()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//") || line.startsWith(";")) return@forEach
            val cut = line.indexOfFirst { it == ' ' || it == '\t' }
            if (cut <= 0) return@forEach
            val key = line.substring(0, cut).lowercase()
            if (key !in keys) keys[key] = line.substring(cut + 1).trimStart(' ', '\t')
        }
        val raw = keys["name"] ?: tdf.nameWithoutExtension
        fun dir(k: String) = keys[k]?.trim()?.trim('"')?.takeIf { it.isNotEmpty() }
        Theater(
            name = raw.trim(), rawName = raw, listed = listed, index = index, tdf = tdf,
            campaignDir = dir("campaigndir"), objectDir = dir("objectdir"), threeDDataDir = dir("3ddatadir"),
            terrainDir = dir("terraindir"), artDir = dir("artdir"),
        )
    }

    // ---------------------------------------------------------------- starts

    /**
     * A start (campaign start, `Instant`, TE template): the archive holds an objectives part (`.obj`). Only the
     * archive's own directory is read. Cached by path, size and time; an unreadable file counts as a start, so it is
     * never taken for the mission being flown.
     */
    fun isStart(f: File): Boolean {
        val key = "${f.path}|${f.length()}|${f.lastModified()}"
        startCache[key]?.let { return it }
        val start = attempt(true) {
            val parts = MissionArchive.parts(f.readBytes())
            parts.isEmpty() || parts.any { it.name.endsWith(".obj", true) }
        }
        startCache[key] = start
        return start
    }

    // ---------------------------------------------------------------- paths without regard to case

    /** [rel] (either slash) under [base], each part matched without case; null when a part is missing. */
    fun resolve(base: File, rel: String): File? = attempt(null) {
        var at = base
        for (part in rel.replace('/', '\\').split('\\').map { it.trim() }.filter { it.isNotEmpty() && it != "." }) {
            if (part == "..") return@attempt null
            val exact = File(at, part)
            at = if (exact.exists()) exact
            else listing(at).firstOrNull { it.name.equals(part, ignoreCase = true) } ?: return@attempt null
        }
        // the name as it is on disk (and a long name for an 8.3 one)
        at.canonicalFile
    }

    fun resolveDir(base: File, rel: String): File? = resolve(base, rel)?.takeIf { it.isDirectory }
    fun resolveFile(base: File, rel: String): File? = resolve(base, rel)?.takeIf { it.isFile }

    private fun listing(dir: File): List<File> = attempt(emptyList()) { dir.listFiles()?.toList().orEmpty() }

    fun canonical(f: File): String = attempt(f.absolutePath) { f.canonicalPath }.trimEnd('\\', '/').lowercase()

    /** The extractor's `slug` (tools/extractor/src/util.mjs): the app's theater ids are made this way. */
    fun slug(s: String): String =
        s.lowercase().replace("+", "plus").replace(Regex("[^a-z0-9]+"), "-").trim('-')

    private inline fun <T> attempt(fallback: T, body: () -> T): T = try { body() } catch (_: Throwable) { fallback }
}
