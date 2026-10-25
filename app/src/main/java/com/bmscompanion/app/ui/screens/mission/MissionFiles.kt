package com.bmscompanion.app.ui.screens.mission

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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
import com.bmscompanion.app.data.FileFilter
import com.bmscompanion.app.data.PcFiles
import com.bmscompanion.app.data.Platform
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.mission.MissionLink
import com.bmscompanion.app.data.mission.PcAnswer
import com.bmscompanion.app.data.mission.PcFileView
import com.bmscompanion.app.ui.components.SearchField
import com.bmscompanion.app.ui.screens.ZoomableBitmap
import com.bmscompanion.app.ui.theme.Hud
import com.bmscompanion.app.ui.theme.Mono
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

// The Files tab (1.4.0): a file of the BMS PC — a text or log, an HTML page, a picture, a PDF — read on this device.
// The PC opens it through its own guarded routes (`/api/files/view`, `/api/files/picture`, `/api/files/pdfpage`;
// FileViewRoutes on the PC): read only, only those kinds, never a program. Several files stay open as the tab's own
// sub-tabs, remembered on the device. It is shown by default on every device (a pilot on a phone reads a log or a
// manual page as much as one at a desk) and hidden with one switch on Setup's Mission pages card ([FilesTabPrefs]).

/** Whether the Files tab shows: on until the pilot turns it off (Setup → Mission pages). */
object FilesTabPrefs {
    private const val KEY = "mission_tab_files"
    private var v by com.bmscompanion.app.data.mission.prefState(KEY) { Repo.getInt(KEY, 1) }
    var shown: Boolean
        get() = v == 1
        set(x) { v = if (x) 1 else 0; Repo.putInt(KEY, v) }
}

/** The files open in the tab, in their order, and the one on show: kept in the device's prefs. */
object OpenFiles {
    private const val KEY = "files_open"
    private const val SEL = "files_sel"
    private const val LAST = "files_last_dir"
    const val MAX = 12

    private fun kept() = Repo.getString(KEY)?.split('\n')?.filter { it.isNotBlank() }.orEmpty().take(MAX)
    val paths = mutableStateListOf<String>().apply { addAll(kept()) }
    // the file on show is where the pilot is: another device's is taken when this one becomes active (MissionPrefs.NAV_KEYS)
    var selected by com.bmscompanion.app.data.mission.prefIntState(SEL) { Repo.getInt(SEL, 0) }
        private set

    init {
        // the files another device opened or closed (MissionSync)
        com.bmscompanion.app.data.mission.PrefWatch.on(KEY) {
            val now = kept()
            if (now != paths.toList()) { paths.clear(); paths.addAll(now) }
            if (selected > paths.lastIndex) selected = paths.lastIndex.coerceAtLeast(0)
        }
    }

    /** The folder the last file was opened from, for the next Open file… */
    val lastFolder: String? get() = Repo.getString(LAST)?.takeIf { it.isNotBlank() }

    fun open(path: String) {
        val i = paths.indexOfFirst { it.equals(path, ignoreCase = true) }
        if (i >= 0) select(i)
        else {
            if (paths.size >= MAX) paths.removeAt(0)
            paths += path
            select(paths.lastIndex)
        }
        Repo.putString(LAST, path.substringBeforeLast('\\', ""))
        save()
    }

    fun select(i: Int) { selected = i.coerceIn(0, (paths.size - 1).coerceAtLeast(0)); Repo.putInt(SEL, selected) }

    fun close(i: Int) {
        if (i !in paths.indices) return
        paths.removeAt(i)
        if (selected >= i) select(selected - 1)
        save()
    }

    private fun save() = Repo.putString(KEY, paths.joinToString("\n"))

    val current: String? get() = paths.getOrNull(selected)
}

private val FILTERS = listOf(
    FileFilter("Files the Files tab opens", PcFileView.TYPES),
    FileFilter("Text and logs", PcFileView.TEXT_TYPES),
    FileFilter("HTML pages", PcFileView.HTML_TYPES),
    FileFilter("Pictures", PcFileView.IMAGE_TYPES),
    FileFilter("PDF documents", PcFileView.PDF_TYPES),
)

/** Opens Windows' dialog (on the BMS PC's own window) or the PC's folder list (anywhere else), and the file picked. */
private suspend fun pickFile(start: String?) {
    val picked = PcFiles.open("Open a file of the BMS PC", startDir = start ?: OpenFiles.lastFolder ?: "@bms", filters = FILTERS) ?: return
    OpenFiles.open(picked.path)
}

@Composable
fun MissionFilesPane(env: MissionEnv) {
    val scope = rememberCoroutineScope()
    val info by MissionLink.info.collectAsState()
    Column(Modifier.fillMaxSize()) {
        // the open files as sub-tabs, and Open file…
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            OpenFiles.paths.forEachIndexed { i, p ->
                FileChip(p, i == OpenFiles.selected, onClick = { OpenFiles.select(i) }, onClose = { OpenFiles.close(i) })
            }
            Row(
                Modifier.clip(RoundedCornerShape(8.dp)).background(Hud.Amber.copy(alpha = 0.14f)).border(1.dp, Hud.Amber.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
                    .clickable { scope.launch { pickFile(null) } }.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.FolderOpen, null, tint = Hud.Amber, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(6.dp))
                Text("Open file…", color = Hud.Amber, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Hud.Outline.copy(alpha = 0.5f)))
        val path = OpenFiles.current
        when {
            info == null -> PaneEmpty("Not linked to the BMS PC", "The Files tab reads files of the PC that runs Falcon BMS. Link this device to it on the Setup page.")
            path == null -> FilesStart { start -> scope.launch { pickFile(start) } }
            else -> androidx.compose.runtime.key(path) { FileView(path, onOpen = { OpenFiles.open(it) }) }
        }
    }
}

@Composable
private fun FileChip(path: String, selected: Boolean, onClick: () -> Unit, onClose: () -> Unit) {
    val name = path.substringAfterLast('\\').substringAfterLast('/')
    val ink = if (selected) Hud.Cyan else Hud.TextDim
    Row(
        Modifier.clip(RoundedCornerShape(8.dp)).background(if (selected) Hud.Cyan.copy(alpha = 0.14f) else Color.Transparent)
            .border(1.dp, ink.copy(alpha = if (selected) 0.7f else 0.35f), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick).padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(kindIcon(PcFileView.kindOf(name)), null, tint = ink, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(name, color = if (selected) Hud.Text else Hud.TextDim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp))
        Box(Modifier.padding(start = 4.dp).clip(RoundedCornerShape(6.dp)).clickable(onClick = onClose).padding(4.dp)) {
            Icon(Icons.Default.Close, "Close $name", tint = Hud.TextFaint, modifier = Modifier.size(14.dp))
        }
    }
}

private fun kindIcon(kind: String?): ImageVector = when (kind) {
    "html" -> Icons.Default.Language
    "image" -> Icons.Default.Image
    "pdf" -> Icons.Default.PictureAsPdf
    else -> Icons.Default.Description
}

/** Nothing open yet: where to start looking. */
@Composable
private fun FilesStart(onPick: (String?) -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)) {
        Text("Files of the BMS PC", color = Hud.Text, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
        Text(
            "Read a text file or log, an HTML page, a picture or a PDF that is on the PC running Falcon BMS. " +
                "Files open read only, and stay open here as tabs.",
            color = Hud.TextDim, fontSize = 13.sp, modifier = Modifier.widthIn(max = 520.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            SmallButton("Falcon BMS folder", Icons.Default.FolderOpen, primary = true) { onPick("@bms") }
            SmallButton("Documents", null, primary = false) { onPick("@documents") }
            SmallButton("Desktop", null, primary = false) { onPick("@desktop") }
            SmallButton("Downloads", null, primary = false) { onPick("@downloads") }
        }
    }
}

/** A path on the PC [href] names, relative to the folder of [page]; null for a link out of the PC (the web). */
internal fun resolveOnPc(page: String, href: String): String? {
    var h = href.trim().substringBefore('#').substringBefore('?')
    if (h.isEmpty()) return null
    if (h.startsWith("file:", ignoreCase = true)) h = h.removePrefix("file:").trimStart('/').let { decodeUrl(it) }
    else if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(h) && !Regex("^[a-zA-Z]:[\\\\/]").containsMatchIn(h)) return null
    else h = decodeUrl(h)
    h = h.replace('/', '\\')
    val joined = if (Regex("^[a-zA-Z]:\\\\").containsMatchIn(h)) h else page.substringBeforeLast('\\') + "\\" + h.trimStart('\\')
    // `..` and `.` folded here too (the PC folds them again before it opens anything)
    val parts = ArrayList<String>()
    for (seg in joined.split('\\')) when (seg) {
        "", "." -> Unit
        ".." -> if (parts.size > 1) parts.removeAt(parts.size - 1)
        else -> parts += seg
    }
    return parts.joinToString("\\")
}

private fun decodeUrl(s: String): String = Regex("%([0-9a-fA-F]{2})").replace(s) { it.groupValues[1].toInt(16).toChar().toString() }

@Composable
private fun FileView(path: String, onOpen: (String) -> Unit) {
    var answer by remember(path) { mutableStateOf<PcAnswer<PcFileView>?>(null) }
    var reload by remember(path) { mutableIntStateOf(0) }
    LaunchedEffect(path, reload) {
        answer = try { MissionLink.filesView(path) } catch (e: CancellationException) { throw e } catch (e: Exception) { PcAnswer(error = e.message ?: "The PC did not answer.") }
    }
    val a = answer
    val v = a?.value
    Column(Modifier.fillMaxSize()) {
        // the file's own line: where it is, how big, as of when; Reload
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(path, color = Hud.TextDim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (v != null) Text(
                    listOfNotNull(sizeWords(v.size), v.modified.takeIf { it > 0 }?.let { "changed " + com.bmscompanion.app.ui.screens.mission.fileTime(it) }, v.note).joinToString(" · "),
                    color = Hud.TextFaint, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            if (v?.kind == "html" && PcFiles.native?.available == true) {
                SmallButton("Open in browser", null, primary = false) { Platform.openUrl?.invoke("file:///" + path.replace('\\', '/')) }
                Spacer(Modifier.width(6.dp))
            }
            SmallButton("Reload", null, primary = false) { reload++ }
        }
        // the kind through a `when (subject)` of its own: a subject-less `when` that mixes `a == null` with `v.kind ==
        // "text"` is miscompiled by Kotlin/Wasm (it hashes `a` as the string; CLAUDE.md, Gotchas) and froze the browser
        when {
            a == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Opening…", color = Hud.TextDim) }
            v == null -> PaneEmpty("Could not open the file", a.error ?: if (a.status == 404) PcAnswer.OLD_PC else "The PC did not send it.")
            else -> when (v.kind) {
                "text" -> TextFile(v)
                "html" -> HtmlFile(v, onOpen)
                "image" -> PictureFile(path)
                "pdf" -> PdfFile(path, v.pages)
                else -> PaneEmpty("Not a file this tab opens", v.name)
            }
        }
    }
}

private fun sizeWords(b: Long): String = when {
    b >= 1024 * 1024 -> "${(b * 10 / (1024 * 1024)) / 10.0} MB"
    b >= 1024 -> "${(b + 512) / 1024} KB"
    else -> "$b bytes"
}

/** "12 Oct 21:37" in the device's own time. */
internal fun fileTime(ms: Long): String = runCatching {
    java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.US).format(java.util.Date(ms))
}.getOrDefault("")

// ---------------------------------------------------------------- text

private object TextPrefs {
    // the size is this device's (a phone's and a PC's screens differ: MissionPrefs); the wrap follows other devices
    var size by mutableIntStateOf(Repo.getInt("files_text_size", 13))
    var wrap by com.bmscompanion.app.data.mission.prefState("files_text_wrap") { Repo.getInt("files_text_wrap", 1) == 1 }
    fun resize(s: Int) { size = s.coerceIn(9, 24); Repo.putInt("files_text_size", size) }
    fun wrapLines(w: Boolean) { wrap = w; Repo.putInt("files_text_wrap", if (w) 1 else 0) }
}

/**
 * [line] with each tab turned into the blanks up to the next stop of [width] columns: a font has no glyph for a tab
 * (the browser drew a box for each), and BMS's files line their columns up with tabs.
 */
internal fun expandTabs(line: String, width: Int = 8): String {
    if ('\t' !in line) return line
    val sb = StringBuilder(line.length + 16)
    for (c in line) if (c == '\t') { do sb.append(' ') while (sb.length % width != 0) } else sb.append(c)
    return sb.toString()
}

@Composable
private fun TextFile(v: PcFileView) {
    val lines = remember(v) { v.text.orEmpty().replace("\r\n", "\n").replace('\r', '\n').split('\n').map { expandTabs(it) } }
    val list = rememberLazyListState()
    var query by remember(v) { mutableStateOf("") }
    val hits = remember(lines, query) { if (query.length < 2) emptyList() else lines.indices.filter { lines[it].contains(query, ignoreCase = true) } }
    var hit by remember(hits) { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    // a log is opened at its end, where the newest lines are
    LaunchedEffect(v) { if (v.name.lowercase().endsWith(".log") || v.truncated && v.offset > 0) list.scrollToItem((lines.size - 1).coerceAtLeast(0)) }
    LaunchedEffect(hits, hit) { hits.getOrNull(hit)?.let { list.animateScrollToItem((it - 3).coerceAtLeast(0)) } }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SearchField(query, { query = it }, "Find in the file", Modifier.weight(1f))
            if (hits.isNotEmpty()) {
                Text("${hit + 1}/${hits.size}", color = Hud.TextDim, fontSize = 12.sp)
                SmallButton("▲", null, primary = false) { hit = (hit - 1 + hits.size) % hits.size }
                SmallButton("▼", null, primary = false) { hit = (hit + 1) % hits.size }
            } else if (query.length >= 2) Text("not found", color = Hud.TextFaint, fontSize = 12.sp)
            SmallButton("A−", null, primary = false) { TextPrefs.resize(TextPrefs.size - 1) }
            SmallButton("A+", null, primary = false) { TextPrefs.resize(TextPrefs.size + 1) }
            SmallButton(if (TextPrefs.wrap) "Wrap" else "No wrap", null, primary = TextPrefs.wrap) { TextPrefs.wrapLines(!TextPrefs.wrap) }
            SmallButton("End", null, primary = false) { scope.launch { list.scrollToItem((lines.size - 1).coerceAtLeast(0)) } }
        }
        Spacer(Modifier.height(6.dp))
        val hScroll = rememberScrollState()
        SelectionContainer(Modifier.fillMaxSize().background(Hud.Surface)) {
            LazyColumn(
                Modifier.fillMaxSize().then(if (TextPrefs.wrap) Modifier else Modifier.horizontalScroll(hScroll)).padding(horizontal = 10.dp, vertical = 6.dp),
                state = list,
            ) {
                itemsIndexed(lines) { i, line ->
                    val on = hits.getOrNull(hit) == i
                    Text(
                        line.ifEmpty { " " }, fontFamily = Mono, fontSize = TextPrefs.size.sp, lineHeight = (TextPrefs.size * 1.35f).sp,
                        color = Hud.Text, softWrap = TextPrefs.wrap,
                        modifier = Modifier.then(if (on) Modifier.background(Hud.Amber.copy(alpha = 0.25f)) else if (i in hits) Modifier.background(Hud.Amber.copy(alpha = 0.10f)) else Modifier),
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- HTML

@Composable
private fun HtmlFile(v: PcFileView, onOpen: (String) -> Unit) {
    val open by rememberUpdatedState(onOpen)
    val linkInk = Hud.Cyan
    val codeInk = Hud.Amber
    val blocks = remember(v) {
        FilesHtml.parse(v.text.orEmpty(), linkInk, codeInk) { href ->
            val p = resolveOnPc(v.path, href)
            when {
                p == null -> Platform.openUrl?.invoke(href)
                PcFileView.kindOf(p) != null -> open(p)
                else -> Unit
            }
        }
    }
    // the page's <title>, unless its first heading already says it
    val title = remember(v, blocks) {
        FilesHtml.title(v.text.orEmpty())?.takeIf { t -> (blocks.firstOrNull() as? FilesHtml.Heading)?.text?.text?.trim() != t }
    }
    LazyColumn(Modifier.fillMaxSize().background(Hud.Surface).padding(horizontal = 16.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp)) {
        title?.let { t -> item { Text(t, color = Hud.Text, fontWeight = FontWeight.Bold, fontSize = 20.sp, modifier = Modifier.padding(bottom = 10.dp)) } }
        itemsIndexed(blocks) { _, b ->
            when (b) {
                is FilesHtml.Heading -> Text(
                    b.text, color = Hud.Text, fontWeight = FontWeight.Bold,
                    fontSize = when (b.level) { 1 -> 20.sp; 2 -> 17.sp; 3 -> 15.sp; else -> 14.sp },
                    modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
                )
                is FilesHtml.Para -> Text(
                    b.text, color = if (b.quote) Hud.TextDim else Hud.Text, fontSize = 14.sp, lineHeight = 20.sp,
                    fontFamily = if (b.pre) Mono else null, softWrap = true,
                    modifier = Modifier.padding(vertical = 4.dp).then(if (b.quote) Modifier.padding(start = 12.dp) else Modifier),
                )
                is FilesHtml.Item -> Row(Modifier.padding(start = (8 + 16 * b.depth).dp, top = 2.dp, bottom = 2.dp)) {
                    Text(b.marker, color = Hud.TextDim, fontSize = 14.sp, modifier = Modifier.width(24.dp))
                    Text(b.text, color = Hud.Text, fontSize = 14.sp, lineHeight = 20.sp)
                }
                is FilesHtml.Row -> Row(
                    Modifier.fillMaxWidth().padding(vertical = 1.dp).background(if (b.header) Hud.Cyan.copy(alpha = 0.10f) else Color.Transparent).padding(vertical = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    b.cells.forEach { c -> Text(c, Modifier.weight(1f), color = Hud.Text, fontSize = 13.sp, fontWeight = if (b.header) FontWeight.SemiBold else null) }
                }
                is FilesHtml.Image -> HtmlPicture(v.path, b)
                FilesHtml.Rule -> Box(Modifier.fillMaxWidth().padding(vertical = 8.dp).height(1.dp).background(Hud.Outline))
            }
        }
        if (blocks.isEmpty()) item { Text("The page has no text to show.", color = Hud.TextDim) }
    }
}

@OptIn(ExperimentalEncodingApi::class)
@Composable
private fun HtmlPicture(page: String, img: FilesHtml.Image) {
    var bmp by remember(img.src) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(img.src) { mutableStateOf(false) }
    LaunchedEffect(img.src) {
        val bytes: ByteArray? = try {
            if (img.src.startsWith("data:image", ignoreCase = true) && ";base64," in img.src) Base64.decode(img.src.substringAfter(";base64,"))
            else resolveOnPc(page, img.src)?.takeIf { PcFileView.kindOf(it) == "image" }?.let { p ->
                MissionLink.filesPicture(p, 1200).value?.data?.let { Base64.decode(it) }
            }
        } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
        bmp = bytes?.let { runCatching { Repo.decodeBitmap(it) }.getOrNull() }
        failed = bmp == null
    }
    val b = bmp
    when {
        b != null -> Image(
            remember(b) { b.asImageBitmap() }, img.alt, contentScale = ContentScale.Fit,
            modifier = Modifier.padding(vertical = 6.dp).widthIn(max = 900.dp).heightIn(max = 600.dp),
        )
        failed -> Text("[picture: ${img.alt ?: img.src.substringAfterLast('/').take(60)}]", color = Hud.TextFaint, fontSize = 12.sp)
        else -> Spacer(Modifier.height(24.dp))
    }
}

// ---------------------------------------------------------------- pictures and PDF pages

@OptIn(ExperimentalEncodingApi::class)
@Composable
private fun PictureFile(path: String) {
    var bmp by remember(path) { mutableStateOf<Bitmap?>(null) }
    var why by remember(path) { mutableStateOf<String?>(null) }
    LaunchedEffect(path) {
        val a = try { MissionLink.filesPicture(path, 3000) } catch (e: CancellationException) { throw e } catch (e: Exception) { PcAnswer(error = e.message) }
        bmp = a.value?.data?.let { d -> runCatching { Repo.decodeBitmap(Base64.decode(d)) }.getOrNull() }
        if (bmp == null) why = a.error ?: "The picture could not be shown."
    }
    val b = bmp
    when {
        b != null -> ZoomableBitmap(b, Modifier.fillMaxSize(), background = Hud.Bg)
        why != null -> PaneEmpty("Could not show the picture", why!!)
        else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Opening…", color = Hud.TextDim) }
    }
}

/** A PDF's pages, drawn on the PC one at a time; kept while the file is open. */
@OptIn(ExperimentalEncodingApi::class)
@Composable
private fun PdfFile(path: String, pages: Int) {
    val key = "files_pdf_page@$path"
    var page by remember(path) { mutableIntStateOf(Repo.getInt(key, 0).coerceIn(0, (pages - 1).coerceAtLeast(0))) }
    val drawn = remember(path) { mutableStateMapOf<Int, Bitmap>() }
    var why by remember(path) { mutableStateOf<String?>(null) }
    fun go(p: Int) { page = p.coerceIn(0, (pages - 1).coerceAtLeast(0)); Repo.putInt(key, page) }
    LaunchedEffect(path, page) {
        why = null
        if (drawn[page] != null) return@LaunchedEffect
        val a = try { MissionLink.filesPdfPage(path, page, 1800) } catch (e: CancellationException) { throw e } catch (e: Exception) { PcAnswer(error = e.message) }
        val b = a.value?.data?.let { d -> runCatching { Repo.decodeBitmap(Base64.decode(d)) }.getOrNull() }
        if (b != null) {
            if (drawn.size > 8) drawn.keys.filter { kotlin.math.abs(it - page) > 3 }.forEach { drawn.remove(it) }
            drawn[page] = b
        } else why = a.error ?: "The page could not be drawn."
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallButton("‹ Previous", null, primary = false) { go(page - 1) }
            Text("Page ${page + 1} of $pages", color = Hud.Text, fontSize = 13.sp)
            SmallButton("Next ›", null, primary = false) { go(page + 1) }
        }
        val b = drawn[page]
        when {
            b != null -> ZoomableBitmap(b, Modifier.fillMaxSize(), background = Color.White, onSwipe = { go(page + it) })
            why != null -> PaneEmpty("Could not draw page ${page + 1}", why!!)
            else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Drawing page ${page + 1}…", color = Hud.TextDim) }
        }
    }
}
