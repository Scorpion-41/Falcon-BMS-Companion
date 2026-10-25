package com.bmscompanion.desktop.bridge

import com.bmscompanion.app.data.mission.BmsInstallEntry
import com.bmscompanion.app.data.mission.BmsInstallList
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.platform.win32.WinReg
import java.io.File

/**
 * The Falcon BMS installs the program can read, and which one it reads (1.4.1; docs/PROTOCOL.md, "Falcon BMS installs").
 *
 * **Which installs**: every `Falcon BMS 4.x` key under `HKLM\SOFTWARE\Benchmark Sims`, in the 32-bit view
 * (`WOW6432Node`, where BMS's installer writes) and the 64-bit one, whose `baseDir` exists — newest version first — and
 * every folder the pilot added (`BridgeSettings.BmsInstalls`, Add folder… on any Setup page or the server page). A pilot
 * with 4.38.1 and 4.37 installed side by side sees both without doing anything.
 *
 * **Which one is read**: `BridgeSettings.BmsDirOverride` (the active folder; null = the registry's newest install, as
 * before 1.4.1). A registry install that is active gives its own key's pilot callsign and `curTheater`; a folder that
 * is not in the registry takes them from the newest key, as an override always did ([BmsInstall.refresh]).
 *
 * **A switch** is a change of folder like any other ([Bridge.update] → [BmsInstall.refresh]), and [switched] drops what
 * the program held of the previous install that is not keyed by its folder (the map tiles drawn from its terrain, the
 * target pictures, the mission's ground picture and weather, the radio log's calls, WDP mode's snapshot of one of its
 * saves). Nothing is adapted to an older Falcon BMS: what does not work with another version's folder fails as it does
 * with a wrong folder, in a sentence.
 *
 * Read only: the registry is never written, and nothing here writes into a BMS folder (only the settings file).
 */
object Installs {
    /** One registry install: the key's name ("Falcon BMS 4.38"), its folder, the pilot and theater it names. */
    data class Reg(val key: String, val dir: String, val callsign: String?, val theater: String?)

    /** Registry installs a developer check adds to the real ones (`--installstest`); empty in the pilot's program. */
    @Volatile internal var extraForCheck: List<Reg> = emptyList()

    /** How many times the program changed folders ([switched]); read by the check. */
    @Volatile internal var switches = 0; private set

    private val VIEWS = listOf(
        "SOFTWARE\\WOW6432Node\\Benchmark Sims" to 0,
        "SOFTWARE\\Benchmark Sims" to WinNT.KEY_WOW64_64KEY,
    )

    private val regLock = Any()
    private var regAt = 0L
    private var regRead: List<Reg> = emptyList()

    /**
     * Every Falcon BMS install the registry names (both views; read at most every 5 s), newest version first, one per
     * folder; the folders are not checked here ([default] takes the first that exists).
     */
    fun registry(): List<Reg> {
        val real = synchronized(regLock) {
            val now = System.currentTimeMillis()
            if (now - regAt !in 0 until 5_000) { regRead = readRegistry(); regAt = now }
            regRead
        }
        return (real + extraForCheck)
            .distinctBy { key(it.dir) }
            .sortedWith(compareByDescending<Reg> { versionOf(it.key).first }.thenByDescending { versionOf(it.key).second })
    }

    /** The registry is read again at the next look (a check, and Refresh on a Setup page). */
    fun forgetRegistry() = synchronized(regLock) { regAt = 0L }

    /** The registry's newest install whose folder exists: the one read while none is chosen. */
    fun default(regs: List<Reg> = registry()): Reg? = regs.firstOrNull { isDir(it.dir) }

    /** The registry install whose folder is [dir], or null. */
    fun regFor(dir: String?, regs: List<Reg> = registry()): Reg? =
        dir?.takeIf { it.isNotBlank() }?.let { d -> regs.firstOrNull { key(it.dir) == key(d) } }

    private fun readRegistry(): List<Reg> {
        val out = ArrayList<Reg>()
        for ((path, sam) in VIEWS) {
            try {
                if (!Advapi32Util.registryKeyExists(WinReg.HKEY_LOCAL_MACHINE, path, sam)) continue
                for (name in Advapi32Util.registryGetKeys(WinReg.HKEY_LOCAL_MACHINE, path, sam)) {
                    if (!name.startsWith("Falcon BMS", ignoreCase = true)) continue
                    val values = runCatching { Advapi32Util.registryGetValues(WinReg.HKEY_LOCAL_MACHINE, "$path\\$name", sam) }.getOrNull() ?: continue
                    val base = regString(values["baseDir"])?.trimEnd('\\')?.takeIf { it.isNotBlank() } ?: continue
                    out += Reg(name.trim(), base, regString(values["PilotCallsign"]), regString(values["curTheater"]))
                }
            } catch (e: Throwable) {
                BridgeLog.warn("Registry read failed ($path): ${e.message}")
            }
        }
        return out
    }

    // ------------------------------------------------------------------------------------------------ the list

    /** The list as the routes answer it: [s]'s folders and the registry's, [inUse] the folder read now. */
    fun list(s: BridgeSettings = Bridge.settings.value, inUse: String? = Bridge.install.baseDir, regs: List<Reg> = registry()): BmsInstallList {
        val def = default(regs)
        val rows = s.BmsInstalls.filter { it.path.isNotBlank() }
        fun labelOf(path: String) = rows.firstOrNull { key(it.path) == key(path) }?.label?.trim()?.takeIf { it.isNotEmpty() }
        val out = ArrayList<BmsInstallEntry>()
        for (r in regs) {
            val label = labelOf(r.dir)
            out += BmsInstallEntry(
                id = "r:" + r.key, name = label ?: r.key, path = r.dir, registryKey = r.key, label = label, added = false,
                registryDefault = def != null && key(def.dir) == key(r.dir), exists = isDir(r.dir), looksLikeBms = looksLikeBms(File(r.dir)),
                active = inUse != null && key(inUse) == key(r.dir), theater = r.theater,
            )
        }
        // the pilot's folders, and the active one even when it never was put in the list (a folder typed before 1.4.1
        // whose file was already brought forward, or a developer check's copy): what is read is always shown
        val own = rows.map { it.path.trim().trimEnd('\\', '/') } +
            listOfNotNull(s.BmsDirOverride?.trim()?.trimEnd('\\', '/')?.takeIf { it.isNotEmpty() })
        for (p in own.distinctBy(::key)) {
            if (out.any { key(it.path) == key(p) }) continue
            val label = labelOf(p)
            out += BmsInstallEntry(
                id = idOf(p), name = label ?: nameOf(p), path = p, label = label, added = true, exists = isDir(p),
                looksLikeBms = looksLikeBms(File(p)), active = inUse != null && key(inUse) == key(p),
            )
        }
        return BmsInstallList(
            installs = out, active = out.firstOrNull { it.active }?.id,
            followsRegistry = s.BmsDirOverride.isNullOrBlank(),
        )
    }

    /** The active install's name for `/api/info` (`BmsStatus.install`), and how many are listed. Never throws. */
    fun status(): Pair<String?, Int> = try {
        val l = list()
        l.installs.firstOrNull { it.active }?.name to l.installs.size
    } catch (_: Throwable) {
        null to 0
    }

    /** The id of a folder the pilot added: its path, without case, as a short number. */
    fun idOf(path: String): String = "a:" + Integer.toHexString(key(path).hashCode())

    private fun nameOf(path: String): String = File(path).name.ifBlank { path }

    /** Holds `Falcon BMS.exe` (top, `Bin\x64` or `Bin\x86`) or `Data\TerrData`. */
    fun looksLikeBms(dir: File): Boolean = try {
        dir.isDirectory && (
            listOf("Falcon BMS.exe", "Bin\\x64\\Falcon BMS.exe", "Bin\\x86\\Falcon BMS.exe").any { File(dir, it).isFile } ||
                File(dir, "Data\\TerrData").isDirectory
            )
    } catch (_: Throwable) {
        false
    }

    // ------------------------------------------------------------------------------------------------ the routes

    /**
     * `GET /api/bms/installs`; `POST /api/bms/installs/active?id=` (switch), `/add?path=[&label=][&activate=1]`,
     * `/remove?id=`, `/label?id=&label=` (blank = the default name). Each answers the list, with `message` or `error`.
     */
    fun handle(req: ApiRequest): ApiResponse = try {
        val p = req.path.trimEnd('/')
        when {
            req.method == "GET" && p == "/api/bms/installs" -> {
                if (req.query["refresh"] == "1") forgetRegistry()
                answer(list())
            }
            req.method == "POST" && p == "/api/bms/installs/active" -> activate(req.query["id"])
            req.method == "POST" && p == "/api/bms/installs/add" ->
                add(req.query["path"], req.query["label"], req.query["activate"].let { it == "1" || it.equals("true", true) })
            req.method == "POST" && p == "/api/bms/installs/remove" -> remove(req.query["id"])
            req.method == "POST" && p == "/api/bms/installs/label" -> label(req.query["id"], req.query["label"])
            else -> ApiResponse.notFound()
        }
    } catch (e: Throwable) {
        BridgeLog.warn("Installs: ${e.message}")
        refuse(500, "That could not be done: ${e.message ?: e.javaClass.simpleName}.")
    }

    private fun entry(id: String?): BmsInstallEntry? = id?.trim()?.takeIf { it.isNotEmpty() }?.let { i -> list().installs.firstOrNull { it.id == i } }

    private fun activate(id: String?): ApiResponse {
        val e = entry(id) ?: return refuse(404, "That Falcon BMS install is not in the list any more.")
        if (!e.exists) return refuse(409, "${e.path} is not there any more, so ${e.name} cannot be used.")
        if (e.active && (!e.registryDefault || Bridge.settings.value.BmsDirOverride.isNullOrBlank())) return answer(list().copy(message = "${e.name} is already the one in use."))
        // the registry's newest install is followed rather than pinned, as before 1.4.1: a new BMS release is taken by itself
        val old = Bridge.install.baseDir
        Bridge.update { followFolders(it, old).copy(BmsDirOverride = if (e.registryDefault) null else e.path) }
        BridgeLog.info("Falcon BMS install switched to ${e.name} (${e.path})")
        return answer(list().copy(message = "Now reading ${e.name}."))
    }

    private fun add(raw: String?, label: String?, activate: Boolean): ApiResponse {
        val f = when (val t = PcFileRoutes.resolve(raw)) {
            is PcFileRoutes.Target.Bad -> return refuse(400, t.why)
            is PcFileRoutes.Target.Ok -> t.file
        }
        if (!isDir(f.path)) return refuse(404, "${f.path} is not a folder on this PC.")
        val path = f.path.trimEnd('\\')
        val l = label?.trim()?.takeIf { it.isNotEmpty() }
        val known = list().installs.firstOrNull { key(it.path) == key(path) }
        if (known == null || (l != null && known.label != l)) {
            Bridge.update(reapply = false) { s ->
                val rows = s.BmsInstalls.filterNot { key(it.path) == key(path) }
                s.copy(BmsInstalls = rows + BmsInstallSetting(path = path, label = l ?: s.BmsInstalls.firstOrNull { key(it.path) == key(path) }?.label))
            }
        }
        val added = list().installs.first { key(it.path) == key(path) }
        if (activate) return activate(added.id)
        val note = if (added.looksLikeBms) "" else " It does not look like a Falcon BMS folder (no Falcon BMS.exe, no Data\\TerrData)."
        return answer(list().copy(message = (if (known == null) "${added.name} was added." else "${added.name} is already in the list.") + note))
    }

    private fun remove(id: String?): ApiResponse {
        val e = entry(id) ?: return refuse(404, "That Falcon BMS install is not in the list any more.")
        if (!e.added) return refuse(409, "${e.name} is installed (the registry names it), so it stays in the list.")
        val old = Bridge.install.baseDir
        Bridge.update(reapply = e.active) { s ->
            (if (e.active) followFolders(s, old) else s).copy(
                BmsInstalls = s.BmsInstalls.filterNot { key(it.path) == key(e.path) },
                BmsDirOverride = s.BmsDirOverride.takeUnless { o -> o != null && key(o) == key(e.path) },
            )
        }
        BridgeLog.info("Falcon BMS folder removed from the list: ${e.path}")
        val now = list()
        return answer(now.copy(message = "${e.name} was taken out of the list" +
            (if (e.active) "; now reading ${now.installs.firstOrNull { it.active }?.name ?: "no Falcon BMS folder"}." else ".")))
    }

    private fun label(id: String?, raw: String?): ApiResponse {
        val e = entry(id) ?: return refuse(404, "That Falcon BMS install is not in the list any more.")
        val l = raw?.trim()?.take(60)?.takeIf { it.isNotEmpty() }
        Bridge.update(reapply = false) { s ->
            val row = s.BmsInstalls.firstOrNull { key(it.path) == key(e.path) }
            val rows = s.BmsInstalls.filterNot { key(it.path) == key(e.path) }
            // a registry install with no label needs no row; a folder the pilot added keeps its row
            val keep = row != null && e.added || l != null
            s.copy(BmsInstalls = if (keep) rows + BmsInstallSetting(path = row?.path ?: e.path, label = l) else rows)
        }
        return answer(list().copy(message = if (l == null) "${e.registryKey ?: nameOf(e.path)} has its own name again." else "Renamed to $l."))
    }

    /**
     * [s] with the folders that were inside the install read until now ([oldBase]) set back to their defaults: EZBoards
     * (`Tools\EZBoards`, filled in again from the new install when the settings are applied), html_brief, the
     * screenshots, the Planner's DataCards. Otherwise a switch would run the last install's EZBoards on the next PRINT
     * and write its cockpit pages. A folder outside it (EZBoards kept elsewhere) is the pilot's and stays.
     */
    internal fun followFolders(s: BridgeSettings, oldBase: String?): BridgeSettings {
        val base = oldBase?.takeIf { it.isNotBlank() }?.let(::key) ?: return s
        fun inOld(p: String?) = p != null && p.isNotBlank() && key(p).let { it == base || it.startsWith("$base\\") }
        return s.copy(
            EzBoardsDir = s.EzBoardsDir.takeUnless(::inOld),
            KneeboardExporterDir = s.KneeboardExporterDir.takeUnless(::inOld),
            PicturesDirOverride = s.PicturesDirOverride.takeUnless(::inOld),
            PlannerDataCardDir = s.PlannerDataCardDir.takeUnless(::inOld),
        )
    }

    private fun answer(l: BmsInstallList) = ApiResponse.json(Bridge.json.encodeToString(BmsInstallList.serializer(), l))

    private fun refuse(status: Int, why: String): ApiResponse {
        val l = runCatching { list() }.getOrDefault(BmsInstallList())
        return ApiResponse.json(Bridge.json.encodeToString(BmsInstallList.serializer(), l.copy(error = why)), status)
    }

    // ------------------------------------------------------------------------------------------------ a switch

    /**
     * The program now reads [new] instead of [old] ([BmsInstall.refresh] of the bridge's own install). Drops what it
     * held of the previous install and is not keyed by its folder; the briefing, the cartridge and the mission file are
     * read again by [Bridge]'s apply. Never throws.
     */
    fun switched(old: String?, new: String?) {
        switches++
        BridgeLog.info("Falcon BMS folder is now ${new ?: "none"} (was ${old ?: "none"})")
        runCatching { MapTiles.forget() }
        runCatching { com.bmscompanion.desktop.bridge.command.CampMap2D.forget() }
        runCatching { TargetPictures.forgetMemory() }
        runCatching { MissionGrounds.forget() }
        runCatching { MissionWeatherRoute.forget() }
        runCatching { MissionDtcFile.forget() }
        runCatching { RadioLog.reader.follow(null) }
        runCatching { MissionSource.discardIfNotIn(new) }
    }

    // ------------------------------------------------------------------------------------------------ small things

    private fun key(p: String) = p.trim().trimEnd('\\', '/').replace('/', '\\').lowercase()
    private fun isDir(p: String) = try { File(p).isDirectory } catch (_: Throwable) { false }

    private fun versionOf(key: String): Pair<Int, Int> =
        Regex("(\\d+)\\.(\\d+)").find(key)?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() } ?: (0 to 0)

    private fun regString(v: Any?): String? = when (v) {
        is ByteArray -> String(v, Charsets.US_ASCII).trimEnd(' ', '\u0000')
        is String -> v.trimEnd(' ', '\u0000')
        else -> null
    }
}
