package com.bmscompanion.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bmscompanion.app.data.update.Updates
import com.bmscompanion.app.ui.theme.Hud

/**
 * Says a newer version is out, and which one.
 *
 * The app asks GitHub once when it starts and says nothing unless there is something to say — no dialog, no
 * interruption while a pilot is getting ready to fly. When there is, this appears in the corner and on the About
 * card, and leads to the page that downloads it.
 */
@Composable
fun UpdateBadge(onClick: () -> Unit, modifier: Modifier = Modifier, compact: Boolean = false) {
    val state by Updates.state.collectAsState()
    val version = state.latest?.version ?: return
    Row(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Hud.Amber.copy(alpha = 0.16f))
            .border(1.dp, Hud.Amber.copy(alpha = 0.65f), RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        androidx.compose.foundation.layout.Box(Modifier.size(8.dp).clip(CircleShape).background(Hud.Amber))
        Text(
            if (compact) version else "Update $version",
            color = Hud.Amber,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** The same thing as a bare dot, for sitting on top of something that already has a label. */
@Composable
fun UpdateDot(modifier: Modifier = Modifier) {
    val state by Updates.state.collectAsState()
    if (!state.available) return
    androidx.compose.foundation.layout.Box(modifier.size(9.dp).clip(CircleShape).background(Hud.Amber))
}
