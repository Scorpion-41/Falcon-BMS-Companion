package com.bmscompanion.app.ui.screens.mission

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bmscompanion.app.data.mission.FoundBridge
import com.bmscompanion.app.data.mission.LinkState
import com.bmscompanion.app.data.mission.MissionLink
import com.bmscompanion.app.ui.components.Masonry
import com.bmscompanion.app.ui.components.SectionCard
import com.bmscompanion.app.ui.theme.Hud
import com.bmscompanion.app.ui.theme.LocalExtra
import com.bmscompanion.app.ui.theme.Mono
import kotlinx.coroutines.launch

@Composable
fun MissionSetupPane(onConnected: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Masonry(minColumn = 440.dp, maxColumns = 2) {
            ConnectionCard(onConnected)
            StatusCard()
            GuideStep(1, "Run the bridge on the BMS PC", Hud.Amber) {
                Para("Download **BMSCompanionBridge.exe** from the project's GitHub Releases page and start it on the PC that runs Falcon BMS. Keep it open (it minimises to the tray).")
                Para("On first launch the bridge opens its own **Setup guide**, which checks every step below for you (✔/✖) and has buttons to fix things. Reopen it from the bridge window or the tray menu.")
                Para("When Windows asks, allow it on **private networks**, or press **Allow through Windows Firewall** in the bridge window. It needs TCP **47474** and UDP **47475**, local network only.")
                Para("No BMS handy? Tick **Demo mode** in the bridge to try every screen with a fake mission.")
            }
            GuideStep(2, "Connect this device", Hud.Amber) {
                Para("Put the tablet or phone on the same Wi-Fi/LAN as the PC, then tap **Find bridge** above.")
                Para("If nothing is found (some routers block broadcasts), type the PC address shown in the bridge window, e.g. **192.168.1.20** port **47474**.")
            }
            GuideStep(3, "Export the briefing from BMS", Hud.Green) {
                Para("In the BMS Launcher open **CONFIG → General → Briefing / Debriefing**:")
                Check(true, "Briefing Output to File (enables the PRINT button)")
                Check(false, "HTML Briefings (must be off: the bridge and EZBoards read the text file)")
                Check(null, "Append New Briefings is optional; the app always uses the newest one")
                Para("Plan the mission, open the **Briefing** tab and press **PRINT** (top right). BMS writes User\\Briefings\\briefing.txt and the app updates within a second.")
                Code("set g_nPrintToFile 1\nset g_bBriefHTML 0")
            }
            GuideStep(4, "Save the DTC", Hud.Green) {
                Para("In the **DTC** screen set target steerpoints, PPTs and the comm plan, then press **SAVE**. That writes User\\Config\\<callsign>.ini, which gives the app steerpoint coordinates, targets, threat rings and radio presets before you enter 3D.")
            }
            GuideStep(5, "Live flight data", Hud.Cyan) {
                Para("Nothing to configure. BMS always publishes shared memory, and position, fuel, RWR, DED, bullseye and steerpoints stream as soon as you are in the 3D world.")
            }
            GuideStep(6, "AWACS picture (other aircraft)", Hud.Cyan) {
                Para("Other aircraft come from BMS's built-in **Tacview real-time telemetry** server (Tacview itself is not needed). Add to **User\\Config\\Falcon BMS User.cfg**:")
                Code("set g_bTacviewRealTime 1\nset g_bTacviewAcmi 1")
                Para("The stream only runs while **ACMI recording** is on: start it in the cockpit (default key **F**) or enable recording in the Launcher.")
                Para("Multiplayer: the host must allow it (**g_bMPTacviewRtAllowedByServer 1**). If you set **g_sTacviewPassword**, enter the same password in the bridge.")
            }
            GuideStep(7, "EZBoards kneeboards", Hud.Magenta) {
                Para("EZBoards ships with BMS 4.38 in **Tools\\EZBoards** and needs the **.NET 8 runtime**. The bridge finds it automatically; if you keep it elsewhere, pick the folder in the bridge settings.")
                Para("After PRINT, tap **Generate kneeboards** on the Boards tab. The console runs hidden on the PC and you get a success or error message here. You can also tick **Run EZBoards automatically when the briefing is printed**.")
                Para("To see the kneeboards in the cockpit, enable the 3D pilot model (Setup → Graphics → Pilot Model).")
            }
            GuideStep(8, "Troubleshooting", Hud.Red) {
                Bullet("**No link / timed out**: the bridge isn't running, the firewall blocks it, or the device is on a guest or other network.")
                Bullet("**Briefing empty**: PRINT not pressed, HTML briefings still on, or g_sBriefingsDirectory points somewhere else (the bridge follows it).")
                Bullet("**No AWACS feed**: check g_bTacviewRealTime and that ACMI recording is running in 3D.")
                Bullet("**EZBoards fails**: open the log on the Boards tab. Common causes are a missing .NET 8 runtime or no briefing printed.")
                Bullet("**Map shows the wrong theater**: the app follows the theater BMS reports; add-on theaters use their base map.")
            }
        }
    }
}

@Composable
private fun ConnectionCard(onConnected: () -> Unit) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val state by MissionLink.state.collectAsState()
    var host by remember { mutableStateOf(MissionLink.host ?: "") }
    var port by remember { mutableStateOf(MissionLink.port.toString()) }
    var searching by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<List<FoundBridge>?>(null) }
    var testing by remember { mutableStateOf(false) }
    var testMsg by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    fun connect(h: String, p: Int) {
        focus.clearFocus()
        testing = true
        testMsg = null
        scope.launch {
            val r = MissionLink.probe(h, p)
            testing = false
            r.onSuccess { info ->
                MissionLink.setBridge(h, p)
                testMsg = true to "Connected to ${info.host} (bridge ${info.version})"
                onConnected()
            }.onFailure { testMsg = false to "Could not reach $h:$p. Check that the bridge is running and the firewall allows it." }
        }
    }

    SectionCard("PC bridge connection", accent = Hud.Amber) {
        val (dot, text) = when (val s = state) {
            is LinkState.Online -> Hud.Green to "Connected to ${s.host}:${MissionLink.port}"
            is LinkState.Offline -> Hud.Red to "Can't reach ${s.host}: ${s.reason}"
            LinkState.Connecting -> Hud.Amber to "Connecting…"
            LinkState.Idle -> Hud.TextFaint to (MissionLink.host?.let { "Saved: $it:${MissionLink.port}" } ?: "Not set up yet")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(8.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = Hud.Text)
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            ActionButton(if (searching) "Searching…" else "Find bridge", primary = true, enabled = !searching) {
                searching = true
                found = null
                scope.launch {
                    found = MissionLink.discover(context)
                    searching = false
                    found?.singleOrNull()?.let { f -> host = f.host; port = f.port.toString(); connect(f.host, f.port) }
                }
            }
            if (searching) { Spacer(Modifier.width(10.dp)); CircularProgressIndicator(Modifier.size(20.dp), color = Hud.Amber, strokeWidth = 2.dp) }
        }
        found?.let { list ->
            Spacer(Modifier.height(8.dp))
            if (list.isEmpty()) Text("No bridge answered. Enter the PC address manually below.", color = Hud.Amber, fontSize = 13.sp)
            list.forEach { f ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(10.dp)).background(Hud.Surface2).clickable { host = f.host; port = f.port.toString(); connect(f.host, f.port) }.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(f.name, fontWeight = FontWeight.SemiBold)
                        Text("${f.host}:${f.port} · bridge ${f.version}", style = LocalExtra.current.monoSmall, color = Hud.TextDim)
                    }
                    Text("Connect ›", color = Hud.Amber, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("Or enter the address manually", style = LocalExtra.current.overline, color = Hud.TextFaint)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Hud.Amber, unfocusedBorderColor = Hud.Outline, cursorColor = Hud.Amber)
            OutlinedTextField(host, { host = it.trim() }, Modifier.weight(1f), singleLine = true, label = { Text("PC IP address") }, colors = colors,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, Modifier.width(96.dp), singleLine = true, label = { Text("Port") }, colors = colors,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton(if (testing) "Connecting…" else "Connect", primary = false, enabled = host.isNotBlank() && !testing) { connect(host, port.toIntOrNull() ?: 47474) }
            if (MissionLink.host != null) ActionButton("Forget", primary = false, enabled = true) { MissionLink.forget(); testMsg = null }
        }
        testMsg?.let { (ok, msg) -> Text(msg, color = if (ok) Hud.Green else Hud.Red, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
    }
}

@Composable
private fun StatusCard() {
    val info by MissionLink.info.collectAsState()
    val i = info ?: return
    SectionCard("What the bridge sees", accent = Hud.Green) {
        Row2("Bridge", "${i.version} on ${i.host}${if (i.demo) " (demo mode)" else ""}", true)
        Row2("Falcon BMS", when { i.demo -> "demo"; i.bms.running -> "running ${i.bms.version ?: ""} · ${if (i.bms.flying) "3D" else "UI"}"; i.bms.installed -> "installed, not running"; else -> "not found" }, i.bms.running)
        Row2("Theater", i.bms.theater ?: "—", i.bms.theater != null)
        Row2("Briefing", if (i.briefing.available) "printed ${i.briefing.generated ?: ""}" else "not printed yet", i.briefing.available)
        Row2("DTC file", if (i.briefing.dtcModified > 0) "found" else "not saved yet", i.briefing.dtcModified > 0)
        Row2("AWACS feed", if (i.tacview.connected) "connected · ${i.tacview.objects} objects" else i.tacview.state, i.tacview.connected)
        Row2("EZBoards", if (i.ezBoards.configured) i.ezBoards.path ?: "ok" else "folder not set", i.ezBoards.configured)
    }
}

@Composable
private fun Row2(label: String, value: String, ok: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (ok) Hud.Green else Hud.Amber))
        Spacer(Modifier.width(8.dp))
        Text(label, Modifier.width(96.dp), fontSize = 13.sp, color = Hud.TextDim)
        // single line: status text changes every poll and must not resize the cards below
        Text(value, fontSize = 13.sp, color = Hud.Text, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}

@Composable
private fun ActionButton(text: String, primary: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Text(
        text,
        Modifier.clip(RoundedCornerShape(10.dp))
            .background(if (primary && enabled) Hud.Amber else if (primary) Hud.Surface3 else Color.Transparent)
            .border(1.dp, if (primary) Color.Transparent else Hud.Amber.copy(alpha = if (enabled) 0.7f else 0.25f), RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        color = if (primary) (if (enabled) Hud.Bg else Hud.TextFaint) else if (enabled) Hud.Amber else Hud.TextFaint,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun GuideStep(n: Int, title: String, accent: Color, content: @Composable () -> Unit) {
    SectionCard("$n · $title", accent = accent) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

/** Paragraph with **bold** markup. */
@Composable
private fun Para(text: String) {
    Text(markup(text), style = MaterialTheme.typography.bodyMedium, color = Hud.TextDim, lineHeight = 20.sp)
}

@Composable
private fun Bullet(text: String) {
    Row { Text("•  ", color = Hud.TextDim); Para(text) }
}

@Composable
private fun Check(on: Boolean?, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            when (on) { true -> "☑"; false -> "☐"; null -> "◇" }, fontSize = 18.sp,
            color = when (on) { true -> Hud.Green; false -> Hud.Red; null -> Hud.TextFaint },
        )
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Hud.Text)
    }
}

@Composable
private fun Code(text: String) {
    Text(
        text, Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0xFF05090C)).border(1.dp, Hud.Outline.copy(alpha = 0.6f), RoundedCornerShape(8.dp)).padding(10.dp),
        fontFamily = Mono, fontSize = 13.sp, color = Hud.Green,
    )
}

private fun markup(text: String) = buildAnnotatedString {
    val parts = text.split("**")
    parts.forEachIndexed { i, p -> if (i % 2 == 1) withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = Hud.Text)) { append(p) } else append(p) }
}
