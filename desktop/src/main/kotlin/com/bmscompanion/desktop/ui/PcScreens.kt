package com.bmscompanion.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Laptop
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bmscompanion.app.ui.components.Masonry
import com.bmscompanion.app.ui.screens.mission.SmallButton
import com.bmscompanion.app.ui.theme.Hud
import com.bmscompanion.app.ui.theme.LocalExtra
import com.bmscompanion.desktop.AppIcon
import com.bmscompanion.desktop.AppInfo
import com.bmscompanion.desktop.PcConfig
import com.bmscompanion.desktop.PcServer

/**
 * Server mode: the whole window is one light page for a PC that serves Android devices, browsers and client PCs.
 * The full app is not loaded here; "Open the full app" turns the same window into it.
 */
@Composable
fun ServerScreen(onOpenApp: () -> Unit, onOpenAbout: () -> Unit, onUseAsClient: () -> Unit, onHide: () -> Unit) {
    val status = rememberPcStatus()
    val running = PcServer.running.collectAsState().value
    BoxWithConstraints(Modifier.fillMaxSize().background(Hud.Bg)) {
        val narrow = maxWidth < 760.dp
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 16.dp)) {
            // header: who we are, and the one big switch to the full app
            val header: @Composable () -> Unit = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(rememberVectorPainter(AppIcon), null, Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)))
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("BMS COMPANION · SERVER MODE", style = LocalExtra.current.overline, color = Hud.Amber)
                        Text("Serving your devices", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Hud.Text)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(if (running) Hud.Green else Hud.Amber))
                            Spacer(Modifier.width(6.dp))
                            Text(if (running) "Running · version ${AppInfo.version}" else "Starting…", fontSize = 12.sp, color = Hud.TextDim)
                        }
                    }
                }
            }
            if (narrow) {
                header()
                Spacer(Modifier.height(12.dp))
                OpenAppButton(onOpenApp, Modifier.fillMaxWidth())
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { header() }
                    SmallButton(if (PcConfig.closeToTray) "Hide to tray" else "Close", null, primary = false, onClick = onHide)
                    Spacer(Modifier.width(12.dp))
                    OpenAppButton(onOpenApp, Modifier.width(390.dp))
                }
            }
            Spacer(Modifier.height(14.dp))

            // glanceable status tiles
            val i = status.info
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Tile("FALCON BMS", when { i == null -> "—"; i.demo -> "Demo mode"; i.bms.flying -> "In 3D"; i.bms.running -> "Running"; i.bms.installed -> "Not running"; else -> "Not found" },
                    i?.bms?.running == true || i?.demo == true, i?.bms?.theater, Modifier.weight(1f))
                Tile("AWACS FEED", if (i?.tacview?.connected == true) "Connected" else "Waiting", i?.tacview?.connected == true,
                    i?.tacview?.let { if (it.connected) "${it.objects} objects" else it.state }, Modifier.weight(1f))
                if (!narrow) Tile("BRIEFING", if (i?.briefing?.available == true) "Printed" else "Not yet", i?.briefing?.available == true, i?.briefing?.generated, Modifier.weight(1f))
                Tile("DEVICES", status.devices.size.toString(), status.devices.isNotEmpty(), status.devices.firstOrNull()?.let { "${it.kind} ${it.address}" } ?: "none connected", Modifier.weight(1f))
            }
            Spacer(Modifier.height(14.dp))

            Masonry(minColumn = 420.dp, maxColumns = 3) {
                // draws nothing at all when this is the newest version
                UpdateCard(onOpenAbout = onOpenAbout)
                ConnectDevicesCard(status)
                SetupChecklistCard(status)
                BmsSettingsCard()
                ClientHintCard(onUseAsClient)
                ActivityCard(status)
                StartupCard()
            }
            if (narrow) {
                Spacer(Modifier.height(12.dp))
                SmallButton(if (PcConfig.closeToTray) "Hide to tray" else "Close", null, primary = false, onClick = onHide)
            }
        }
    }
}

@Composable
private fun OpenAppButton(onClick: () -> Unit, modifier: Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(14.dp))
            .background(Brush.horizontalGradient(listOf(Hud.Amber, Color(0xFFFFC96B))))
            .clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Apps, null, tint = Hud.Bg, modifier = Modifier.size(30.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Open the full app", color = Hud.Bg, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Text("Maps, AWACS, briefing and reference on this PC", color = Hud.Bg.copy(alpha = 0.75f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = Hud.Bg)
    }
}

@Composable
private fun Tile(label: String, value: String, ok: Boolean, sub: String?, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).background(Hud.Surface)
            .border(1.dp, (if (ok) Hud.Green else Hud.Outline).copy(alpha = 0.45f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(label, style = LocalExtra.current.overline, color = Hud.TextFaint, maxLines = 1)
        Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = if (ok) Hud.Green else Hud.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(sub ?: " ", fontSize = 11.sp, color = Hud.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ClientHintCard(onUseAsClient: () -> Unit) {
    com.bmscompanion.app.ui.components.SectionCard("Is Falcon BMS on another PC?", accent = Hud.TextDim) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Laptop, null, tint = Hud.TextDim, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
            Text("On a laptop or second PC, use BMS Companion as a client: the full app with the mission from the BMS PC.",
                fontSize = 13.sp, color = Hud.TextDim, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        SmallButton("Use this PC as a client", null, primary = false, onClick = onUseAsClient)
    }
}
