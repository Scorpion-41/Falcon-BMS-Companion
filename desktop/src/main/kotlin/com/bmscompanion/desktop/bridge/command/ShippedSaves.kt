package com.bmscompanion.desktop.bridge.command

import com.bmscompanion.desktop.bridge.CampaignArchive
import com.bmscompanion.desktop.bridge.DevGuard
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes

/**
 * Whose a save is — Falcon BMS's (a campaign start, a TE or training that came with BMS or an add-on theater) or the
 * pilot's — worked out **at run time from the pilot's own install**; the app ships no list.
 *
 * In this order:
 * 1. `Auto Save.*` is BMS's working save, rewritten at every save: the pilot's.
 * 2. A file the Command section made with Save as (`<BMS>\User\BMS Companion Command\created.json`) is the pilot's —
 *    a copy of a start keeps its objectives part.
 * 3. A start by name (`Save<n>`, `Instant`, `Te_New*`, `TvT_Start`) or a shipped mission by name (`TE_BMS_*`,
 *    `TR_BMS_*`, `DF_BMS_*`).
 * 4. Named in the folder's backup record ([CommandBackup]): what was recognised the first time the section opened the
 *    folder stays BMS's, whatever its contents now.
 * 5. It has an objectives part: only a start does (BMS never writes one).
 * 6. Its bytes are those of a backed-up file (BMS's content under another name).
 * 7. Its modified time falls within two minutes of a file of the same folder that BMS's installer laid down (one
 *    whose modified time is older than its creation: the TE_BMS files, `Atc.ini`, the briefing scripts…).
 * 8. It came into the folder carrying an older date (modified before it was created), in the same hour as BMS's own
 *    files: an add-on's mission (Hellas WCP's `SAT 001`, Israel's `06-AIRSHOW`) — or a pilot's file copied in with
 *    the install, which is then saved as a copy too (the sentence says so). Never a `Save-Day …` save: that is the
 *    name BMS gives a pilot's campaign save.
 * Otherwise the file is the pilot's and may be overwritten (after asking).
 */
object ShippedSaves {
    enum class Kind { START, SHIPPED, PILOT }

    /** The answer: [kind], whether it is a campaign start ([start]: objectives editable after a backup), and why. */
    class Identity(
        val kind: Kind, val start: Boolean, val why: String,
        /** BMS's only by its dates (never by name or an objectives part): the pilot may say it is theirs ([MineFiles]) */
        val claimable: Boolean = false,
        /** the pilot said it is theirs ([MineFiles]): they may take that back */
        val mine: Boolean = false,
    ) {
        /** Overwrite may be offered (it still asks first). */
        val overwriteAllowed: Boolean get() = kind == Kind.PILOT
    }

    /** A file recognised as BMS's in a folder, with the reason; [firm] by its name or its objectives part (not by
     *  its dates or the backup record alone). */
    class Found(val file: File, val start: Boolean, val why: String, val firm: Boolean = false)

    private val START_NAME = Regex("^(save\\d+|instant|te_new.*|tvt_start)$", RegexOption.IGNORE_CASE)
    private val PILOT_SAVE_NAME = Regex("^Save-Day\\s", RegexOption.IGNORE_CASE)
    private val SHIPPED_NAME = Regex("^(te_bms_|tr_bms_|df_bms_).*", RegexOption.IGNORE_CASE)
    private val SAVE_EXT = setOf("cam", "tac", "trn")
    /** Kinds of file BMS's installer lays down in a campaign folder, whose times date the shipment. */
    private val DATA_EXT = setOf("b", "pri", "db", "ia", "lst", "dat", "xml", "thr", "wch", "idx", "txt", "rt", "tt", "bin", "rsc", "str", "ini", "tri", "wth")
    private const val CLUSTER_MS = 120_000L
    private const val SAME_INSTALL_MS = 3_600_000L

    fun isSave(f: File): Boolean = f.extension.lowercase() in SAVE_EXT
    fun startByName(base: String): Boolean = START_NAME.matches(base)

    /**
     * A name BMS's own start screens use as a start (read out of `Falcon BMS.exe`): New campaign opens `save0`-`save5`,
     * the saved-campaign list leaves out Save0-5 and Instant, the TE list leaves out te_new and te_new_nt, and a new TE
     * is always `te_new_nt.tac`. Every other file is listed as a saved game, even one holding an objectives part.
     */
    fun bmsStartSlot(base: String): Boolean = START_SLOT.matches(base)
    private val START_SLOT = Regex("^(save[0-5]|instant|te_new|te_new_nt)$", RegexOption.IGNORE_CASE)
    fun shippedByName(base: String): Boolean = SHIPPED_NAME.matches(base)
    fun isAutoSave(base: String): Boolean = base.equals("Auto Save", ignoreCase = true)

    /**
     * Never one of BMS's shipped files, whatever its dates or a record says: `Auto Save`, a `Save-Day …` campaign save
     * (the names BMS gives a pilot's saves) and the section's own copies. Checked before every other rule, and an entry
     * of the backup record naming such a file is not trusted (it is dropped at the next visit).
     */
    fun neverShipped(f: File): Boolean = neverShippedName(f.nameWithoutExtension) || CreatedCopies.contains(f) || MineFiles.contains(f)
    fun neverShippedName(base: String): Boolean = isAutoSave(base) || PILOT_SAVE_NAME.containsMatchIn(base)

    /** Why Save as may not use [target]'s name (it looks like BMS's own file), or null. */
    fun refuseName(target: File): String? {
        val base = target.nameWithoutExtension
        if (startByName(base)) return "\"$base\" is the name of one of Falcon BMS's campaign starts: choose another name."
        if (shippedByName(base)) return "\"$base\" looks like one of Falcon BMS's own missions (TE_BMS_, TR_BMS_, DF_BMS_): choose another name."
        if (isAutoSave(base)) return "\"Auto Save\" is the file BMS rewrites at every save: choose another name."
        val folder = target.absoluteFile.parentFile
        if (folder != null && !neverShippedName(base) && CommandBackup.list(folder).any { it.name.equals(target.name, ignoreCase = true) }) return "${target.name} is one of Falcon BMS's own files in this folder: choose another name."
        return null
    }

    /** Whose [file] is ([doc] when it is open, for its objectives part). Read only; never throws. */
    fun identify(file: File, doc: CommandSave? = null): Identity = try {
        identifyInner(file, doc)
    } catch (e: Throwable) {
        Identity(Kind.SHIPPED, false, "It could not be told whose ${file.name} is (${CommandFiles.reason(e)}): save your changes as a copy.")
    }

    private fun identifyInner(file: File, doc: CommandSave?): Identity {
        val base = file.nameWithoutExtension
        val hasObj = doc?.obj != null || (doc == null && hasObjPart(file))
        if (isAutoSave(base)) return Identity(Kind.PILOT, false, "Auto Save is BMS's working save, rewritten at every save.")
        if (CreatedCopies.contains(file)) return Identity(Kind.PILOT, hasObj, "${file.name} is a copy made by Save as here: yours.")
        if (PILOT_SAVE_NAME.containsMatchIn(base)) return Identity(Kind.PILOT, false, "${file.name} is a campaign save made in BMS (BMS names them Save-Day …): yours.")
        if (!hasObj && !startByName(base) && !shippedByName(base) && MineFiles.contains(file)) return Identity(Kind.PILOT, false, "${file.name} is yours (you said so: its dates had put it with Falcon BMS's own files).", mine = true)
        if (startByName(base)) return Identity(Kind.START, bmsStartSlot(base) || hasObj, "$base is one of Falcon BMS's campaign starts: save your changes as a copy, or choose Edit BMS's campaign start (its original is backed up first).")
        if (shippedByName(base)) return Identity(Kind.SHIPPED, hasObj, "$base is one of Falcon BMS's own missions: save your changes as a copy.")
        val folder = file.absoluteFile.parentFile
        val record = folder?.let { CommandBackup.list(it) }.orEmpty()
        if (record.any { it.name.equals(file.name, ignoreCase = true) } && !neverShipped(file)) {
            return if (hasObj) Identity(Kind.START, true, "${file.name} is a campaign start that came with this theater: save your changes as a copy, or choose Edit BMS's campaign start.")
            else Identity(Kind.SHIPPED, false, "${file.name} came with Falcon BMS or this theater (it is in the folder's backup record): save your changes as a copy.",
                claimable = record.firstOrNull { it.name.equals(file.name, ignoreCase = true) }?.why?.contains("dated", ignoreCase = true) == true)
        }
        if (hasObj) return Identity(Kind.START, true, "${file.name} has its own objectives, which only a campaign start has: save your changes as a copy, or choose Edit BMS's campaign start.")
        if (record.isNotEmpty()) {
            val hash = CommandFiles.sha256(file)
            if (hash != null && record.any { it.sha256 == hash }) return Identity(Kind.SHIPPED, false, "${file.name} holds exactly one of Falcon BMS's own files: save your changes as a copy.")
        }
        folder?.let { dated(it, file) }?.let { return Identity(Kind.SHIPPED, false, it, claimable = true) }
        return Identity(Kind.PILOT, false, "${file.name} is your own save.")
    }

    /**
     * Every save of [folder] recognised as BMS's by the run-time rules (names, objectives part, dates) and every file
     * the folder's backup record names that is still there. `Auto Save` and the section's own copies are left out.
     */
    fun recognise(folder: File): List<Found> {
        val files = listing(folder).filter { it.isFile && isSave(it) }
        val record = CommandBackup.list(folder).map { it.name.lowercase() }.toSet()
        val out = ArrayList<Found>()
        for (f in files) {
            val base = f.nameWithoutExtension
            if (neverShipped(f)) continue
            val obj = hasObjPart(f)
            val why = when {
                startByName(base) -> "campaign start (by name)"
                shippedByName(base) -> "Falcon BMS mission (by name)"
                obj -> "campaign start (objectives part)"
                f.name.lowercase() in record -> "in the backup record"
                else -> dated(folder, f)?.let { "dated with Falcon BMS's files" }
            } ?: continue
            out += Found(f, obj || startByName(base), why, firm = startByName(base) || shippedByName(base) || obj)
        }
        return out
    }

    // ---------------------------------------------------------------- the dates

    private class Times(val modified: Long, val created: Long)

    private fun times(f: File): Times? = runCatching {
        val a = Files.readAttributes(f.toPath(), BasicFileAttributes::class.java)
        Times(a.lastModifiedTime().toMillis(), a.creationTime().toMillis())
    }.getOrNull()

    /** The sentence when [file]'s dates put it with BMS's installed files of [folder], else null (rules 7 and 8). */
    private fun dated(folder: File, file: File): String? {
        val t = times(file) ?: return null
        val anchors = anchors(folder)
        if (anchors.isEmpty()) return null
        if (anchors.any { kotlin.math.abs(it.modified - t.modified) <= CLUSTER_MS }) {
            return "${file.name} carries the same date as Falcon BMS's own files in this folder: it came with BMS or this theater. Save your changes as a copy."
        }
        if (t.modified < t.created - 60_000 && anchors.any { kotlin.math.abs(it.created - t.created) <= SAME_INSTALL_MS }) {
            return "${file.name} came into this folder with Falcon BMS's files and an older date (an add-on's mission, or a file copied in with the install): save your changes as a copy."
        }
        return null
    }

    /**
     * Files of [folder] BMS's installer laid down: data kinds ([DATA_EXT]) and saves named as BMS names its own, modified
     * before they were created (the installer keeps the build's date) — never a save's companion of another name.
     */
    private fun anchors(folder: File): List<Times> {
        val all = listing(folder).filter { it.isFile }
        val pilotBases = all.filter { isSave(it) && !startByName(it.nameWithoutExtension) && !shippedByName(it.nameWithoutExtension) }
            .map { it.nameWithoutExtension.lowercase() }.toSet()
        return all.mapNotNull { f ->
            val ext = f.extension.lowercase()
            val base = f.nameWithoutExtension.substringBefore('.').lowercase()
            val bmsSave = ext in SAVE_EXT && (startByName(f.nameWithoutExtension) || shippedByName(f.nameWithoutExtension))
            val data = ext in DATA_EXT && base !in pilotBases && f.nameWithoutExtension.lowercase() !in pilotBases
            if (!bmsSave && !data) null else times(f)?.takeIf { it.modified < it.created - 60_000 }
        }
    }

    private fun listing(folder: File): List<File> = runCatching { folder.listFiles()?.toList() }.getOrNull().orEmpty()

    private fun hasObjPart(f: File): Boolean = runCatching { CampaignArchive.directoryOf(f).has("obj") }.getOrDefault(false)

    // ---------------------------------------------------------------- the section's own copies

    /**
     * The record of the copies the section made (`<BMS>\User\BMS Companion Command\created.json`): a file listed
     * there is the pilot's even when it has an objectives part. Paths are kept relative to the BMS folder.
     */
    object CreatedCopies {
        private const val FILE = "User/BMS Companion Command/created.json"

        private fun rootOf(f: File): File? {
            var d: File? = f.absoluteFile.parentFile
            while (d != null) {
                if (File(d, "Data").isDirectory && File(d, "User").isDirectory) return d
                d = d.parentFile
            }
            return null
        }

        private fun recordOf(f: File): File? = rootOf(f)?.let { File(it, FILE) }

        private fun rel(root: File, f: File): String = f.absoluteFile.relativeToOrSelf(root.absoluteFile).path.replace('\\', '/').lowercase()

        private fun read(record: File): MutableList<String> = runCatching {
            val text = record.readText(Charsets.UTF_8)
            Regex("\"path\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(text).map { it.groupValues[1].replace("\\\\", "\\").replace("\\\"", "\"") }.toMutableList()
        }.getOrDefault(ArrayList())

        fun contains(f: File): Boolean {
            val root = rootOf(f) ?: return false
            val record = File(root, FILE).takeIf { it.isFile } ?: return false
            return rel(root, f) in read(record)
        }

        /** Records [f] as made by the section. Null when done, else why not. */
        fun add(f: File): String? {
            val root = rootOf(f) ?: return null // not in a BMS folder: nothing to protect it from
            val record = File(root, FILE)
            DevGuard.refusal(root.path)?.let { return it }
            val paths = read(record)
            val p = rel(root, f)
            if (p !in paths) paths += p
            val json = buildString {
                append("{\n  \"note\": \"Saves made by BMS Companion's Command section (Save as). They are yours: the section may overwrite them.\",\n  \"copies\": [\n")
                paths.forEachIndexed { i, s ->
                    append("    { \"path\": \"").append(s.replace("\\", "\\\\").replace("\"", "\\\"")).append("\" }")
                    append(if (i < paths.size - 1) ",\n" else "\n")
                }
                append("  ]\n}\n")
            }
            return CommandFiles.writeAtomic(record, json.toByteArray(Charsets.UTF_8))
        }
    }
}
