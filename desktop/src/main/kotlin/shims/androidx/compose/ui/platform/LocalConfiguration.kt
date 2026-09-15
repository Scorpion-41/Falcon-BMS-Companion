package androidx.compose.ui.platform

import androidx.compose.runtime.compositionLocalOf

/** PC stand-in for android.content.res.Configuration: only the window size in dp is used (phone/tablet layouts). */
class Configuration(@JvmField val screenWidthDp: Int, @JvmField val screenHeightDp: Int)

/** Provided by the PC window from its current content size, so wide windows get the tablet layouts. */
val LocalConfiguration = compositionLocalOf { Configuration(1280, 800) }
