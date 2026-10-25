package com.bmscompanion.desktop.ui.command

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragData
import androidx.compose.ui.draganddrop.dragData
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.bmscompanion.app.data.mission.LinkMode
import com.bmscompanion.app.data.mission.MissionLink
import com.bmscompanion.app.ui.components.rememberMapState
import com.bmscompanion.app.ui.screens.editor.Rule
import com.bmscompanion.app.ui.theme.Hud
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The Command section: a campaign and TE editor for Falcon BMS 4.38.1's saves, in the PC's own window only.
 *
 * One desk-style workspace, no cards: the toolbar and the open file's identity, the page strip (Map | Units | ATO |
 * Objectives | Teams | Campaign | Pilots | Checks | Files), the layers and filters on the left, the map (or the page's
 * own view) in the middle, the inspector of the selection on the right and the page's table in a drawer at the foot.
 * The map, the tables and the inspector share one selection ([CommandSession.selection]). The pages, tables,
 * inspectors and context actions are slots ([CommandSlots]) the editor fills; what is built in reads the file only.
 */
@Composable
fun CommandSection(@Suppress("UNUSED_PARAMETER") nav: NavHostController?, local: Boolean? = null) {
    val mode by MissionLink.mode.collectAsState()
    val here = local ?: (mode == LinkMode.LOCAL)
    if (!here) {
        Box(Modifier.fillMaxSize().background(Hud.Bg).padding(32.dp), contentAlignment = Alignment.Center) {
            Column(Modifier.widthIn(max = 560.dp)) {
                Text("Command", color = Hud.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text(
                    "The campaign editor works on Falcon BMS's own files on this PC, so it opens only when this window is set to This PC " +
                        "(Setup → where Falcon BMS runs). A window linked to another PC cannot reach that PC's campaign files.",
                    color = Hud.TextDim, fontSize = 13.sp, lineHeight = 18.sp,
                )
            }
        }
        return
    }
    CommandRegistry.installOnce()
    // a desk's dense type: the theme's 24-sp line and 0.5-sp spacing taken out (with them every 11-12 sp line was a
    // 24-sp box, taller than the section's rows, and the stance matrix's boxes sat over the row below)
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.material3.LocalTextStyle provides androidx.compose.material3.LocalTextStyle.current.copy(
            lineHeight = androidx.compose.ui.unit.TextUnit.Unspecified, letterSpacing = 0.sp,
        ),
    ) { CommandWorkspace() }
}

/** Opens [file] in the background; the section shows it when it is read (or the reason in a dialog). */
internal suspend fun openFile(file: File) {
    CommandSession.opening = file.name
    val got = withContext(Dispatchers.IO) { CmdDocument.open(file, CommandSession.rootOverride) }
    CommandSession.opening = null
    got.onSuccess { d ->
        CommandSession.selection.clear()
        CommandSession.doc = d
        CommandSession.openWindow = false
        CommandSession.remember(file)
        CommandSession.status = "${file.name}: ${d.world.objectives.size} objectives, ${d.world.units.size} units." +
            (d.world.notes.firstOrNull()?.let { "  $it" } ?: "")
    }.onFailure { CommandSession.error = "${file.name} could not be opened: ${it.message ?: it.javaClass.simpleName}" }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
private fun CommandWorkspace() {
    val doc = CommandSession.doc
    val scope = rememberCoroutineScope()
    val mapState = rememberMapState()
    val root = remember { FocusRequester() }
    var rootFocused by remember { mutableStateOf(false) }
    var saveAs by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    LaunchedEffect(Unit) { runCatching { root.requestFocus() } }

    val dropTarget = remember {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                val data = event.dragData() as? DragData.FilesList ?: return false
                val f = data.readFiles().mapNotNull { runCatching { File(java.net.URI(it)) }.getOrNull() }
                    .firstOrNull { it.extension.lowercase() in setOf("cam", "tac", "trn") } ?: return false
                scope.launch { openFile(f) }
                return true
            }
        }
    }

    // (the open file read when pressed: the toolbar's remembered references to these would otherwise keep the file
    // that was open when the section was first drawn — none — and the buttons did nothing)
    fun undo() { CommandSession.doc?.editing?.let { e -> if (e.canUndo) { e.undo()?.let { CommandSession.status = it } ?: CommandSession.changed() } } }
    fun redo() { CommandSession.doc?.editing?.let { e -> if (e.canRedo) { e.redo()?.let { CommandSession.status = it } ?: CommandSession.changed() } } }

    Column(
        Modifier.fillMaxSize().background(Hud.Bg)
            .focusRequester(root).onFocusChanged { rootFocused = it.isFocused }.focusable()
            .pointerInput(Unit) {
                // a press anywhere gives the section the keyboard back (a text field pressed takes it after)
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent(PointerEventPass.Initial)
                        if (e.type == PointerEventType.Press) runCatching { root.requestFocus() }
                    }
                }
            }
            .dragAndDropTarget(shouldStartDragAndDrop = { it.dragData() is DragData.FilesList }, target = dropTarget)
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                when {
                    e.isCtrlPressed && e.key == Key.O -> { CommandSession.openWindow = true; true }
                    e.isCtrlPressed && e.key == Key.S -> { if (doc?.editing != null) saveAs = true; true }
                    e.isCtrlPressed && e.key == Key.Z && !e.isShiftPressed -> { undo(); true }
                    e.isCtrlPressed && (e.key == Key.Y || (e.key == Key.Z && e.isShiftPressed)) -> { redo(); true }
                    e.key == Key.Escape -> { CommandSession.tool = CmdTool.SELECT; CommandSession.selection.clear(); true }
                    !rootFocused || e.isCtrlPressed -> false
                    else -> {
                        val t = CmdTool.entries.firstOrNull { Key(androidx.compose.ui.input.key.Key.A.keyCode + (it.key - 'A')) == e.key }
                        if (t != null) { CommandSession.tool = t; true } else false
                    }
                }
            },
    ) {
        Toolbar(doc, onOpen = { CommandSession.openWindow = true }, onSaveAs = { saveAs = true }, onConfirm = { confirm = it }, onUndo = ::undo, onRedo = ::redo, scope = scope)
        PageStrip(doc)
        Rule(0.5f)
        if (doc == null) {
            OpenPanel(Modifier.weight(1f).fillMaxWidth(), inline = true)
        } else {
            // a new context at every edit: the pages and inspectors take it, so an edit draws them again (with the same
            // document and records they would be skipped, the edit log not being Compose state)
            val ctx = remember(doc, mapState, CommandSession.version) { CmdContext(doc, CommandSession.selection, mapState) }
            Desk(doc, ctx, mapState, Modifier.weight(1f).fillMaxWidth())
        }
        StatusBar(doc)
    }

    if (CommandSession.openWindow) OpenWindow(onClose = { CommandSession.openWindow = false })
    if (saveAs && doc?.editing != null) SaveAsDialog(doc, doc.editing!!, onClose = { saveAs = false })
    confirm?.let { c -> ConfirmDialog(c, onClose = { confirm = null }) }
    if (doc != null) { val octx = remember(doc, mapState, CommandSession.version) { CmdContext(doc, CommandSession.selection, mapState) }; CommandSlots.overlays.forEach { it(octx) } }
    CommandSession.error?.let { msg ->
        AlertDialog(
            onDismissRequest = { CommandSession.error = null },
            confirmButton = { DeskButton("OK", strong = true) { CommandSession.error = null } },
            title = { Text("Command", color = Hud.Text, fontSize = 15.sp) },
            text = { Text(msg, color = Hud.TextDim, fontSize = 13.sp) },
            containerColor = Hud.Surface,
        )
    }
}

/** A question before an action that cannot be taken back from here. */
internal class Confirm(val title: String, val text: String, val yes: String, val danger: Boolean, val run: () -> String?)

@Composable
private fun ConfirmDialog(c: Confirm, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = {
            DeskButton(c.yes, strong = true, danger = c.danger) {
                onClose()
                val why = runCatching { c.run() }.getOrElse { it.message ?: "That did not work." }
                if (why != null) CommandSession.error = why else CommandSession.changed()
            }
        },
        dismissButton = { DeskButton("Cancel") { onClose() } },
        title = { Text(c.title, color = Hud.Text, fontSize = 15.sp) },
        text = { Text(c.text, color = Hud.TextDim, fontSize = 13.sp, lineHeight = 18.sp) },
        containerColor = Hud.Surface,
    )
}

/** Save as…: a copy beside the file, under the name proposed ("<name> (edited)") or one typed; asks before replacing. */
@Composable
private fun SaveAsDialog(doc: CmdDocument, e: CmdEditing, onClose: () -> Unit) {
    var name by remember { mutableStateOf(e.proposedCopyName()) }
    var replace by remember { mutableStateOf(false) }
    val refusal = e.refuseCopyName(name)
    val exists = refusal == null && e.copyExists(name)
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = {
            DeskButton(if (exists) "Replace it" else "Save the copy", enabled = refusal == null && name.isNotBlank() && (!exists || replace), strong = true) {
                val why = e.saveAs(name.trim(), replace = exists)
                onClose()
                if (why != null) CommandSession.error = why else { CommandSession.status = "Saved a copy as ${name.trim()}."; CommandSession.changed() }
            }
        },
        dismissButton = { DeskButton("Cancel") { onClose() } },
        title = { Text("Save as a copy", color = Hud.Text, fontSize = 15.sp) },
        text = {
            Column {
                Text("A copy of ${doc.file.name} with your edits, in the same campaign folder. The open file is left as it is.", color = Hud.TextDim, fontSize = 12.5.sp)
                // a save takes its objectives from the start its header names, and a copy keeps that name
                if (doc.core.identity.start && doc.core.objectivesEditable) SmallNote(
                    "BMS reads the objectives of a save from its start, so this copy flies ${doc.core.scenario.ifBlank { doc.file.nameWithoutExtension }}'s objectives: " +
                        "changes to objectives reach Falcon BMS only when the start itself is written (Edit BMS's campaign start, then Overwrite…).",
                    Hud.Amber,
                )
                Spacer(Modifier.height(10.dp))
                BasicTextField(
                    name, { name = it; replace = false }, singleLine = true,
                    textStyle = TextStyle(color = Hud.Text, fontSize = 13.sp), cursorBrush = SolidColor(Hud.Amber),
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(3.dp)).background(Hud.Bg).border(1.dp, Hud.Outline, RoundedCornerShape(3.dp)).padding(8.dp),
                )
                Text(".${doc.file.extension}", color = Hud.TextFaint, fontSize = 11.5.sp, modifier = Modifier.padding(top = 3.dp))
                if (refusal != null) SmallNote(refusal, Hud.Red)
                if (exists) CheckLine("A file of that name is there: replace it", replace) { replace = it }
            }
        },
        containerColor = Hud.Surface,
    )
}

// ------------------------------------------------------------------ toolbar, identity, pages

@Composable
private fun Toolbar(
    doc: CmdDocument?, onOpen: () -> Unit, onSaveAs: () -> Unit, onConfirm: (Confirm) -> Unit,
    onUndo: () -> Unit, onRedo: () -> Unit, scope: kotlinx.coroutines.CoroutineScope,
) {
    val e = doc?.editing
    // (read so an edit, an undo or a save draws the buttons again: the edit log is not Compose state)
    @Suppress("UNUSED_VARIABLE") val v = CommandSession.version
    Strip(Hud.Surface.copy(alpha = 0.75f)) {
        DeskButton("Open…", tip = "Open a campaign, TE or training of any theater  (Ctrl+O; or drop a file on the window)") { onOpen() }
        DeskButton(
            "Save as…", enabled = e != null, strong = e != null && e.editCount > 0,
            tip = if (e == null) "Saving comes with the editor, which is not in this build yet: the file is shown read only." else "Save a copy with your edits  (Ctrl+S)",
        ) { onSaveAs() }
        val over = e?.overwriteRefusal
        DeskButton(
            "Overwrite…", enabled = e != null && over == null && e.editCount > 0,
            tip = over ?: if (e == null) "Comes with the editor." else "Write your edits into ${doc.file.name} itself (asks first)",
        ) {
            if (e != null) onConfirm(
                Confirm(
                    "Overwrite ${doc.file.name}?", "Your ${e.editCount} edit${if (e.editCount == 1) "" else "s"} will be written into ${doc.file.name} itself, " +
                        "in ${doc.file.parentFile?.name ?: "its folder"}. " +
                        (if (e.editingStart) "BMS's original stays in BMS Companion Backup\\Command beside it (Restore BMS's original puts it back)."
                        else "The file as it is now is not kept. Save as… keeps it and writes a copy instead."),
                    "Overwrite", danger = true,
                ) { e.overwrite() },
            )
        }
        VRule()
        DeskButton("Undo", enabled = e?.canUndo == true, tip = e?.undoName?.let { "Undo: $it  (Ctrl+Z)" } ?: "Undo  (Ctrl+Z)") { onUndo() }
        DeskButton("Redo", enabled = e?.canRedo == true, tip = e?.redoName?.let { "Redo: $it  (Ctrl+Y)" } ?: "Redo  (Ctrl+Y)") { onRedo() }
        VRule()
        DeskButton("Reload", enabled = doc != null, tip = "Read the file again from disk (edits not saved are dropped)") {
            if (doc != null) scope.launch { openFile(doc.file) }
        }
        VRule()
        Identity(doc, onConfirm)
    }
}

/** theater › file · version · kind · edits; for BMS's campaign start, its own two actions. */
@Composable
private fun androidx.compose.foundation.layout.RowScope.Identity(doc: CmdDocument?, onConfirm: (Confirm) -> Unit) {
    if (doc == null) {
        Text(CommandSession.opening?.let { "Opening $it…" } ?: "No file open", color = Hud.TextDim, fontSize = 12.sp, modifier = Modifier.weight(1f))
        return
    }
    // (read so an edit, Edit BMS's campaign start or a claim draws the strip again: the edit log is not Compose state,
    // and with the same document the strip would otherwise be skipped and keep showing the old count)
    @Suppress("UNUSED_VARIABLE") val v = CommandSession.version
    val e = doc.editing
    val kind = when {
        doc.startWhy != null -> "campaign start"
        doc.kind == "te" -> "TE"
        doc.kind == "training" -> "training"
        else -> "campaign"
    }
    // one line of words, cut with "…" where the bar has no room for all of it (the whole of it under the mouse); the tag
    // and the buttons after it are items of the bar of their own, so they wrap onto its next line whole
    val edits = if (e != null) "${e.editCount} edit${if (e.editCount == 1) "" else "s"}" else "read only"
    val words = androidx.compose.ui.text.buildAnnotatedString {
        withStyle(androidx.compose.ui.text.SpanStyle(color = Hud.TextDim)) { append(doc.theaterName) }
        withStyle(androidx.compose.ui.text.SpanStyle(color = Hud.TextFaint)) { append("  ›  ") }
        withStyle(androidx.compose.ui.text.SpanStyle(color = Hud.Text, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp)) { append(doc.file.name) }
        withStyle(androidx.compose.ui.text.SpanStyle(color = Hud.TextDim)) { append(" · v${doc.version ?: "?"} · $kind · ") }
        withStyle(androidx.compose.ui.text.SpanStyle(color = if (e != null && e.editCount > 0) Hud.Amber else if (e == null) Hud.TextFaint else Hud.TextDim)) { append(edits) }
    }
    Box(Modifier.weight(1f).widthIn(min = 180.dp)) {
        Tip("${doc.theaterName} › ${doc.file.name} · version ${doc.version ?: "?"} · $kind · $edits\n${doc.file.path}") {
            Text(words, fontSize = 12.sp, maxLines = 1, softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
    }
    run {
        doc.shippedWhy?.let { why -> Tip("$why. Save writes a copy; this file is never overwritten through Save.") { Text("BMS's own", color = Hud.Cyan, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false) } }
        com.bmscompanion.desktop.ui.command.inspectors.MineButtons(doc, onConfirm)
        if (e != null && e.isBmsStart) {
            if (!e.editingStart) DeskButton("Edit BMS's campaign start", tip = "Edit this start in place, after a one-time copy of BMS's original beside it") {
                onConfirm(
                    Confirm(
                        "Edit BMS's campaign start in place?",
                        "${doc.file.name} is one of Falcon BMS's own campaign starts. Before the first change is written, BMS's original is copied once into " +
                            "\"BMS Companion Backup\\Command\" beside it, with a README saying how to put it back by hand. Restore BMS's original does it from here.",
                        "Edit in place", danger = false,
                    ) { e.editStart() },
                )
            }
            if (e.canRestoreStart) DeskButton("Restore BMS's original", danger = true, tip = "Put BMS's original of this start back from its backup") {
                onConfirm(Confirm("Restore BMS's original?", "${doc.file.name} is replaced by BMS's original kept in BMS Companion Backup\\Command. Every change made to it is lost.", "Restore", danger = true) { e.restoreStart() })
            }
        }
    }
}

@Composable
private fun PageStrip(doc: CmdDocument?) {
    // (wraps onto a second line in a narrow window rather than losing the last pages past the edge)
    WrapRow(Modifier.fillMaxWidth().background(Hud.Surface.copy(alpha = 0.35f)).padding(horizontal = 8.dp), gap = 0.dp, lineGap = 0.dp) {
        CmdPage.entries.forEach { p ->
            val on = CommandSession.page == p
            Column(
                Modifier.clickable(enabled = doc != null) { CommandSession.page = p }.padding(horizontal = 11.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    p.label, color = if (doc == null) Hud.TextFaint else if (on) Hud.Amber else Hud.TextDim,
                    fontSize = 12.5.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, softWrap = false,
                    modifier = Modifier.padding(top = 6.dp, bottom = 4.dp),
                )
                Box(Modifier.width(28.dp).height(2.dp).background(if (on && doc != null) Hud.Amber else Hud.Bg.copy(alpha = 0f)))
            }
        }
    }
}

// ------------------------------------------------------------------ the desk: side panels, centre, drawer, inspector, status

/**
 * The side panels' widths and folds and the drawer's share of the centre, kept in the PC's prefs (`cmd_*`, the
 * section's own, never synced to another device). The panels give way to the map in a narrow window: the layers fold
 * to a rail first, then the inspector narrows to its least, then it folds too ([Desk]); a panel opened from its rail
 * while there is no room stays open for the rest of the run ([layersForced], [inspForced]).
 */
internal object CmdPanes {
    const val LAYERS_MIN = 180
    const val LAYERS_MAX = 440
    const val INSP_MIN = 260
    const val INSP_MAX = 640
    /** what the map keeps beside the panels before they give way */
    val MAP_MIN = 560.dp
    val RAIL = 22.dp

    var layersW by androidx.compose.runtime.mutableIntStateOf(com.bmscompanion.app.data.Repo.getInt("cmd_layers_w", 250))
    var inspW by androidx.compose.runtime.mutableIntStateOf(com.bmscompanion.app.data.Repo.getInt("cmd_insp_w", 340))
    var layersOpen by mutableStateOf(com.bmscompanion.app.data.Repo.getInt("cmd_layers_open", 1) == 1)
    var inspOpen by mutableStateOf(com.bmscompanion.app.data.Repo.getInt("cmd_insp_open", 1) == 1)
    var layersForced by mutableStateOf(false)
    var inspForced by mutableStateOf(false)
    /** the drawer's share of the centre's height, per mille */
    var drawerPm by androidx.compose.runtime.mutableIntStateOf(com.bmscompanion.app.data.Repo.getInt("cmd_drawer_pm", 400))

    fun save() {
        val r = com.bmscompanion.app.data.Repo
        r.putInt("cmd_layers_w", layersW); r.putInt("cmd_insp_w", inspW)
        r.putInt("cmd_layers_open", if (layersOpen) 1 else 0); r.putInt("cmd_insp_open", if (inspOpen) 1 else 0)
        r.putInt("cmd_drawer_pm", drawerPm)
    }
}

/** The workspace under the page strip: layers | map (or the page's view) and its drawer | inspector. */
@Composable
private fun Desk(doc: CmdDocument, ctx: CmdContext, mapState: com.bmscompanion.app.ui.components.MapState, modifier: Modifier) {
    androidx.compose.foundation.layout.BoxWithConstraints(modifier) {
        val w = maxWidth
        val lw = CmdPanes.layersW.dp.coerceIn(CmdPanes.LAYERS_MIN.dp, maxOf(CmdPanes.LAYERS_MIN.dp, minOf(CmdPanes.LAYERS_MAX.dp, w * 0.3f)))
        val iwWant = CmdPanes.inspW.dp.coerceIn(CmdPanes.INSP_MIN.dp, maxOf(CmdPanes.INSP_MIN.dp, minOf(CmdPanes.INSP_MAX.dp, w * 0.42f)))
        // who gives way: the layers fold first, then the inspector narrows to its least, then it folds
        var showL = CmdPanes.layersOpen
        var showI = CmdPanes.inspOpen
        var iw = iwWant
        fun need() = (if (showL) lw else CmdPanes.RAIL) + (if (showI) iw else CmdPanes.RAIL) + CmdPanes.MAP_MIN
        if (showL && !CmdPanes.layersForced && need() > w) showL = false
        if (showI && need() > w) iw = maxOf(CmdPanes.INSP_MIN.dp, w - CmdPanes.MAP_MIN - (if (showL) lw else CmdPanes.RAIL))
        if (showI && !CmdPanes.inspForced && need() > w) showI = false
        val lNow by androidx.compose.runtime.rememberUpdatedState(lw)
        val iNow by androidx.compose.runtime.rememberUpdatedState(iw)
        Row(Modifier.fillMaxSize()) {
            if (showL) {
                Column(Modifier.width(lw).fillMaxHeight()) {
                    PanelHead("LAYERS", fold = "‹", foldTip = "Fold the layers to the edge (the map takes the room)") {
                        CmdPanes.layersOpen = false; CmdPanes.layersForced = false; CmdPanes.save()
                    }
                    Rule()
                    val scroll = rememberScrollState()
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        Column(Modifier.fillMaxSize().padding(end = CMD_BAR).verticalScroll(scroll)) { LayersPanel(doc) }
                        CmdVBar(androidx.compose.foundation.rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                    }
                }
                PanelDivider { dx ->
                    CmdPanes.layersW = (lNow.value + dx).toInt().coerceIn(CmdPanes.LAYERS_MIN, CmdPanes.LAYERS_MAX)
                }
            } else Rail("LAYERS", "›", if (CmdPanes.layersOpen) "Show the layers (folded while the window has no room for them beside the map)" else "Show the layers") {
                CmdPanes.layersOpen = true; CmdPanes.layersForced = true; CmdPanes.save()
            }
            Centre(ctx, mapState, Modifier.weight(1f).fillMaxHeight())
            if (showI) {
                PanelDivider { dx ->
                    CmdPanes.inspW = (iNow.value - dx).toInt().coerceIn(CmdPanes.INSP_MIN, CmdPanes.INSP_MAX)
                }
                Column(Modifier.width(iw).fillMaxHeight()) {
                    InspectorPanel(ctx) { CmdPanes.inspOpen = false; CmdPanes.inspForced = false; CmdPanes.save() }
                }
            } else Rail("INSPECTOR", "‹", if (CmdPanes.inspOpen) "Show the inspector (folded while the window has no room for it beside the map)" else "Show the inspector") {
                CmdPanes.inspOpen = true; CmdPanes.inspForced = true; CmdPanes.save()
            }
        }
    }
}

/** A side panel's head line: its name and the button that folds it. */
@Composable
private fun PanelHead(title: String, fold: String, foldTip: String, extra: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {}, onFold: () -> Unit) {
    Strip {
        Text(title, color = Hud.TextDim, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp, maxLines = 1, softWrap = false)
        Box(Modifier.weight(1f))
        extra()
        FoldButton(fold, foldTip, onFold)
    }
}

@Composable
private fun FoldButton(glyph: String, tip: String, onClick: () -> Unit) = Tip(tip) {
    Box(
        Modifier.size(20.dp).clip(RoundedCornerShape(3.dp)).border(1.dp, Hud.Outline.copy(alpha = 0.6f), RoundedCornerShape(3.dp)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(glyph, color = Hud.TextDim, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false) }
}

/** A folded panel: a thin rail at the edge, its name down it; a click opens the panel. */
@Composable
private fun Rail(title: String, glyph: String, tip: String, onOpen: () -> Unit) {
    Tip(tip) {
        Column(
            Modifier.width(CmdPanes.RAIL).fillMaxHeight().background(Hud.Surface.copy(alpha = 0.55f)).clickable(onClick = onOpen).padding(top = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(glyph, color = Hud.Amber, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
            Spacer(Modifier.height(6.dp))
            title.forEach { ch -> Text("$ch", color = Hud.TextDim, fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false) }
        }
    }
}

/** The line between a side panel and the map: drag it to make the panel wider or narrower ([onDrag] gets dp). */
@Composable
private fun PanelDivider(onDrag: (Float) -> Unit) {
    val density = LocalDensity.current
    val drag by androidx.compose.runtime.rememberUpdatedState(onDrag)
    Box(
        Modifier.width(5.dp).fillMaxHeight().pointerHoverIcon(PointerIcon(java.awt.Cursor(java.awt.Cursor.E_RESIZE_CURSOR)))
            .pointerInput(Unit) {
                detectHorizontalDragGestures(onDragEnd = { CmdPanes.save() }) { ch, dx -> ch.consume(); drag(dx / density.density) }
            },
        contentAlignment = Alignment.Center,
    ) { Box(Modifier.width(1.dp).fillMaxHeight().background(Hud.Outline.copy(alpha = 0.5f))) }
}

@Composable
private fun Centre(ctx: CmdContext, mapState: com.bmscompanion.app.ui.components.MapState, modifier: Modifier) {
    val page = CommandSession.page
    val view = CommandSlots.pages[page] ?: CommandBuiltIns.pages[page]
    val table = CommandSlots.tables[page] ?: CommandBuiltIns.tables[page]
    val density = LocalDensity.current
    androidx.compose.foundation.layout.BoxWithConstraints(modifier) {
        val total = maxHeight
        Column(Modifier.fillMaxSize()) {
            if (view != null && view.replacesMap) {
                Box(Modifier.weight(1f).fillMaxWidth()) { view.Content(ctx) }
                return@Column
            }
            // the drawer takes its share of the height (dragged and remembered), the map keeps at least 200 dp
            val drawerMin = 90.dp
            val drawer = (total * (CmdPanes.drawerPm / 1000f)).coerceIn(drawerMin, maxOf(drawerMin, total - 200.dp))
            val drawerNow by androidx.compose.runtime.rememberUpdatedState(drawer)
            val totalNow by androidx.compose.runtime.rememberUpdatedState(total)
            CommandMap(ctx, mapState, Modifier.weight(1f).fillMaxWidth())
            if (table != null) {
                // the drawer's handle: drag it up or down
                Box(
                    Modifier.fillMaxWidth().height(7.dp).background(Hud.Surface).pointerHoverIcon(PointerIcon(java.awt.Cursor(java.awt.Cursor.N_RESIZE_CURSOR)))
                        .pointerInput(Unit) {
                            detectDragGestures(onDragEnd = { CmdPanes.save() }) { ch, d ->
                                ch.consume()
                                val h = (drawerNow.value - d.y / density.density).coerceIn(drawerMin.value, maxOf(drawerMin.value, totalNow.value - 200f))
                                CmdPanes.drawerPm = (h / totalNow.value * 1000f).toInt().coerceIn(50, 900)
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) { Box(Modifier.width(40.dp).height(2.dp).background(Hud.Outline)) }
                Box(Modifier.fillMaxWidth().height(drawer)) { table.Content(ctx) }
            }
        }
    }
}

@Composable
private fun InspectorPanel(ctx: CmdContext, onFold: () -> Unit) {
    @Suppress("UNUSED_VARIABLE") val v = CommandSession.version
    val refs = ctx.selection.refs
    Column(Modifier.fillMaxSize()) {
        PanelHead("INSPECTOR", fold = "›", foldTip = "Fold the inspector to the edge (the map takes the room)", extra = {
            if (refs.size > 1) Text("${refs.size} selected", color = Hud.Amber, fontSize = 11.5.sp, maxLines = 1, softWrap = false)
        }, onFold = onFold)
        Rule()
        val first = refs.firstOrNull()
        val inspector = first?.let { r -> CommandSlots.inspectors.firstOrNull { runCatching { it.accepts(r) }.getOrDefault(false) } }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                first == null -> Column(Modifier.padding(8.dp)) {
                    SmallNote("Nothing selected. Click something on the map or a row of a table; Shift adds to the selection, the Area tool takes a box.")
                    FileFacts(ctx.doc)
                }
                inspector != null -> inspector.Content(ctx, refs)
                else -> CommandBuiltIns.Inspector(ctx, refs)
            }
        }
    }
}

/** What the open file is, under an empty selection. */
@Composable
private fun FileFacts(doc: CmdDocument) {
    @Suppress("UNUSED_VARIABLE") val v = CommandSession.version // (a renamed team, a shifted clock)
    val h = doc.core.cmp
    val w = doc.world
    Spacer(Modifier.height(6.dp))
    FactRow("File", doc.file.name)
    FactRow("Theater", doc.theaterName)
    FactRow("Version", "${doc.version}")
    FactRow("What it is", doc.core.identity.why)
    h?.let {
        FactRow("Campaign", it.uiName.text.trim().takeIf { s -> s.isNotEmpty() && s != "???" } ?: "—")
        FactRow("Start file", it.scenario.text.ifBlank { "—" })
        FactRow("Clock", com.bmscompanion.desktop.bridge.CampaignArchive.clock(it.currentTime))
    }
    FactRow("Objectives", "${w.objectives.size}")
    FactRow("Units", "${w.units.size} (${w.units.count { it.kind == CmdKind.FLIGHT }} flights, ${w.units.count { it.kind == CmdKind.BATTALION }} battalions)")
    FactRow("Teams", w.teams.filter { it.inUse }.joinToString { it.name })
    w.notes.forEach { SmallNote(it, Hud.Amber) }
}

@Composable
private fun StatusBar(doc: CmdDocument?) {
    Rule(0.5f)
    // a message (what was just done, why something was refused) stays a few seconds and fades; otherwise the line says
    // what a click on the map does with the tool chosen, while the map is shown
    val msg = CommandSession.status
    val at = CommandSession.statusAt
    var fading by remember(at) { mutableStateOf(false) }
    val alpha by androidx.compose.animation.core.animateFloatAsState(if (fading) 0f else 1f, androidx.compose.animation.core.tween(600), label = "status")
    LaunchedEffect(at) {
        if (msg.isBlank()) return@LaunchedEffect
        kotlinx.coroutines.delay(if (msg.startsWith("Done:")) 6_000L else 10_000L)
        fading = true
        kotlinx.coroutines.delay(650)
        if (CommandSession.statusAt == at) CommandSession.status = ""
    }
    val page = CommandSession.page
    val mapShown = (CommandSlots.pages[page] ?: CommandBuiltIns.pages[page])?.replacesMap != true
    Row(Modifier.fillMaxWidth().height(24.dp).background(Hud.Surface.copy(alpha = 0.6f)).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (msg.isNotBlank()) Text(msg, color = if (msg.startsWith("Done:")) Hud.Green.copy(alpha = alpha) else Hud.Text.copy(alpha = alpha), fontSize = 11.5.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        else Text(if (doc == null) "Open a file to begin." else if (mapShown) CommandSession.tool.hint else "", color = Hud.TextFaint, fontSize = 11.5.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
}
