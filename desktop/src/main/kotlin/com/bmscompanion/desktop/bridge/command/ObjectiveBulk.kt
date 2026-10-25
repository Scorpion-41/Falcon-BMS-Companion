package com.bmscompanion.desktop.bridge.command

import kotlin.math.hypot

/**
 * The objectives' bulk operations a theater builder runs on a campaign start (the Objectives page's Bulk… window),
 * each one undoable edit. Supply and fuel by class are what BMS records, so they work in a save too (its deltas);
 * the rest change the records and need a start being edited ([ObjectiveOps.Mode.FULL]).
 */
object ObjectiveBulk {
    private const val CITY = 8
    private const val TOWN = 28
    private const val VILLAGE = 29
    private const val MAIN_PRIORITY = 80

    private fun type(doc: CommandSave, o: CmdObjective) = ObjectiveOps.typeOf(doc, o)

    /** Supply and fuel of every objective of class type [objType] (null: every type) and owner [owner] (null: every team). */
    fun supplyAndFuel(doc: CommandSave, objType: Int?, owner: Int?, supply: Int?, fuel: Int?, full: Boolean): CommandEdit {
        val objs = doc.objectives.filter { (objType == null || type(doc, it) == objType) && (owner == null || doc.ownerNow(it) == owner) }
        val edits = objs.flatMap { o -> listOfNotNull(supply?.let { ObjectiveOps.supply(doc, o, it, full) }, fuel?.let { ObjectiveOps.fuel(doc, o, it, full) }) }
        return if (edits.isEmpty()) Edits.failing("Supply and fuel", "obj", "no objective of that kind") else Edits.group("Supply and fuel of ${objs.size} objectives", edits)
    }

    /** Every objective's first owner becomes who holds it now. */
    fun firstOwnerToCurrent(doc: CommandSave): CommandEdit {
        val edits = doc.objectives.filter { it.firstOwner != it.owner }.map { o -> ObjectiveOps.field("First owner", { o.firstOwner }, { o.firstOwner = it }, o.owner) }
        return if (edits.isEmpty()) Edits.failing("First owners", "obj", "every first owner is the owner already") else Edits.group("First owner = owner (${edits.size})", edits)
    }

    /** As a fresh start: no spotting, losses or repair time, base flags clear, every building intact (its class's count). */
    fun resetObjectives(doc: CommandSave): CommandEdit {
        val edits = ArrayList<CommandEdit>()
        for (o in doc.objectives) {
            val n = ObjectiveOps.featureCount(doc, o)
            if (o.spotted != 0) edits += ObjectiveOps.field("Reset", { o.spotted }, { o.spotted = it }, 0)
            if (o.spotTime != 0L) edits += ObjectiveOps.field("Reset", { o.spotTime }, { o.spotTime = it }, 0L)
            if (o.losses != 0) edits += ObjectiveOps.field("Reset", { o.losses }, { o.losses = it }, 0)
            if (o.lastRepair != 0L) edits += ObjectiveOps.field("Reset", { o.lastRepair }, { o.lastRepair = it }, 0L)
            if (o.baseFlags != 0) edits += ObjectiveOps.field("Reset", { o.baseFlags }, { o.baseFlags = it }, 0)
            if (o.fstatus.size != n || o.fstatus.any { it.toInt() != 0 }) edits += ObjectiveOps.field("Reset", { o.fstatus }, { o.fstatus = it }, ByteArray(n.coerceAtLeast(0)))
        }
        return if (edits.isEmpty()) Edits.failing("Reset objectives", "obj", "every objective is as a fresh start already") else Edits.group("Reset every objective", edits)
    }

    /**
     * Parents again: an objective of priority 80 or more has none; a city or town takes the nearest such main city or
     * town of the same first owner, a village the nearest city or town, anything else the nearest city, town or village.
     */
    fun resetParents(doc: CommandSave): CommandEdit {
        val objs = doc.objectives
        fun d(a: CmdObjective, b: CmdObjective) = hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble())
        val main = objs.filter { type(doc, it) in setOf(CITY, TOWN) && it.priority >= MAIN_PRIORITY }
        val towns = objs.filter { type(doc, it) in setOf(CITY, TOWN) }
        val places = objs.filter { type(doc, it) in setOf(CITY, TOWN, VILLAGE) }
        val edits = ArrayList<CommandEdit>()
        for (o in objs) {
            val p: VuId = if (o.priority >= MAIN_PRIORITY) VuId.NONE else {
                val pool = when (type(doc, o)) {
                    CITY, TOWN -> main.filter { it.firstOwner == o.firstOwner }
                    VILLAGE -> towns
                    else -> places
                }.filter { it !== o }
                pool.minByOrNull { d(o, it) }?.id ?: VuId.NONE
            }
            if (p != o.parent) edits += ObjectiveOps.field("Parent", { o.parent }, { o.parent = it }, p)
        }
        return if (edits.isEmpty()) Edits.failing("Parents", "obj", "every parent is as the rule gives it already") else Edits.group("Parents again (${edits.size})", edits)
    }

    /**
     * Front lines from the links and the teams' stances: an objective linked to one held by a team its owner is
     * hostile to or at war with is on the front line (flag 0x1), one linked to a front-line objective on the second
     * (0x2), one linked to a second-line objective on the third (0x4). Objectives marked Manual (0x100) are kept.
     */
    fun frontLines(doc: CommandSave): CommandEdit {
        val objs = doc.objectives
        val byId = objs.associateBy { it.id }
        fun war(a: Int, b: Int): Boolean {
            if (a == b) return false
            val t = doc.teams.getOrNull(a)?.team ?: return false
            val s = t.stance.getOrNull(b) ?: 0
            val c = doc.teams.getOrNull(t.cteam)?.team?.stance?.getOrNull(b) ?: 0
            return s >= 4 || (s == 0 && c >= 4)
        }
        fun neighbours(o: CmdObjective) = o.links.mapNotNull { byId[it.id] }
        val front = objs.filter { o -> neighbours(o).any { war(o.owner, it.owner) } }.toSet()
        val second = objs.filter { o -> o !in front && neighbours(o).any { it in front } }.toSet()
        val third = objs.filter { o -> o !in front && o !in second && neighbours(o).any { it in second } }.toSet()
        val edits = ArrayList<CommandEdit>()
        for (o in objs) {
            if (o.objFlags and 0x100 != 0) continue
            val bits = when (o) { in front -> 1; in second -> 2; in third -> 4; else -> 0 }
            val v = (o.objFlags and 7.inv()) or bits
            if (v != o.objFlags) edits += ObjectiveOps.field("Front line", { o.objFlags }, { o.objFlags = it }, v)
        }
        return if (edits.isEmpty()) Edits.failing("Front lines", "obj", "every front-line mark is as the links give it already")
        else Edits.group("Front lines: ${front.size} front, ${second.size} second, ${third.size} third", edits)
    }

    /** Every radar objective, airbase and airstrip emitting (base flag 0x1). */
    fun radarsEmitting(doc: CommandSave): CommandEdit {
        val ocds = doc.tables.ocd
        val edits = doc.objectives.filter { o ->
            (ocds[ObjectiveOps.ocdOf(doc, o)]?.radarFeature ?: 255) < 255 || type(doc, o) in setOf(1, 2)
        }.filter { it.baseFlags and 1 == 0 }.map { o -> ObjectiveOps.field("Emitting", { o.baseFlags }, { o.baseFlags = it }, o.baseFlags or 1) }
        return if (edits.isEmpty()) Edits.failing("Emitting", "obj", "every radar is emitting already") else Edits.group("${edits.size} radars emitting", edits)
    }
}
