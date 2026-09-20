package com.bmscompanion.app.ui.screens.mission

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.mission.MissionLink
import com.bmscompanion.app.ui.components.SectionCard
import com.bmscompanion.app.ui.components.Tag
import com.bmscompanion.app.ui.go
import com.bmscompanion.app.ui.screens.KNEEBOARD_SCHEME
import com.bmscompanion.app.ui.screens.chartRoute
import com.bmscompanion.app.ui.theme.Hud
import kotlinx.coroutines.launch

/**
 * The kneeboard UOAF's html_brief exported on the BMS PC, read here as a chart.
 *
 * That tool turns a printed BMS briefing into kneeboard pages — package and weather, roster and loadout, the flight
 * plan, targets, the map, the airfield and comms — and puts them in the cockpit. The same pages are worth having on
 * the tablet, so the PC renders whatever it last exported and this shows them: page cards that open in the **chart
 * viewer**, with its zoom buttons, its arrows, its swipe and Esc to close, because a kneeboard page is a chart in
 * every way that matters. Nothing here writes to that tool's folder.
 *
 * This is also where html_brief is started from: it has no headless export, so the button puts its window up on the
 * BMS PC for the pilot to export in. When BMS has printed a newer briefing than the export, the card says so rather
 * than quietly showing last flight's pages. When nobody has told BMS Companion where the tool is, the card says what
 * it is and what setting it up would buy, instead of vanishing.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KneeboardCard(nav: NavHostController) {
    val info by MissionLink.info.collectAsState()
    val kb = info?.kneeboard
    var note by remember { mutableStateOf<String?>(null) }
    if (kb == null) return

    SectionCard(
        if (kb.available) "HTML Briefing generated kneeboard (${kb.pages})" else "HTML Briefing generated kneeboard",
        accent = Hud.Amber,
        trailing = { if (kb.stale) Tag("briefing is newer", Hud.Amber) },
    ) {
        // Not set up. A pilot who has never heard of html_brief cannot be expected to guess that one folder in the
        // settings is all that stands between them and these pages, so the card says it.
        if (!kb.configured) {
            Text(
                "This section shows the kneeboard pages exported by BMS's HTML Briefing tool (html_brief, which BMS " +
                    "ships in Tools\\html_brief_win). That tool is run separately and exports PDFs of its own — if you " +
                    "do not use it, you can ignore this card entirely. Point BMS Companion at its folder and its pages " +
                    "show here, on every device, and as a board in VR.",
                style = MaterialTheme.typography.bodySmall, color = Hud.TextDim,
            )
            Text(
                "On the BMS PC: Settings → Falcon BMS settings → HTML Briefing folder.",
                style = MaterialTheme.typography.bodySmall, color = Hud.TextFaint,
                modifier = Modifier.padding(top = 6.dp),
            )
            return@SectionCard
        }
        if (!kb.available) {
            Text(kb.message ?: "Nothing exported yet.", style = MaterialTheme.typography.bodySmall, color = Hud.TextDim)
            RunExporter(true) { note = it }
            note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Hud.Green) }
            return@SectionCard
        }
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            repeat(kb.pages) { i ->
                Column(
                    Modifier.width(142.dp).clip(RoundedCornerShape(10.dp)).background(Hud.Surface2)
                        .clickable { nav.go(chartRoute("$KNEEBOARD_SCHEME:1", "HTML Briefing kneeboard", kb.pages, i + 1)) },
                ) {
                    Box(Modifier.fillMaxWidth().height(160.dp).background(Color.White)) {
                        KneeboardThumb(i, kb.exported)
                    }
                    Text("Page ${i + 1}", Modifier.padding(8.dp), fontSize = 12.sp)
                }
            }
        }
        Text(
            if (kb.stale) "The briefing has been printed since these were exported — export again to bring them up to date."
            else "These are the kneeboard pages BMS's HTML Briefing tool (html_brief) exported for this mission — " +
                "tap one to open it. It is a separate tool you run yourself; if you do not use it, nothing here needs " +
                "your attention.",
            style = MaterialTheme.typography.bodySmall,
            color = if (kb.stale) Hud.Amber else Hud.TextFaint,
            modifier = Modifier.padding(top = 6.dp),
        )
        RunExporter(kb.stale) { note = it }
        note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Hud.Green) }
    }
}

/**
 * The button that puts the HTML Briefing window up on the BMS PC.
 *
 * It belongs here rather than in the settings because this is where its pages are read: exporting is part of preparing
 * a briefing, not part of setting the program up. It is only a shortcut — html_brief has no headless export, so the
 * pilot still presses its own buttons.
 */
@Composable
private fun RunExporter(urgent: Boolean, onNote: (String?) -> Unit) {
    val scope = rememberCoroutineScope()
    Box(Modifier.padding(top = 8.dp)) {
        SmallButton("Run HTML Briefing", null, primary = urgent) {
            scope.launch { onNote(MissionLink.openKneeboardExporter()) }
        }
    }
}

/** A page at card size. A plain picture, so the tap belongs to the card and opens it — a zoomable one ate it. */
@Composable
private fun KneeboardThumb(index: Int, exported: Long) {
    val bmp by produceState<Bitmap?>(null, index, exported) {
        value = MissionLink.fetchBytes(kneeboardPagePath(index, 420))?.let { Repo.decodeBitmap(it) }
    }
    val b = bmp
    if (b != null) {
        Image(b.asImageBitmap(), "Page ${index + 1}", Modifier.fillMaxSize().padding(2.dp), contentScale = ContentScale.Fit)
    } else {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Hud.Amber, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
        }
    }
}

/** Where a page comes from. The PC renders it; every device simply shows the picture. */
fun kneeboardPagePath(index: Int, max: Int) = "/api/kneeboard/page?i=$index&max=$max"
