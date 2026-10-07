package androidx.activity.compose

import androidx.compose.runtime.Composable

/**
 * Stand-in for Android's system Back handler, so a shared screen can hand Back to its own pane (the Reference
 * section's airfield list, for one). There is no system Back here: the screen's own back arrow does the same.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun BackHandler(enabled: Boolean = true, onBack: () -> Unit) = Unit
