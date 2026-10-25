package com.bmscompanion.app.ui.screens.mission

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bmscompanion.app.data.PcFiles
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.mission.BmsInstallEntry
import com.bmscompanion.app.data.mission.BmsInstallList
import com.bmscompanion.app.data.mission.BridgeInfo
import com.bmscompanion.app.data.mission.LinkState
import com.bmscompanion.app.data.mission.MissionLink
import com.bmscompanion.app.data.mission.PcAnswer
import com.bmscompanion.app.ui.components.SectionCard
import com.bmscompanion.app.ui.screens.wdp.PlannerMissionState
import com.bmscompanion.app.ui.screens.wdp.WdpSession
import com.bmscompanion.app.ui.theme.Hud
import kotlinx.coroutines.launch

/**
 * Where [BmsInstallsCard] reads the list of Falcon BMS installs and sends a change: the PC over the link ([LinkInstalls],
 * every Setup page), or the PC program itself (its server page, `PcInstalls` in the desktop sources).
 */
interface BmsInstallsSource {
    suspend fun list(refresh: Boolean): PcAnswer<BmsInstallList>
    suspend fun change(op: String, args: Map<String, String>): PcAnswer<BmsInstallList>
    /** a folder on the BMS PC the pilot chose (the PC's own folder window), or null */
    suspend fun pickFolder(start: String?): String?
}

/** The list on the PC through [MissionLink] (`/api/bms/installs`), a folder added through the PC's folder window ([PcFiles.folder]). */
object LinkInstalls : BmsInstallsSource {
    override suspend fun list(refresh: Boolean) = MissionLink.bmsInstalls(refresh)
    override suspend fun change(op: String, args: Map<String, String>) = MissionLink.bmsInstallsChange(op, args)
    override suspend fun pickFolder(start: String?): String? = PcFiles.folder("Add a Falcon BMS folder", start ?: "@bms")?.path
}

/** " · <active install>" for the Mission section's source line when the PC lists two installs or more ("" otherwise). */
fun installSuffix(info: BridgeInfo?): String =
    info?.bms?.takeIf { it.installs > 1 }?.install?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""

/**
 * **Falcon BMS installs** (1.4.1): every install the PC knows — the registry's (4.38.1 and 4.37 side by side, each found
 * by itself) and the folders added here — with the one in use marked; one tap on another makes it the one everything
 * reads, on every device. Add folder… opens the PC's folder window; a folder added can be renamed and taken out again
 * (a registry install can be renamed only). What another version of Falcon BMS does not support is not adapted: it
 * says so where it fails, as it would with a wrong folder.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BmsInstallsCard(source: BmsInstallsSource = LinkInstalls, linkedOverride: Boolean? = null, refreshKey: Any? = null) {
    val info by MissionLink.info.collectAsState()
    val link by MissionLink.state.collectAsState()
    val linked = linkedOverride ?: (link is LinkState.Online && info != null)
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<BmsInstallList?>(null) }
    var said by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<BmsInstallEntry?>(null) }

    fun take(a: PcAnswer<BmsInstallList>) {
        a.value?.let { list = it }
        said = a.value?.message
        error = a.error ?: a.value?.error
    }
    fun run(body: suspend () -> PcAnswer<BmsInstallList>?) {
        if (busy) return
        busy = true
        scope.launch {
            try { body()?.let(::take) } finally { busy = false }
        }
    }
    // read on show, and again when the folder in use changes (another device may have switched)
    LaunchedEffect(linked, info?.bms?.baseDir, info?.bms?.installs, refreshKey) {
        if (!linked) return@LaunchedEffect
        val a = source.list(false)
        a.value?.let { list = it }
        if (a.value == null) error = a.error
    }

    SectionCard("Falcon BMS installs", accent = Hud.Green) {
        Text(
            "The Falcon BMS folder everything is read from. Installs the registry names are found by themselves; add any other folder. One tap switches, on every device.",
            color = Hud.TextDim, fontSize = 12.sp, lineHeight = 16.sp,
        )
        Spacer(Modifier.padding(top = 6.dp))
        val rows = list?.installs.orEmpty()
        if (rows.isEmpty()) {
            Text(
                when {
                    !linked -> "Connect to BMS Companion on the PC to see its Falcon BMS installs: they are kept there."
                    list == null && error == null -> "Reading…"
                    else -> "No Falcon BMS folder is known on the PC. Add its folder."
                },
                color = if (!linked) Hud.Amber else Hud.TextDim, fontSize = 12.sp, lineHeight = 16.sp,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (e in rows) InstallRow(
                e, enabled = linked && !busy,
                onPick = { run { source.change("active", mapOf("id" to e.id)) } },
                onRename = { renaming = e },
                onRemove = { run { source.change("remove", mapOf("id" to e.id)) } },
            )
        }
        Spacer(Modifier.padding(top = 8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (linked) {
                SmallButton("Add folder…", Icons.Default.CreateNewFolder, primary = false) {
                    run {
                        val start = rows.firstOrNull { it.active }?.path?.trimEnd('\\')?.substringBeforeLast('\\', "")?.takeIf { it.length > 2 }
                        source.pickFolder(start)?.let { p -> source.change("add", mapOf("path" to p)) }
                    }
                }
                SmallButton("Refresh", Icons.Default.Refresh, primary = false) { run { source.list(true) } }
            }
            if (busy) CircularProgressIndicator(Modifier.size(16.dp), color = Hud.Green, strokeWidth = 2.dp)
        }
        error?.let { Text(it, color = Hud.Red, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 6.dp)) }
            ?: said?.let { Text(it, color = Hud.Green, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 6.dp)) }
    }

    renaming?.let { e ->
        var text by remember(e.id) { mutableStateOf(e.label ?: e.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Name this install") },
            text = {
                Column {
                    Text(e.path, color = Hud.TextDim, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.padding(top = 8.dp))
                    OutlinedTextField(text, { text = it.take(60) }, singleLine = true, label = { Text("Name") })
                }
            },
            confirmButton = {
                TextButton({ renaming = null; run { source.change("label", mapOf("id" to e.id, "label" to text.trim())) } }) {
                    Text("Save", color = Hud.Green, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                Row {
                    if (e.label != null) TextButton({ renaming = null; run { source.change("label", mapOf("id" to e.id, "label" to "")) } }) {
                        Text("Its own name", color = Hud.TextDim)
                    }
                    TextButton({ renaming = null }) { Text("Cancel", color = Hud.TextDim) }
                }
            },
            containerColor = Hud.Surface,
        )
    }
}

@Composable
private fun InstallRow(e: BmsInstallEntry, enabled: Boolean, onPick: () -> Unit, onRename: () -> Unit, onRemove: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    val canPick = enabled && !e.active && e.exists
    Row(
        Modifier.fillMaxWidth().clip(shape)
            .background(if (e.active) Hud.Green.copy(alpha = 0.10f) else Hud.Surface2)
            .border(1.dp, if (e.active) Hud.Green.copy(alpha = 0.65f) else Hud.Outline.copy(alpha = 0.5f), shape)
            .clickable(enabled = canPick, onClick = onPick)
            .padding(start = 10.dp, end = 2.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (e.active) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked, null,
            tint = if (e.active) Hud.Green else if (e.exists) Hud.TextDim else Hud.TextFaint, modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    e.name, color = if (e.exists) Hud.Text else Hud.TextFaint, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                )
                if (e.active) {
                    Spacer(Modifier.width(8.dp))
                    Text("IN USE", color = Hud.Green, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
            Text(e.path, color = Hud.TextDim, fontSize = 11.sp, lineHeight = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val facts = listOfNotNull(
                if (e.registryKey != null) (if (e.label != null) "${e.registryKey} · " else "") + "installed" else "added folder",
                "the registry's newest".takeIf { e.registryDefault },
                e.theater?.let { "theater $it" },
            ).joinToString(" · ")
            Text(facts, color = Hud.TextFaint, fontSize = 11.sp, lineHeight = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            when {
                !e.exists -> Text("Folder not found", color = Hud.Red, fontSize = 11.sp, lineHeight = 15.sp)
                !e.looksLikeBms -> Text("Does not look like a Falcon BMS folder (no Falcon BMS.exe, no Data\\TerrData)", color = Hud.Amber, fontSize = 11.sp, lineHeight = 15.sp)
            }
        }
        if (enabled) {
            IconButton(onClick = onRename, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Edit, "Rename", tint = Hud.TextDim, modifier = Modifier.size(18.dp))
            }
            if (e.added) IconButton(onClick = onRemove, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Close, "Take out of the list", tint = Hud.TextDim, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/**
 * This device's side of a switch (1.4.1): when the PC reads another Falcon BMS folder than the last one this device
 * saw (kept in `bms_install_seen`), the Planner plans the printed briefing again (a save of the other install is not
 * this one's) and the device starts its view of the mission afresh ([MissionEpoch.reset]). Never throws.
 */
object BmsInstallSeen {
    private const val KEY = "bms_install_seen"

    fun observe(dir: String?) {
        val now = dir?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val prev = runCatching { Repo.getString(KEY) }.getOrNull()
        if (prev != null && prev.equals(now, ignoreCase = true)) return
        runCatching { Repo.putString(KEY, now) }
        if (prev == null) return
        runCatching { PlannerMissionState.backToBriefing(share = false) }
        runCatching { MissionEpoch.reset() }
        // the DTC page's edits and the DataCard's entries belonged to the other install's cartridge: dropped, unasked,
        // and the new install's cartridge read (a later Save to DTC must never write them into this install's file)
        runCatching { WdpSession.dropForInstall() }
        drops++
    }

    /** How many switches this device has acted on (the install check). */
    var drops = 0
        private set
}

/** Watches the folder the PC reads for [BmsInstallSeen], wherever the app is drawn (from [MissionEpochWatcher]). */
@Composable
fun BmsInstallWatcher() {
    val info by MissionLink.info.collectAsState()
    val dir = info?.bms?.baseDir
    LaunchedEffect(dir) { BmsInstallSeen.observe(dir) }
}
