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
import com.bmscompanion.app.ui.components.Masonry
import com.bmscompanion.app.ui.components.SectionCard
import com.bmscompanion.app.ui.theme.Hud
import com.bmscompanion.app.ui.theme.LocalExtra
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MissionBoardsPane(env: MissionEnv, onSetup: () -> Unit) {
    val info by MissionLink.info.collectAsState()
    val mission by MissionLink.mission.collectAsState()
    val board = mission?.board
    val ezInfo = info?.ezBoards

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        EzGenerateCard(onSetup)

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

/** The generate button with its status and last result (also a Dashboard card). */
@Composable
fun EzGenerateCard(onSetup: () -> Unit) {
    val info by MissionLink.info.collectAsState()
    val ez by MissionLink.ez.collectAsState()
    val link by MissionLink.state.collectAsState()
    val ezInfo = info?.ezBoards
    val last: EzRun? = listOfNotNull(ez.result, ezInfo?.lastRun).maxByOrNull { it.time }
    val running = ez.running || ezInfo?.running == true
    SectionCard("EZBoards kneeboards", accent = Hud.Amber) {
        Text(
            "Writes your briefing onto the in-cockpit kneeboards (EZBoards by Logic). Press PRINT on the BMS briefing screen first, and Save the DTC for target steerpoints.",
            style = MaterialTheme.typography.bodySmall, color = Hud.TextDim,
        )
        Spacer(Modifier.height(12.dp))
        val enabled = link is LinkState.Online && ezInfo?.configured == true && !running
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.clip(RoundedCornerShape(14.dp)).background(if (enabled) Hud.Amber else Hud.Surface3).clickable(enabled = enabled) { MissionLink.generateBoards() }
                    .padding(horizontal = 22.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (running) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = Hud.Bg, strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                }
                Text(if (running) "GENERATING…" else "GENERATE KNEEBOARDS", color = if (enabled || running) Hud.Bg else Hud.TextFaint, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                when {
                    link !is LinkState.Online -> Text("Not connected to the BMS PC", color = Hud.Red, fontSize = 12.sp)
                    ezInfo?.configured != true -> Text("EZBoards folder not set on the PC", color = Hud.Amber, fontSize = 12.sp, modifier = Modifier.clickable(onClick = onSetup))
                    info?.briefing?.available != true -> Text("No briefing printed yet", color = Hud.Amber, fontSize = 12.sp)
                    else -> Text("Briefing ${info?.briefing?.generated ?: ""}", color = Hud.TextDim, fontSize = 12.sp)
                }
                if (ezInfo?.autoOnPrint == true) Text("Auto-generate on PRINT is on", color = Hud.Green, fontSize = 11.sp)
            }
        }
        last?.let { ResultBanner(it) }
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

