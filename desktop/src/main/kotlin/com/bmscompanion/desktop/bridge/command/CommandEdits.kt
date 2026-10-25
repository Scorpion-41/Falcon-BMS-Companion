package com.bmscompanion.desktop.bridge.command

/**
 * One undoable change to an open save. [apply] and [undo] work on the same record objects (a removed record is kept
 * by the edit and put back by its undo), so an edit may hold references to the records it touches. [parts] names the
 * part kinds it changes ("uni", "cmp"…): an edit is refused while one of them did not walk exactly.
 */
interface CommandEdit {
    val label: String
    val parts: Set<String>
    fun apply(doc: CommandSave)
    fun undo(doc: CommandSave)
}

/**
 * The edit log of one open save: run, undo, redo and the count the identity strip shows. Never throws: [run] answers
 * a sentence when it refuses or the edit fails (and the edit is then rolled back).
 */
class CommandEdits(private val doc: CommandSave) {
    private val done = ArrayList<CommandEdit>()
    private val undone = ArrayList<CommandEdit>()
    private var savedAt = 0
    private var savedTop: CommandEdit? = null

    /** Edits in force (undone ones not counted). */
    val count: Int get() = done.size
    val canUndo: Boolean get() = done.isNotEmpty()
    val canRedo: Boolean get() = undone.isNotEmpty()
    val undoLabel: String? get() = done.lastOrNull()?.label
    val redoLabel: String? get() = undone.lastOrNull()?.label
    /** The labels of the edits in force, oldest first. */
    val log: List<String> get() = done.map { it.label }
    /** Something changed since the file was opened or last written in place. */
    val dirty: Boolean get() = done.size != savedAt || done.lastOrNull() !== savedTop

    /** Applies [edit]; null when done, else why not. */
    fun run(edit: CommandEdit): String? {
        refusal(edit)?.let { return it }
        return try {
            edit.apply(doc)
            done += edit
            undone.clear()
            doc.changed()
            null
        } catch (e: Throwable) {
            runCatching { edit.undo(doc) }
            doc.changed()
            "${edit.label} could not be done: ${e.message ?: e.javaClass.simpleName}."
        }
    }

    fun undo(): Boolean {
        val e = done.removeLastOrNull() ?: return false
        runCatching { e.undo(doc) }
        undone += e
        doc.changed()
        return true
    }

    fun redo(): Boolean {
        val e = undone.removeLastOrNull() ?: return false
        return try { e.apply(doc); done += e; doc.changed(); true } catch (_: Throwable) { runCatching { e.undo(doc) }; doc.changed(); false }
    }

    /** The file now holds what is in memory (after Overwrite): [dirty] is false until the next edit. */
    fun markSaved() { savedAt = done.size; savedTop = done.lastOrNull() }

    /** Why [edit] may not run on this save, or null. */
    fun refusal(edit: CommandEdit): String? {
        if (doc.version < CommandSave.OLDEST) return "${doc.file.name} is in an older format than Falcon BMS 4.38's: it cannot be edited."
        for (p in edit.parts) {
            if (!doc.partEditable(p)) return "${edit.label}: the .$p part of ${doc.file.name} could not be read exactly, so it is kept as it is and cannot be changed."
            if (p == "obj" && !doc.objectivesEditable) return "Objectives are edited as records only in a campaign start; in a save, change what BMS records (owner, supply, buildings)."
        }
        return null
    }
}

/** Builders for the common kinds of edit. The editors add their own on top ([group] ties several into one). */
object Edits {
    /** Sets a value through [set], remembering what [get] gave for the undo. */
    fun <T> set(label: String, part: String, get: () -> T, set: (T) -> Unit, value: T): CommandEdit = object : CommandEdit {
        override val label = label
        override val parts = setOf(part)
        private var old: T? = null
        private var had = false
        override fun apply(doc: CommandSave) { old = get(); had = true; set(value) }
        override fun undo(doc: CommandSave) { @Suppress("UNCHECKED_CAST") if (had) set(old as T) }
    }

    /** Inserts [item] into [list] at [index] (the end when out of range). */
    fun <T> insert(label: String, part: String, list: MutableList<T>, item: T, index: Int = list.size): CommandEdit = object : CommandEdit {
        override val label = label
        override val parts = setOf(part)
        private var at = -1
        override fun apply(doc: CommandSave) { at = index.coerceIn(0, list.size); list.add(at, item) }
        override fun undo(doc: CommandSave) { if (at >= 0 && at < list.size && list[at] === item) list.removeAt(at) else list.remove(item) }
    }

    /** Removes [item] (by identity) from [list]; undo puts it back where it was. */
    fun <T> remove(label: String, part: String, list: MutableList<T>, item: T): CommandEdit = object : CommandEdit {
        override val label = label
        override val parts = setOf(part)
        private var at = -1
        override fun apply(doc: CommandSave) {
            at = list.indexOfFirst { it === item }
            if (at < 0) throw IllegalStateException("it is no longer there")
            list.removeAt(at)
        }
        override fun undo(doc: CommandSave) { if (at >= 0) list.add(at.coerceAtMost(list.size), item) }
    }

    /** Moves the item at [from] to [to] within [list]. */
    fun <T> move(label: String, part: String, list: MutableList<T>, from: Int, to: Int): CommandEdit = object : CommandEdit {
        override val label = label
        override val parts = setOf(part)
        override fun apply(doc: CommandSave) { val x = list.removeAt(from); list.add(to.coerceIn(0, list.size), x) }
        override fun undo(doc: CommandSave) { val x = list.removeAt(to.coerceIn(0, list.size - 1)); list.add(from.coerceIn(0, list.size), x) }
    }

    /** Several edits as one: applied in order, undone in reverse; a failure undoes those already applied. */
    fun group(label: String, edits: List<CommandEdit>): CommandEdit = object : CommandEdit {
        override val label = label
        override val parts = edits.flatMap { it.parts }.toSet()
        override fun apply(doc: CommandSave) {
            val applied = ArrayList<CommandEdit>()
            try {
                for (e in edits) { e.apply(doc); applied += e }
            } catch (t: Throwable) {
                applied.asReversed().forEach { runCatching { it.undo(doc) } }
                throw t
            }
        }
        override fun undo(doc: CommandSave) { edits.asReversed().forEach { it.undo(doc) } }
    }

    /**
     * Several independent edits as one undoable edit, **each on its own** (Checks' Fix all): one that is refused (a
     * part that cannot be changed, [CommandEdits.refusal]) or fails is rolled back alone and named in [Each.refused],
     * and the rest are made. Undo takes back the ones made.
     */
    class Each(override val label: String, private val log: CommandEdits, private val edits: List<CommandEdit>) : CommandEdit {
        override val parts: Set<String> = emptySet()
        private val applied = ArrayList<CommandEdit>()
        /** why each edit not made was not made, as of the last apply */
        val refused = ArrayList<String>()
        val made: Int get() = applied.size
        override fun apply(doc: CommandSave) {
            applied.clear(); refused.clear()
            for (e in edits) {
                val no = log.refusal(e)
                if (no != null) { refused += no; continue }
                try { e.apply(doc); applied += e } catch (t: Throwable) {
                    runCatching { e.undo(doc) }
                    refused += "${e.label}: ${t.message ?: t.javaClass.simpleName}"
                }
            }
            if (applied.isEmpty()) throw IllegalStateException(refused.firstOrNull() ?: "nothing to do")
        }
        override fun undo(doc: CommandSave) { applied.asReversed().forEach { runCatching { it.undo(doc) } } }
    }

    /**
     * The owner of objective [o] becomes [team]: in a start, the record itself; in a save, its delta (one is added,
     * from the start's values, when the objective has none yet) — what BMS records.
     */
    fun objectiveOwner(doc: CommandSave, o: CmdObjective, team: Int): CommandEdit {
        val label = "Owner of ${o.name.text.ifBlank { "objective ${o.campId}" }}"
        if (doc.objectivesEditable && doc.obj?.objectives?.any { it === o } == true) return set(label, "obj", { o.owner }, { o.owner = it }, team)
        return withDelta(doc, o, label) { d -> set(label, "obd", { d.owner }, { d.owner = it }, team) }
    }

    /** A change to objective [o]'s delta in a save: [change] gets the delta (new or existing). */
    fun withDelta(doc: CommandSave, o: CmdObjective, label: String, change: (CmdDelta) -> CommandEdit): CommandEdit {
        val obd = doc.obd ?: return failing(label, "obd", "${doc.file.name} has no objective delta part")
        val existing = obd.delta(o.id)
        if (existing != null) return change(existing)
        val d = CmdDelta.of(o)
        return group(label, listOf(insert(label, "obd", obd.deltas, d), change(d)))
    }

    /** An edit that always fails with [why] (for a request that cannot be made). */
    fun failing(label: String, part: String, why: String): CommandEdit = object : CommandEdit {
        override val label = label
        override val parts = setOf(part)
        override fun apply(doc: CommandSave) { throw IllegalStateException(why) }
        override fun undo(doc: CommandSave) {}
    }
}
