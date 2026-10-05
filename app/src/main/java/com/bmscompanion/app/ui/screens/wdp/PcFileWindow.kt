package com.bmscompanion.app.ui.screens.wdp

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.bmscompanion.app.data.BrowseSession
import com.bmscompanion.app.data.FileMode
import com.bmscompanion.app.data.FileRequest
import com.bmscompanion.app.data.PcFiles
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.mission.MissionLink
import com.bmscompanion.app.data.mission.PcFileEntry
import com.bmscompanion.app.data.mission.PcFolder
import com.bmscompanion.app.data.mission.PcPlace
import com.bmscompanion.app.data.mission.PcPlaces
import com.bmscompanion.app.ui.theme.Hud
import kotlinx.coroutines.launch

/**
 * Where [PcFiles]' window is drawn, on a device that is not the BMS PC's own window: over everything, as a dialog
 * (Back and Escape are its Cancel). Drawn once, by the app's root (`AppRoot`); with none drawn, a file cannot be picked
 * and [PcFiles.open] answers null at once rather than wait for a window nobody can see.
 */
@Composable
fun PcFileWindowHost() {
    DisposableEffect(Unit) {
        PcFiles.hosts++
        onDispose { PcFiles.hosts-- }
    }
    val s = PcFiles.session ?: return
    key(s) {
        Dialog(
            onDismissRequest = { PcFiles.finish(s, null, s.request.filterIndex) },
            properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = false, usePlatformDefaultWidth = false),
        ) {
            BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                // a phone gets the whole screen; a tablet or a PC a dialog of a sensible size
                // by finger on a tablet, nearly the whole screen: the listing is what the window is for
                val sheet = maxWidth < 600.dp || maxHeight < 460.dp
                val finger = WdpTouch.device
                val w = if (sheet) maxWidth else if (finger) maxWidth - 24.dp else minOf(maxWidth * 0.92f, 900.dp)
                val h = if (sheet) maxHeight else if (finger) maxHeight - 24.dp else minOf(maxHeight * 0.9f, 700.dp)
                PlannerWindowFrame(
                    s.request.title.ifBlank { when (s.request.mode) { FileMode.SAVE -> "Save"; FileMode.FOLDER -> "Select Folder"; else -> "Open" } }, "Files",
                    Modifier.width(w).height(h),
                    onClose = { PcFiles.finish(s, null, s.request.filterIndex) }, sheet = sheet,
                ) { PcFileWindow(s, wide = w >= 640.dp) }
            }
        }
    }
}

/** How the files of a folder are ordered (the folders always by name, first). */
private enum class FileSort(val label: String) { NAME("Name"), NEWEST("Newest first") }

/** What the window keeps between one opening and the next: the last folder and the order. */
private object PcFileMemory {
    var lastFolder: String? = null
    var places: PcPlaces? = null
    var sort: FileSort
        get() = runCatching { FileSort.valueOf(Repo.getString("files_sort") ?: "NAME") }.getOrDefault(FileSort.NAME)
        set(v) { runCatching { Repo.putString("files_sort", v.name) } }
}

/** The listing of the whole PC: its places and drives, shown where a path is empty. */
private const val PC_ROOT = ""

/**
 * The window itself: where it is (a path with Up, and the places and drives), what is there (folders, then the files
 * of the chosen type, by name or newest first), and Open — or, for Save, the name and Save, with the question when a
 * file of that name is there already.
 */
@Composable
private fun ColumnScope.PcFileWindow(s: BrowseSession, wide: Boolean) {
    val r = s.request
    val scope = rememberCoroutineScope()
    val finger = WdpTouch.device
    val filters = r.filters
    var filter by remember { mutableIntStateOf(r.filterIndex.coerceIn(0, (filters.size - 1).coerceAtLeast(0))) }
    var sort by remember { mutableStateOf(PcFileMemory.sort) }
    var path by remember { mutableStateOf(r.startDir?.takeIf { it.isNotBlank() } ?: PcFileMemory.lastFolder ?: "@bms") }
    var reload by remember { mutableIntStateOf(0) }
    var folder by remember { mutableStateOf<PcFolder?>(null) }
    var places by remember { mutableStateOf(PcFileMemory.places) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<PcFileEntry?>(null) }
    var name by remember { mutableStateOf(r.fileName?.takeIf { r.mode == FileMode.SAVE && '*' !in it && '?' !in it }.orEmpty()) }
    var question by remember { mutableStateOf<PcFileEntry?>(null) }
    var saveNote by remember { mutableStateOf<String?>(null) }
    val types = filters.getOrNull(filter)?.extensions.orEmpty()
    // the path [folder] was listed for: until the folder just asked for is listed, the one before is not picked
    var listedFor by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val a = MissionLink.filesPlaces()
        a.value?.let { places = it; PcFileMemory.places = it }
    }
    LaunchedEffect(path, filter, reload) {
        if (path == PC_ROOT) { folder = null; error = null; loading = false; return@LaunchedEffect }
        loading = true
        val asked = path
        val a = MissionLink.filesList(path, types)
        loading = false
        val v = a.value
        if (v != null && a.error == null) {
            folder = v
            listedFor = asked
            error = null
            PcFileMemory.lastFolder = v.path
            // Open with a file name (WDP's FileName): that file is picked where it is listed
            if (r.mode == FileMode.OPEN && selected == null) {
                selected = v.entries.firstOrNull { !it.dir && it.name.equals(r.fileName?.trim(), ignoreCase = true) }
            }
        } else {
            error = a.error ?: "The PC did not answer."
        }
    }

    fun go(p: String) { selected = null; question = null; saveNote = null; path = p }
    fun done(p: String) = PcFiles.finish(s, p, filter)
    val shownPath = if (path == PC_ROOT) PC_ROOT else folder?.path ?: path

    // ---- where (Up, the path, Refresh) and which files (the types, the order): one slim row where there is room
    val where = @Composable { m: Modifier ->
        Row(m, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val up = when {
                shownPath == PC_ROOT -> null
                else -> folder?.parent ?: PC_ROOT
            }
            IconBox(Icons.Default.ArrowUpward, "Up", "Files/Up", enabled = up != null) { up?.let { go(it) } }
            Crumbs(shownPath, Modifier.weight(1f)) { go(it) }
            IconBox(Icons.Default.Refresh, "Refresh", "Files/Refresh", enabled = !loading) { reload++; scope.launch { MissionLink.filesPlaces().value?.let { places = it; PcFileMemory.places = it } } }
        }
    }
    val which = @Composable { m: Modifier ->
        Row(
            m.horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            filters.forEachIndexed { i, f -> PlannerChip(f.label, on = i == filter, probe = "Files/Type/$i") { filter = i; selected = null } }
            PlannerSegments(FileSort.entries.map { it.label }, FileSort.entries.map { "Files/Sort/${it.name}" }, sort.ordinal) {
                sort = FileSort.entries[it]; PcFileMemory.sort = sort
            }
        }
    }
    if (wide) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            where(Modifier.weight(1f))
            which(Modifier.widthIn(max = 420.dp))
        }
    } else {
        where(Modifier.fillMaxWidth())
        which(Modifier.fillMaxWidth())
    }

    // ---- the places and the listing, side by side where there is room
    Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (wide) {
            Column(
                Modifier.width(200.dp).fillMaxHeight().background(Hud.Surface2)
                    .verticalScroll(rememberScrollState()).padding(vertical = 4.dp),
            ) { PlaceList(places, shownPath) { go(it) } }
        }
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (!wide) PlaceChips(places, shownPath) { go(it) }
            folder?.note?.takeIf { path != PC_ROOT }?.let { Text(it, color = Hud.Amber, fontSize = fingerSp(11.5f), lineHeight = 15.sp) }
            error?.let { e ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(e, color = Hud.Red, fontSize = fingerSp(12.5f), lineHeight = 17.sp, modifier = Modifier.weight(1f).plannerProbe("Files/Error"))
                    PlannerButton("Try again", probe = "Files/Retry", small = true) { reload++ }
                }
            }
            Box(
                Modifier.fillMaxWidth().weight(1f).background(Hud.Bg.copy(alpha = 0.55f))
                    .border(1.dp, Hud.Outline.copy(alpha = 0.6f)),
            ) {
                if (path == PC_ROOT) {
                    PcRootList(places, finger) { go(it) }
                } else {
                    val f = folder
                    // choosing a folder (PcFiles.folder) lists the folders only
                    val rows = remember(f, sort) { f?.let { ordered(it.entries, sort) }.orEmpty().filter { r.mode != FileMode.FOLDER || it.dir } }
                    val list = rememberLazyListState()
                    LaunchedEffect(f?.path, filter, sort) { runCatching { list.scrollToItem(0) } }
                    LazyColumn(Modifier.fillMaxSize().alpha(if (loading) 0.55f else 1f), state = list) {
                        items(rows, key = { it.path }) { e ->
                            EntryRow(e, selected = selected?.path == e.path, wide = wide, finger = finger) {
                                when {
                                    e.dir -> go(e.path)
                                    r.mode == FileMode.SAVE -> { selected = e; name = e.name; question = null; saveNote = null }
                                    // a second tap on the picked file opens it (a double click with a mouse)
                                    selected?.path == e.path -> done(e.path)
                                    else -> selected = e
                                }
                            }
                        }
                        if (f != null && rows.isEmpty()) item("empty") {
                            Text(
                                "No folders here" + when {
                                    r.mode == FileMode.FOLDER -> "."
                                    types.isEmpty() -> ", and no files."
                                    else -> ", and no " + types.joinToString(" or ") { ".$it" } + " files."
                                },
                                color = Hud.TextDim, fontSize = fingerSp(12.5f), modifier = Modifier.padding(14.dp),
                            )
                        }
                        if (f?.truncated == true) item("more") {
                            Text("Only the first ${PcFolder.MAX} items are listed.", color = Hud.TextFaint, fontSize = fingerSp(11f), modifier = Modifier.padding(10.dp))
                        }
                    }
                    if (f == null && error == null) {
                        Text("Reading the folder on the PC…", color = Hud.TextDim, fontSize = fingerSp(12.5f), modifier = Modifier.align(Alignment.Center))
                    }
                }
            }
        }
    }

    // ---- the answer
    question?.let { q ->
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Hud.Amber.copy(alpha = 0.12f)).padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("${q.name} is already there. Replace it?", color = Hud.Amber, fontSize = fingerSp(13f), modifier = Modifier.weight(1f).plannerProbe("Files/Replace?"))
            PlannerButton("No", probe = "Files/ReplaceNo") { question = null }
            PlannerButton("Replace", probe = "Files/Replace", primary = true) { done(q.path) }
        }
    }
    saveNote?.let { Text(it, color = Hud.Red, fontSize = fingerSp(12f), lineHeight = 16.sp, modifier = Modifier.plannerProbe("Files/SaveNote")) }

    val canPickHere = path != PC_ROOT && folder != null && listedFor == path
    fun trySave() {
        val f = folder ?: return
        val typed = name.trim()
        val why = nameProblem(typed)
        if (why != null) { saveNote = why; return }
        val ext = r.defaultExt?.takeIf { it.isNotBlank() } ?: types.firstOrNull()
        val final = if ('.' !in typed.trimStart('.') && ext != null) "$typed.$ext" else typed
        val full = f.path.trimEnd('\\') + "\\" + final
        saveNote = null
        scope.launch {
            val a = MissionLink.filesStat(full)
            val e = a.value
            when {
                e == null || a.error != null -> saveNote = a.error ?: "The PC did not answer."
                e.dir -> saveNote = "$final is a folder."
                !e.write -> saveNote = "BMS Companion does not save .${final.substringAfterLast('.', "")} files on the PC."
                e.exists -> question = e
                else -> done(full)
            }
        }
    }

    @Composable
    fun Buttons() {
        PlannerButton("Cancel", probe = "Files/Cancel") { PcFiles.finish(s, null, filter) }
        if (r.mode == FileMode.SAVE) {
            PlannerButton("Save", probe = "Files/Save", primary = true, enabled = canPickHere && name.isNotBlank()) { trySave() }
        } else if (r.mode == FileMode.FOLDER) {
            // the folder on show is the answer (WDP's FolderBrowserDialog: the folder selected, here the one opened)
            PlannerButton("Select Folder", probe = "Files/SelectFolder", primary = true, enabled = canPickHere) { folder?.let { done(it.path) } }
        } else {
            PlannerButton("Open", probe = "Files/Open", primary = true, enabled = canPickHere && selected != null) { selected?.let { done(it.path) } }
        }
    }

    if (r.mode == FileMode.SAVE) {
        val field = @Composable { m: Modifier ->
            Row(
                m.heightIn(min = if (finger) 42.dp else 32.dp).clip(RoundedCornerShape(6.dp)).background(Hud.Surface)
                    .border(1.dp, Hud.Outline, RoundedCornerShape(6.dp)).padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Name ", color = Hud.TextFaint, fontSize = fingerSp(12f))
                BasicTextField(
                    name, { name = it.replace("\n", ""); question = null; saveNote = null },
                    singleLine = true,
                    textStyle = TextStyle(color = Hud.Text, fontSize = fingerSp(14f)),
                    cursorBrush = SolidColor(Hud.Amber),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { trySave() }),
                    modifier = Modifier.weight(1f).plannerProbe("Files/Name"),
                )
            }
        }
        if (wide) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                field(Modifier.weight(1f))
                Buttons()
            }
        } else {
            field(Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) { Buttons() }
        }
    } else {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                selected?.name ?: if (canPickHere) "Pick a file" + (if (types.isEmpty()) "" else " (" + types.joinToString(", ") { ".$it" } + ")") else "Pick a folder",
                color = if (selected != null) Hud.Text else Hud.TextFaint, fontSize = fingerSp(13f),
                fontWeight = if (selected != null) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).plannerProbe("Files/Picked"),
            )
            Buttons()
        }
    }
}

/** Folders by name, then files by name or newest first. */
private fun ordered(entries: List<PcFileEntry>, sort: FileSort): List<PcFileEntry> {
    val (dirs, files) = entries.partition { it.dir }
    val byName = compareBy<PcFileEntry> { it.name.lowercase() }
    return dirs.sortedWith(byName) + when (sort) {
        FileSort.NAME -> files.sortedWith(byName)
        FileSort.NEWEST -> files.sortedWith(compareByDescending<PcFileEntry> { it.modified }.then(byName))
    }
}

/** Why Windows would not take [n] as a file name, or null. */
private fun nameProblem(n: String): String? = when {
    n.isEmpty() -> "Type a name."
    n.any { it in "\\/:*?\"<>|" || it < ' ' } -> "A file name cannot hold \\ / : * ? \" < > |."
    n.trimEnd().endsWith(".") -> "A file name cannot end with a dot."
    n.substringBefore('.').trim().uppercase() in setOf("CON", "PRN", "AUX", "NUL") ||
        Regex("^(COM|LPT)[0-9]$").matches(n.substringBefore('.').trim().uppercase()) -> "Windows keeps that name for a device."
    else -> null
}

/** The path as buttons, one per folder: a tap goes there. The first is the whole PC (its places and drives). */
@Composable
private fun Crumbs(path: String, modifier: Modifier, onGo: (String) -> Unit) {
    val scroll = rememberScrollState()
    LaunchedEffect(path) { runCatching { scroll.scrollTo(scroll.maxValue) } }
    LaunchedEffect(scroll.maxValue) { runCatching { scroll.scrollTo(scroll.maxValue) } }
    val parts = mutableListOf("BMS PC" to PC_ROOT)
    if (path.length >= 2 && path[1] == ':') {
        val root = path.take(2) + "\\"
        parts += path.take(2) to root
        var acc = root
        for (seg in path.substring(minOf(3, path.length)).split('\\').filter { it.isNotEmpty() }) {
            acc = if (acc.endsWith("\\")) acc + seg else "$acc\\$seg"
            parts += seg to acc
        }
    }
    Row(
        modifier.clip(RoundedCornerShape(6.dp)).background(Hud.Surface2).horizontalScroll(scroll).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        parts.forEachIndexed { i, (label, target) ->
            if (i > 0) Icon(Icons.Default.ChevronRight, null, tint = Hud.TextFaint, modifier = Modifier.size(16.dp))
            val last = i == parts.lastIndex
            Box(
                Modifier.heightIn(min = toolHeight).clip(RoundedCornerShape(4.dp))
                    .plannerPress(!last) { onGo(target) }.padding(horizontal = 6.dp).plannerProbe("Files/Crumb/$i"),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label, color = if (last) Hud.Text else Hud.TextDim, fontSize = fingerSp(12.5f),
                    fontWeight = if (last) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1,
                )
            }
        }
    }
}

/** A square button with an icon, a finger's size where the Planner is worked by one. */
@Composable
private fun IconBox(icon: ImageVector, label: String, probe: String, enabled: Boolean = true, onClick: () -> Unit) {
    val side: Dp = toolHeight
    Box(
        Modifier.size(side).clip(RoundedCornerShape(6.dp)).background(Hud.Surface3).plannerPress(enabled) { onClick() }
            .alpha(if (enabled) 1f else 0.4f).plannerProbe(probe),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = Hud.Text, modifier = Modifier.size(if (WdpTouch.device) 22.dp else 18.dp)) }
}

/** One folder or file: its icon, name, size and when it was changed. The picked one is lit. */
@Composable
private fun EntryRow(e: PcFileEntry, selected: Boolean, wide: Boolean, finger: Boolean, onClick: () -> Unit) {
    val tint = if (e.dir) Hud.Amber else Hud.TextDim
    Row(
        Modifier.fillMaxWidth().heightIn(min = if (finger) 44.dp else 30.dp)
            .background(if (selected) Hud.Amber.copy(alpha = 0.14f) else Color.Transparent)
            .plannerPress { onClick() }.padding(horizontal = 10.dp, vertical = 4.dp)
            .plannerProbe("Files/Entry/${e.name}"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(iconOf(e), null, tint = tint, modifier = Modifier.size(if (finger) 22.dp else 18.dp))
        val details = listOfNotNull(if (e.dir) null else sizeWords(e.size), fileWhen(e.modified).takeIf { it.isNotEmpty() })
        if (wide) {
            Text(
                e.name, color = if (selected) Hud.Amber else Hud.Text, fontSize = fingerSp(13.5f), maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            Text(fileWhen(e.modified), color = Hud.TextFaint, fontSize = fingerSp(11.5f), maxLines = 1, textAlign = TextAlign.End, modifier = Modifier.width(120.dp))
            Text(if (e.dir) "" else sizeWords(e.size), color = Hud.TextFaint, fontSize = fingerSp(11.5f), maxLines = 1, textAlign = TextAlign.End, modifier = Modifier.width(70.dp))
        } else {
            Column(Modifier.weight(1f)) {
                Text(e.name, color = if (selected) Hud.Amber else Hud.Text, fontSize = fingerSp(13.5f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (details.isNotEmpty()) Text(details.joinToString(" · "), color = Hud.TextFaint, fontSize = fingerSp(11f), maxLines = 1)
            }
        }
    }
}

/** The whole PC, where the path is empty: its places, then its drives, each a row like a folder's. */
@Composable
private fun PcRootList(places: PcPlaces?, finger: Boolean, onGo: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        if (places == null) item("wait") { Text("Asking the PC for its drives…", color = Hud.TextDim, fontSize = fingerSp(12.5f), modifier = Modifier.padding(14.dp)) }
        places?.let { p ->
            if (p.places.isNotEmpty()) item("h1") { ListHead("Places") }
            items(p.places, key = { "p" + it.path }) { PlaceRow(it, selected = false, finger = finger) { onGo(it.path) } }
            if (p.drives.isNotEmpty()) item("h2") { ListHead("Drives") }
            items(p.drives, key = { "d" + it.path }) { PlaceRow(it, selected = false, finger = finger) { onGo(it.path) } }
        }
    }
}

/** The side column of a wide window: the places, then the drives; the one the listing is in is lit. */
@Composable
private fun PlaceList(places: PcPlaces?, current: String, onGo: (String) -> Unit) {
    val finger = WdpTouch.device
    if (places == null) {
        Text("Asking the PC…", color = Hud.TextFaint, fontSize = fingerSp(11.5f), modifier = Modifier.padding(10.dp))
        return
    }
    ListHead("Places")
    for (p in places.places) PlaceRow(p, selected = sameFolder(p.path, current), finger = finger, compact = true) { onGo(p.path) }
    Spacer(Modifier.height(6.dp))
    ListHead("Drives")
    for (p in places.drives) PlaceRow(p, selected = sameFolder(p.path, current), finger = finger, compact = true) { onGo(p.path) }
}

/** The places and drives as one row of chips over the listing, on a narrow window. */
@Composable
private fun PlaceChips(places: PcPlaces?, current: String, onGo: (String) -> Unit) {
    val all = places?.let { it.places + it.drives }.orEmpty()
    if (all.isEmpty()) return
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        PlannerChip("BMS PC", on = current == PC_ROOT, probe = "Files/Place/pc") { onGo(PC_ROOT) }
        for (p in all) PlannerChip(p.name, on = sameFolder(p.path, current), probe = "Files/Place/${p.kind}/${p.name}") { onGo(p.path) }
    }
}

@Composable
private fun ListHead(text: String) {
    Text(
        text.uppercase(), color = Hud.TextFaint, fontSize = 10.5.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 2.dp),
    )
}

@Composable
private fun PlaceRow(p: PcPlace, selected: Boolean, finger: Boolean, compact: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = if (finger) 40.dp else if (compact) 30.dp else 36.dp)
            .background(if (selected) Hud.Amber.copy(alpha = 0.14f) else Color.Transparent)
            .plannerPress { onClick() }.padding(horizontal = 12.dp, vertical = 3.dp)
            .plannerProbe("Files/Place/${p.kind}/${p.name}"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(placeIcon(p.kind), null, tint = if (selected) Hud.Amber else Hud.Cyan, modifier = Modifier.size(if (finger) 22.dp else 18.dp))
        Column(Modifier.weight(1f)) {
            Text(p.name, color = if (selected) Hud.Amber else Hud.Text, fontSize = fingerSp(if (compact) 12.5f else 13.5f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!compact) p.detail?.let { Text(it, color = Hud.TextFaint, fontSize = fingerSp(10.5f), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

private fun sameFolder(a: String, b: String) = a.trimEnd('\\').equals(b.trimEnd('\\'), ignoreCase = true)

private fun placeIcon(kind: String): ImageVector = when (kind) {
    "bms" -> Icons.Default.Flight
    "config" -> Icons.Default.Settings
    "campaign", "datacampaign" -> Icons.Default.Map
    "wdp", "planner" -> Icons.Default.Calculate
    "datacards" -> Icons.Default.Description
    "documents" -> Icons.Default.Description
    "desktop" -> Icons.Default.DesktopWindows
    "downloads" -> Icons.Default.Download
    "network" -> Icons.Default.Cloud
    "removable" -> Icons.Default.Usb
    "cd" -> Icons.Default.Album
    "pc" -> Icons.Default.Computer
    else -> Icons.Default.Storage
}

private fun iconOf(e: PcFileEntry): ImageVector {
    if (e.dir) return Icons.Default.Folder
    return when (e.name.substringAfterLast('.', "").lowercase()) {
        "png", "jpg", "jpeg", "bmp", "dds", "gif" -> Icons.Default.Image
        "fmap", "twx" -> Icons.Default.Cloud
        "ini", "txt", "cfg", "ppi", "bdc", "lst" -> Icons.Default.Description
        else -> Icons.Default.InsertDriveFile
    }
}

/** "412 KB", "3.4 MB"; a few bytes are still a KB, as Explorer shows them. */
private fun sizeWords(bytes: Long): String = when {
    bytes <= 0 -> "0 KB"
    bytes < 1024 * 1024 -> "${(bytes + 1023) / 1024} KB"
    else -> {
        val tenths = (bytes * 10 + 512 * 1024) / (1024 * 1024)
        "${tenths / 10}.${tenths % 10} MB"
    }
}
