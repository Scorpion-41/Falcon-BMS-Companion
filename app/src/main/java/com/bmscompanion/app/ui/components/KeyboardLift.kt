package com.bmscompanion.app.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.foundation.relocation.BringIntoViewResponder
import androidx.compose.foundation.relocation.bringIntoViewResponder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * **The on-screen keyboard never resizes the app.** It used to: the app's frame was padded by the keyboard's height
 * (`WindowInsets.safeDrawing` holds the keyboard), so every page was laid out again in the half left over, frame by
 * frame while the keyboard slid in — and the Planner, whose page is fitted to the room it has, shrank to half its size
 * and stuttered. Now the keyboard is laid over the app, and:
 *
 * - the Planner's boxes are typed in a bar of their own docked on the keyboard (`WdpEditBar`), which holds the lift
 *   off ([hold]) while it shows;
 * - any other text field the keyboard would cover (a search low on a page, the Weather tab's numbers, a file name)
 *   lifts the whole app just far enough to sit above it, as Android's "pan" does: one translation of what is already
 *   drawn, no layout at all. Which field is known because a focused text field asks to be brought into view, and this
 *   modifier is the outermost thing that hears the request ([bringIntoViewResponder]).
 *
 * Nothing changes where there is no on-screen keyboard (the PC, a browser on a desktop): its inset is always 0.
 */
object KeyboardLift {
    /** How many editors that sit on the keyboard themselves are showing: while any is, nothing is lifted. */
    var hold by mutableIntStateOf(0)
}

/**
 * The system's bars and the camera cut-out — never the keyboard — and, while the keyboard is up, as they were just
 * before it came: Android shows its navigation bar with the keyboard even in full screen, and padding the app by
 * that bar laid the whole page out again (the Planner's re-fitted at a new scale) as the keyboard opened and closed.
 */
@Composable
fun rememberSteadyBars(): WindowInsets {
    val bars = WindowInsets.systemBars.union(WindowInsets.displayCutout)
    val ime = WindowInsets.ime
    return remember(bars, ime) { SteadyInsets(bars, ime) }
}

private class SteadyInsets(private val base: WindowInsets, private val ime: WindowInsets) : WindowInsets {
    /** left, top, right, bottom as last seen with no keyboard up */
    private val held = IntArray(4)
    // an editor on the keyboard counts from the moment it shows: the bar can come up a frame before the keyboard does
    private fun typing(d: Density) = KeyboardLift.hold > 0 || ime.getBottom(d) > 0
    override fun getLeft(density: Density, layoutDirection: LayoutDirection): Int =
        if (typing(density)) held[0] else base.getLeft(density, layoutDirection).also { held[0] = it }
    override fun getTop(density: Density): Int =
        if (typing(density)) held[1] else base.getTop(density).also { held[1] = it }
    override fun getRight(density: Density, layoutDirection: LayoutDirection): Int =
        if (typing(density)) held[2] else base.getRight(density, layoutDirection).also { held[2] = it }
    override fun getBottom(density: Density): Int =
        if (typing(density)) held[3] else base.getBottom(density).also { held[3] = it }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.keyboardLift(): Modifier {
    val density = LocalDensity.current
    val ime = WindowInsets.ime
    // the focused field's rectangle, in this box's own (unlifted) coordinates
    var field by remember { mutableStateOf<Rect?>(null) }
    val box = remember { arrayOfNulls<LayoutCoordinates>(1) }
    // the keyboard put away: the field it was for is forgotten (a request comes before the keyboard is up)
    LaunchedEffect(ime, density) {
        var was = false
        snapshotFlow { ime.getBottom(density) > 0 }.distinctUntilChanged().collect { up ->
            if (was && !up) field = null
            was = up
        }
    }
    val margin = with(density) { 12.dp.toPx() }
    val responder = remember {
        object : BringIntoViewResponder {
            override fun calculateRectForParent(localRect: Rect): Rect = localRect
            // (a field in an editor that sits on the keyboard itself is never one to lift for, even as it goes)
            override suspend fun bringChildIntoView(localRect: () -> Rect?) { field = if (KeyboardLift.hold > 0) null else localRect() }
        }
    }
    return this
        .onGloballyPositioned { box[0] = it }
        .graphicsLayer {
            // read here, in the drawing pass: the keyboard sliding in moves one layer, nothing is composed or measured
            val kb = ime.getBottom(density)
            val f = field
            val c = box[0]
            translationY = if (kb <= 0 || f == null || KeyboardLift.hold > 0 || c == null || !c.isAttached) 0f else {
                val rootH = c.findRootCoordinates().size.height.toFloat()
                val top = c.positionInRoot().y
                val over = top + f.bottom + margin - (rootH - kb)
                // never so far that the field's own top leaves the screen
                -over.coerceIn(0f, (top + f.top - margin).coerceAtLeast(0f))
            }
        }
        .bringIntoViewResponder(responder)
}
