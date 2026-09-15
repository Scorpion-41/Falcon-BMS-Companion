package com.bmscompanion.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bmscompanion.app.data.mission.BridgeInfo
import com.bmscompanion.app.data.mission.LinkState
import com.bmscompanion.app.data.mission.MissionLink
import com.bmscompanion.app.ui.components.SectionCard
import com.bmscompanion.app.ui.screens.mission.SmallButton
import com.bmscompanion.app.ui.theme.Hud
import com.bmscompanion.app.ui.theme.LocalExtra
import com.bmscompanion.desktop.Device
import com.bmscompanion.desktop.PcConfig
import com.bmscompanion.desktop.PcServer
import com.bmscompanion.desktop.PcServices
import com.bmscompanion.desktop.SystemTools
import com.bmscompanion.desktop.serverPort
import com.bmscompanion.desktop.bridge.Bridge
import com.bmscompanion.desktop.bridge.BridgeLog
import com.bmscompanion.desktop.bridge.EzBoardsRunner
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Cards of BMS Companion for Windows itself: status, connecting devices, the setup checklist, settings and start-up.
// Shown on the server page and in Mission → Setup of the app mode.

/** Live status of this PC, refreshed every 2 seconds while shown. */
class PcStatus(
    val info: BridgeInfo?,
    val devices: List<Device>,
    val firewallRule: Boolean,
    val dotNet8: Boolean,
    val cfg: Map<String, String?>,
)

@Composable
fun rememberPcStatus(): PcStatus {
    var status by remember { mutableStateOf(PcStatus(null, emptyList(), false, true, emptyMap())) }
    val ticks by Bridge.ticks.collectAsState()
    LaunchedEffect(Unit) {
        var fwCheckedAt = 0L
        var fw = false
        val dotNet = withContext(Dispatchers.IO) { SystemTools.dotNet8Installed() }
        while (true) {
            status = withContext(Dispatchers.IO) {
                if (System.currentTimeMillis() - fwCheckedAt > 15_000) { fw = SystemTools.firewallRuleExists(); fwCheckedAt = System.currentTimeMillis() }
                val cfg = listOf("g_nPrintToFile", "g_bBriefHTML", "g_bTacviewRealTime", "g_bTacviewAcmi").associateWith { Bridge.install.cfgValue(it) }
                PcStatus(if (Bridge.running) Bridge.info() else null, PcServer.recentDevices(), fw, dotNet, cfg)
            }
            delay(2000)
        }
    }
    @Suppress("UNUSED_EXPRESSION") ticks
    return status
}

// ------------------------------------------------------------------ small parts

@Composable
fun StatusRow(label: String, value: String, ok: Boolean, labelWidth: Int = 104) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (ok) Hud.Green else Hud.Amber))
        Spacer(Modifier.width(8.dp))
        Text(label, Modifier.width(labelWidth.dp), fontSize = 13.sp, color = Hud.TextDim)
        Text(value, fontSize = 13.sp, color = Hud.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun QrCode(text: String, modifier: Modifier) {
    val matrix = remember(text) { runCatching { QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 1)) }.getOrNull() }
    Canvas(modifier.clip(RoundedCornerShape(8.dp)).background(Color.White)) {
        val m = matrix ?: return@Canvas
        val cell = size.minDimension / m.width
        for (y in 0 until m.height) for (x in 0 until m.width) if (m.get(x, y)) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.6f, cell + 0.6f))
    }
}

@Composable
fun Toggle(label: String, sub: String?, on: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(enabled = enabled) { onChange(!on) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 14.sp, color = if (enabled) Hud.Text else Hud.TextFaint)
            if (sub != null) Text(sub, fontSize = 12.sp, color = Hud.TextDim)
        }
        Switch(on, onChange, enabled = enabled, colors = SwitchDefaults.colors(checkedTrackColor = Hud.Amber, checkedThumbColor = Hud.Bg))
    }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier.fillMaxWidth(), password: Boolean = false) {
    OutlinedTextField(
        value, onChange, modifier, singleLine = true, label = { Text(label, fontSize = 12.sp) },
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Hud.Amber, unfocusedBorderColor = Hud.Outline, cursorColor = Hud.Amber),
    )
}

@Composable
private fun Overline(text: String) = Text(text, style = LocalExtra.current.overline, color = Hud.TextFaint, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))

@Composable
fun FirewallButton() {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf<Boolean?>(null) }
    SmallButton(if (busy) "Waiting for Windows…" else if (done == true) "Firewall rules added ✓" else "Allow through Windows Firewall", null, primary = false) {
        if (busy) return@SmallButton
        busy = true
        scope.launch {
            withContext(Dispatchers.IO) { SystemTools.addFirewallRules(serverPort()) }
            done = withContext(Dispatchers.IO) { SystemTools.firewallRuleExists() }
            busy = false
        }
    }
}

fun pickFolder(title: String): String? {
    val chooser = javax.swing.JFileChooser().apply {
        dialogTitle = title
        fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
    }
    return if (chooser.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) chooser.selectedFile.path else null
}

/** Keeps MissionLink polling while a PC settings view is shown (status rows need live info). */
@Composable
fun KeepLinkPolling() {
    DisposableEffect(Unit) {
        MissionLink.acquire()
        onDispose { MissionLink.release() }
    }
}

// ------------------------------------------------------------------ cards

/** Where the app window's mission data comes from (app mode). */
@Composable
fun DataSourceCard(onSwitchToServer: () -> Unit) {
    SectionCard("Where does Falcon BMS run?", accent = Hud.Amber) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SourceChoice(Icons.Default.Computer, "On this PC", "BMS Companion reads Falcon BMS here and also serves your phones, tablets, browsers and client PCs.", PcConfig.bmsOnThisPc) { PcConfig.useBmsOnThisPc(true) }
            SourceChoice(Icons.Default.Language, "On another PC (this PC is a client)", "For a laptop or second PC: the full app with the mission from BMS Companion on the BMS PC.", !PcConfig.bmsOnThisPc) { PcConfig.useBmsOnThisPc(false) }
        }
        Spacer(Modifier.height(10.dp))
        Text("Only serving devices from this PC? The server page uses far less memory than the full app.", fontSize = 12.sp, color = Hud.TextDim)
        Spacer(Modifier.height(6.dp))
        SmallButton("Switch to server mode", null, primary = false, onClick = onSwitchToServer)
    }
}

@Composable
private fun SourceChoice(icon: ImageVector, title: String, sub: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(if (selected) Hud.Amber.copy(alpha = 0.14f) else Hud.Surface2)
            .border(1.dp, if (selected) Hud.Amber.copy(alpha = 0.75f) else Hud.Outline.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (selected) Hud.Amber else Hud.TextDim, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = if (selected) Hud.Amber else Hud.Text)
            Text(sub, fontSize = 12.sp, color = Hud.TextDim, lineHeight = 16.sp)
        }
    }
}

/** Addresses, QR code and what each kind of device needs. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConnectDevicesCard(status: PcStatus) {
    val running by PcServer.running.collectAsState()
    val error by PcServer.error.collectAsState()
    Bridge.settings.collectAsState()
    val port = System.getenv("BMSC_SHOW_PORT")?.toIntOrNull() ?: serverPort() // env: example port for screenshots
    val ips = remember(running) { SystemTools.lanAddresses() }
    val url = ips.firstOrNull()?.let { "http://$it:$port" }
    SectionCard("Connect your devices", accent = Hud.Cyan) {
        if (error != null) Text(error!!, color = Hud.Red, fontSize = 13.sp, modifier = Modifier.padding(bottom = 6.dp))
        if (!running && error == null) Text("Starting…", color = Hud.TextDim, fontSize = 13.sp)

        DeviceBlock(Icons.Default.Language, "iPhone, iPad and any browser", Hud.Cyan) {
            if (!PcConfig.webEnabled) {
                Text("Browser access is off.", fontSize = 13.sp, color = Hud.TextDim)
                SmallButton("Turn on browser access", null, primary = true) { PcConfig.setWeb(true) }
            } else if (url != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    QrCode(url, Modifier.size(128.dp))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Scan with the camera, or open:", fontSize = 12.sp, color = Hud.TextDim)
                        Text(url, style = LocalExtra.current.mono.copy(fontSize = 16.sp), color = Hud.Cyan, modifier = Modifier.clickable { SystemTools.openUrl(url) })
                        ips.drop(1).forEach { Text("http://$it:$port", style = LocalExtra.current.monoSmall, color = Hud.TextDim) }
                        Text("The app runs in the browser itself. On iPhone/iPad: Share → Add to Home Screen.", fontSize = 12.sp, color = Hud.TextFaint)
                    }
                }
            } else Text("No network connection found on this PC.", fontSize = 13.sp, color = Hud.Amber)
        }
        DeviceBlock(Icons.Default.PhoneAndroid, "Android phones and tablets", Hud.Green) {
            Text("Install BMS-Companion.apk, then Mission → Setup → Find BMS PC.", fontSize = 13.sp, color = Hud.TextDim)
            if (ips.isNotEmpty()) Text("Or type ${ips.joinToString(" or ")}  ·  port $port", style = LocalExtra.current.monoSmall, color = Hud.Text)
        }
        DeviceBlock(Icons.Default.Computer, "Laptop or second PC", Hud.Amber) {
            Text("Install BMS Companion there, open the full app, choose “On another PC” in Mission → Setup and press Find BMS PC.", fontSize = 13.sp, color = Hud.TextDim)
        }
        Toggle("Browser access", "Browsers on this network can open the app", PcConfig.webEnabled) { PcConfig.setWeb(it) }
        FirewallButton()
        Spacer(Modifier.height(6.dp))
        Text(
            if (status.devices.isEmpty()) "No devices connected right now." else "Connected: " + status.devices.joinToString(" · ") { "${it.kind} ${it.address}" },
            fontSize = 12.sp, color = if (status.devices.isEmpty()) Hud.TextDim else Hud.Green,
        )
        Text("Local network only (TCP $port, UDP 47475).", fontSize = 11.sp, color = Hud.TextFaint, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun DeviceBlock(icon: ImageVector, title: String, accent: Color, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Icon(icon, null, tint = accent, modifier = Modifier.size(22.dp).padding(top = 2.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Hud.Text)
            content()
        }
    }
}

private enum class Check { OK, TODO, PROBLEM, INFO }

/** The setup steps with live checks and a fix button for each (like the earlier bridge's guide). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SetupChecklistCard(status: PcStatus) {
    val info = status.info
    val s = Bridge.settings.collectAsState().value
    val scope = rememberCoroutineScope()
    val install = Bridge.install
    val cfg = status.cfg
    val demo = info?.demo == true

    data class Step(val title: String, val state: Check, val text: String, val actions: List<Pair<String, () -> Unit>> = emptyList(), val code: String? = null)
    val userCfg = install.configDir?.let { File(it, "Falcon BMS User.cfg") }
    val steps = buildList {
        add(
            if (install.baseDir != null) Step("Falcon BMS is found", Check.OK, "Found: ${install.baseDir}" + (install.registryVersion?.let { " ($it)" } ?: ""))
            else Step("Falcon BMS is found", Check.PROBLEM, "Falcon BMS was not found. Choose the BMS folder.",
                listOf("Choose BMS folder…" to { pickFolder("Select your Falcon BMS folder")?.let { p -> Bridge.update { it.copy(BmsDirOverride = p) } } }))
        )
        val seen = status.devices.isNotEmpty()
        add(
            when {
                status.firewallRule -> Step("Allowed on your network", Check.OK, "Firewall rules are in place.")
                seen -> Step("Allowed on your network", Check.OK, "A device reached this PC, so the network is fine.")
                else -> Step("Allowed on your network", Check.TODO, "No firewall rule found yet. Fine if you allowed the Windows prompt; otherwise press the button (one administrator prompt).",
                    listOf("Allow through Windows Firewall" to { Thread { SystemTools.addFirewallRules(serverPort()) }.start() }))
            }
        )
        val print = cfg["g_nPrintToFile"]
        val html = cfg["g_bBriefHTML"]
        add(
            when {
                demo -> Step("Export the briefing", Check.INFO, "Demo mode: using the demo briefing.")
                html == "1" -> Step("Export the briefing", Check.PROBLEM, "HTML Briefings is ON: untick it in the Launcher (CONFIG → General).")
                print != null && print.toIntOrNull() == 0 -> Step("Export the briefing", Check.PROBLEM, "Briefing Output to File is OFF: tick it in the Launcher (CONFIG → General).")
                info?.briefing?.available == true -> Step("Export the briefing", Check.OK, "Briefing found (printed ${info.briefing.generated}). Press PRINT again after changing the mission.")
                else -> Step("Export the briefing", Check.TODO, "No briefing printed yet: press PRINT on the BMS Briefing screen.", code = "set g_nPrintToFile 1\nset g_bBriefHTML 0")
            }.let { st -> st.copy(actions = listOfNotNull(Bridge.briefingPath?.let { bp -> "Open Briefings folder" to { SystemTools.open(File(bp).parent) } })) }
        )
        add(
            when {
                demo -> Step("Save the DTC", Check.INFO, "Demo mode: using demo steerpoints.")
                (info?.briefing?.dtcModified ?: 0) > 0 -> Step("Save the DTC", Check.OK, "DTC saved ${SimpleDateFormat("d MMM HH:mm", Locale.US).format(Date(info!!.briefing.dtcModified))}.")
                install.callsign == null -> Step("Save the DTC", Check.TODO, "Pilot callsign not known yet (log into BMS once).")
                else -> Step("Save the DTC", Check.TODO, "No DTC saved yet for “${install.callsign}”: press SAVE in the DTC page.")
            }
        )
        add(
            when {
                demo -> Step("Live flight data", Check.INFO, "Demo mode is on.")
                info?.bms?.flying == true -> Step("Live flight data", Check.OK, "Receiving live data (${info.bms.aircraft ?: "aircraft"}, ${info.bms.theater}).")
                info?.bms?.running == true -> Step("Live flight data", Check.INFO, "BMS is running (in the UI). Live data starts in 3D.")
                else -> Step("Live flight data", Check.INFO, "Start Falcon BMS. Nothing to set up.")
            }
        )
        val rt = cfg["g_bTacviewRealTime"]
        val acmi = cfg["g_bTacviewAcmi"]
        val tvActions = listOfNotNull(userCfg?.let { f -> "Edit Falcon BMS User.cfg" to { if (f.isFile) SystemTools.openInNotepad(f.path) else SystemTools.open(f.parent) } })
        add(
            when {
                demo -> Step("AWACS picture", Check.INFO, "Demo mode: showing demo traffic.")
                !s.TacviewEnabled -> Step("AWACS picture", Check.TODO, "Reading the Tacview stream is switched off (settings below).")
                info?.tacview?.connected == true -> Step("AWACS picture", Check.OK, "Connected to the AWACS feed (${info.tacview.objects} objects).")
                rt != "1" -> Step("AWACS picture", Check.TODO, "Add these lines to Falcon BMS User.cfg, then restart BMS:", tvActions, "set g_bTacviewRealTime 1\nset g_bTacviewAcmi 1")
                acmi == "0" -> Step("AWACS picture", Check.PROBLEM, "g_bTacviewAcmi is 0: set it to 1 (the real-time stream needs ACMI recording).", tvActions)
                info?.bms?.flying == true -> Step("AWACS picture", Check.TODO, "Config is right. Start ACMI recording in 3D (default key F).")
                else -> Step("AWACS picture", Check.OK, "Config is right. The feed connects in 3D while ACMI recording is on.")
            }
        )
        val last = info?.ezBoards?.lastRun
        val ezPick = "Choose EZBoards folder…" to {
            pickFolder("Select the EZBoards folder (contains EZBOARDS.BAT)")?.let { p -> if (EzBoardsRunner.isValidDir(p)) Bridge.update { it.copy(EzBoardsDir = p) } }
            Unit
        }
        add(
            when {
                !EzBoardsRunner.isValidDir(s.EzBoardsDir) -> Step("EZBoards kneeboards (optional)", Check.TODO, "EZBoards folder not set (BMS ships it in Tools\\EZBoards).", listOf(ezPick))
                !status.dotNet8 -> Step("EZBoards kneeboards (optional)", Check.PROBLEM, "Folder OK, but the .NET 8 runtime was not found. EZBoards will not run without it.",
                    listOf("Get .NET 8 runtime" to { SystemTools.openUrl("https://dotnet.microsoft.com/en-us/download/dotnet/8.0") }))
                last != null && !last.ok -> Step("EZBoards kneeboards (optional)", Check.PROBLEM, "Last run failed: ${last.message}", listOf(ezPick))
                else -> Step("EZBoards kneeboards (optional)", Check.OK, "Ready: ${s.EzBoardsDir}")
            }
        )
    }
    val counted = steps.filter { it.state != Check.INFO }
    val done = counted.count { it.state == Check.OK }

    SectionCard(
        "Setup checklist", accent = Hud.Green,
        trailing = { Text(if (demo) "DEMO MODE" else "$done of ${counted.size} done", fontSize = 12.sp, color = if (demo) Hud.Amber else if (done == counted.size) Hud.Green else Hud.TextDim, fontWeight = FontWeight.Bold) },
    ) {
        if (info == null) {
            Text("Falcon BMS is not read on this PC (it is a client). The checklist applies to the BMS PC.", fontSize = 13.sp, color = Hud.TextDim)
            return@SectionCard
        }
        steps.forEachIndexed { i, st ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                val (mark, color) = when (st.state) { Check.OK -> "✓" to Hud.Green; Check.PROBLEM -> "✕" to Hud.Red; Check.TODO -> "●" to Hud.Amber; Check.INFO -> "i" to Hud.TextDim }
                Box(Modifier.size(24.dp).clip(CircleShape).background(color.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                    Text(mark, color = color, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("${i + 1}. ${st.title}", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Hud.Text)
                    Text(st.text, fontSize = 12.sp, color = if (st.state == Check.PROBLEM) Hud.Red else Hud.TextDim, lineHeight = 16.sp)
                    st.code?.let {
                        Text(it, Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xFF05090C)).padding(horizontal = 8.dp, vertical = 5.dp),
                            style = LocalExtra.current.monoSmall, color = Hud.Green)
                    }
                    if (st.actions.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        st.actions.forEach { (label, action) -> SmallButton(label, null, primary = false) { scope.launch { action() } } }
                    }
                }
            }
        }
    }
}

/** Settings of the part that reads Falcon BMS: folders, AWACS feed, EZBoards, demo mode, port. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BmsSettingsCard() {
    val s by Bridge.settings.collectAsState()
    var ez by remember(s.EzBoardsDir) { mutableStateOf(s.EzBoardsDir.orEmpty()) }
    var bmsDir by remember(s.BmsDirOverride) { mutableStateOf(s.BmsDirOverride.orEmpty()) }
    var picsDir by remember(s.PicturesDirOverride) { mutableStateOf(s.PicturesDirOverride.orEmpty()) }
    var tvHost by remember(s.TacviewHost) { mutableStateOf(s.TacviewHost) }
    var tvPort by remember(s.TacviewPort) { mutableStateOf(s.TacviewPort.toString()) }
    var tvPass by remember(s.TacviewPassword) { mutableStateOf(s.TacviewPassword) }
    var port by remember(s.Port) { mutableStateOf(s.Port.toString()) }

    SectionCard("Falcon BMS settings", accent = Hud.Green) {
        Overline("EZBOARDS")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("EZBoards folder", ez, { ez = it }, Modifier.weight(1f))
            SmallButton("Browse…", null, primary = false) { pickFolder("Select the EZBoards folder (contains EZBOARDS.BAT)")?.let { p -> Bridge.update { it.copy(EzBoardsDir = p) } } }
        }
        if (ez != s.EzBoardsDir.orEmpty()) SmallButton("Save folder", null, primary = true) { Bridge.update { it.copy(EzBoardsDir = ez.trim().ifEmpty { null }) } }
        Toggle("Generate kneeboards automatically when the briefing is printed", null, s.AutoEzBoardsOnPrint) { on -> Bridge.update { it.copy(AutoEzBoardsOnPrint = on) } }

        Overline("AWACS PICTURE (TACVIEW REAL-TIME STREAM)")
        Toggle("Read other aircraft from BMS's Tacview stream", "Needs set g_bTacviewRealTime 1 and ACMI recording in 3D", s.TacviewEnabled) { on -> Bridge.update { it.copy(TacviewEnabled = on) } }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("Host", tvHost, { tvHost = it }, Modifier.weight(1f))
            Field("Port", tvPort, { tvPort = it.filter(Char::isDigit).take(5) }, Modifier.width(100.dp))
            Field("Password", tvPass, { tvPass = it }, Modifier.weight(1f), password = true)
        }
        if (tvHost != s.TacviewHost || tvPort != s.TacviewPort.toString() || tvPass != s.TacviewPassword) {
            SmallButton("Save Tacview settings", null, primary = true) {
                Bridge.update { it.copy(TacviewHost = tvHost.trim().ifEmpty { "127.0.0.1" }, TacviewPort = tvPort.toIntOrNull()?.coerceIn(1, 65535) ?: 42674, TacviewPassword = tvPass) }
            }
        }

        Overline("FOLDERS AND ADVANCED")
        Toggle("Demo mode", "A synthetic mission with moving traffic, to try everything without BMS", s.DemoMode) { on -> Bridge.update { it.copy(DemoMode = on) } }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("BMS folder (only if not found)", bmsDir, { bmsDir = it }, Modifier.weight(1f))
            SmallButton("Browse…", null, primary = false) { pickFolder("Select the Falcon BMS folder")?.let { p -> Bridge.update { it.copy(BmsDirOverride = p) } } }
        }
        if (bmsDir != s.BmsDirOverride.orEmpty()) SmallButton("Save BMS folder", null, primary = true) { Bridge.update { it.copy(BmsDirOverride = bmsDir.trim().ifEmpty { null }) } }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("Screenshots folder (only if not the BMS one)", picsDir, { picsDir = it }, Modifier.weight(1f))
            SmallButton("Browse…", null, primary = false) { pickFolder("Select the folder with your BMS screenshots")?.let { p -> Bridge.update { it.copy(PicturesDirOverride = p) } } }
        }
        if (picsDir != s.PicturesDirOverride.orEmpty()) SmallButton("Save screenshots folder", null, primary = true) { Bridge.update { it.copy(PicturesDirOverride = picsDir.trim().ifEmpty { null }) } }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("Network port", port, { port = it.filter(Char::isDigit).take(5) }, Modifier.width(140.dp))
            if (port.toIntOrNull()?.let { it in 1024..65535 && it != s.Port } == true) SmallButton("Apply port", null, primary = true) {
                Bridge.update { it.copy(Port = port.toInt()) }
                PcServices.restartServer()
            }
            Text("Devices use this port; UDP 47475 finds the PC.", fontSize = 12.sp, color = Hud.TextFaint)
        }
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Bridge.install.configDir?.let { dir -> SmallButton("Edit Falcon BMS User.cfg", null, primary = false) { File(dir, "Falcon BMS User.cfg").let { if (it.isFile) SystemTools.openInNotepad(it.path) else SystemTools.open(dir) } } }
            Bridge.picturesDir?.let { d -> SmallButton("Open screenshots folder", null, primary = false) { SystemTools.open(d) } }
        }
    }
}

/** What happened recently: connected devices and the log. */
@Composable
fun ActivityCard(status: PcStatus) {
    var expanded by remember { mutableStateOf(false) }
    val lines = remember(status) { BridgeLog.snapshot() }
    SectionCard("Activity", accent = Hud.TextDim, trailing = { Text(if (expanded) "Less" else "More", color = Hud.Amber, fontSize = 12.sp, modifier = Modifier.clickable { expanded = !expanded }) }) {
        if (status.devices.isEmpty()) Text("No devices in the last two minutes.", fontSize = 13.sp, color = Hud.TextDim)
        status.devices.forEach { d -> StatusRow(d.kind, "${d.address} · ${d.requests} requests", true, labelWidth = 150) }
        Spacer(Modifier.height(6.dp))
        (if (expanded) lines.takeLast(60) else lines.takeLast(6)).forEach {
            Text(it, style = LocalExtra.current.monoSmall, color = if (" WARN " in it) Hud.Amber else Hud.TextDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun StartupCard() {
    val exe = SystemTools.appExe
    var withWindows by remember { mutableStateOf<Boolean?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { withWindows = withContext(Dispatchers.IO) { SystemTools.startsWithWindows() } }
    SectionCard("Start-up and closing", accent = Hud.TextDim) {
        Toggle("Keep running in the tray when the window is closed", "Phones, tablets and browsers stay connected. Exit from the tray icon.", PcConfig.closeToTray) { PcConfig.keepInTray(it) }
        Toggle(
            "Start with Windows (in the tray)",
            if (exe == null) "Available in the installed or unzipped app" else "Handy when this PC serves your other devices",
            withWindows == true, enabled = exe != null && withWindows != null,
        ) { on -> scope.launch { withContext(Dispatchers.IO) { SystemTools.setStartWithWindows(on) }; withWindows = withContext(Dispatchers.IO) { SystemTools.startsWithWindows() } } }
    }
}

/** Connection status in one glance (app mode Setup). */
@Composable
fun PcStatusCard(status: PcStatus) {
    val state by MissionLink.state.collectAsState()
    SectionCard("Status", accent = Hud.Green) {
        StatusRow(
            "Data",
            when (val st = state) {
                is LinkState.Online -> if (PcConfig.readsBms) "reading Falcon BMS on this PC" else "connected to ${st.host}"
                is LinkState.Offline -> st.reason
                LinkState.Connecting -> "connecting…"
                LinkState.Idle -> if (PcConfig.readsBms) "starting…" else "choose the BMS PC below"
            },
            state is LinkState.Online,
        )
        MissionLink.info.collectAsState().value?.let { i ->
            StatusRow("Falcon BMS", when { i.demo -> "demo mode"; i.bms.running -> "running ${i.bms.version ?: ""} · ${if (i.bms.flying) "3D" else "UI"}"; i.bms.installed -> "installed, not running"; else -> "not found" }, i.bms.running || i.demo)
            StatusRow("Briefing", if (i.briefing.available) "printed ${i.briefing.generated ?: ""}" else "not printed yet", i.briefing.available)
            StatusRow("AWACS feed", if (i.tacview.connected) "connected · ${i.tacview.objects} objects" else i.tacview.state, i.tacview.connected)
        }
        StatusRow("Devices", if (status.devices.isEmpty()) "none connected" else status.devices.joinToString { "${it.kind} ${it.address}" }, status.devices.isNotEmpty())
    }
}
