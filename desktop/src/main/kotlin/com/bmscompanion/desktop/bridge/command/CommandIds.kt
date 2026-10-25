package com.bmscompanion.desktop.bridge.command

/**
 * New identities for records the Command section adds or clones. Units and objectives share one id space and one
 * CampId space; each answer is checked against everything the save holds now (its own records and, for a played save,
 * its start's objectives), so a new id never collides.
 *
 * Pools for the id number (the creator is 0, as BMS's own starts have it): flights count up from the highest flight,
 * packages from the highest package, squadrons, battalions, brigades and task forces share a pool, objectives count
 * up from the highest objective or unit. An empty pool starts at [FIRST_UNIT] (units) or [FIRST_OBJECTIVE].
 */
object CommandIds {
    const val FIRST_OBJECTIVE = 4L
    /** the first id of a non-volatile unit: 4 + 8,000 objectives + 1 */
    const val FIRST_UNIT = 8005L

    private fun usedNums(doc: CommandSave): Set<Long> =
        (doc.units.map { it.id.num } + doc.objectives.map { it.id.num }).toHashSet()

    /** A new id for a unit of [kind]. */
    fun unitId(doc: CommandSave, kind: UnitKind): VuId {
        val pool = when (kind) {
            UnitKind.FLIGHT -> doc.flights
            UnitKind.PACKAGE -> doc.packages
            else -> doc.units.filter { it.kind != UnitKind.FLIGHT && it.kind != UnitKind.PACKAGE }
        }
        return free(doc, (pool.maxOfOrNull { it.id.num } ?: (FIRST_UNIT - 1)) + 1)
    }

    /** A new id for an objective (in a start). */
    fun objectiveId(doc: CommandSave): VuId {
        val top = (doc.objectives.map { it.id.num } + doc.units.map { it.id.num }).maxOrNull() ?: (FIRST_OBJECTIVE - 1)
        return free(doc, maxOf(top + 1, FIRST_OBJECTIVE))
    }

    private fun free(doc: CommandSave, from: Long): VuId {
        val used = usedNums(doc)
        var n = from
        while (n in used) n++
        return VuId(n, 0)
    }

    /** The CampIds in use (objectives and units together). */
    fun campIdsInUse(doc: CommandSave): Set<Int> = (doc.objectives.map { it.campId } + doc.units.map { it.campId }).toHashSet()

    /** The first CampId not in use, counting up from the lowest objective's (1 without objectives). */
    fun campId(doc: CommandSave): Int {
        val used = campIdsInUse(doc)
        var c = maxOf(1, doc.objectives.minOfOrNull { it.campId } ?: 1)
        while (c in used) c++
        return c
    }

    /**
     * A name number for a new unit of [kind] owned by [owner]: one more than the highest that team's units of the
     * kind carry (of [type] too, for battalions). A package's name number is its CampId.
     */
    fun nameId(doc: CommandSave, kind: UnitKind, owner: Int, type: Int? = null): Int =
        (doc.units.filter { it.kind == kind && it.owner == owner && (type == null || kind != UnitKind.BATTALION || it.type == type) }
            .maxOfOrNull { it.nameId } ?: 0) + 1

    /** The lowest callsign number (1-9) no flight with callsign word [callsignId] uses, or null when all are taken. */
    fun callsignNumber(doc: CommandSave, callsignId: Int, except: CmdFlight? = null): Int? {
        val used = doc.flights.filter { it !== except && it.callsignId == callsignId }.map { it.callsignNum }.toSet()
        return (1..9).firstOrNull { it !in used }
    }

    /** Another flight already flies as callsign [callsignId] [num]. */
    fun callsignTaken(doc: CommandSave, callsignId: Int, num: Int, except: CmdFlight? = null): CmdFlight? =
        doc.flights.firstOrNull { it !== except && it.callsignId == callsignId && it.callsignNum == num }

    /**
     * The callsign usage bits of `.plt` as the flights say they should be (bit n-1 for number n, numbers 1-8), for a
     * Checks fix; [PltPart.callsigns] keeps what the file has.
     */
    fun callsignUsage(doc: CommandSave): IntArray {
        val n = doc.plt?.callsigns?.size ?: 0
        val out = IntArray(n)
        for (f in doc.flights) if (f.callsignId in 0 until n && f.callsignNum in 1..8) out[f.callsignId] = out[f.callsignId] or (1 shl (f.callsignNum - 1))
        return out
    }
}
