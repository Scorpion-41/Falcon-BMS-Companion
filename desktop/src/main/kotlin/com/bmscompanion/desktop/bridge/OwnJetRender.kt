package com.bmscompanion.desktop.bridge

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import com.bmscompanion.app.ui.components.OwnJetLook
import com.bmscompanion.app.ui.components.drawOwnJet
import java.io.File

/**
 * `--ownjetrender <out folder>` (1.4.2): your jet as every map now draws it, Small / Medium / Large, beside a leg of
 * the route in its amber, over a Korea tile of each bundled style, at a PC's density (1.0) and a tablet's (2.0).
 * One PNG per style and density. Reads only the bundled map tiles.
 */
object OwnJetRender {
    fun run(out: File): String = buildString {
        out.mkdirs()
        for (style in listOf("relief", "satellite", "dark", "chart", "topo")) for (density in listOf(1f, 2f)) {
            val w = (520 * density).toInt(); val h = (220 * density).toInt()
            val img = ImageBitmap(w, h)
            val canvas = Canvas(img)
            val tileBytes = OwnJetRender::class.java.getResourceAsStream("/maps/korea/$style/3/3_4.webp")?.readBytes()
            val tile = tileBytes?.let { com.bmscompanion.desktop.skiaDecode(it) }
            CanvasDrawScope().draw(Density(density), LayoutDirection.Ltr, canvas, Size(w.toFloat(), h.toFloat())) {
                if (tile != null) drawImage(tile, IntOffset.Zero, IntSize(tile.width, tile.height), IntOffset.Zero, IntSize(w, h))
                else drawRect(Color.Gray)
                val light = style == "chart" || style == "topo"
                for (i in 0..2) {
                    OwnJetLook.size = i
                    val p = Offset(w * (0.2f + i * 0.3f), h * 0.55f)
                    // a leg of the route through the jet, as on the map (Hud.Amber)
                    drawLine(Color(0xFFFFB020), p + Offset(-w * 0.12f, h * 0.35f), p + Offset(w * 0.12f, -h * 0.35f), 3f)
                    drawOwnJet(p, 35.0, OwnJetLook.lengthPx(this) * 1.6f, light)
                }
            }
            OwnJetLook.size = 1
            val f = File(out, "ownjet-$style-${density.toInt()}x.png")
            com.bmscompanion.desktop.skiaPng(img)?.let { f.writeBytes(it) }
            appendLine("${f.name}: ${if (tile == null) "no tile, grey ground" else "Korea tile"}; S/M/L = " +
                (0..2).joinToString("/") { i -> "${lenOf(i, density)} px" })
            OwnJetLook.size = 1
        }
    }

    private fun lenOf(i: Int, density: Float): Int {
        val dp = floatArrayOf(26f, 36f, 50f)[i] * density
        val px = floatArrayOf(30f, 44f, 60f)[i]
        return maxOf(dp, px).toInt()
    }
}
