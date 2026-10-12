package com.bmscompanion.desktop.bridge

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Draws a model's triangles ([BmlModel.triangles]) as a picture of the target, on the processor (no graphics card,
 * nothing installed): an orthographic camera — a targeting pod is miles away, so nothing shrinks with distance —
 * looking down at [View.pitch] from [View.yaw] (0 = from dead ahead, measured towards the right side), the model
 * framed to fill the picture, flat-shaded from one light above and to the left of the camera, its shadow on the
 * ground, its outline and creases inked, drawn at twice the size and averaged down so the edges are smooth. Then one
 * of two looks: [Look.PLAIN], a clean grey model on a light ground with a scale bar, or [Look.INTEL], a black-and-white
 * targeting-pod picture (contrast, a little blur, sensor noise, a reticle and a caption).
 */
object TargetRender {
    enum class View(val id: String, val label: String, val yaw: Double, val pitch: Double) {
        FRONT_RIGHT("fr", "Front right", 40.0, 30.0),
        REAR_LEFT("rl", "Rear left", 220.0, 30.0);

        companion object {
            fun of(id: String?): View = entries.firstOrNull { it.id.equals(id, true) } ?: FRONT_RIGHT
        }
    }

    enum class Look(val id: String) {
        PLAIN("plain"), INTEL("intel");

        companion object {
            fun of(id: String?): Look = entries.firstOrNull { it.id.equals(id, true) } ?: PLAIN
        }
    }

    private const val SS = 2
    private const val MARGIN = 0.09

    /**
     * The picture of [tris] (nine floats a triangle, [count] floats used; model feet: x starboard, y up, z forward),
     * [width] x [height] pixels. [caption] is printed on the intelligence look; [seed] makes its noise the same each
     * time for one target.
     */
    fun render(tris: FloatArray, count: Int, view: View, look: Look, width: Int, height: Int, caption: String? = null, seed: Long = 1L): BufferedImage {
        val w = width * SS
        val h = height * SS
        val yaw = Math.toRadians(view.yaw)
        val pitch = Math.toRadians(view.pitch)
        // towards the camera, the screen's right and up (Gram-Schmidt of the world's up)
        val dx = sin(yaw) * cos(pitch); val dy = sin(pitch); val dz = cos(yaw) * cos(pitch)
        val rx = -cos(yaw); val ry = 0.0; val rz = sin(yaw)
        var ux = -dy * dx; var uy = 1.0 - dy * dy; var uz = -dy * dz
        run { val l = sqrt(ux * ux + uy * uy + uz * uz); ux /= l; uy /= l; uz /= l }
        // the light: above, to the left of the camera, a little towards it
        var lx = -0.45 * rx + 0.85 * 0.0 + 0.30 * dx
        var ly = -0.45 * ry + 0.85 * 1.0 + 0.30 * dy
        var lz = -0.45 * rz + 0.85 * 0.0 + 0.30 * dz
        run { val l = sqrt(lx * lx + ly * ly + lz * lz); lx /= l; ly /= l; lz /= l }

        val n = count / 3
        val px = FloatArray(n); val py = FloatArray(n); val pd = FloatArray(n)
        var x0 = Double.MAX_VALUE; var x1 = -Double.MAX_VALUE; var y0 = Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
        var ground = Double.MAX_VALUE
        val ok = BooleanArray(n / 3)
        for (t in 0 until n / 3) {
            var good = true
            for (k in 0 until 3) {
                val i = (t * 3 + k) * 3
                val x = tris[i].toDouble(); val y = tris[i + 1].toDouble(); val z = tris[i + 2].toDouble()
                if (!x.isFinite() || !y.isFinite() || !z.isFinite() || abs(x) > 5000 || abs(y) > 5000 || abs(z) > 5000) good = false
            }
            ok[t] = good
            if (!good) continue
            for (k in 0 until 3) {
                val v = t * 3 + k
                val i = v * 3
                val x = tris[i].toDouble(); val y = tris[i + 1].toDouble(); val z = tris[i + 2].toDouble()
                val sx = x * rx + y * ry + z * rz
                val sy = x * ux + y * uy + z * uz
                px[v] = sx.toFloat(); py[v] = sy.toFloat(); pd[v] = (-(x * dx + y * dy + z * dz)).toFloat()
            }
        }
        // the frame: what lies between the 0.5th and 99.5th percentile on each screen axis, widened by half that
        // span — a model can carry a stray vertex or a helper far from the rest, which would shrink the target to a
        // corner; triangles outside it are not drawn
        val xs = ArrayList<Float>(); val ys = ArrayList<Float>()
        for (t in 0 until n / 3) if (ok[t]) for (k in 0 until 3) { xs += px[t * 3 + k]; ys += py[t * 3 + k] }
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        if (xs.isEmpty()) return img
        xs.sort(); ys.sort()
        fun q(l: List<Float>, f: Double) = l[((l.size - 1) * f).toInt()].toDouble()
        val qx0 = q(xs, 0.005); val qx1 = q(xs, 0.995); val qy0 = q(ys, 0.005); val qy1 = q(ys, 0.995)
        val lx0 = qx0 - (qx1 - qx0) * 0.5 - 1; val lx1 = qx1 + (qx1 - qx0) * 0.5 + 1
        val ly0 = qy0 - (qy1 - qy0) * 0.5 - 1; val ly1 = qy1 + (qy1 - qy0) * 0.5 + 1
        for (t in 0 until n / 3) {
            if (!ok[t]) continue
            for (k in 0 until 3) {
                val v = t * 3 + k
                if (px[v] < lx0 || px[v] > lx1 || py[v] < ly0 || py[v] > ly1) ok[t] = false
            }
            if (!ok[t]) continue
            for (k in 0 until 3) {
                val v = t * 3 + k
                val sx = px[v].toDouble(); val sy = py[v].toDouble()
                if (sx < x0) x0 = sx; if (sx > x1) x1 = sx
                if (sy < y0) y0 = sy; if (sy > y1) y1 = sy
                val y = tris[v * 3 + 1].toDouble()
                if (y < ground) ground = y
            }
        }
        if (x0 > x1) return img
        val spanX = max(x1 - x0, 1.0); val spanY = max(y1 - y0, 1.0)
        val scale = min(w * (1 - 2 * MARGIN) / spanX, h * (1 - 2 * MARGIN) / spanY)
        val cx = (x0 + x1) / 2; val cy = (y0 + y1) / 2
        fun sxOf(x: Double) = w / 2.0 + (x - cx) * scale
        fun syOf(y: Double) = h / 2.0 - (y - cy) * scale

        // ground, then the shadow cast on it along the light
        val shade = FloatArray(w * h)
        val depth = FloatArray(w * h) { Float.POSITIVE_INFINITY }
        val shadow = BooleanArray(w * h)
        val sxs = DoubleArray(3); val sys = DoubleArray(3); val sds = DoubleArray(3)
        for (t in 0 until n / 3) {
            if (!ok[t]) continue
            for (k in 0 until 3) {
                val i = (t * 3 + k) * 3
                val x = tris[i].toDouble(); val y = tris[i + 1].toDouble(); val z = tris[i + 2].toDouble()
                val f = (y - ground) / ly
                val gx = x - lx * f; val gy = ground; val gz = z - lz * f
                sxs[k] = sxOf(gx * rx + gy * ry + gz * rz)
                sys[k] = syOf(gx * ux + gy * uy + gz * uz)
            }
            fill(sxs, sys, null, w, h) { k, _ -> shadow[k] = true }
        }
        // the model
        for (t in 0 until n / 3) {
            if (!ok[t]) continue
            val a = t * 9
            val ax = tris[a + 3] - tris[a]; val ay = tris[a + 4] - tris[a + 1]; val az = tris[a + 5] - tris[a + 2]
            val bx = tris[a + 6] - tris[a]; val by = tris[a + 7] - tris[a + 1]; val bz = tris[a + 8] - tris[a + 2]
            var nx = (ay * bz - az * by).toDouble(); var ny = (az * bx - ax * bz).toDouble(); var nz = (ax * by - ay * bx).toDouble()
            val nl = sqrt(nx * nx + ny * ny + nz * nz)
            if (nl < 1e-9) continue
            nx /= nl; ny /= nl; nz /= nl
            if (nx * dx + ny * dy + nz * dz < 0) { nx = -nx; ny = -ny; nz = -nz } // two-sided: the side the camera sees
            val diffuse = max(0.0, nx * lx + ny * ly + nz * lz)
            val value = (0.30 + 0.62 * diffuse + 0.08 * ny.coerceAtLeast(0.0)).toFloat()
            for (k in 0 until 3) {
                val v = t * 3 + k
                sxs[k] = sxOf(px[v].toDouble()); sys[k] = syOf(py[v].toDouble()); sds[k] = pd[v].toDouble()
            }
            fill(sxs, sys, sds, w, h) { k, d ->
                if (d < depth[k]) { depth[k] = d.toFloat(); shade[k] = value }
            }
        }
        // ink: the outline against the ground, and creases where the depth jumps
        val crease = max(1.0, max(spanX, spanY) * 0.012).toFloat() // feet: a foot on a vehicle, more on a ship
        val ink = FloatArray(w * h)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val k = y * w + x
            val d = depth[k]
            if (d == Float.POSITIVE_INFINITY) continue
            var e = 0f
            for (o in intArrayOf(-1, 1, -w, w)) {
                val dn = depth[k + o]
                if (dn == Float.POSITIVE_INFINITY) e = 1f
                else if (dn - d > crease) e = max(e, 0.55f)
            }
            ink[k] = e
        }

        // down to the picture's size, then the look
        val intel = look == Look.INTEL
        val lum = FloatArray(width * height)
        val cover = FloatArray(width * height)
        for (y in 0 until height) for (x in 0 until width) {
            var sum = 0f
            var on = 0
            for (j in 0 until SS) for (i in 0 until SS) {
                val k = (y * SS + j) * w + (x * SS + i)
                val g: Float = if (depth[k] != Float.POSITIVE_INFINITY) {
                    on++
                    val base = if (intel) 0.10f + 0.88f * shade[k] else 0.16f + 0.76f * shade[k]
                    base * (1f - (if (intel) 0.45f else 0.6f) * ink[k])
                } else {
                    val top = y.toFloat() / height
                    val gnd = if (intel) 0.42f - 0.06f * top else 0.80f - 0.06f * top
                    if (shadow[k]) gnd * (if (intel) 0.62f else 0.78f) else gnd
                }
                sum += g
            }
            lum[y * width + x] = sum / (SS * SS)
            cover[y * width + x] = on / (SS * SS).toFloat()
        }
        if (intel) intelLook(lum, width, height, seed)
        for (y in 0 until height) for (x in 0 until width) {
            val g = lum[y * width + x].coerceIn(0f, 1f)
            val rgb = if (intel) {
                val v = (g * 255).toInt(); (v shl 16) or (v shl 8) or v
            } else {
                // a cool grey model on a warm sand ground reads as a model, not a hole in the picture
                val c = cover[y * width + x]
                val r = g * (0.90f * c + 1.00f * (1 - c)); val gg = g * (0.94f * c + 0.96f * (1 - c)); val b = g * (1.00f * c + 0.86f * (1 - c))
                ((r.coerceIn(0f, 1f) * 255).toInt() shl 16) or ((gg.coerceIn(0f, 1f) * 255).toInt() shl 8) or (b.coerceIn(0f, 1f) * 255).toInt()
            }
            img.setRGB(x, y, rgb)
        }
        overlay(img, look, caption, scale / SS)
        return img
    }

    /** Fills the triangle ([xs], [ys], depths [ds] or none) at pixel centres, calling [put] with the pixel and its depth. */
    private inline fun fill(xs: DoubleArray, ys: DoubleArray, ds: DoubleArray?, w: Int, h: Int, put: (Int, Double) -> Unit) {
        val minX = max(0, floor(min(xs[0], min(xs[1], xs[2]))).toInt())
        val maxX = min(w - 1, ceil(max(xs[0], max(xs[1], xs[2]))).toInt())
        val minY = max(0, floor(min(ys[0], min(ys[1], ys[2]))).toInt())
        val maxY = min(h - 1, ceil(max(ys[0], max(ys[1], ys[2]))).toInt())
        if (minX > maxX || minY > maxY) return
        val den = (ys[1] - ys[2]) * (xs[0] - xs[2]) + (xs[2] - xs[1]) * (ys[0] - ys[2])
        if (abs(den) < 1e-12) return
        for (y in minY..maxY) {
            val cy = y + 0.5
            for (x in minX..maxX) {
                val cx = x + 0.5
                val w0 = ((ys[1] - ys[2]) * (cx - xs[2]) + (xs[2] - xs[1]) * (cy - ys[2])) / den
                if (w0 < -1e-9) continue
                val w1 = ((ys[2] - ys[0]) * (cx - xs[2]) + (xs[0] - xs[2]) * (cy - ys[2])) / den
                if (w1 < -1e-9) continue
                val w2 = 1 - w0 - w1
                if (w2 < -1e-9) continue
                put(y * w + x, if (ds == null) 0.0 else w0 * ds[0] + w1 * ds[1] + w2 * ds[2])
            }
        }
    }

    /** A targeting pod's TV picture: stretched contrast, a little blur, sensor noise, faint lines and a dark rim. */
    private fun intelLook(lum: FloatArray, w: Int, h: Int, seed: Long) {
        val src = lum.copyOf()
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0f; var c = 0
            for (j in -1..1) for (i in -1..1) {
                val xx = x + i; val yy = y + j
                if (xx in 0 until w && yy in 0 until h) { s += src[yy * w + xx] * (if (i == 0 && j == 0) 4 else 1); c += if (i == 0 && j == 0) 4 else 1 }
            }
            lum[y * w + x] = s / c
        }
        val rnd = java.util.Random(seed)
        for (y in 0 until h) for (x in 0 until w) {
            val k = y * w + x
            var g = (lum[k] - 0.5f) * 1.35f + 0.5f
            g += (rnd.nextGaussian() * 0.045).toFloat()
            if (y % 2 == 1) g -= 0.015f
            val ex = (x - w / 2f) / (w / 2f); val ey = (y - h / 2f) / (h / 2f)
            g *= 1f - 0.22f * (ex * ex + ey * ey).coerceAtMost(1.6f)
            lum[k] = g
        }
    }

    /** The scale bar, and on the intelligence look the reticle and caption. [pxPerFt] at the picture's size. */
    private fun overlay(img: BufferedImage, look: Look, caption: String?, pxPerFt: Double) {
        val g = img.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            val w = img.width; val h = img.height
            val unit = max(1f, w / 640f)
            val intel = look == Look.INTEL
            val ink = if (intel) Color(235, 235, 235) else Color(70, 74, 80)
            val halo = if (intel) Color(0, 0, 0, 150) else Color(255, 255, 255, 160)
            g.font = Font(Font.MONOSPACED, Font.BOLD, (13 * unit).toInt())
            fun text(s: String, x: Float, y: Float) {
                g.color = halo; g.drawString(s, x + unit, y + unit)
                g.color = ink; g.drawString(s, x, y)
            }
            // scale bar: a round length about a sixth of the width
            val want = w / 6.0 / pxPerFt
            val len = listOf(1, 2, 5, 10, 20, 25, 50, 100, 200, 250, 500, 1000).lastOrNull { it <= want } ?: 1
            val bar = (len * pxPerFt).toFloat()
            val bx = 14 * unit; val by = h - 14 * unit
            g.stroke = BasicStroke(2.2f * unit)
            g.color = halo
            g.drawLine((bx + unit).toInt(), (by + unit).toInt(), (bx + bar + unit).toInt(), (by + unit).toInt())
            g.color = ink
            g.drawLine(bx.toInt(), by.toInt(), (bx + bar).toInt(), by.toInt())
            g.drawLine(bx.toInt(), (by - 5 * unit).toInt(), bx.toInt(), (by + 1).toInt())
            g.drawLine((bx + bar).toInt(), (by - 5 * unit).toInt(), (bx + bar).toInt(), (by + 1).toInt())
            text("$len ft", bx + bar + 6 * unit, by + 4 * unit)
            if (intel) {
                // the reticle: a cross with its middle open, and the four corner brackets of the pod's field of view
                val cx = w / 2f; val cy = h / 2f
                val gap = 16 * unit; val arm = 46 * unit
                g.stroke = BasicStroke(1.6f * unit)
                for ((sx, sy) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                    g.color = halo
                    g.drawLine((cx + sx * gap + unit).toInt(), (cy + sy * gap + unit).toInt(), (cx + sx * arm + unit).toInt(), (cy + sy * arm + unit).toInt())
                    g.color = ink
                    g.drawLine((cx + sx * gap).toInt(), (cy + sy * gap).toInt(), (cx + sx * arm).toInt(), (cy + sy * arm).toInt())
                }
                val m = 24 * unit; val c = 26 * unit
                g.color = ink
                for ((ex, ey) in listOf(m to m, w - m to m, m to h - m - 18 * unit, w - m to h - m - 18 * unit)) {
                    val sx = if (ex < w / 2) 1 else -1; val sy = if (ey < h / 2) 1 else -1
                    g.drawLine(ex.toInt(), ey.toInt(), (ex + sx * c).toInt(), ey.toInt())
                    g.drawLine(ex.toInt(), ey.toInt(), ex.toInt(), (ey + sy * c).toInt())
                }
                text("TV", 14 * unit, 22 * unit)
                caption?.takeIf { it.isNotBlank() }?.let { cap ->
                    val fm = g.fontMetrics
                    val s = cap.uppercase().take(48)
                    text(s, w - fm.stringWidth(s) - 14 * unit, 22 * unit)
                }
            }
        } finally {
            g.dispose()
        }
    }

    /** [img] as a JPEG (quality 0.9). */
    fun jpeg(img: BufferedImage): ByteArray {
        val out = ByteArrayOutputStream()
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        try {
            ImageIO.createImageOutputStream(out).use { ios ->
                writer.output = ios
                val p = writer.defaultWriteParam
                p.compressionMode = ImageWriteParam.MODE_EXPLICIT
                p.compressionQuality = 0.9f
                writer.write(null, IIOImage(img, null, null), p)
            }
        } finally {
            writer.dispose()
        }
        return out.toByteArray()
    }
}
