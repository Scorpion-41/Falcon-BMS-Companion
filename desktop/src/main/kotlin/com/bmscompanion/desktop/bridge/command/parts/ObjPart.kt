package com.bmscompanion.desktop.bridge.command

/**
 * A link from an objective to a neighbour: the cost of moving along it for each of the eight movement types, and the
 * neighbour's id.
 */
class ObjLink(var costs: IntArray = IntArray(8) { 255 }, var id: VuId = VuId.NONE) {
    fun write(w: LeOut) { w.u8s(costs); w.id(id) }
    fun copy() = ObjLink(costs.copyOf(), id)
    companion object { fun read(r: LeIn) = ObjLink(r.u8s(8), r.id()) }
}

/**
 * One objective of a campaign start (`.obj`): the campaign base (id, class, grid cell [x] east / [y] north, height,
 * spotting, base flags, owner, CampId), last repair, objective flags, supply, fuel, losses, the buildings' states
 * ([fstatus], one byte a building in 4.38.1: 0 intact, 1 repaired, 2 damaged, 3 destroyed), priority, name number, parent, first owner, links, radar detection ratios (eight,
 * when [radar] is set), its exact sim position (feet: [simNorth], [simEast], [simZ]) and heading (radians), and its
 * name (80).
 */
class CmdObjective {
    var type = 0
    var id = VuId.NONE
    var entityType = 0
    var x = 0
    var y = 0
    var zBits = 0
    var spotTime = 0L
    var spotted = 0
    var baseFlags = 0
    var owner = 0
    var campId = 0
    var lastRepair = 0L
    var objFlags = 0
    var supply = 0
    var fuel = 0
    var losses = 0
    var fstatus = ByteArray(0)
    var priority = 0
    var nameId = 0
    var parent = VuId.NONE
    var firstOwner = 0
    var links: MutableList<ObjLink> = ArrayList()
    /** radar data present ([detectRatio] eight floats) */
    var hasRadar = 0
    var detectRatioBits = IntArray(8)
    var simNorthBits = 0L
    var simEastBits = 0L
    var simZBits = 0L
    var headingBits = 0
    var name = FixedText.of(80)
    var raw = ByteArray(0)

    var z: Float get() = fl(zBits); set(v) { zBits = v.bits() }
    var simNorth: Double get() = Double.fromBits(simNorthBits); set(v) { simNorthBits = v.toRawBits() }
    var simEast: Double get() = Double.fromBits(simEastBits); set(v) { simEastBits = v.toRawBits() }
    var simZ: Double get() = Double.fromBits(simZBits); set(v) { simZBits = v.toRawBits() }
    var heading: Float get() = fl(headingBits); set(v) { headingBits = v.bits() }
    fun detectRatio(i: Int): Float = fl(detectRatioBits[i])

    /** The state of building [i] (0 intact … 3 destroyed; one byte a building, [ObjectiveOps.FeatureStates]). */
    fun featureState(i: Int): Int = (fstatus.getOrNull(i)?.toInt() ?: 0) and 0xFF

    fun write(w: LeOut) {
        w.i16(type)
        w.id(id); w.u16(entityType); w.i16(x); w.i16(y); w.i32(zBits)
        w.u32(spotTime); w.i16(spotted); w.i16(baseFlags); w.u8(owner); w.i16(campId)
        w.u32(lastRepair); w.i32(objFlags); w.u8(supply); w.u8(fuel); w.u8(losses)
        w.u8(fstatus.size); w.bytes(fstatus)
        w.u8(priority); w.i16(nameId); w.id(parent); w.u8(firstOwner)
        w.u8(links.size); links.forEach { it.write(w) }
        w.u8(hasRadar)
        if (hasRadar != 0) w.ints32(detectRatioBits)
        w.i64(simNorthBits); w.i64(simEastBits); w.i64(simZBits); w.i32(headingBits)
        w.text(name)
    }

    fun encode(): ByteArray = LeOut(256).also { write(it) }.toByteArray()
    fun copy(): CmdObjective = read(LeIn(encode()))

    companion object {
        fun read(r: LeIn): CmdObjective {
            val o = CmdObjective()
            o.type = r.i16()
            o.id = r.id(); o.entityType = r.u16(); o.x = r.i16(); o.y = r.i16(); o.zBits = r.i32()
            o.spotTime = r.u32(); o.spotted = r.i16(); o.baseFlags = r.i16(); o.owner = r.u8(); o.campId = r.i16()
            o.lastRepair = r.u32(); o.objFlags = r.i32(); o.supply = r.u8(); o.fuel = r.u8(); o.losses = r.u8()
            o.fstatus = r.bytes(r.u8())
            o.priority = r.u8(); o.nameId = r.i16(); o.parent = r.id(); o.firstOwner = r.u8()
            o.links = MutableList(r.u8()) { ObjLink.read(r) }
            o.hasRadar = r.u8()
            if (o.hasRadar != 0) o.detectRatioBits = r.ints32(8)
            o.simNorthBits = r.i64(); o.simEastBits = r.i64(); o.simZBits = r.i64(); o.headingBits = r.i32()
            o.name = r.text(80)
            return o
        }
    }
}

/** The objectives of a start (`.obj`), in order; [tail] what a short walk left. */
class ObjPart(var objectives: MutableList<CmdObjective>, var tail: ByteArray, val stop: String?, val headerCount: Int, val headerOnly: Boolean) {
    val editable: Boolean get() = stop == null && tail.isEmpty()
    val count: Int get() = objectives.size

    fun encode(): ByteArray {
        val w = LeOut(objectives.size * 160 + tail.size)
        objectives.forEach { it.write(w) }
        w.bytes(tail)
        return w.toByteArray()
    }

    companion object {
        fun read(body: ByteArray, count: Int): ObjPart {
            val r = LeIn(body)
            val out = ArrayList<CmdObjective>(maxOf(0, count))
            var stop: String? = null
            while (out.size < count && r.p < body.size) {
                val start = r.p
                try {
                    val o = CmdObjective.read(r)
                    o.raw = body.copyOfRange(start, r.p)
                    out += o
                } catch (e: Throwable) {
                    stop = "the objective at byte $start could not be read: ${e.message ?: e.javaClass.simpleName}"
                    r.p = start
                    break
                }
            }
            if (stop == null && out.size < count && body.isNotEmpty()) stop = "the part ended after ${out.size} of $count objectives"
            return ObjPart(out, body.copyOfRange(r.p, body.size), stop, count, body.isEmpty())
        }
    }
}

/**
 * How one objective differs from its start in a save (`.obd`): owner now, supply, fuel, losses, last repair and the
 * buildings' states (one byte a building). The only objective data a played save records.
 */
class CmdDelta(
    var id: VuId, var lastRepair: Long, var owner: Int, var supply: Int, var fuel: Int, var losses: Int, var fstatus: ByteArray,
) {
    var raw = ByteArray(0)
    fun write(w: LeOut) { w.id(id); w.u32(lastRepair); w.u8(owner); w.u8(supply); w.u8(fuel); w.u8(losses); w.u8(fstatus.size); w.bytes(fstatus) }
    fun copy() = CmdDelta(id, lastRepair, owner, supply, fuel, losses, fstatus.copyOf())
    fun featureState(i: Int): Int = (fstatus.getOrNull(i)?.toInt() ?: 0) and 0xFF
    fun setFeatureState(i: Int, state: Int) {
        if (i >= fstatus.size) fstatus = fstatus.copyOf(i + 1)
        fstatus[i] = (state and 3).toByte()
    }

    companion object {
        fun read(r: LeIn) = CmdDelta(r.id(), r.u32(), r.u8(), r.u8(), r.u8(), r.u8(), r.bytes(r.u8()))

        /** A delta for [o] as its start has it (the first change of an objective in a save). */
        fun of(o: CmdObjective) = CmdDelta(o.id, o.lastRepair, o.owner, o.supply, o.fuel, o.losses, o.fstatus.copyOf())
    }
}

/** The objective deltas of a save (`.obd`). */
class ObdPart(var deltas: MutableList<CmdDelta>, var tail: ByteArray, val stop: String?, val headerCount: Int) {
    val editable: Boolean get() = stop == null && tail.isEmpty()
    val count: Int get() = deltas.size
    fun delta(id: VuId): CmdDelta? = deltas.firstOrNull { it.id == id }

    fun encode(): ByteArray {
        val w = LeOut(deltas.size * 24 + tail.size)
        deltas.forEach { it.write(w) }
        w.bytes(tail)
        return w.toByteArray()
    }

    companion object {
        fun read(body: ByteArray, count: Int): ObdPart {
            val r = LeIn(body)
            val out = ArrayList<CmdDelta>(maxOf(0, count))
            var stop: String? = null
            while (out.size < count && r.p < body.size) {
                val start = r.p
                try {
                    val d = CmdDelta.read(r)
                    d.raw = body.copyOfRange(start, r.p)
                    out += d
                } catch (e: Throwable) {
                    stop = "the delta at byte $start could not be read: ${e.message ?: e.javaClass.simpleName}"
                    r.p = start
                    break
                }
            }
            if (stop == null && out.size < count) stop = "the part ended after ${out.size} of $count deltas"
            return ObdPart(out, body.copyOfRange(r.p, body.size), stop, count)
        }
    }
}
