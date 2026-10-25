package com.bmscompanion.desktop.bridge.command

/**
 * The archive a `.cam`, `.tac` or `.trn` is: an `i32` at 0 giving where the directory is; the parts back to back
 * from byte 4; the directory (`i32` count, then per part a length-prefixed ANSI name, an `u32` offset and an `u32`
 * length). Read here keeping, for every part, the bytes exactly as stored ([Entry.stored]) and, for the packed parts,
 * what they unpack to ([Entry.unpacked]) — so a part nobody edits is written back byte for byte.
 *
 * The packed parts and their small headers (BMS's own convention for the first field, the bytes after it):
 * - `.cmp`: `i32 packed`, `i32 unpacked`, stream at 8;
 * - `.uni`, `.obd`: `i32 packed`, `i16 records`, `i32 unpacked`, stream at 10;
 * - `.obj`: `i16 records`, `i32 unpacked`, `i32 stream length`, stream at 10 (a header alone when it holds nothing).
 * Every other part is stored as it is. BMS reads the unpacked length and decodes from the bytes present; the first
 * field is not relied on (the shipped starts carry six bytes more than BMS's own saves do).
 *
 * Bytes after the directory (an earlier, longer write left them) are counted in [ReadResult.tail] and never written.
 */
object SaveContainer {

    /** One part. [records] is the count in a packed part's header (0 otherwise). */
    class Entry(
        val name: String,
        val stored: ByteArray,
        val unpacked: ByteArray?,
        val records: Int,
        /** why the part could not be unpacked, when it could not */
        val error: String? = null,
    ) {
        /** "uni", "cmp"… in lower case. */
        val ext: String get() = extOf(name)
        val packed: Boolean get() = isPacked(ext)
        /** What the part holds for a reader: the unpacked bytes of a packed part, else the stored ones. */
        val content: ByteArray get() = unpacked ?: stored
        /** The part's name with another base ("Save1.uni" → "My copy.uni"), keeping its extension's spelling. */
        fun renamed(base: String): String = base + name.substring(name.lastIndexOf('.').let { if (it < 0) name.length else it })
    }

    class ReadResult(
        val entries: List<Entry>,
        val directoryAt: Int,
        val size: Int,
        /** bytes after the directory */
        val tail: Int,
        val error: String?,
    ) {
        fun entry(ext: String): Entry? = entries.firstOrNull { it.ext == ext }
    }

    fun extOf(name: String): String = name.substringAfterLast('.', "").lowercase()
    fun isPacked(ext: String): Boolean = ext == "cmp" || ext == "uni" || ext == "obd" || ext == "obj"

    /** Reads [blob] whole. Never throws: [ReadResult.error] says what is wrong. */
    fun read(blob: ByteArray): ReadResult {
        try {
            if (blob.size < 8) return ReadResult(emptyList(), 0, blob.size, 0, "The file is too short to be a save (${blob.size} bytes).")
            val r = LeIn(blob)
            val dir = r.i32()
            if (dir < 4 || dir.toLong() + 4 > blob.size) return ReadResult(emptyList(), dir, blob.size, 0, "Its directory offset ($dir) lies outside the file (${blob.size} bytes).")
            r.p = dir
            val count = r.i32()
            if (count <= 0 || count > 64) return ReadResult(emptyList(), dir, blob.size, 0, "Its directory lists $count parts.")
            val out = ArrayList<Entry>(count)
            for (i in 0 until count) {
                val len = r.u8()
                val name = String(r.bytes(len), ANSI)
                val at = r.u32()
                val plen = r.u32()
                if (at + plen > blob.size) return ReadResult(out, dir, blob.size, 0, "Part $name ($at + $plen bytes) lies outside the file (${blob.size} bytes).")
                val stored = blob.copyOfRange(at.toInt(), (at + plen).toInt())
                out += unpackEntry(name, stored)
            }
            return ReadResult(out, dir, blob.size, blob.size - r.p, null)
        } catch (e: Throwable) {
            return ReadResult(emptyList(), 0, blob.size, 0, "Its directory could not be read: ${e.message ?: e.javaClass.simpleName}.")
        }
    }

    /** An entry for part [name] stored as [stored], unpacked when it is a packed kind. */
    fun unpackEntry(name: String, stored: ByteArray): Entry {
        val ext = extOf(name)
        if (!isPacked(ext)) return Entry(name, stored, null, 0)
        return try {
            when (ext) {
                "cmp" -> {
                    if (stored.size < 8) return Entry(name, stored, null, 0, "the campaign part is too short")
                    val unc = LeIn(stored, 4).i32()
                    val body = Lzss.decode(stored, 8, unc) ?: return Entry(name, stored, null, 0, "the campaign part could not be unpacked ($unc bytes promised)")
                    Entry(name, stored, body, 0)
                }
                "obj" -> {
                    if (stored.size < 10) return Entry(name, stored, null, 0, "the objective part is too short")
                    val r = LeIn(stored)
                    val n = r.i16()
                    val unc = r.i32()
                    val body = if (unc == 0) ByteArray(0) else Lzss.decode(stored, 10, unc)
                        ?: return Entry(name, stored, null, n, "the objective part could not be unpacked ($unc bytes promised)")
                    Entry(name, stored, body, n)
                }
                else -> { // uni, obd
                    if (stored.size < 10) return Entry(name, stored, null, 0, "the .$ext part is too short")
                    val r = LeIn(stored, 4)
                    val n = r.i16()
                    val unc = r.i32()
                    val body = if (unc == 0) ByteArray(0) else Lzss.decode(stored, 10, unc)
                        ?: return Entry(name, stored, null, n, "the .$ext part could not be unpacked ($unc bytes promised)")
                    Entry(name, stored, body, n)
                }
            }
        } catch (e: Throwable) {
            Entry(name, stored, null, 0, "the .$ext part could not be read: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /**
     * The stored bytes of a packed part of kind [ext] holding [unpacked] ([records] for the counted kinds), packed
     * with [Lzss.encode] and headed BMS's way. An empty `.obd`/`.uni` is its header alone (what the shipped starts
     * carry: `06 00 00 00` then zeros); an empty `.obj` likewise.
     */
    fun pack(ext: String, unpacked: ByteArray, records: Int): ByteArray {
        val stream = if (unpacked.isEmpty()) ByteArray(0) else Lzss.encode(unpacked)
        val w = LeOut(stream.size + 10)
        when (ext) {
            "cmp" -> { w.i32(stream.size + 4); w.i32(unpacked.size) }
            "obj" -> { w.i16(records); w.i32(unpacked.size); w.i32(stream.size) }
            else -> { w.i32(stream.size + 6); w.i16(records); w.i32(unpacked.size) }
        }
        w.bytes(stream)
        return w.toByteArray()
    }

    /** The file: `i32` directory offset, the parts in order from byte 4, the directory. Nothing after it. */
    fun build(parts: List<Pair<String, ByteArray>>): ByteArray {
        val w = LeOut(parts.sumOf { it.second.size } + 64 * parts.size + 8)
        val total = 4 + parts.sumOf { it.second.size }
        w.i32(total)
        val offsets = ArrayList<Int>()
        var at = 4
        for ((_, b) in parts) { offsets += at; w.bytes(b); at += b.size }
        w.i32(parts.size)
        parts.forEachIndexed { i, (name, b) ->
            val nb = name.toByteArray(ANSI)
            w.u8(nb.size)
            w.bytes(nb)
            w.i32(offsets[i])
            w.i32(b.size)
        }
        return w.toByteArray()
    }
}
