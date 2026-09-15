package com.bmscompanion.app.ui.screens.mission

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bmscompanion.app.data.mission.LinkState
import com.bmscompanion.app.data.mission.MissionLink
import com.bmscompanion.app.ui.components.Masonry
import com.bmscompanion.app.ui.components.SectionCard
import com.bmscompanion.app.ui.theme.Hud
import com.bmscompanion.app.ui.theme.Mono

// Browser version of app/.../ui/screens/mission/MissionSetup.kt: the page always gets its data from the PC program that
// served it, so there is nothing to connect. It shows what that PC sees, and the BMS-side setup.

@Composable
fun MissionSetupPane(onConnected: () -> Unit) {
    val state by MissionLink.state.collectAsState()
    val info by MissionLink.info.collectAsState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Masonry(minColumn = 440.dp, maxColumns = 2) {
            SectionCard("Connected through a browser", accent = Hud.Cyan) {
                StatusRow(
                    "PC",
                    when (val s = state) {
                        is LinkState.Online -> "connected to ${s.host}"
                        is LinkState.Offline -> s.reason
                        LinkState.Connecting -> "connecting…"
                        LinkState.Idle -> "idle"
                    },
                    state is LinkState.Online,
                )
                info?.let { i ->
                    StatusRow("Falcon BMS", when { i.demo -> "demo mode"; i.bms.running -> "running · ${if (i.bms.flying) "3D" else "UI"}"; i.bms.installed -> "installed, not running"; else -> "not found" }, i.bms.running || i.demo)
                    StatusRow("Theater", i.bms.theater ?: "—", i.bms.theater != null)
                    StatusRow("Briefing", if (i.briefing.available) "printed ${i.briefing.generated ?: ""}" else "not printed yet", i.briefing.available)
                    StatusRow("DTC", if (i.briefing.dtcModified > 0) "saved" else "not saved yet", i.briefing.dtcModified > 0)
                    StatusRow("AWACS feed", if (i.tacview.connected) "connected · ${i.tacview.objects} objects" else i.tacview.state, i.tacview.connected)
                    StatusRow("EZBoards", if (i.ezBoards.configured) "ready" else "folder not set", i.ezBoards.configured)
                }
                Spacer(Modifier.padding(4.dp))
                Para("The whole app runs in this browser; BMS Companion on the PC provides the data. Settings (BMS folders, AWACS feed, EZBoards) are changed on the PC.")
            }
            GuideStep(1, "Use it like an app", Hud.Amber) {
                Para("**iPhone/iPad (Safari):** Share → **Add to Home Screen**. It opens full screen with its own icon.")
                Para("**Android (Chrome):** menu ⋮ → **Add to Home screen** or **Install app**.")
                Para("Your layouts, favorites and theater choice are saved in this browser, separately from other devices.")
            }
            GuideStep(2, "Export the briefing from BMS", Hud.Green) {
                Para("In the BMS Launcher open **CONFIG → General → Briefing / Debriefing**: tick **Briefing Output to File**, untick **HTML Briefings**.")
                Para("Plan the mission, open the **Briefing** tab and press **PRINT**. Save the DTC for steerpoints, targets and presets before 3D.")
                Code("set g_nPrintToFile 1\nset g_bBriefHTML 0")
            }
            GuideStep(3, "AWACS picture (other aircraft)", Hud.Cyan) {
                Para("Add to **User\\Config\\Falcon BMS User.cfg** on the BMS PC, then turn on ACMI recording in 3D (default key **F**):")
                Code("set g_bTacviewRealTime 1\nset g_bTacviewAcmi 1")
            }
            GuideStep(4, "Troubleshooting", Hud.Red) {
                Bullet("**Page doesn't load**: same Wi-Fi as the PC (guest networks isolate devices), BMS Companion running with **Browser access** on, and its firewall button pressed.")
                Bullet("**Blank page or an update message**: the browser version needs Safari 18.2 (iOS 18.2) or newer, or a current Chrome, Edge or Firefox.")
                Bullet("**No live data**: check the status above; the PC decides where the data comes from.")
            }
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String, ok: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (ok) Hud.Green else Hud.Amber))
        Spacer(Modifier.width(8.dp))
        Text(label, Modifier.width(96.dp), fontSize = 13.sp, color = Hud.TextDim)
        Text(value, fontSize = 13.sp, color = Hud.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun GuideStep(n: Int, title: String, accent: Color, content: @Composable () -> Unit) {
    SectionCard("$n · $title", accent = accent) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@Composable
private fun Para(text: String) {
    Text(markup(text), style = MaterialTheme.typography.bodyMedium, color = Hud.TextDim, lineHeight = 20.sp)
}

@Composable
private fun Bullet(text: String) {
    Row { Text("•  ", color = Hud.TextDim); Para(text) }
}

@Composable
private fun Code(text: String) {
    Text(
        text, Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0xFF05090C)).border(1.dp, Hud.Outline.copy(alpha = 0.6f), RoundedCornerShape(8.dp)).padding(10.dp),
        fontFamily = Mono, fontSize = 13.sp, color = Hud.Green,
    )
}

private fun markup(text: String) = buildAnnotatedString {
    text.split("**").forEachIndexed { i, p -> if (i % 2 == 1) withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = Hud.Text)) { append(p) } else append(p) }
}
