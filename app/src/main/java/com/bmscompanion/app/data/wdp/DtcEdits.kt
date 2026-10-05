package com.bmscompanion.app.data.wdp

import com.bmscompanion.app.data.mission.CartridgeEdit

/**
 * What the pilot changed on the DTC page, as the keys of the cartridge it touches — and nothing else.
 *
 * WDP writes the whole of its model back into the cartridge on every save, in its own order and its own format, so
 * a save in WDP reorders keys, rewrites numbers BMS wrote differently and adds sections BMS never had. The app does
 * not: it writes only what the pilot actually edited, so `[LINK16]`, `[MAP_POP]` and every key a later BMS adds
 * survive byte for byte, and a save that changes one steerpoint changes one line.
 *
 * The edits are found without knowing which control touched which key. The same save ([DtcSave]) is run twice over
 * the text as it was loaded: once from a model loaded from that text and never touched, once from the page's model.
 * Whatever the page did not change comes out identical in both — WDP's formatting and all — so the keys that differ
 * are exactly the pilot's edits, in WDP's own spelling of them.
 *
 * Each edit is an absolute value for one key, so applying the same edits twice gives the same file as applying them
 * once; that is what lets the PC apply them to the cartridge as it is on disk at that moment, even if BMS has
 * rewritten it since the page loaded it.
 */
object DtcEdits {

    /** The keys whose value differs between [before] and [after] (a key only in [before] comes back as a removal). */
    fun between(before: String, after: String): List<CartridgeEdit> {
        val a = keys(before)
        val b = keys(after)
        val out = ArrayList<CartridgeEdit>()
        for ((id, e) in b) {
            val old = a[id]
            if (old == null || old.value != e.value) out += e
        }
        for ((id, e) in a) if (id !in b) out += CartridgeEdit(e.section, e.key, null)
        return out
    }

    /** [edits] written into [text] one key at a time, as Windows' profile API would write them ([DtcIni]). */
    fun apply(text: String, edits: List<CartridgeEdit>): String {
        var t = text
        for (e in edits) {
            if (e.section.isBlank() || e.key.isBlank()) continue
            t = if (e.value == null) DtcIni.deleteKey(t, e.section, e.key) else DtcIni.write(t, e.section, e.key, e.value)
        }
        return t
    }

    /**
     * Every key of an INI text, keyed by section and key without regard to case, as [DtcIni] reads them: only the
     * first section of a name counts, and the first key of a name within it; values are trimmed of blanks.
     */
    private fun keys(text: String): LinkedHashMap<String, CartridgeEdit> {
        val out = LinkedHashMap<String, CartridgeEdit>()
        val seenSections = HashSet<String>()
        var section: String? = null
        var counting = false
        for (raw in text.split('\n')) {
            val line = raw.trimEnd('\r')
            val s = line.trim(' ', '\t')
            if (s.startsWith("[")) {
                val name = s.substring(1).substringBefore(']').trim(' ', '\t')
                section = name
                counting = seenSections.add(name.uppercase())
                continue
            }
            if (!counting || section == null || s.startsWith(";")) continue
            val eq = line.indexOf('=')
            if (eq < 0) continue
            val key = line.substring(0, eq).trim(' ', '\t')
            val id = section.uppercase() + "\u0000" + key.uppercase()
            if (id in out) continue
            out[id] = CartridgeEdit(section, key, line.substring(eq + 1).trim(' ', '\t'))
        }
        return out
    }
}
