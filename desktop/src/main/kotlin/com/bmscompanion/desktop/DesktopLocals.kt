package com.bmscompanion.desktop

import androidx.compose.runtime.staticCompositionLocalOf

/** Window actions the app screens can trigger in the PC program. */
class PcActions(
    /** Turns the window into the light server page. */
    val switchToServer: () -> Unit,
    /** Borderless full screen on the window's monitor (F11). */
    val toggleFullscreen: () -> Unit,
    val isFullscreen: () -> Boolean,
)

val LocalPcActions = staticCompositionLocalOf { PcActions({}, {}, { false }) }
