package com.bmscompanion.app.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText

/**
 * Crash-safe text drawing. `drawText(measurer, text, topLeft)` derives its width constraint from
 * (canvas width - topLeft.x) and throws when the label is off-canvas (e.g. zoomed maps).
 * Here we measure unconstrained and skip anything fully outside the canvas.
 */
fun DrawScope.safeText(tm: TextMeasurer, text: String, topLeft: Offset, style: TextStyle) {
    if (text.isEmpty()) return
    if (topLeft.x > size.width || topLeft.y > size.height) return
    val label = MapText.label(this, tm, text, style)
    if (topLeft.x + label.width < 0 || topLeft.y + label.height < 0) return
    MapText.draw(this, label, topLeft)
}

/**
 * The maps' labels, laid out once and kept for as long as they are drawn ([label]), and drawn ([draw]) as a bitmap
 * when they carry a blurred shadow. A map draws every label it shows on every frame of a pan or a zoom: measured
 * through a [TextMeasurer] each time, a theater's towns and fields outgrew its cache and were all laid out again on
 * every frame — on the tablet that was half of a frame's time — and a shadow was blurred again on every frame on
 * top. A label here costs one lookup a frame. The key is what was laid out (text, style, width allowed, density),
 * so a skin or a size change makes new ones; kept to [MAX_LABELS] and [maxSpriteBytes], the least lately drawn
 * dropped first. Pass styles made once (not a new TextStyle per label per frame) where you can: equal styles still
 * hit, identical ones hit faster.
 */
object MapText {
    private const val MAX_LABELS = 3000
    private val maxSpriteBytes: Long get() = if (com.bmscompanion.app.data.Repo.lowMemory) 6L shl 20 else 24L shl 20

    class Label internal constructor(val layout: androidx.compose.ui.text.TextLayoutResult) {
        val width: Int get() = layout.size.width
        val height: Int get() = layout.size.height
        internal var sprite: androidx.compose.ui.graphics.ImageBitmap? = null
        internal var pad = 0
        internal var spriteDone = false
        internal var used = 0L
    }

    private class Key(val text: String, val style: TextStyle, val maxWidth: Int, val density: Float, val fontScale: Float, val rtl: Boolean) {
        private val hash = ((((text.hashCode() * 31 + style.hashCode()) * 31 + maxWidth) * 31 + density.hashCode()) * 31 + fontScale.hashCode()) * 2 + (if (rtl) 1 else 0)
        override fun hashCode() = hash
        override fun equals(other: Any?) = other is Key && other.hash == hash && other.maxWidth == maxWidth && other.density == density &&
            other.fontScale == fontScale && other.rtl == rtl && other.text == text && (other.style === style || other.style == style)
    }

    private val labels = HashMap<Key, Label>()
    private var clock = 0L
    private var spriteBytes = 0L

    /** [text] laid out in [style], at most [maxWidth] px wide (wrapping), at [scope]'s density. */
    fun label(scope: DrawScope, tm: TextMeasurer, text: String, style: TextStyle, maxWidth: Int = Int.MAX_VALUE): Label = synchronized(this) {
        val rtl = scope.layoutDirection == androidx.compose.ui.unit.LayoutDirection.Rtl
        val key = Key(text, style, maxWidth, scope.density, scope.fontScale, rtl)
        val found = labels[key]
        if (found != null) { found.used = ++clock; return found }
        val layout = tm.measure(
            text, style,
            constraints = if (maxWidth == Int.MAX_VALUE) androidx.compose.ui.unit.Constraints() else androidx.compose.ui.unit.Constraints(maxWidth = maxWidth),
            layoutDirection = scope.layoutDirection,
            density = androidx.compose.ui.unit.Density(scope.density, scope.fontScale),
        )
        val l = Label(layout).also { it.used = ++clock }
        labels[key] = l
        if (labels.size > MAX_LABELS) trim()
        l
    }

    /** Draws [label] with its top left at [topLeft] (whole pixels for a shadowed one, which is a bitmap). */
    fun draw(scope: DrawScope, label: Label, topLeft: Offset) {
        val shadow = label.layout.layoutInput.style.shadow
        if (shadow == null || shadow.blurRadius <= 0f || label.width <= 0 || label.height <= 0) { scope.drawText(label.layout, topLeft = topLeft); return }
        val sprite = synchronized(this) {
            if (!label.spriteDone) {
                label.spriteDone = true
                makeSprite(scope, label)
                if (spriteBytes > maxSpriteBytes) trim()
            }
            label.sprite
        }
        if (sprite == null) { scope.drawText(label.layout, topLeft = topLeft); return }
        val x = kotlin.math.round(topLeft.x).toInt() - label.pad
        val y = kotlin.math.round(topLeft.y).toInt() - label.pad
        scope.drawImage(sprite, topLeft = Offset(x.toFloat(), y.toFloat()))
    }

    private fun makeSprite(scope: DrawScope, label: Label) {
        val layout = label.layout
        val shadow = layout.layoutInput.style.shadow ?: return
        val pad = kotlin.math.ceil(shadow.blurRadius * 1.5f + maxOf(kotlin.math.abs(shadow.offset.x), kotlin.math.abs(shadow.offset.y)) + 2f).toInt()
        val w = layout.size.width + 2 * pad
        val h = layout.size.height + 2 * pad
        if (w > 2048 || h > 512) return
        runCatching {
            val image = androidx.compose.ui.graphics.ImageBitmap(w, h)
            androidx.compose.ui.graphics.drawscope.CanvasDrawScope().draw(
                androidx.compose.ui.unit.Density(scope.density, scope.fontScale), scope.layoutDirection,
                androidx.compose.ui.graphics.Canvas(image), androidx.compose.ui.geometry.Size(w.toFloat(), h.toFloat()),
            ) { drawText(layout, topLeft = Offset(pad.toFloat(), pad.toFloat())) }
            image.prepareToDraw()
            label.sprite = image
            label.pad = pad
            spriteBytes += 4L * w * h
        }
    }

    /** Drops the least lately drawn quarter (more while the bitmaps are over their budget). */
    private fun trim() {
        val byAge = labels.entries.sortedBy { it.value.used }
        var i = 0
        val targetCount = MAX_LABELS * 3 / 4
        val targetBytes = maxSpriteBytes * 3 / 4
        while (i < byAge.size && (labels.size > targetCount || spriteBytes > targetBytes)) {
            val (k, l) = byAge[i++]
            labels.remove(k)
            l.sprite?.let { s -> spriteBytes -= 4L * s.width * s.height }
        }
    }

    /** Forgets every label (a check). */
    fun clear() = synchronized(this) { labels.clear(); spriteBytes = 0L }
}

// ------------------------------------------------------------------ dashes the GPU draws itself
//
// A line, ring or outline stroked with a dash PathEffect is dashed and stroked on the processor and then drawn by a
// phone's GPU as a mask the size of the whole shape, made and uploaded again on every frame: a threat ring or a
// track across the screen cost a mask the size of the screen, and a mission map's pan spent more time on those than
// on everything else. These draw the same dashes as plain straight strokes (each a quad the GPU draws in a batch),
// only those on screen, with the dash pattern carried round corners as a PathEffect does. [dash] is on, off (on,
// off …) in pixels, as given to PathEffect.dashPathEffect.

/** [start]–[end] dashed by [dash]; answers how far into the pattern the line ended (to carry on along a polyline). */
fun DrawScope.drawDashedLine(
    color: androidx.compose.ui.graphics.Color, start: Offset, end: Offset, strokeWidth: Float, dash: FloatArray, phase: Float = 0f,
    cap: androidx.compose.ui.graphics.StrokeCap = androidx.compose.ui.graphics.StrokeCap.Butt,
): Float {
    val dx = end.x - start.x
    val dy = end.y - start.y
    val len = kotlin.math.sqrt(dx * dx + dy * dy)
    var period = 0f
    for (d in dash) period += d
    if (len <= 0f || period <= 0f || dash.size < 2 || !len.isFinite()) return phase
    // the part of the line that can be seen (Liang–Barsky against the canvas, a stroke's width round it)
    val m = strokeWidth + 2f
    var t0 = 0f
    var t1 = 1f
    fun clip(p: Float, q: Float): Boolean {
        if (p == 0f) return q >= 0f
        val r = q / p
        if (p < 0f) { if (r > t1) return false; if (r > t0) t0 = r } else { if (r < t0) return false; if (r < t1) t1 = r }
        return true
    }
    val inView = clip(-dx, start.x + m) && clip(dx, size.width + m - start.x) && clip(-dy, start.y + m) && clip(dy, size.height + m - start.y)
    val endPhase = (phase + len) % period
    if (!inView || t1 <= t0) return endPhase
    val ux = dx / len
    val uy = dy / len
    val d0 = t0 * len
    val d1 = t1 * len
    // walk the pattern from the period the visible part starts in
    var at = -phase + kotlin.math.floor((d0 + phase) / period) * period
    var i = 0
    var guard = 0
    while (at < d1 && guard++ < 20000) {
        val seg = dash[i]
        if (i % 2 == 0) {
            val a = kotlin.math.max(at, d0)
            val b = kotlin.math.min(at + seg, d1)
            if (b > a) drawLine(color, Offset(start.x + ux * a, start.y + uy * a), Offset(start.x + ux * b, start.y + uy * b), strokeWidth, cap)
        }
        at += seg
        i = (i + 1) % dash.size
    }
    return endPhase
}

/** A dashed polyline through [points] (closed back to the first when [closed]), the pattern carried round each corner. */
fun DrawScope.drawDashedPolyline(color: androidx.compose.ui.graphics.Color, points: List<Offset>, strokeWidth: Float, dash: FloatArray, closed: Boolean = false) {
    if (points.size < 2) return
    var phase = 0f
    for (k in 1 until points.size) phase = drawDashedLine(color, points[k - 1], points[k], strokeWidth, dash, phase)
    if (closed) drawDashedLine(color, points[points.size - 1], points[0], strokeWidth, dash, phase)
}

/**
 * A dashed circle as straight chords (a dash is short beside its ring: the chord is within a quarter pixel of the
 * arc for any ring this is used for); a ring too small for that, or with more dashes than a screen could show, is
 * left to the PathEffect.
 */
fun DrawScope.drawDashedCircle(color: androidx.compose.ui.graphics.Color, radius: Float, center: Offset, strokeWidth: Float, dash: FloatArray) {
    var period = 0f
    for (d in dash) period += d
    if (radius <= 0f || period <= 0f || dash.size < 2) return
    val m = strokeWidth + 2f
    // nowhere near the view: nothing
    val nx = center.x.coerceIn(0f, size.width)
    val ny = center.y.coerceIn(0f, size.height)
    val near = kotlin.math.hypot(center.x - nx, center.y - ny)
    val far = maxOf(kotlin.math.hypot(center.x, center.y), kotlin.math.hypot(center.x - size.width, center.y), kotlin.math.hypot(center.x, center.y - size.height), kotlin.math.hypot(center.x - size.width, center.y - size.height))
    if (radius - m > far || radius + m < near) return
    val circumference = (2 * kotlin.math.PI * radius).toFloat()
    val longest = dash.max()
    if (radius < 4f * longest || circumference / period > 6000f) {
        drawCircle(color, radius, center, style = androidx.compose.ui.graphics.drawscope.Stroke(strokeWidth, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(dash, 0f)))
        return
    }
    // whole periods round the ring (as Skia's dash does not), so the last dash meets the first evenly
    val k = circumference / (kotlin.math.round(circumference / period).coerceAtLeast(1f) * period)
    var at = 0f
    var i = 0
    // start at the top, as a dashed circle path does
    while (at < circumference - 0.01f) {
        val seg = dash[i] * k
        if (i % 2 == 0) {
            val a0 = at / radius - (kotlin.math.PI / 2).toFloat()
            val a1 = (at + seg) / radius - (kotlin.math.PI / 2).toFloat()
            val p0 = Offset(center.x + radius * kotlin.math.cos(a0), center.y + radius * kotlin.math.sin(a0))
            val p1 = Offset(center.x + radius * kotlin.math.cos(a1), center.y + radius * kotlin.math.sin(a1))
            if (!(maxOf(p0.x, p1.x) < -m || minOf(p0.x, p1.x) > size.width + m || maxOf(p0.y, p1.y) < -m || minOf(p0.y, p1.y) > size.height + m)) {
                drawLine(color, p0, p1, strokeWidth)
            }
        }
        at += seg
        i = (i + 1) % dash.size
    }
}

/**
 * Draws a measured label; one with a blurred shadow (the maps' labels: white on a dark blur, or dark on a white one)
 * is drawn once into a small bitmap and only that bitmap is drawn after ([TextSprites]). Blurring every label again
 * on every frame of a pan was most of what a map's frame cost — on the tablet and in the headless timing alike.
 */
fun DrawScope.drawTextFast(layout: androidx.compose.ui.text.TextLayoutResult, topLeft: Offset) {
    val shadow = layout.layoutInput.style.shadow
    if (shadow == null || shadow.blurRadius <= 0f || layout.size.width <= 0 || layout.size.height <= 0) {
        drawText(layout, topLeft = topLeft)
        return
    }
    TextSprites.draw(this, layout, topLeft)
}

/**
 * The bitmaps of the shadowed labels drawn lately, keyed by what was laid out (text, style, constraints, density), so a
 * label costs one blur when it first appears and a bitmap's draw on every frame after. Kept to [MAX] labels, the
 * oldest dropped first. Drawn on the UI thread only.
 */
object TextSprites {
    private const val MAX = 400

    private class Sprite(val image: androidx.compose.ui.graphics.ImageBitmap, val pad: Int)

    private val sprites = LinkedHashMap<androidx.compose.ui.text.TextLayoutInput, Sprite>()

    fun draw(scope: DrawScope, layout: androidx.compose.ui.text.TextLayoutResult, topLeft: Offset) {
        val key = layout.layoutInput
        val sprite = sprites[key] ?: make(scope, layout)?.also { s ->
            if (sprites.size >= MAX) sprites.keys.firstOrNull()?.let { sprites.remove(it) }
            sprites[key] = s
        }
        if (sprite == null) { scope.drawText(layout, topLeft = topLeft); return }
        // whole pixels, so the bitmap is copied rather than resampled
        val x = kotlin.math.round(topLeft.x).toInt() - sprite.pad
        val y = kotlin.math.round(topLeft.y).toInt() - sprite.pad
        scope.drawImage(sprite.image, topLeft = Offset(x.toFloat(), y.toFloat()))
    }

    private fun make(scope: DrawScope, layout: androidx.compose.ui.text.TextLayoutResult): Sprite? = runCatching {
        val shadow = layout.layoutInput.style.shadow
        val pad = kotlin.math.ceil((shadow?.blurRadius ?: 0f) * 1.5f + maxOf(kotlin.math.abs(shadow?.offset?.x ?: 0f), kotlin.math.abs(shadow?.offset?.y ?: 0f)) + 2f).toInt()
        val w = layout.size.width + 2 * pad
        val h = layout.size.height + 2 * pad
        if (w > 2048 || h > 512) return@runCatching null
        val image = androidx.compose.ui.graphics.ImageBitmap(w, h)
        androidx.compose.ui.graphics.drawscope.CanvasDrawScope().draw(
            androidx.compose.ui.unit.Density(scope.density, scope.fontScale), scope.layoutDirection,
            androidx.compose.ui.graphics.Canvas(image), androidx.compose.ui.geometry.Size(w.toFloat(), h.toFloat()),
        ) { drawText(layout, topLeft = Offset(pad.toFloat(), pad.toFloat())) }
        Sprite(image, pad)
    }.getOrNull()

    /** Forgets every bitmap (a check, or a change of skin that changes nothing of the key). */
    fun clear() = sprites.clear()
}
