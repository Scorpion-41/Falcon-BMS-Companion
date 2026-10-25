package com.bmscompanion.desktop.bridge.command

import com.bmscompanion.desktop.bridge.Theaters
import java.io.File

/**
 * `--campmaprender <a BMS folder> <out folder>` (read only): BMS's own 2D campaign map ([CampMap2D]) of every theater
 * of the folder's theater list — which `CampMap.idx` it is, its size, the level drawn pixel for pixel — and three tiles
 * of each distinct chart as JPEGs: the whole theater (level 0), one at the chart's own level and one enlarged two steps,
 * both over the theater's middle, with how long each took (cold, the first one also reduces the whole picture once).
 */
object CampMapRender {
    fun run(root: File, out: File): String = buildString {
        out.mkdirs()
        CampMap2D.rootOverride = root
        CampMap2D.forget()
        try {
            val set = Theaters.at(root)
            val seen = HashSet<String>()
            for (t in set.all) {
                val c = CampMap2D.chart(set, t)
                if (c !is CampMap2D.Chart) { appendLine("${t.name}: no chart — $c"); continue }
                val key = c.bundle.rsc.canonicalPath.lowercase()
                appendLine("${t.name} (${t.appId}): ${c.bundle.idx.path.removePrefix(root.path)} · ${c.width} x ${c.height} · ${if (c.image.paletted) "8-bit, ${c.palette.size} colours" else "RGB555"} · own level z${c.nativeZ} · map ${c.mapId ?: "?"}")
                if (!seen.add(key)) { appendLine("  (the same file as a theater above)"); continue }
                val slug = t.appId
                val mid = (1 shl c.nativeZ) / 2
                for ((z, r, cc) in listOf(Triple(0, 0, 0), Triple(c.nativeZ, mid, mid), Triple(c.nativeZ + 2, mid * 4, mid * 4))) {
                    val t0 = System.nanoTime()
                    val (bytes, why) = CampMap2D.tile(c.mapId.orEmpty(), t.appId, z, r, cc)
                    val ms = (System.nanoTime() - t0) / 1_000_000
                    if (bytes == null) appendLine("FAIL: ${t.name} z$z: $why")
                    else {
                        File(out, "$slug-z$z.jpg").writeBytes(bytes)
                        appendLine("  z$z ($r, $cc): ${bytes.size / 1024} KB in $ms ms")
                    }
                }
                val t1 = System.nanoTime()
                CampMap2D.tile(c.mapId.orEmpty(), t.appId, c.nativeZ - 1, mid / 2, mid / 2 + 1)
                appendLine("  z${c.nativeZ - 1} warm: ${(System.nanoTime() - t1) / 1_000_000} ms")
                if (c.mapId == null) appendLine("  note: its terrain is not one of the app's maps, so no map offers the chart for it")
            }
            if (seen.isEmpty()) appendLine("FAIL: no theater of ${root.path} has a chart")
        } catch (e: Throwable) {
            appendLine("FAIL: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            CampMap2D.rootOverride = null
            CampMap2D.forget()
        }
    }
}
