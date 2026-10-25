package com.bmscompanion.desktop.bridge.command

/** One campaign event flag (`.evt`): its number and flags. */
class CampEventFlag(var id: Int, var flags: Int)

/** The event flags (`.evt`): an `i16` count, then id and flags (`i16` each). */
class EvtPart(var events: MutableList<CampEventFlag>, var tail: ByteArray, val stop: String?) {
    val editable: Boolean get() = stop == null && tail.isEmpty()
    fun encode(): ByteArray = LeOut(2 + events.size * 4).also { w ->
        w.i16(events.size); events.forEach { w.i16(it.id); w.i16(it.flags) }; w.bytes(tail)
    }.toByteArray()

    companion object {
        fun read(b: ByteArray): EvtPart = small(b, { EvtPart(ArrayList(), it, "the event part is too short") }) { r ->
            val list = MutableList(r.i16().coerceAtLeast(0)) { CampEventFlag(r.i16(), r.i16()) }
            EvtPart(list, r.bytes(r.left), null)
        }
    }
}

/** A primary objective (`.pol`): its id and, per team, a priority and a flag (written for the teams in the mask). */
class PolEntry(var id: VuId, var priority: IntArray = IntArray(8), var flags: IntArray = IntArray(8))

/** The primary objectives (`.pol`): a team mask, an `i16` count, the entries. */
class PolPart(var teamMask: Int, var entries: MutableList<PolEntry>, var tail: ByteArray, val stop: String?) {
    val editable: Boolean get() = stop == null && tail.isEmpty()
    fun encode(): ByteArray = LeOut(3 + entries.size * 32).also { w ->
        w.u8(teamMask); w.i16(entries.size)
        entries.forEach { e ->
            w.id(e.id)
            for (t in 0 until 8) if (teamMask and (1 shl t) != 0) { w.i16(e.priority[t]); w.u8(e.flags[t]) }
        }
        w.bytes(tail)
    }.toByteArray()

    companion object {
        fun read(b: ByteArray): PolPart = small(b, { PolPart(0, ArrayList(), it, "the primary objective part is too short") }) { r ->
            val mask = r.u8()
            val list = MutableList(r.i16().coerceAtLeast(0)) {
                val e = PolEntry(r.id())
                for (t in 0 until 8) if (mask and (1 shl t) != 0) { e.priority[t] = r.i16(); e.flags[t] = r.u8() }
                e
            }
            PolPart(mask, list, r.bytes(r.left), null)
        }
    }
}

/** A pilot's usage (`.plt`): how many flights use the name, its voice and photo. Names are Strings 2300 + index. */
class PilotUsage(var usage: Int, var voice: Int, var photo: Int)

/**
 * Pilots and callsigns (`.plt`): an `i16` count of pilots (4 bytes each), then an `i16` count of callsigns, one usage
 * byte each — a bit per callsign number (1-8) in use. Names: Strings 2300 + pilot, 2000 + callsign.
 */
class PltPart(var pilots: MutableList<PilotUsage>, var callsigns: IntArray, var tail: ByteArray, val stop: String?) {
    val editable: Boolean get() = stop == null && tail.isEmpty()
    fun encode(): ByteArray = LeOut(4 + pilots.size * 4 + callsigns.size).also { w ->
        w.i16(pilots.size); pilots.forEach { w.u16(it.usage); w.u8(it.voice); w.u8(it.photo) }
        w.i16(callsigns.size); w.u8s(callsigns)
        w.bytes(tail)
    }.toByteArray()

    companion object {
        fun read(b: ByteArray): PltPart = small(b, { PltPart(ArrayList(), IntArray(0), it, "the pilot part is too short") }) { r ->
            val pilots = MutableList(r.i16().coerceAtLeast(0)) { PilotUsage(r.u16(), r.u8(), r.u8()) }
            val calls = r.u8s(r.i16().coerceAtLeast(0))
            PltPart(pilots, calls, r.bytes(r.left), null)
        }
    }
}

/**
 * A persistent object (`.pst`, 24 bytes): a crater, a wreck… at [x]/[y] (feet, sim axes: x north), the id of what
 * left it (creator, number, index), padding, its visual type and flags.
 */
class PersistentObject(
    var xBits: Int, var yBits: Int, var creator: Long, var num: Long, var index: Int, var pad: ByteArray, var visType: Int, var flags: Int,
) {
    var x: Float get() = fl(xBits); set(v) { xBits = v.bits() }
    var y: Float get() = fl(yBits); set(v) { yBits = v.bits() }
    fun write(w: LeOut) { w.i32(xBits); w.i32(yBits); w.u32(creator); w.u32(num); w.u8(index); w.bytes(pad); w.i16(visType); w.i16(flags) }
    companion object { fun read(r: LeIn) = PersistentObject(r.i32(), r.i32(), r.u32(), r.u32(), r.u8(), r.bytes(3), r.i16(), r.i16()) }
}

/** The persistent objects (`.pst`): an `i32` count, then 24 bytes each. */
class PstPart(var objects: MutableList<PersistentObject>, var tail: ByteArray, val stop: String?) {
    val editable: Boolean get() = stop == null && tail.isEmpty()
    fun encode(): ByteArray = LeOut(4 + objects.size * 24).also { w ->
        w.i32(objects.size); objects.forEach { it.write(w) }; w.bytes(tail)
    }.toByteArray()

    companion object {
        fun read(b: ByteArray): PstPart = small(b, { PstPart(ArrayList(), it, "the persistent object part is too short") }) { r ->
            val n = r.i32()
            if (n < 0 || n.toLong() * 24 > r.left) throw ShortRead("$n objects do not fit in ${r.left} bytes")
            PstPart(MutableList(n) { PersistentObject.read(r) }, r.bytes(r.left), null)
        }
    }
}

/** Reads a small part with [body]; a part that does not walk is kept whole as [broken]'s tail with its reason. */
private inline fun <T> small(b: ByteArray, broken: (ByteArray) -> T, body: (LeIn) -> T): T = try {
    body(LeIn(b))
} catch (e: Throwable) {
    broken(b.copyOf())
}
