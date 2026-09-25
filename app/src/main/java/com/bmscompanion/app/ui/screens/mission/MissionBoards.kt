package com.bmscompanion.app.ui.screens.mission

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bmscompanion.app.data.mission.BoardTable
import com.bmscompanion.app.data.mission.EzRun
import com.bmscompanion.app.data.mission.LinkState
import com.bmscompanion.app.data.mission.MissionLink
import com.bmscompanion.app.ui.components.CollapsibleCard
import com.bmscompanion.app.ui.components.Masonry
import com.bmscompanion.app.ui.components.SectionCard
import com.bmscompanion.app.ui.theme.Hud
import com.bmscompanion.app.ui.theme.LocalExtra
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Everything that gets printed, carried or pinned in the headset, on one page.
 *
 * It was three places: a Boards tab for the EZBoards sheets, a VR boards tab for the OpenKneeboard addresses, and a
 * card on Briefing for the HTML Briefing pages — three names for the same job. Here the page opens with what a pilot
 * actually has to do in BMS (print, then save the DTC), says plainly whether the kneeboards came out of it, and keeps
 * the manual button underneath as the fallback it is. The two sections below fold, and remember being folded.
 */
@Composable
fun MissionBoardsPane(env: MissionEnv, onSetup: () -> Unit) {
    val info by MissionLink.info.collectAsState()
    val mission by MissionLink.mission.collectAsState()
    val board = mission?.board
    val ezInfo = info?.ezBoards

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        EzGenerateCard(onSetup)

        // The VR board addresses: a page of its own until now, and only ever on the PC that runs the headset.
        vrBoardsPane?.let { pane ->
            CollapsibleCard("VR boards for OpenKneeboard", initiallyOpen = true, accent = Hud.Cyan, rememberKey = "kb_vr") { pane() }
        }

        // html_brief is somebody else's tool: folded shut for everyone who does not use it, and open ever after for
        // everyone who does.
        CollapsibleCard(
            "HTML Briefing kneeboard",
            initiallyOpen = false,
            accent = Hud.Amber,
            rememberKey = "kb_html",
            preview = "The pages BMS's own HTML Briefing tool exports. Nothing here needs you if you do not use it.",
        ) { KneeboardPages(env.nav) }

        if (board == null || board.tables.none { it.rows.isNotEmpty() }) {
            if (ezInfo?.configured == true && info?.briefing?.available == true) {
                Text("Board preview unavailable (xbrief could not read the briefing).", color = Hud.TextFaint, fontSize = 12.sp)
            }
            return@Column
        }
        Text("KNEEBOARD CONTENT", style = LocalExtra.current.overline, color = Hud.TextFaint, modifier = Modifier.padding(start = 4.dp))
        Masonry(minColumn = 480.dp, maxColumns = 2) {
            board.tables.filter { it.rows.isNotEmpty() }.forEach { BoardTableCard(it) }
        }
        board.tables.firstOrNull { it.rows.isEmpty() && it.title.isNotBlank() }?.let { Text(it.title, fontSize = 11.sp, color = Hud.TextFaint, modifier = Modifier.padding(start = 4.dp)) }
    }
}

/**
 * What to do in BMS, whether it worked, and the manual way out.
 *
 * The button used to be the whole card, which put the least-used path first: with auto-generate on, printing the
 * briefing is all a pilot ever does, and the button is for the times that went wrong. So the two steps come first, the
 * state of the kneeboards is stated rather than implied, and the button sits under a rule with its own explanation.
 */
@Composable
fun EzGenerateCard(onSetup: () -> Unit) {
    val info by MissionLink.info.collectAsState()
    val ez by MissionLink.ez.collectAsState()
    val link by MissionLink.state.collectAsState()
    val ezInfo = info?.ezBoards
    val last: EzRun? = listOfNotNull(ez.result, ezInfo?.lastRun).maxByOrNull { it.time }
    val running = ez.running || ezInfo?.running == true
    val printed = info?.briefing?.available == true

    SectionCard("BMS kneeboards", accent = Hud.Amber) {
        Text(
            "The sheets BMS shows in the cockpit, written from your briefing by EZBoards (by Logic, which BMS ships).",
            style = MaterialTheme.typography.bodySmall, color = Hud.TextDim,
        )

        Spacer(Modifier.height(12.dp))
        KneeboardState(last, running, printed, info?.briefing?.generated, ezInfo?.autoOnPrint == true)

        Spacer(Modifier.height(14.dp))
        Text("IN BMS, BEFORE THE MISSION STARTS", style = LocalExtra.current.overline, color = Hud.TextFaint)
        Spacer(Modifier.height(6.dp))
        // Only tell the pilot that PRINT does it by itself when it actually will. The setting lived on the PC and
        // defaults to off, so this page used to promise something that did not happen and gave no reason why.
        val auto = ezInfo?.autoOnPrint == true
        Step(
            "1", "Print the briefing",
            if (auto) {
                "On the briefing screen press PRINT (top right). BMS Companion sees the printed briefing and generates " +
                    "the kneeboards itself — you do not need to come back to this page."
            } else {
                "On the briefing screen press PRINT (top right). That is what writes the briefing BMS Companion reads. " +
                    "It will not generate the kneeboards on its own until you switch that on below."
            },
        )
        Step(
            "2", "Save the DTC",
            "On the DTC screen press SAVE. That supplies the steerpoints, radio presets, IFF and weapon target names, " +
                "and your position before you are in the aircraft.",
        )

        Spacer(Modifier.height(14.dp))
        androidx.compose.material3.HorizontalDivider(color = Hud.Outline.copy(alpha = 0.5f))
        Spacer(Modifier.height(12.dp))
        val enabled = link is LinkState.Online && ezInfo?.configured == true && !running
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.clip(RoundedCornerShape(12.dp)).background(if (enabled) Hud.Surface3 else Hud.Surface2)
                    .border(1.dp, if (enabled) Hud.Amber.copy(alpha = 0.7f) else Hud.Outline.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                    .clickable(enabled = enabled) { MissionLink.generateBoards() }
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (running) {
                    CircularProgressIndicator(Modifier.size(16.dp), color = Hud.Amber, strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                }
                Text(if (running) "GENERATING…" else "GENERATE NOW", color = if (enabled || running) Hud.Amber else Hud.TextFaint, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "A manual fallback: use it only if the kneeboards were not produced on their own — the briefing was " +
                        "printed before BMS Companion was running, or the print went unnoticed.",
                    color = Hud.TextDim, fontSize = 12.sp,
                )
                when {
                    link !is LinkState.Online -> Text("Not connected to the BMS PC", color = Hud.Red, fontSize = 12.sp)
                    ezInfo?.configured != true -> Text("EZBoards folder not set on the PC", color = Hud.Amber, fontSize = 12.sp, modifier = Modifier.clickable(onClick = onSetup))
                    else -> {}
                }
            }
        }

        // The switch that makes step 1 true. It lives on the BMS PC, but this is the page that explains it, and a
        // pilot reading it on a tablet had no way to turn it on from here.
        if (link is LinkState.Online && ezInfo?.configured == true) {
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .clickable { MissionLink.setBoardsOnPrint(!auto) }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(width = 38.dp, height = 22.dp).clip(RoundedCornerShape(11.dp))
                        .background(if (auto) Hud.Green.copy(alpha = 0.35f) else Hud.Surface2)
                        .border(1.dp, if (auto) Hud.Green else Hud.Outline, RoundedCornerShape(11.dp)),
                    contentAlignment = if (auto) Alignment.CenterEnd else Alignment.CenterStart,
                ) {
                    Box(Modifier.padding(horizontal = 3.dp).size(16.dp).clip(RoundedCornerShape(8.dp))
                        .background(if (auto) Hud.Green else Hud.TextFaint))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Generate them the moment you press PRINT", color = Hud.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (auto) "On. Printing the briefing in BMS writes the kneeboards by itself."
                        else "Off. Printing the briefing does nothing here until you turn this on.",
                        color = if (auto) Hud.TextDim else Hud.Amber, fontSize = 12.sp,
                    )
                }
            }
        }

        last?.let { ResultBanner(it) }
    }
}

/** One line that says whether the kneeboards for this mission exist, and how they came to. */
@Composable
private fun KneeboardState(last: EzRun?, running: Boolean, printed: Boolean, printedAt: String?, auto: Boolean) {
    val done = last != null && last.ok
    val col = when {
        running -> Hud.Cyan
        done -> Hud.Green
        last != null -> Hud.Red
        else -> Hud.Amber
    }
    val title = when {
        running -> "Generating…"
        done -> if (last!!.auto) "Kneeboards generated automatically" else "Kneeboards generated"
        last != null -> "The last run did not finish"
        printed -> "Briefing printed, no kneeboards yet"
        else -> "No briefing printed yet"
    }
    val detail = when {
        running -> "EZBoards is writing them now."
        done -> listOfNotNull(
            SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(last!!.time)),
            if (last.auto) "as soon as you printed the briefing" else "from the button on this page",
        ).joinToString(" · ")
        last != null -> last.message
        printed -> listOfNotNull(printedAt?.let { "Briefing $it" }, "press PRINT again, or generate them below").joinToString(" · ")
        auto -> "They are generated the moment you press PRINT on the BMS briefing screen."
        else -> "Print the briefing in BMS, then generate them below."
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(col.copy(alpha = 0.10f))
            .border(1.dp, col.copy(alpha = 0.45f), RoundedCornerShape(12.dp)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (done) Icons.Default.CheckCircle else Icons.Default.ErrorOutline, null, tint = col, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = col, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(detail, color = Hud.TextDim, fontSize = 12.sp)
        }
    }
}

/** A numbered thing to do, in BMS rather than here. */
@Composable
private fun Step(n: String, title: String, text: String) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Text(
            n,
            Modifier.clip(RoundedCornerShape(6.dp)).border(1.dp, Hud.Cyan.copy(alpha = 0.5f), RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 1.dp),
            color = Hud.Cyan, style = LocalExtra.current.monoSmall,
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Hud.Text, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Text(text, color = Hud.TextDim, fontSize = 12.sp)
        }
    }
}

@Composable
private fun ResultBanner(r: EzRun) {
    var open by remember(r.time) { mutableStateOf(!r.ok) }
    val col = if (r.ok) Hud.Green else Hud.Red
    Spacer(Modifier.height(12.dp))
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(col.copy(alpha = 0.10f)).border(1.dp, col.copy(alpha = 0.45f), RoundedCornerShape(12.dp)).clickable { open = !open }.padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (r.ok) Icons.Default.CheckCircle else Icons.Default.ErrorOutline, null, tint = col, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(r.message, color = col, fontWeight = FontWeight.SemiBold)
                Text(
                    listOfNotNull(
                        SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(r.time)),
                        r.durationMs.takeIf { it > 0 }?.let { String.format(Locale.US, "%.1f s", it / 1000.0) },
                        if (r.auto) "auto after PRINT" else null,
                        if (r.log.isNotEmpty()) (if (open) "hide log" else "show log") else null,
                    ).joinToString(" · "),
                    fontSize = 11.sp, color = Hud.TextDim,
                )
            }
        }
        AnimatedVisibility(open && r.log.isNotEmpty()) {
            Column(Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0xFF05090C)).padding(8.dp).horizontalScroll(rememberScrollState())) {
                r.log.forEach { Text(it, style = LocalExtra.current.monoSmall.copy(fontSize = 11.sp), color = Hud.TextDim, maxLines = 1, softWrap = false) }
            }
        }
    }
}

/** An xbrief table rendered natively: own flight rows highlighted, wide tables scroll sideways on phones. */
@Composable
private fun BoardTableCard(t: BoardTable) {
    val accent = when {
        t.title.startsWith("Package") -> Hud.Cyan
        t.title.startsWith("Comm") -> Hud.Amber
        t.title.startsWith("Steer") -> Hud.Amber
        t.title.startsWith("Target") -> Hostile
        t.title.startsWith("Weather") -> Hud.Cyan
        else -> Hud.Green
    }
    val cols = maxOf(t.header.size, t.rows.maxOfOrNull { it.cells.size } ?: 0)
    val widths = (0 until cols).map { c ->
        val longest = (listOfNotNull(t.header.getOrNull(c)) + t.rows.mapNotNull { it.cells.getOrNull(c) }).maxOfOrNull { it.length } ?: 4
        (longest * 7.2f + 14).coerceIn(34f, 190f).dp
    }
    SectionCard(t.title.ifBlank { "Board" }, accent = accent) {
        Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            Column {
                if (t.header.isNotEmpty()) Row(Modifier.padding(bottom = 4.dp)) {
                    t.header.forEachIndexed { i, h -> Text(h, Modifier.width(widths[i]), fontSize = 10.sp, color = Hud.TextFaint, fontWeight = FontWeight.SemiBold, maxLines = 1) }
                }
                t.rows.forEach { r ->
                    val own = r.kind?.startsWith("own") == true
                    Row(
                        Modifier.clip(RoundedCornerShape(4.dp)).background(if (own) Hud.Amber.copy(alpha = 0.10f) else Color.Transparent).padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        r.cells.forEachIndexed { i, cell ->
                            Text(
                                cell, Modifier.width(widths.getOrElse(i) { 60.dp }).padding(end = 4.dp),
                                style = LocalExtra.current.monoSmall.copy(fontSize = 12.sp),
                                color = when { own && i == 0 -> Hud.Amber; i == 0 -> Hud.Text; cell == "--" -> Hud.TextFaint; else -> Hud.Text },
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

