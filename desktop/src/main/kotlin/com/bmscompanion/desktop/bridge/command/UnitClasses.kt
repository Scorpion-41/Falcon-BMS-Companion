package com.bmscompanion.desktop.bridge.command

import java.util.concurrent.ConcurrentHashMap

/**
 * What the unit editor reads of a theater's class tables: a unit type is its class-table number + 100; the class
 * entry (CT) says what it is (domain 2 air, 3 land, 4 sea; class 6 unit, 7 vehicle or weapon; type) and points at its
 * unit record (UCD), whose 16 slots name a vehicle (CT → VCD) and how many of it a full unit has. An aircraft's
 * hardpoints are its VCD's 16 weapon slots: a fixed store (the gun) or, when the count is 255, a weapon list (WLD) of
 * what may hang there and how many. A squadron is stocked from the stores template (SSD) its UCD names.
 *
 * Read only, through [ClassTablesMore] (cached by file). Nothing throws: a missing table gives empty answers.
 */
class UnitClasses private constructor(private val tables: ClassTablesMore.Tables, private val names: com.bmscompanion.desktop.bridge.CampaignArchive.Names?) {
    private val t get() = tables
    val ct: Map<Int, ClassTablesMore.Rec> by lazy { t.table("CT") }
    val ucdTable: Map<Int, ClassTablesMore.Rec> by lazy { t.table("UCD") }
    val vcdTable: Map<Int, ClassTablesMore.Rec> by lazy { t.table("VCD") }
    val wcdTable: Map<Int, ClassTablesMore.Rec> by lazy { t.table("WCD") }
    val fcdTable: Map<Int, ClassTablesMore.Rec> by lazy { t.table("FCD") }

    /** One class entry, as numbers. */
    class Cls(val num: Int, val domain: Int, val cls: Int, val type: Int, val sub: Int, val specific: Int, val idx: Int)

    fun cls(ctNum: Int): Cls? = ct[ctNum]?.let { Cls(ctNum, it.int("Domain"), it.int("Class"), it.int("Type"), it.int("SubType"), it.int("Specific"), it.int("EntityIdx", -1)) }
    fun clsOfType(unitType: Int): Cls? = cls(unitType - 100)

    fun ucd(u: CmdUnit): ClassTablesMore.Rec? = ucdOfType(u.type)
    fun ucdOfType(unitType: Int): ClassTablesMore.Rec? = clsOfType(unitType)?.idx?.let { ucdTable[it] }

    /** The vehicle class entry of slot [k] of a unit type. */
    fun slotVehicleCt(unitType: Int, k: Int): Int? = ucdOfType(unitType)?.int("VehicleCtIdx_$k", -1)?.takeIf { it >= 0 }
    fun vcdOfCt(ctNum: Int?): ClassTablesMore.Rec? = ctNum?.let { cls(it) }?.idx?.let { vcdTable[it] }

    /** A unit type's lead vehicle (slot 0): the aircraft of a flight or squadron. */
    fun leadVcd(unitType: Int): ClassTablesMore.Rec? = vcdOfCt(slotVehicleCt(unitType, 0))

    /** How many vehicles slot [k] of a full unit has (0-3). */
    fun slotMax(unitType: Int, k: Int): Int = (ucdOfType(unitType)?.int("ElementCount_$k") ?: 0).let { if (it > 3) it / 256 else it }.coerceIn(0, 3)

    /** The roster of a full unit: each slot at its count, two bits a slot. */
    fun maxRoster(unitType: Int): Int = (0 until 16).fold(0) { r, k -> r or (slotMax(unitType, k) shl (2 * k)) }

    /** Slot [k]'s vehicle name. */
    fun slotName(unitType: Int, k: Int): String? = vcdOfCt(slotVehicleCt(unitType, k))?.name?.takeIf { it.isNotBlank() }

    /** The unit types of a kind (domain, type), with their unit name and lead vehicle; placeholders left out. */
    fun typesOf(kind: UnitKind): List<TypeChoice> = typesCache.getOrPut(key(kind)) {
        val (dom, typ) = when (kind) {
            UnitKind.FLIGHT -> 2 to 1; UnitKind.PACKAGE -> 2 to 2; UnitKind.SQUADRON -> 2 to 3
            UnitKind.BATTALION -> 3 to 1; UnitKind.BRIGADE -> 3 to 2; UnitKind.TASKFORCE -> 4 to 1
        }
        ct.keys.sorted().mapNotNull { n ->
            val c = cls(n) ?: return@mapNotNull null
            if (c.domain != dom || c.cls != 6 || c.type != typ) return@mapNotNull null
            val u = ucdTable[c.idx] ?: return@mapNotNull null
            val name = u.name
            val veh = leadVcd(n + 100)?.name.orEmpty()
            if (placeholder(name) || (kind != UnitKind.PACKAGE && kind != UnitKind.BRIGADE && placeholder(veh))) return@mapNotNull null
            TypeChoice(n + 100, c.sub, name, veh)
        }
    }

    class TypeChoice(val type: Int, val sub: Int, val unitName: String, val vehicle: String) {
        val label: String get() = if (vehicle.isNotBlank() && !unitName.contains(vehicle, ignoreCase = true)) "$vehicle — $unitName" else unitName.ifBlank { vehicle }
    }

    /** The flight type that flies a squadron type's aircraft: the air flight class whose lead vehicle is the same. */
    fun flightTypeFor(squadronType: Int): Int? {
        val v = slotVehicleCt(squadronType, 0) ?: return null
        return typesOf(UnitKind.FLIGHT).firstOrNull { slotVehicleCt(it.type, 0) == v }?.type
            ?: ct.keys.sorted().firstOrNull { n -> cls(n)?.let { it.domain == 2 && it.cls == 6 && it.type == 1 } == true && slotVehicleCt(n + 100, 0) == v }?.plus(100)
    }

    /** The squadron types of the same role (sub type) as [type], or every squadron type. */
    fun squadronTypes(sameRoleAs: Int?): List<TypeChoice> {
        val sub = sameRoleAs?.let { clsOfType(it)?.sub }
        return typesOf(UnitKind.SQUADRON).filter { sub == null || it.sub == sub }
    }

    // ---------------------------------------------------------------- aircraft: hardpoints, fuel, weights

    /** One hardpoint of an aircraft: a fixed store ([fixed] weapon × [fixedCount]) or the stores a list allows. */
    class Hardpoint(val n: Int, val fixed: Int?, val fixedCount: Int, val allowed: List<Pair<Int, Int>>, val listName: String?) {
        val loadable: Boolean get() = fixed == null && allowed.isNotEmpty()
        fun max(weapon: Int): Int = if (fixed != null) (if (weapon == fixed) fixedCount else 0) else allowed.firstOrNull { it.first == weapon }?.second ?: 0
    }

    /** The 16 hardpoints of the aircraft a flight or squadron type flies (empty when its VCD is not found). */
    fun hardpoints(unitType: Int): List<Hardpoint> {
        val v = leadVcd(unitType) ?: return emptyList()
        return (0 until 16).map { k ->
            val idx = v.int("WpnOrHpIdx_$k", -1)
            val cnt = v.int("WpnCount_$k", 0)
            when {
                idx < 0 || (idx == 0 && cnt == 0) -> Hardpoint(k, null, 0, emptyList(), null)
                cnt == 255 -> t.weaponList(idx)?.let { (name, list) -> Hardpoint(k, null, 0, list.filter { it.first > 0 && it.second > 0 }, name) }
                    ?: Hardpoint(k, null, 0, emptyList(), null)
                else -> Hardpoint(k, idx, cnt, emptyList(), null)
            }
        }
    }

    /** A loadout with only the fixed stores (the gun): what a cleared loadout is. */
    fun bareLoadout(unitType: Int): Loadout {
        val l = Loadout()
        hardpoints(unitType).forEach { h -> if (h.fixed != null) { l.weapons[h.n] = h.fixed; l.counts[h.n] = h.fixedCount.coerceIn(0, 255) } }
        return l
    }

    fun weaponName(id: Int): String = names?.weapon(id) ?: wcdTable[id]?.name?.takeIf { it.isNotBlank() } ?: "Weapon $id"
    fun weaponWeight(id: Int): Int = wcdTable[id]?.int("Weight") ?: 0
    fun weaponDrag(id: Int): Int = wcdTable[id]?.int("Drag") ?: 0

    /** A store that is a fuel tank (its class: a weapon of type 4), and the fuel it holds (its Strength, lb). */
    fun tankFuel(id: Int): Int {
        val w = wcdTable[id] ?: return 0
        val c = cls(w.int("CtIdx", -1)) ?: return 0
        return if (c.cls == 7 && c.type == 4) w.int("Strength") else 0
    }

    class Weights(val empty: Int, val max: Int, val internalFuel: Int, val externalFuel: Int, val stores: Int, val drag: Int) {
        val gross: Int get() = empty + internalFuel + externalFuel + stores
        val over: Boolean get() = max in 1 until gross
    }

    fun weights(unitType: Int, l: Loadout?): Weights {
        val v = leadVcd(unitType)
        var load = 0; var ext = 0; var drag = 0
        if (l != null) for (k in 0 until 16) {
            val id = l.weapons[k]; val c = l.counts[k]
            if (id <= 0 || c <= 0) continue
            load += weaponWeight(id) * c
            drag += weaponDrag(id) * c
            ext += tankFuel(id) * c
        }
        return Weights(v?.int("EmptyWeight") ?: 0, v?.int("MaxWeight") ?: 0, v?.int("FuelWeight") ?: 0, ext, load, drag)
    }

    /** The callsigns an aircraft is given (VCD CallsignIdx … + CallsignSlots), as callsign word numbers. */
    fun callsignRange(unitType: Int): IntRange? {
        val v = leadVcd(unitType) ?: return null
        val first = v.int("CallsignIdx", -1); val n = v.int("CallsignSlots", 0)
        return if (first >= 0 && n > 0) first until first + n else null
    }

    // ---------------------------------------------------------------- squadrons: stores

    /** The stores a new squadron of [unitType] gets (weapon id → count), from its UCD's template. */
    fun storesTemplate(unitType: Int): Map<Int, Int>? {
        val idx = ucdOfType(unitType)?.int("SquadronStoresIdx", -1) ?: return null
        if (idx < 0) return null
        return t.squadronStores(idx)
    }

    // ---------------------------------------------------------------- objectives

    /** The objective class (OCD) of an objective. */
    fun ocdOf(o: CmdObjective): ClassTablesMore.Ocd? = cls(o.type - 100)?.idx?.let { t.ocd[it] }

    /** An objective's buildings: index within it, name, value (BMS's importance; 0 = not a target). */
    fun buildings(o: CmdObjective): List<Triple<Int, String, Int>> {
        val ocd = ocdOf(o) ?: return emptyList()
        return t.features(ocd.num).mapIndexed { i, f ->
            val name = cls(f.ctIdx)?.idx?.let { fcdTable[it]?.name }?.takeIf { it.isNotBlank() } ?: "Building $i"
            Triple(i, name, f.value)
        }
    }

    /** How many parking points of [types] the objective's class has (SAM/AAA sites: headers of list [listType]). */
    fun pointCount(o: CmdObjective, listType: Int, types: Set<Int>): Int {
        val ocd = ocdOf(o) ?: return 0
        val heads = t.pointHeaders(ocd.num).filter { it.type == listType }
        if (heads.isEmpty()) return 0
        val pts = t.points(ocd.num)
        return heads.sumOf { h -> (h.firstPt until h.firstPt + h.pointCount).count { i -> pts.getOrNull(i)?.type in types } }
    }

    private fun key(kind: UnitKind) = "${t.file("CT")?.let { com.bmscompanion.desktop.bridge.CampaignArchive.key(it) }}|$kind"

    companion object {
        private val cache = ConcurrentHashMap<String, UnitClasses>()
        private val typesCache = ConcurrentHashMap<String, List<TypeChoice>>()

        fun of(doc: CommandSave): UnitClasses {
            val k = (doc.tables.file("CT")?.path ?: "-") + "|" + doc.tables.file("UCD")?.path
            return cache.getOrPut(k) { UnitClasses(doc.tables, doc.names) }
        }

        fun placeholder(name: String): Boolean {
            val n = name.trim()
            return n.isEmpty() || n.endsWith("*") || n.contains("_empty", ignoreCase = true) || n.contains("free slot", ignoreCase = true)
        }

        /** Vehicles in a roster (two bits a slot). */
        fun rosterTotal(roster: Int): Int = (0 until 16).sumOf { (roster ushr (2 * it)) and 3 }
        fun slotCount(roster: Int, k: Int): Int = (roster ushr (2 * k)) and 3
        fun withSlot(roster: Int, k: Int, n: Int): Int = (roster and (3 shl (2 * k)).inv()) or ((n.coerceIn(0, 3)) shl (2 * k))
    }
}
