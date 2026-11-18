package com.bmscompanion.desktop.bridge.command

import com.bmscompanion.desktop.bridge.DevGuard
import java.io.File

/**
 * The Command section's one-time copies of Falcon BMS's own files, kept **in the BMS folder beside them**:
 * `<campaigndir>\BMS Companion Backup\Command\` with a plain-English `README.txt` and a record (`Backup record.txt`:
 * name, size, SHA-256, modified time, why it was copied).
 *
 * - The first time the section opens a theater's campaign folder ([ensureFolder]), every file there recognised as
 *   BMS's or an add-on's ([ShippedSaves.recognise]: the starts, the shipped TEs and trainings, an add-on's missions)
 *   is copied once. From then on the record is the list of that folder's shipped files ([ShippedSaves] reads it).
 * - Before the section writes a BMS file in place — a campaign start under "Edit BMS's campaign start", or a
 *   theater-wide campaign database file (`Stations+Ils.dat`, `RadioMap.dat`, the TE lists…) — [backupOnce] makes
 *   sure its original is there.
 * - A copy is **never replaced by the section**: the first one is the original. [restore] puts it back (temp file,
 *   atomic move). The one exception is a **Falcon BMS update** ([refreshUpdated], run by [ensureFolder]): a recorded
 *   file that no longer matches its copy and carries an update's dates (modified more than a minute before it was
 *   created, the patch's own date kept) is BMS's new original, so the old copy moves to `Older copies\<date>\` and the
 *   new file is copied in its place. 4.38.2 replaced Korea's campaign starts this way, and Restore would otherwise
 *   have put the 4.38.1 files back over them.
 *
 * Nothing throws; every answer that is not null is a sentence for the page. Developer checks may not write the real
 * install ([DevGuard]).
 */
object CommandBackup {
    const val FOLDER = "BMS Companion Backup"
    const val SUB = "Command"
    const val RECORD = "Backup record.txt"
    const val README = "README.txt"
    const val OLDER = "Older copies"

    /** One copy: the file's name, size, hash and time as copied, and why it was copied. */
    class Entry(val name: String, val size: Long, val sha256: String, val modified: Long, val why: String)

    fun dirFor(folder: File): File = File(File(folder, FOLDER), SUB)

    /** The record of [folder]'s copies (empty when none was made). */
    fun list(folder: File): List<Entry> {
        val f = File(dirFor(folder), RECORD)
        if (!f.isFile) return emptyList()
        return runCatching {
            f.readLines(Charsets.UTF_8).mapNotNull { line ->
                if (line.isBlank() || line.startsWith("#")) return@mapNotNull null
                val c = line.split('\t')
                if (c.size < 4) null else Entry(c[0], c[1].toLongOrNull() ?: -1, c[2], c[3].toLongOrNull() ?: 0, c.getOrElse(4) { "" })
            }
        }.getOrDefault(emptyList())
    }

    /** The copy of [name] in [folder]'s backup, when there is one. */
    fun copyOf(folder: File, name: String): File? =
        list(folder).firstOrNull { it.name.equals(name, ignoreCase = true) }?.let { File(dirFor(folder), it.name) }?.takeIf { it.isFile }

    /** The folder's backup was made (the record exists). */
    fun made(folder: File): Boolean = File(dirFor(folder), RECORD).isFile

    /** What [ensureFolder] did: how many files it copied now, and the first reason a copy failed. */
    class Result(val copied: Int, val error: String?)

    /**
     * Copies, once, every file of campaign folder [folder] recognised as BMS's that is not backed up yet. Called when
     * the section opens a theater's folder and before any write there. Never throws.
     */
    fun ensureFolder(folder: File): Result {
        val refreshed = refreshUpdated(folder)
        if (refreshed.error != null) return refreshed
        // by name or objectives part always; by date (or the record) only a file the pilot never claimed — one claimed
        // and taken back since may hold the pilot's bytes, never BMS's original
        val found = ShippedSaves.recognise(folder).filter { it.firm || !MineFiles.everClaimed(it.file) }
        val recorded = list(folder)
        // an entry naming a pilot's save (an earlier build's mistake on a copied folder) is not trusted: it leaves the record
        val untrusted = recorded.filter { ShippedSaves.neverShipped(File(folder, it.name)) }
        val have = recorded.map { it.name.lowercase() }.toSet()
        val todo = found.filter { it.file.name.lowercase() !in have }
        if (todo.isEmpty() && untrusted.isEmpty() && made(folder)) return Result(refreshed.copied, null)
        DevGuard.refusal(folder.path)?.let { return Result(0, it) }
        if (untrusted.isNotEmpty()) writeRecord(folder, recorded - untrusted.toSet())?.let { return Result(0, it) }
        var copied = refreshed.copied
        var error: String? = null
        for (s in todo) {
            val why = add(folder, s.file, s.why)
            if (why == null) copied++ else if (error == null) error = why
        }
        if (!made(folder) && error == null) writeRecord(folder, list(folder))?.let { error = it }
        return Result(copied, error)
    }

    /**
     * Takes a Falcon BMS update's new files as the originals: every recorded file of [folder] whose bytes differ from
     * its copy and that an update laid down ([laidByUpdate]: modified more than a minute before it was created — an
     * updater keeps the patch's date, while a file BMS or this program writes is modified when it is created). The
     * earlier copy moves to `Older copies\<yyyy-MM-dd HHmm>\` (never deleted), the new file is copied and the record
     * says so. A file the section wrote in place, or the pilot changed, has no such dates and keeps its copy. Never
     * throws; [Result.copied] counts the files refreshed.
     */
    fun refreshUpdated(folder: File, now: Long = System.currentTimeMillis()): Result {
        val recorded = list(folder)
        if (recorded.isEmpty()) return Result(0, null)
        val stale = recorded.filter { e ->
            val f = File(folder, e.name)
            f.isFile && (f.length() != e.size || f.lastModified() != e.modified) && laidByUpdate(f) &&
                CommandFiles.sha256(f).let { it != null && it != e.sha256 }
        }
        if (stale.isEmpty()) return Result(0, null)
        DevGuard.refusal(folder.path)?.let { return Result(0, it) }
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HHmm").format(java.util.Date(now))
        val older = File(File(dirFor(folder), OLDER), stamp)
        var entries = recorded
        var done = 0
        for (e in stale) {
            val file = File(folder, e.name)
            val copy = File(dirFor(folder), e.name)
            val bytes = runCatching { file.readBytes() }
                .getOrElse { return Result(done, "${e.name} could not be read for its backup: ${CommandFiles.reason(it)}.") }
            if (copy.isFile) {
                val old = runCatching { copy.readBytes() }
                    .getOrElse { return Result(done, "The copy of ${e.name} could not be read: ${CommandFiles.reason(it)}.") }
                val kept = File(older, e.name)
                CommandFiles.writeAtomic(kept, old)?.let { return Result(done, "The earlier copy of ${e.name} could not be kept in ${older.path}: $it.") }
                runCatching { kept.setLastModified(e.modified) }
            }
            CommandFiles.writeAtomic(copy, bytes)?.let { return Result(done, "${e.name} could not be backed up into ${dirFor(folder).path}: $it.") }
            runCatching { copy.setLastModified(file.lastModified()) }
            entries = entries.filterNot { it.name.equals(e.name, ignoreCase = true) } +
                Entry(e.name, bytes.size.toLong(), CommandFiles.sha256(bytes), file.lastModified(),
                    "updated by Falcon BMS (the earlier copy is in $OLDER\\$stamp); before: ${e.why}".take(300))
            done++
        }
        writeRecord(folder, entries)?.let { return Result(done, it) }
        return Result(done, null)
    }

    /** Laid down by an installer or an updater: modified more than a minute before it was created. */
    fun laidByUpdate(f: File): Boolean = runCatching {
        val a = java.nio.file.Files.readAttributes(f.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java)
        a.lastModifiedTime().toMillis() < a.creationTime().toMillis() - 60_000L
    }.getOrDefault(false)

    /**
     * Makes sure [file]'s original is in its folder's backup before it is written in place ([why] goes into the
     * record). Null when the copy is there (made now or before), else the reason — the write must not go ahead then.
     */
    fun backupOnce(file: File, why: String): String? {
        val folder = file.absoluteFile.parentFile ?: return "${file.name} has no folder."
        // a copy BMS's own update has made stale is not the original any more
        refreshUpdated(folder).error?.let { return it }
        if (copyOf(folder, file.name) != null) return null
        DevGuard.refusal(folder.path)?.let { return it }
        return add(folder, file, why)
    }

    /** Puts back the original of [name] in [folder] (temp file beside it, atomic move). Null when done. */
    fun restore(folder: File, name: String): String? {
        // never put back a file an update of Falcon BMS has since replaced
        refreshUpdated(folder).error?.let { return it }
        val copy = copyOf(folder, name) ?: return "There is no copy of $name in ${File(FOLDER, SUB)}."
        DevGuard.refusal(folder.path)?.let { return it }
        val e = list(folder).first { it.name.equals(name, ignoreCase = true) }
        val bytes = runCatching { copy.readBytes() }.getOrElse { return "The copy of $name could not be read: ${CommandFiles.reason(it)}." }
        if (e.sha256.isNotEmpty() && CommandFiles.sha256(bytes) != e.sha256) return "The copy of $name is not the file that was backed up (its contents changed): it was not put back."
        val target = File(folder, name)
        CommandFiles.writeAtomic(target, bytes)?.let { return "$name could not be put back: $it." }
        runCatching { target.setLastModified(e.modified) }
        return null
    }

    // ---------------------------------------------------------------- inside

    private fun add(folder: File, file: File, why: String): String? {
        val dir = dirFor(folder)
        val bytes = runCatching { file.readBytes() }.getOrElse { return "${file.name} could not be read for its backup: ${CommandFiles.reason(it)}." }
        val target = File(dir, file.name)
        if (!target.exists()) {
            CommandFiles.writeAtomic(target, bytes)?.let { return "${file.name} could not be backed up into ${dir.path}: $it." }
            runCatching { target.setLastModified(file.lastModified()) }
        }
        val entries = list(folder).filterNot { it.name.equals(file.name, ignoreCase = true) } +
            Entry(file.name, bytes.size.toLong(), CommandFiles.sha256(bytes), file.lastModified(), why)
        return writeRecord(folder, entries)
    }

    private fun writeRecord(folder: File, entries: List<Entry>): String? {
        val dir = dirFor(folder)
        val text = buildString {
            append("# Falcon BMS's own files in this folder, copied once by BMS Companion's Command section before it wrote anything here.\r\n")
            append("# name\tsize\tsha256\tmodified (ms since 1970)\twhy\r\n")
            entries.sortedBy { it.name.lowercase() }.forEach { append("${it.name}\t${it.size}\t${it.sha256}\t${it.modified}\t${it.why}\r\n") }
        }
        CommandFiles.writeAtomic(File(dir, RECORD), text.toByteArray(Charsets.UTF_8))?.let { return "The backup record could not be written: $it." }
        val readme = File(dir, README)
        if (!readme.exists()) CommandFiles.writeAtomic(readme, README_TEXT.replace("\n", "\r\n").toByteArray(Charsets.UTF_8))?.let { return "The backup's README could not be written: $it." }
        return null
    }

    private val README_TEXT = """
        BMS Companion - Command section: original Falcon BMS files
        ===========================================================

        This folder holds untouched copies of Falcon BMS's own files from the folder above it (the
        campaign starts, the training missions and TEs that came with Falcon BMS or an add-on theater,
        and any campaign database file BMS Companion's Command section was later allowed to change).
        Each file was copied here once, before BMS Companion could write anything in that folder, and a
        copy here is never replaced by BMS Companion. When a Falcon BMS update replaces one of these
        files, the new file becomes the original and the earlier copy is moved, not deleted, to
        "Older copies\<date>". "Backup record.txt" lists every copy with its size and checksum.

        BMS Companion saves your edits as a copy under a new name by default. It writes one of these
        files in place only when you choose "Edit BMS's campaign start" (or edit a campaign database
        file), and its Command section has a "Restore BMS's original" button that puts the copy back.

        To restore a file by hand, without BMS Companion:
          1. Close Falcon BMS.
          2. Copy the file from this folder into the folder above "BMS Companion Backup"
             (the campaign folder), replacing the file there.
        That is all: nothing else was changed.

        Keep this folder: if it is deleted, BMS Companion copies the files again as they are at that
        moment the next time its Command section opens this campaign folder.
    """.trimIndent() + "\n"
}
