package com.bmscompanion.app.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.bmscompanion.app.data.LocalImageActions
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.mission.LinkState
import com.bmscompanion.app.data.mission.MediaList
import com.bmscompanion.app.data.mission.MissionLink
import com.bmscompanion.app.data.mission.Shot
import com.bmscompanion.app.ui.Routes
import com.bmscompanion.app.ui.components.BmsTopBar
import com.bmscompanion.app.ui.components.isMedium
import com.bmscompanion.app.ui.go
import com.bmscompanion.app.ui.theme.Hud
import com.bmscompanion.app.ui.theme.LocalExtra
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Media: the screenshots taken in Falcon BMS, served by the bridge on the BMS PC (User\Pictures).

fun mediaViewRoute(name: String) = "media/view?name=${android.net.Uri.encode(name)}"

/** Thumbnails and the last list, kept while the app runs so going back to the grid is instant. */
private object MediaCache {
    // used from the UI thread only; a decoded thumbnail is about half a megabyte, so only a few screenfuls are kept
    private val thumbs = LinkedHashMap<String, Bitmap>()
    var list by mutableStateOf<MediaList?>(null)

    suspend fun thumb(s: Shot): Bitmap? {
        val key = "${s.name}@${s.time}"
        thumbs[key]?.let { return it }
        val bytes = MissionLink.fetchBytes(MissionLink.mediaPath("thumb", s.name)) ?: return null
        return Repo.decodeBitmap(bytes)?.also {
            thumbs[key] = it
            while (thumbs.size > 64) thumbs.remove(thumbs.keys.first())
        }
    }
}

private val dayFormat get() = SimpleDateFormat("EEEE d MMMM yyyy", Locale.US)
private val timeFormat get() = SimpleDateFormat("HH:mm:ss", Locale.US)

private fun sizeText(bytes: Long) = when {
    bytes >= 1L shl 30 -> String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024 * 1024))
    bytes >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
    else -> String.format(Locale.US, "%d KB", (bytes / 1024).toInt())
}

private fun actionIcon(name: String): ImageVector = when (name) {
    "share" -> Icons.Default.Share
    "copy" -> Icons.Default.ContentCopy
    "folder" -> Icons.Default.Folder
    else -> Icons.Default.Download
}

@Composable
private fun KeepPolling() {
    DisposableEffect(Unit) {
        MissionLink.acquire()
        onDispose { MissionLink.release() }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaScreen(nav: NavHostController) {
    KeepPolling()
    val state by MissionLink.state.collectAsState()
    val info by MissionLink.info.collectAsState()
    val scope = rememberCoroutineScope()
    val download = LocalImageActions.current.firstOrNull { it.icon == "download" }
    var downloading by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var confirmDelete by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val online = state is LinkState.Online
    val changeKey = info?.media?.let { "${it.count}-${it.latest}" }

    LaunchedEffect(changeKey, refresh, online) {
        if (!online) return@LaunchedEffect
        loading = true
        MissionLink.mediaList()?.let { MediaCache.list = it }
        loading = false
    }
    val list = MediaCache.list
    val shots = list?.shots.orEmpty()
    LaunchedEffect(shots) { selected = selected.filter { n -> shots.any { it.name == n } }.toSet() }

    Column(Modifier.fillMaxSize().background(Hud.Bg)) {
        BmsTopBar(
            if (selecting) "${selected.size} selected" else "Screenshots",
            if (selecting) "Tap pictures to select" else list?.let { l -> "${l.shots.size} pictures · ${sizeText(l.shots.sumOf { it.size })}" } ?: "Taken in Falcon BMS",
            onBack = if (selecting) ({ selecting = false; selected = emptySet() }) else null,
            actions = {
                if (selecting) {
                    TextButton({ selected = if (selected.size == shots.size) emptySet() else shots.map { it.name }.toSet() }) {
                        Text(if (selected.size == shots.size) "None" else "All", color = Hud.Amber)
                    }
                    if (download != null) IconButton({
                        if (downloading || selected.isEmpty()) return@IconButton
                        downloading = true
                        val names = shots.map { it.name }.filter { it in selected }
                        scope.launch {
                            var last: String? = null
                            names.forEach { n -> last = download.run(n) { MissionLink.fetchBytes(MissionLink.mediaPath("file", n), timeoutMs = 60_000) } }
                            message = if (names.size == 1) last else last?.let { "${names.size} screenshots: $it" }
                            downloading = false
                            selecting = false; selected = emptySet()
                        }
                    }, enabled = selected.isNotEmpty() && !downloading) {
                        if (downloading) CircularProgressIndicator(Modifier.size(20.dp), color = Hud.Cyan, strokeWidth = 2.dp)
                        else Icon(Icons.Default.Download, download.label, tint = if (selected.isNotEmpty()) Hud.Cyan else Hud.TextFaint)
                    }
                    IconButton({ if (selected.isNotEmpty()) confirmDelete = true }, enabled = selected.isNotEmpty()) { Icon(Icons.Default.Delete, "Delete", tint = if (selected.isNotEmpty()) Hud.Red else Hud.TextFaint) }
                } else {
                    if (shots.isNotEmpty()) TextButton({ selecting = true }) { Text("Select", color = Hud.Amber) }
                    IconButton({ refresh++ }) {
                        if (loading) CircularProgressIndicator(Modifier.size(20.dp), color = Hud.Amber, strokeWidth = 2.dp)
                        else Icon(Icons.Default.Refresh, "Refresh", tint = Hud.TextDim)
                    }
                }
            },
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                MissionLink.host == null -> MediaEmpty("Connect to the BMS PC", "Screenshots come from the PC running Falcon BMS. Set up the connection first.", "Go to Setup") { nav.go(Routes.SETUP) }
                list == null && !online -> MediaEmpty("Connecting…", (state as? LinkState.Offline)?.reason ?: "Waiting for the BMS PC.")
                list == null -> MediaEmpty("Loading…", "Reading the screenshot folder on the BMS PC.")
                !list.available -> MediaEmpty("Screenshot folder not found", "BMS Companion looked in ${list.dir ?: "User\\Pictures"}. Take a screenshot in BMS first, or set the screenshots folder in its settings on the PC.")
                shots.isEmpty() -> MediaEmpty("No screenshots yet", "Take one in Falcon BMS (PrtScr, or your screenshot key). It appears here within a few seconds.")
                else -> {
                    val groups = remember(shots) { shots.groupBy { dayFormat.format(Date(it.time)) } }
                    LazyVerticalGrid(
                        GridCells.Adaptive(if (isMedium()) 220.dp else 150.dp),
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        groups.forEach { (day, dayShots) ->
                            item(key = "h-$day", span = { GridItemSpan(maxLineSpan) }) {
                                Row(Modifier.padding(top = 8.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(day.uppercase(Locale.US), style = LocalExtra.current.overline, color = Hud.TextDim, modifier = Modifier.weight(1f))
                                    Text("${dayShots.size}", fontSize = 11.sp, color = Hud.TextFaint)
                                }
                            }
                            items(dayShots, key = { it.name }) { s ->
                                val isSel = s.name in selected
                                Box(
                                    Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)).background(Hud.Surface2)
                                        .border(2.dp, if (isSel) Hud.Amber else Color.Transparent, RoundedCornerShape(10.dp))
                                        .combinedClickable(
                                            onClick = {
                                                if (selecting) selected = if (isSel) selected - s.name else selected + s.name
                                                else nav.go(mediaViewRoute(s.name))
                                            },
                                            onLongClick = { selecting = true; selected = selected + s.name },
                                        ),
                                ) {
                                    val bmp by produceState<Bitmap?>(null, s.name, s.time) { value = MediaCache.thumb(s) }
                                    bmp?.let { Image(it.asImageBitmap(), s.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                                    Text(
                                        timeFormat.format(Date(s.time)),
                                        Modifier.align(Alignment.BottomStart).padding(6.dp).clip(RoundedCornerShape(6.dp)).background(Hud.Bg.copy(alpha = 0.7f)).padding(horizontal = 6.dp, vertical = 2.dp),
                                        style = LocalExtra.current.monoSmall.copy(fontSize = 11.sp), color = Hud.Text,
                                    )
                                    if (selecting) Icon(
                                        if (isSel) Icons.Default.CheckCircle else Icons.Outlined.Circle, null,
                                        tint = if (isSel) Hud.Amber else Color.White, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(24.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            message?.let { m -> Toast(m, Modifier.align(Alignment.BottomCenter)) { message = null } }
        }
    }

    if (confirmDelete) {
        DeleteDialog(selected.size, onDismiss = { confirmDelete = false }) {
            confirmDelete = false
            val names = selected.toList()
            scope.launch {
                val n = MissionLink.deleteMedia(names)
                message = if (n == null) "Could not reach the BMS PC" else "$n moved to the Recycle Bin on the BMS PC"
                selected = emptySet(); selecting = false
                MissionLink.mediaList()?.let { MediaCache.list = it }
            }
        }
    }
}

@Composable
private fun MediaEmpty(title: String, text: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Default.PhotoLibrary, null, tint = Hud.TextFaint, modifier = Modifier.size(48.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, color = Hud.Text)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = Hud.TextDim, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            if (action != null && onAction != null) Text(
                action, Modifier.clip(RoundedCornerShape(10.dp)).background(Hud.Amber).clickable(onClick = onAction).padding(horizontal = 16.dp, vertical = 9.dp),
                color = Hud.Bg, fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun DeleteDialog(count: Int, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (count == 1) "Delete this screenshot?" else "Delete $count screenshots?") },
        text = { Text("They are moved to the Recycle Bin on the BMS PC, so you can still restore them there.") },
        confirmButton = { TextButton(onConfirm) { Text("Delete", color = Hud.Red, fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel", color = Hud.TextDim) } },
        containerColor = Hud.Surface,
    )
}

@Composable
private fun Toast(text: String, modifier: Modifier, onDone: () -> Unit) {
    LaunchedEffect(text) { kotlinx.coroutines.delay(3500); onDone() }
    Text(
        text, modifier.padding(16.dp).clip(RoundedCornerShape(10.dp)).background(Hud.Surface3).border(1.dp, Hud.Outline, RoundedCornerShape(10.dp))
            .clickable(onClick = onDone).padding(horizontal = 14.dp, vertical = 10.dp),
        color = Hud.Text,
    )
}

/** Full-screen viewer: zoom and pan, previous/next, share or save with the platform's actions, delete. */
@Composable
fun MediaViewerScreen(nav: NavHostController, startName: String) {
    KeepPolling()
    val scope = rememberCoroutineScope()
    val actions = LocalImageActions.current
    var name by remember { mutableStateOf(startName) }
    var confirmDelete by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { if (MediaCache.list == null) MissionLink.mediaList()?.let { MediaCache.list = it } }
    val shots = MediaCache.list?.shots.orEmpty()
    val index = shots.indexOfFirst { it.name == name }
    val shot = shots.getOrNull(index)
    val max = if (isMedium()) 2560 else 1600
    val bmp by produceState<Bitmap?>(null, name) {
        value = null
        value = MissionLink.fetchBytes(MissionLink.mediaPath("view", name, max))?.let { Repo.decodeBitmap(it) }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val b = bmp
        if (b != null) ZoomableBitmap(b, Modifier.fillMaxSize(), background = Color.Black)
        else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Hud.Amber, strokeWidth = 2.dp) }

        Row(
            Modifier.fillMaxWidth().background(Hud.Bg.copy(alpha = 0.82f)).padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton({ nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Hud.Text) }
            Column(Modifier.weight(1f)) {
                Text(shot?.let { SimpleDateFormat("d MMM yyyy · HH:mm:ss", Locale.US).format(Date(it.time)) } ?: name, color = Hud.Text, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(
                    listOfNotNull(name, shot?.let { sizeText(it.size) }, shot?.w?.let { w -> shot.h?.let { h -> "${w}×$h" } }, if (index >= 0) "${index + 1}/${shots.size}" else null).joinToString(" · "),
                    fontSize = 11.sp, color = Hud.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton({ shots.getOrNull(index - 1)?.let { name = it.name } }, enabled = index > 0) { Icon(Icons.Default.ChevronLeft, "Newer", tint = if (index > 0) Hud.Text else Hud.TextFaint) }
            IconButton({ shots.getOrNull(index + 1)?.let { name = it.name } }, enabled = index in 0 until shots.lastIndex) { Icon(Icons.Default.ChevronRight, "Older", tint = if (index in 0 until shots.lastIndex) Hud.Text else Hud.TextFaint) }
            actions.forEach { a ->
                IconButton({
                    if (busy) return@IconButton
                    busy = true
                    val current = name
                    scope.launch {
                        message = a.run(current) { MissionLink.fetchBytes(MissionLink.mediaPath("file", current), timeoutMs = 60_000) }
                        busy = false
                    }
                }) { Icon(actionIcon(a.icon), a.label, tint = Hud.Cyan) }
            }
            IconButton({ confirmDelete = true }) { Icon(Icons.Default.Delete, "Delete", tint = Hud.Red) }
        }
        if (busy) CircularProgressIndicator(Modifier.align(Alignment.Center).size(36.dp), color = Hud.Cyan, strokeWidth = 3.dp)
        message?.let { m -> Toast(m, Modifier.align(Alignment.BottomCenter)) { message = null } }
    }

    if (confirmDelete) {
        DeleteDialog(1, onDismiss = { confirmDelete = false }) {
            confirmDelete = false
            val current = name
            val next = shots.getOrNull(index + 1) ?: shots.getOrNull(index - 1)
            scope.launch {
                val n = MissionLink.deleteMedia(listOf(current))
                if (n == null || n == 0) { message = "Could not delete it on the BMS PC"; return@launch }
                MediaCache.list = MediaCache.list?.let { l -> l.copy(shots = l.shots.filterNot { it.name == current }) }
                if (next != null) name = next.name else nav.popBackStack()
            }
        }
    }
}
