package com.bmscompanion.desktop.bridge

import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.mission.SyncAnswer
import com.bmscompanion.app.data.mission.SyncItem
import com.bmscompanion.app.data.mission.SyncPost
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The Mission section as every device last left it (1.4.2, `GET`/`POST /api/sync`; docs/PROTOCOL.md "The Mission
 * section on every device"): the Mission section's preferences (map look and layers, the Dashboard's pages, the Taxi
 * page, the fuel plan settings …) and the Planner pages' entries not saved yet, key by key, the latest of each whichever
 * device made it. A pilot who changes something on the PC and picks up the tablet carries on where the PC left off
 * (`MissionSync` on each device; the PC's own window is a client like any other, in-process).
 *
 * **Which is newer is the PC's clock alone**, as for `/api/planner/state` ([PlannerStateStore]): a change says how long
 * ago it was made by the device's steady clock, and is dated the PC's now less that; an older change never replaces a
 * newer one (the answer carries the PC's, which the device takes); a **weak** change (a device seeding an empty store
 * with its own preferences) is kept only where the PC has nothing the device has not seen.
 *
 * In `%APPDATA%\BMS Companion\mission-sync.json` (never in the BMS folder), written at most every two seconds through a
 * temporary file and an atomic move. A Planner page's entries (keys starting `@`) are kept per flight; the oldest go
 * when there are more than [MAX_SESSION] of them, and a cartridge's edits (`@dtc/`) only for the last [MAX_DTC] flights.
 * Nothing here throws: a change that could not be written is held in memory and the answer says so.
 */
object MissionSyncStore {
    const val FILE_NAME = "mission-sync.json"
    private const val MAX_VALUE_CHARS = 256 * 1024
    private const val MAX_BODY = 2 * 1024 * 1024
    private const val MAX_PREFS = 3000
    private const val MAX_SESSION = 4000
    private const val MAX_DTC = 6
    private const val MAX_AGE_MS = 7L * 24 * 3600 * 1000
    private const val WRITE_DELAY_MS = 2000L
    private val KEY = Regex("[\\x21-\\x7e]{1,200}")

    @Serializable
    private data class Stored(val version: Int = 1, val items: List<SyncItem> = emptyList())

    private val lenient = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true; explicitNulls = false }
    private val lock = Any()
    private var loaded = false
    private val items = LinkedHashMap<String, SyncItem>()
    private var lastError: String? = null
    private var lastAt = 0L
    @Volatile private var writeDue = false
    private var writer: Thread? = null

    /** A developer check keeps the store in a scratch folder; null = `%APPDATA%\BMS Companion`. */
    @Volatile internal var folderOverride: File? = null
    private val folder: File get() = folderOverride ?: Repo.settingsFolder

    /** Drops what is held in memory (written first), so the next request reads the file again (for the checks). */
    internal fun reset() = synchronized(lock) { if (writeDue) { writeDue = false; lastError = persist() }; loaded = false; items.clear(); lastError = null; lastAt = 0 }

    fun handle(req: ApiRequest): ApiResponse = try {
        when (req.method) {
            "GET" -> answer(get(req.query["since"]?.toLongOrNull() ?: 0L, req.query["prefix"]))
            "POST" -> {
                val p = if (req.body.size > MAX_BODY) null
                else runCatching { lenient.decodeFromString(SyncPost.serializer(), req.body.decodeToString()) }.getOrNull()
                if (p == null) answer(SyncAnswer(error = "That is not a change of the Mission section the PC keeps.", now = now()), 400)
                else answer(post(p))
            }
            else -> ApiResponse.json("""{"error":"GET or POST"}""", 405)
        }
    } catch (e: Throwable) {
        BridgeLog.warn("/api/sync: ${e.message}")
        answer(SyncAnswer(error = "The Mission section's state could not be read: ${e.message ?: e::class.simpleName}", now = now()), 500)
    }

    private fun answer(a: SyncAnswer, status: Int = 200) = ApiResponse.json(Bridge.json.encodeToString(SyncAnswer.serializer(), a), status)

    private fun now() = System.currentTimeMillis()

    private fun plannerAt(): Long = runCatching { PlannerStateStore.all().entries.maxOfOrNull { it.at } ?: 0L }.getOrDefault(0L)

    /** The items changed after [since] (PC time), only those whose key starts with [prefix] when one is given. */
    fun get(since: Long, prefix: String? = null): SyncAnswer {
        val list = synchronized(lock) {
            load()
            items.values.filter { it.at > since && (prefix.isNullOrEmpty() || it.key.startsWith(prefix)) }
        }
        return SyncAnswer(list, now = now(), plannerAt = plannerAt(), error = lastError)
    }

    /** One device's changes: each kept when it is the newer (see the class), else the PC's answered for it. */
    fun post(p: SyncPost): SyncAnswer {
        val out = ArrayList<SyncItem>()
        val kept = ArrayList<String>()
        synchronized(lock) {
            load()
            val t = now()
            for (c in p.changes) {
                if (!KEY.matches(c.key)) continue
                val had = items[c.key]
                val at = t - c.age.coerceIn(0, MAX_AGE_MS)
                val keep = when {
                    had == null -> true
                    c.weak -> had.at <= c.seen
                    else -> at >= had.at || had.at <= c.seen
                }
                if (!keep) { out += had!!; continue }
                val v = c.value?.let { if (it.length > MAX_VALUE_CHARS) it.take(MAX_VALUE_CHARS) else it }
                // never earlier than what it replaces, nor than the last change kept: a device that has read up to a
                // time sees every later change as later
                val stamp = maxOf(at, (had?.at ?: 0L) + 1, lastAt + 1)
                lastAt = stamp
                val e = SyncItem(c.key, v, c.type.take(2), stamp, p.by.take(20))
                items.remove(c.key)
                items[c.key] = e
                out += e
                kept += c.key
            }
            prune()
            if (kept.isNotEmpty()) scheduleWrite()
        }
        return SyncAnswer(out, kept, now(), plannerAt(), lastError)
    }

    /** The oldest entries go when there are too many: preferences, a Planner page's entries, a cartridge's edits. */
    private fun prune() {
        fun dropOldest(sel: (String) -> Boolean, max: Int) {
            val keys = items.values.filter { sel(it.key) }
            if (keys.size <= max) return
            keys.sortedBy { it.at }.take(keys.size - max).forEach { items.remove(it.key) }
        }
        dropOldest({ !it.startsWith("@") }, MAX_PREFS)
        dropOldest({ it.startsWith("@") }, MAX_SESSION)
        dropOldest({ it.startsWith("@dtc/") }, MAX_DTC)
    }

    private fun load() {
        if (loaded) return
        loaded = true
        val f = File(folder, FILE_NAME)
        runCatching { if (f.isFile) lenient.decodeFromString(Stored.serializer(), f.readText(Charsets.UTF_8)).items else emptyList() }
            .onFailure { BridgeLog.warn("$FILE_NAME could not be read: ${it.message}") }
            .getOrDefault(emptyList())
            .filter { KEY.matches(it.key) }
            .sortedBy { it.at }
            .forEach { items[it.key] = it; if (it.at > lastAt) lastAt = it.at }
    }

    /** A write two seconds from now (one for every change made meanwhile), on a thread of its own. */
    private fun scheduleWrite() {
        writeDue = true
        if (writer?.isAlive == true) return
        writer = Thread({
            try { Thread.sleep(WRITE_DELAY_MS) } catch (_: InterruptedException) {}
            synchronized(lock) { if (writeDue) { writeDue = false; lastError = persist() } }
        }, "mission-sync-write").apply { isDaemon = true; start() }
    }

    /** Writes now what waits to be written (the program closing, a check). */
    fun flush() = synchronized(lock) { if (writeDue) { writeDue = false; lastError = persist() } }

    /** Writes the items: a temporary file beside them, then an atomic move. Null when they are on disk, else the sentence. */
    private fun persist(): String? {
        val dir = folder
        return try {
            if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) return "The Mission section's state could not be kept on the PC: its settings folder could not be made. It is held until BMS Companion closes."
            val target = File(dir, FILE_NAME)
            val tmp = File(dir, "$FILE_NAME.tmp")
            try {
                tmp.writeText(lenient.encodeToString(Stored.serializer(), Stored(items = items.values.toList())), Charsets.UTF_8)
                try {
                    Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                runCatching { if (tmp.exists()) tmp.delete() }
            }
            null
        } catch (e: Throwable) {
            BridgeLog.warn("The Mission section's state could not be kept in ${dir.path}: ${e.message}")
            "The Mission section's state could not be kept on the PC (${e.message ?: e::class.simpleName}). It is held until BMS Companion closes."
        }
    }
}
