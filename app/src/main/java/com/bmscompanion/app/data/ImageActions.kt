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

/** Set by each platform at start-up (Android MainActivity, PC Main, browser Main). */
object Platform {
    var imageActions: List<ImageAction> = emptyList()

    /** Opens a link outside the app (the browser on Android and the PC, a new tab in the browser version). */
    var openUrl: ((String) -> Unit)? = null

    /**
     * Fetches a URL as text, for the few things the app reads straight from the internet rather than from the PC:
     * the release list and its checksums. Null where there is no way to (or no need to) reach out.
     */
    var fetchText: (suspend (String) -> String)? = null

    /** Downloading and running an update, where the platform can do that. Null in the browser. */
    var installer: com.bmscompanion.app.data.update.Installer? = null

    /** The clock. A platform hook because the browser has no java.lang.System to read it from. */
    var nowMillis: () -> Long = { 0L }
}

/** The actions for the current screen; browser sessions on the PC provide their own. */
val LocalImageActions = staticCompositionLocalOf { Platform.imageActions }
