package com.bmscompanion.web

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.platform.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.withTimeoutOrNull
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
    // the Planner's Upd Kneeboard draws each page here and sends it to the PC as PNG, encoded through Skia
    Platform.encodePng = { img ->
        runCatching {
            val skia = org.jetbrains.skia.Image.makeFromBitmap(img.asSkiaBitmap())
            try { skia.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)?.bytes } finally { skia.close() }
        }.getOrNull()
    }
    // WDP's pictures are JPEG files (Save Map, the airport schedule), and a plan picture is opened from one
    Platform.encodeJpeg = { img, quality ->
        runCatching {
            val skia = org.jetbrains.skia.Image.makeFromBitmap(img.asSkiaBitmap())
            try { skia.encodeToData(org.jetbrains.skia.EncodedImageFormat.JPEG, quality.coerceIn(0, 100))?.bytes } finally { skia.close() }
        }.getOrNull()
    }
    Platform.decodeImage = { bytes ->
        runCatching { org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()
    }
    // a phone or a tablet: its main pointer is a finger (a laptop with a touch screen has a fine one, a mouse or a pad)
    Platform.touchFirst = touchFirst()
    // the airfield page's divert figures copy on a tap; Compose's own clipboard does not reach the browser's
    Platform.copyText = { copyToClipboard(it) }
    // Compose for Web hands the maps a wheel event's raw deltaY: about 100 a notch in pixels (Chrome, Edge, Safari),
    // 3 in lines (Firefox). The unit is read off each event's deltaMode, so a notch zooms the same in every browser.
    watchWheelMode()
    com.bmscompanion.app.ui.components.ZoomMath.wheelUnit = {
        when (wheelMode()) { 1 -> 3f; 2 -> 1f; else -> 100f }
    }
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
        // The browser has no system fonts to fall back on: the one font Compose for Web draws with lacks the arrows,
        // triangles, squares and ticks the app writes (◀ ▶ ◂ ▸ ▾ ■ ▲ ▼ ✓ ✕ ☰ ⋮ …), which came out as empty boxes.
        // A font holding them is preloaded before the app is shown, and Skia then takes any glyph the first font
        // lacks from it. A font that does not arrive within a few seconds only leaves those boxes, never a blank page.
        val fonts = LocalFontFamilyResolver.current
        var ready by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            withTimeoutOrNull(4_000) { loadFallbackFont(fonts) }
            ready = true
            hideSplash()
        }
        if (ready) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                CompositionLocalProvider(LocalConfiguration provides Configuration(maxWidth.value.toInt(), maxHeight.value.toInt())) {
                    BmsTheme { AppRoot(startRoute) }
                }
            }
        }
    }
}

/**
 * The symbols font (`/fonts/SymbolsFallback.ttf`, a subset of DejaVu Sans: arrows, maths, geometric shapes, block
 * elements, miscellaneous symbols and dingbats), preloaded so that Skia falls back on it for any glyph the default
 * font does not have. Nothing throws: without it the app still runs, with those glyphs as boxes.
 */
/**
 * Whether this browser's main pointer is a finger: `(pointer: coarse)` (a phone, a tablet), or a touch screen with no
 * fine pointer at all. A laptop with a touch screen and a mouse or a pad answers false; a finger there is noticed as
 * it lands (`WdpTouch`).
 */
@JsFun(
    """() => {
    try {
        const m = (q) => !!(window.matchMedia && window.matchMedia(q).matches);
        return m('(pointer: coarse)') || ((navigator.maxTouchPoints || 0) > 0 && !m('(any-pointer: fine)'));
    } catch (e) { return false; }
}""",
)
private external fun touchFirst(): Boolean

/**
 * A line of text onto the clipboard: `navigator.clipboard` where the page may use it (https, localhost), else a hidden
 * text area and the copy command, which a plain http address on the LAN still allows inside a tap. Nothing throws.
 */
@JsFun(
    """(t) => {
    const legacy = () => {
        try {
            const a = document.createElement('textarea');
            a.value = t; a.setAttribute('readonly', ''); a.style.position = 'fixed'; a.style.opacity = '0';
            document.body.appendChild(a); a.select(); document.execCommand('copy'); a.remove();
        } catch (e) { }
    };
    try {
        if (navigator.clipboard && window.isSecureContext) navigator.clipboard.writeText(t).catch(legacy);
        else legacy();
    } catch (e) { legacy(); }
}""",
)
private external fun copyToClipboard(text: String)

/**
 * Notes each wheel event's `deltaMode` before the page's own listener reads its delta (capture phase on the window):
 * 0 pixels, 1 lines, 2 pages. Reading the mode first also keeps Firefox in its line mode for that event, so the delta
 * Compose passes on and the unit [wheelMode] answers always agree.
 */
@JsFun(
    """() => {
    try {
        if (window.__bmscWheelWatch) return;
        window.__bmscWheelWatch = true;
        window.__bmscWheelMode = 0;
        window.addEventListener('wheel', (e) => { try { window.__bmscWheelMode = e.deltaMode | 0; } catch (x) {} }, { capture: true, passive: true });
    } catch (e) {}
}""",
)
private external fun watchWheelMode()

@JsFun("() => { try { return (window.__bmscWheelMode | 0); } catch (e) { return 0; } }")
private external fun wheelMode(): Int

private suspend fun loadFallbackFont(fonts: FontFamily.Resolver) {
    val bytes = runCatching { httpBytes("/fonts/SymbolsFallback.ttf") }.getOrNull() ?: return
    if (bytes.size < 1024) return
    runCatching { fonts.preload(FontFamily(Font("BMSC Symbols", bytes))) }
}

