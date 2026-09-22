package com.bmscompanion.desktop.bridge

/**
 * The archive Falcon BMS keeps a campaign or a tactical engagement in — a `.cam`, `.tac` or `.trn` file.
 *
 * The file is a small archive: a directory at the end lists the parts by name, and each part holds one kind of
 * campaign data (the units, the objectives, the teams…). Most parts are packed with the compression Falcon has
 * used since 1998 — a 4 KB sliding window, a 12-bit offset and a 4-bit length — and carry their own little header
 * saying how big they were before packing.
 *
 * Only reading is implemented, and nothing here ever writes to the BMS folder.
 */
internal object MissionArchive {

    /** One part of the archive: where it is in the file and how long it is. */
    data class Part(val name: String, val at: Int, val length: Int)

    /** The parts of [blob], in the order the directory lists them. */
    fun parts(blob: ByteArray): List<Part> {
        if (blob.size < 8) return emptyList()
        val dir = int32(blob, 0)
        if (dir < 0 || dir + 4 > blob.size) return emptyList()
        val count = int32(blob, dir)
        if (count <= 0 || count > 4096) return emptyList()
        var p = dir + 4
        val found = ArrayList<Part>(count)
        repeat(count) {
            if (p >= blob.size) return found
            val nameLen = blob[p].toInt() and 0xFF
            p++
            if (p + nameLen + 8 > blob.size) return found
            val name = String(blob, p, nameLen, Charsets.US_ASCII)
            p += nameLen
            val at = int32(blob, p); p += 4
            val len = int32(blob, p); p += 4
            if (at >= 0 && len >= 0 && at + len <= blob.size) found += Part(name, at, len)
        }
        return found
    }

    /**
     * The contents of [part], unpacked.
     *
     * The header in front of the packed bytes differs by what the part holds; the two numbers that matter are the
     * unpacked size and, for the units, how many records it contains. A part that is not packed is returned as it
     * is, which is what happens with the small text parts.
     */
    fun contents(blob: ByteArray, part: Part): Contents? {
        val raw = blob.copyOfRange(part.at, part.at + part.length)
        val kind = part.name.substringAfterLast('.', "").lowercase()
        return when (kind) {
            // the units: packed length, then how many records, then the unpacked length
            "uni", "obd" -> {
                if (raw.size < 10) return null
                val records = int16(raw, 4)
                val unpacked = int32(raw, 6)
                unpack(raw, 10, unpacked)?.let { Contents(it, records) }
            }
            // the campaign itself: packed length, then the unpacked length
            "cmp" -> {
                if (raw.size < 8) return null
                unpack(raw, 8, int32(raw, 4))?.let { Contents(it, 0) }
            }
            else -> Contents(raw, 0)
        }
    }

    /** Unpacked bytes, and for the units how many records they hold. */
    data class Contents(val bytes: ByteArray, val records: Int) {
        override fun equals(other: Any?) = this === other
        override fun hashCode() = System.identityHashCode(this)
    }

    // ---------------------------------------------------------------- the packing

    private const val WINDOW = 4096
    private const val SHORTEST = 1 // a match shorter than this is cheaper written out as bytes

    /**
     * Unpacks [want] bytes starting at [from].
     *
     * A flag byte carries eight decisions, least significant bit first: a one means the next byte is itself, a zero
     * means the next two bytes are a place in the window and a length to copy from it. The window starts empty and
     * fills as the output is produced, which is why it has to be kept rather than reading back over the output.
     */
    private fun unpack(src: ByteArray, from: Int, want: Int): ByteArray? {
        if (want <= 0 || from >= src.size) return null
        val out = ByteArray(want)
        val window = ByteArray(WINDOW)
        var read = from
        var flags = src[read].toInt() and 0xFF
        read++
        var bit = 0
        var written = 0
        var head = 1
        while (written < want) {
            if (bit == 8) {
                if (read >= src.size) return null
                flags = src[read].toInt() and 0xFF
                read++
                bit = 0
            }
            val literal = (flags shr bit) and 1 == 1
            bit++
            if (literal) {
                if (read >= src.size) return null
                val b = src[read]
                read++
                out[written] = b
                written++
                window[head] = b
                head = (head + 1) and (WINDOW - 1)
                continue
            }
            if (read + 1 >= src.size) return null
            val first = src[read].toInt() and 0xFF
            val second = src[read + 1].toInt() and 0xFF
            read += 2
            val at = second or ((first and 0x0F) shl 8)
            val run = (first shr 4) + SHORTEST
            val copy = if (run < want - written) run + 1 else want - written
            for (i in 0 until copy) {
                val b = window[(at + i) and (WINDOW - 1)]
                out[written] = b
                written++
                window[head] = b
                head = (head + 1) and (WINDOW - 1)
            }
        }
        return out
    }

    // ---------------------------------------------------------------- little-endian reads

    fun int16(b: ByteArray, at: Int): Int = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)
    fun int32(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or
            ((b[at + 2].toInt() and 0xFF) shl 16) or ((b[at + 3].toInt() and 0xFF) shl 24)
}
