package com.bmscompanion.desktop.bridge

import com.bmscompanion.app.data.Repo
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * `--tldcheck out.txt <a BMS folder>` — BMS's terrain levelling ([TerrainLevelling]) against the app's field
 * elevations and the PC's ground at a point ([TerrainHeights]). Reads the folder only.
 *
 * 1. **The blend**, on a made-up zone: the level inside the 100 % polygon, a 50 % ring halfway, the height map outside.
 * 2. **Every theater**: its TLD folder and zones; then every field of the app's data (ships left out) whose position a
 *    zone's 100 % polygon holds — the app's elevation against that `terrain_level` and the ATC file's `#INFO` figure,
 *    and the PC's ground at the field's position (`/api/campaign/ground`'s answer) against the level. A FAIL where
 *    the PC's ground there is not the level, or where more than a tenth of a theater's levelled fields carry an
 *    elevation more than 1 ft from their level (a field whose runways reach past its zone can, and is listed).
 */
object TldCheck {
    fun run(bms: File): String = buildString {
        appendLine("Terrain levelling (TerrainRelatedData/TLD_<CampId>.TPB) in ${bms.name}: field elevations and the ground at a point")
        appendLine()
        appendLine("1. The blend")
        val zones = ArrayList<TerrainLevelling.Zone>()
        TerrainLevelling.parse(
            """
            terrain_level_data {
              terrain_level: 100
              terrain_level_polygon { terrain_leveling_percentage: 100
                terrain_level_point { sim_grid_pos_x: 0 sim_grid_pos_y: 0 } terrain_level_point { sim_grid_pos_x: 0 sim_grid_pos_y: 10 }
                terrain_level_point { sim_grid_pos_x: 10 sim_grid_pos_y: 10 } terrain_level_point { sim_grid_pos_x: 10 sim_grid_pos_y: 0 } }
              terrain_level_polygon { terrain_leveling_percentage: 50
                terrain_level_point { sim_grid_pos_x: -10 sim_grid_pos_y: -10 } terrain_level_point { sim_grid_pos_x: -10 sim_grid_pos_y: 20 }
                terrain_level_point { sim_grid_pos_x: 20 sim_grid_pos_y: 20 } terrain_level_point { sim_grid_pos_x: 20 sim_grid_pos_y: -10 } }
            }
            """.trimIndent(), 1, zones,
        )
        val made = TerrainLevelling.Levels(null, zones)
        val blend = listOf(Triple(5.0, 5.0, 100.0), Triple(-5.0, 5.0, 50.0), Triple(-15.0, 5.0, 0.0))
            .map { (n, e, want) -> Triple(n, made.at(n, e, 0.0), want) }
        val ok = zones.size == 1 && blend.all { it.second == it.third }
        appendLine("${if (ok) "OK  " else "FAIL"} level 100 over ground 0: core ${blend[0].second}, 50 % ring ${blend[1].second}, outside ${blend[2].second} (want 100, 50, 0)")
        appendLine()

        appendLine("2. Every theater")
        val set = Theaters.at(bms)
        set.error?.let { appendLine("FAIL $it"); return@buildString }
        val index = runBlocking { Repo.index() }
        val seen = HashSet<String>()
        for (t in set.all) {
            val th = index.theaters.firstOrNull { it.id == t.appId }
            if (th == null) { appendLine("     ${t.name}: not in the app's data"); continue }
            val levels = TerrainLevelling.of(set, t)
            val key = th.airportSet + "|" + levels.dir?.path
            if (!seen.add(key)) { appendLine("     ${t.name}: as above (${th.airportSet}, ${levels.dir?.let { rel(set.data, it) } ?: "no levelling"})"); continue }
            val files = levels.zones.map { it.id }.distinct().size
            appendLine("  ${t.name}: ${levels.dir?.let { rel(set.data, it) } ?: "no TLD folder"}, $files files, ${levels.zones.size} zones")
            if (levels.isEmpty) continue
            val atc = atcElevations(set, t)
            val airports = runBlocking { Repo.airportSet(th.airportSet) }.airports.filter { it.type != "Carrier" && it.runways.isNotEmpty() }
            val held = airports.mapNotNull { a -> levels.zones.firstOrNull { it.rings[0].holds(a.x, a.y) }?.let { a to it } }
            val ground = TerrainHeights.at(set, t, held.map { it.first.x to it.first.y })
            var agree = 0; var atcN = 0; var atcAgree = 0; var pcAgree = 0
            val off = ArrayList<String>(); val atcOff = ArrayList<String>(); val pcOff = ArrayList<String>()
            held.forEachIndexed { i, (a, z) ->
                val lv = Math.round(z.level).toInt()
                if (a.elevationFt != null && kotlin.math.abs(a.elevationFt!! - lv) <= 1) agree++ else off += "${a.name} ${a.elevationFt} (level $lv, TLD_${z.id})"
                atc[a.id]?.takeIf { it != 0 }?.let { f -> atcN++; if (kotlin.math.abs(f - lv) <= 1) atcAgree++ else atcOff += "${a.name} ATC $f, level $lv" }
                val g = ground.heights.getOrNull(i)
                if (g == lv) pcAgree++ else pcOff += "${a.name}: the PC's ground $g, level $lv"
            }
            val tooMany = off.size * 10 > held.size
            appendLine("${if (tooMany || pcOff.isNotEmpty()) "FAIL" else "OK  "}   ${airports.size} fields, ${held.size} on a levelled zone: elevation = level $agree, the PC's ground = level $pcAgree, ATC figure = level $atcAgree of $atcN" +
                (ground.error?.let { " ($it)" } ?: ""))
            off.forEach { appendLine("         elevation off its level: $it") }
            pcOff.forEach { appendLine("    FAIL $it") }
            atcOff.forEach { appendLine("         $it") }
        }
    }

    private fun rel(data: File, f: File) = f.path.removePrefix(data.path).trimStart('\\', '/')

    /** The ATC files' `#INFO` elevations by CampId (`<terraindir>/../ATC`, else `TerrData/ATC`). */
    private fun atcElevations(set: Theaters.TheaterSet, t: Theaters.Theater): Map<Int, Int> {
        val terrain = t.terrainDir?.let { Theaters.resolveDir(set.data, it) }
        val dir = terrain?.parentFile?.let { Theaters.resolveDir(it, "ATC") } ?: Theaters.resolveDir(set.data, "TerrData/ATC") ?: return emptyMap()
        val out = HashMap<Int, Int>()
        for (f in dir.listFiles().orEmpty()) {
            if (!f.name.endsWith(".dat", ignoreCase = true)) continue
            val lines = try { f.readLines(Charsets.ISO_8859_1).map { it.trim() } } catch (_: Exception) { continue }
            val at = lines.indexOfFirst { it.startsWith("#") && it.lowercase().replace("#", "").trim().startsWith("campid") }
            val id = if (at >= 0) lines.drop(at + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") }?.split(Regex("\\s+"))?.firstOrNull()?.toIntOrNull() else null
            val elev = lines.firstNotNullOfOrNull { Regex("^#[*]*INFO\\s+(-?\\d+)", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)?.toIntOrNull() }
            if (id != null && elev != null) out[id] = elev
        }
        return out
    }
}
