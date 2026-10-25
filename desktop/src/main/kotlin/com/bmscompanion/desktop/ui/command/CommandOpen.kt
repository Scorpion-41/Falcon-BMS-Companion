package com.bmscompanion.desktop.ui.command

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bmscompanion.app.data.FileFilter
import com.bmscompanion.app.data.PcFiles
import com.bmscompanion.app.ui.screens.editor.Rule
import com.bmscompanion.app.ui.theme.Hud
import com.bmscompanion.desktop.bridge.Bridge
import com.bmscompanion.desktop.bridge.CampaignArchive
import com.bmscompanion.desktop.bridge.CampaignStarts
import com.bmscompanion.desktop.bridge.Theaters
import com.bmscompanion.desktop.bridge.command.ShippedSaves
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

/** One file of a theater's campaign folder, as the Open list shows it. */
private class OpenRow(val file: File, val kind: String, val start: String?, val shipped: Boolean, val modified: Long)

/** One theater's files. */
private class OpenTheater(val t: Theaters.Theater, val current: Boolean, val rows: List<OpenRow>)

private val SHIPPED_NAMES = Regex("^(TE_BMS_|TR_BMS_|DF_BMS_|TvT_Start).*", RegexOption.IGNORE_CASE)

/** Every theater's campaign folder (BMS's theater list, the current one first), each folder newest first. */
private fun listTheaters(): List<OpenTheater> {
    val base = CommandSession.rootOverride ?: Bridge.install.baseDir?.let(::File) ?: return emptyList()
    val set = Theaters.at(base)
    val cur = set.current(Bridge.install.theater)
    // BMS's and an add-on's own files as the section tells them at run time (names, objectives part, the folder's backup
    // record, the install's dates), one folder at a time
    val recognised = HashMap<File, Set<String>>()
    fun bmsOwn(f: File): Boolean {
        val dir = f.absoluteFile.parentFile ?: return false
        return f.name.lowercase() in recognised.getOrPut(dir) { runCatching { ShippedSaves.recognise(dir).map { it.file.name.lowercase() }.toSet() }.getOrDefault(emptySet()) }
    }
    return set.all.sortedBy { if (it == cur) 0 else 1 }.map { t ->
        val rows = set.saves(t).map { f ->
            // (the directory alone: a start holds the objectives part)
            val start = CampaignStarts.byName(f.name)
                ?: if (CampaignArchive.directoryOf(f).has("obj")) "${f.nameWithoutExtension} is a campaign start (it holds the objectives)" else null
            OpenRow(f, CampaignArchive.kindOfName(f.name), start, start != null || SHIPPED_NAMES.matches(f.nameWithoutExtension) || bmsOwn(f), f.lastModified())
        }
        OpenTheater(t, t == cur, rows)
    }.filter { it.rows.isNotEmpty() }
}

/** The Open window over the section: the theaters' files, the recent ones and Windows' own dialog. */
@Composable
internal fun OpenWindow(onClose: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Hud.Bg.copy(alpha = 0.6f)).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 1100.dp).fillMaxWidth(0.86f).fillMaxHeight(0.84f).clip(RoundedCornerShape(6.dp))
                .background(Hud.Surface).border(1.dp, Hud.Outline, RoundedCornerShape(6.dp)).clickable(enabled = false) {},
        ) {
            Strip(Hud.Surface2) {
                Text("Open a campaign, TE or training", color = Hud.Text, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                DeskButton("Close") { onClose() }
            }
            OpenPanel(Modifier.weight(1f).fillMaxWidth(), inline = false)
        }
    }
}

/** The list itself (the empty section shows it in place of the workspace). */
@Composable
internal fun OpenPanel(modifier: Modifier, inline: Boolean) {
    val scope = rememberCoroutineScope()
    val theaters by produceState<List<OpenTheater>?>(null) { value = withContext(Dispatchers.IO) { runCatching { listTheaters() }.getOrDefault(emptyList()) } }
    var chosen by remember { mutableStateOf<String?>(null) }
    var showShipped by remember { mutableStateOf(true) }
    val list = theaters
    fun open(f: File) { scope.launch { openFile(f) } }
    Column(modifier) {
        Strip {
            if (inline) Text("Command — open a file to edit", color = Hud.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            DeskButton("Browse…", tip = "Windows' own Open dialog, for a file anywhere on this PC") {
                scope.launch {
                    val picked = PcFiles.open("Open a campaign, TE or training", "@campaign", listOf(FileFilter("Campaigns, TEs and trainings", "cam", "tac", "trn")))
                    picked?.let { open(File(it.path)) }
                }
            }
            CheckLine("Show BMS's own files (starts, shipped TEs and trainings)", showShipped) { showShipped = it }
            Box(Modifier.weight(1f))
            CommandSession.opening?.let { Text("Opening $it…", color = Hud.Amber, fontSize = 12.sp) }
        }
        Rule()
        Row(Modifier.weight(1f).fillMaxWidth()) {
            // left: recent files and the theaters
            Column(Modifier.width(260.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(6.dp)) {
                val recent = CommandSession.recent.map(::File).filter { it.isFile }
                if (recent.isNotEmpty()) {
                    Text("RECENT", color = Hud.TextFaint, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(4.dp))
                    recent.forEach { f ->
                        Text(f.name, color = Hud.Text, fontSize = 12.sp, maxLines = 1, modifier = Modifier.fillMaxWidth().clickable { open(f) }.padding(horizontal = 6.dp, vertical = 4.dp))
                    }
                    Spacer(Modifier.height(8.dp)); Rule()
                }
                Text("THEATERS", color = Hud.TextFaint, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(4.dp))
                if (list == null) SmallNote("Reading the theaters…")
                else if (list.isEmpty()) SmallNote("No campaign folder with files was found. Is Falcon BMS's folder set (Setup)?")
                list?.forEach { ot ->
                    val on = (chosen ?: list.firstOrNull()?.t?.appId) == ot.t.appId
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(3.dp)).background(if (on) Hud.Amber.copy(alpha = 0.15f) else Hud.Surface.copy(alpha = 0f))
                            .clickable { chosen = ot.t.appId }.padding(horizontal = 6.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(ot.t.name, color = if (on) Hud.Amber else Hud.Text, fontSize = 12.sp, modifier = Modifier.weight(1f), maxLines = 1)
                        if (ot.current) Text("BMS's", color = Hud.TextFaint, fontSize = 10.5.sp)
                        Text("  ${ot.rows.size}", color = Hud.TextFaint, fontSize = 11.sp)
                    }
                }
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(Hud.Outline.copy(alpha = 0.5f)))
            // right: the chosen theater's files
            val ot = list?.firstOrNull { it.t.appId == (chosen ?: list.firstOrNull()?.t?.appId) }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp)) {
                    Text("NAME", color = Hud.TextFaint, fontSize = 10.5.sp, modifier = Modifier.weight(1f))
                    Text("KIND", color = Hud.TextFaint, fontSize = 10.5.sp, modifier = Modifier.width(90.dp))
                    Text("", modifier = Modifier.width(120.dp))
                    Text("MODIFIED", color = Hud.TextFaint, fontSize = 10.5.sp, modifier = Modifier.width(130.dp))
                }
                Rule()
                val rows = ot?.rows.orEmpty().filter { showShipped || !it.shipped }
                val fmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm") }
                val openList = androidx.compose.foundation.lazy.rememberLazyListState()
                Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(Modifier.fillMaxSize().padding(end = CMD_BAR), state = openList) {
                    items(rows, key = { it.file.absolutePath }) { r ->
                        Row(
                            Modifier.fillMaxWidth().clickable { open(r.file) }.padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(r.file.name, color = if (r.shipped) Hud.TextDim else Hud.Text, fontSize = 12.5.sp, modifier = Modifier.weight(1f), maxLines = 1)
                            Text(when (r.kind) { "te" -> "TE"; "training" -> "Training"; else -> "Campaign" }, color = Hud.TextDim, fontSize = 12.sp, modifier = Modifier.width(90.dp))
                            Box(Modifier.width(120.dp)) {
                                when {
                                    r.start != null -> Tip("${r.start}. It opens; Save writes a copy, and it is edited in place only through \"Edit BMS's campaign start\".") { Text("campaign start", color = Hud.Cyan, fontSize = 11.5.sp) }
                                    r.shipped -> Tip("Came with Falcon BMS or this theater: Save writes a copy, never over it.") { Text("BMS's own", color = Hud.Cyan, fontSize = 11.5.sp) }
                                }
                            }
                            Text(fmt.format(Date(r.modified)), color = Hud.TextDim, fontSize = 12.sp, modifier = Modifier.width(130.dp))
                        }
                        Rule(0.15f)
                    }
                }
                    CmdVBar(androidx.compose.foundation.rememberScrollbarAdapter(openList), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                }
            }
        }
    }
}
