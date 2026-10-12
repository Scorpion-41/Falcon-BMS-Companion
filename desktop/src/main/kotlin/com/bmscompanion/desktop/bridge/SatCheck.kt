package com.bmscompanion.desktop.bridge

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import kotlin.math.floor

/**
 * `--satcheck out.txt <a BMS folder>` — every terrain's Satellite tiles drawn the way `/api/maptile` draws them, in
 * this process's heap (run it with the packaged program's 512 MB to see what a pilot's PC does). Reads the folder only.
 *
 * Per terrain: its size and height map, its GlobalColorMap (size, mip levels, format: the Falklands' is 16,384 px in
 * one level, which 1.4.0's first builds decoded whole for the auto levels — a gigabyte, so every Falklands satellite
 * tile failed with "Java heap space" in 512 MB), how many 4K and 16K cells it has and what they are named, then
 * tiles: the whole theater at level 0 (the GlobalColorMap read at a stride), and at levels 5, 7 and 10 the tile over
 * a field of that terrain, with the time each took and the share of pixels no layer painted. A tile that cannot be
 * drawn is a FAIL.
 */
object SatCheck {
    /** a field a terrain's tiles are drawn over: north, east (theater feet) */
    private val FIELDS = mapOf(
        "korea" to Triple("Osan AB", 1163530.0, 1540725.0),
        "balkans" to Triple("Batajnica Airbase", 2830990.0, 2674400.0),
        "hellas" to Triple("Larissa Airbase", 2293126.0, 962235.0),
        "israel" to Triple("Haifa Airport", 1974965.0, 1692949.0),
        "falklands" to Triple("RAF Mount Pleasant", 1591446.0, 4831709.0),
    )
    private const val NO_DATA = 0xFF12202E.toInt()

    fun run(bms: File): String = buildString {
        MapTiles.rootOverride = bms
        appendLine("Satellite tiles from ${bms.path} (read only), heap ${Runtime.getRuntime().maxMemory() / (1024 * 1024)} MB")
        var fails = 0
        for (id in MapTiles.SOURCES.keys) {
            val t = MapTiles.terrain(id)
            if (t == null) { appendLine("$id: no terrain"); continue }
            appendLine("== $id: ${t.sizeKm.toInt()} km, height map ${t.n} samples a side (${"%.1f".format(t.sizeKm * 1000 / t.n)} m)")
            val g = t.photo?.listFiles()?.firstOrNull { it.name.equals("GlobalColorMap.dds", true) }
            appendLine("  GlobalColorMap: ${g?.let(::dds) ?: "none"}")
            val l0 = System.nanoTime()
            val lv = MapTiles.levels(t)
            appendLine("  auto levels (low, scale × 1000): ${lv[0]}, ${lv[1]} in ${(System.nanoTime() - l0) / 1_000_000} ms")
            for (sub in listOf("4K", "16K")) {
                val names = t.photo?.let { Theaters.resolveDir(it, sub) }?.list()?.filter { it.endsWith(".dds", true) }.orEmpty()
                val nums = names.mapNotNull { it.substringBeforeLast('.').toIntOrNull() }
                val grid = Math.round(t.sizeKm / (if (sub == "4K") 64 else 32)).toInt()
                appendLine("  $sub: ${names.size} cells, named ${nums.minOrNull()}..${nums.maxOrNull()} of a $grid × $grid grid (1..${grid * grid})" +
                    (if (nums.any { it < 1 || it > grid * grid }) "  FAIL: a name outside the grid" else ""))
                if (nums.any { it < 1 || it > grid * grid }) fails++
            }
            val hires = t.photo?.let { Theaters.resolveDir(it, "Hires/Day") }?.list()?.filter { it.endsWith(".dds", true) }.orEmpty()
            appendLine("  Hires/Day: ${hires.size} files, ${hires.count { Regex("R\\d+_C\\d+\\.dds", RegexOption.IGNORE_CASE).matches(it) }} named R<row>_C<col>")
            val (name, north, east) = FIELDS[id] ?: Triple("theater centre", t.sizeFt / 2, t.sizeFt / 2)
            val tiles = listOf(Triple(0, 0, 0)) + listOf(5, 7, 10).map { z ->
                val n = 1 shl z
                Triple(z, floor((1 - north / t.sizeFt) * n).toInt().coerceIn(0, n - 1), floor(east / t.sizeFt * n).toInt().coerceIn(0, n - 1))
            }
            for ((z, r, c) in tiles) {
                val t0 = System.nanoTime()
                val px = try { MapTiles.satellite(t, z, r, c, MapTiles.TILE) } catch (e: Throwable) { appendLine("  z$z r$r c$c: FAIL ${e.javaClass.simpleName}: ${e.message}"); fails++; continue }
                val ms = (System.nanoTime() - t0) / 1_000_000
                if (px == null) { appendLine("  z$z r$r c$c: FAIL no picture"); fails++; continue }
                val empty = px.count { it == NO_DATA } * 100.0 / px.size
                appendLine("  z$z r$r c$c${if (z > 0) " ($name)" else " (the theater)"}: $ms ms, ${"%.1f".format(empty)} % painted by no layer")
            }
        }
        appendLine()
        appendLine(if (fails == 0) "PASS: every terrain's satellite tiles drawn" else "FAIL: $fails")
    }

    private fun dds(f: File): String = try {
        FileChannel.open(f.toPath(), StandardOpenOption.READ).use { ch ->
            val b = ByteBuffer.allocate(148).order(ByteOrder.LITTLE_ENDIAN)
            ch.read(b, 0)
            val four = String(ByteArray(4) { b.get(84 + it) }, Charsets.ISO_8859_1)
            val fmt = if (four == "DX10") "DX10 format ${b.getInt(128)}" else four
            "${b.getInt(16)} × ${b.getInt(12)} px, ${maxOf(1, b.getInt(28))} level(s), $fmt, ${f.length() / (1024 * 1024)} MB"
        }
    } catch (e: Exception) { "unreadable: ${e.message}" }
}
