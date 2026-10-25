package com.bmscompanion.desktop.bridge

import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.mission.PlannerStateAll
import com.bmscompanion.app.data.mission.PlannerStateEntry
import com.bmscompanion.app.data.mission.PlannerStatePost
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The planning every device shares (1.4.2, `GET`/`POST /api/planner/state`; docs/PROTOCOL.md "Planning on the PC"): the
 * Planner's open flight, the Upd Kneeboard plan and the Steps' ticks, the latest of each, whichever device made it. A
 * pilot who plans on the PC and then flies with a tablet finds the flight and the kneeboard plan already there
 * (`PlannerSync` on each device; the PC's own window is a client like any other, in-process).
 *
 * **Which is newer is the PC's clock alone**: a post says how long ago the change was made by the device's steady clock
 * ([PlannerStatePost.age]), and the entry's time is the PC's now less that, so a tablet whose clock is wrong never wins
 * or loses for it. A newer entry is never replaced by an older change: the answer then carries the PC's ([kept] false)
 * and the device takes it, keeping its own aside. A device's **weak** change (made by itself: a default laid) is kept
 * only when the PC has nothing the device has not seen.
 *
 * In `%APPDATA%\BMS Companion\planner-state.json` (never in the BMS folder), through a temporary file and an atomic move.
 * Nothing here throws: a change that could not be written is held in memory and the answer says so.
 */
object PlannerStateStore {
    const val FILE_NAME = "planner-state.json"
    private const val MAX_VALUE_BYTES = 512 * 1024
    private const val MAX_ENTRIES = 32
    /** how far back a post's age may go: a change older than this is taken as made this long ago */
    private const val MAX_AGE_MS = 7L * 24 * 3600 * 1000
    private val NAME = Regex("[a-z0-9_.-]{1,40}")

    @Serializable
    private data class Stored(val version: Int = 1, val entries: List<PlannerStateEntry> = emptyList())

    private val lenient = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true; explicitNulls = false }
    private val lock = Any()
    private var loaded = false
    private val entries = LinkedHashMap<String, PlannerStateEntry>()
    private var lastError: String? = null

    /** A developer check keeps the state in a scratch folder; null = `%APPDATA%\BMS Companion`. */
    @Volatile internal var folderOverride: File? = null
    private val folder: File get() = folderOverride ?: Repo.settingsFolder

    /** Drops what is held in memory, so the next request reads the file again (for the checks). */
    internal fun reset() = synchronized(lock) { loaded = false; entries.clear(); lastError = null }

    fun handle(req: ApiRequest): ApiResponse = try {
        when (req.method) {
            "GET" -> answer(all())
            "POST" -> {
                val p = if (req.body.size > MAX_VALUE_BYTES + 4096) null
                else runCatching { lenient.decodeFromString(PlannerStatePost.serializer(), req.body.decodeToString()) }.getOrNull()
                when {
                    p == null -> answer(PlannerStateAll(error = "That is not a piece of planning the PC keeps.", now = now()), 400)
                    !NAME.matches(p.name) -> answer(PlannerStateAll(error = "No such piece of planning: ${p.name.take(40)}.", now = now()), 400)
                    else -> answer(post(p))
                }
            }
            else -> ApiResponse.json("""{"error":"GET or POST"}""", 405)
        }
    } catch (e: Throwable) {
        BridgeLog.warn("/api/planner/state: ${e.message}")
        answer(PlannerStateAll(error = "The planning could not be read: ${e.message ?: e::class.simpleName}", now = now()), 500)
    }

    private fun answer(a: PlannerStateAll, status: Int = 200) = ApiResponse.json(Bridge.json.encodeToString(PlannerStateAll.serializer(), a), status)

    private fun now() = System.currentTimeMillis()

    /** Every entry kept. */
    fun all(): PlannerStateAll = synchronized(lock) {
        load()
        PlannerStateAll(entries.values.toList(), now = now(), error = lastError)
    }

    /** One device's change: kept when it is the newer (see the class), else the PC's answered with [PlannerStateAll.kept] false. */
    fun post(p: PlannerStatePost): PlannerStateAll = synchronized(lock) {
        load()
        val t = now()
        val had = entries[p.name]
        val at = t - p.age.coerceIn(0, MAX_AGE_MS)
        val keep = when {
            had == null -> true
            p.weak -> had.at <= p.seen
            else -> at >= had.at || had.at <= p.seen
        }
        if (!keep) return@synchronized PlannerStateAll(listOf(had!!), kept = false, now = t, error = lastError)
        // never earlier than what it replaces, so every device that saw the old one sees this as new
        val e = PlannerStateEntry(p.name, p.value, maxOf(at, (had?.at ?: 0L) + 1), p.by.take(20))
        entries.remove(p.name)
        entries[p.name] = e
        while (entries.size > MAX_ENTRIES) entries.remove(entries.keys.first())
        lastError = persist()
        PlannerStateAll(listOf(e), kept = true, now = t, error = lastError)
    }

    private fun load() {
        if (loaded) return
        loaded = true
        val f = File(folder, FILE_NAME)
        runCatching { if (f.isFile) lenient.decodeFromString(Stored.serializer(), f.readText(Charsets.UTF_8)).entries else emptyList() }
            .onFailure { BridgeLog.warn("$FILE_NAME could not be read: ${it.message}") }
            .getOrDefault(emptyList())
            .filter { NAME.matches(it.name) }
            .forEach { entries[it.name] = it }
    }

    /** Writes the entries: a temporary file beside them, then an atomic move. Null when they are on disk, else the sentence. */
    private fun persist(): String? {
        val dir = folder
        return try {
            if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) return "The planning could not be kept on the PC: its settings folder could not be made. It is held until BMS Companion closes."
            val target = File(dir, FILE_NAME)
            val tmp = File(dir, "$FILE_NAME.tmp")
            try {
                tmp.writeText(lenient.encodeToString(Stored.serializer(), Stored(entries = entries.values.toList())), Charsets.UTF_8)
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
            BridgeLog.warn("The planning could not be kept in ${dir.path}: ${e.message}")
            "The planning could not be kept on the PC (${e.message ?: e::class.simpleName}). It is held until BMS Companion closes."
        }
    }
}
