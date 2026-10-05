package com.bmscompanion.app.data.wdp

import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.Station
import com.bmscompanion.app.data.Weapon
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The jet's stores for WDP's Loadout window (`fclsLoadout`): what hangs on each hardpoint of each aircraft of the
 * flight, and what that weighs, drags and carries in fuel.
 *
 * WDP reads the flight's loadout out of the campaign and the stores' figures out of BMS's weapon table. The app has
 * both already: the briefing's ordnance table (what each aircraft carries) and BMS's own weapon and aircraft data
 * (each store's weight, drag index and — for a tank — its fuel; which hardpoint takes which store, and how many).
 * The briefing does not say which hardpoint a store is on, so [fromBriefing] hangs each store where the jet can
 * carry it, a symmetric pair at a time from the wingtips inwards; weight, drag and fuel do not depend on where.
 *
 * The totals are WDP's `WeightsDragsFuel`: the clean jet's drag index of 1 plus each store's, times its count, plus
 * — once for each loaded hardpoint — the weight and drag of the **pylon and rack** it hangs from. Those are Falcon BMS
 * 4.38.1's own, from its `BmsRack.dat` and weapon table (`data/wdp/racks.json`, written by
 * `tools/extractor/src/wdpracks.mjs`): an F-16 wing pylon 320 lb / 14, a LAU-129 rail 95 lb / 2, a TER 369 lb / 24.
 * WDP reads the same entries through the campaign's pylon and rack ids. The briefing does not say which rack BMS
 * used, so [mount] takes the first that has room for the count, in the order BMS's file lists them (smallest
 * first, the file says) — which has not been compared with a running BMS's own loadout page.
 *
 * A store's own weight, drag and fuel are the **mission theater's** ([figures], [Racks.storeFigures]): BMS flies a
 * theater with its own weapon table, as WDP reads the running theater's, and a few of the add-on theaters' F-16 stores
 * differ from Korea's, which is what the app's weapon data keeps (Hellas's AN/AAQ-13 NAVPOD drags 32 where Korea's
 * drags 22, EF2000's Litening pod 12 where Korea's 22). A jet carrying conformal tanks adds their drag to the clean
 * jet's ([airframeDrag]); their weight and fuel are the jet's own ([PerformancePlan.cftFromFuel]).
 */
class PerformanceLoadout(
    /** the aircraft's hardpoints, 1–9 and the two chin stations 10 (5L) and 11 (5R), as the app's aircraft data has them */
    val stations: List<Station>,
    /** every store the app knows, by its key */
    val weapons: Map<String, Weapon>,
    /** by hardpoint and store: every pylon and rack that can carry it, in BMS's order ([Racks.forVariant]); empty for none */
    val mounts: Map<Int, Map<String, List<Mount>>> = emptyMap(),
    /** each store's weight, drag and fuel in the mission's theater, by key ([Racks.storeFigures]); the app's own where absent */
    val figures: Map<String, Figures> = emptyMap(),
) {
    /** A pylon and a rack BMS can hang a store on: how many it takes, and what each weighs and drags. */
    data class Mount(val slots: Int, val pylonLbs: Int, val pylonDrag: Int, val rackLbs: Int, val rackDrag: Int)

    /** A store's weight (lb), drag index and — for a tank — fuel (lb, WDP's `GetExtFuel`: the weapon's strength) in one theater. */
    data class Figures(val weightLbs: Double, val drag: Double, val fuelLbs: Double)

    /** The drag the jet itself adds to the clean F-16's [PLANE_DRAG]: its conformal tanks' when it carries them, else 0. */
    var airframeDrag = 0
    /** One hardpoint's load: which store and how many of it. */
    data class Load(val key: String, val count: Int)

    /** The flight's aircraft, lead first, each a map of hardpoint → load. WDP's `Aircraft[seat].LoadoutTable`. */
    val seats: List<MutableMap<Int, Load>> = List(4) { LinkedHashMap() }

    /** How many aircraft the flight has (the seats that can be picked). */
    var aircraftCount = 1

    /** A store by the name the briefing prints ("AIM-120C AMRAAM", "Tank 370gal"). */
    fun byName(name: String): Weapon? {
        val n = name.trim()
        return weapons.values.firstOrNull { it.name.equals(n, true) }
            ?: weapons.values.firstOrNull { norm(it.name) == norm(n) }
    }

    /** Whether hardpoint [hp] takes [key], and how many of it at most (0 when it does not). */
    fun max(hp: Int, key: String): Int = stations.firstOrNull { it.n == hp }?.weapons?.firstOrNull { it.key == key }?.max ?: 0

    /**
     * The pylon and rack BMS hangs [load] from on [hp]: the first in its file's order with room for the count, else
     * the largest; null where the app has no rack data for that station and store (then nothing is added).
     */
    fun mount(hp: Int, load: Load): Mount? {
        val list = mounts[hp]?.get(load.key)?.takeIf { it.isNotEmpty() } ?: return null
        return list.firstOrNull { it.slots >= load.count } ?: list.last()
    }

    /** The rack a store hangs from on [hp], or null for none. */
    fun rack(hp: Int, key: String): String? =
        stations.firstOrNull { it.n == hp }?.weapons?.firstOrNull { it.key == key }?.rack?.takeIf { !it.contains("no-rack") && !it.contains("no-rail") }

    /** Every store the jet can carry somewhere, in the order WDP's list shows them (by name). */
    val stores: List<Weapon> by lazy {
        stations.flatMap { s -> s.weapons.map { it.key } }.distinct().mapNotNull { weapons[it] }.sortedBy { it.name.lowercase() }
    }

    /**
     * The briefing's stores, aircraft by aircraft, each hung on the hardpoints that take it — a count up to the
     * station's own maximum, pair by pair in [ORDER]. Guns are part of the jet, not of the loadout.
     */
    fun fromBriefing(aircraft: List<List<Pair<Int, String>>>) {
        aircraftCount = aircraft.size.coerceIn(1, 4)
        for (s in seats) s.clear()
        aircraft.take(4).forEachIndexed { seat, list ->
            for ((qty, name) in list) {
                val w = byName(name) ?: continue
                if (w.category == "GUN") continue
                var left = qty
                while (left > 0) { if (!addOne(seat, w.key)) break; left-- }
            }
        }
    }

    /**
     * One more of [key] on the jet: onto a hardpoint that already carries it and has room, else the first free one
     * that takes it, in [ORDER]. False when there is nowhere left.
     */
    fun addOne(seat: Int, key: String): Boolean = addOneAt(seat, key) != null

    /** [addOne], answering the hardpoint the store went on (null when there was nowhere left). */
    fun addOneAt(seat: Int, key: String): Int? {
        val t = seats[seat]
        for (hp in ORDER) {
            val cur = t[hp] ?: continue
            if (cur.key == key && cur.count < max(hp, key)) { t[hp] = cur.copy(count = cur.count + 1); return hp }
        }
        for (hp in ORDER) {
            if (t[hp] == null && max(hp, key) > 0) { t[hp] = Load(key, 1); return hp }
        }
        return null
    }

    /** Whether the jet has hardpoint [hp]. */
    fun has(hp: Int): Boolean = stations.any { it.n == hp }

    /** The stores hardpoint [hp] takes and how many of each at most, by name. */
    fun fits(hp: Int): List<Pair<Weapon, Int>> = stations.firstOrNull { it.n == hp }?.weapons.orEmpty()
        .mapNotNull { sw -> weapons[sw.key]?.let { it to sw.max } }
        .distinctBy { it.first.key }
        .sortedBy { it.first.name.lowercase() }

    /**
     * [count] of [key] on hardpoint [hp] of [seat], at most what the station takes; 0 or less empties it. False when
     * the station cannot carry the store at all (nothing changes then).
     */
    fun set(seat: Int, hp: Int, key: String, count: Int): Boolean {
        val m = max(hp, key)
        if (m <= 0) return false
        val t = seats[seat]
        val n = minOf(count, m)
        if (n <= 0) t.remove(hp) else t[hp] = Load(key, n)
        return true
    }

    /**
     * One more ([delta] 1) or one fewer (-1) of the store on [hp]; taking the last one off empties the station. False
     * when nothing changed: an empty station, or one already at the most it takes.
     */
    fun step(seat: Int, hp: Int, delta: Int): Boolean {
        val t = seats[seat]
        val cur = t[hp] ?: return false
        val n = cur.count + delta
        if (n > max(hp, cur.key)) return false
        if (n <= 0) t.remove(hp) else t[hp] = cur.copy(count = n)
        return true
    }

    /** Empties hardpoint [hp] of [seat]; false when it was empty. */
    fun clearStation(seat: Int, hp: Int): Boolean = seats[seat].remove(hp) != null

    /** How many stores [seat] carries, all hardpoints together. */
    fun count(seat: Int): Int = seats[seat].values.sumOf { it.count }

    /** Seat [from]'s stores onto every other aircraft of the flight. */
    fun copyToFlight(from: Int) {
        for (i in 0 until aircraftCount.coerceIn(1, 4)) if (i != from) { seats[i].clear(); seats[i].putAll(seats[from]) }
    }

    /**
     * These stores onto [to], another aircraft's hardpoints (a different F-16 picked in Type): each on the same
     * hardpoint where that one takes it — as many as it takes there — and whatever is left wherever it fits.
     */
    fun carryTo(to: PerformanceLoadout) {
        to.aircraftCount = aircraftCount
        for (s in 0 until 4) {
            to.seats[s].clear()
            val left = ArrayList<Pair<String, Int>>()
            for ((hp, l) in seats[s]) {
                val m = to.max(hp, l.key)
                if (m > 0 && to.seats[s][hp] == null) {
                    to.seats[s][hp] = Load(l.key, minOf(l.count, m))
                    if (l.count > m) left += l.key to (l.count - m)
                } else left += l.key to l.count
            }
            for ((key, n) in left) repeat(n) { to.addOneAt(s, key) }
        }
    }

    /**
     * A tap on a hardpoint with a store picked, as WDP's grid cell: a store other than the one there goes on with a
     * count of one; the same store goes up by one, and past the station's maximum comes off.
     */
    fun cycle(seat: Int, hp: Int, key: String): Boolean {
        val m = max(hp, key)
        if (m <= 0) return false
        val t = seats[seat]
        val cur = t[hp]
        val next = if (cur == null || cur.key != key) 1 else if (cur.count >= m) 0 else cur.count + 1
        if (next == 0) t.remove(hp) else t[hp] = Load(key, next)
        return true
    }

    fun clear() { for (s in seats) s.clear() }

    /** The flight's loadout as it is now, to put back with [restore] (the Loadout window's Cancel). */
    fun snapshot(): List<Map<Int, Load>> = seats.map { LinkedHashMap(it) }

    fun restore(s: List<Map<Int, Load>>) {
        seats.forEachIndexed { i, seat -> seat.clear(); s.getOrNull(i)?.let { seat.putAll(it) } }
    }

    /** WDP's `WeightsDragsFuel` for one aircraft: the load's weight, the total drag index, the fuel in its tanks. */
    data class Totals(val loadWeight: Int, val totalDrag: Int, val extFuel: Int)

    fun totals(seat: Int): Totals {
        var weight = 0.0
        var drag = 0.0
        var fuel = 0.0
        for ((hp, l) in seats[seat]) {
            val w = weapons[l.key] ?: continue
            val f = figures[l.key]
            weight += l.count * (f?.weightLbs ?: w.weightLbs ?: 0.0)
            drag += l.count * (f?.drag ?: w.drag ?: 0.0)
            if (w.category == "FUEL_TANK") fuel += l.count * (f?.fuelLbs ?: w.strength ?: 0.0)
            // the hardpoint's pylon and rack, once, whatever the count on it (WDP: LoadoutTable[n].Pylon / .Rack)
            mount(hp, l)?.let { m ->
                weight += m.pylonLbs + m.rackLbs
                drag += m.pylonDrag + m.rackDrag
            }
        }
        return Totals(DataCardNet.roundInt(weight), PLANE_DRAG + airframeDrag + DataCardNet.roundInt(drag), DataCardNet.roundInt(fuel))
    }

    /** "4x AIM-120C AMRAAM, 2x AIM-9X Sidewinder" for the stores of one kind, as the window's summary lines. */
    fun summary(seat: Int, kind: Int): String = seats[seat].values.groupBy { it.key }
        .mapNotNull { (k, loads) -> weapons[k]?.takeIf { category(it) == kind }?.let { "${loads.sumOf { it.count }}x ${it.name}" } }
        .joinToString(", ")

    /**
     * `data/wdp/racks.json`: the pylons and racks of every F-16, per theater (theaters with the same data share one);
     * and, per theater too, what each F-16 store weighs, drags and holds there, and the F-16s' conformal tanks.
     */
    @Serializable
    class Racks(
        val theaters: Map<String, String> = emptyMap(),
        val sets: Map<String, Map<String, Map<String, Int>>> = emptyMap(),
        val tables: List<Map<String, Int>> = emptyList(),
        val options: List<List<List<Int>>> = emptyList(),
        val weights: Map<String, String> = emptyMap(),
        val weightSets: Map<String, WeightSet> = emptyMap(),
    ) {
        /** One theater's store figures (`[lb, drag, strength]` by store key) and conformal tanks (`[lb, fuel lb, drag]` by aircraft key). */
        @Serializable
        class WeightSet(
            val stores: Map<String, List<Double>> = emptyMap(),
            val cft: Map<String, List<Double>> = emptyMap(),
        )

        /** Every store's figures in the first of [theaters] the file has (the mission's own first). */
        fun storeFigures(theaters: List<String>): Map<String, Figures> {
            val set = theaters.firstNotNullOfOrNull { t -> weights[t]?.let { weightSets[it] } } ?: return emptyMap()
            return set.stores.mapNotNull { (k, v) -> if (v.size >= 3) k to Figures(v[0], v[1], v[2]) else null }.toMap()
        }

        /**
         * The conformal tanks an aircraft can carry ([PerformancePlan.Cft]: what they weigh, hold and drag), from the
         * first of [theaters] whose data has that aircraft; null for a jet without them.
         */
        fun cft(aircraftKey: String, theaters: List<String>): PerformancePlan.Cft? {
            val v = theaters.firstNotNullOfOrNull { t -> weights[t]?.let { weightSets[it] }?.cft?.get(aircraftKey) } ?: return null
            if (v.size < 3 || v[1] <= 0.0) return null
            return PerformancePlan.Cft(DataCardNet.roundInt(v[0]), DataCardNet.roundInt(v[1]), DataCardNet.roundInt(v[2]))
        }

        /**
         * One aircraft's mounts by hardpoint and store, from the first of [theaters] (the variant's own, as the app's
         * aircraft data lists them) that the file has.
         */
        fun forVariant(aircraftKey: String, theaters: List<String>): Map<Int, Map<String, List<Mount>>> {
            val set = theaters.firstNotNullOfOrNull { t -> this.theaters[t]?.let { sets[it] }?.get(aircraftKey) } ?: return emptyMap()
            return set.entries.mapNotNull { (n, table) ->
                val hp = n.toIntOrNull() ?: return@mapNotNull null
                hp to tables.getOrNull(table).orEmpty().mapValues { (_, o) ->
                    options.getOrNull(o).orEmpty().filter { it.size >= 5 }.map { Mount(it[0], it[1], it[2], it[3], it[4]) }
                }
            }.toMap()
        }
    }

    companion object {
        /** A clean F-16's own drag index, which WDP starts every total from. */
        const val PLANE_DRAG = 1

        const val RACKS = "data/wdp/racks.json"
        private val json = Json { ignoreUnknownKeys = true }

        /** The pylon and rack data; an empty set (nothing added, as before) when the file cannot be read. */
        suspend fun loadRacks(): Racks = runCatching { Repo.text(RACKS)?.let { json.decodeFromString(Racks.serializer(), it) } }.getOrNull() ?: Racks()

        /** The order stores are hung in: the wingtips, then inwards a symmetric pair at a time, the centreline, the chin. */
        val ORDER = listOf(1, 9, 2, 8, 3, 7, 4, 6, 5, 10, 11)

        /** The hardpoint across the jet from [hp]: 1 and 9, 2 and 8, 3 and 7, 4 and 6, 5L and 5R; null for the centreline. */
        fun mirror(hp: Int): Int? = when (hp) {
            in 1..4, in 6..9 -> 10 - hp
            10 -> 11
            11 -> 10
            else -> null
        }

        /** A hardpoint's name as the jet is marked: 1 to 9, and the chin stations 5L (the app's 10) and 5R (11). */
        fun hpName(hp: Int): String = when (hp) { 10 -> "5L"; 11 -> "5R"; else -> hp.toString() }

        const val AA = 1
        const val AG = 2
        const val OTHER = 3
        const val TANK = 4
        const val ECM = 5

        /** WDP's `GetWpnCat`, in the app's store categories: 1 air-to-air, 2 air-to-ground, 3 the rest. */
        fun category(w: Weapon): Int = when (w.category) {
            "AAM_RADAR", "AAM_IR" -> AA
            "AGM", "ARM", "ANTI_SHIP", "ROCKETS", "BOMB_GUIDED", "BOMB_LGB", "BOMB_GP", "BOMB_CLUSTER", "BOMB_INCENDIARY",
            "BOMB_NUCLEAR", "BOMB_SPECIAL" -> AG
            "FUEL_TANK" -> TANK
            "ECM_POD" -> ECM
            else -> OTHER
        }

        private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
    }
}
