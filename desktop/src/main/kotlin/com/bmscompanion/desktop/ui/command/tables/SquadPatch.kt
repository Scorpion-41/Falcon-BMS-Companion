package com.bmscompanion.desktop.ui.command.tables

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.bmscompanion.desktop.bridge.command.CommandSave
import com.bmscompanion.desktop.bridge.command.SquadronPatches
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ImageInfo
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A squadron's patch drawn from BMS's own picture ([SquadronPatches]) at exactly the pixels it covers: the 96-px
 * picture is area-averaged down to that size once (cached), then drawn 1:1, so it stays crisp in a 22-dp table row
 * and in the inspector. Draws nothing when BMS has no picture for the number.
 */
object SquadPatchPictures {
    private val cache = ConcurrentHashMap<String, Any>()

    fun bitmap(set: SquadronPatches.PatchSet, n: Int, px: Int): ImageBitmap? {
        val key = "${System.identityHashCode(set)}|$n|$px"
        val got = cache.getOrPut(key) {
            val p = set.patch(n) ?: return@getOrPut Unit
            val s = px.coerceIn(4, 512)
            // the picture's own aspect inside an s x s square
            val scale = min(s.toDouble() / p.w, s.toDouble() / p.h)
            val dw = max(1, (p.w * scale).roundToInt())
            val dh = max(1, (p.h * scale).roundToInt())
            val out = boxScale(p.argb, p.w, p.h, dw, dh)
            val b = Bitmap()
            b.allocPixels(ImageInfo.makeN32(dw, dh, ColorAlphaType.PREMUL))
            val bytes = ByteArray(dw * dh * 4)
            for (i in 0 until dw * dh) {
                val c = out[i]
                bytes[i * 4] = (c and 0xFF).toByte(); bytes[i * 4 + 1] = ((c shr 8) and 0xFF).toByte()
                bytes[i * 4 + 2] = ((c shr 16) and 0xFF).toByte(); bytes[i * 4 + 3] = ((c ushr 24) and 0xFF).toByte()
            }
            b.installPixels(bytes)
            b.setImmutable()
            runCatching { b.asComposeImageBitmap() }.getOrNull() ?: Unit
        }
        return got as? ImageBitmap
    }

    /** Area average of an ARGB picture onto [dw] x [dh], premultiplied (BGRA order out, premultiplied, for N32). */
    private fun boxScale(src: IntArray, sw: Int, sh: Int, dw: Int, dh: Int): IntArray {
        val out = IntArray(dw * dh)
        val fx = sw.toDouble() / dw
        val fy = sh.toDouble() / dh
        for (dy in 0 until dh) {
            val y0 = dy * fy; val y1 = (dy + 1) * fy
            for (dx in 0 until dw) {
                val x0 = dx * fx; val x1 = (dx + 1) * fx
                var a = 0.0; var r = 0.0; var g = 0.0; var bl = 0.0; var wsum = 0.0
                var sy = y0.toInt()
                while (sy < y1 && sy < sh) {
                    val wy = min(y1, sy + 1.0) - max(y0, sy.toDouble())
                    var sx = x0.toInt()
                    while (sx < x1 && sx < sw) {
                        val w = wy * (min(x1, sx + 1.0) - max(x0, sx.toDouble()))
                        val c = src[sy * sw + sx]
                        val ca = ((c ushr 24) and 0xFF) / 255.0
                        a += ca * w
                        r += ((c shr 16) and 0xFF) * ca * w; g += ((c shr 8) and 0xFF) * ca * w; bl += (c and 0xFF) * ca * w
                        wsum += w
                        sx++
                    }
                    sy++
                }
                if (wsum <= 0) continue
                val oa = (a / wsum * 255).roundToInt().coerceIn(0, 255)
                val or = (r / wsum).roundToInt().coerceIn(0, 255)
                val og = (g / wsum).roundToInt().coerceIn(0, 255)
                val ob = (bl / wsum).roundToInt().coerceIn(0, 255)
                out[dy * dw + dx] = (oa shl 24) or (or shl 16) or (og shl 8) or ob
            }
        }
        return out
    }
}

/** Squadron patch number [n] of [doc]'s theater, [size] square (nothing when BMS has no picture for it). */
@Composable
fun SquadPatch(doc: CommandSave, n: Int?, size: Dp = 20.dp) {
    val set = SquadronPatches.of(doc) ?: return
    if (n == null || n < 0) return
    val density = LocalDensity.current.density
    val px = (size.value * density).roundToInt()
    val img = SquadPatchPictures.bitmap(set, n, px) ?: return
    Canvas(Modifier.size(size)) {
        val ox = ((this.size.width - img.width) / 2f).roundToInt()
        val oy = ((this.size.height - img.height) / 2f).roundToInt()
        drawImage(img, srcOffset = IntOffset.Zero, srcSize = IntSize(img.width, img.height), dstOffset = IntOffset(ox, oy), dstSize = IntSize(img.width, img.height), filterQuality = FilterQuality.None)
    }
}
