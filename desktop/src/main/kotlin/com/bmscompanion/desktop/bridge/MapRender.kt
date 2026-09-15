package com.bmscompanion.desktop.bridge

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.ui.components.MapLook
import com.bmscompanion.app.ui.components.TheaterMap
import com.bmscompanion.app.ui.components.rememberMapState
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/**
 * Developer check of the map base and landmarks: `--maprender <theater id> <out folder> [xFt,yFt]` renders every map
 * style at three zoom levels into PNGs (same view per zoom, so styles can be compared for alignment).
 */
object MapRender {
    fun run(theaterId: String, out: File, focus: Pair<Double, Double>?) {
        out.mkdirs()
        val th = runBlocking { Repo.theater(theaterId) } ?: error("unknown theater $theaterId")
        val center = focus ?: (th.sizeFt / 2 to th.sizeFt / 2)
        val before = MapLook.style
        for (style in MapLook.styles.map { it.first }) {
            MapLook.chooseStyle(style)
            for (zoom in listOf(1f, 4f, 14f)) {
                val scene = ImageComposeScene(1400, 900, Density(1f)) {
                    TheaterMap(th.map, th.sizeFt, Modifier.fillMaxSize(), rememberMapState(), maxScale = 24f, focus = if (zoom > 1f) center else null, focusScale = zoom)
                }
                var t = 0L
                repeat(60) { scene.render(t); t += 50_000_000; Thread.sleep(50) } // let overview, tiles and geo load
                val png = scene.render(t).encodeToData(EncodedImageFormat.PNG)!!.bytes
                File(out, "${theaterId}-${style}-z${zoom.toInt()}.png").writeBytes(png)
                scene.close()
            }
        }
        MapLook.chooseStyle(before)
    }
}
