package com.bmscompanion.desktop.bridge

import com.bmscompanion.app.data.mission.CampMagVar
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * A theater's magnetic variation, as Falcon BMS ships it (`GET /api/campaign/magvar`, the Planner Map page's VAR).
 *
 * **Where.** `<terraindir>\Weather\MagVarMap_<the terraindir's last folder>.csv`, the terrain folder named by the
 * theater's own definition (`terraindir`), matched without case; else the only `MagVarMap_*.csv` in that folder
 * (Hellas's also holds a stray `MagVarMap_korea.CSV`, which the name rule passes over). A definition without the key
 * (Korea TvT) reads Korea's, `TerrData\Korea`, as BMS does. Every theater of 4.38.1 reaches a file this way: the six
 * Korea 2012 theaters, KTO 80s, LKTO and OFMKTO use Korea's, EF2000 Balkans', Hellas WCP and LHTO Hellas'. WDP looks
 * for `MagVarMap_<its own database name>.csv`, so a theater outside its database has no variation there.
 *
 * **The file.** Lines starting `#` are comments (`#2021`, the model year). The header is `y\x, 0, 16, 32, …`: the
 * columns, in km east. Every row then starts with its own position, km north, and the rows run south to north —
 * Korea's opens with `0, -6.073605, …` (the south-west corner, -6.07°) and ends with `1024, -8.604374, …` (north-west,
 * -8.60°). Each row is placed by its own first value, not by its line number. Negative is west.
 *
 * Read only; cached by path, size and time; nothing throws — a file that cannot be read is a reason, in a sentence.
 */
object MagVarMap {
    /** What [of] answers: the grid, or why there is none. */
    class Found(val value: CampMagVar?, val reason: String?)

    private val cache = ConcurrentHashMap<String, CampMagVar>()

    /** The folder BMS reads a theater's terrain from when its definition names none (Korea TvT). */
    const val DEFAULT_TERRAIN = "TerrData\\Korea"

    /** The variation map of theater [t] of [set]. */
    fun of(set: Theaters.TheaterSet, t: Theaters.Theater): Found {
        val terrain = t.terrainDir?.trim()?.trim('"')?.takeIf { it.isNotEmpty() } ?: DEFAULT_TERRAIN
        val want = "$terrain\\Weather\\MagVarMap_*.csv"
        val file = attempt(null) { find(set, terrain) }
            ?: return Found(null, "Falcon BMS has no magnetic variation map for ${t.name} ($want).")
        val key = "${file.absolutePath}|${attempt(0L) { file.length() }}|${attempt(0L) { file.lastModified() }}"
        cache[key]?.let { return Found(it.copy(theater = t.name), null) }
        val grid = attempt<Pair<CampMagVar?, String?>>(null to "The variation map ${file.name} could not be read.") {
            parse(file.readLines(Charsets.ISO_8859_1))
        }
        val g = grid.first ?: return Found(null, grid.second ?: "The variation map ${file.name} could not be read.")
        val value = g.copy(theater = t.name, file = relative(set.root, file))
        cache[key] = value
        return Found(value, null)
    }

    /** `<terrain>\Weather\MagVarMap_<last folder>.csv`, else the only `MagVarMap_*.csv` there. */
    private fun find(set: Theaters.TheaterSet, terrain: String): File? {
        val dir = Theaters.resolveDir(set.data, "$terrain\\Weather") ?: return null
        val all = dir.listFiles()?.filter { it.isFile && it.name.startsWith("MagVarMap_", ignoreCase = true) && it.name.endsWith(".csv", ignoreCase = true) }.orEmpty()
        val last = terrain.replace('/', '\\').trimEnd('\\').substringAfterLast('\\')
        return all.firstOrNull { it.name.equals("MagVarMap_$last.csv", ignoreCase = true) } ?: all.singleOrNull()
    }

    /**
     * The grid in [lines], or null with the reason. Columns come from the header's cells after the first; a row whose
     * cell count differs from the header's is refused rather than guessed.
     */
    fun parse(lines: List<String>): Pair<CampMagVar?, String?> {
        var xs: List<Double>? = null
        val rows = ArrayList<Pair<Double, List<Float>>>()
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val cells = line.split(',').map { it.trim() }.let { c -> if (c.isNotEmpty() && c.last().isEmpty()) c.dropLast(1) else c }
            if (cells.size < 2) continue
            if (xs == null) {
                // the header: "y\x, 0, 16, …"
                val head = cells.drop(1).map { it.toDoubleOrNull() }
                if (head.any { it == null }) return null to "The variation map's header is not a list of positions."
                xs = head.map { it!! }
                continue
            }
            val y = cells[0].toDoubleOrNull() ?: return null to "A row of the variation map does not start with its position (\"${cells[0]}\")."
            val v = cells.drop(1).map { it.toFloatOrNull() }
            if (v.size != xs.size || v.any { it == null }) return null to "The row at $y km of the variation map has ${v.size} values where the header has ${xs.size}."
            rows += y to v.map { it!! }
        }
        val x = xs ?: return null to "The variation map has no header."
        if (rows.isEmpty()) return null to "The variation map has no rows."
        if (x.zipWithNext().any { (a, b) -> b <= a }) return null to "The variation map's columns do not run west to east."
        val sorted = rows.sortedBy { it.first }
        if (sorted.zipWithNext().any { (a, b) -> b.first <= a.first }) return null to "The variation map has two rows at the same position."
        return CampMagVar(xKm = x, yKm = sorted.map { it.first }, deg = sorted.flatMap { it.second }) to null
    }

    private fun relative(root: File, f: File): String = attempt(f.path) {
        val r = root.canonicalFile.toPath()
        val p = f.canonicalFile.toPath()
        if (p.startsWith(r)) r.relativize(p).toString() else f.path
    }

    private inline fun <T> attempt(fallback: T, body: () -> T): T = try { body() } catch (_: Throwable) { fallback }
}
