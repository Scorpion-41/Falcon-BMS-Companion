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
    val layout = tm.measure(text, style)
    if (topLeft.x + layout.size.width < 0 || topLeft.y + layout.size.height < 0) return
    drawText(layout, topLeft = topLeft)
}
