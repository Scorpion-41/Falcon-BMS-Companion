package com.bmscompanion.desktop.bridge

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * One Falcon BMS 3-D model (`Models/<id>/Model_<lod>.bml`), read as the sim reads it.
 *
 * The layout was read out of the sim itself (`Falcon BMS.exe` with its pdb: `bp::RTModel::loadFromFile`,
 * `RTModel::readNodeFromFile` and each node's `readFromBuffer`); `tools/extractor/bml-format.md` has the whole of it.
 *
 *   file          "BML\0", u32 version, u32 compression (2 = LZMA), u64 size, u64 packed size, the LZMA properties
 *                 (5 bytes) at 0x1C and the stream from 0x21
 *   unpacked      u32; version 1: u32 n + n texture numbers (`KoreaObj/<n>.dds`), version 2: u32 n + n material names
 *                 (u32 length + bytes); then u32 index format, u32, u32 vertex count, u32 node count, the nodes, u32 +
 *                 the index buffer, u32 + the vertex buffer
 *   node          u32 type, u32 id, u32 version, then by type: 1 primitive, 2 DOF, 3 DOF end, 4 switch, 5 switch end,
 *                 6 slot, 7 slot end, 8 render control
 *
 * The nodes are a tree written in order: a DOF, a switch or a slot opens a group that its end node closes. A DOF and a
 * slot carry a transform (scale, then a 3 x 3 rotation applied to the row vector, then a translation) that every
 * vertex inside it goes through, the innermost first; a switch shows one of its branches. A primitive is a run of the
 * vertex buffer (36-byte records: position, normal, colour, texture coordinates) drawn as a triangle list (topology 4),
 * a strip (5), lines or points. The model's axes are x to starboard, y up and z forward (a left-handed frame: the
 * Nimitz's island stands at +x, a T-72's gun points to +z). Nothing here throws: [read] answers null with [lastError]
 * saying why.
 */
class BmlModel(
    val textures: List<Int>,
    val nodes: List<Node>,
    private val vb: ByteBuffer,
) {
    sealed class Node(val id: Int)
    class Primitive(id: Int, val topology: Int, val vbOffset: Int, val count: Int, val stride: Int) : Node(id)
    /** [type] 0 rotates, 1 slides, 2 scales (`bp::RTModelDofNode::DofType`). */
    class Dof(id: Int, val dof: Int, val type: Int, val scale: FloatArray, val rot: FloatArray, val move: FloatArray) : Node(id)
    class Slot(id: Int, val slot: Int, val rot: FloatArray, val move: FloatArray) : Node(id)
    class Switch(id: Int, val switch: Int, val branch: Int, val inverted: Boolean) : Node(id)
    class End(id: Int, val type: Int) : Node(id)
    class Control(id: Int) : Node(id)

    /** A transform of the tree: scale, the row-vector rotation (row-major 3 x 3), then the move. */
    class Xf(val scale: FloatArray, val rot: FloatArray, val move: FloatArray)

    /**
     * Every triangle of the model as the sim shows it at rest, by the sim's own rules (`RTModel::draw`):
     * - a DOF at value 0: a rotating one (type 0) is its stored rotation and move, a sliding one (1) moves nothing (its
     *   "move" is the axis it slides along), a scaling one (2) is its move alone;
     * - a switch shows a branch when that bit of the switch's value is set, or for an inverted switch (its flag) when
     *   it is not. Each switch is given the bit of its lowest ordinary branch (the first of an SA-6's six track
     *   frames; a ZPU-2's deployed gun rather than the inverted alternative drawn on top of it; a missile's plume, an
     *   inverted switch alone, stays off);
     * - on each slot whose number is in [slots], that model's triangles (a launcher's missiles), at the slot.
     * Nine floats a triangle, model feet, appended to [out]; [outer] is the transform this whole model sits in.
     */
    fun triangles(out: FloatList, slots: Map<Int, BmlModel> = emptyMap(), outer: List<Xf> = emptyList()) {
        val lowest = HashMap<Int, Int>()
        for (n in nodes) if (n is Switch && !n.inverted) lowest[n.switch] = minOf(lowest[n.switch] ?: Int.MAX_VALUE, n.branch)
        fun shows(s: Switch): Boolean {
            val bit = lowest[s.switch] ?: 0
            return if (s.inverted) s.branch != bit else s.branch == bit
        }
        class Frame(val xf: List<Xf>, val shown: Boolean)
        val stack = ArrayList<Frame>()
        stack += Frame(outer, true)
        val v = FloatArray(3)
        for (n in nodes) {
            val top = stack.last()
            when (n) {
                is Dof -> stack += when (n.type) {
                    0 -> Frame(listOf(Xf(ONE, n.rot, n.move)) + top.xf, top.shown)
                    2 -> Frame(listOf(Xf(ONE, IDENTITY, n.move)) + top.xf, top.shown)
                    else -> Frame(top.xf, top.shown)
                }
                is Slot -> {
                    val xf = listOf(Xf(ONE, n.rot, n.move)) + top.xf
                    stack += Frame(xf, top.shown)
                    if (top.shown) slots[n.slot]?.triangles(out, emptyMap(), xf)
                }
                is Switch -> stack += Frame(top.xf, top.shown && shows(n))
                is End -> if (stack.size > 1) stack.removeAt(stack.size - 1)
                is Control -> Unit
                is Primitive -> {
                    if (!top.shown || n.stride < 12 || n.count < 3) continue
                    if (n.vbOffset < 0 || n.vbOffset.toLong() + n.count.toLong() * n.stride > vb.capacity()) continue
                    fun vertex(k: Int) {
                        val o = n.vbOffset + k * n.stride
                        v[0] = vb.getFloat(o); v[1] = vb.getFloat(o + 4); v[2] = vb.getFloat(o + 8)
                        apply(top.xf, v)
                        out.add(v[0]); out.add(v[1]); out.add(v[2])
                    }
                    when (n.topology) {
                        LIST -> { var k = 0; while (k + 2 < n.count) { vertex(k); vertex(k + 1); vertex(k + 2); k += 3 } }
                        STRIP -> for (k in 0 until n.count - 2) {
                            if (k % 2 == 0) { vertex(k); vertex(k + 1); vertex(k + 2) } else { vertex(k + 1); vertex(k); vertex(k + 2) }
                        }
                        else -> Unit // lines and points: lamps, aerials, nothing with an area
                    }
                }
            }
        }
    }

    private fun apply(xf: List<Xf>, v: FloatArray) {
        var x = v[0]; var y = v[1]; var z = v[2]
        for (t in xf) {
            val xs = x * t.scale[0]; val ys = y * t.scale[1]; val zs = z * t.scale[2]
            val r = t.rot
            val nx = xs * r[0] + ys * r[3] + zs * r[6] + t.move[0]
            val ny = xs * r[1] + ys * r[4] + zs * r[7] + t.move[1]
            val nz = xs * r[2] + ys * r[5] + zs * r[8] + t.move[2]
            x = nx; y = ny; z = nz
        }
        v[0] = x; v[1] = y; v[2] = z
    }

    /** A growable float array (a model is a few thousand triangles; a carrier ninety thousand). */
    class FloatList(capacity: Int = 1 shl 14) {
        var data = FloatArray(capacity)
        var size = 0
        fun add(f: Float) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = f
        }
    }

    companion object {
        const val LIST = 4
        const val STRIP = 5
        private val ONE = floatArrayOf(1f, 1f, 1f)
        private val IDENTITY = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

        /** Why the last [read] answered null (a developer check prints it). */
        @Volatile var lastError: String? = null
            private set

        /** The model in [file], or null (see [lastError]). */
        fun read(file: File): BmlModel? {
            val raw = try { file.readBytes() } catch (e: Throwable) { lastError = "cannot read ${file.name}: ${e.message}"; return null }
            return parse(raw, file.name)
        }

        fun parse(raw: ByteArray, name: String = "model"): BmlModel? {
            try {
                if (raw.size < 0x22 || raw[0] != 'B'.code.toByte() || raw[1] != 'M'.code.toByte() || raw[2] != 'L'.code.toByte()) {
                    lastError = "$name is not a BML file"; return null
                }
                val h = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
                val version = h.getInt(4)
                val compression = h.getInt(8)
                val size = h.getLong(12)
                if (size <= 0 || size > 256L shl 20) { lastError = "$name states a size of $size"; return null }
                val bytes = when (compression) {
                    2 -> Lzma.decode(raw.copyOfRange(0x1C, 0x21), raw, 0x21, size.toInt())
                    0 -> raw.copyOfRange(0x1C, minOf(raw.size, 0x1C + size.toInt()))
                    else -> null
                } ?: run { lastError = "$name: compression $compression could not be unpacked"; return null }
                return nodes(bytes, version, name)
            } catch (e: Throwable) {
                lastError = "$name: ${e.javaClass.simpleName} ${e.message}"
                return null
            }
        }

        private fun nodes(b: ByteArray, version: Int, name: String): BmlModel? {
            val r = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
            fun u(): Int = r.getInt()
            fun f(): Float = r.getFloat()
            fun floats(n: Int) = FloatArray(n) { f() }
            u() // unknown
            val textures = ArrayList<Int>()
            when (version) {
                1 -> { val n = u(); require(n in 0..4096); repeat(n) { textures += u() } }
                2 -> { val n = u(); require(n in 0..4096); repeat(n) { val len = u(); require(len in 0..0xFFFF); r.position(r.position() + len) } }
                else -> { lastError = "$name: model version $version"; return null }
            }
            u() // index format
            u()
            u() // vertex count
            val count = u()
            if (count !in 0..100_000) { lastError = "$name: $count nodes"; return null }
            val nodes = ArrayList<Node>(count)
            repeat(count) {
                val type = u(); val id = u(); val v = u()
                nodes += when (type) {
                    1 -> {
                        val topology = u(); u(); u(); u(); u()
                        val vbOffset = u(); val n = u(); val stride = u()
                        r.position(r.position() + 12 + 2) // centre, two flags
                        if (v == 1) r.position(r.position() + 20) else r.position(r.position() + 2)
                        Primitive(id, topology, vbOffset, n, stride)
                    }
                    2 -> {
                        // number, type, min, max, multiplier, flags, scale, move, rotation
                        val dof = u(); val type = u(); f(); f(); f(); u()
                        val scale = floats(3); val move = floats(3); val rot = floats(9)
                        Dof(id, dof, type, scale, rot, move)
                    }
                    4 -> { val sw = u(); val branch = u(); val inverted = r.get().toInt() != 0; Switch(id, sw, branch, inverted) }
                    6 -> { val slot = u(); val rot = floats(9); val move = floats(3); Slot(id, slot, rot, move) }
                    8 -> { r.position(r.position() + 36); Control(id) }
                    3, 5, 7 -> End(id, type)
                    else -> { lastError = "$name: node type $type at ${r.position() - 12}"; return null }
                }
            }
            val ib = u()
            require(ib in 0..r.remaining())
            r.position(r.position() + ib)
            val vbBytes = u()
            require(vbBytes in 0..r.remaining())
            val vb = ByteBuffer.wrap(b, r.position(), vbBytes).slice().order(ByteOrder.LITTLE_ENDIAN)
            return BmlModel(textures, nodes, vb)
        }
    }
}
