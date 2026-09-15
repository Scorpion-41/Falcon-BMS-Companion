package com.bmscompanion.web

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.window.ComposeViewport
import com.bmscompanion.app.data.ImageAction
import com.bmscompanion.app.data.Platform
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.mission.MissionLink
import com.bmscompanion.app.ui.AppRoot
import com.bmscompanion.app.ui.theme.BmsTheme
import kotlinx.browser.document
import kotlinx.browser.window

/**
 * BMS Companion in a browser (iPhone, iPad, any phone, tablet or computer). The whole app runs on the device;
 * the PC program that serves this page provides the bundled data (/assets) and the mission API (/api).
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
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
    // ?route=mission opens a page directly (bookmarks, home-screen shortcuts)
    val startRoute = window.location.search.removePrefix("?").split('&').firstOrNull { it.startsWith("route=") }?.substringAfter('=')?.let(::decodeUriComponent)
    ComposeViewport(document.body!!) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            CompositionLocalProvider(LocalConfiguration provides Configuration(maxWidth.value.toInt(), maxHeight.value.toInt())) {
                BmsTheme { AppRoot(startRoute) }
            }
        }
    }
    hideSplash()
}
