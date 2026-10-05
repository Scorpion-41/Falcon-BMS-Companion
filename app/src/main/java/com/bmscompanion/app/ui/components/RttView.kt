package com.bmscompanion.app.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.bmscompanion.app.data.mission.RttFrame
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.bmscompanion.app.data.Perf
import com.bmscompanion.app.data.mission.MissionLink
import com.bmscompanion.app.data.mission.RttState
import kotlinx.coroutines.delay
import kotlin.time.TimeSource

/**
 * One of Falcon BMS's own cockpit displays, drawn live.
 *
 * The client asks for a frame, draws it, and asks again: there is no stream to fall behind and nothing to
 * resynchronise — a tablet on a slow link simply gets fewer frames, which on an MFD is a picture that updates more
 * slowly rather than one that breaks. Each request quotes the picture already on screen, and a display that has not
 * changed answers with nothing at all (see `MissionLink.rttFrame`): on the PC's own window that is a comparison of
 * two small rectangles, on a phone a 204 with no body and no decode.
 *
 * [fps] is what to aim for, not a promise. The wait is measured from the start of each request, so a fast link gets
 * the rate asked for; a slow one waits at least a quarter of the gap between requests, so it never queues them.
 * `Perf.easy` halves it for a machine being spared.
 *
 * [width] 0 asks for the size that fits the view on this device, in whole steps so two devices of nearly the same
 * size share the PC's encoding; the PC's own window ignores it and draws BMS's pixels as they are.
 */
/**
 * Where [RttView] gets its frames. Null (the app) asks `MissionLink`; `--mfdrender` provides pictures of its own so
 * the live state can be drawn headless without a BMS or a link.
 */
val LocalRttSource = staticCompositionLocalOf<(suspend (display: String, width: Int, since: Long) -> RttFrame?)?> { null }

@Composable
fun RttView(
    display: String,
    modifier: Modifier = Modifier,
    fps: Int = 10,
    width: Int = 0,
    contentScale: ContentScale = ContentScale.Fit,
) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val ask = if (width > 0) width else ((constraints.maxWidth + 127) / 128 * 128).coerceIn(128, 1024)
        var frame by remember(display) { mutableStateOf<Bitmap?>(null) }
        val target = (if (Perf.easy) maxOf(1, fps / 2) else fps).coerceIn(1, 60)

        val source = LocalRttSource.current
        LaunchedEffect(display, ask, target) {
            val gap = 1000L / target
            var tag = 0L
            while (true) {
                val started = TimeSource.Monotonic.markNow()
                val f = if (source != null) source(display, ask, tag) else MissionLink.rttFrame(display, ask, tag)
                // A frame that does not arrive leaves the last one on screen rather than blinking to nothing: BMS
                // pausing for a second is not a reason to take the pilot's MFD away.
                if (f != null) {
                    f.image?.let { frame = it }
                    tag = f.tag
                }
                val spent = started.elapsedNow().inWholeMilliseconds
                delay(if (f == null) 1000L else maxOf(gap - spent, gap / 4))
            }
        }

        frame?.let { Image(it.asImageBitmap(), display, Modifier.fillMaxSize(), contentScale = contentScale) }
    }
}

/**
 * Keeps the display-export state fresh for whoever is showing one.
 *
 * Asked for every couple of seconds — it changes when BMS starts, when the pilot enters 3D and when the setting is
 * switched, none of which happen four times a second, but a pilot who has just entered the cockpit should see the
 * glass change within a breath rather than a count of four. [enabled] false asks nothing at all.
 */
@Composable
fun rememberRttState(everyMs: Long = 2500, enabled: Boolean = true): RttState? {
    var state by remember { mutableStateOf<RttState?>(null) }
    LaunchedEffect(enabled) {
        if (!enabled) return@LaunchedEffect
        while (true) {
            state = MissionLink.rttState()
            delay(everyMs)
        }
    }
    return state
}
