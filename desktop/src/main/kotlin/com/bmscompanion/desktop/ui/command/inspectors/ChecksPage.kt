package com.bmscompanion.desktop.ui.command.inspectors

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bmscompanion.app.ui.screens.editor.Rule
import com.bmscompanion.app.ui.theme.Hud
import com.bmscompanion.desktop.bridge.command.CheckIssue
import com.bmscompanion.desktop.bridge.command.CheckRef
import com.bmscompanion.desktop.bridge.command.CheckSeverity
import com.bmscompanion.desktop.bridge.command.CommandChecks
import com.bmscompanion.desktop.bridge.command.Edits
import com.bmscompanion.desktop.ui.command.CmdContext
import com.bmscompanion.desktop.ui.command.CmdKind
import com.bmscompanion.desktop.ui.command.CmdPage
import com.bmscompanion.desktop.ui.command.CmdPageView
import com.bmscompanion.desktop.ui.command.CmdRef
import com.bmscompanion.desktop.ui.command.CommandSession
import com.bmscompanion.desktop.ui.command.DeskButton
import com.bmscompanion.desktop.ui.command.MiniChip
import com.bmscompanion.desktop.ui.command.Strip

/**
 * The Checks page: one report of everything in the open file Falcon BMS would trip over or that looks like a mistake
 * ([CommandChecks], with the other editors' checks), grouped, each line with what it is about (a press selects it and
 * turns to its page) and its **Fix** where one is safe; a group's fixes can be run together. Every fix is an undoable
 * edit, and the report is made again after each.
 */
object ChecksPage : CmdPageView {
    override val page = CmdPage.CHECKS

    @Composable
    override fun Content(ctx: CmdContext) {
        val ver = CommandSession.version
        val issues = remember(ctx.core, ver) { runCatching { CommandChecks.run(ctx.core) }.getOrElse { listOf(CheckIssue("Checks", CheckSeverity.ERROR, "The checks could not run: ${it.message}")) } }
        var minSev by remember { mutableStateOf(CheckSeverity.INFO) }
        val shown = issues.filter { it.severity.ordinal <= minSev.ordinal }
        Column(Modifier.fillMaxSize()) {
            EdDialogHost()
            Strip {
                val e = issues.count { it.severity == CheckSeverity.ERROR }
                val w = issues.count { it.severity == CheckSeverity.WARNING }
                val i = issues.count { it.severity == CheckSeverity.INFO }
                Text(if (issues.isEmpty()) "Nothing found." else "$e error${if (e == 1) "" else "s"} · $w warning${if (w == 1) "" else "s"} · $i note${if (i == 1) "" else "s"}",
                    color = if (e > 0) Hud.Red else if (w > 0) Hud.Amber else Hud.Green, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                MiniChip("Errors", minSev == CheckSeverity.ERROR) { minSev = CheckSeverity.ERROR }
                MiniChip("And warnings", minSev == CheckSeverity.WARNING) { minSev = CheckSeverity.WARNING }
                MiniChip("Everything", minSev == CheckSeverity.INFO) { minSev = CheckSeverity.INFO }
            }
            Rule(0.5f)
            val groups = shown.groupBy { it.group }
            val listState = androidx.compose.foundation.lazy.rememberLazyListState()
            Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(Modifier.fillMaxSize().padding(end = com.bmscompanion.desktop.ui.command.CMD_BAR), state = listState) {
                for ((g, list) in groups) {
                    item(key = "h:$g") {
                        val fixable = list.filter { it.fix != null }
                        EdHeading("$g (${list.size})") {
                            if (fixable.size > 1) DeskButton("Fix all ${fixable.size}", tip = "Every fix of this group as one edit; a fix that is refused is left out and named") {
                                // each fix on its own: one refused never stops the rest (Edits.Each)
                                val notMade = ArrayList<String>()
                                val edits = fixable.mapNotNull { f ->
                                    runCatching { f.fix!!.invoke() }.getOrElse { notMade += "${f.text}: ${it.message ?: it.javaClass.simpleName}"; null }
                                }
                                val each = Edits.Each("Fix ${edits.size} findings: $g", ctx.core.edits, edits)
                                val ok = edits.isNotEmpty() && ctx.run(each)
                                notMade += each.refused
                                if (notMade.isNotEmpty()) ctx.say(
                                    (if (ok) "Fixed ${each.made} of ${fixable.size}; " else "No fix was made; ") +
                                        "${notMade.size} refused: " + notMade.take(3).joinToString("; ") + if (notMade.size > 3) " …" else "",
                                )
                            }
                        }
                    }
                    items(list, key = { "i:$g:${it.text}:${System.identityHashCode(it)}" }) { issue -> Line(ctx, issue) }
                }
                if (shown.isEmpty()) item { EdNote(if (issues.isEmpty()) "The file reads clean: no doubled ids, no references to missing units or objectives, the squadron list and the units agree, every objective change names an objective of the start." else "Nothing at this level.") }
            }
                com.bmscompanion.desktop.ui.command.CmdVBar(androidx.compose.foundation.rememberScrollbarAdapter(listState), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
            }
        }
    }

    @Composable
    private fun Line(ctx: CmdContext, issue: CheckIssue) {
        Row(Modifier.fillMaxWidth().heightIn(min = 26.dp).padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(when (issue.severity) { CheckSeverity.ERROR -> Hud.Red; CheckSeverity.WARNING -> Hud.Amber; CheckSeverity.INFO -> Hud.TextFaint }))
            Text(issue.text, color = Hud.Text, fontSize = 12.sp, lineHeight = 15.sp, modifier = Modifier.weight(1f).padding(start = 8.dp))
            val ref = issue.ref?.let { toRef(it) }
            if (ref != null) Text("Show", color = Hud.Amber, fontSize = 11.5.sp, modifier = Modifier.padding(horizontal = 6.dp).clickable {
                ctx.selection.set(ref)
                ctx.open(if (ref.kind == CmdKind.OBJECTIVE) CmdPage.OBJECTIVES else if (ref.kind == CmdKind.TEAM) CmdPage.TEAMS else CmdPage.UNITS)
                CommandSession.flyTo(ref)
            })
            Box(Modifier.widthIn(min = 150.dp), contentAlignment = Alignment.CenterEnd) {
                if (issue.fix != null) DeskButton(issue.fixLabel ?: "Fix") {
                    val e = runCatching { issue.fix.invoke() }.getOrElse { ctx.say("That fix could not be made: ${it.message}"); null }
                    if (e != null) ctx.edit(e)
                }
            }
        }
        Rule(0.1f)
    }

    private fun toRef(r: CheckRef): CmdRef? = when (r.kind) {
        "objective" -> CmdRef(CmdKind.OBJECTIVE, r.id)
        "flight" -> CmdRef(CmdKind.FLIGHT, r.id)
        "package" -> CmdRef(CmdKind.PACKAGE, r.id)
        "squadron" -> CmdRef(CmdKind.SQUADRON, r.id)
        "battalion" -> CmdRef(CmdKind.BATTALION, r.id)
        "brigade" -> CmdRef(CmdKind.BRIGADE, r.id)
        "taskforce" -> CmdRef(CmdKind.TASKFORCE, r.id)
        "team" -> CmdRef(CmdKind.TEAM, r.id)
        else -> null
    }
}
