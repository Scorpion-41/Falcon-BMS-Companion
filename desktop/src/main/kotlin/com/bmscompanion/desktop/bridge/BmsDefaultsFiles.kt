package com.bmscompanion.desktop.bridge

import com.bmscompanion.app.data.BmsDefaults
import com.bmscompanion.app.data.BmsDefaultsFile
import com.bmscompanion.app.data.BmsDefaultsValue
import java.io.File

/**
 * BMS's own defaults for the data cartridge, the `*_Def.ini` files in `User/Config` (EWS, HARM, IFF and MFD in
 * 4.38.1), for the Planner's "Default" buttons (`GET /api/cfg/defaults`). They are the defaults BMS ships for those
 * DTC pages, so they replace the values Weapon Delivery Planner carried in its own code (its MFD default wrote
 * `Display0-0-0=0` where BMS's file says 4).
 *
 * **Read only.** These files belong to BMS: nothing here opens one for writing, and nothing in the Planner writes
 * them either (its saves go to the pilot's `<callsign>.ini`, through [CartridgeStore]). Every `*_Def.ini` in the
 * folder is read rather than a fixed list, so a file a later BMS adds reaches the page with no code change.
 *
 * Like [BmsConfig], nothing in here throws: a file that cannot be read is named in [BmsDefaults.error] and the
 * others are still answered.
 */
object BmsDefaultsFiles {

    private val SECTION = Regex("""^\s*\[([^\]]+)]\s*$""")

    /** Reads every `*_Def.ini` in [configDir] (BMS's `User/Config`); `available` is false when there is no such folder. */
    fun read(configDir: File?): BmsDefaults {
        if (configDir == null || !configDir.isDirectory) return BmsDefaults(available = false)
        val errors = ArrayList<String>()
        val files = (runCatching { configDir.listFiles() }.getOrNull() ?: emptyArray())
            .filter { it.isFile && it.name.endsWith("_Def.ini", ignoreCase = true) }
            .sortedBy { it.name.lowercase() }
            .mapNotNull { f ->
                runCatching { parse(f.name, f.readText(Charsets.ISO_8859_1)) }
                    .onFailure { errors += "${f.name} could not be read: ${it.message ?: it::class.simpleName}" }
                    .getOrNull()
            }
        if (files.isEmpty() && errors.isEmpty()) errors += "There is no *_Def.ini in ${configDir.name}."
        return BmsDefaults(available = true, files = files, error = errors.takeIf { it.isNotEmpty() }?.joinToString(" "))
    }

    /**
     * An INI as BMS writes these: `[Section]` lines, then `key=value` split at the first "=" (keys hold spaces:
     * "PGM 0 Chaff BQ", "THREAT 0 0"). Values are kept as text — "0202" and "00" are codes whose leading zeros
     * matter — and blank lines and `;`/`#` comments are skipped.
     */
    fun parse(name: String, text: String): BmsDefaultsFile {
        val sections = ArrayList<String>()
        val values = ArrayList<BmsDefaultsValue>()
        var section = ""
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith(";") || line.startsWith("#")) continue
            val s = SECTION.matchEntire(line)
            if (s != null) {
                section = s.groupValues[1].trim()
                if (section !in sections) sections += section
                continue
            }
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            values += BmsDefaultsValue(section, line.substring(0, eq).trim(), line.substring(eq + 1).trim())
        }
        return BmsDefaultsFile(name, sections, values)
    }
}
