package com.bmscompanion.desktop.bridge

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.ui.components.MapDeep
import com.bmscompanion.app.ui.components.MapLook
import com.bmscompanion.app.ui.components.TheaterMap
import com.bmscompanion.app.ui.components.rememberMapState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/**
 * Developer check of the map base and landmarks: `--maprender <theater id> <out folder> [xFt,yFt] [--bms=<folder>]`
 * renders every map style at three zoom levels into PNGs (same view per zoom, so styles can be compared for
 * alignment). With a BMS folder (read only) it also draws the levels past the bundled ones as the PC draws them
 * ([MapTiles], handed to the map through [MapDeep.fetcher]) at four deeper zooms — 30, 120, 600 and 2,500 px a
 * nautical mile, the second and deeper with the fields as their diagrams — and times each tile.
 */
object MapRender {
    fun run(theaterId: String, out: File, focus: Pair<Double, Double>?, bms: File? = null) {
        out.mkdirs()
        val th = runBlocking { Repo.theater(theaterId) } ?: error("unknown theater $theaterId")
        val center = focus ?: (th.sizeFt / 2 to th.sizeFt / 2)
        val before = MapLook.style
        val times = ArrayList<Long>()
        val frames = StringBuilder()
        if (bms != null) {
            MapTiles.rootOverride = bms
            MapDeep.fetcher = { path ->
                withContext(Dispatchers.IO) {
                    val q = path.substringAfter('?').split('&').associate { it.substringBefore('=') to it.substringAfter('=') }
                    val t0 = System.nanoTime()
                    val a = MapTiles.tile(q["map"]!!, q["style"]!!, q["z"]!!.toInt(), q["r"]!!.toInt(), q["c"]!!.toInt())
                    synchronized(times) { times += (System.nanoTime() - t0) / 1_000_000 }
                    a.bytes
                }
            }
        }
        val w = 1400; val h = 900
        val nm = th.sizeFt / 6076.12
        // scale for a given number of pixels a nautical mile: the square is the window's height at scale 1
        val deep = if (bms != null) listOf(30f, 120f, 600f, 2500f).map { (it * nm / h).toFloat() } else emptyList()
        // BMSC_MAPRENDER_STYLES=relief,topo draws only those styles (a quicker look at one change)
        val only = System.getenv("BMSC_MAPRENDER_STYLES")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
        for (style in MapLook.styles.map { it.first }.filter { only == null || it in only }) {
            MapLook.chooseStyle(style)
            for (zoom in listOf(1f, 4f, 14f) + deep) {
                val scene = ImageComposeScene(w, h, Density(1f)) {
                    TheaterMap(th.map, th.sizeFt, Modifier.fillMaxSize(), rememberMapState(), maxScale = 24f, focus = if (zoom > 1f) center else null, focusScale = zoom)
                }
                var t = 0L
                val rounds = if (zoom > 14f) 160 else 60
                repeat(rounds) { scene.render(t); t += 50_000_000; Thread.sleep(50) } // let overview, tiles, geo and diagrams load
                // the cost of a frame of this view once everything has come (the draw alone, headless, on the processor)
                val f0 = System.nanoTime()
                repeat(20) { t += 16_000_000; scene.render(t) }
                val view = if (zoom > 14f) "${(zoom * h / nm).toInt()} px/nm" else "x${zoom.toInt()}"
                frames.append("$style $view: ${"%.1f".format((System.nanoTime() - f0) / 20e6)} ms a frame\n")
                val png = scene.render(t).encodeToData(EncodedImageFormat.PNG)!!.bytes
                val name = if (zoom > 14f) "${theaterId}-${style}-${(zoom * h / nm).toInt()}pxnm.png" else "${theaterId}-${style}-z${zoom.toInt()}.png"
                File(out, name).writeBytes(png)
                scene.close()
            }
        }
        MapLook.chooseStyle(before)
        if (bms != null) {
            MapDeep.fetcher = null
            val s = times.sorted()
            File(out, "maprender-tiles.txt").writeText(
                "PC tiles drawn: ${s.size}; ms each: median ${s.getOrNull(s.size / 2)}, 90th ${s.getOrNull(s.size * 9 / 10)}, max ${s.lastOrNull()}\n" + frames,
            )
        }
    }
}
