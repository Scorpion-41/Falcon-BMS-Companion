package com.bmscompanion.desktop.bridge

import com.bmscompanion.app.data.mission.PcFileData
import com.bmscompanion.app.data.mission.PcFileEntry
import com.bmscompanion.app.data.mission.PcFileWrite
import com.bmscompanion.app.data.mission.PcFolder
import com.bmscompanion.app.data.mission.PcPicture
import com.bmscompanion.app.data.mission.PcPlace
import com.bmscompanion.app.data.mission.PcPlaces
import com.bmscompanion.app.data.mission.PcPlannerFolders
import com.bmscompanion.app.data.mission.PcWeather
import com.bmscompanion.app.data.weather.CampaignTime
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.KnownFolders
import com.sun.jna.platform.win32.Shell32Util
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File
import java.nio.file.AccessDeniedException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.DosFileAttributes
import java.util.Base64
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * `/api/files/…`: the BMS PC's own disks, for the Planner's file windows on any device (docs/PROTOCOL.md, "Files on
 * the BMS PC"). Weapon Delivery Planner opens and saves its files through Windows' file dialogs; on the PC's own window
 * the Planner shows that same dialog ([com.bmscompanion.desktop.PcFileDialogs]), and everywhere else it lists the PC's
 * folders through these routes and has the PC read and write the file it picked.
 *
 * - `GET /api/files/places` → `PcPlaces`: the Planner's folders that exist here and the drives
 * - `GET /api/files/list?path=&ext=` → `PcFolder`: one folder's folders and its files of those types
 * - `GET /api/files/stat?path=` → `PcFileEntry` (`exists` false for a name that is not there yet)
 * - `GET /api/files/read?path=` → `PcFileData` (base64)
 * - `POST /api/files/write?path=&overwrite=0|1`, body `PcFileWrite` → `PcFileEntry`
 * - `POST /api/files/mkdir?path=` → `PcFileEntry`: makes the folder and any missing above it, as WDP makes its
 *   `DataCards\<mission>\<package>\<callsign>` and `Files\<part>` folders before it opens a file window there
 * - `GET /api/files/weather?path=&clock=&save=` → `PcWeather`: an `.fmap` or a `.twx` read for Reload WX, or a save's
 *   own `.twx` for the Planner's card, checked against that save (`save=`) ([weather])
 * - `GET /api/files/picture?path=&max=` → `PcPicture`: a `.jpg`, `.png`, `.bmp` or `.dds` as a PNG any device decodes,
 *   for Upd Kneeboard's Browse picture… ([picture])
 * - `GET /api/files/planner` → `PcPlannerFolders`: the Planner's own folder in the BMS install and its DataCards folder;
 *   `POST /api/files/planner?datacards=` sets the DataCards folder (blank: back to the default) ([plannerFolders])
 *
 * **What keeps it safe on a LAN.** A path is a full path on one of the PC's own drives (`C:\…`) or a place (`@config`,
 * `@wdp\DataCards`, …): never a network share (`\\server\…`, which would make the PC log on to whatever machine a
 * client named), a device path (`\\?\`, `\\.\`), an alternate data stream or a reserved device name (`CON`, `NUL`…),
 * and `..` is resolved before anything is looked at. **Any folder may be listed** (hidden and system items left out),
 * but **only the file types the Planner uses are read** ([READ_TYPES]) **and fewer are written** ([WRITE_TYPES]):
 * never a program or a script. Falcon BMS's own data files the Planner only reads — the weather maps, the config
 * files, the `.dat` tables, the textures — are read but never written here: the Weather and Config pages write those,
 * under their own rules. A write goes through a temporary file beside the target and one move, so a failed write
 * leaves the old file as it was; an existing file is replaced only when the client says so (`overwrite=1`, after the
 * pilot answered the window's question), and a developer run never writes into a BMS folder ([DevGuard]).
 *
 * **Nothing here throws.** A request that cannot be answered gets `{"error": "<sentence>"}`: 400 for one the PC will
 * not take (a path of the wrong shape, a type it does not read or write), 404 for a file that is not there, 409 for the
 * PC's present state (a file already there, a folder that does not answer, Windows refusing a write).
 */
object PcFileRoutes {
    /** The routes this object answers. */
    val ROUTES = listOf(
        "GET /api/files/places", "GET /api/files/list", "GET /api/files/stat", "GET /api/files/read", "POST /api/files/write",
        "POST /api/files/mkdir", "GET /api/files/weather", "GET /api/files/picture", "GET /api/files/planner",
        "POST /api/files/planner",
    )

    /**
     * What these routes write: what Weapon Delivery Planner's own Save dialogs write. `ini` (Callsign.ini, a TE's
     * mission file, Codewords.ini, PackageTiming.ini, PPT.ini), `ppi` (personal PPTs), `bdc` (a backup DataCard), the
     * DTC page's backup files (`pth` PPTs, `lns` lines, `tgt` targets, `opn` open steerpoints, `hpn` Harpoon, `plf`
     * PPTs and lines, `ews`, `mfd`, `rad` radios, `sts` systems, `wpn` weapons, `hrm` HARM), and the pictures it saves
     * (`jpg`, `png`: the attack maps, the airport schedule, the parking chart, the DataCard's picture), plus `txt`.
     */
    val WRITE_TYPES: Set<String> = setOf(
        "ini", "ppi", "bdc",
        "pth", "lns", "tgt", "opn", "hpn", "plf", "ews", "mfd", "rad", "sts", "wpn", "hrm",
        "txt", "jpg", "jpeg", "png",
    )

    /**
     * What these routes read: everything they write, and what the Planner only ever reads — BMS's weather (`fmap`,
     * `twx`), its config files (`cfg`), its data tables (`dat`, `dcd`, `lst`) and pictures (`bmp`, `dds`: the kneeboard's
     * Open picture).
     */
    val READ_TYPES: Set<String> = WRITE_TYPES + setOf("fmap", "twx", "cfg", "dat", "dcd", "lst", "bmp", "dds")

    /** The largest file read or written in one answer. */
    const val MAX_BYTES = 32L * 1024 * 1024

    /** How long a folder may take to list (a network drive that is not connected can take half a minute). */
    private const val LIST_MS = 12_000L

    fun handle(req: ApiRequest): ApiResponse {
        val path = req.path.trimEnd('/')
        if (path !in listOf(
                "/api/files/places", "/api/files/list", "/api/files/stat", "/api/files/read", "/api/files/write", "/api/files/mkdir",
                "/api/files/weather", "/api/files/picture", "/api/files/planner",
            )
        ) {
            return ApiResponse.notFound()
        }
        val method = if (path == "/api/files/write" || path == "/api/files/mkdir") "POST" else "GET"
        if (path == "/api/files/planner") {
            if (req.method != "GET" && req.method != "POST") return error("Only GET and POST are answered on $path.", 405)
        } else if (req.method != method) return error("Only $method is answered on $path.", 405)
        return try {
            when (path) {
                "/api/files/planner" -> if (req.method == "POST") setDataCards(req.query["datacards"]) else encode(PcPlannerFolders.serializer(), plannerFolders())
                "/api/files/places" -> encode(PcPlaces.serializer(), places())
                "/api/files/list" -> list(req.query["path"], req.query["ext"])
                "/api/files/stat" -> stat(req.query["path"])
                "/api/files/read" -> read(req.query["path"])
                "/api/files/mkdir" -> mkdir(req.query["path"])
                "/api/files/weather" -> weather(req.query["path"], req.query["clock"], req.query["save"])
                "/api/files/picture" -> picture(req.query["path"], req.query["max"])
                else -> write(req.query["path"], req.query["overwrite"], req.body, req.remote)
            }
        } catch (e: Throwable) {
            BridgeLog.warn("$path: ${e.message}")
            error("The PC could not do that: ${reason(e)}", 409)
        }
    }

    // ---------------------------------------------------------------- where things are

    /**
     * The folder a place names (`bms`, `config`, `campaign`, `datacampaign`, `wdp`, `planner`, `datacards`,
     * `documents`, `desktop`, `downloads`), whether or not it exists; null when this PC has no such place (no BMS
     * folder, WDP not found). `campaign` is the campaign folder of the theater BMS is set to; `campaign:<theater>` is
     * that theater's own (its name as its definition gives it, or the app's id: "campaign:Korea KTO"), through
     * [Theaters] — where a save opened from another theater keeps its companion files, its `.twx` among them.
     *
     * `planner` is where the Planner keeps what WDP keeps in its own program folder (its `Files\EWS`, `Files\PPT`,
     * `SavedMaps`, `DataCards`…): its own folder **inside the BMS install**, `<BMS>\User\BMS Companion Planner`
     * ([PLANNER_FOLDER], laid out as WDP's, [SKELETON]) — never WDP's folder, whose files stay WDP's, and not
     * `User\Config`, which is BMS's. `datacards` is the DataCards folder: the one the pilot chose
     * ([BridgeSettings.PlannerDataCardDir], WDP's Settings → DataCard directory), else `DataCards` in the Planner's
     * folder. Both are made the first time a file window needs them ([mkdir], [preparePlanner]).
     */
    fun placeDir(key: String): File? {
        val install = Bridge.install
        val base = install.baseDir?.let(::File)
        if (key.startsWith("campaign:", ignoreCase = true)) {
            val name = key.substringAfter(':').trim()
            return quiet { Theaters.of(install)?.let { set -> set.byName(name)?.let { set.campaignDir(it) } } }
        }
        return when (key.lowercase()) {
            "bms" -> base
            "config" -> install.configDir?.let(::File)
            "campaign" -> quiet { Theaters.of(install)?.let { set -> set.campaignDir(set.current(install.theater)) } }
                ?: base?.let { File(File(it, "Data"), "Campaign") }
            "datacampaign" -> base?.let { File(File(it, "Data"), "Campaign") }
            "wdp" -> quiet { WdpRemote.root(Bridge.settings.value, install) }
            "planner" -> base?.let { File(File(it, "User"), PLANNER_FOLDER) }
            "datacards" -> chosenDataCards() ?: placeDir("planner")?.let { File(it, "DataCards") }
            "documents" -> known("documents")
            "desktop" -> known("desktop")
            "downloads" -> known("downloads")
            else -> null
        }
    }

    /** The Planner's folder in the BMS install's `User` folder. */
    const val PLANNER_FOLDER = "BMS Companion Planner"

    /**
     * The folders of WDP's program folder that the Planner writes into, by WDP's own names (`CheckForDir` before each
     * of its file windows): made together the first time the Planner's folder is, so it is laid out as WDP's. WDP's
     * other folders hold its own program files (charts, maps, pictures, textures) and the Planner has no use for them.
     */
    val SKELETON = listOf(
        "DataCards", "DataCards\\PlanPic", "SavedMaps",
        "Files\\EWS", "Files\\Harm", "Files\\Harpoon", "Files\\Line", "Files\\MFD", "Files\\Open", "Files\\PPT",
        "Files\\PPT\\Personal", "Files\\Radio", "Files\\System", "Files\\Target", "Files\\Weapons",
    )

    /**
     * Where an earlier 1.3.8 test build kept the Planner's files when WDP was not on the PC, `Documents\BMS Companion
     * Planner`: copied once into the Planner's folder when that is first made ([preparePlanner]), never moved or
     * deleted. A check points it at a scratch folder.
     */
    @Volatile internal var legacyPlanner: () -> File? = { known("documents")?.let { File(it, PLANNER_FOLDER) } }

    /** The DataCards folder the pilot chose ([BridgeSettings.PlannerDataCardDir]), when there is one and it is a local path. */
    private fun chosenDataCards(): File? {
        val raw = Bridge.settings.value.PlannerDataCardDir?.trim()?.trimEnd('\\')?.takeIf { it.isNotEmpty() } ?: return null
        // a drive's root ("D:") is "D:\", which is absolute; "D:" alone is the drive's current folder
        val s = if (raw.length == 2 && raw[1] == ':') "$raw\\" else raw
        return quiet { File(s).takeIf { it.isAbsolute && DRIVE.containsMatchIn(it.path + "\\") } }
    }

    /** The Planner's folders as a device's Settings window shows them (`GET /api/files/planner`). */
    fun plannerFolders(): PcPlannerFolders {
        val planner = placeDir("planner")
        val cards = placeDir("datacards")
        return PcPlannerFolders(
            planner = planner?.path.orEmpty(), plannerExists = planner?.let(::isDir) == true,
            dataCards = cards?.path.orEmpty(), dataCardsDefault = planner?.let { File(it, "DataCards").path }.orEmpty(),
            dataCardsSet = chosenDataCards() != null, dataCardsExists = cards?.let(::isDir) == true,
        )
    }

    /**
     * `POST /api/files/planner?datacards=`: the DataCards folder, as WDP's Settings sets its DataCard directory — a full
     * path on one of the PC's own drives (or a place), not a file; blank puts back the default. The folder need not be
     * there yet: it is made the first time a file window opens in it. Only the setting is written here.
     */
    private fun setDataCards(raw: String?): ApiResponse {
        val want = raw?.trim().orEmpty()
        if (want.isEmpty()) {
            Bridge.update(reapply = false) { it.copy(PlannerDataCardDir = null) }
            BridgeLog.info("files: the DataCards folder is the default again")
            return encode(PcPlannerFolders.serializer(), plannerFolders())
        }
        val f = when (val t = resolve(want)) {
            is Target.Bad -> return error(t.why, 400)
            is Target.Ok -> t.file
        }
        if (isFile(f)) return error("${f.path} is a file, not a folder.", 400)
        // the Planner writes only into its own folder inside Falcon BMS, and never into WDP's (the ground rules)
        fun under(a: File, b: File?): Boolean {
            val x = a.path.trimEnd('\\').lowercase()
            val y = b?.path?.trimEnd('\\')?.lowercase()?.takeIf { it.isNotEmpty() } ?: return false
            return x == y || x.startsWith("$y\\")
        }
        val planner = placeDir("planner")
        if (under(f, placeDir("bms")) && !under(f, planner))
            return error("${f.path} is inside Falcon BMS: the DataCards folder can be anywhere else, or inside ${planner?.path ?: "the Planner's own folder"}.", 400)
        if (under(f, quiet { placeDir("wdp") }))
            return error("${f.path} is inside Weapon Delivery Planner's own folder, which the Planner never writes into: choose another folder.", 400)
        val default = placeDir("planner")?.let { File(it, "DataCards") }
        val chosen = f.path.trimEnd('\\').takeUnless { default != null && it.equals(default.path, ignoreCase = true) }
        Bridge.update(reapply = false) { it.copy(PlannerDataCardDir = chosen) }
        BridgeLog.info("files: the DataCards folder is ${f.path}")
        return encode(PcPlannerFolders.serializer(), plannerFolders())
    }

    /**
     * The first time the Planner's folder is made ([mkdir] of it or of anything in it, while it is not there): the files
     * an earlier build kept in [legacyPlanner] are copied in (a file already there is kept, nothing is moved or
     * deleted), and WDP's folders are made ([SKELETON]). Nothing here throws; what could not be done is logged.
     */
    @Synchronized
    internal fun preparePlanner(root: File) {
        // two devices asking at once: the second finds the folder made, and copies nothing again
        if (isDir(root) && SKELETON.all { isDir(File(root, it)) }) return
        val old = quiet { legacyPlanner() }
        if (old != null && isDir(old) && !old.path.equals(root.path, ignoreCase = true)) {
            var copied = 0
            var failed = 0
            // a folder that cannot be read (permissions, a cloud placeholder) is counted and passed over, not the end
            // of the copy
            quiet {
                Files.walkFileTree(old.toPath(), object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
                    override fun preVisitDirectory(dir: java.nio.file.Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                        try { Files.createDirectories(root.toPath().resolve(old.toPath().relativize(dir).toString())) } catch (e: Throwable) { failed++ }
                        return java.nio.file.FileVisitResult.CONTINUE
                    }
                    override fun visitFile(file: java.nio.file.Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                        try {
                            val to = root.toPath().resolve(old.toPath().relativize(file).toString())
                            if (attrs.isRegularFile && !Files.exists(to)) {
                                Files.createDirectories(to.parent)
                                Files.copy(file, to, StandardCopyOption.COPY_ATTRIBUTES)
                                copied++
                            }
                        } catch (e: Throwable) { failed++ }
                        return java.nio.file.FileVisitResult.CONTINUE
                    }
                    override fun visitFileFailed(file: java.nio.file.Path, exc: java.io.IOException): java.nio.file.FileVisitResult {
                        failed++
                        return java.nio.file.FileVisitResult.CONTINUE
                    }
                })
            }
            if (copied + failed > 0) {
                val more = if (failed > 0) " ($failed could not be copied)" else ""
                BridgeLog.info("files: copied $copied file(s) from ${old.path} into ${root.path}$more")
            }
        }
        for (sub in SKELETON) quiet { Files.createDirectories(File(root, sub).toPath()) }
    }

    /** Windows' own folder for Documents, Desktop or Downloads (wherever the pilot moved it), or the usual place. */
    private fun known(which: String): File? {
        val id = when (which) {
            "documents" -> KnownFolders.FOLDERID_Documents
            "desktop" -> KnownFolders.FOLDERID_Desktop
            else -> KnownFolders.FOLDERID_Downloads
        }
        return quiet { Shell32Util.getKnownFolderPath(id)?.takeIf { it.isNotBlank() }?.let(::File) }
            ?: System.getProperty("user.home")?.let { File(it, which.replaceFirstChar { c -> c.uppercase() }) }
    }

    /** The Planner's folders that exist on this PC, in the order a file window offers them, and the drives. */
    fun places(): PcPlaces {
        val out = mutableListOf<PcPlace>()
        val seen = mutableSetOf<String>()
        fun add(name: String, dir: File?, kind: String, detail: String? = null) {
            if (dir == null || !isDir(dir)) return
            val key = dir.path.trimEnd('\\').lowercase()
            if (!seen.add(key)) return
            out += PcPlace(name, dir.path, kind, detail)
        }
        val install = Bridge.install
        val base = install.baseDir?.let(::File)
        add("Falcon BMS", base, "bms", base?.name)
        add("User\\Config", placeDir("config"), "config", "Cartridges, the pilot's settings")
        val theater = quiet { Theaters.current(install) }
        add("Campaign", placeDir("campaign"), "campaign", theater?.name?.let { "$it: saves, TEs, weather" } ?: "Saves, TEs, weather")
        add("Data\\Campaign", placeDir("datacampaign"), "datacampaign", "The base theater's campaign folder")
        add("BMS Companion Planner", placeDir("planner"), "planner", "In User: DataCards, PPTs, backups")
        add("DataCards", placeDir("datacards"), "datacards", "Backup DataCards, codewords, package timing")
        add("Weapon Delivery Planner", placeDir("wdp"), "wdp", "WDP's own folder")
        add("Documents", placeDir("documents"), "documents")
        add("Desktop", placeDir("desktop"), "desktop")
        add("Downloads", placeDir("downloads"), "downloads")
        return PcPlaces(out, drives(), READ_TYPES.sorted(), WRITE_TYPES.sorted())
    }

    /**
     * The PC's drives, each with Windows' name for it. A network drive, a CD and a removable drive are not asked for
     * their label: an empty card reader or a network drive that is not connected can keep the answer waiting.
     */
    private fun drives(): List<PcPlace> = File.listRoots().orEmpty().mapNotNull { r ->
        val root = r.path
        val letter = root.take(2).uppercase()
        val type = quiet { Kernel32.INSTANCE.GetDriveType(root) } ?: 3
        val kind = when (type) {
            2 -> "removable"
            4 -> "network"
            5 -> "cd"
            1 -> return@mapNotNull null   // no root: a letter with nothing behind it
            else -> "drive"
        }
        val label = if (kind == "drive") quiet {
            val name = CharArray(261)
            if (Kernel32.INSTANCE.GetVolumeInformation(root, name, name.size, null, null, null, null, 0)) String(name).substringBefore('\u0000').trim() else null
        }?.takeIf { it.isNotEmpty() } else null
        val name = when (kind) {
            "network" -> "Network drive ($letter)"
            "removable" -> "Removable drive ($letter)"
            "cd" -> "CD/DVD drive ($letter)"
            else -> "${label ?: "Local Disk"} ($letter)"
        }
        PcPlace(name, root, kind)
    }

    // ---------------------------------------------------------------- the paths a client sends

    /** A path from a client: the file it names, or the sentence saying why it is not taken. */
    sealed interface Target {
        class Ok(val file: File) : Target
        class Bad(val why: String) : Target
    }

    private val DRIVE = Regex("^[A-Za-z]:\\\\")
    private val RESERVED = Regex("^(CON|PRN|AUX|NUL|COM[0-9]|LPT[0-9]|CONIN\\$|CONOUT\\$)$", RegexOption.IGNORE_CASE)

    /**
     * [raw] as a file on one of this PC's drives: a place (`@config`, `@wdp\DataCards`) is resolved, `/` read as `\`,
     * `..` resolved, and anything that is not a plain local path refused (see the class comment). Public for the
     * PC window's own dialog, which starts in the same folder a remote window would.
     */
    fun resolve(raw: String?): Target {
        var s = raw?.trim()?.trim('"')?.replace('/', '\\').orEmpty()
        if (s.isEmpty()) return Target.Bad("No path was given.")
        if (s.startsWith("@")) {
            val key = s.drop(1).substringBefore('\\')
            val rest = s.drop(1).substringAfter('\\', "").trim('\\')
            val dir = placeDir(key) ?: return Target.Bad(
                when {
                    key.lowercase() in PLACE_KEYS -> "This PC has no ${placeWords(key)}."
                    key.startsWith("campaign:", ignoreCase = true) ->
                        "Falcon BMS on this PC has no campaign folder for the theater “${key.substringAfter(':').trim()}”."
                    else -> "“@$key” is not a place this PC knows."
                },
            )
            s = if (rest.isEmpty()) dir.path else dir.path.trimEnd('\\') + "\\" + rest
        }
        if (s.length == 2 && s[1] == ':') s += "\\"
        if (s.startsWith("\\\\")) return Target.Bad("Only the PC's own drives are opened here, not a network path ($s).")
        if (!DRIVE.containsMatchIn(s)) return Target.Bad("“$raw” is not a full path on one of the PC's drives (C:\\…).")
        if (s.indexOf(':', 2) >= 0 || s.any { it < ' ' || it in "<>\"|?*" }) return Target.Bad("“$s” is not a name Windows allows.")
        for (seg in s.substring(3).split('\\')) {
            if (seg.isEmpty() || seg == "." || seg == "..") continue
            if (RESERVED.matches(seg.substringBefore('.').trimEnd())) return Target.Bad("“$seg” is a name Windows keeps for a device.")
        }
        val norm = quiet { Paths.get(s).normalize().toFile() } ?: return Target.Bad("“$s” is not a path Windows can take.")
        if (!DRIVE.containsMatchIn(norm.path)) return Target.Bad("“$s” is not a full path on one of the PC's drives.")
        return Target.Ok(norm)
    }

    private val PLACE_KEYS = setOf(
        "bms", "config", "campaign", "datacampaign", "wdp", "planner", "datacards", "documents", "desktop", "downloads",
    )

    private fun placeWords(key: String) = when (key.lowercase()) {
        "bms", "config", "campaign", "datacampaign" -> "Falcon BMS folder"
        "wdp" -> "Weapon Delivery Planner folder"
        "planner", "datacards" -> "Falcon BMS folder"
        else -> "$key folder"
    }

    /** The extension of [f], lower case and without the dot ("" for none). */
    fun typeOf(f: File): String = f.name.substringAfterLast('.', "").lowercase()

    // ---------------------------------------------------------------- the routes

    private fun list(rawPath: String?, rawTypes: String?): ApiResponse {
        val asked = when (val t = resolve(rawPath?.takeIf { it.isNotBlank() } ?: "@bms")) {
            is Target.Bad -> return error(t.why, 400)
            is Target.Ok -> t.file
        }
        val types = rawTypes?.split(',', ';', ' ', '|')?.map { it.trim().removePrefix("*").removePrefix(".").lowercase() }
            ?.filter { it.isNotEmpty() && it != "*" }?.toSet().orEmpty()
        // a file names the folder it is in (a start folder given as the last file opened, as WDP gives it); a folder
        // that is not there is answered with the nearest one above it that is
        val askedFile = isFile(asked)
        var dir: File? = if (askedFile) asked.parentFile else asked
        while (dir != null && !isDir(dir)) dir = dir.parentFile
        val fallback = dir == null
        if (dir == null) dir = listOfNotNull(placeDir("bms"), placeDir("documents")).firstOrNull { isDir(it) }
        if (dir == null) return error("${asked.path} is not on the PC, and neither is any folder above it.", 404)
        val note = when {
            fallback -> "${asked.path} is not on the PC: this is ${dir.path}."
            askedFile || dir.path.trimEnd('\\').equals(asked.path.trimEnd('\\'), true) -> null
            else -> "${asked.path} is not on the PC: this is the nearest folder above it."
        }
        val shown = dir
        val listing = try {
            timed(LIST_MS) { readFolder(shown, types) }
        } catch (_: TimeoutException) {
            return error("${shown.path} did not answer in time. Is it a network drive that is not connected?", 409)
        }
        return encode(PcFolder.serializer(), listing.copy(note = note))
    }

    /** One folder: folders first, then files of [types] (all when empty), each by name; hidden and system items left out. */
    private fun readFolder(dir: File, types: Set<String>): PcFolder {
        val folders = ArrayList<PcFileEntry>()
        val files = ArrayList<PcFileEntry>()
        var skipped = 0
        var truncated = false
        Files.newDirectoryStream(dir.toPath()).use { stream ->
            for (p in stream) {
                if (folders.size + files.size >= PcFolder.MAX) { truncated = true; break }
                val a = quiet { Files.readAttributes(p, DosFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS) } ?: continue
                if (a.isHidden || a.isSystem) { skipped++; continue }
                val name = p.fileName?.toString() ?: continue
                if (name.endsWith(TMP, ignoreCase = true)) continue
                val folder = a.isDirectory || (a.isSymbolicLink && quiet { Files.isDirectory(p) } == true)
                if (folder) {
                    folders += PcFileEntry(name, p.toString(), dir = true, modified = a.lastModifiedTime().toMillis())
                } else {
                    val type = name.substringAfterLast('.', "").lowercase()
                    if (types.isNotEmpty() && type !in types) continue
                    files += PcFileEntry(
                        name, p.toString(), size = a.size(), modified = a.lastModifiedTime().toMillis(),
                        read = type in READ_TYPES, write = type in WRITE_TYPES,
                    )
                }
            }
        }
        val byName = compareBy<PcFileEntry, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
        return PcFolder(dir.path, dir.parentFile?.path, folders.sortedWith(byName) + files.sortedWith(byName), skipped, truncated)
    }

    private fun stat(rawPath: String?): ApiResponse {
        val f = when (val t = resolve(rawPath)) {
            is Target.Bad -> return error(t.why, 400)
            is Target.Ok -> t.file
        }
        return encode(PcFileEntry.serializer(), entryOf(f))
    }

    /** What [f] is now: a folder, a file, or a name that is not there yet. */
    private fun entryOf(f: File): PcFileEntry {
        val type = typeOf(f)
        val a = quiet { Files.readAttributes(f.toPath(), DosFileAttributes::class.java) }
            ?: return PcFileEntry(f.name, f.path, exists = false, read = type in READ_TYPES, write = type in WRITE_TYPES)
        return if (a.isDirectory) PcFileEntry(f.name.ifEmpty { f.path }, f.path, dir = true, modified = a.lastModifiedTime().toMillis())
        else PcFileEntry(f.name, f.path, size = a.size(), modified = a.lastModifiedTime().toMillis(), read = type in READ_TYPES, write = type in WRITE_TYPES)
    }

    private fun read(rawPath: String?): ApiResponse {
        val f = when (val t = resolve(rawPath)) {
            is Target.Bad -> return error(t.why, 400)
            is Target.Ok -> t.file
        }
        refuseType(f, READ_TYPES, "read")?.let { return it }
        if (isDir(f)) return error("${f.path} is a folder.", 400)
        if (!isFile(f)) return error("${f.path} is not on the PC.", 404)
        // a link named .ini that leads to something else is read as what it leads to
        val real = quiet { f.toPath().toRealPath().toFile() } ?: f
        if (typeOf(real) !in READ_TYPES) return error("${f.path} leads to ${real.name}, which is not a file the Planner reads.", 400)
        val size = quiet { Files.size(real.toPath()) } ?: 0L
        if (size > MAX_BYTES) return error("${f.name} is ${size / (1024 * 1024)} MB: the Planner reads files up to ${MAX_BYTES / (1024 * 1024)} MB.", 409)
        val bytes = try {
            Files.readAllBytes(real.toPath())
        } catch (e: Throwable) {
            return error("${f.path} could not be read: ${reason(e)}", 409)
        }
        val modified = quiet { Files.getLastModifiedTime(real.toPath()).toMillis() } ?: 0L
        return encode(PcFileData.serializer(), PcFileData(f.path, f.name, bytes.size.toLong(), modified, Base64.getEncoder().encodeToString(bytes)))
    }

    private fun write(rawPath: String?, rawOverwrite: String?, body: ByteArray, remote: String): ApiResponse {
        val f = when (val t = resolve(rawPath)) {
            is Target.Bad -> return error(t.why, 400)
            is Target.Ok -> t.file
        }
        refuseType(f, WRITE_TYPES, "write")?.let { return it }
        if (f.name.isBlank() || f.parentFile == null) return error("${f.path} names no file.", 400)
        val overwrite = rawOverwrite == "1" || rawOverwrite.equals("true", ignoreCase = true)
        val sent = quiet { Bridge.json.decodeFromString(PcFileWrite.serializer(), body.toString(Charsets.UTF_8)) }
            ?: return error("The body is not a file to write ({\"data\": \"<base64>\"}).", 400)
        val bytes = quiet { Base64.getDecoder().decode(sent.data) } ?: quiet { Base64.getMimeDecoder().decode(sent.data) }
            ?: return error("The file's content is not base64.", 400)
        if (bytes.size > MAX_BYTES) return error("That is ${bytes.size / (1024 * 1024)} MB: the Planner writes files up to ${MAX_BYTES / (1024 * 1024)} MB.", 400)
        val folder = f.parentFile
        if (!isDir(folder)) return error("The folder ${folder.path} is not on the PC.", 404)
        if (isDir(f)) return error("${f.path} is a folder.", 409)
        if (isFile(f) && !overwrite) return error("${f.name} is already in ${folder.path}.", 409)
        DevGuard.refusal(folder.path)?.let { return error(it, 409) }
        try {
            writeSafely(f, bytes)
        } catch (e: Throwable) {
            BridgeLog.warn("files: ${f.path} was not written: ${e.message}")
            return error("${f.name} was not written: ${reason(e)}", 409)
        }
        BridgeLog.info("files: wrote ${f.path} (${bytes.size} bytes) for $remote")
        return encode(PcFileEntry.serializer(), entryOf(f))
    }

    /**
     * Makes the folder [rawPath] names, and any folder above it that is missing: WDP's `CheckForDir` and the
     * `Directory.CreateDirectory` calls before its Save windows, so a window opens where WDP's does. A folder that is
     * there already is answered as it is; a developer run makes nothing inside a BMS folder ([DevGuard]).
     */
    private fun mkdir(rawPath: String?): ApiResponse {
        val f = when (val t = resolve(rawPath)) {
            is Target.Bad -> return error(t.why, 400)
            is Target.Ok -> t.file
        }
        if (isDir(f)) return encode(PcFileEntry.serializer(), entryOf(f))
        if (isFile(f)) return error("${f.path} is a file, not a folder.", 409)
        DevGuard.refusal(f.path)?.let { return error(it, 409) }
        // the Planner's own folder, the first time anything in it is asked for: laid out as WDP's, with what an earlier
        // build kept in Documents copied in
        val root = placeDir("planner")
        if (root != null && !isDir(root) &&
            (f.path.equals(root.path, ignoreCase = true) || f.path.startsWith(root.path.trimEnd('\\') + "\\", ignoreCase = true))
        ) {
            preparePlanner(root)
            BridgeLog.info("files: made the Planner's folder ${root.path}")
        }
        try {
            Files.createDirectories(f.toPath())
        } catch (e: Throwable) {
            return error("The folder ${f.path} could not be made: ${reason(e)}", 409)
        }
        BridgeLog.info("files: made the folder ${f.path}")
        return encode(PcFileEntry.serializer(), entryOf(f))
    }

    /**
     * Reload WX's file ([PcWeather]): an `.fmap` as its cells, or a `.twx` as its settings and — when its model is a
     * map — the map BMS flies with it: the update map in force at [rawClock] (campaign milliseconds;
     * `WeatherMapsUpdates` beside the `.twx`, the newest named for a time at or before it) when the campaign updates
     * its maps, else the `.fmap` of the same name beside it, as WDP's `GetFmap` chooses; with neither there, the file's
     * own tables stand in for the map, as WDP's do, and [PcWeather.note] says so. Read only.
     *
     * [rawSave] names the save beside the `.twx` whose weather it is asked as (the Planner's card asks for the save's
     * own file; Reload WX names none): [PcWeather.otherSave] then says when the file was written with another save.
     * See [otherSave].
     */
    private fun weather(rawPath: String?, rawClock: String?, rawSave: String? = null): ApiResponse {
        val f = when (val t = resolve(rawPath)) {
            is Target.Bad -> return error(t.why, 400)
            is Target.Ok -> t.file
        }
        val type = typeOf(f)
        if (type != "fmap" && type != "twx") return error("${f.name} is not a weather file: Reload WX opens an .fmap or a .twx.", 400)
        if (!isFile(f)) return error("${f.name} is not in ${f.parentFile?.path ?: "that folder"}.", 404)
        val clock = rawClock?.trim()?.toLongOrNull()
        val modified = quiet { f.lastModified() } ?: 0L
        if (type == "fmap") {
            val m = Fmap.read(f) ?: return error("${f.name} is not a weather map the Planner can read (a version 5 or 8 .fmap).", 409)
            return encode(PcWeather.serializer(), cellsOf(m, f.path).copy(path = f.path, kind = "fmap", modified = modified))
        }
        val bytes = try { Files.readAllBytes(f.toPath()) } catch (e: Throwable) { return error("${f.path} could not be read: ${reason(e)}", 409) }
        val twx = when (val r = Twx.read(bytes)) {
            is Twx.Read.Bad -> return error("${f.name}: ${r.why}", 409)
            is Twx.Read.Ok -> r.twx
        }
        val base = PcWeather(path = f.path, kind = "twx", twx = twx, modified = modified, otherSave = otherSave(f, twx, clock, rawSave))
        if (twx.model != 3) return encode(PcWeather.serializer(), base)
        val dir = f.parentFile
        val updates = if (twx.mapUpdates > 0 && clock != null) updateMap(File(dir, "WeatherMapsUpdates"), clock) else null
        val mapFile = updates ?: File(dir, f.name.substringBeforeLast('.') + ".fmap")
        val m = if (isFile(mapFile)) Fmap.read(mapFile) else null
        // no map to read: the file's own table of the type it is in stands in, as WDP's CreateAtis falls back on it
        val table = listOf("Sunny", "Fair", "Poor", "Inclement").getOrNull(twx.condition - 1)?.let { "the $it table" } ?: "its tables"
        val out = if (m == null) base.copy(
            note = if (twx.mapUpdates > 0 && clock == null) "${f.name} flies a weather map that updates through the day, and no campaign time was given to pick it by: $table of ${f.name} stands in for it."
            else "${f.name} flies a weather map, and ${mapFile.name} is not beside it to read: $table of ${f.name} stands in for it, as WDP does.",
        ) else cellsOf(m, mapFile.path).copy(path = f.path, kind = "twx", twx = twx, modified = modified, otherSave = base.otherSave)
        return encode(PcWeather.serializer(), out)
    }

    /**
     * Whether the `.twx` [f] is not the weather of the save [rawSave] (a name beside it: "Auto Save.tac") at campaign
     * time [clock], as a sentence; null when it is, or when no save is named. A campaign and a TE or training mission
     * saved under one name share one `.twx` — `Auto Save.cam` and `Auto Save.tac` both write `Auto Save.twx` — so the
     * older of the two would take the other's weather. BMS writes a campaign's `.twx` in the same millisecond as the
     * `.cam`, and the file's last check is BMS's last weather check, up to a few minutes before the save's clock; a TE's is written by SAVE WTH, before or after the TE's own
     * save, so a TE's file is refused only on evidence: another save of the same name written at the same moment as
     * the `.twx` (within two seconds), or — for a campaign — neither the time nor the clock agreeing.
     */
    private fun otherSave(f: File, twx: com.bmscompanion.app.data.mission.PcTwx, clock: Long?, rawSave: String?): String? = quiet {
        val save = rawSave?.trim()?.takeIf { it.isNotEmpty() && it.none { c -> c == '\\' || c == '/' || c == ':' } } ?: return@quiet null
        val dir = f.parentFile ?: return@quiet null
        val own = File(dir, save)
        val base = save.substringBeforeLast('.')
        val ext = save.substringAfterLast('.', "").lowercase()
        val at = f.lastModified()
        fun near(a: Long, b: Long) = a > 0 && b > 0 && kotlin.math.abs(a - b) <= 2_000L
        val ownTime = if (own.isFile) own.lastModified() else 0L
        val twxClock = twx.clock
        // the file's last check is BMS's last weather check, which can be a few minutes of campaign time before the
        // save's clock (116 s on a save of the test fixture), never after it
        val sameClock = twxClock != null && clock != null && clock - twxClock in -60_000L..CHECK_LAG_MS
        if (sameClock || near(at, ownTime)) return@quiet null
        val other = listOf("cam", "tac", "trn").filter { it != ext }.map { File(dir, "$base.$it") }
            .firstOrNull { it.isFile && near(it.lastModified(), at) }
        val written = twxClock?.let { " at campaign time ${CampaignFiles.clockShort(it)}" } ?: ""
        when {
            other != null -> "${f.name} was written with ${other.name}$written, not with $save: a campaign and a TE saved " +
                "under one name share one weather file, and BMS rewrote it for ${other.name}."
            ext == "cam" && twxClock != null && clock != null -> "${f.name} was written$written, and $save at " +
                "${CampaignFiles.clockShort(clock)}: BMS writes a campaign's weather file with every save, so this one belongs to another save."
            else -> null
        }
    }

    /** How far a `.twx`'s last weather check may be behind its campaign save's clock (campaign ms) and still be that save's. */
    private const val CHECK_LAG_MS = 5 * 60_000L

    /** The update map in force at [clock] (campaign ms): the newest `dhhmm.fmap` in [dir] named for a time at or before it. */
    private fun updateMap(dir: File, clock: Long): File? {
        if (!isDir(dir)) return null
        val minute = clock / 60_000L
        return quiet {
            dir.listFiles { x -> x.isFile && x.name.endsWith(".fmap", ignoreCase = true) }.orEmpty()
                .mapNotNull { x -> CampaignTime.ofFmapName(x.name)?.let { it.minutes to x } }
                .filter { it.first <= minute }
                .maxByOrNull { it.first }?.second
        }
    }

    /** A map's cells, one list per property (the surface wind in knots; one decimal where the file has more). */
    private fun cellsOf(m: Fmap, path: String): PcWeather {
        val n = m.cells
        fun r1(v: Float): Float = Math.round(v * 10f) / 10f
        return PcWeather(
            map = path, version = m.version, cols = m.cols, rows = m.rows,
            type = List(n) { m.int(Fmap.Field.TYPE, it) },
            pressureMb = List(n) { r1(m.float(Fmap.Field.PRESSURE, it)) },
            tempC = List(n) { r1(m.float(Fmap.Field.TEMPERATURE, it)) },
            windKt = List(n) { r1((m.windSpeed(it, 0) / Fmap.KMH_PER_KT).toFloat()) },
            windDeg = List(n) { r1(m.windDir(it, 0)) },
            cloudBaseFt = List(n) { r1(m.float(Fmap.Field.CLOUD_BASE, it)) },
            cover = List(n) { m.int(Fmap.Field.COVER, it) },
            towering = List(n) { m.int(Fmap.Field.TOWERING, it) },
            visKm = List(n) { r1(m.visibilityKm(it)) },
        )
    }

    /**
     * Upd Kneeboard's Browse picture… (WDP's `Image.FromFile`, and DevIL for a `.dds`): the picture [rawPath] names,
     * as a PNG every device decodes the same way ([PcPicture]), scaled down to fit [rawMax] pixels a side (64-4096,
     * [PcPicture.MAX] by default) when it is larger. Read by [PictureFile], which says what it reads and how its memory
     * is bounded; anything else is refused in a sentence. Read only. This is the device's **preview**: a print sends the
     * picture's path, and the PC draws the half from the file itself ([pictureFile], `KneeboardPrint`).
     */
    private fun picture(rawPath: String?, rawMax: String?): ApiResponse {
        val at = pictureFile(rawPath)
        val real = at.file ?: return error(at.why ?: "No picture was named.", at.status)
        val f = at.asked ?: real
        val max = (rawMax?.trim()?.toIntOrNull() ?: PcPicture.MAX).coerceIn(64, 4096)
        // a preview: read no larger than twice what is sent (a large photo is never decoded whole for it)
        val read = when (val r = PictureFile.read(real, skipOk = true) { w, h -> maxOf(w, h) >= 2 * max }) {
            is PictureFile.Read.Bad -> return error("${f.name} could not be read as a picture: ${r.why}", 409)
            is PictureFile.Read.Ok -> r
        }
        val sent = fitWithin(read.img, max)
        val png = java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(sent, "png", it) }.toByteArray()
        val size = quiet { Files.size(real.toPath()) } ?: 0L
        val modified = quiet { Files.getLastModifiedTime(real.toPath()).toMillis() } ?: 0L
        return encode(PcPicture.serializer(), PcPicture(
            path = f.path, name = f.name, size = size, modified = modified, format = read.format, width = read.width, height = read.height,
            sentWidth = sent.width, sentHeight = sent.height, data = Base64.getEncoder().encodeToString(png),
        ))
    }

    /** A picture file a client named: [file] the file itself (a link followed), [asked] the path as named; or [why] not, and the HTTP [status]. */
    class PictureAt(val file: File?, val asked: File?, val why: String?, val status: Int)

    /**
     * The picture file [rawPath] names, through the same guards as every route here (the PC's own drives, no device
     * names, `..` resolved) and a picture's type, also where a link leads: for `GET /api/files/picture` and for a
     * print's Picture half (`POST /api/kbprint/file`, [com.bmscompanion.app.data.mission.KbHalf.picture]).
     */
    fun pictureFile(rawPath: String?): PictureAt {
        val f = when (val t = resolve(rawPath)) {
            is Target.Bad -> return PictureAt(null, null, t.why, 400)
            is Target.Ok -> t.file
        }
        if (typeOf(f) !in PcPicture.TYPES) return PictureAt(null, f, "${f.name} is not a picture the kneeboard takes: a .jpg, .png, .bmp or .dds.", 400)
        if (isDir(f)) return PictureAt(null, f, "${f.path} is a folder.", 400)
        if (!isFile(f)) return PictureAt(null, f, "${f.path} is not on the PC.", 404)
        val real = quiet { f.toPath().toRealPath().toFile() } ?: f
        if (typeOf(real) !in PcPicture.TYPES) return PictureAt(null, f, "${f.path} leads to ${real.name}, which is not a picture.", 400)
        // a link on a local drive is held to the same rules where it leads (own drives only, no network share)
        if (real.path != f.path) (resolve(real.path) as? Target.Bad)?.let { return PictureAt(null, f, "${f.path} leads to ${real.path}: ${it.why}", 400) }
        return PictureAt(real, f, null, 200)
    }

    /** [img] as it is when it fits [max] a side, else scaled down to fit (halved first, so no pixel is skipped). */
    private fun fitWithin(img: java.awt.image.BufferedImage, max: Int): java.awt.image.BufferedImage {
        val s = minOf(1.0, max.toDouble() / maxOf(img.width, img.height))
        if (s >= 1.0 && img.type == java.awt.image.BufferedImage.TYPE_INT_ARGB) return img
        val w = maxOf(1, Math.round(img.width * s).toInt())
        val h = maxOf(1, Math.round(img.height * s).toInt())
        var src = img
        while (src.width >= 2 * w && src.height >= 2 * h) {
            val half = java.awt.image.BufferedImage(src.width / 2, src.height / 2, java.awt.image.BufferedImage.TYPE_INT_ARGB)
            val g = half.createGraphics()
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.drawImage(src, 0, 0, half.width, half.height, null)
            g.dispose()
            src = half
        }
        val out = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        val g = out.createGraphics()
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        g.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING, java.awt.RenderingHints.VALUE_RENDER_QUALITY)
        g.drawImage(src, 0, 0, w, h, null)
        g.dispose()
        return out
    }

    /** The refusal for a file whose type these routes do not [what] (read or write), or null. */
    private fun refuseType(f: File, allowed: Set<String>, what: String): ApiResponse? {
        val type = typeOf(f)
        if (type in allowed) return null
        val shown = if (type.isEmpty()) "a file with no type" else ".$type files"
        return error("BMS Companion does not $what $shown here: only the files the Planner uses (${allowed.sorted().joinToString(", ") { ".$it" }}).", 400)
    }

    // ---------------------------------------------------------------- doing it safely

    private const val TMP = ".bmsc-new"

    /** New bytes beside the file first, then moved over it in one step: a failed write cannot leave half a file. */
    private fun writeSafely(file: File, bytes: ByteArray) {
        val tmp = File(file.parentFile, file.name + TMP)
        try {
            Files.write(tmp.toPath(), bytes)
            try {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            runCatching { if (tmp.exists()) tmp.delete() }
        }
    }

    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "files").apply { isDaemon = true } }

    /** [body] on its own thread, given up on after [ms] (the thread is left to finish on its own). */
    private fun <T> timed(ms: Long, body: () -> T): T {
        val job = pool.submit(Callable { body() })
        try {
            return job.get(ms, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            job.cancel(true)
            throw e
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    private fun isDir(f: File): Boolean = quiet { f.isDirectory } == true
    private fun isFile(f: File): Boolean = quiet { f.isFile } == true

    private inline fun <T> quiet(body: () -> T): T? = try { body() } catch (_: Throwable) { null }

    private fun reason(e: Throwable): String = when (e) {
        is AccessDeniedException ->
            "Windows would not let BMS Companion write to ${e.file}. The file may be read-only, or the folder may need " +
                "administrator rights (Falcon BMS is often installed under Program Files)."
        is NoSuchFileException -> "${e.file} is not on the PC."
        is FileSystemException -> listOfNotNull(e.file, e.reason).joinToString(": ").ifBlank { e.javaClass.simpleName }
        else -> (e.message ?: e.javaClass.simpleName)
    }.trimEnd('.') + "."

    private fun <T> encode(serializer: KSerializer<T>, value: T) = ApiResponse.json(Bridge.json.encodeToString(serializer, value))

    private fun error(sentence: String, status: Int) =
        ApiResponse.json("""{"error":${Bridge.json.encodeToString(String.serializer(), sentence)}}""", status)
}
