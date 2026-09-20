package com.bmscompanion.app.ui.screens

import android.graphics.Bitmap
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.mission.MissionLink
import com.bmscompanion.app.ui.screens.mission.kneeboardPagePath

/**
 * Where a chart page's picture comes from.
 *
 * Nearly all of them are bundled with the app. The exception is the kneeboard UOAF's html_brief exported on the BMS
 * PC: those pages live in that tool's folder, are rendered on the PC and fetched one at a time. Everything else
 * about them is a chart — the viewer zooms, turns and closes them the same way — so they are named with a scheme
 * rather than given a screen of their own.
 *
 * This lives apart from `Charts.kt` because the PC and the browser replace that file with their own copy (mouse
 * wheel zoom), and both need to load a page the same way.
 */
const val KNEEBOARD_SCHEME = "kneeboard"

/** True for a page of the exported kneeboard rather than a bundled chart. */
fun isKneeboardPage(path: String) = path.startsWith("$KNEEBOARD_SCHEME:")

/** The picture of a chart page, bundled or rendered by the PC. */
suspend fun chartBitmap(path: String): Bitmap? {
    val page = path.substringAfter("$KNEEBOARD_SCHEME:", "").toIntOrNull() ?: return Repo.bitmap(path)
    val bytes = MissionLink.fetchBytes(kneeboardPagePath(page - 1, 2200), timeoutMs = 20_000)
    return bytes?.let { Repo.decodeBitmap(it) }
}
