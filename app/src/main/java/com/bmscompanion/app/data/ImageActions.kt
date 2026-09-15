package com.bmscompanion.app.data

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Something the platform can do with an image, e.g. Share or Save on Android, Copy or Save as on the PC.
 * [run] receives the file name and a loader for the original bytes, and returns a short message to show (or null).
 */
class ImageAction(
    val label: String,
    /** share, save, copy, folder */
    val icon: String,
    val run: suspend (name: String, load: suspend () -> ByteArray?) -> String?,
)

/** Set by each platform at start-up (Android MainActivity, PC Main). */
object Platform {
    var imageActions: List<ImageAction> = emptyList()
}

/** The actions for the current screen; browser sessions on the PC provide their own. */
val LocalImageActions = staticCompositionLocalOf { Platform.imageActions }
