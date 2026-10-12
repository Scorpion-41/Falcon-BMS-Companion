package com.bmscompanion.desktop.bridge

import java.io.File

/**
 * Falcon BMS's terrain levelling: the ground it flattens under airbases and other objectives at run time, on top of
 * `NewTerrain/HeightMaps/HeightMap.raw`.
 *
 * The sim reads `NewTerrain/HeightMaps/TerrainLevelingData.bin`; each objective that levels has a readable source of
 * it, `<objectdir>/TerrainRelatedData/TLD_<the objective's CampId, five digits>.TPB` — protocol buffers in text form:
 * one or more `terrain_level_data` zones, each a `terrain_level` (feet) and polygons of `terrain_level_point`s
 * (`sim_grid_pos_x` = north, `sim_grid_pos_y` = east, absolute theater feet), the first at
 * `terrain_leveling_percentage` 100 and rings round it at lower percentages (66.7/33.3, 75/50/25, 80/60/40/20, 50).
 * Inside a zone's 100 % polygon the ground is its level — where an airbase's ATC file gives an elevation it is that
 * figure (Osan 42, Daegu 120, Gunsan 22). In a ring of p % the ground is taken as p % of the level and the rest the
 * height map's: BMS's own blend there is not documented, so this is a linear mix.
 *
 * Why it matters: the height map is not always levelled itself. Hellas's carries its airbases at a wrong height (Graf
 * Ignatievo 190 ft inside the field, 650-680 ft round it; its level is 679), and where it is levelled it can be a few
 * cells off the runway (Pohang: the ATC file says 16, the level 75, the published 70).
 *
 * Read only; parsed once per folder and kept ([cache]); nothing throws (a file that does not read adds no zone).
 */
object TerrainLevelling {
    class Ring(val pct: Double, val north: DoubleArray, val east: DoubleArray) {
        val n0 = north.min(); val n1 = north.max(); val e0 = east.min(); val e1 = east.max()

        /** Even-odd: whether (n, e) lies inside. */
        fun holds(n: Double, e: Double): Boolean {
            if (n < n0 || n > n1 || e < e0 || e > e1) return false
            var c = false
            var j = north.size - 1
            for (i in north.indices) {
                if ((east[i] > e) != (east[j] > e) && n < (north[j] - north[i]) * (e - east[i]) / (east[j] - east[i]) + north[i]) c = !c
                j = i
            }
            return c
        }
    }

    /** One levelled zone: [rings] innermost (100 %) first; [id] the CampId its file names. */
    class Zone(val id: Int, val level: Double, val rings: List<Ring>) {
        val outer get() = rings.last()
    }

    class Levels(val dir: File?, val zones: List<Zone>) {
        /** The innermost ring holding (n, e) among every zone, with its zone, or null outside them all. */
        fun weight(n: Double, e: Double): Pair<Zone, Double>? {
            var best: Pair<Zone, Double>? = null
            for (z in zones) {
                if (!z.outer.holds(n, e)) continue
                val r = z.rings.firstOrNull { it.holds(n, e) } ?: continue
                if (best == null || r.pct > best.second) best = z to r.pct
                if (r.pct >= 99.9) break
            }
            return best
        }

        /** The zone whose 100 % polygon holds (n, e), or null. */
        fun core(n: Double, e: Double): Zone? = zones.firstOrNull { it.rings[0].holds(n, e) }

        /** The levelled ground at (n, e), given the height map's [ground] there (null: the level alone, else null). */
        fun at(n: Double, e: Double, ground: Double?): Double? {
            val (z, pct) = weight(n, e) ?: return ground
            if (pct >= 99.9 || ground == null) return z.level
            val p = pct / 100.0
            return z.level * p + ground * (1 - p)
        }

        /** The zones whose outer ring meets the box [n0, n1] × [e0, e1] (a map tile's), as their own [Levels]. */
        fun within(n0: Double, n1: Double, e0: Double, e1: Double): Levels =
            Levels(dir, zones.filter { val o = it.outer; o.n1 >= n0 && o.n0 <= n1 && o.e1 >= e0 && o.e0 <= e1 })

        val isEmpty get() = zones.isEmpty()
    }

    private val NONE = Levels(null, emptyList())
    private val cache = HashMap<String, Levels>()

    /**
     * The levelling of theater [t]: the TLD files of its `objectdir`, else its `3ddatadir`, else — a theater flying the
     * base Korea terrain whose own folder has none (OFM KTO) — the base `TerrData/Objects`.
     */
    fun of(set: Theaters.TheaterSet, t: Theaters.Theater): Levels {
        val own = listOfNotNull(set.objectDir(t), set.threeDDataDir(t)).firstNotNullOfOrNull { tldDir(it) }
        if (own != null) return load(own)
        val korea = Theaters.resolveDir(set.data, "TerrData/Korea")
        val terrain = t.terrainDir?.let { Theaters.resolveDir(set.data, it) }
        val onBase = terrain == null || (korea != null && terrain.canonicalPath.equals(korea.canonicalPath, ignoreCase = true))
        val base = if (onBase) Theaters.resolveDir(set.data, "TerrData/Objects")?.let { tldDir(it) } else null
        return base?.let { load(it) } ?: NONE
    }

    /** The levelling of a terrain folder's own theater (the map tiles: [objects] is that theater's `objectdir`). */
    fun ofObjects(objects: File?): Levels = objects?.let { tldDir(it) }?.let { load(it) } ?: NONE

    private fun tldDir(objects: File): File? = Theaters.resolveDir(objects, "TerrainRelatedData")
        ?.takeIf { d -> d.list()?.any { it.startsWith("TLD_", ignoreCase = true) && it.endsWith(".TPB", ignoreCase = true) } == true }

    private fun load(dir: File): Levels {
        val key = dir.path.lowercase() + "|" + dir.lastModified()
        synchronized(cache) { cache[key]?.let { return it } }
        val zones = ArrayList<Zone>()
        for (f in dir.listFiles().orEmpty().sortedBy { it.name }) {
            val m = TLD_NAME.matchEntire(f.name) ?: continue
            val text = try { f.readText(Charsets.ISO_8859_1) } catch (_: Exception) { continue }
            parse(text, m.groupValues[1].toInt(), zones)
        }
        val out = Levels(dir, zones)
        synchronized(cache) { cache[key] = out }
        return out
    }

    private val TLD_NAME = Regex("TLD_(-?\\d+)\\.TPB", RegexOption.IGNORE_CASE)
    private const val NUM = "(-?[\\d.]+(?:[eE][-+]?\\d+)?)"
    private val LEVEL = Regex("terrain_level:\\s*$NUM")
    private val PCT = Regex("terrain_leveling_percentage:\\s*$NUM")
    private val POINT = Regex("sim_grid_pos_x:\\s*$NUM\\s*sim_grid_pos_y:\\s*$NUM")

    /** One TLD file's [text] → its zones, added to [into]. */
    fun parse(text: String, id: Int, into: MutableList<Zone>) {
        for (block in text.split("terrain_level_data").drop(1)) {
            val level = LEVEL.find(block)?.groupValues?.get(1)?.toDoubleOrNull() ?: continue
            val rings = ArrayList<Ring>()
            for (poly in block.split("terrain_level_polygon").drop(1)) {
                val pct = PCT.find(poly)?.groupValues?.get(1)?.toDoubleOrNull() ?: 100.0
                val pts = POINT.findAll(poly).mapNotNull { p ->
                    val n = p.groupValues[1].toDoubleOrNull(); val e = p.groupValues[2].toDoubleOrNull()
                    if (n == null || e == null || !n.isFinite() || !e.isFinite()) null else n to e
                }.toList()
                if (pts.size >= 3) rings += Ring(pct, DoubleArray(pts.size) { pts[it].first }, DoubleArray(pts.size) { pts[it].second })
            }
            rings.sortByDescending { it.pct }
            if (rings.isNotEmpty() && rings[0].pct >= 99.9 && level.isFinite()) into += Zone(id, level, rings)
        }
    }
}
