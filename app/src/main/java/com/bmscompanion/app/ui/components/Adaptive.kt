package com.bmscompanion.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Tablet-landscape helpers. Everything here falls back to the plain single-column phone layout
 * below the width thresholds, so phone UI is unchanged.
 */

/** Two content columns when there is room (≥ [minSplit]); otherwise left then right stacked. */
@Composable
fun AdaptiveSplit(
    modifier: Modifier = Modifier,
    maxWidth: Dp = 1400.dp,
    minSplit: Dp = 760.dp,
    leftWeight: Float = 1f,
    rightWeight: Float = 1f,
    left: @Composable ColumnScope.() -> Unit,
    right: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        BoxWithConstraints(Modifier.widthIn(max = maxWidth).fillMaxWidth()) {
            if (this.maxWidth >= minSplit) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(leftWeight), verticalArrangement = Arrangement.spacedBy(12.dp)) { left() }
                    Column(Modifier.weight(rightWeight), verticalArrangement = Arrangement.spacedBy(12.dp)) { right() }
                }
            } else {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) { left(); right() }
            }
        }
    }
}

/**
 * Masonry: children keep their order but each goes into the currently shortest column.
 * Column count = width / [minColumn] (capped by [maxColumns]); a single column is a plain vertical stack.
 */
@Composable
fun Masonry(
    modifier: Modifier = Modifier,
    minColumn: Dp = 420.dp,
    maxColumns: Int = 3,
    spacing: Dp = 12.dp,
    content: @Composable () -> Unit,
) {
    Layout(content, modifier.fillMaxWidth()) { measurables, constraints ->
        val gap = spacing.roundToPx()
        val width = constraints.maxWidth
        val cols = (width / minColumn.roundToPx()).coerceIn(1, maxColumns)
        val colW = (width - gap * (cols - 1)) / cols
        val heights = IntArray(cols)
        val placed = measurables.map { m ->
            val p = m.measure(Constraints.fixedWidth(colW))
            val c = heights.indices.minBy { heights[it] }
            val y = heights[c]
            heights[c] += p.height + gap
            Triple(p, c, y)
        }
        val total = (heights.maxOrNull() ?: 0).let { if (it > 0) it - gap else 0 }
        layout(width, total) {
            placed.forEach { (p, c, y) -> p.placeRelative(c * (colW + gap), y) }
        }
    }
}
