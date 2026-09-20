package com.bmscompanion.web

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.window.ComposeViewport
import com.bmscompanion.app.AppVersion
import com.bmscompanion.app.data.ImageAction
import com.bmscompanion.app.data.Platform
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.mission.MissionLink
import com.bmscompanion.app.ui.AppRoot
import com.bmscompanion.app.ui.theme.BmsTheme
import kotlinx.browser.document
import kotlinx.browser.window

/** Which version last ran in this browser; a change means everything it kept is from an older app. */
private const val VERSION_KEY = "app_version"

/**
 * BMS Companion in a browser (iPhone, iPad, any phone, tablet or computer). The whole app runs on the device;
 * the PC program that serves this page provides the bundled data (/assets) and the mission API (/api).
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    // A browser that has run an older version may still be holding that version's files: they are thrown away the
    // first time a new version runs here. The settings are not — a pilot's Dashboard layouts, map look and the PC
    // they connect to survive an update, the same way they do on the PC and on Android.
    val seen = storageGet(VERSION_KEY)
    if (seen != AppVersion.NAME) {
        clearBrowserCaches()
        storageSet(VERSION_KEY, AppVersion.NAME)
    }
    Repo.init()
    Platform.imageActions = listOf(
        ImageAction("Download to this device", "download") { name, _ ->
            downloadUrl(MissionLink.mediaFileUrl(name), name)
            "Downloading $name"
        },
        ImageAction("Share", "share") { name, _ ->
            if (!canShareFiles()) "Sharing is not supported by this browser: use Download"
            else shareUrl(MissionLink.mediaFileUrl(name), name).ifEmpty { null }
        },
    )
    Platform.openUrl = { openInNewTab(it) }
    // The browser cannot install anything, but it can still say a newer version is out and show its notes:
    // Platform.installer stays null, which is what turns the About card into a link to the release.
    Platform.fetchText = { url -> httpText(url) }
    Platform.nowMillis = { nowMillis().toLong() }
    // ?route=mission opens a page directly (bookmarks, home-screen shortcuts)
    val query = window.location.search.removePrefix("?").split('&')
    val startRoute = query.firstOrNull { it.startsWith("route=") }?.substringAfter('=')?.let(::decodeUriComponent)
    // The board layout (for a VR kneeboard program such as OpenKneeboard) can be asked for three ways, because a
    // program that is handed a URL may keep only part of it: the address /kneeboard, ?kneeboard=1, or #kneeboard.
    // A path survives everything, which is why it is the one the setup guides give out.
    // The address decides, every time the page loads: the ordinary address is the ordinary site even on a browser
    // that opened the board earlier, and the board's address is the board even if nothing was remembered.
    // /kneeboard/3 is board three; /kneeboard on its own is the board with the menu on it
    val slot = Regex("/kneeboard/(\\d{1,2})$").find(window.location.pathname.trimEnd('/'))?.groupValues?.get(1)?.toIntOrNull()
    com.bmscompanion.app.ui.Kneeboard.useSlot(slot)
    val asked = slot != null ||
        window.location.pathname.trimEnd('/').endsWith("/kneeboard") ||
        window.location.hash.removePrefix("#") == "kneeboard" ||
        query.firstOrNull { it.startsWith("kneeboard=") }?.substringAfter('=')?.let { it != "0" } == true
    com.bmscompanion.app.ui.Kneeboard.set(asked)
    if (asked) {
        // only a VR board can be reshaped, and only the page can ask: the ☰ menu's shape list appears because of this
        com.bmscompanion.app.ui.Kneeboard.askShape = { w, h -> askBoardPixelSize(w, h) }
        com.bmscompanion.app.ui.Kneeboard.applyShape()
        // and the sections as pages, so the board can be flipped through without a pointer
        com.bmscompanion.app.ui.Kneeboard.publishPages = { slot, want, w, h -> okbPublishPages(slot, want, w, h) }
        com.bmscompanion.app.ui.Kneeboard.takePage = { okbTakePage() }
        com.bmscompanion.app.ui.Kneeboard.setTitle = { t -> setDocumentTitle(t) }
        com.bmscompanion.app.ui.Kneeboard.lastError = { okbLastError() }
        com.bmscompanion.app.ui.Kneeboard.roundCorners = { px -> setBoardCorner(px) }
    }
    ComposeViewport(document.body!!) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            CompositionLocalProvider(LocalConfiguration provides Configuration(maxWidth.value.toInt(), maxHeight.value.toInt())) {
                BmsTheme { AppRoot(startRoute) }
            }
        }
    }
    hideSplash()
}

