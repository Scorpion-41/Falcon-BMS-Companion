package com.bmscompanion.app.ui.screens.mission

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bmscompanion.app.data.mission.HostileContacts
import com.bmscompanion.app.data.mission.LinkState
import com.bmscompanion.app.data.mission.MissionLink
import com.bmscompanion.app.ui.components.SectionCard
import com.bmscompanion.app.ui.theme.Hud

/**
 * Whether the PC sends hostile contacts from the live feed ([HostileContacts]; off by default). Every page that shows
 * the enemy from the feed asks this, and says [HostileContacts.OFF] rather than "none" while it is off.
 */
@Composable
fun rememberHostilesOn(): Boolean {
    val info by MissionLink.info.collectAsState()
    return HostileContacts.on(info)
}

/**
 * The setting itself, on all three Setup pages: one switch, kept on the PC for every device and VR board
 * (`POST /api/contacts/hostiles`).
 */
@Composable
fun HostilesCard() {
    val info by MissionLink.info.collectAsState()
    val link by MissionLink.state.collectAsState()
    val linked = link is LinkState.Online && info != null
    val on = HostileContacts.on(info)
    SectionCard("Live picture", accent = Hud.Cyan) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(HostileContacts.TITLE, color = Hud.Text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(HostileContacts.LINE, color = Hud.TextDim, fontSize = 12.sp)
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = on, enabled = linked, onCheckedChange = { MissionLink.setHostiles(it) })
        }
        if (!linked) Text(
            "Connect to BMS Companion on the PC to change it: the setting is kept there.",
            color = Hud.Amber, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp),
        )
    }
}
