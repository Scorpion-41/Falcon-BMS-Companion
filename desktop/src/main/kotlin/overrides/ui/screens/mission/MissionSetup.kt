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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.mission.FoundBridge
import com.bmscompanion.app.data.mission.LinkState
import com.bmscompanion.app.data.mission.MissionLink
import com.bmscompanion.app.ui.components.Masonry
import com.bmscompanion.app.ui.components.SectionCard
import com.bmscompanion.app.ui.theme.Hud
import com.bmscompanion.app.ui.theme.LocalExtra
import com.bmscompanion.app.ui.theme.Mono
import com.bmscompanion.desktop.LocalPcActions
import com.bmscompanion.desktop.PcConfig
import com.bmscompanion.desktop.ui.BmsSettingsCard
import com.bmscompanion.desktop.ui.ConnectDevicesCard
import com.bmscompanion.desktop.ui.DataSourceCard
import com.bmscompanion.desktop.ui.PcStatusCard
import com.bmscompanion.desktop.ui.SetupChecklistCard
import com.bmscompanion.desktop.ui.StartupCard
import com.bmscompanion.desktop.ui.rememberPcStatus
import kotlinx.coroutines.launch

// PC version of app/.../ui/screens/mission/MissionSetup.kt. BMS Companion for Windows is one program (app + reading Falcon BMS +
// server for devices): this tab says where Falcon BMS runs, holds the settings and shows the guides for every kind of device.

@Composable
fun MissionSetupPane(onConnected: () -> Unit) {
    val actions = LocalPcActions.current
    val status = rememberPcStatus()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Masonry(minColumn = 440.dp, maxColumns = 2) {
            DataSourceCard(onSwitchToServer = actions.switchToServer)
            PcStatusCard(status)
            if (!PcConfig.bmsOnThisPc) RemoteConnectionCard(onConnected)
            if (PcConfig.bmsOnThisPc) SetupChecklistCard(status)
            ConnectDevicesCard(status)
            if (PcConfig.bmsOnThisPc) BmsSettingsCard()
            StartupCard()
            GuideStep(1, "Which devices, which files", Hud.Amber) {
                Bullet("**PC with Falcon BMS**: install **BMS Companion** (this program). Use the full app here, or the light **server page** when it only serves other devices.")
                Bullet("**Android phone or tablet**: BMS Companion on the BMS PC + the Android app (APK).")
                Bullet("**iPhone, iPad or any browser**: BMS Companion on the BMS PC with **Browser access** on. Nothing to install on the device.")
                Bullet("**Laptop or second PC**: BMS Companion on both. On the laptop open the full app and choose **On another PC** above.")
            }
            GuideStep(2, "Android phones and tablets", Hud.Amber) {
                Para("Install **BMS-Companion.apk** (allow installing unknown apps). Put the device on the same Wi-Fi/LAN as the BMS PC.")
                Para("In the app open **Mission → Setup → Find BMS PC**. If nothing is found, enter the BMS PC's address and port **47474**.")
                Para("When Windows asks the first time, allow network access on **private networks**, or press **Allow through Windows Firewall** (TCP 47474 and UDP 47475, local network only).")
            }
            GuideStep(3, "iPhone, iPad and browsers", Hud.Cyan) {
                Para("Keep **Browser access** on. Scan the QR code with the camera or open the address shown (e.g. **http://192.168.1.20:47474**) in Safari or any browser.")
                Para("The whole app runs in the browser (Safari 18.2 / iOS 18.2 or newer, or a current Chrome, Edge or Firefox). On iPhone/iPad, **Share → Add to Home Screen** gives a full-screen icon.")
            }
            GuideStep(4, "Laptop or second PC as a client", Hud.Cyan) {
                Para("On the BMS PC run BMS Companion (full app or server page) and press **Allow through Windows Firewall**.")
                Para("On the laptop install BMS Companion, open the full app, choose **On another PC** above, then **Find BMS PC** or type the BMS PC's address and port **47474**.")
                Para("The laptop runs the full app with every tab, fed by the BMS PC.")
            }
            GuideStep(5, "Export the briefing from BMS", Hud.Green) {
                Para("In the BMS Launcher open **CONFIG → General → Briefing / Debriefing**:")
                Check(true, "Briefing Output to File (enables the PRINT button)")
                Check(false, "HTML Briefings (must be off: BMS Companion and EZBoards read the text file)")
                Check(null, "Append New Briefings is optional; the newest briefing is always used")
                Para("Plan the mission, open the **Briefing** tab and press **PRINT** (top right). BMS writes User\\Briefings\\briefing.txt and the app updates within a second.")
                Code("set g_nPrintToFile 1\nset g_bBriefHTML 0")
            }
            GuideStep(6, "Save the DTC", Hud.Green) {
                Para("In the **DTC** screen set target steerpoints, PPTs and the comm plan, then press **SAVE**. That writes User\\Config\\<callsign>.ini: steerpoint coordinates, targets, threat rings and radio presets before you enter 3D.")
            }
            GuideStep(7, "Live flight data", Hud.Cyan) {
                Para("Nothing to configure. BMS always publishes shared memory, and position, fuel, RWR, DED, bullseye and steerpoints stream as soon as you are in the 3D world.")
                Para("Tips: **F11** or the full-screen button gives a borderless full-screen window; the **pin** keeps it on top; **Ctrl +/−** changes the UI size.")
            }
            GuideStep(8, "AWACS picture (other aircraft)", Hud.Cyan) {
                Para("Other aircraft come from BMS's built-in **Tacview real-time telemetry** (the Tacview program is not needed). Add to **User\\Config\\Falcon BMS User.cfg** (the checklist has an edit button):")
                Code("set g_bTacviewRealTime 1\nset g_bTacviewAcmi 1")
                Para("The stream only runs while **ACMI recording** is on: start it in the cockpit (default key **F**) or enable recording in the Launcher.")
                Para("Multiplayer: the host must allow it (**g_bMPTacviewRtAllowedByServer 1**). If you set **g_sTacviewPassword**, enter the same password in the settings. Prefer not to see hostiles? Turn off the feed, or use the Hostiles switch on the map.")
            }
            GuideStep(9, "EZBoards kneeboards", Hud.Magenta) {
                Para("EZBoards ships with BMS 4.38 in **Tools\\EZBoards** and needs the **.NET 8 runtime**. BMS Companion finds it automatically; otherwise pick the folder in the settings.")
                Para("After PRINT, click **Generate kneeboards** on the Boards tab (or a Dashboard card). Tick **Generate kneeboards automatically** to run it on every PRINT.")
                Para("To see the kneeboards in the cockpit, enable the 3D pilot model (Setup → Graphics → Pilot Model).")
            }
            GuideStep(10, "VR: the app on your knee", Hud.Cyan) {
                Para("BMS Companion does not draw inside the headset itself — that needs a native OpenXR layer. Use **OpenKneeboard** (openkneeboard.com), which already places the board on your knee, drags, resizes and rotates it, toggles it with your own key and lets the mouse move in and out of it.")
                Bullet("1. Turn **Browser access** on (server page → Connect your devices).")
                Bullet("2. In OpenKneeboard: **Settings → Tabs → Add a tab → Web Dashboard**.")
                Bullet("3. Address: the one the **VR boards** tab gives you for that board — **/kneeboard/1**, **/kneeboard/2** and so on, a board to a tab.")
                Bullet("4. Size and place the board in OpenKneeboard; its own binding shows and hides it in the headset.")
                Para("**In the headset you flip the board, you do not press it.** OpenKneeboard supports graphics tablets and nothing else — its own FAQ says \"Mice are not supported in-game\" — so bind a button (HOTAS, keyboard, StreamDeck) to OpenKneeboard's **next page** and **previous page** and it walks through BMS Companion's sections: Mission, Arsenal, Threats, Airfields, Cockpit. Nothing to aim at. A graphics tablet, if you have one, points at the board as well.")
                Para("**Print too small?** The board is drawn at a headset-friendly resolution, and **☰ → Print size** decides how large that resolution is used — Normal, Large or Very large. It is remembered per board.")
                Para("OpenKneeboard gives a web tab a landscape page and will not let you reproportion it — the page has to ask. This one asks for an upright board, and **☰ → Board shape** on the board itself offers the others (5:8 like a real kneeboard, 3:4, square, landscape). The board remembers which one it is.")
                Para("**/kneeboard** is what makes the board layout. It lays the page out for a board: the content gets all of it, a small **☰** in the corner opens the sections and the Mission tabs, each page’s own options sit behind **⋯** beside it, and both fade away when the mouse stops. The map fills the board. Left out are the things you would not use in the cockpit: the AWACS/GCI page, the Boards tab (the briefing has the same tables), the setup guides, Home and Media.")
                Para("The board layout is the browser version only: this window and the Android app keep their normal one.")
                Para("Try it without a headset: open **http://127.0.0.1:47474/kneeboard** in a browser on this PC and make the window small (about 800 x 800). The buttons fade a couple of seconds after the mouse stops — move it to bring them back.")
            }
            GuideStep(11, "Troubleshooting", Hud.Red) {
                Bullet("**Phones, browsers or client PCs can't connect**: same network (guest Wi-Fi isolates devices), and **Allow through Windows Firewall** on the BMS PC.")
                Bullet("**Port in use**: another program (or an old BMS Companion Bridge) uses 47474. Close it, or change the port in the settings.")
                Bullet("**Briefing empty**: PRINT not pressed, HTML briefings still on, or g_sBriefingsDirectory points somewhere else (it is followed while BMS runs).")
                Bullet("**No AWACS feed**: check g_bTacviewRealTime and that ACMI recording is running in 3D.")
                Bullet("**EZBoards fails**: open the log on the Boards tab. Common causes are a missing .NET 8 runtime or no briefing printed.")
                Bullet("**Map shows the wrong theater**: the app follows the theater BMS reports; add-on theaters use their base map.")
                Para("**Reporting a bug?** Anything that goes wrong is written to a log next to the settings: **%APPDATA%\\BMS Companion\\error.log** — paste that path into Explorer, or press **Open the error log** at the bottom of the server page's **Falcon BMS settings** card. Sending the last entry with your report saves a lot of guessing.")
            }
        }
    }
}

/** Client: find or enter the BMS PC, like the Android app. */
@Composable
private fun RemoteConnectionCard(onConnected: () -> Unit) {
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val state by MissionLink.state.collectAsState()
    var host by remember { mutableStateOf(Repo.getString("bridge_host") ?: "") }
    var port by remember { mutableStateOf(Repo.getInt("bridge_port", 47474).toString()) }
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
                testMsg = true to "Connected to ${info.host} (BMS Companion ${info.version})"
                onConnected()
            }.onFailure { testMsg = false to "Could not reach $h:$p. Check that BMS Companion runs on that PC and its firewall button was pressed." }
        }
    }

    SectionCard("Connect to the BMS PC", accent = Hud.Amber) {
        val (dot, text) = when (val s = state) {
            is LinkState.Online -> Hud.Green to "Connected to ${s.host}:${MissionLink.port}"
            is LinkState.Offline -> Hud.Red to "Can't reach ${s.host}: ${s.reason}"
            LinkState.Connecting -> Hud.Amber to "Connecting…"
            LinkState.Idle -> Hud.TextFaint to (MissionLink.host?.let { "Saved: $it:${MissionLink.port}" } ?: "Not connected yet")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(8.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = Hud.Text, maxLines = 2)
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            ActionButton(if (searching) "Searching…" else "Find BMS PC", primary = true, enabled = !searching) {
                searching = true
                found = null
                scope.launch {
                    found = MissionLink.discover()
                    searching = false
                    found?.singleOrNull()?.let { f -> host = f.host; port = f.port.toString(); connect(f.host, f.port) }
                }
            }
            if (searching) { Spacer(Modifier.width(10.dp)); CircularProgressIndicator(Modifier.size(20.dp), color = Hud.Amber, strokeWidth = 2.dp) }
        }
        found?.let { list ->
            Spacer(Modifier.height(8.dp))
            if (list.isEmpty()) Text("No BMS PC answered. Enter its address below.", color = Hud.Amber, fontSize = 13.sp)
            list.forEach { f ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(10.dp)).background(Hud.Surface2).clickable { host = f.host; port = f.port.toString(); connect(f.host, f.port) }.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(f.name, fontWeight = FontWeight.SemiBold)
                        Text("${f.host}:${f.port} · BMS Companion ${f.version}", style = LocalExtra.current.monoSmall, color = Hud.TextDim)
                    }
                    Text("Connect ›", color = Hud.Amber, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("Or enter the address", style = LocalExtra.current.overline, color = Hud.TextFaint)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Hud.Amber, unfocusedBorderColor = Hud.Outline, cursorColor = Hud.Amber)
            OutlinedTextField(host, { host = it.trim() }, Modifier.weight(1f), singleLine = true, label = { Text("BMS PC address") }, colors = colors)
            OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, Modifier.width(96.dp), singleLine = true, label = { Text("Port") }, colors = colors)
        }
        Spacer(Modifier.height(8.dp))
        ActionButton(if (testing) "Connecting…" else "Connect", primary = false, enabled = host.isNotBlank() && !testing) { connect(host, port.toIntOrNull() ?: 47474) }
        testMsg?.let { (ok, msg) -> Text(msg, color = if (ok) Hud.Green else Hud.Red, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
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
