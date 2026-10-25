package com.bmscompanion.desktop.bridge.command

import com.bmscompanion.desktop.bridge.DevGuard
import java.io.File

/**
 * Writes an open save (the Command section's saving rules):
 *
 * - **Save as** ([saveAs]) is the main way: a new file (proposed `<name> (edited)` beside the old, [proposedName]),
 *   its header's `SaveFile` and its parts' names set to the new name, `Scenario` kept, and the companions copied to
 *   the new name (`.twx .fmap .ini .iff .frc .his .l16.txtpb .skn`, and `.tri` from a start). A name that looks like
 *   BMS's own is refused ([ShippedSaves.refuseName]); an existing file is replaced only when the caller asked the
 *   pilot first ([saveAsConflicts] lists them). The copy is recorded as the pilot's ([ShippedSaves.CreatedCopies]).
 * - **Overwrite** ([overwrite]) only a pilot's own file ([ShippedSaves.Identity.overwriteAllowed]), after the page asked;
 *   refused when the file changed on disk since it was read (BMS saved over it), unless [evenIfChanged].
 * - **Edit BMS's campaign start** ([overwriteStart]): a start in place, only once its original is in the folder's
 *   backup ([CommandBackup.backupOnce]).
 *
 * Every write keeps bytes: a part nobody changed goes back exactly as stored; a changed one is re-packed with
 * [Lzss.encode] under BMS's header; the directory is rebuilt in the original order with nothing after it; `.ver` is
 * never changed. The result is written to a temp file beside the target, read back and **verified** — the directory,
 * every part walking exactly, untouched parts byte-identical, changed ones unpacking to what was meant — then moved
 * over the target atomically. No refusal while BMS runs. Nothing throws: a failure is a [Result] whose [Result.message]
 * is the sentence for the error dialog, and the target is left as it was. Developer checks may not write the real
 * install ([DevGuard]).
 */
object CommandWriter {
    /** Companion files of a save, copied under the new name by Save as. */
    val COMPANIONS = listOf(".twx", ".fmap", ".ini", ".iff", ".frc", ".his", ".l16.txtpb", ".skn")

    /** Falcon BMS keeps the directory in fixed arrays of 32 entries (a 33rd overwrites what follows them). */
    const val MAX_PARTS = 32

    class Result(
        val ok: Boolean,
        val file: File?,
        val message: String,
        /** companions copied (Save as) */
        val companions: List<String> = emptyList(),
        /** parts written re-packed (the rest went back byte for byte) */
        val repacked: List<String> = emptyList(),
    )

    /**
     * "<name> (edited)" in the same folder, with a number when that is taken. A copy of one of BMS's own missions or
     * starts is never named like one ([ShippedSaves.refuseName] refuses "TE_BMS_01 … (edited)" and "Te_New (edited)"),
     * so it is offered as "Edited <name>".
     */
    fun proposedName(doc: CommandSave): File {
        val dir = doc.file.absoluteFile.parentFile
        val base = doc.file.nameWithoutExtension
        val ext = doc.file.extension
        val bmsLike = ShippedSaves.refuseName(File(dir, "$base (edited).$ext")) != null
        fun name(n: Int) = if (bmsLike) "Edited $base" + (if (n > 1) " $n" else "") else "$base (edited" + (if (n > 1) " $n" else "") + ")"
        var n = 1
        var f = File(dir, "${name(n)}.$ext")
        while ((f.exists() || ShippedSaves.refuseName(f) != null) && n < 999) { n++; f = File(dir, "${name(n)}.$ext") }
        return f
    }

    /** Files Save as to [target] would replace (the save and its companions): the page asks before going on. */
    fun saveAsConflicts(doc: CommandSave, target: File): List<File> {
        val out = ArrayList<File>()
        if (target.exists()) out += target
        companionPairs(doc, target).forEach { (_, to) -> if (to.exists()) out += to }
        return out
    }

    /**
     * Save as [target]; [replace] when the pilot agreed to replace [saveAsConflicts]. [ownObjectives] (a copy of a
     * start only) also sets the copy's `Scenario` to its own name, spelled as the file is: BMS then reads the copy's
     * own objectives (and no deltas) — otherwise a copy keeps `Scenario` and flies its start's objectives, edits to its
     * `.obj` unseen. Saves made from such a copy name it, so it must then stay in its folder.
     */
    fun saveAs(doc: CommandSave, target: File, replace: Boolean = false, ownObjectives: Boolean = false): Result = guarded(target) {
        if (!ShippedSaves.isSave(target)) return@guarded fail(target, "A save must be a .cam, .tac or .trn file.")
        if (target.absoluteFile == doc.file.absoluteFile) return@guarded fail(target, "That is the open file itself: use Overwrite to write it in place.")
        ShippedSaves.refuseName(target)?.let { return@guarded fail(target, it) }
        if (target.extension.lowercase() != doc.file.extension.lowercase()) return@guarded fail(target, "Keep the extension .${doc.file.extension}: BMS reads a campaign, a TE and a training differently.")
        val conflicts = saveAsConflicts(doc, target)
        if (conflicts.isNotEmpty() && !replace) return@guarded fail(target, "${conflicts.joinToString { it.name }} already exist${if (conflicts.size == 1) "s" else ""}: confirm to replace.")
        val folder = target.absoluteFile.parentFile ?: return@guarded fail(target, "${target.path} has no folder.")
        DevGuard.refusal(folder.path)?.let { return@guarded fail(target, it) }
        // BMS's files of that folder are backed up before the section writes anything there (best effort for a new file)
        CommandBackup.ensureFolder(folder)
        val newBase = target.nameWithoutExtension
        val (bytes, repacked, why) = build(doc, newBase, if (ownObjectives && doc.obj != null) newBase else null)
        if (bytes == null) return@guarded fail(target, why ?: "The save could not be put together.")
        write(doc, bytes, target)?.let { return@guarded fail(target, it) }
        val copied = ArrayList<String>()
        val problems = ArrayList<String>()
        for ((from, to) in companionPairs(doc, target)) {
            if (!from.isFile) continue
            CommandFiles.copyAtomic(from, to)?.let { problems += "${to.name}: $it" } ?: run { copied += to.name }
        }
        ShippedSaves.CreatedCopies.add(target)?.let { problems += "the record of copies: $it" }
        val msg = "Saved as ${target.name}" + (if (copied.isNotEmpty()) ", with ${copied.joinToString()}" else "") + "." +
            (if (problems.isNotEmpty()) " Not copied: ${problems.joinToString("; ")}." else "")
        Result(true, target, msg, copied, repacked)
    }

    /** Writes the open file in place (a pilot's own save; the page asked first). */
    fun overwrite(doc: CommandSave, evenIfChanged: Boolean = false): Result = guarded(doc.file) {
        val id = doc.identity
        if (!id.overwriteAllowed) return@guarded fail(doc.file, id.why)
        inPlace(doc, evenIfChanged)
    }

    /** "Edit BMS's campaign start": writes a start in place once its original is backed up. */
    fun overwriteStart(doc: CommandSave, evenIfChanged: Boolean = false): Result = guarded(doc.file) {
        if (!doc.identity.start) return@guarded fail(doc.file, "${doc.file.name} is not a campaign start.")
        DevGuard.refusal(doc.file.absoluteFile.parentFile?.path)?.let { return@guarded fail(doc.file, it) }
        CommandBackup.backupOnce(doc.file, "campaign start, before Edit BMS's campaign start")?.let {
            return@guarded fail(doc.file, "Its original could not be backed up, so it was not written: $it")
        }
        inPlace(doc, evenIfChanged)
    }

    private fun inPlace(doc: CommandSave, evenIfChanged: Boolean): Result {
        val f = doc.file
        DevGuard.refusal(f.absoluteFile.parentFile?.path)?.let { return fail(f, it) }
        if (!evenIfChanged && f.exists() && (f.lastModified() != doc.diskModified || f.length() != doc.diskLength)) {
            return fail(f, "${f.name} changed on disk since it was opened (Falcon BMS may have saved over it). Reload it, or save your changes as a copy.")
        }
        f.absoluteFile.parentFile?.let { CommandBackup.ensureFolder(it) }
        val (bytes, repacked, why) = build(doc, null)
        if (bytes == null) return fail(f, why ?: "The save could not be put together.")
        write(doc, bytes, f)?.let { return fail(f, it) }
        doc.diskModified = f.lastModified()
        doc.diskLength = f.length()
        doc.edits.markSaved()
        return Result(true, f, "${f.name} saved.", emptyList(), repacked)
    }

    // ---------------------------------------------------------------- putting the file together

    /** The file's bytes ([newBase] renames the parts and sets `SaveFile`), the parts re-packed, or why it failed. */
    fun build(doc: CommandSave, newBase: String?, newScenario: String? = null): Triple<ByteArray?, List<String>, String?> {
        val c = doc.container
        if (c.error != null) return Triple(null, emptyList(), "${doc.file.name} could not be read as a save, so it cannot be written: ${c.error}")
        if (doc.version < CommandSave.OLDEST) return Triple(null, emptyList(), "${doc.file.name} is in an older format than Falcon BMS 4.38's; it is not written.")
        if (newBase != null && newBase.toByteArray(ANSI).size > 39) return Triple(null, emptyList(), "The name \"$newBase\" is longer than the 39 characters BMS keeps for a save's name.")
        val oldSaveFile = doc.cmp?.saveFile
        val oldScenario = doc.cmp?.scenario
        if (newScenario != null && doc.cmp != null) doc.cmp.scenario = oldScenario!!.copy().also { it.text = newScenario }
        if (newBase != null && doc.cmp != null) doc.cmp.saveFile = oldSaveFile!!.copy().also { it.text = newBase }
        try {
            val parts = ArrayList<Pair<String, ByteArray>>()
            val repacked = ArrayList<String>()
            for (e in c.entries) {
                val name = if (newBase != null) e.renamed(newBase) else e.name
                val enc = encodePart(doc, e.ext)
                val stored = when {
                    enc == null -> e.stored
                    e.packed -> if (e.unpacked != null && enc.first.contentEquals(e.unpacked) && enc.second == e.records) e.stored
                        else SaveContainer.pack(e.ext, enc.first, enc.second).also { repacked += e.ext }
                    else -> enc.first.also { if (!it.contentEquals(e.stored)) repacked += e.ext }
                }
                parts += name to stored
            }
            return Triple(SaveContainer.build(parts), repacked, null)
        } catch (t: Throwable) {
            return Triple(null, emptyList(), "The save could not be put together: ${CommandFiles.reason(t)}.")
        } finally {
            if (newBase != null && doc.cmp != null && oldSaveFile != null) doc.cmp.saveFile = oldSaveFile
            if (newScenario != null && doc.cmp != null && oldScenario != null) doc.cmp.scenario = oldScenario
        }
    }

    /** A modelled part's unpacked bytes and record count, or null to keep it as stored (not modelled or not exact). */
    private fun encodePart(doc: CommandSave, ext: String): Pair<ByteArray, Int>? {
        if (!doc.partEditable(ext)) return null
        return when (ext) {
            "cmp" -> doc.cmp!!.encode() to 0
            "uni" -> doc.uni!!.let { it.encode() to it.count }
            "obj" -> doc.obj!!.let { if (it.headerOnly && it.objectives.isEmpty()) null else it.encode() to it.count }
            "obd" -> doc.obd!!.let { it.encode() to it.count }
            "tea" -> doc.tea!!.encode() to 0
            "evt" -> doc.evt!!.encode() to 0
            "pol" -> doc.pol!!.encode() to 0
            "plt" -> doc.plt!!.encode() to 0
            "pst" -> doc.pst!!.encode() to 0
            else -> null
        }
    }

    /**
     * Checks [bytes] read back as a save: the directory has the parts in order, every modelled part that walked
     * exactly in [doc] walks exactly again, and a part not re-packed is byte-identical to [doc]'s. Null when it holds.
     */
    fun verify(doc: CommandSave, bytes: ByteArray, target: File): String? {
        val back = CommandSave.fromBytes(target, bytes, doc.theaters, withStart = false)
        val c = back.container
        if (c.error != null) return "the file written does not read back as a save (${c.error})"
        if (c.tail != 0) return "the file written has ${c.tail} bytes after its directory"
        // what Falcon BMS itself relies on: it checks nothing, so these must hold
        if (c.entries.size > MAX_PARTS) return "the file would have ${c.entries.size} parts; Falcon BMS reads at most $MAX_PARTS"
        c.entries.groupBy { it.ext }.entries.firstOrNull { it.value.size > 1 }?.let { return "the file would hold two .${it.key} parts; Falcon BMS reads only the first" }
        if (c.entry("cmp") == null) return "the file would have no campaign header (.cmp), without which Falcon BMS cannot load it"
        val orig = doc.container.entries
        if (c.entries.size != orig.size) return "the file written has ${c.entries.size} parts, not ${orig.size}"
        // deltas must name objectives of the start: BMS drops every delta after the first unknown one
        if (doc.objectives.isNotEmpty() && back.obd != null) {
            val ids = doc.objectives.mapTo(HashSet()) { it.id }
            val before = doc.container.entry("obd")?.unpacked?.let { u -> runCatching { ObdPart.read(u, doc.container.entry("obd")!!.records).deltas.map { it.id }.toSet() }.getOrNull() }.orEmpty()
            back.obd.deltas.firstOrNull { it.id !in ids && it.id !in before }?.let { return "an objective delta names ${it.id}, which the start does not have (Falcon BMS would drop every delta after it)" }
        }
        for ((i, e) in orig.withIndex()) {
            val n = c.entries[i]
            if (n.ext != e.ext) return "part ${i + 1} of the file written is .${n.ext}, not .${e.ext}"
            if (n.packed && n.unpacked == null) return "the .${n.ext} part written does not unpack"
            if (doc.partEditable(e.ext) && !back.partEditable(e.ext)) return "the .${e.ext} part written does not read back exactly"
            val enc = encodePart(back, e.ext)
            if (enc != null && !enc.first.contentEquals(n.content)) return "the .${e.ext} part written does not read back to the same bytes"
            if (!doc.partEditable(e.ext) && !n.stored.contentEquals(e.stored)) return "the .${e.ext} part, which nothing changed, was not written back as it was"
        }
        return null
    }

    /** Builds the temp file beside [target], verifies what is on disk, then moves it over. Null when done. */
    private fun write(doc: CommandSave, bytes: ByteArray, target: File): String? {
        verify(doc, bytes, target)?.let { return "It was not saved: $it." }
        val tmp = CommandFiles.tempFor(target)
        return try {
            target.absoluteFile.parentFile?.mkdirs()
            tmp.writeBytes(bytes)
            val disk = tmp.readBytes()
            if (!disk.contentEquals(bytes)) return "It was not saved: the temp file did not read back as written."
            verify(doc, disk, target)?.let { return "It was not saved: $it." }
            CommandFiles.moveOver(tmp, target)?.let { "It was not saved: $it." }
        } catch (t: Throwable) {
            "It was not saved: ${CommandFiles.reason(t)}."
        } finally {
            runCatching { if (tmp.exists()) tmp.delete() }
        }
    }

    private fun companionPairs(doc: CommandSave, target: File): List<Pair<File, File>> {
        val dir = doc.file.absoluteFile.parentFile ?: return emptyList()
        val toDir = target.absoluteFile.parentFile ?: return emptyList()
        val oldBase = doc.file.nameWithoutExtension
        val saveFile = doc.cmp?.saveFile?.text?.trim().orEmpty()
        val newBase = target.nameWithoutExtension
        val exts = COMPANIONS + (if (doc.identity.start) listOf(".tri") else emptyList())
        return exts.mapNotNull { ext ->
            val from = listOf(File(dir, oldBase + ext), File(dir, saveFile + ext)).firstOrNull { it.isFile } ?: return@mapNotNull null
            from to File(toDir, newBase + ext)
        }
    }

    private fun fail(f: File, why: String) = Result(false, f, why)

    private inline fun guarded(f: File, body: () -> Result): Result = try { body() } catch (t: Throwable) {
        Result(false, f, "${f.name} was not saved: ${CommandFiles.reason(t)}.")
    }
}
