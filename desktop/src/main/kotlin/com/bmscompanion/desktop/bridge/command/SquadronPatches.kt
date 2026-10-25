package com.bmscompanion.desktop.bridge.command

import com.bmscompanion.desktop.bridge.Theaters
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Falcon BMS's squadron patches, read at run time for the Command section's tables and the squadron inspector (read
 * only, nothing kept on disk). A squadron's patch number (its record's `squadron_patch`, the squadron list's patch) is
 * a place in BMS's patch list `Art\Patches\ImageIds.id` — one `NAME<TAB>id` line a patch, `#` lines and lines without
 * a number not counted, so "1ST_BMS 70001" is patch 0 — and the picture of that name is in the resource bundle
 * `Art\Resource\Patches.idx/.rsc` (96 x 96, RGB555, BMS's magenta transparent). Both are looked for under the theater's
 * `artdir` first, else BMS's own `Data\Art`. Never throws: a patch that cannot be found is null.
 */
object SquadronPatches {
    class Patch(val name: String, val w: Int, val h: Int, val argb: IntArray)

    class PatchSet(set: Theaters.TheaterSet, t: Theaters.Theater?) {
        private val artRoots: List<File> = buildList {
            t?.artDir?.trim()?.trim('"', '\\', '/')?.takeIf { it.isNotEmpty() }?.let { a ->
                Theaters.resolveDir(set.data, "$a\\Art")?.let(::add)
                Theaters.resolveDir(set.data, a)?.let(::add)
            }
            Theaters.resolveDir(set.data, "Art")?.let(::add)
        }.distinct()

        /** The patch names in BMS's order (index = patch number). */
        val names: List<String> by lazy {
            val f = artRoots.firstNotNullOfOrNull { Theaters.resolveFile(it, "Patches\\ImageIds.id") } ?: return@lazy emptyList()
            runCatching {
                f.readLines(Charsets.ISO_8859_1).mapNotNull { line ->
                    val s = line.replace('\t', ' ').trim()
                    if (s.isEmpty() || s.startsWith("#")) return@mapNotNull null
                    val sp = s.indexOf(' ')
                    if (sp <= 0) return@mapNotNull null
                    if (s.substring(sp + 1).trim().toIntOrNull() == null) null else s.substring(0, sp)
                }
            }.getOrDefault(emptyList())
        }

        private val bundle: ResourceBundle.Bundle? by lazy {
            artRoots.firstNotNullOfOrNull { r -> Theaters.resolveDir(r, "Resource")?.listFiles()?.firstOrNull { it.isFile && it.name.equals("Patches.idx", true) } }
                ?.let { ResourceBundle.read(it) }
        }

        private val cache = ConcurrentHashMap<Int, Any>()

        /** Patch [n]'s picture; null when the list or the bundle has none. */
        fun patch(n: Int): Patch? {
            if (n < 0) return null
            val got = cache.getOrPut(n) {
                val name = names.getOrNull(n) ?: return@getOrPut Unit
                val b = bundle ?: return@getOrPut Unit
                val img = b[name] ?: return@getOrPut Unit
                ResourceBundle.smallImage(b, img, maxPixels = 512 * 512)?.let { Patch(name, img.width, img.height, it) } ?: Unit
            }
            return got as? Patch
        }

        /** Patch [n]'s name ("KTO_CAMP_36TH_FS"), null when the list has none. */
        fun name(n: Int): String? = names.getOrNull(n)
    }

    private val sets = ConcurrentHashMap<String, PatchSet>()

    /** Theater [t]'s patches (made once per theater and folder). */
    fun forTheater(set: Theaters.TheaterSet, t: Theaters.Theater?): PatchSet =
        sets.getOrPut("${set.root.absolutePath}|${t?.appId}") { PatchSet(set, t) }

    /** The patches of the theater [doc] belongs to; null when its BMS folder is unknown. */
    fun of(doc: CommandSave): PatchSet? = doc.theaters?.let { forTheater(it, doc.theater) }

    fun forget() = sets.clear()
}

/**
 * A flight's radios as the Units table shows them: its callsign's row of the theater's `RadioMap.dat` (beside the
 * save, the campaign folder's), "Cowboy1, 365025, 54525, 279250" = callsign word and number, UHF, VHF, backup UHF in
 * kHz; shown in MHz ("365.025"), NONE as nothing. Read once per file and time; never throws.
 */
object FlightRadios {
    class Radio(val uhf: String, val vhf: String)

    private class Loaded(val modified: Long, val map: Map<String, Radio>)

    private val maps = ConcurrentHashMap<String, Loaded>()

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    private fun mhz(s: String?): String {
        val v = s?.trim()?.toLongOrNull() ?: return ""
        if (v <= 0) return ""
        return "${v / 1000}.${(v % 1000).toString().padStart(3, '0')}"
    }

    private fun mapFor(dir: File?): Map<String, Radio> {
        val f = dir?.listFiles()?.firstOrNull { it.isFile && it.name.equals("RadioMap.dat", true) } ?: return emptyMap()
        val key = f.absolutePath
        val t = f.lastModified()
        maps[key]?.takeIf { it.modified == t }?.let { return it.map }
        val m = runCatching {
            f.readLines(Charsets.ISO_8859_1).mapNotNull { line ->
                val s = line.substringBefore("//").trim()
                if (s.isEmpty()) return@mapNotNull null
                val p = s.split(',')
                if (p.size < 3) null else norm(p[0]) to Radio(mhz(p[1]), mhz(p[2]))
            }.toMap()
        }.getOrDefault(emptyMap())
        maps[key] = Loaded(t, m)
        return m
    }

    /** Flight [f]'s radios, null when the theater's RadioMap has no row for its callsign. */
    fun of(doc: CommandSave, f: CmdFlight): Radio? = runCatching {
        mapFor(doc.file.absoluteFile.parentFile)[norm(UnitNames.callsign(doc, f))]
    }.getOrNull()
}
