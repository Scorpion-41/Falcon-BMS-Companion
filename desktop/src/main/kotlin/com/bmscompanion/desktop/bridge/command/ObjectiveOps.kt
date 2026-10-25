package com.bmscompanion.desktop.bridge.command

import com.bmscompanion.desktop.bridge.CampaignArchive
import com.bmscompanion.desktop.bridge.TerrainHeights
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The Command section's objective edits, as undoable [CommandEdit]s on an open save. Two models, as Falcon BMS has
 * them (the section's rule 3):
 *
 * - **A played save** (and a copy of a start that keeps naming it): only what BMS records, the objective deltas
 *   (`.obd`): owner, supply, fuel, losses, last repair and the buildings' states. A delta is added for an objective the
 *   first time one of those changes ([Edits.withDelta]).
 * - **A campaign start whose own objectives BMS reads** ([CommandSave.objectivesEditable], under "Edit BMS's campaign
 *   start" or in the pilot's own copy that reads its own): the records themselves — position, class, name, parent,
 *   owners, priority, flags, links, radar sectors — and objectives added or deleted.
 *
 * Buildings: Falcon BMS 4.38.1 keeps **one byte a building** (0 intact, 1 repaired, 2 damaged, 3 destroyed), in the
 * objective record and in its delta alike (checked on every objective of the shipped starts against the class's
 * feature list, and on every delta of the saves at hand) ([FeatureStates]).
 */
object ObjectiveOps {
    private const val CELL = CampaignArchive.FEET_PER_CELL
    const val MAX_LINKS = 8

    /** The objective's class (OCD number), from the class table. */
    fun ocdOf(doc: CommandSave, o: CmdObjective): Int = doc.names?.ct(o.type)?.entityIdx ?: -1

    /** The objective's type (class-table Type: 1 airbase … 31 SAM site). */
    fun typeOf(doc: CommandSave, o: CmdObjective): Int = doc.names?.ct(o.type)?.type ?: 0

    fun displayName(doc: CommandSave, o: CmdObjective): String =
        o.name.text.trim().ifEmpty { (doc.string(500 + typeOf(doc, o))?.trim().orEmpty() + " ${o.campId}").trim() }

    // ---------------------------------------------------------------- the buildings

    /**
     * Building states in a status array: one byte a building (4.38.1; every delta of the saves at hand is exactly as
     * long as its class's feature list, `CmdDelta.featureState` and `CampaignArchive.featureStatus` read the same).
     */
    object FeatureStates {
        val NAMES = listOf("Intact", "Repaired", "Damaged", "Destroyed")

        @Suppress("UNUSED_PARAMETER")
        fun get(bytes: ByteArray, i: Int, count: Int): Int = (bytes.getOrNull(i)?.toInt() ?: 0) and 0xFF

        /** A copy of [bytes] with building [i] in [state] (grown to [count] when short). */
        fun set(bytes: ByteArray, i: Int, state: Int, count: Int): ByteArray {
            val out = bytes.copyOf(maxOf(bytes.size, i + 1, count))
            out[i] = (state and 3).toByte()
            return out
        }

        fun allIntact(bytes: ByteArray): ByteArray = ByteArray(bytes.size)
        fun damaged(bytes: ByteArray, count: Int): Int = (0 until maxOf(count, bytes.size)).count { get(bytes, it, count) >= 2 }
    }

    /** How many buildings the objective's class has (its FED list), else what its status array holds. */
    fun featureCount(doc: CommandSave, o: CmdObjective): Int {
        val n = doc.tables.features(ocdOf(doc, o)).size
        return if (n > 0) n else o.fstatus.size
    }

    /** The buildings' states the objective has now: its delta's in a save, else the record's. */
    fun statesNow(doc: CommandSave, o: CmdObjective): ByteArray = doc.delta(o.id)?.fstatus ?: o.fstatus

    // ---------------------------------------------------------------- which model

    /** What may be edited of the objectives of [doc], with the reason when nothing may. */
    enum class Mode { FULL, DELTAS, NONE }

    /**
     * [FULL] in a start whose own objectives BMS reads, once the pilot chose to edit it ([editingStart]) or when it is
     * the pilot's own file; [DELTAS] in a played save (BMS reads its deltas); else [NONE].
     */
    fun mode(doc: CommandSave, editingStart: Boolean): Mode = when {
        doc.objectivesEditable && (editingStart || doc.identity.kind == ShippedSaves.Kind.PILOT) -> Mode.FULL
        doc.objectivesEditable -> Mode.NONE
        doc.obd != null && doc.obd.editable && !doc.deltasIgnored && doc.objectives.isNotEmpty() -> Mode.DELTAS
        else -> Mode.NONE
    }

    fun modeWhy(doc: CommandSave, editingStart: Boolean): String = when (mode(doc, editingStart)) {
        Mode.FULL -> "This campaign start's own objectives: every field, links and radar can be edited."
        Mode.DELTAS -> "A save records only how objectives differ from their start (owner, supply, fuel, losses, buildings): those are edited here."
        Mode.NONE -> when {
            doc.objectivesEditable -> "${doc.file.name} is one of Falcon BMS's campaign starts: choose Edit BMS's campaign start to change its objectives (BMS's original is backed up first)."
            doc.deltasIgnored -> "BMS reads no objective changes for ${doc.file.name} (it names itself as its start)."
            doc.objectives.isEmpty() -> "The objectives of ${doc.file.name}'s start are not available."
            else -> "The objective changes of ${doc.file.name} could not be read exactly, so they are kept as they are."
        }
    }

    // ---------------------------------------------------------------- recorded fields (both models)

    /**
     * A field BMS records in a save as well (owner, supply, fuel, losses, last repair): the record in a start
     * ([Mode.FULL]), the delta in a save.
     */
    fun <T> recorded(
        doc: CommandSave, o: CmdObjective, label: String, full: Boolean,
        objGet: () -> T, objSet: (T) -> Unit, deltaGet: (CmdDelta) -> T, deltaSet: (CmdDelta, T) -> Unit, value: T,
    ): CommandEdit =
        if (full) Edits.set(label, "obj", objGet, objSet, value)
        else Edits.withDelta(doc, o, label) { d -> Edits.set(label, "obd", { deltaGet(d) }, { deltaSet(d, it) }, value) }

    fun supply(doc: CommandSave, o: CmdObjective, v: Int, full: Boolean) =
        recorded(doc, o, "Supply of ${displayName(doc, o)}", full, { o.supply }, { o.supply = it }, { it.supply }, { d, x -> d.supply = x }, v.coerceIn(0, 255))

    fun fuel(doc: CommandSave, o: CmdObjective, v: Int, full: Boolean) =
        recorded(doc, o, "Fuel of ${displayName(doc, o)}", full, { o.fuel }, { o.fuel = it }, { it.fuel }, { d, x -> d.fuel = x }, v.coerceIn(0, 255))

    fun losses(doc: CommandSave, o: CmdObjective, v: Int, full: Boolean) =
        recorded(doc, o, "Losses of ${displayName(doc, o)}", full, { o.losses }, { o.losses = it }, { it.losses }, { d, x -> d.losses = x }, v.coerceIn(0, 255))

    fun owner(doc: CommandSave, o: CmdObjective, team: Int, full: Boolean) =
        recorded(doc, o, "Owner of ${displayName(doc, o)}", full, { o.owner }, { o.owner = it }, { it.owner }, { d, x -> d.owner = x }, team)

    /** Building [i] of [o] to [state] (the delta in a save). */
    fun building(doc: CommandSave, o: CmdObjective, i: Int, state: Int, full: Boolean): CommandEdit {
        val n = featureCount(doc, o)
        val label = "Building ${i + 1} of ${displayName(doc, o)}: ${FeatureStates.NAMES.getOrElse(state) { "$state" }}"
        return recorded(doc, o, label, full, { o.fstatus }, { o.fstatus = it }, { it.fstatus }, { d, x -> d.fstatus = x },
            FeatureStates.set(statesNow(doc, o), i, state, n))
    }

    /** Every building of [objs] intact (Repair). */
    fun repair(doc: CommandSave, objs: List<CmdObjective>, full: Boolean): CommandEdit {
        val label = if (objs.size == 1) "Repair ${displayName(doc, objs[0])}" else "Repair ${objs.size} objectives"
        return Edits.group(label, objs.map { o ->
            recorded(doc, o, label, full, { o.fstatus }, { o.fstatus = it }, { it.fstatus }, { d, x -> d.fstatus = x }, FeatureStates.allIntact(statesNow(doc, o)))
        })
    }

    // ---------------------------------------------------------------- the record (a start)

    fun <T> field(label: String, get: () -> T, set: (T) -> Unit, value: T): CommandEdit = Edits.set(label, "obj", get, set, value)

    fun rename(doc: CommandSave, o: CmdObjective, name: String): CommandEdit =
        field("Name of ${displayName(doc, o)}", { o.name }, { o.name = it }, o.name.copy().also { it.text = name.take(79) })

    /** Moves [o] to (north, east) feet: the exact sim position and the grid cell. */
    fun place(doc: CommandSave, o: CmdObjective, north: Double, east: Double): CommandEdit {
        val label = "Move ${displayName(doc, o)}"
        return Edits.group(label, listOf(
            field(label, { o.simNorth }, { o.simNorth = it }, north), field(label, { o.simEast }, { o.simEast = it }, east),
            field(label, { o.x }, { o.x = it }, floor(east / CELL).toInt()), field(label, { o.y }, { o.y = it }, floor(north / CELL).toInt()),
        ))
    }

    /** The objective's class becomes class-table entry [type] (the record's prefix and entity type), its buildings resized. */
    fun changeClass(doc: CommandSave, o: CmdObjective, type: Int): CommandEdit {
        val label = "Class of ${displayName(doc, o)}"
        val ocd = doc.names?.ct(type)?.entityIdx ?: -1
        val n = doc.tables.features(ocd).size
        val edits = mutableListOf(field(label, { o.type }, { o.type = it }, type), field(label, { o.entityType }, { o.entityType = it }, type))
        if (n > 0 && o.fstatus.size != n) edits += field(label, { o.fstatus }, { o.fstatus = it }, o.fstatus.copyOf(n))
        return Edits.group(label, edits)
    }

    /** Objective flag bits (the editor's names; BMS's meaning where it is known). */
    val FLAGS = listOf(
        0x1 to "Front line", 0x2 to "Second line", 0x4 to "Third line", 0x10 to "Jammed", 0x100 to "Manual (kept by bulk changes)",
        0x400 to "SAM capable", 0x800 to "Artillery", 0x10000 to "Radar", 0x20000 to "Needs repair", 0x40000 to "Engineers assigned",
        0x100000 to "Abandoned", 0x200000 to "NCTR", 0x400000 to "GCI",
    )

    fun setFlag(doc: CommandSave, o: CmdObjective, bit: Int, on: Boolean): CommandEdit {
        val name = FLAGS.firstOrNull { it.first == bit }?.second ?: "flag 0x${bit.toString(16)}"
        val v = if (on) o.objFlags or bit else o.objFlags and bit.inv()
        return field("$name of ${displayName(doc, o)} ${if (on) "on" else "off"}", { o.objFlags }, { o.objFlags = it }, v)
    }

    // ---------------------------------------------------------------- links

    fun cellKm(a: CmdObjective, b: CmdObjective): Double = hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble())

    /**
     * What a link costs per kilometre for each of the eight movement types (none, foot, wheeled, tracked, low air, air,
     * naval, rail), **fitted to the theater's own links** in [doc] (the median of cost over length of every link that
     * is passable for that type), and whether a type is impassable on nearly every link (then a new link gets 255).
     */
    class CostModel(val perKm: DoubleArray, val closed: BooleanArray) {
        fun costs(km: Double): IntArray = IntArray(8) { i -> if (closed[i]) 255 else (km * perKm[i]).toInt().coerceIn(1, 254) }
        /** [old]'s costs for a link [km] long: a type closed on it stays closed. */
        fun recost(old: IntArray, km: Double): IntArray = IntArray(8) { i -> if (old.getOrElse(i) { 255 } >= 255) 255 else (km * perKm[i]).toInt().coerceIn(1, 254) }
    }

    fun costModel(doc: CommandSave): CostModel {
        val objs = doc.objectives
        val byId = objs.associateBy { it.id }
        val ratios = Array(8) { ArrayList<Double>() }
        val closed = IntArray(8)
        var links = 0
        for (o in objs) for (l in o.links) {
            val b = byId[l.id] ?: continue
            val km = cellKm(o, b)
            links++
            for (i in 0 until 8) {
                val c = l.costs.getOrElse(i) { 255 }
                if (c >= 255) closed[i]++ else if (km >= 1.0) ratios[i] += c / km
            }
        }
        val fallback = doubleArrayOf(1.0, 1.6, 4.3, 2.9, 1.1, 1.1, 1.2, 1.0)
        val per = DoubleArray(8) { i -> ratios[i].sorted().let { if (it.isEmpty()) fallback[i] else it[it.size / 2] } }
        return CostModel(per, BooleanArray(8) { i -> links == 0 && i == 7 || links > 0 && closed[i] >= links * 0.9 })
    }

    /** A link both ways between [a] and [b], costed by the theater's own links. */
    fun link(doc: CommandSave, a: CmdObjective, b: CmdObjective, model: CostModel = costModel(doc)): CommandEdit? {
        if (a === b || a.links.any { it.id == b.id }) return null
        val c = model.costs(cellKm(a, b))
        val label = "Link ${displayName(doc, a)} and ${displayName(doc, b)}"
        val edits = mutableListOf(Edits.insert(label, "obj", a.links, ObjLink(c, b.id)))
        if (b.links.none { it.id == a.id }) edits += Edits.insert(label, "obj", b.links, ObjLink(c.copyOf(), a.id))
        return Edits.group(label, edits)
    }

    fun unlink(doc: CommandSave, a: CmdObjective, b: CmdObjective): CommandEdit? {
        val la = a.links.filter { it.id == b.id }
        val lb = b.links.filter { it.id == a.id }
        if (la.isEmpty() && lb.isEmpty()) return null
        val label = "Unlink ${displayName(doc, a)} and ${displayName(doc, b)}"
        return Edits.group(label, la.map { Edits.remove(label, "obj", a.links, it) } + lb.map { Edits.remove(label, "obj", b.links, it) })
    }

    /** Every link of [objs] removed, both ways. */
    fun removeLinks(doc: CommandSave, objs: List<CmdObjective>): CommandEdit {
        val set = objs.map { it.id }.toSet()
        val label = if (objs.size == 1) "Remove the links of ${displayName(doc, objs[0])}" else "Remove the links of ${objs.size} objectives"
        val edits = ArrayList<CommandEdit>()
        for (o in doc.objectives) {
            if (o.id in set) o.links.forEach { edits += Edits.remove(label, "obj", o.links, it) }
            else o.links.filter { it.id in set }.forEach { edits += Edits.remove(label, "obj", o.links, it) }
        }
        return Edits.group(label, edits)
    }

    /** [objs]' links costed again from their length, by the theater's own links ([costModel]); closed types stay closed. */
    fun recost(doc: CommandSave, objs: List<CmdObjective>): CommandEdit {
        val model = costModel(doc)
        val byId = doc.objectives.associateBy { it.id }
        val label = if (objs.size == doc.objectives.size) "Recalculate every link's costs" else "Recalculate the links' costs of ${objs.size} objective${if (objs.size == 1) "" else "s"}"
        val edits = ArrayList<CommandEdit>()
        for (o in objs) for (l in o.links) {
            val b = byId[l.id] ?: continue
            val c = model.recost(l.costs, cellKm(o, b))
            if (!c.contentEquals(l.costs)) edits += Edits.set(label, "obj", { l.costs }, { l.costs = it }, c)
        }
        return Edits.group(label, edits)
    }

    /** The objective types that carry links in this theater (at least half their objectives have one). */
    fun linkedTypes(doc: CommandSave): Set<Int> = doc.objectives.groupBy { typeOf(doc, it) }
        .filter { (_, os) -> os.count { it.links.isNotEmpty() } * 2 >= os.size }.keys

    /**
     * Links made for [objs] to their neighbours, the way the theater's network is laid: to the nearest objectives of
     * a linked type ([linkedTypes]) within [maxKm], at most [MAX_LINKS] each, leaving out a pair with another objective
     * close to the line between them or already joined through a neighbour of both (a short detour). Water is not
     * looked at: a link across a strait should be removed by hand.
     */
    fun autoLink(doc: CommandSave, objs: List<CmdObjective>, maxKm: Double = 25.0): CommandEdit {
        val types = linkedTypes(doc)
        val pool = doc.objectives.filter { typeOf(doc, it) in types }
        val grid = HashMap<Long, MutableList<CmdObjective>>()
        val bucket = 10
        fun key(x: Int, y: Int) = (x / bucket).toLong() * 100_000L + (y / bucket)
        pool.forEach { grid.getOrPut(key(it.x, it.y)) { ArrayList() } += it }
        fun near(o: CmdObjective, km: Double): List<CmdObjective> {
            val r = (km / bucket).toInt() + 1
            val out = ArrayList<CmdObjective>()
            for (dx in -r..r) for (dy in -r..r) grid[((o.x / bucket) + dx).toLong() * 100_000L + ((o.y / bucket) + dy)]?.let { out += it }
            return out.filter { it !== o && cellKm(o, it) <= km }
        }
        // the links as they will be: existing plus those added here
        val adj = HashMap<VuId, MutableSet<VuId>>()
        doc.objectives.forEach { o -> adj.getOrPut(o.id) { HashSet() }.addAll(o.links.map { it.id }) }
        val byId = doc.objectives.associateBy { it.id }
        val model = costModel(doc)
        val label = if (objs.size == 1) "Auto-link ${displayName(doc, objs[0])}" else "Auto-link ${objs.size} objectives"
        val edits = ArrayList<CommandEdit>()
        for (a in objs) {
            if (typeOf(doc, a) !in types) continue
            for (b in near(a, maxKm).sortedBy { cellKm(a, it) }) {
                val la = adj.getOrPut(a.id) { HashSet() }
                val lb = adj.getOrPut(b.id) { HashSet() }
                if (la.size >= MAX_LINKS) break
                if (b.id in la || lb.size >= MAX_LINKS) continue
                val d = cellKm(a, b)
                val between = near(a, d).any { c -> c !== b && segmentDistance(a, b, c)?.let { it < 1.5 } == true }
                if (between) continue
                // already joined through a neighbour of both lying roughly on the way
                val detour = la.any { cid -> cid in lb && byId[cid]?.let { c -> cellKm(a, c) + cellKm(c, b) < d * 1.3 } == true }
                if (detour) continue
                val c = model.costs(d)
                edits += Edits.insert(label, "obj", a.links, ObjLink(c, b.id))
                edits += Edits.insert(label, "obj", b.links, ObjLink(c.copyOf(), a.id))
                la += b.id; lb += a.id
            }
        }
        return Edits.group(label, edits)
    }

    /** How far [c] is from the line a-b (km), when it lies between them (not near either end); else null. */
    private fun segmentDistance(a: CmdObjective, b: CmdObjective, c: CmdObjective): Double? {
        val ax = a.x.toDouble(); val ay = a.y.toDouble()
        val dx = b.x - ax; val dy = b.y - ay
        val len2 = dx * dx + dy * dy
        if (len2 < 1e-9) return null
        val t = ((c.x - ax) * dx + (c.y - ay) * dy) / len2
        if (t < 0.15 || t > 0.85) return null
        return abs((c.x - ax) * dy - (c.y - ay) * dx) / kotlin.math.sqrt(len2)
    }

    /** One problem of the objective network, and its fix when there is one. */
    class LinkIssue(val objective: CmdObjective, val text: String, val fix: CommandEdit?)

    /** The network's problems: too many links, links to nothing, to itself, twice, without a way back, long ones. */
    fun checkLinks(doc: CommandSave, longKm: Double = 60.0): List<LinkIssue> {
        val byId = doc.objectives.associateBy { it.id }
        val types = linkedTypes(doc)
        val out = ArrayList<LinkIssue>()
        for (o in doc.objectives) {
            val n = displayName(doc, o)
            if (o.links.size > MAX_LINKS) out += LinkIssue(o, "$n has ${o.links.size} links (BMS uses at most $MAX_LINKS).", null)
            val seen = HashSet<VuId>()
            for (l in o.links) {
                val b = byId[l.id]
                when {
                    l.id == o.id -> out += LinkIssue(o, "$n is linked to itself.", Edits.remove("Remove $n's link to itself", "obj", o.links, l))
                    b == null -> out += LinkIssue(o, "$n links to ${l.id}, which is not an objective of this start.", Edits.remove("Remove $n's link to ${l.id}", "obj", o.links, l))
                    !seen.add(l.id) -> out += LinkIssue(o, "$n links to ${displayName(doc, b)} twice.", Edits.remove("Remove $n's second link to ${displayName(doc, b)}", "obj", o.links, l))
                    b.links.none { it.id == o.id } -> out += LinkIssue(o, "$n links to ${displayName(doc, b)}, which has no link back.",
                        if (b.links.size < MAX_LINKS) Edits.insert("Link ${displayName(doc, b)} back to $n", "obj", b.links, ObjLink(l.costs.copyOf(), o.id)) else null)
                    cellKm(o, b) > longKm && o.id.num < b.id.num -> out += LinkIssue(o, "$n and ${displayName(doc, b)} are linked across ${"%.0f".format(cellKm(o, b))} km.", null)
                }
            }
            if (o.links.isEmpty() && typeOf(doc, o) in types) out += LinkIssue(o, "$n has no links (objectives of its type are linked).", null)
        }
        return out
    }

    // ---------------------------------------------------------------- radar sectors

    /** The eight sectors' bearings (degrees true), in the order the record keeps them: 135-180, 090-135 … 180-225. */
    fun sectorBearings(i: Int): Pair<Double, Double> = (180.0 - 45.0 * (i + 1)).let { a -> (if (a < 0) a + 360 else a) to (if (a < 0) a + 360 else a) + 45.0 }

    const val RADAR_DEFAULT = 0.022f

    /**
     * The eight detection ratios of a radar at [o] worked out from the ground round it, from Falcon BMS's own height
     * map ([TerrainHeights]): per sector the steepest rise of the ground seen from an antenna 50 ft above the site
     * (rise over distance, out to 10 km), never below [RADAR_DEFAULT] (BMS's own value for open ground). Null with the
     * reason when the theater has no height map. Held against 40 radars of Korea's shipped Save1 (read only): this
     * sector order fits BMS's own figures far better than any other turn or mirror of it, most within a few per cent.
     */
    fun radarFromTerrain(doc: CommandSave, o: CmdObjective): Pair<FloatArray?, String?> {
        val set = doc.theaters ?: return null to "The theater definitions were not found."
        val t = doc.theater ?: return null to "The file's theater was not found."
        val n0 = if (o.simNorth.isFinite() && o.simNorth != 0.0) o.simNorth else (o.y + 0.5) * CELL
        val e0 = if (o.simEast.isFinite() && o.simEast != 0.0) o.simEast else (o.x + 0.5) * CELL
        val pts = ArrayList<Pair<Double, Double>>()
        val meta = ArrayList<Triple<Int, Double, Int>>()
        pts += n0 to e0
        val ranges = (1..40).map { it * 250.0 * 3.28084 }
        for (s in 0 until 8) {
            val (from, _) = sectorBearings(s)
            for (k in 0..8) {
                val b = Math.toRadians(from + k * 45.0 / 8)
                for ((ri, r) in ranges.withIndex()) {
                    pts += (n0 + r * cos(b)) to (e0 + r * sin(b))
                    meta += Triple(s, r, ri)
                }
            }
        }
        val g = TerrainHeights.at(set, t, pts)
        if (g.heights.all { it == null }) return null to (g.error ?: "No ground heights for ${t.name}.")
        val antenna = (g.heights[0] ?: 0) + 50.0
        val best = FloatArray(8) { RADAR_DEFAULT }
        for ((i, m) in meta.withIndex()) {
            val h = g.heights[i + 1] ?: continue
            val ratio = ((h - antenna) / m.second).toFloat()
            if (ratio > best[m.first]) best[m.first] = ratio
        }
        return best to null
    }

    /** Sets [o]'s radar data: [ratios] (eight) and whether it has any. */
    fun setRadar(doc: CommandSave, o: CmdObjective, ratios: FloatArray?, label: String): CommandEdit {
        val bits = ratios?.let { r -> IntArray(8) { r.getOrElse(it) { RADAR_DEFAULT }.toRawBits() } } ?: o.detectRatioBits.copyOf()
        return Edits.group(label, listOf(
            field(label, { o.hasRadar }, { o.hasRadar = it }, if (ratios != null) 1 else 0),
            field(label, { o.detectRatioBits }, { o.detectRatioBits = it }, bits),
        ))
    }

    // ---------------------------------------------------------------- adding and deleting objectives (a start)

    /**
     * A new objective at (north, east): a copy of [template]'s class, owners, flags and figures (its buildings intact,
     * no links, no radar data unless the class has it), with a new id and CampId and [name].
     */
    fun add(doc: CommandSave, template: CmdObjective, north: Double, east: Double, name: String): Pair<CommandEdit?, CmdObjective?> {
        val objs = doc.obj?.objectives ?: return null to null
        val o = template.copy()
        o.id = CommandIds.objectiveId(doc)
        o.campId = CommandIds.campId(doc)
        o.links = ArrayList()
        o.fstatus = ByteArray(featureCount(doc, template).coerceAtLeast(template.fstatus.size))
        o.spotted = 0; o.spotTime = 0; o.lastRepair = 0; o.losses = 0; o.baseFlags = 0
        o.parent = VuId.NONE
        o.simNorth = north; o.simEast = east
        o.x = floor(east / CELL).toInt(); o.y = floor(north / CELL).toInt()
        o.name = o.name.copy().also { it.text = name.take(79) }
        o.raw = ByteArray(0)
        return Edits.insert("Add objective ${name.ifBlank { "${o.campId}" }}", "obj", objs, o) to o
    }

    /**
     * [objs] deleted from the start, with every link to them, every parent naming them (cleared) and their places in
     * the primary objective list. Units still ordered to them are named by the Checks page.
     */
    fun delete(doc: CommandSave, objs: List<CmdObjective>): CommandEdit {
        val list = doc.obj?.objectives ?: return Edits.failing("Delete objectives", "obj", "the file has no objectives of its own")
        val ids = objs.map { it.id }.toSet()
        val label = if (objs.size == 1) "Delete ${displayName(doc, objs[0])}" else "Delete ${objs.size} objectives"
        val edits = ArrayList<CommandEdit>()
        for (o in list) {
            if (o.id in ids) continue
            o.links.filter { it.id in ids }.forEach { edits += Edits.remove(label, "obj", o.links, it) }
            if (o.parent in ids) edits += field(label, { o.parent }, { o.parent = it }, VuId.NONE)
        }
        doc.pol?.takeIf { it.editable }?.let { pol -> pol.entries.filter { it.id in ids }.forEach { edits += Edits.remove(label, "pol", pol.entries, it) } }
        doc.obd?.takeIf { it.editable }?.let { obd -> obd.deltas.filter { it.id in ids }.forEach { edits += Edits.remove(label, "obd", obd.deltas, it) } }
        objs.forEach { edits += Edits.remove(label, "obj", list, it) }
        return Edits.group(label, edits)
    }

    /** What still names [objs] (units' airbases, orders, targets and waypoints), for the warning before a delete. */
    fun usedBy(doc: CommandSave, objs: List<CmdObjective>): List<String> {
        val ids = objs.map { it.id }.toSet()
        val out = ArrayList<String>()
        for (u in doc.units) {
            val why = when {
                u is CmdSquadron && u.airbaseId in ids -> "its airbase"
                u is CmdGroundUnit && u.aobj in ids -> "its objective"
                u.targetId in ids -> "its target"
                u.waypoints.any { it.hasTarget && it.targetId in ids } -> "a waypoint's target"
                else -> null
            } ?: continue
            out += "${UnitNames.full(doc, u)} ($why)"
        }
        return out
    }

    // ---------------------------------------------------------------- deltas and persistent objects (a save)

    /** Deltas that say nothing (equal to their start) or name an objective the start does not have. */
    fun idleDeltas(doc: CommandSave): List<CmdDelta> {
        val obd = doc.obd ?: return emptyList()
        val byId = doc.objectives.associateBy { it.id }
        if (byId.isEmpty()) return emptyList()
        return obd.deltas.filter { d ->
            val o = byId[d.id] ?: return@filter true
            d.owner == o.owner && d.supply == o.supply && d.fuel == o.fuel && d.losses == o.losses && d.lastRepair == o.lastRepair && d.fstatus.contentEquals(o.fstatus)
        }
    }

    fun removeDeltas(doc: CommandSave, ds: List<CmdDelta>, label: String): CommandEdit {
        val obd = doc.obd ?: return Edits.failing(label, "obd", "the file has no objective delta part")
        return Edits.group(label, ds.map { Edits.remove(label, "obd", obd.deltas, it) })
    }

    fun removePersistent(doc: CommandSave, ps: List<PersistentObject>, label: String): CommandEdit {
        val pst = doc.pst ?: return Edits.failing(label, "pst", "the file has no persistent object part")
        return Edits.group(label, ps.map { Edits.remove(label, "pst", pst.objects, it) })
    }

    /** The objective a persistent object lies at (within 2 km of its sim position), for the list. */
    fun nearestObjective(doc: CommandSave, p: PersistentObject): CmdObjective? {
        val n = p.x.toDouble(); val e = p.y.toDouble()
        return doc.objectives.minByOrNull { o -> hypot(o.simNorth - n, o.simEast - e) }?.takeIf { o -> hypot(o.simNorth - n, o.simEast - e) < 2 * 3280.84 }
    }

    /** Bearing (degrees true) from [a] to [b] by their cells. */
    fun bearing(a: CmdObjective, b: CmdObjective): Double = (Math.toDegrees(atan2((b.x - a.x).toDouble(), (b.y - a.y).toDouble())) + 360) % 360
}
