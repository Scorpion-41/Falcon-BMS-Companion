package com.bmscompanion.desktop.bridge

import java.io.File
import java.util.Random
import java.util.zip.ZipFile
import kotlin.math.abs

/**
 * `--elevframe out.txt <a BMS folder> [recording] [theater name]` — the PC's ground at a point ([TerrainHeights],
 * `/api/campaign/ground`) in BMS's terrain frame ([MapTiles.TerrainFrame]), against the PC's map tiles and against
 * BMS's own ground units. Reads the folder only. (The levelled airbases, where the level wins, are `--tldcheck`'s.)
 *
 * 1. **Against the map tiles**: on each terrain the map draws, 400 points away from every levelled zone, the height
 *    map under the point as the Planner reads it (bilinear in BMS's frame) and as WDP's `ReadNewTerrainElvLoc` read it
 *    (the cell of the grid's square), each against the height the deepest map level draws at the same point (its
 *    Catmull-Rom between the same samples). FAIL when the Planner's median differs by more than 1 ft or its 90th
 *    percentile by more than 5 ft. Then the DataCard's Target Selection (`/api/campaign/objectives`, [CampaignTargets])
 *    on the theater's newest save: every objective must carry the same ground.
 * 2. **Against BMS** (a Tacview recording of the theater named, Korea KTO by default): every ground vehicle's first
 *    position and altitude (`T=lon|lat|alt|…|U|V|…`, U/V metres of 3.28084 ft; infantry left out, recorded at 0.21 m:
 *    the recording's lift over the ground, taken off every altitude), against the two reads. FAIL when the Planner's
 *    read is within 1 ft of fewer than half of them, or its median miss is not a quarter of WDP's or less.
 */
object ElevFrame {
    private const val FT_PER_M = 3.28084
    private const val LIFT_FT = 0.21 * FT_PER_M

    fun run(bms: File, recording: File?, theaterName: String?): String =
        body(bms, recording, theaterName).let { if (it.lines().any { l -> l.startsWith("FAIL") }) it else it + "\nALL PASS\n" }

    private fun body(bms: File, recording: File?, theaterName: String?): String = buildString {
        appendLine("The ground at a point in BMS's terrain frame (${bms.name}): TerrainHeights against the map tiles and BMS's ground units")
        appendLine()
        val set = Theaters.at(bms)
        set.error?.let { appendLine("FAIL $it"); return@buildString }
        MapTiles.rootOverride = bms

        appendLine("1. Against the map tiles (400 points a terrain, away from levelled zones; |difference| in feet: median / p90 / max)")
        for (id in MapTiles.SOURCES.keys) {
            val terrain = MapTiles.terrain(id)
            val hf = terrain?.heightFile
            if (terrain == null || hf == null) { appendLine("     $id: no terrain in this folder"); continue }
            val t = set.all.firstOrNull { th -> TerrainHeights.heightMap(set, th)?.canonicalPath.equals(hf.canonicalPath, ignoreCase = true) }
            if (t == null) { appendLine("     $id: no theater reads ${hf.name} here"); continue }
            val levels = TerrainLevelling.of(set, t)
            val rnd = Random(id.hashCode().toLong())
            val pts = ArrayList<Triple<Double, Double, Float>>()
            var tries = 0
            while (pts.size < 400 && tries++ < 4000) {
                val n = terrain.sizeFt * (0.02 + 0.96 * rnd.nextDouble())
                val e = terrain.sizeFt * (0.02 + 0.96 * rnd.nextDouble())
                if (levels.weight(n, e) != null) continue
                val at = MapTiles.heightAt(terrain, n, e) ?: continue
                if (levels.weight(at.first, at.second) != null) continue
                pts += at
            }
            val at = pts.map { it.first to it.second }
            val ours = TerrainHeights.heightMapAt(set, t, at, wdp = false)
            val wdp = TerrainHeights.heightMapAt(set, t, at, wdp = true)
            if (ours == null || wdp == null) { appendLine("FAIL $id (${t.name}): the height map could not be read"); continue }
            val dOurs = pts.indices.mapNotNull { i -> ours[i]?.let { abs(it - pts[i].third) } }
            val dWdp = pts.indices.mapNotNull { i -> wdp[i]?.let { abs(it - pts[i].third) } }
            val o = Stats(dOurs); val w = Stats(dWdp)
            // the points above 1 ft, where the sea's flat levels cannot hide a misplaced read
            val land = pts.indices.filter { pts[it].third > 1f }
            val oL = Stats(land.mapNotNull { i -> ours[i]?.let { abs(it - pts[i].third) } })
            val wL = Stats(land.mapNotNull { i -> wdp[i]?.let { abs(it - pts[i].third) } })
            val bad = dOurs.size < pts.size || o.median > 1.0 || o.p90 > 5.0
            appendLine("${if (bad) "FAIL" else "OK  "} $id (${t.name}, ${terrain.n} samples, ${pts.size} points): the Planner ${o.line()}, WDP's cell ${w.line()}")
            appendLine("       on land (${land.size}): the Planner ${oL.line()}, WDP's cell ${wL.line()}")
        }
        appendLine()

        val t = set.byName(theaterName ?: "Korea KTO")
        if (t == null) { appendLine("FAIL no theater '${theaterName ?: "Korea KTO"}' in this folder"); return@buildString }

        // the DataCard's Target Selection (`/api/campaign/objectives`) reads the same ground
        set.newestSave(t)?.let { save ->
            val t0 = System.nanoTime()
            val objs = CampaignTargets.objectives(set, t, save).objectives
            val ms = (System.nanoTime() - t0) / 1_000_000
            val want = TerrainHeights.at(set, t, objs.map { it.x to it.y }).heights
            val same = objs.indices.count { objs[it].elevFt == want[it] }
            appendLine("${if (same == objs.size && objs.isNotEmpty()) "OK  " else "FAIL"} the Target Selection's objectives of ${save.name}: $same of ${objs.size} carry the ground at their position ($ms ms)")
            appendLine()
        }

        appendLine("2. Against BMS's ground units")
        if (recording == null) { appendLine("     no recording given"); return@buildString }
        val units = vehicles(recording)
        if (units.isEmpty()) { appendLine("FAIL no ground vehicle in ${recording.name}"); return@buildString }
        val at = units.map { it.second * FT_PER_M to it.first * FT_PER_M }
        val ours = TerrainHeights.heightMapAt(set, t, at, wdp = false)
        val wdp = TerrainHeights.heightMapAt(set, t, at, wdp = true)
        if (ours == null || wdp == null) { appendLine("FAIL ${t.name}: the height map could not be read"); return@buildString }
        val answer = TerrainHeights.at(set, t, at).heights
        fun misses(h: List<Number?>) = units.indices.mapNotNull { i -> h[i]?.let { abs(it.toDouble() - (units[i].third * FT_PER_M - LIFT_FT)) } }
        val o = Stats(misses(ours)); val w = Stats(misses(wdp)); val a = Stats(misses(answer))
        val bad = o.within(1.0) * 2 < units.size || o.median > w.median / 4
        appendLine("${if (bad) "FAIL" else "OK  "} ${units.size} vehicles in ${recording.name} on ${t.name} (|ground - (altitude - 0.21 m)| in feet):")
        appendLine("       the Planner (bilinear, BMS's frame) ${o.line()}, within 0.1 ft ${o.within(0.1)}, within 1 ft ${o.within(1.0)}")
        appendLine("       WDP's cell (the grid's square)     ${w.line()}, within 0.1 ft ${w.within(0.1)}, within 1 ft ${w.within(1.0)}")
        appendLine("       /api/campaign/ground (levelled, whole feet) ${a.line()}, within 1 ft ${a.within(1.0)}")
    }

    private class Stats(d: List<Double>) {
        val s = d.sorted()
        val median = s.getOrElse(s.size / 2) { Double.NaN }
        val p90 = s.getOrElse((s.size * 9) / 10) { Double.NaN }
        val max = s.lastOrNull() ?: Double.NaN
        fun within(ft: Double) = s.count { it <= ft }
        fun line() = "%.2f / %.2f / %.1f".format(median, p90, max)
    }

    /** Each ground vehicle's first (U, V, altitude) in metres, infantry and anything recorded at ground zero left out. */
    private fun vehicles(f: File): List<Triple<Double, Double, Double>> {
        val seen = LinkedHashMap<String, Triple<Double, Double, Double>>()
        val re = Regex("^([0-9a-f]+),T=([^,]*),.*Type=Ground\\+Vehicle,Name=([^,]*)")
        fun line(l: String) {
            val m = re.find(l) ?: return
            val id = m.groupValues[1]
            if (id in seen || "Squad" in m.groupValues[3]) return
            val p = m.groupValues[2].split('|')
            if (p.size < 9) return
            val alt = p[2].toDoubleOrNull() ?: return
            val u = p[6].toDoubleOrNull() ?: return
            val v = p[7].toDoubleOrNull() ?: return
            if (alt > 1.0) seen[id] = Triple(u, v, alt)
        }
        val zipped = f.inputStream().use { val b = ByteArray(2); it.read(b) == 2 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte() }
        if (zipped) ZipFile(f).use { z ->
            val e = z.entries().asSequence().firstOrNull { !it.isDirectory } ?: return emptyList()
            z.getInputStream(e).bufferedReader().useLines { ls -> ls.forEach(::line) }
        } else f.bufferedReader().useLines { ls -> ls.forEach(::line) }
        return seen.values.toList()
    }
}
