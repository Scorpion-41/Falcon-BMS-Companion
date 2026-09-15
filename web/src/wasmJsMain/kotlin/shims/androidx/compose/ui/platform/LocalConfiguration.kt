package androidx.compose.ui.platform

import androidx.compose.runtime.compositionLocalOf

/** Browser stand-in for android.content.res.Configuration: only the page size in dp is used (phone/tablet layouts). */
class Configuration(val screenWidthDp: Int, val screenHeightDp: Int)

/** Provided by the page from its current size, so tablets and wide browser windows get the tablet layouts. */
val LocalConfiguration = compositionLocalOf { Configuration(390, 844) }
