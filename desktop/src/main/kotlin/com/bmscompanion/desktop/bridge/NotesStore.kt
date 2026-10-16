package com.bmscompanion.desktop.bridge

import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.mission.LedgerMission
import com.bmscompanion.app.data.mission.NotesDoc
import com.bmscompanion.app.data.mission.NotesState
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The pilot's noteboards (1.4.1, `GET /api/notes`, `POST /api/notes`; docs/PROTOCOL.md, "The Noteboard"): what the
 * Upd Kneeboard's Noteboard pages print and the VR Notes board shows, kept on this PC so every device sees the same.
 *
 * **Kept per mission**, by a key made of the flight the Mission section shows now ([keyNow]: the theater, the flight's
 * callsign and its package — the printed briefing's in EZBoards mode, the populated flight's in WDP mode, so a PRINT
 * of the same flight again, or a switch of mode with the same flight, keeps its notes). The last [MAX_MISSIONS]
 * missions' notes are kept; older ones go. A mission with nothing written starts from the newest notes marked
 * **Keep for the next mission** ([NotesDoc.keep]), copied, and the answer says so ([NotesState.carried]).
 *
 * In `%APPDATA%\BMS Companion\notes.json`, **never in the BMS folder** (they are the pilot's, not Falcon BMS's), through
 * a temporary file and an atomic move. Nothing here throws: a change that could not be written is still held in memory
 * (devices see it until the program closes) and the answer carries the sentence ([NotesState.error]).
 */
object NotesStore {
    const val FILE_NAME = "notes.json"

    /** The missions whose notes are kept, newest first. */
    const val MAX_MISSIONS = 12

    /** A noteboard is a page of text: anything this size is not one. */
    const val MAX_BODY_BYTES = 256 * 1024

    @Serializable
    private data class Stored(val version: Int = 1, val missions: List<NotesDoc> = emptyList())

    private val lenient = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true; explicitNulls = false }
    private val lock = Any()
    private var loaded = false
    private var missions: List<NotesDoc> = emptyList()
    private var lastError: String? = null

    /** A developer check keeps the notes in a scratch folder; null = `%APPDATA%\BMS Companion`. */
    @Volatile internal var folderOverride: File? = null
    private val folder: File get() = folderOverride ?: Repo.settingsFolder

    /** Drops what is held in memory, so the next request reads the file again (for the checks). */
    internal fun reset() = synchronized(lock) { loaded = false; missions = emptyList(); lastError = null }

    fun handle(req: ApiRequest): ApiResponse = try {
        when (req.method) {
            "GET" -> ApiResponse.json(Bridge.json.encodeToString(NotesState.serializer(), state()))
            "POST" -> {
                if (req.body.size > MAX_BODY_BYTES) ApiResponse.json("""{"error":"Those notes are too long to keep."}""", 400)
                else {
                    val doc = runCatching { lenient.decodeFromString(NotesDoc.serializer(), req.body.decodeToString()) }.getOrNull()
                    if (doc == null) ApiResponse.json("""{"error":"Those are not noteboards."}""", 400)
                    else ApiResponse.json(Bridge.json.encodeToString(NotesState.serializer(), save(doc)))
                }
            }
            else -> ApiResponse.json("""{"error":"GET or POST"}""", 405)
        }
    } catch (e: Throwable) {
        BridgeLog.warn("/api/notes: ${e.message}")
        ApiResponse.json(Bridge.json.encodeToString(NotesState.serializer(), NotesState(error = "The notes could not be read: ${e.message ?: e::class.simpleName}")), 500)
    }

    /** The notes of the mission now, or the newest kept ones, or none. */
    fun state(): NotesState {
        val (key, words) = keyNow()
        return synchronized(lock) {
            load()
            val own = missions.firstOrNull { it.key == key }
            // notes kept for the next mission carry over within the same install only
            val kept = if (own == null) missions.filter { it.keep && installOf(it.key) == installOf(key) }.maxByOrNull { it.modified } else null
            NotesState(key, words, own ?: kept?.copy(key = key), carried = own == null && kept != null, error = lastError)
        }
    }

    /**
     * Keeps [doc] for the mission it names ([NotesDoc.key], which the device read with it: a change finished after the
     * mission changed lands on the mission it was written for). Answers the state of the mission now.
     */
    fun save(doc: NotesDoc): NotesState {
        synchronized(lock) {
            load()
            val d = doc.copy(modified = System.currentTimeMillis())
            missions = (listOf(d) + missions.filter { it.key != d.key }).take(MAX_MISSIONS)
            lastError = persist()
        }
        return state()
    }

    /**
     * The mission now as a key, and in words: the theater, the flight's callsign and its package, of the populated
     * flight in WDP mode or of the printed briefing in EZBoards mode. ("", null) with neither.
     */
    fun keyNow(): Pair<String, String?> {
        val m = runCatching {
            if (MissionSource.wdp) LedgerMission.ofPopulated(MissionSource.populated())
            else LedgerMission.ofBriefing(Bridge.currentBriefing(), Bridge.printedAt(), Bridge.install.theater)
        }.getOrNull() ?: return "" to null
        fun norm(s: String?) = s.orEmpty().filter { it.isLetterOrDigit() }.lowercase()
        // + the install (1.4.1, Installs): "|@<hex of its folder>" when another install than the registry's newest is
        // read, so two installs flying the same flight keep their own notes; the default install's key is as before,
        // so the notes kept before 1.4.1 stay its own
        val key = listOf(norm(m.theater), norm(m.callsign), m.packageId?.toString().orEmpty()).joinToString("|") + installTag()
        val words = listOfNotNull(m.callsign.trim().ifEmpty { null }, m.packageId?.let { "package $it" }, m.theater?.trim()?.ifEmpty { null }).joinToString(" · ")
        return key to words.ifEmpty { null }
    }

    /** The install part of a key: "" for the registry's newest install (and every key from before 1.4.1). */
    internal fun installTag(): String = runCatching {
        if (Bridge.settings.value.BmsDirOverride.isNullOrBlank()) "" else Bridge.install.baseDir?.let { "|@" + Installs.idOf(it).removePrefix("a:") } ?: ""
    }.getOrDefault("")

    private fun installOf(key: String): String = key.substringAfter("|@", "")

    private fun load() {
        if (loaded) return
        loaded = true
        val f = File(folder, FILE_NAME)
        missions = runCatching { if (f.isFile) lenient.decodeFromString(Stored.serializer(), f.readText(Charsets.UTF_8)).missions else emptyList() }
            .onFailure { BridgeLog.warn("$FILE_NAME could not be read: ${it.message}") }
            .getOrDefault(emptyList())
    }

    /** Writes the notes: a temporary file beside them, then an atomic move. Null when they are on disk, else the sentence. */
    private fun persist(): String? {
        val dir = folder
        return try {
            if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) return "The notes could not be kept on the PC: its settings folder could not be made. They are held until BMS Companion closes."
            val target = File(dir, FILE_NAME)
            val tmp = File(dir, "$FILE_NAME.tmp")
            try {
                tmp.writeText(lenient.encodeToString(Stored.serializer(), Stored(missions = missions)), Charsets.UTF_8)
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
            BridgeLog.warn("The notes could not be kept in ${dir.path}: ${e.message}")
            "The notes could not be kept on the PC (${e.message ?: e::class.simpleName}). They are held until BMS Companion closes."
        }
    }
}
