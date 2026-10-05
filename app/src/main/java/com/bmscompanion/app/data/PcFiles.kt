package com.bmscompanion.app.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.bmscompanion.app.data.mission.MissionLink
import com.bmscompanion.app.data.mission.PcAnswer
import com.bmscompanion.app.data.mission.PcFileEntry
import kotlinx.coroutines.CompletableDeferred
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * The Planner's file windows: what Weapon Delivery Planner does with `OpenFileDialog` and `SaveFileDialog` (Reload WX,
 * Load and Save DataCard, Open and Save Callsign.ini, the codewords and package timing, the DTC's backup files, the
 * pictures it saves), on every device. The files are always the **BMS PC's**: the caller gets a [PickedFile], a path on
 * that PC with the means to read or write it through the PC, and never touches the device's own disk.
 *
 * - On the PC's own window reading Falcon BMS on that PC ("This PC"), it is Windows' own file dialog, as WDP's is: the
 *   same title, start folder, file name, file types and Save's overwrite question ([native], set by the PC program).
 * - Anywhere else — a phone, a tablet, a browser, a PC window linked to another PC — it is a window listing the BMS
 *   PC's folders (`PcFileWindow`, drawn by `PcFileWindowHost`), served by the PC (`/api/files`).
 *
 * Both return null when the pilot cancels. Nothing here throws: a PC that cannot be reached is said in the window,
 * and a read or a write that fails answers with the PC's sentence in [PcAnswer.error].
 *
 * A start folder ([open]'s and [save]'s `startDir`) is a Windows path on the BMS PC or a place the PC resolves
 * itself, so a device that does not know where Falcon BMS is installed can still start where WDP does:
 * `@bms` (the BMS folder), `@config` (`User\Config`), `@campaign` (the campaign folder of the theater BMS is set to),
 * `@datacampaign` (`Data\Campaign`), `@planner` (the Planner's own folder, `User\BMS Companion Planner`),
 * `@datacards` (its DataCards folder, or the one the pilot chose), `@wdp` (Weapon Delivery Planner's own folder),
 * `@documents`, `@desktop`, `@downloads`, each optionally followed by `\sub\folder` (`@planner\Files\EWS`). A start
 * given as a file names the folder
 * it is in; a folder that does not exist opens the nearest one above it.
 */
object PcFiles {
    /**
     * Windows' own file dialog, where this device is the BMS PC: set by the PC program at start, null elsewhere.
     * [NativeFileDialogs.available] says whether it applies now (the PC window linked to another PC lists that
     * PC's folders instead).
     */
    var native: NativeFileDialogs? = null

    /**
     * For the headless checks: answers every request with the path it returns (null = cancelled) without showing a
     * window. Null in the app.
     */
    var testAnswer: ((FileRequest) -> String?)? = null

    /** The window on show on this device, or null. */
    internal var session: BrowseSession? by mutableStateOf(null)
        private set

    /** How many [com.bmscompanion.app.ui.screens.wdp.PcFileWindowHost]s are drawn: with none, nothing can be picked. */
    internal var hosts = 0

    /** A file window is on show on this device (the PC window's Escape leaves it to the window). */
    val isBrowsing: Boolean get() = session != null

    /**
     * WDP's `OpenFileDialog`: the file the pilot picked on the BMS PC, or null when they cancelled.
     *
     * [title] is WDP's `Title`, [startDir] its `InitialDirectory` (see the class comment), [fileName] its `FileName`,
     * [filters] its `Filter` ([FileFilter.parse] takes WDP's own string) and [filterIndex] its `FilterIndex` (0-based
     * here, where WDP's is 1-based).
     */
    suspend fun open(
        title: String,
        startDir: String? = null,
        filters: List<FileFilter> = emptyList(),
        fileName: String? = null,
        filterIndex: Int = 0,
    ): PickedFile? = pick(FileRequest(FileMode.OPEN, title, startDir, fileName, filters, filterIndex, null))

    /**
     * WDP's `SaveFileDialog`: where the pilot chose to save on the BMS PC, or null when they cancelled. A file that is
     * there already has been confirmed by the pilot (Windows' question on the PC, the window's elsewhere), so
     * [PickedFile.write] replaces it. [suggestedName] is WDP's `FileName`, [defaultExt] its `DefaultExt` ("ini", with
     * or without the dot): added to a name typed without one, as Windows does; by default the chosen type's first.
     */
    suspend fun save(
        title: String,
        startDir: String? = null,
        suggestedName: String = "",
        filters: List<FileFilter> = emptyList(),
        filterIndex: Int = 0,
        defaultExt: String? = null,
    ): PickedFile? = pick(FileRequest(FileMode.SAVE, title, startDir, suggestedName, filters, filterIndex, defaultExt?.trim()?.removePrefix(".")))

    /**
     * WDP's `FolderBrowserDialog` (its Settings' DataCard directory): the folder the pilot chose on the BMS PC, as a
     * [PickedFile] whose [PickedFile.path] is that folder, or null when they cancelled. [startDir] as for [open].
     */
    suspend fun folder(title: String, startDir: String? = null): PickedFile? =
        pick(FileRequest(FileMode.FOLDER, title, startDir, null, emptyList(), 0, null))

    private suspend fun pick(asked: FileRequest): PickedFile? {
        // WDP sometimes gives a whole path as the file name (the last file it opened): its folder is where to start
        val req = asked.fileName?.takeIf { '\\' in it || '/' in it }?.let { full ->
            val name = full.substringAfterLast('\\').substringAfterLast('/')
            asked.copy(startDir = asked.startDir ?: full.dropLast(name.length).trimEnd('\\', '/').ifEmpty { null }, fileName = name)
        } ?: asked
        testAnswer?.let { answer -> return answer(req)?.let { PickedFile(it, req.mode, req.filterIndex) } }
        val n = native
        if (n != null && n.available) {
            when (val a = runCatching { n.show(req) }.getOrElse { NativeAnswer.Failed(it.message ?: "error") }) {
                is NativeAnswer.Picked -> return PickedFile(a.path, req.mode, a.filterIndex)
                NativeAnswer.Cancelled -> return null
                // Windows' dialog could not be shown: the PC's own listing does the same job
                is NativeAnswer.Failed -> Unit
            }
        }
        return browse(req)
    }

    /** Shows the window and waits for the pilot. A second request while one is on show replaces it (the first is cancelled). */
    private suspend fun browse(req: FileRequest): PickedFile? {
        if (hosts <= 0) return null
        val s = BrowseSession(req, CompletableDeferred())
        session?.done?.complete(null)
        session = s
        try {
            return s.done.await()
        } finally {
            if (session === s) session = null
        }
    }

    /** The window's answer: [path] picked (with the file type it was picked under), or null for Cancel. */
    internal fun finish(s: BrowseSession, path: String?, filterIndex: Int) {
        s.done.complete(path?.let { PickedFile(it, s.request.mode, filterIndex) })
        if (session === s) session = null
    }

    /** One file on the BMS PC by its path, for a caller that already knows it (WDP's last file). */
    fun file(path: String): PickedFile = PickedFile(path, FileMode.OPEN, 0)

    /**
     * WDP's `Process.Start("Explorer.exe", folder)`: File Explorer on [folder] where this device is the BMS PC
     * ([native]), true when it opened. Elsewhere false: the caller shows the folder in the Planner's own window.
     */
    fun explore(folder: String): Boolean {
        if (testAnswer != null) return false
        val n = native ?: return false
        return n.available && runCatching { n.explore(folder) }.getOrDefault(false)
    }
}

/** Open or Save, or a folder chosen ([PcFiles.folder]). */
enum class FileMode { OPEN, SAVE, FOLDER }

/**
 * One file type of a file window, as WDP's `Filter` names it: [label] ("FMAP(*.fmap)") and the [extensions] it
 * shows, lower case without the dot (empty = every file).
 */
data class FileFilter(val label: String, val extensions: List<String>) {
    constructor(label: String, vararg extensions: String) : this(label, extensions.toList())

    /** "*.fmap" / "*.cam;*.tac;*.trn", as Windows' dialog is given it. */
    val pattern: String get() = if (extensions.isEmpty()) "*.*" else extensions.joinToString(";") { "*.$it" }

    companion object {
        /** Every file. */
        val ALL = FileFilter("All files (*.*)", emptyList())

        /**
         * WDP's own `Filter` string as a list: "FMAP(*.fmap)|*.fmap|TWX(*.twx)|*.twx" is two types, "all|*.cam;*.tac;*.trn|…"
         * a type showing three extensions. A string that is not pairs gives what it can.
         */
        fun parse(filter: String): List<FileFilter> {
            val parts = filter.split('|')
            return (0 until parts.size / 2).map { i ->
                val exts = parts[i * 2 + 1].split(';').map { it.trim().removePrefix("*").removePrefix(".").lowercase() }
                    .filter { it.isNotEmpty() && it != "*" }
                FileFilter(parts[i * 2].trim(), exts)
            }
        }
    }
}

/** What a file window was asked for. [filterIndex] is 0-based; [defaultExt] without the dot. */
data class FileRequest(
    val mode: FileMode,
    val title: String,
    val startDir: String?,
    val fileName: String?,
    val filters: List<FileFilter>,
    val filterIndex: Int,
    val defaultExt: String?,
)

/** Windows' own file dialog, where this device is the BMS PC (see [PcFiles.native]). */
interface NativeFileDialogs {
    /** True while the dialog applies: the PC window reading Falcon BMS on this PC. */
    val available: Boolean

    /** Shows the dialog for [request] and waits for the pilot. Must not throw; a failure is [NativeAnswer.Failed]. */
    suspend fun show(request: FileRequest): NativeAnswer

    /**
     * Opens Windows' File Explorer on [folder] (a path or a place, as a start folder is given), as WDP's Cards
     * Directory button does. True when Explorer was started. Must not throw.
     */
    fun explore(folder: String): Boolean = false
}

sealed interface NativeAnswer {
    /** [filterIndex] is the file type chosen when it was picked, 0-based. */
    data class Picked(val path: String, val filterIndex: Int) : NativeAnswer
    data object Cancelled : NativeAnswer
    /** The dialog could not be shown; the in-app window is shown instead. */
    data class Failed(val why: String) : NativeAnswer
}

/** A file window on show, and the answer its caller waits for. */
internal class BrowseSession(val request: FileRequest, val done: CompletableDeferred<PickedFile?>)

/**
 * A file on the BMS PC that a file window picked: its [path] there, and its content read and written by the PC
 * (`/api/files/read`, `/api/files/write`; in-process on the PC's own window). The PC reads and writes only the file
 * types the Planner uses, and refuses the rest in words.
 *
 * [mode] is the window it came from, [filterIndex] the file type (0-based) it was picked under, as WDP reads a
 * dialog's `FilterIndex` afterwards.
 */
class PickedFile(val path: String, val mode: FileMode, val filterIndex: Int) {
    /** "Viper.ini" */
    val name: String get() = path.substringAfterLast('\\').substringAfterLast('/')

    /** "G:\\Falcon BMS 4.38\\User\\Config": the folder it is in, as WDP's `Path.GetDirectoryName`. */
    val folder: String get() = path.substring(0, (path.length - name.length).coerceAtLeast(0)).trimEnd('\\', '/').let { if (it.length == 2 && it[1] == ':') "$it\\" else it }

    /** "ini": the extension, lower case, without the dot ("" for none). */
    val extension: String get() = name.substringAfterLast('.', "").lowercase()

    /** "Viper": the name without its extension, as WDP's `Path.GetFileNameWithoutExtension`. */
    val baseName: String get() = if ('.' in name) name.substringBeforeLast('.') else name

    /** The file's bytes, or the PC's sentence. */
    @OptIn(ExperimentalEncodingApi::class)
    suspend fun read(): PcAnswer<ByteArray> {
        val a = runCatching { MissionLink.filesRead(path) }.getOrElse { PcAnswer.unreachable(it.message ?: "error") }
        val v = a.value ?: return PcAnswer(error = a.error ?: "The PC did not send the file.", status = a.status)
        if (a.error != null) return PcAnswer(error = a.error, status = a.status)
        return runCatching { PcAnswer(value = Base64.Default.decode(v.data), status = a.status) }
            .getOrElse { PcAnswer(error = "The PC's copy of $name could not be read.", status = a.status) }
    }

    /** The file as text ([PcText.decode]: UTF-8 or UTF-16 when it says so, else Windows' own 8-bit text, as WDP reads it). */
    suspend fun readText(): PcAnswer<String> {
        val a = read()
        return PcAnswer(value = a.value?.let { PcText.decode(it) }, error = a.error, status = a.status)
    }

    /** Writes [bytes] as the file's whole content: replaces it when it is there (the window has asked), creates it when not. */
    @OptIn(ExperimentalEncodingApi::class)
    suspend fun write(bytes: ByteArray): PcAnswer<PcFileEntry> =
        runCatching { MissionLink.filesWrite(path, Base64.Default.encode(bytes), overwrite = true) }
            .getOrElse { PcAnswer.unreachable(it.message ?: "error") }

    /** Writes [text] ([PcText.encode]: Windows' 8-bit text as WDP writes an .ini, or UTF-8 when [utf8]). */
    suspend fun writeText(text: String, utf8: Boolean = false): PcAnswer<PcFileEntry> = write(PcText.encode(text, utf8))

    override fun toString() = path
}

/**
 * Text as Windows programs of WDP's age write it: an `.ini` is 8-bit text in the PC's own code page (Latin-1 here,
 * which is that code page for every character an `.ini` of WDP's holds), unless it starts with a byte-order mark.
 */
object PcText {
    fun decode(b: ByteArray): String {
        fun u(i: Int) = b[i].toInt() and 0xFF
        if (b.size >= 3 && u(0) == 0xEF && u(1) == 0xBB && u(2) == 0xBF) return b.decodeToString(3, b.size)
        if (b.size >= 2 && u(0) == 0xFF && u(1) == 0xFE) return utf16(b, 2, little = true)
        if (b.size >= 2 && u(0) == 0xFE && u(1) == 0xFF) return utf16(b, 2, little = false)
        // plain ASCII and valid UTF-8 read as UTF-8; anything else is 8-bit text
        val utf8 = runCatching { b.decodeToString(throwOnInvalidSequence = true) }.getOrNull()
        if (utf8 != null) return utf8
        val out = CharArray(b.size)
        for (i in b.indices) out[i] = u(i).toChar()
        return out.concatToString()
    }

    fun encode(text: String, utf8: Boolean = false): ByteArray {
        if (utf8) return text.encodeToByteArray()
        return ByteArray(text.length) { i -> text[i].code.let { if (it > 255) '?'.code else it }.toByte() }
    }

    private fun utf16(b: ByteArray, from: Int, little: Boolean): String {
        val n = (b.size - from) / 2
        val out = CharArray(n)
        for (i in 0 until n) {
            val lo = b[from + i * 2].toInt() and 0xFF
            val hi = b[from + i * 2 + 1].toInt() and 0xFF
            out[i] = (if (little) (hi shl 8) or lo else (lo shl 8) or hi).toChar()
        }
        return out.concatToString()
    }
}
