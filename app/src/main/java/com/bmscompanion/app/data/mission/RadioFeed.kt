package com.bmscompanion.app.data.mission

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex

/**
 * The radio calls this device has fetched from the PC (`GET /api/radio`), for the Radio page and the Taxi page. Kept
 * for as long as the app runs: the list grows by what is new each second (`since`), and starts afresh when the PC's
 * session changes (BMS restarted, or the mission changed and every call was filed again).
 */
object RadioFeed {
    var log by mutableStateOf(RadioLog())
        private set

    /** The PC has BMS's debug log (waiting or live): the Radio tab shows by itself. Set from `BridgeInfo.radio`. */
    var logOn by mutableStateOf(false)

    private val busy = Mutex()

    fun noteInfo(info: BridgeInfo?) {
        val on = info?.radio?.state.let { it == RadioLogStatus.WAITING || it == RadioLogStatus.LIVE }
        if (on != logOn) logOn = on
    }

    /** One fetch of what is new; a second caller while one runs does nothing. */
    suspend fun refresh() {
        if (!busy.tryLock()) return
        try {
            val had = log
            // the last call is asked for again: lines BMS printed under it at the same moment (an AWACS picture's
            // groups) may have been joined to it on the PC after this device fetched it
            val r = MissionLink.radio(if (had.session.isNotEmpty()) (had.last - 1).coerceAtLeast(0) else 0L) ?: return
            log = if (r.session != had.session) r.copy(messages = r.messages.takeLast(CAP))
            else {
                val from = r.messages.minOfOrNull { it.seq } ?: Long.MAX_VALUE
                r.copy(messages = (had.messages.filter { it.seq < from } + r.messages).takeLast(CAP))
            }
        } finally {
            busy.unlock()
        }
    }

    const val CAP = 2000
}

/** Fetches the radio once a second while the composable that calls it is shown. */
@Composable
fun RadioPolling(on: Boolean = true, periodMs: Long = 1000) {
    LaunchedEffect(on) {
        if (!on) return@LaunchedEffect
        while (true) {
            RadioFeed.refresh()
            delay(periodMs)
        }
    }
}
