package com.bmscompanion.desktop.bridge.command

import com.bmscompanion.desktop.bridge.DevGuard
import java.io.File

/**
 * Saves the pilot said are theirs although their dates put them with Falcon BMS's own files ("This file is mine" in
 * the Command section's identity strip; only for a file recognised by its dates, never a start by name or by its
 * objectives part, nor a TE_BMS_/TR_BMS_/DF_BMS_ mission). Kept in `<BMS>\User\BMS Companion Command\mine.json` beside
 * the record of the section's own copies: the path (relative to the BMS folder), and the size and time the file had
 * when it was claimed (for the record only — BMS saving over it later does not undo the claim). [ShippedSaves] treats
 * a listed file as the pilot's; [remove] takes the claim back, but the entry stays (`"claimed": false`): a file once
 * claimed was the pilot's at some point, so its bytes may no longer be BMS's original, and [CommandBackup] never copies
 * it as one ([everClaimed]; its dates still keep it from being overwritten).
 */
object MineFiles {
    private const val FILE = "User/BMS Companion Command/mine.json"

    private class Entry(val path: String, val size: Long, val modified: Long, val claimed: Boolean = true)

    private fun rootOf(f: File): File? {
        var d: File? = f.absoluteFile.parentFile
        while (d != null) {
            if (File(d, "Data").isDirectory && File(d, "User").isDirectory) return d
            d = d.parentFile
        }
        return null
    }

    private fun rel(root: File, f: File): String = f.absoluteFile.relativeToOrSelf(root.absoluteFile).path.replace('\\', '/').lowercase()

    private fun read(record: File): MutableList<Entry> = runCatching {
        val text = record.readText(Charsets.UTF_8)
        Regex("\\{\\s*\"path\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"\\s*,\\s*\"size\"\\s*:\\s*(-?\\d+)\\s*,\\s*\"modified\"\\s*:\\s*(-?\\d+)\\s*(?:,\\s*\"claimed\"\\s*:\\s*(true|false)\\s*)?}")
            .findAll(text).map {
                Entry(it.groupValues[1].replace("\\\\", "\\").replace("\\\"", "\""), it.groupValues[2].toLong(), it.groupValues[3].toLong(), it.groupValues[4] != "false")
            }
            .toMutableList()
    }.getOrDefault(ArrayList())

    fun contains(f: File): Boolean {
        val root = rootOf(f) ?: return false
        val record = File(root, FILE).takeIf { it.isFile } ?: return false
        val p = rel(root, f)
        return read(record).any { it.path == p && it.claimed }
    }

    /** [f] is the pilot's now or was once (the claim taken back since): never backed up as BMS's original. */
    fun everClaimed(f: File): Boolean {
        val root = rootOf(f) ?: return false
        val record = File(root, FILE).takeIf { it.isFile } ?: return false
        val p = rel(root, f)
        return read(record).any { it.path == p }
    }

    /** Records [f] as the pilot's. Null when done, else why not. */
    fun add(f: File): String? = change(f) { list, p ->
        if (list.none { it.path == p && it.claimed }) {
            list.removeAll { it.path == p }
            list += Entry(p, runCatching { f.length() }.getOrDefault(0), runCatching { f.lastModified() }.getOrDefault(0))
        }
    }

    /** Takes the claim on [f] back (its dates decide again; it stays [everClaimed]). Null when done, else why not. */
    fun remove(f: File): String? = change(f) { list, p ->
        val i = list.indexOfFirst { it.path == p }
        if (i >= 0) list[i] = Entry(list[i].path, list[i].size, list[i].modified, claimed = false)
    }

    private fun change(f: File, edit: (MutableList<Entry>, String) -> Unit): String? {
        val root = rootOf(f) ?: return "${f.name} is not inside a Falcon BMS folder."
        DevGuard.refusal(root.path)?.let { return it }
        val record = File(root, FILE)
        val list = read(record)
        edit(list, rel(root, f))
        val json = buildString {
            append("{\n  \"note\": \"Saves you told BMS Companion's Command section are yours, although their dates put them with Falcon BMS's own files. The section may overwrite them (after asking).\",\n  \"mine\": [\n")
            list.forEachIndexed { i, e ->
                append("    { \"path\": \"").append(e.path.replace("\\", "\\\\").replace("\"", "\\\"")).append("\", \"size\": ").append(e.size).append(", \"modified\": ").append(e.modified).append(", \"claimed\": ").append(e.claimed).append(" }")
                append(if (i < list.size - 1) ",\n" else "\n")
            }
            append("  ]\n}\n")
        }
        record.absoluteFile.parentFile?.mkdirs()
        return CommandFiles.writeAtomic(record, json.toByteArray(Charsets.UTF_8))
    }
}
