package com.bmscompanion.desktop.ui.command.inspectors

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.Airport
import com.bmscompanion.app.ui.screens.editor.Rule
import com.bmscompanion.app.ui.theme.Hud
import com.bmscompanion.desktop.bridge.command.CampaignDbFiles
import com.bmscompanion.desktop.bridge.command.CommandBackup
import com.bmscompanion.desktop.bridge.command.CommandSave
import com.bmscompanion.desktop.bridge.command.ObjectiveOps
import com.bmscompanion.desktop.ui.command.CmdContext
import com.bmscompanion.desktop.ui.command.CmdGrid
import com.bmscompanion.desktop.ui.command.CmdPage
import com.bmscompanion.desktop.ui.command.CmdPageView
import com.bmscompanion.desktop.ui.command.CommandSession
import com.bmscompanion.desktop.ui.command.DeskButton
import com.bmscompanion.desktop.ui.command.FactRow
import com.bmscompanion.desktop.ui.command.GridCol
import com.bmscompanion.desktop.ui.command.SmallNote
import com.bmscompanion.desktop.ui.command.Strip
import com.bmscompanion.desktop.ui.command.VRule
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The Files page: the theater's campaign database files (Stations+Ils.dat, RadioMap.dat, the TE lists,
 * InvalidAcTypes.XML, every MissionData_*.xml, CampObjData.XML), one table each — the list of files on the left, the
 * open file's rows in the middle, the chosen row's fields on the right. Edits wait on the page (undoable) until
 * **Save**, which writes the file under the backup rule ([CampaignDbFiles.save]: BMS's original copied once beside it,
 * then temp + atomic move); **Restore BMS's original** puts that copy back.
 */
object FilesPage : CmdPageView {
    override val page = CmdPage.FILES

    /** A row on the page: a stable id (for the table and the selection) and the row. */
    private class PRow(val uid: Int, val row: CampaignDbFiles.Row)

    /** One file being edited: the table as read, its rows as the page has them, the undo stack. */
    private class Session(val table: CampaignDbFiles.Table) {
        var rows by mutableStateOf(table.rows.mapIndexed { i, r -> PRow(i, r) })
        val undo = ArrayList<List<PRow>>()
        val redo = ArrayList<List<PRow>>()
        var nextUid = table.rows.size
        var picked by mutableIntStateOf(-1)
        val changes: Int get() {
            val orig = table.rows
            val now = rows.map { it.row }
            val keys = now.mapNotNull { r -> r.key.takeIf { it >= 0 } }.toSet()
            return now.count { r -> r.key < 0 || !r.same(orig[r.key]) } + orig.indices.count { it !in keys } +
                (if (now.mapNotNull { it.key.takeIf { k -> k >= 0 } } != now.mapNotNull { it.key.takeIf { k -> k >= 0 } }.sorted()) 1 else 0)
        }

        fun change(new: List<PRow>) { undo += rows; redo.clear(); rows = new }
        fun set(uid: Int, col: Int, value: String) {
            val i = rows.indexOfFirst { it.uid == uid }
            if (i < 0 || rows[i].row.cells.getOrElse(col) { "" } == value) return
            change(rows.toMutableList().also { it[i] = PRow(uid, it[i].row.with(col, value)) })
        }
        fun addAfter(uid: Int) {
            val i = rows.indexOfFirst { it.uid == uid }
            val model = rows.getOrNull(i)?.row ?: rows.lastOrNull()?.row
            val cells = model?.cells ?: List(table.columns.size) { "" }
            val r = PRow(nextUid++, CampaignDbFiles.Row(-1, cells))
            change(rows.toMutableList().also { it.add(if (i < 0) it.size else i + 1, r) })
            picked = r.uid
        }
        fun delete(uid: Int) { change(rows.filterNot { it.uid == uid }); picked = -1 }
        fun undoOne() { if (undo.isNotEmpty()) { redo += rows; rows = undo.removeAt(undo.size - 1) } }
        fun redoOne() { if (redo.isNotEmpty()) { undo += rows; rows = redo.removeAt(redo.size - 1) } }
    }

    private val sessions = mutableStateMapOf<String, Session>()
    private var open by mutableStateOf<String?>(null)
    private var listVersion by mutableIntStateOf(0)

    /** For the renders: open the file named [name] of [dir] with its row [row] chosen. */
    fun pickForCheck(dir: File, name: String, row: Int) {
        val e = CampaignDbFiles.list(dir).firstOrNull { it.file.name.equals(name, ignoreCase = true) } ?: return
        open = e.file.absolutePath
        session(e).picked = row
    }

    private fun session(e: CampaignDbFiles.Entry): Session =
        sessions.getOrPut(e.file.absolutePath) { Session(CampaignDbFiles.read(e.kind, e.file)) }

    @Composable
    override fun Content(ctx: CmdContext) {
        val doc = ctx.doc
        val dir = doc.set.campaignDir(doc.theater)
        Column(Modifier.fillMaxSize()) {
            EdDialogHost()
            if (dir == null || !dir.isDirectory) { EdNote("This theater's campaign folder was not found, so its database files cannot be shown.", Hud.Amber); return@Column }
            val entries = remember(dir.absolutePath, listVersion) { CampaignDbFiles.list(dir) }
            if (entries.isEmpty()) { EdNote("${dir.name} holds none of the campaign database files.", Hud.TextDim); return@Column }
            val current = entries.firstOrNull { it.file.absolutePath == open } ?: entries.first()
            val s = session(current)
            Row(Modifier.fillMaxSize()) {
                FileList(entries, current, dir)
                Box1px()
                Column(Modifier.weight(1f).fillMaxHeight()) { FileTable(ctx, current, s, dir) }
                Box1px()
                Column(Modifier.width(320.dp).fillMaxHeight().verticalScroll(rememberScrollState())) { RowInspector(ctx, current, s) }
            }
        }
    }

    @Composable
    private fun Box1px() = androidx.compose.foundation.layout.Box(Modifier.width(1.dp).fillMaxHeight().background(Hud.Outline.copy(alpha = 0.4f)))

    @Composable
    private fun FileList(entries: List<CampaignDbFiles.Entry>, current: CampaignDbFiles.Entry, dir: File) {
        Column(Modifier.width(250.dp).fillMaxHeight().verticalScroll(rememberScrollState())) {
            EdHeading("Campaign database")
            SmallNote("${dir.name} · theater-wide files: every campaign, TE and training of this theater reads them.")
            for (e in entries) {
                val on = e.file.absolutePath == current.file.absolutePath
                val pending = sessions[e.file.absolutePath]?.changes ?: 0
                Column(
                    Modifier.fillMaxWidth().background(if (on) Hud.Amber.copy(alpha = 0.14f) else Color.Transparent)
                        .clickable { open = e.file.absolutePath }.padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Row {
                        Text(e.file.name, color = if (on) Hud.Amber else Hud.Text, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        if (pending > 0) Text("$pending ●", color = Hud.Amber, fontSize = 11.sp)
                    }
                    Text(
                        e.kind.title + " · " + when {
                            e.differsFromOriginal -> "changed (BMS's original kept)"
                            e.backedUp -> "as BMS ships it (original kept)"
                            else -> "not written yet"
                        },
                        color = if (e.differsFromOriginal) Hud.Cyan else Hud.TextFaint, fontSize = 10.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Rule(0.12f)
            }
            EdGap()
            SmallNote("The first save of a file copies BMS's original into ${CommandBackup.FOLDER}\\${CommandBackup.SUB} beside it, with a README on putting it back by hand. Strings.txt and the terrain are not edited here.")
        }
    }

    /** The objective a station's CampId names, in the open file's objectives. */
    private fun objectiveFor(core: CommandSave, campId: String) = campId.trim().toIntOrNull()?.let { id -> core.objectives.firstOrNull { it.campId == id } }

    @Composable
    private fun FileTable(ctx: CmdContext, e: CampaignDbFiles.Entry, s: Session, dir: File) {
        val t = s.table
        Strip {
            Text(e.file.name, color = Hud.Text, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
            Text(e.kind.about, color = Hud.TextDim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        Rule(0.2f)
        EdButtons {
            val n = s.changes
            DeskButton(if (n > 0) "Save ($n)" else "Save", enabled = n > 0, strong = n > 0, tip = "Write ${e.file.name} (its original is backed up first, once)") {
                val (why, back) = CampaignDbFiles.save(t, s.rows.map { it.row })
                if (why != null) CommandSession.error = why
                else {
                    if (back != null) sessions[e.file.absolutePath] = Session(back)
                    listVersion++
                    ctx.say("${e.file.name} written.")
                }
            }
            DeskButton("Undo", enabled = s.undo.isNotEmpty()) { s.undoOne() }
            DeskButton("Redo", enabled = s.redo.isNotEmpty()) { s.redoOne() }
            DeskButton("Read again", tip = if (n > 0) "Throws away the page's changes to this file" else "Reads the file from disk again") {
                sessions[e.file.absolutePath] = Session(CampaignDbFiles.read(e.kind, e.file)); listVersion++
            }
            DeskButton("Add row", enabled = t.canAddRows && t.problems.isEmpty(), tip = "A copy of the chosen row, below it") { s.addAfter(s.picked) }
            DeskButton("Delete row", enabled = t.canAddRows && s.picked >= 0, danger = true) { s.delete(s.picked) }
            DeskButton("Restore BMS's original", enabled = e.backedUp, danger = true, tip = if (e.backedUp) "Puts back the copy kept in ${CommandBackup.FOLDER}\\${CommandBackup.SUB}" else "Nothing to restore: this file was never written here") {
                EdDialogs.ask = EdAsk(
                    "Restore BMS's original?",
                    "${e.file.name} goes back to the copy taken before the Command section first wrote it. Every change made to it since is lost (the page's unsaved changes too).",
                    "Restore", danger = true,
                ) {
                    CampaignDbFiles.restore(e.file).also {
                        if (it == null) { sessions[e.file.absolutePath] = Session(CampaignDbFiles.read(e.kind, e.file)); listVersion++; ctx.say("${e.file.name} restored.") }
                    }
                }
            }
        }
        Rule(0.4f)
        t.problems.forEach { EdNote(it, Hud.Red) }
        val core = ctx.core
        val cols = ArrayList<GridCol<PRow>>()
        var frozen = 1
        // a station's comment goes last: the airbase it names is the frozen first column
        val order = t.columns.indices.sortedBy { if (e.kind == CampaignDbFiles.Kind.STATIONS && t.columns[it].name == "Comment") 1 else 0 }
        if (e.kind == CampaignDbFiles.Kind.STATIONS) {
            val ci = t.col("CampId")
            cols += GridCol("Airbase", 170.dp) { r -> objectiveFor(core, r.row.cells.getOrElse(ci) { "" })?.let { ObjectiveOps.displayName(core, it) } ?: "—" }
            frozen = 2
        }
        for (i in order) {
            val c = t.columns[i]
            val wide = !c.numeric || c.name in setOf("CampName")
            cols += GridCol(
                c.name, if (c.name == "Comment" || c.name == "Name" || c.name == "CampName") 200.dp else if (c.name == "Mission type") 150.dp else if (wide) 120.dp else 84.dp,
                numeric = c.numeric,
                ink = { r -> if (CampaignDbFiles.frequencyProblem(c.name, r.row.cells.getOrElse(i) { "" }) != null) Hud.Red else if (r.row.key < 0 || r.row.key >= t.rows.size || r.row.cells.getOrElse(i) { "" } != t.rows[r.row.key].cells.getOrElse(i) { "" }) Hud.Cyan else null },
            ) { r -> r.row.cells.getOrElse(i) { "" } }
        }
        when (e.kind) {
            CampaignDbFiles.Kind.MISSION_DATA -> frozen = 2
            CampaignDbFiles.Kind.TE_UNITS, CampaignDbFiles.Kind.TE_NAVAL -> {
                val names = ctx.doc.names
                val domain = if (e.kind == CampaignDbFiles.Kind.TE_NAVAL) 4 else 3
                val byKey = remember(names) {
                    (0 until names.ctCount).mapNotNull { names.ct(it + 100) }.filter { it.domain == domain && it.cls == 6 && it.type == 1 }
                        .associate { (it.subType to it.specific) to (names.unitClass(it.num + 100) ?: "") }
                }
                cols += GridCol("Unit (class table)", 220.dp) { r ->
                    val st = r.row.cells.getOrElse(1) { "" }.trim().toIntOrNull(); val sp = r.row.cells.getOrElse(2) { "" }.trim().toIntOrNull()
                    if (st == null || sp == null) "" else byKey[st to sp]?.ifEmpty { null } ?: "not in the class table"
                }
            }
            CampaignDbFiles.Kind.INVALID_AC -> {
                val names = ctx.doc.names
                cols += GridCol("Squadron (class table)", 220.dp) { r -> r.row.cells.getOrElse(1) { "" }.trim().toIntOrNull()?.let { names.unitClass(it + 100) } ?: "" }
            }
            else -> {}
        }
        CmdGrid(
            rows = s.rows,
            cols = cols,
            key = { it.uid },
            selected = { it.uid == s.picked },
            onClick = { r, _ -> s.picked = r.uid },
            frozen = frozen,
            scrollTo = s.picked.takeIf { it >= 0 },
        )
    }

    @Composable
    private fun RowInspector(ctx: CmdContext, e: CampaignDbFiles.Entry, s: Session) {
        val t = s.table
        val r = s.rows.firstOrNull { it.uid == s.picked }
        if (r == null) {
            EdHeading(e.kind.title)
            EdNote("Choose a row to edit its fields. A changed field is drawn in cyan, a frequency outside BMS's bands in red.", Hud.TextDim)
            if (!t.canAddRows) EdNote("This file's rows are fixed: their fields are edited, none added or deleted.", Hud.TextFaint)
            return
        }
        EdHeading(if (r.row.key < 0) "New row" else "Row ${r.row.key + 1}")
        val core = ctx.core
        if (e.kind == CampaignDbFiles.Kind.STATIONS) StationFacts(ctx, core, r.row.cells.getOrElse(t.col("CampId")) { "" }, t, r.row)
        t.columns.forEachIndexed { i, c ->
            val v = r.row.cells.getOrElse(i) { "" }
            if (!c.editable) FactRow(c.name, v, mono = c.numeric)
            else {
                EdText(c.name, v, tip = c.tip, mono = c.numeric) { s.set(r.uid, i, it) }
                CampaignDbFiles.frequencyProblem(c.name, v)?.let { EdNote("$v: $it.", Hud.Red) }
            }
        }
        if (e.kind == CampaignDbFiles.Kind.MISSION_DATA) {
            val fi = t.col("Flags")
            val flags = r.row.cells.getOrElse(fi) { "" }.trim().toLongOrNull()
            if (fi >= 0 && flags != null) {
                EdHeading("Flags, bit by bit")
                val on = (0 until 32).filter { flags and (1L shl it) != 0L }
                EdNote(if (on.isEmpty()) "No bit set." else "Set: " + on.joinToString { "bit $it" }, Hud.TextDim)
                EdButtons {
                    for (b in 0 until 32) com.bmscompanion.desktop.ui.command.MiniChip("$b", selected = flags and (1L shl b) != 0L) {
                        s.set(r.uid, fi, (flags xor (1L shl b)).toString())
                    }
                }
            }
        }
    }

    /** A station's airbase and which runway end each ILS slot serves (the sim's rule, [CampaignDbFiles.ilsSlots]). */
    @Composable
    private fun StationFacts(ctx: CmdContext, core: CommandSave, campId: String, t: CampaignDbFiles.Table, row: CampaignDbFiles.Row) {
        val o = objectiveFor(core, campId)
        FactRow("Airbase", o?.let { ObjectiveOps.displayName(core, it) } ?: "no objective with CampId $campId in this file")
        if (o == null) return
        val airports by produceState<List<Airport>>(emptyList(), ctx.doc.theaterId) {
            value = runCatching { Repo.theater(ctx.doc.theaterId)?.airportSet?.let { Repo.airportSet(it).airports }.orEmpty() }.getOrDefault(emptyList())
        }
        val slots = remember(o.id, core.revision) { ObjectiveOps.ocdOf(core, o).let { if (it >= 0) CampaignDbFiles.ilsSlots(core.tables, it) else emptyMap() } }
        val ap = airports.firstOrNull { it.id == o.campId }
        val known = remember(o.id, core.revision) { core.tables.objectiveData != null && ObjectiveOps.ocdOf(core, o).let { it >= 0 && core.tables.pointHeaders(it).isNotEmpty() } }
        for (k in 0 until 4) {
            val freq = row.cells.getOrElse(t.col("ILS ${k + 1}")) { "" }.trim()
            val slot = slots[k]
            val where = if (!known) "runway unknown (BMS's runway data for this field was not found)"
            else if (slot == null) "serves no runway end (the field has no such runway end for BMS)" else {
                val (q, course) = slot
                val end = ap?.runways?.firstOrNull { it.dbRunway == q }?.ends?.minByOrNull { abs(((it.headingTrue - course) % 360 + 540) % 360 - 180) }
                    ?.takeIf { abs(((it.headingTrue - course) % 360 + 540) % 360 - 180) < 30 }
                (end?.designator?.let { "runway $it" } ?: "runway ${q + 1}, course ${course.roundToInt()}°") + " (BMS's runway $q)"
            }
            val f = freq.toIntOrNull()?.takeIf { it > 0 }?.let { "%d.%02d".format(it / 100, it % 100) } ?: "none"
            FactRow("ILS ${k + 1}", "$f · $where", ink = if (known && slot == null && freq.toIntOrNull()?.let { it > 0 } == true) Hud.Amber else Hud.Text)
        }
    }
}
