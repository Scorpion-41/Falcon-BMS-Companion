package com.bmscompanion.app.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * How hard the app works the machine it is running on.
 *
 * The app is not a heavy program, but what it draws is: a theater map redrawn four times a second, with its tiles,
 * its landmark layers and a few hundred measured labels. On a machine whose graphics are a decade old — an Intel HD
 * of the kind that is still perfectly good for everything else on a second monitor beside Falcon BMS — that is the
 * difference between a live picture and a slide show.
 *
 * [easy] is one switch over all of it rather than a page of sliders, because a pilot who has a slow window wants it
 * to stop being slow, not to learn which of six settings costs the most. It slows the live data to one reading a
 * second, which is what actually drives the redraws: nothing on a theater map moves far in a second, and a jet at
 * 500 kt has crossed 850 ft — less than the width of the symbol drawn for it.
 */
object Perf {
    /** Quarter of the live updates, for a machine whose graphics cannot keep up with four a second. */
    var easy by mutableStateOf(Repo.getInt("perf_easy", 0) == 1)
        private set

    /** How long to wait between readings of the live data. */
    val liveMs: Long get() = if (easy) 1000L else 250L

    /** How many live readings there are to a round of the slower calls, so a round stays about two seconds. */
    val livePerRound: Int get() = if (easy) 2 else 8

    fun goEasy(on: Boolean) {
        easy = on
        Repo.putInt("perf_easy", if (on) 1 else 0)
    }
}
