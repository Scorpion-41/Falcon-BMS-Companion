package com.bmscompanion.app.ui.screens.wdp

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bmscompanion.app.data.mission.MissionLink
import com.bmscompanion.app.data.mission.NoteBlock
import com.bmscompanion.app.data.mission.NoteRow
import com.bmscompanion.app.data.mission.Noteboards
import com.bmscompanion.app.ui.theme.Hud
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The Noteboard editor (1.4.1): a noteboard put together like a small Dashboard of the pilot's own — blocks added,
 * moved up and down and taken out, each typed in place — beside the page as it will be printed (on a phone, Edit and
 * Preview take turns). Free notes, laser codes (the cartridge's own TGP and LST codes filled in where it has them),
 * figures, and the briefing's comm ladder and steerpoints. Every change is kept on the PC a moment after the last key
 * ([Noteboards]), so the Print, the VR Notes board and every other device show the same boards; **Keep for the next
 * mission** carries them to the next flight.
 *
 * Opened from Upd Kneeboard (a Noteboard half's **Edit notes…**, in place of the page table: [n] and [side] are that
 * half, for the preview's foot) and from the Kneeboards page (**Edit notes**, its own window: [n] 0). [probe] names
 * its controls for the checks (`<probe>/Board/1`, `<probe>/Add/text`, `<probe>/Done`…).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NoteboardEditor(board0: Int, modifier: Modifier = Modifier, n: Int = 0, side: Char = 'L', probe: String = "Notes", onDone: (() -> Unit)? = null) {
    var board by remember { mutableStateOf(board0.coerceIn(0, Noteboards.BOARDS - 1)) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { Noteboards.load() }
    // another device's change comes in while nothing typed here is waiting to be kept
    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000)
            if (!Noteboards.dirty && !Noteboards.saving) Noteboards.load()
        }
    }
    val doc = Noteboards.current()
    val st = Noteboards.state
    Column(modifier.plannerProbe(probe), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // one slim tool row: which board, keep for the next mission, what happened, and Done
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PlannerSegments(
                (1..Noteboards.BOARDS).map { "Noteboard $it" }, (1..Noteboards.BOARDS).map { "$probe/Board/$it" }, board,
            ) { board = it }
            Spacer(Modifier.weight(1f))
            Text(
                when {
                    Noteboards.saving -> "Keeping…"
                    Noteboards.dirty -> "Changed"
                    Noteboards.loading && st == null -> "Reading…"
                    st != null -> "Kept on the PC"
                    else -> ""
                },
                color = if (Noteboards.dirty || Noteboards.saving) Hud.Cyan else Hud.TextFaint, fontSize = fingerSp(11f), maxLines = 1,
            )
            if (onDone != null) Btn("Done", primary = true, probe = "$probe/Done") { scope.launch { Noteboards.flush() }; onDone() }
        }
        val line = listOfNotNull(
            st?.mission?.let { "For $it" } ?: "No mission yet: PRINT in BMS (or Populate in WDP mode) and the notes are kept for that flight",
            "carried from an earlier mission".takeIf { st?.carried == true },
        ).joinToString("  ·  ")
        Text(line, color = Hud.TextDim, fontSize = fingerSp(11f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Noteboards.error?.let { Text(it, color = Hud.Red, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).plannerPress { Noteboards.setKeep(!doc.keep) }.padding(vertical = 2.dp)
                .plannerProbe("$probe/Keep"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Toggle(doc.keep)
            Spacer(Modifier.width(8.dp))
            Text(
                "Keep for the next mission" + if (doc.keep) ": a new flight starts with these boards" else ": a new flight starts with fresh boards",
                color = if (doc.keep) Hud.Text else Hud.TextDim, fontSize = fingerSp(12f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val wide = maxWidth >= 620.dp
            // the page as it will be printed, as tall as the room allows, never more than half the width
            val w = minOf(maxWidth * 0.48f, maxHeight * (2f / 3f))
            if (wide) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    BlocksEditor(board, Modifier.weight(1f).fillMaxHeight(), probe)
                    NotePreview(board, n, side, Modifier.width(w).fillMaxHeight(), probe)
                }
            } else {
                var preview by remember { mutableStateOf(false) }
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    PlannerSegments(listOf("Edit", "Preview"), listOf("$probe/Edit", "$probe/Preview"), if (preview) 1 else 0) { preview = it == 1 }
                    if (preview) NotePreview(board, n, side, Modifier.weight(1f).fillMaxWidth(), probe)
                    else BlocksEditor(board, Modifier.weight(1f).fillMaxWidth(), probe)
                }
            }
        }
    }
}

/** The page as printed, scaled into the box, keeping its shape. */
@Composable
private fun NotePreview(board: Int, n: Int, side: Char, modifier: Modifier, probe: String) {
    val kind = KneeboardPages.of(KneeboardNotes.kindOf(board)) ?: return
    Box(modifier.clip(RoundedCornerShape(3.dp)).background(Color(0xFF30353C)).plannerProbe("$probe/Page"), contentAlignment = Alignment.TopCenter) {
        KneeboardPageHost(kind, n, side, Modifier.fillMaxSize())
    }
}

/** The board's title and its blocks, each typed in place, then the Add buttons. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BlocksEditor(board: Int, modifier: Modifier, probe: String) {
    val b = Noteboards.board(board)
    val mission by MissionLink.mission.collectAsState()
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Label("Title on the page")
        NoteField(b.title, { v -> Noteboards.updateBoard(board) { it.copy(title = v) } }, if (board == 0) "NOTES" else "NOTES ${board + 1}", "$probe/Title", Modifier.fillMaxWidth())
        // the Add buttons at the head, where they are seen without scrolling past the blocks; a block goes at the foot
        Label("Add a block")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (t in NoteBlock.TYPES) Btn("+ " + NoteBlock.typeName(t), probe = "$probe/Add/$t") { Noteboards.addBlock(board, t) }
        }
        if (b.blocks.isEmpty()) Text("Nothing on this board yet: add a block above. A board with only an empty block of notes prints as a blank page with its title.", color = Hud.TextDim, fontSize = 12.sp)
        b.blocks.forEachIndexed { i, block ->
            Hairline()
            BlockEditor(board, block, first = i == 0, last = i == b.blocks.lastIndex, probe = "$probe/Block/$i", briefingRows = when (block.type) {
                NoteBlock.COMMS -> mission?.briefing?.comms?.count { it.agency.isNotBlank() }
                NoteBlock.STEERPOINTS, NoteBlock.FUEL -> mission?.briefing?.steerpoints?.count { it.n > 0 }
                else -> null
            })
        }
        Hairline()
        Btn("Start this board again", probe = "$probe/Reset") { Noteboards.clearBoard(board) }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun BlockEditor(board: Int, block: NoteBlock, first: Boolean, last: Boolean, probe: String, briefingRows: Int?) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(NoteBlock.typeName(block.type).uppercase(), color = Hud.Amber, fontSize = fingerSp(11f), fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp, maxLines = 1)
            NoteField(block.title, { v -> Noteboards.updateBlock(board, block.id) { it.copy(title = v) } }, "Heading (optional)", "$probe/Heading", Modifier.weight(1f))
            PlannerIconButton(Icons.Default.ArrowUpward, "Move up", "$probe/Up", enabled = !first) { Noteboards.moveBlock(board, block.id, -1) }
            PlannerIconButton(Icons.Default.ArrowDownward, "Move down", "$probe/Down", enabled = !last) { Noteboards.moveBlock(board, block.id, 1) }
            PlannerIconButton(Icons.Default.Close, "Take this block out", "$probe/Remove") { Noteboards.removeBlock(board, block.id) }
        }
        when (block.type) {
            NoteBlock.TEXT -> NoteField(
                block.text, { v -> Noteboards.updateBlock(board, block.id) { it.copy(text = v) } },
                "Your notes: anything you want on your knee", "$probe/Text", Modifier.fillMaxWidth().heightIn(min = 110.dp), singleLine = false,
            )
            NoteBlock.LASER, NoteBlock.FIGURES -> {
                val laser = block.type == NoteBlock.LASER
                block.rows.forEachIndexed { r, row ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        NoteField(row.label, { v -> setRow(board, block, r) { it.copy(label = v) } }, if (laser) "Who (#2, Buddy…)" else "What (Joker…)", "$probe/Row/$r/Label", Modifier.weight(1f))
                        NoteField(
                            row.value, { v -> setRow(board, block, r) { it.copy(value = if (laser) v.filter { c -> c.isDigit() }.take(4) else v) } },
                            if (laser) "Code" else "Figure", "$probe/Row/$r/Value", Modifier.weight(1f), mono = laser, digits = laser,
                        )
                        PlannerIconButton(Icons.Default.Close, "Take this row out", "$probe/Row/$r/Remove") {
                            Noteboards.updateBlock(board, block.id) { it.copy(rows = it.rows.filterIndexed { j, _ -> j != r }) }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Btn("+ Row", probe = "$probe/AddRow") { Noteboards.updateBlock(board, block.id) { it.copy(rows = it.rows + NoteRow()) } }
                    if (laser) Text("Four digits, 1111-1788. A row with no code is left off the page.", color = Hud.TextFaint, fontSize = 11.sp)
                    else Text("A row with no figure is left off the page.", color = Hud.TextFaint, fontSize = 11.sp)
                }
            }
            NoteBlock.COMMS, NoteBlock.STEERPOINTS, NoteBlock.FUEL -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    when (briefingRows) {
                        null, 0 -> "From the briefing: nothing yet (PRINT in BMS, or Populate in WDP mode)."
                        else -> if (block.type == NoteBlock.FUEL) "Worked out from the flight plan with the Fuel plan card's settings." else "From the briefing: $briefingRows rows."
                    } + " At most:",
                    color = Hud.TextDim, fontSize = fingerSp(12f), modifier = Modifier.weight(1f, fill = false),
                )
                Btn("−", enabled = block.max > 1, probe = "$probe/Fewer") { Noteboards.updateBlock(board, block.id) { it.copy(max = (if (it.max <= 0) 12 else it.max) - 1) } }
                Text(if (block.max <= 0) "all" else "${block.max}", color = Hud.Text, fontSize = fingerSp(13f), fontWeight = FontWeight.SemiBold)
                Btn("+", probe = "$probe/More") { Noteboards.updateBlock(board, block.id) { it.copy(max = (if (it.max <= 0) 12 else it.max) + 1) } }
            }
        }
    }
}

private fun setRow(board: Int, block: NoteBlock, r: Int, change: (NoteRow) -> NoteRow) =
    Noteboards.updateBlock(board, block.id) { b -> b.copy(rows = b.rows.mapIndexed { j, row -> if (j == r) change(row) else row }) }

@Composable
private fun Label(text: String) = Text(text, color = Hud.TextFaint, fontSize = fingerSp(10.5f))

/** A switch drawn as the Kneeboards page draws its own. */
@Composable
private fun Toggle(on: Boolean) {
    Box(
        Modifier.size(width = 34.dp, height = 20.dp).clip(RoundedCornerShape(10.dp))
            .background(if (on) Hud.Green.copy(alpha = 0.35f) else Hud.Surface2)
            .border(1.dp, if (on) Hud.Green else Hud.Outline, RoundedCornerShape(10.dp)),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(Modifier.padding(horizontal = 3.dp).size(14.dp).clip(RoundedCornerShape(7.dp)).background(if (on) Hud.Green else Hud.TextFaint))
    }
}

/** A field typed in place: the windows' slim box, the hint while empty. */
@Composable
private fun NoteField(
    value: String, onChange: (String) -> Unit, hint: String, probe: String, modifier: Modifier,
    singleLine: Boolean = true, mono: Boolean = false, digits: Boolean = false,
) {
    val shape = RoundedCornerShape(5.dp)
    Box(
        modifier.heightIn(min = toolHeight).clip(shape).background(Hud.Surface2).border(1.dp, Hud.Outline, shape)
            .padding(horizontal = 8.dp, vertical = 5.dp).plannerProbe(probe),
        contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart,
    ) {
        if (value.isEmpty()) Text(hint, color = Hud.TextFaint, fontSize = fingerSp(12.5f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        BasicTextField(
            value, onChange, singleLine = singleLine,
            textStyle = TextStyle(color = Hud.Text, fontSize = fingerSp(13f), fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default),
            cursorBrush = SolidColor(Hud.Amber),
            keyboardOptions = if (digits) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** The Noteboard as its own window (the Kneeboards page's **Edit notes**, [PlannerWindow.NOTES]). */
@Composable
fun ColumnScope.NoteboardWindow(board: Int, onClose: () -> Unit) {
    NoteboardEditor(board, Modifier.weight(1f).fillMaxWidth(), probe = "Notes", onDone = onClose)
}
