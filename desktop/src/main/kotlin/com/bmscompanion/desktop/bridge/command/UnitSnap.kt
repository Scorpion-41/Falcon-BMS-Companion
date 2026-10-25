package com.bmscompanion.desktop.bridge.command

import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

/**
 * A snapshot of record objects that puts them back **in place**: every field of each object (its class and its
 * superclasses) is remembered — arrays copied, lists as their elements, nested records of this package (a waypoint, a
 * loadout, a squadron pilot, a header row, a fixed text…) snapshotted in turn — and [restore] writes the same values
 * back into the same objects, so the lists keep their identity and earlier edits in the undo log, which hold
 * references to those objects, still find them. Units are never snapshotted through a list (the unit list is held as
 * its elements only): an edit names every unit it changes.
 */
internal class Snap private constructor(private val target: Any, private val values: Array<Any?>, private val fields: List<Field>) {
    fun restore() {
        for ((i, f) in fields.withIndex()) {
            val v = values[i]
            val fixed = Modifier.isFinal(f.modifiers)
            val now = f.get(target)
            when (v) {
                is ListState -> { if (now !== v.list && !fixed) f.set(target, v.list); v.restore() }
                is Snap -> { if (now !== v.target && !fixed) f.set(target, v.target); v.restore() }
                is IntArray -> if (fixed && now is IntArray && now.size == v.size) v.copyInto(now) else if (!fixed) f.set(target, v.copyOf())
                is ByteArray -> if (fixed && now is ByteArray && now.size == v.size) v.copyInto(now) else if (!fixed) f.set(target, v.copyOf())
                is LongArray -> if (fixed && now is LongArray && now.size == v.size) v.copyInto(now) else if (!fixed) f.set(target, v.copyOf())
                else -> if (!fixed && now != v) f.set(target, v)
            }
        }
    }

    /** A list's elements (and the snapshots of those that are records), put back into the same list. */
    class ListState(val list: MutableList<Any?>, deep: Boolean) {
        private val items = ArrayList(list)
        private val snaps = if (deep) items.map { it?.let { e -> if (isRecord(e) && e !is CmdUnit) of(e) else null } } else emptyList()
        fun restore() {
            list.clear(); list.addAll(items)
            snaps.forEach { it?.restore() }
        }
    }

    companion object {
        private val fieldCache = ConcurrentHashMap<Class<*>, List<Field>>()

        private fun fieldsOf(c: Class<*>): List<Field> = fieldCache.getOrPut(c) {
            val out = ArrayList<Field>()
            var k: Class<*>? = c
            while (k != null && k != Any::class.java) {
                k.declaredFields.filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic }.forEach { it.isAccessible = true; out += it }
                k = k.superclass
            }
            out
        }

        /** A record of the save's model (not an id, a string or a number). */
        fun isRecord(v: Any): Boolean = v.javaClass.name.startsWith("com.bmscompanion.desktop.bridge.command.") && v !is VuId && !v.javaClass.isEnum

        fun of(target: Any): Snap {
            val fs = fieldsOf(target.javaClass)
            val vals = arrayOfNulls<Any?>(fs.size)
            for ((i, f) in fs.withIndex()) {
                val v = f.get(target)
                vals[i] = when {
                    v == null -> null
                    v is IntArray -> v.copyOf()
                    v is ByteArray -> v.copyOf()
                    v is LongArray -> v.copyOf()
                    v is MutableList<*> -> @Suppress("UNCHECKED_CAST") ListState(v as MutableList<Any?>, deep = true)
                    v is CmdUnit -> v
                    isRecord(v) -> of(v)
                    else -> v
                }
            }
            return Snap(target, vals, fs)
        }

        /** The list itself (its elements, not their fields): the unit list, a package's element ids. */
        fun ofList(list: MutableList<*>): ListState = @Suppress("UNCHECKED_CAST") ListState(list as MutableList<Any?>, deep = false)
    }
}

/**
 * The unit editor's edits: [mutate] runs a change on the save's records and undoes it by putting back, in place,
 * everything it names in [UnitEdit.Scope] beforehand — the units it changes, the unit list when it adds or removes
 * one, the header's squadron rows, the pilots' part. Redo runs the change again on the state it first ran on, so it
 * gives the same result. A change that finds it cannot be done throws [Refused] with the sentence to show.
 */
object UnitEdit {
    class Refused(why: String) : RuntimeException(why)

    fun refuse(why: String): Nothing = throw Refused(why)

    /** What one change may touch; [body] adds to it before it changes anything ([touch], [touchList]). */
    class Scope internal constructor(val doc: CommandSave) {
        internal val snaps = ArrayList<Snap>()
        internal val lists = ArrayList<Snap.ListState>()
        private val seen = java.util.IdentityHashMap<Any, Boolean>()

        /** Remembers [obj] (a unit, a header, a part, a row) as it is now, to be put back on undo. */
        fun touch(obj: Any?) { if (obj != null && seen.put(obj, true) == null) snaps += Snap.of(obj) }
        fun touchAll(objs: Iterable<Any?>) = objs.forEach { touch(it) }

        /** Remembers a list's elements (the unit list, the header's squadron rows). */
        fun touchList(list: MutableList<*>?) { if (list != null && seen.put(list, true) == null) lists += Snap.ofList(list) }

        /** The unit list, remembered (for an add or a delete). */
        val units: MutableList<CmdUnit> get() = doc.uni?.units?.also { touchList(it) } ?: refuse("${doc.file.name} has no unit part")

        /** The header, remembered (its squadron rows too). */
        val cmp: CmpHeader? get() = doc.cmp?.also { touch(it) }
        val plt: PltPart? get() = doc.plt?.also { touch(it) }
        val tea: TeaPart? get() = doc.tea?.also { tm -> tm.teams.forEach { touch(it.team) } }

        internal fun restore() {
            lists.asReversed().forEach { it.restore() }
            snaps.asReversed().forEach { it.restore() }
        }
    }

    /**
     * A change named [label] on the parts [parts] ("uni", "cmp", "plt"… — those the save does not have are left out,
     * so a save without a pilots' part can still be edited), done by [body] on a [Scope].
     */
    fun mutate(label: String, doc: CommandSave, parts: Set<String> = setOf("uni"), body: Scope.() -> Unit): CommandEdit {
        val have = parts.filter { p -> when (p) { "cmp" -> doc.cmp != null; "plt" -> doc.plt != null; "tea" -> doc.tea != null; "obd" -> doc.obd != null; else -> true } }.toSet()
        return object : CommandEdit {
            override val label = label
            override val parts = have
            private var scope: Scope? = null
            override fun apply(doc: CommandSave) {
                val s = Scope(doc)
                scope = s
                body(s)
            }
            override fun undo(doc: CommandSave) { scope?.restore(); scope = null }
        }
    }
}
