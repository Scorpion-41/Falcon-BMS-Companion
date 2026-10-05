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

    /**
     * Encodes a picture the app drew as PNG bytes. The Planner's Upd Kneeboard draws each page on the device
     * and sends it to the PC this way (R3-PLAN A17). Android compresses its Bitmap; the PC and the browser encode
     * through Skia. Null where nothing set it; the function itself answers null when the encoder fails.
     */
    var encodePng: ((androidx.compose.ui.graphics.ImageBitmap) -> ByteArray?)? = null

    /**
     * Encodes a picture the app drew as JPEG bytes at a quality of 0 to 100: what Weapon Delivery Planner saves its
     * pictures as (the attack pages' Save Map, the airport schedule), so a file saved here opens where WDP's does.
     * Android compresses its Bitmap; the PC and the browser encode through Skia. Null where nothing set it (the caller
     * then saves PNG bytes); the function itself answers null when the encoder fails.
     */
    var encodeJpeg: ((androidx.compose.ui.graphics.ImageBitmap, Int) -> ByteArray?)? = null

    /**
     * Decodes a picture file's bytes (JPEG, PNG, BMP, GIF, WebP), for a picture the pilot picks on the BMS PC (the
     * Coordination Card's plan pictures, which WDP opens from a file). Null where nothing set it; the function itself
     * answers null for bytes that are not a picture.
     */
    var decodeImage: ((ByteArray) -> androidx.compose.ui.graphics.ImageBitmap?)? = null

    /**
     * The device is worked by finger first: Android with a touch screen, a browser on a phone or a tablet. The Planner
     * lays itself out for fingers and opens its big editors on a finger's tap (`WdpTouch`); a mouse click is never
     * taken for a finger's, whatever this says. False on the PC, where a finger on a touch screen is noticed as it lands.
     */
    var touchFirst: Boolean = false

    /**
     * Puts a line of text on the clipboard, where Compose's own clipboard cannot: the browser (the page's
     * `navigator.clipboard`, else a hidden text area and the copy command, which also works on a plain http address).
     * Null on Android and the PC, where `LocalClipboardManager` does it.
     */
    var copyText: ((String) -> Unit)? = null
}

/** The actions for the current screen; browser sessions on the PC provide their own. */
val LocalImageActions = staticCompositionLocalOf { Platform.imageActions }
