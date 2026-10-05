package com.bmscompanion.app.ui.screens.mission

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.mission.MissionLink
import com.bmscompanion.app.data.mission.RadioCategory
import com.bmscompanion.app.data.mission.RadioFeed
import com.bmscompanion.app.data.mission.RadioLogStatus
import com.bmscompanion.app.data.mission.RadioMessage
import com.bmscompanion.app.data.mission.RadioPolling
import com.bmscompanion.app.ui.components.HudChip
import com.bmscompanion.app.ui.components.SearchField
import com.bmscompanion.app.ui.components.SectionCard
import com.bmscompanion.app.ui.theme.Hud
import com.bmscompanion.app.ui.theme.LocalExtra
import kotlinx.coroutines.launch

/**
 * The colour of a group, after BMS's own idea for its subtitles: controllers green (`g_sRadioTowerCol`), the pilot's
 * flight red (`g_sRadioflightCol`), AWACS blue (`g_sRadioStandardCol`); tankers amber, other flights magenta.
 */
fun radioColor(category: String): Color = when (category) {
    RadioCategory.ATC -> Hud.Green
    RadioCategory.AWACS -> Hud.Blue
    RadioCategory.TANKER -> Hud.Amber
    RadioCategory.MINE -> Hud.Red
    RadioCategory.FLIGHTS -> Hud.Magenta
    else -> Hud.TextDim
}

/** What the page shows when the PC has no debug log: the two things to switch on in BMS, and that nothing else needs them. */
const val RADIO_NEEDS =
    "The radio log needs two things in Falcon BMS: debug mode (BMS Launcher or Alternative Launcher, its debug option), " +
        "which makes BMS write User\\Logs\\…_xlog.txt, and Display Radio Subtitles ticked (SETUP → SIMULATION). " +
        "Without them this page stays empty and everything else works as before."

/**
 * The Radio page: every call BMS subtitled this session, filed under ATC, AWACS, tanker, the pilot's own flight, other
 * flights and the rest, newest at the bottom and followed as they come, with the sim time and a search.
 */
@Composable
fun MissionRadioPane(env: MissionEnv) {
    val info by MissionLink.info.collectAsState()
    val status = info?.radio ?: RadioLogStatus()
    RadioPolling(on = status.state != RadioLogStatus.OFF || RadioFeed.log.messages.isNotEmpty())
    val log = RadioFeed.log
    val all = log.messages
    if (status.state == RadioLogStatus.OFF && all.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(RADIO_NEEDS, color = Hud.TextDim, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 560.dp))
        }
        return
    }
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    val shown = remember(all, filter, query) {
        val q = query.trim().lowercase()
        all.filter { m ->
            (filter == null || m.category == filter || (filter == RadioCategory.MINE && m.mine)) &&
                (q.isEmpty() || m.text.lowercase().contains(q) || m.time.contains(q))
        }
    }
    val listState = rememberLazyListState()
    // newest at the bottom, followed as they come — unless the pilot has scrolled up to read something
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) return@LaunchedEffect
        val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        follow = last >= listState.layoutInfo.totalItemsCount - 2
    }
    LaunchedEffect(shown.size, shown.lastOrNull()?.seq) {
        if (follow && shown.isNotEmpty()) listState.scrollToItem(shown.size - 1)
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StateDot(status.state)
            Spacer(Modifier.width(8.dp))
            Text(
                listOfNotNull(
                    when (status.state) { RadioLogStatus.LIVE -> "LIVE"; RadioLogStatus.WAITING -> "WAITING"; else -> "OFF" },
                    log.own?.let { "you: $it" },
                    status.file,
                    "${all.size} calls",
                ).joinToString("  ·  "),
                color = Hud.TextDim, style = LocalExtra.current.monoSmall, maxLines = 1,
            )
        }
        status.note?.takeIf { status.state != RadioLogStatus.LIVE }?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, color = Hud.TextDim, fontSize = 12.sp)
        }
        Spacer(Modifier.height(8.dp))
        SearchField(query, { query = it }, "Search the calls (callsign, runway, words)")
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier.fillMaxWidth().horizontalScrollCompat(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HudChip("All ${all.size}", filter == null) { filter = null }
            for (c in RadioCategory.ALL) {
                val n = all.count { it.category == c || (c == RadioCategory.MINE && it.mine) }
                HudChip("${RadioCategory.label(c)} $n", filter == c) { filter = if (filter == c) null else c }
            }
        }
        Spacer(Modifier.height(6.dp))
        if (shown.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    if (all.isEmpty()) "No radio calls yet this session." else "No call matches.",
                    color = Hud.TextDim, fontSize = 13.sp,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxWidth().weight(1f), state = listState) {
                items(shown, key = { it.seq }) { m -> RadioRow(m) }
            }
        }
    }
}

@Composable
private fun Modifier.horizontalScrollCompat(): Modifier =
    this.then(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()))

@Composable
private fun StateDot(state: String) {
    val c = when (state) { RadioLogStatus.LIVE -> Hud.Green; RadioLogStatus.WAITING -> Hud.Amber; else -> Hud.TextFaint }
    Box(Modifier.size(9.dp).clip(CircleShape).background(c))
}

@Composable
private fun RadioRow(m: RadioMessage) {
    val c = radioColor(m.category)
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(m.time, color = Hud.TextFaint, style = LocalExtra.current.monoSmall, modifier = Modifier.width(64.dp))
        Box(Modifier.padding(top = 3.dp).size(width = 3.dp, height = 14.dp).background(c, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            val who = listOfNotNull(m.from, m.to?.let { "→ $it" }).joinToString(" ")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(RadioCategory.label(m.category).uppercase(), color = c, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                if (who.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    Text(who, color = Hud.TextDim, fontSize = 11.sp, maxLines = 1)
                }
            }
            Text(m.text, color = if (m.mine) Hud.Text else Hud.Text.copy(alpha = 0.88f), fontSize = 13.sp)
        }
    }
}

/** Whether the Radio tab shows: by itself while the PC has BMS's debug log, or always when the pilot asks. */
object RadioTabPrefs {
    private const val KEY = "mission_tab_radio_always"
    private var always by mutableStateOf(Repo.getInt(KEY, 0))
    var showAlways: Boolean
        get() = always == 1
        set(v) { always = if (v) 1 else 0; Repo.putInt(KEY, always) }
    val shown: Boolean get() = showAlways || RadioFeed.logOn
}

/**
 * The radio log on the Setup pages: what it needs in BMS, whether the PC has it now, the Radio page's switch, and
 * deleting BMS's old debug logs (off unless turned on; to the Recycle Bin, never the newest session's).
 */
@Composable
fun RadioLogCard() {
    val info by MissionLink.info.collectAsState()
    val st = info?.radio ?: RadioLogStatus()
    val scope = rememberCoroutineScope()
    var said by remember { mutableStateOf<String?>(null) }
    SectionCard("Radio log & automatic taxi", accent = Hud.Green) {
        Text(
            "Needs Falcon BMS's debug mode (writes User\\Logs\\…_xlog.txt). Off: these features stay off and everything else works as before.",
            color = Hud.TextDim, fontSize = 12.sp,
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            StateDot(st.state)
            Spacer(Modifier.width(8.dp))
            Text(
                when (st.state) {
                    RadioLogStatus.LIVE -> "Live — ${st.lines} radio lines from ${st.file}"
                    RadioLogStatus.WAITING -> "Waiting — ${st.file ?: "debug log found"}"
                    else -> "Off — no debug log"
                },
                color = Hud.Text, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
            )
        }
        st.note?.takeIf { st.state != RadioLogStatus.LIVE }?.let { Text(it, color = Hud.TextDim, fontSize = 12.sp) }
        Spacer(Modifier.height(6.dp))
        // the two things to switch on in BMS; the second cannot be read from the pilot file safely, so it is a check
        Text("In BMS:", color = Hud.Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Text(
            (if (st.state != RadioLogStatus.OFF) "● " else "○ ") + "Debug mode on — BMS Launcher or Alternative Launcher, its debug option",
            color = Hud.TextDim, fontSize = 12.sp,
        )
        Text(
            (if (st.lines > 0) "● " else "○ ") + "Display Radio Subtitles ticked — BMS: SETUP → SIMULATION (kept per pilot)",
            color = Hud.TextDim, fontSize = 12.sp,
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Always show the Radio page", color = Hud.Text, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text("Otherwise it shows by itself while BMS writes a debug log.", color = Hud.TextDim, fontSize = 12.sp)
            }
            Switch(checked = RadioTabPrefs.showAlways, onCheckedChange = { RadioTabPrefs.showAlways = it })
        }
        Spacer(Modifier.height(10.dp))
        val c = st.cleanup
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Delete old debug logs", color = Hud.Text, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text(
                    "BMS's own *_xlog.txt and *_xlog_*.csv in its logs folder, to the Recycle Bin, at program start and when a new log " +
                        "starts. Never the newest session's, never crash dumps or anything else.",
                    color = Hud.TextDim, fontSize = 12.sp,
                )
            }
            Switch(checked = c.on, onCheckedChange = { on ->
                scope.launch { said = MissionLink.radioCleanup("?on=" + (if (on) "1" else "0")).error }
            })
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth().horizontalScrollCompat(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Keep the last", color = Hud.TextDim, fontSize = 12.sp)
            for ((n, days) in listOf(3 to false, 5 to false, 10 to false, 7 to true, 30 to true)) {
                HudChip(if (days) "$n days" else "$n sessions", c.keep == n && c.byDays == days) {
                    scope.launch { said = MissionLink.radioCleanup("?keep=$n&days=" + (if (days) "1" else "0")).error }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        val mb = (c.bytes / 1024.0 / 1024.0)
        Text(
            "Delete old logs now (${c.files} files, ${((mb * 10).toLong() / 10.0)} MB)",
            Modifier.clip(RoundedCornerShape(10.dp)).background(if (c.files > 0) Hud.Amber else Hud.Surface2)
                .clickable(enabled = c.files > 0) { scope.launch { said = MissionLink.radioCleanup("", now = true).let { a -> a.error ?: a.value?.cleanup?.error } } }
                .padding(horizontal = 14.dp, vertical = 8.dp),
            color = if (c.files > 0) Hud.Bg else Hud.TextDim, fontWeight = FontWeight.Bold, fontSize = 13.sp,
        )
        if (c.lastRun > 0) Text("Last clean-up: ${c.lastDeleted} files, ${(c.lastBytes / 1024 / 1024)} MB to the Recycle Bin.", color = Hud.TextDim, fontSize = 12.sp)
        (said ?: c.error)?.let { Text(it, color = Hud.Red, fontSize = 12.sp) }
    }
}
