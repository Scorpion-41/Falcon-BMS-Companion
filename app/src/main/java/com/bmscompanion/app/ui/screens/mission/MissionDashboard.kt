package com.bmscompanion.app.ui.screens.mission

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Adjust
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.FlightLand
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.mission.Live
import com.bmscompanion.app.data.mission.MissionLink
import com.bmscompanion.app.ui.components.MapState
import com.bmscompanion.app.ui.components.SectionCard
import com.bmscompanion.app.ui.Kneeboard
import com.bmscompanion.app.ui.go
import com.bmscompanion.app.ui.theme.Hud
import com.bmscompanion.app.ui.theme.LocalExtra
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

// Dashboard: a Mission page the pilot builds from cards (map, RWR, DED, picture, fuel, comms, …).

/** Everything that can go on the dashboard. [tall] cards also have a height setting. */
enum class DashCard(val title: String, val desc: String, val icon: ImageVector, val tall: Boolean = false) {
    MAP("Live map", "Theater map with route, threat rings and the AWACS picture", Icons.Default.Map, tall = true),
    OWNSHIP("Ownship", "Altitude, speed, heading, fuel, bullseye, TACAN, UHF", Icons.Default.Flight),
    RWR("RWR", "Threat scope with launch and lock warnings", Icons.Default.Radar, tall = true),
    DED("DED", "Data entry display replica", Icons.Default.Terminal),
    PICTURE("Picture", "Hostile contacts, nearest first, with BRAA", Icons.Default.Adjust),
    FUEL("Fuel", "Fuel vs bingo, endurance, fuel needed to get home", Icons.Default.LocalGasStation),
    TIME("Time & TOT", "Zulu time and countdowns to take-off, push and TOT", Icons.Default.AccessTime),
    BULLSEYE("Bullseye", "Your bullseye position and the nearest bandit's", Icons.Default.Adjust),
    THREATS("Threat rings", "Pre-planned threats: range, and whether you are inside", Icons.Default.Warning),
    STEERPOINTS("Steerpoints", "Flight plan with bearing and range", Icons.Default.Route),
    MISSION("Mission", "Callsign, task, T/O, push and TOT", Icons.Default.Description),
    AIRBASES("Airbases", "Home, recovery and alternate: TACAN, tower, ILS", Icons.Default.FlightLand),
    COMMS("Comm ladder", "Frequencies with the tuned one highlighted", Icons.Default.Headset),
    SUPPORT("Tankers & support", "Tankers with TACAN and UHF, AWACS, JSTARS; airborne position from bullseye", Icons.Default.Hub),
    PRESETS("Radio presets", "DTC UHF/VHF presets", Icons.Default.Radio),
    BOARDS("Kneeboards", "Generate EZBoards kneeboards", Icons.Default.Assignment),
}

/** Card width relative to the dashboard: S = one column, M = two thirds, L = full width. */
enum class DashWidth(val label: String) { S("S"), M("M"), L("L") }

data class DashItem(val card: DashCard, val width: DashWidth = DashWidth.S, val height: Int = 1)

/** Saved layouts, one for narrow screens (phones) and one for wide ones (tablets, PC, browser on a desktop). */
object DashLayouts {
    private val states = HashMap<String, MutableState<List<DashItem>>>()

    val presets: List<Pair<String, List<DashItem>>> = listOf(
        "Pilot" to listOf(
            DashItem(DashCard.MAP, DashWidth.M, 1), DashItem(DashCard.OWNSHIP), DashItem(DashCard.RWR), DashItem(DashCard.PICTURE),
            DashItem(DashCard.FUEL), DashItem(DashCard.DED), DashItem(DashCard.STEERPOINTS),
        ),
        "Cockpit" to listOf(
            DashItem(DashCard.MAP, DashWidth.M, 2), DashItem(DashCard.RWR, DashWidth.S, 1), DashItem(DashCard.DED), DashItem(DashCard.FUEL),
            DashItem(DashCard.OWNSHIP, DashWidth.L),
        ),
        "Navigator" to listOf(
            DashItem(DashCard.MAP, DashWidth.M, 1), DashItem(DashCard.TIME), DashItem(DashCard.BULLSEYE), DashItem(DashCard.STEERPOINTS),
            DashItem(DashCard.AIRBASES), DashItem(DashCard.THREATS), DashItem(DashCard.COMMS),
        ),
        // what the Flight tab used to show, for anyone who lived on it
        "In flight" to listOf(
            DashItem(DashCard.OWNSHIP, DashWidth.L), DashItem(DashCard.RWR, DashWidth.S, 2), DashItem(DashCard.DED),
            DashItem(DashCard.PICTURE), DashItem(DashCard.FUEL),
        ),
        "Pre-flight" to listOf(
            DashItem(DashCard.MISSION), DashItem(DashCard.TIME), DashItem(DashCard.AIRBASES), DashItem(DashCard.COMMS, DashWidth.M),
            DashItem(DashCard.SUPPORT, DashWidth.M), DashItem(DashCard.PRESETS), DashItem(DashCard.BOARDS), DashItem(DashCard.STEERPOINTS),
        ),
    )

    fun state(key: String): MutableState<List<DashItem>> = states.getOrPut(key) {
        mutableStateOf(Repo.getString("dash_$key")?.let(::decode)?.takeIf { it.isNotEmpty() } ?: presets.first().second)
    }

    fun set(key: String, items: List<DashItem>) {
        state(key).value = items
        Repo.putString("dash_$key", encode(items))
    }

    private fun encode(items: List<DashItem>) = items.joinToString(",") { "${it.card.name}:${it.width.name}:${it.height}" }

    private fun decode(s: String): List<DashItem> = s.split(',').mapNotNull { part ->
        val f = part.split(':')
        val card = DashCard.entries.firstOrNull { it.name == f.getOrNull(0) } ?: return@mapNotNull null
        DashItem(card, DashWidth.entries.firstOrNull { it.name == f.getOrNull(1) } ?: DashWidth.S, f.getOrNull(2)?.toIntOrNull()?.coerceIn(0, 2) ?: 1)
    }.distinctBy { it.card }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MissionDashboardPane(env: MissionEnv, mapState: MapState, mapSel: MapSel?, onSel: (MapSel?) -> Unit, onOpenTab: (MissionTab) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // a VR board is a tall, narrow page: wider cards running down it read better than a third column with
        // nothing under it
        val tallBoard = Kneeboard.on && maxHeight > maxWidth * 1.3f
        val cols = when {
            maxWidth < 600.dp -> 1
            maxWidth < 1000.dp -> if (tallBoard) 1 else 2
            maxWidth < 1700.dp -> if (tallBoard) 2 else 3
            else -> if (tallBoard) 3 else 4
        }
        val key = if (cols == 1) "narrow" else "wide"
        val layout = DashLayouts.state(key)
        val items = layout.value
        var editing by remember { mutableStateOf(false) }
        val d = rememberMapData(env)
        fun update(list: List<DashItem>) = DashLayouts.set(key, list)

        if (Kneeboard.on) {
            BoardDashboard(items, env, d, mapState, mapSel, onSel, onOpenTab, tall = maxHeight > maxWidth * 1.15f, columns = cols, boardW = maxWidth, boardH = maxHeight)
            return@BoxWithConstraints
        }

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = if (Kneeboard.on) 6.dp else 12.dp, vertical = if (Kneeboard.on) 4.dp else 8.dp)) {
            // A kneeboard shows the cards and nothing else: the line above them is a row of board height spent on a
            // button nobody presses in the cockpit, and the newest log entry sits in that corner instead.
            //
            // Everywhere else it is a wide button across the middle of the page, saying what it does in full. A small
            // "Edit" in the corner is the standard place for it and was read by nobody: this page is the one thing in
            // the app a pilot builds himself, and it has to say so before he can discover it.
            if (!Kneeboard.on) Column(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                // Its width and its position do the inviting; the colour stays out of the way. A page a pilot reads
                // live has one thing on it that should catch the eye, and it is the flying, not a settings button.
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(if (editing) Hud.Amber.copy(alpha = 0.12f) else Hud.Surface2)
                        .border(1.dp, if (editing) Hud.Amber.copy(alpha = 0.6f) else Hud.Outline.copy(alpha = 0.8f), RoundedCornerShape(12.dp))
                        .clickable { editing = !editing }
                        .padding(vertical = 11.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (editing) Icons.Default.Check else Icons.Default.Edit, null,
                        tint = if (editing) Hud.Amber else Hud.TextDim, modifier = Modifier.size(17.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (editing) "Done customizing" else "Customize Dashboard",
                        color = if (editing) Hud.Amber else Hud.Text,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Text(
                    if (editing) "Move, resize or remove cards, then add more below" else "Your cards · ${items.size} · choose which ones, how wide and in what order",
                    style = MaterialTheme.typography.bodySmall, color = Hud.TextDim,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            if (items.isEmpty() && !editing) {
                PaneEmpty("No cards", "Press Customize Dashboard to add the cards you want on this page.", "Customize Dashboard") { editing = true }
            }
            SpanMasonry(
                spans = items.map { spanOf(it.width, cols) },
                columns = cols,
            ) {
                items.forEachIndexed { i, item ->
                    Column {
                        if (editing) EditBar(
                            item, cols,
                            onUp = if (i > 0) ({ update(items.toMutableList().also { l -> l.add(i - 1, l.removeAt(i)) }) }) else null,
                            onDown = if (i < items.lastIndex) ({ update(items.toMutableList().also { l -> l.add(i + 1, l.removeAt(i)) }) }) else null,
                            onWidth = { w -> update(items.toMutableList().also { l -> l[i] = item.copy(width = w) }) },
                            onHeight = { h -> update(items.toMutableList().also { l -> l[i] = item.copy(height = h) }) },
                            onRemove = { update(items.filterIndexed { j, _ -> j != i }) },
                        )
                        DashCardContent(item, env, d, mapState, mapSel, onSel, onOpenTab)
                    }
                }
            }
            if (editing) {
                Spacer(Modifier.height(12.dp))
                SectionCard("Add cards", accent = Hud.Green) {
                    val missing = DashCard.entries.filter { c -> items.none { it.card == c } }
                    if (missing.isEmpty()) Text("Every card is on the dashboard.", color = Hud.TextDim, fontSize = 13.sp)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        missing.forEach { c ->
                            Row(
                                Modifier.widthIn(max = 320.dp).clip(RoundedCornerShape(10.dp)).background(Hud.Surface2)
                                    .border(1.dp, Hud.Outline.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                                    .clickable { update(items + DashItem(c, if (c == DashCard.MAP) DashWidth.M else DashWidth.S, 1)) }.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Default.Add, null, tint = Hud.Green, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text(c.title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                    Text(c.desc, fontSize = 11.sp, color = Hud.TextDim, maxLines = 2)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Text("PRESETS", style = LocalExtra.current.overline, color = Hud.TextFaint)
                    Spacer(Modifier.height(6.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        DashLayouts.presets.forEach { (name, preset) -> SmallButton(name, null, primary = false) { update(preset) } }
                        SmallButton("Clear all", null, primary = false) { update(emptyList()) }
                    }
                }
            }
        }
    }
}

private fun spanOf(w: DashWidth, cols: Int) = when (w) {
    DashWidth.S -> 1
    DashWidth.M -> ceil(cols * 2 / 3.0).toInt().coerceIn(1, cols)
    DashWidth.L -> cols
}

/**
 * The Dashboard on a VR board.
 *
 * A window is as tall as the reading needs; a board is whatever shape the pilot strapped to their thigh, and on a 5:8
 * one the cards ran out halfway down and left the rest of the board blank. So the board is divided rather than
 * filled: the map takes the larger share and the cards scroll in the smaller one, which means every shape — 5:8,
 * 3:4, square, landscape — is used from edge to edge, whatever the data happens to be. A tall board splits top and
 * bottom (the map wants width, the cards are lines of text); a square or landscape one splits left and right.
 *
 * The map is drawn bare rather than in a card: a heading and a rule across the top of it is a line of board spent on
 * something the ☰ already says.
 */
@Composable
private fun BoardDashboard(
    items: List<DashItem>,
    env: MissionEnv,
    d: MapData,
    mapState: MapState,
    mapSel: MapSel?,
    onSel: (MapSel?) -> Unit,
    onOpenTab: (MissionTab) -> Unit,
    tall: Boolean,
    columns: Int,
    boardW: Dp,
    boardH: Dp,
) {
    val hasMap = items.any { it.card == DashCard.MAP } && env.theater != null
    val rest = items.filter { it.card != DashCard.MAP }

    @Composable
    fun Map(modifier: Modifier) = Box(modifier.clip(RoundedCornerShape(8.dp))) {
        LiveMap(env, d, mapState, mapSel, onSel, Modifier.fillMaxSize(), flightStrip = false, compactControls = true)
        if (mapSel != null) SelectionCard(env, mapSel, d, Modifier.align(Alignment.BottomStart).padding(start = 6.dp, end = 56.dp, bottom = 6.dp).widthIn(max = 380.dp).fillMaxWidth()) { onSel(null) }
    }

    @Composable
    fun Cards(modifier: Modifier) = Column(
        modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (columns > 1) SpanMasonry(spans = rest.map { spanOf(it.width, columns) }, columns = columns, spacing = 8.dp) {
            rest.forEach { item -> DashCardContent(item, env, d, mapState, mapSel, onSel, onOpenTab) }
        } else rest.forEach { item -> DashCardContent(item, env, d, mapState, mapSel, onSel, onOpenTab) }
    }

    val pad = Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 4.dp)
    when {
        !hasMap -> Cards(pad)
        rest.isEmpty() -> Map(pad)
        // the map gets the larger share, because it is the only thing that reads better the more board it is given
        // the cards take what they need up to about half the board and the map takes the rest, so the page is
        // full whether the jet is on the ramp with nothing to report or airborne with everything
        tall -> Column(pad, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Map(Modifier.fillMaxWidth().weight(1f))
            Cards(Modifier.fillMaxWidth().heightIn(max = boardH * 0.46f))
        }
        else -> Row(pad, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Map(Modifier.fillMaxHeight().weight(1f))
            Cards(Modifier.fillMaxHeight().widthIn(max = boardW * 0.42f))
        }
    }
}

/**
 * Masonry with column spans: each card goes to the lowest place where its span fits, so small cards
 * stack beside a wide map instead of leaving a gap under it. Order is kept as far as the layout allows.
 */
@Composable
private fun SpanMasonry(spans: List<Int>, columns: Int, spacing: Dp = 12.dp, content: @Composable () -> Unit) {
    Layout(content, Modifier.fillMaxWidth()) { measurables, constraints ->
        val gap = spacing.roundToPx()
        val width = constraints.maxWidth
        val colW = (width - gap * (columns - 1)) / columns
        val heights = IntArray(columns)
        val placed = measurables.mapIndexed { i, m ->
            val span = spans.getOrElse(i) { 1 }.coerceIn(1, columns)
            val w = colW * span + gap * (span - 1)
            val p = m.measure(Constraints.fixedWidth(w))
            // lowest start column; ties go left
            var bestCol = 0
            var bestY = Int.MAX_VALUE
            for (c in 0..columns - span) {
                val y = (c until c + span).maxOf { heights[it] }
                if (y < bestY) { bestY = y; bestCol = c }
            }
            for (c in bestCol until bestCol + span) heights[c] = bestY + p.height + gap
            Triple(p, bestCol, bestY)
        }
        val total = (heights.maxOrNull() ?: 0).let { if (it > 0) it - gap else 0 }
        layout(width, total) { placed.forEach { (p, c, y) -> p.placeRelative(c * (colW + gap), y) } }
    }
}

@Composable
private fun EditBar(item: DashItem, cols: Int, onUp: (() -> Unit)?, onDown: (() -> Unit)?, onWidth: (DashWidth) -> Unit, onHeight: (Int) -> Unit, onRemove: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 4.dp).clip(RoundedCornerShape(10.dp)).background(Hud.Amber.copy(alpha = 0.12f))
            .border(1.dp, Hud.Amber.copy(alpha = 0.5f), RoundedCornerShape(10.dp)).padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(item.card.title, color = Hud.Amber, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 4.dp))
        IconBox(Icons.Default.ArrowUpward, "Move up", onUp)
        IconBox(Icons.Default.ArrowDownward, "Move down", onDown)
        if (cols > 1) Segmented(DashWidth.entries.map { it.label }, item.width.ordinal) { onWidth(DashWidth.entries[it]) }
        if (item.card.tall) { Spacer(Modifier.width(4.dp)); Segmented(listOf("▁", "▃", "▅"), item.height) { onHeight(it) } }
        IconBox(Icons.Default.Close, "Remove", onRemove, tint = Hud.Red)
    }
}

@Composable
private fun IconBox(icon: ImageVector, desc: String, onClick: (() -> Unit)?, tint: Color = Hud.Text) {
    Box(
        Modifier.size(34.dp).clip(RoundedCornerShape(8.dp)).clickable(enabled = onClick != null) { onClick?.invoke() },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, desc, tint = if (onClick != null) tint else Hud.TextFaint, modifier = Modifier.size(18.dp)) }
}

/** Compact segmented control (e.g. S/M/L). */
@Composable
fun Segmented(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(8.dp)).border(1.dp, Hud.Outline, RoundedCornerShape(8.dp))) {
        labels.forEachIndexed { i, l ->
            Text(
                l, Modifier.background(if (i == selected) Hud.Amber else Color.Transparent).clickable { onSelect(i) }.padding(horizontal = 9.dp, vertical = 5.dp),
                color = if (i == selected) Hud.Bg else Hud.TextDim, fontSize = 12.sp, fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
fun SmallButton(text: String, icon: ImageVector?, primary: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(10.dp)).background(if (primary) Hud.Amber else Hud.Surface2)
            .border(1.dp, if (primary) Color.Transparent else Hud.Amber.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { Icon(icon, null, tint = if (primary) Hud.Bg else Hud.Amber, modifier = Modifier.size(15.dp)); Spacer(Modifier.width(6.dp)) }
        Text(text, color = if (primary) Hud.Bg else Hud.Amber, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

@Composable
private fun DashCardContent(item: DashItem, env: MissionEnv, d: MapData, mapState: MapState, mapSel: MapSel?, onSel: (MapSel?) -> Unit, onOpenTab: (MissionTab) -> Unit) {
    val mission by MissionLink.mission.collectAsState()
    val l = d.live
    when (item.card) {
        DashCard.MAP -> SectionCard("Live map", accent = Hud.Cyan, trailing = { SmallLink("Full map ›") { onOpenTab(MissionTab.MAP) } }) {
            val h = when (item.height) { 0 -> 300.dp; 2 -> 640.dp; else -> 440.dp }
            if (env.theater == null) Text("Connect to the BMS PC to load the theater.", color = Hud.TextDim, fontSize = 13.sp)
            else Box(Modifier.fillMaxWidth().height(h).clip(RoundedCornerShape(10.dp))) {
                LiveMap(env, d, mapState, mapSel, onSel, Modifier.fillMaxSize(), flightStrip = false, compactControls = true)
                if (mapSel != null) SelectionCard(env, mapSel, d, Modifier.align(Alignment.BottomStart).padding(start = 8.dp, end = 64.dp, bottom = 8.dp).widthIn(max = 440.dp).fillMaxWidth()) { onSel(null) }
            }
        }
        DashCard.OWNSHIP -> FlightTiles(l, d.bull, compact = item.width == DashWidth.S)
        DashCard.RWR -> SectionCard("RWR", accent = Hud.Green, trailing = { l?.let { Text("${it.rwr.size} emitters", fontSize = 11.sp, color = Hud.TextDim) } }) {
            if (l == null || !l.flying) { NotFlying(); return@SectionCard }
            val max = when (item.height) { 0 -> 220.dp; 2 -> 520.dp; else -> 340.dp }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { RwrScope(l, Modifier.widthIn(max = max).fillMaxWidth().aspectRatio(1f)) }
            if (item.height > 0) RwrList(l)
        }
        DashCard.DED -> SectionCard("DED", accent = Hud.Green) { if (l == null || !l.flying) NotFlying() else DedPanel(l) }
        DashCard.PICTURE -> PictureCard(d.ctcs, d.ownPos, d.ownHdg, d.bull, onPick = { c -> onSel(MapSel.Ctc(c.id)); mapState.flyTo(c.x, c.y, maxOf(mapState.scale, 4f)) })
        DashCard.FUEL -> FuelCard(l, env, d)
        DashCard.TIME -> TimeCard(l, mission)
        DashCard.BULLSEYE -> BullseyeCard(d)
        DashCard.THREATS -> ThreatRingsCard(d)
        DashCard.STEERPOINTS -> SteerpointList(d.stpts, d.ownPos, d.bull, selected = (mapSel as? MapSel.Stp)?.n) { s ->
            onSel(MapSel.Stp(s.n)); mapState.flyTo(s.x!!, s.y!!, maxOf(mapState.scale, 4f)); MapLayers.follow = false
        }.also { if (d.stpts.isEmpty()) EmptyCard(item.card, "No flight plan yet: PRINT the briefing or save the DTC.") }
        DashCard.MISSION -> mission?.briefing?.let { OverviewCard(it, l?.voice?.flight) } ?: EmptyCard(item.card, "Press PRINT on the BMS briefing screen.")
        DashCard.AIRBASES -> {
            val bases = airbases(l, mission?.briefing)
            if (bases.departure == null && bases.arrival == null && bases.alternate == null) EmptyCard(item.card, "Press PRINT on the BMS briefing screen.")
            else AirbasesCard(env, bases)
        }
        DashCard.COMMS -> mission?.briefing?.comms?.takeIf { it.isNotEmpty() }?.let { LadderCard(it, l?.uhfFreq ?: 0) } ?: EmptyCard(item.card, "Press PRINT on the BMS briefing screen.")
        DashCard.PRESETS -> mission?.dtc?.takeIf { it.uhf.isNotEmpty() || it.vhf.isNotEmpty() }?.let { PresetCard(it.uhf, it.vhf, l?.uhfPreset ?: 0) } ?: EmptyCard(item.card, "Save the DTC in BMS.")
        DashCard.BOARDS -> EzGenerateCard(onSetup = { env.nav.go(com.bmscompanion.app.ui.Routes.SETUP) })
        DashCard.SUPPORT -> SupportCard(rememberSupportAssets(env))
    }
}

@Composable
private fun EmptyCard(card: DashCard, text: String) {
    SectionCard(card.title, accent = Hud.TextDim) { Text(text, color = Hud.TextDim, fontSize = 13.sp) }
}

@Composable
private fun NotFlying() = Text("Not in 3D", color = Hud.TextDim, style = MaterialTheme.typography.bodySmall)

@Composable
fun SmallLink(text: String, onClick: () -> Unit) {
    Text(text, color = Hud.Amber, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 2.dp))
}

/** Big value with a small label, for glanceable cards. */
@Composable
fun BigStat(label: String, value: String, modifier: Modifier = Modifier, color: Color = Hud.Text, sub: String? = null) {
    Column(modifier.clip(RoundedCornerShape(10.dp)).background(Hud.Surface2).padding(horizontal = 10.dp, vertical = 8.dp)) {
        Text(label, fontSize = 10.sp, color = Hud.TextDim, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Text(value, style = LocalExtra.current.mono.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold), color = color, maxLines = 1)
        if (sub != null) Text(sub, fontSize = 11.sp, color = Hud.TextFaint, maxLines = 1)
    }
}

private fun hm(hours: Double): String {
    if (hours.isNaN() || hours.isInfinite() || hours < 0) return "—"
    val min = (hours * 60).roundToInt()
    return String.format(Locale.US, "%d:%02d", min / 60, min % 60)
}

@Composable
private fun FuelCard(l: Live?, env: MissionEnv, d: MapData) {
    val mission by MissionLink.mission.collectAsState()
    SectionCard("Fuel", accent = Hud.Amber) {
        if (l == null || !l.flying) { NotFlying(); return@SectionCard }
        val fuel = l.fuelTotal
        val low = l.bingo > 0 && fuel <= l.bingo
        val ff = l.fuelFlow.coerceAtLeast(1.0)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BigStat("TOTAL lb", "%,d".format(Locale.US, fuel.toInt()), Modifier.weight(1f), if (low) Hud.Red else Hud.Green)
            BigStat("OVER BINGO", if (l.bingo > 0) "%+,d".format(Locale.US, (fuel - l.bingo).toInt()) else "—", Modifier.weight(1f), if (low) Hud.Red else Hud.Text)
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BigStat("ENDURANCE", hm(fuel / ff), Modifier.weight(1f), sub = "at ${"%,d".format(Locale.US, ff.toInt())} pph")
            BigStat("TO BINGO", if (l.bingo > 0) hm((fuel - l.bingo) / ff) else "—", Modifier.weight(1f), if (low) Hud.Red else Hud.Text)
        }
        // fuel to get home at the current ground speed and fuel flow (a pessimistic estimate: cruise burns less)
        val home = matchAirport(env.set, airbases(l, mission?.briefing).arrival)
        if (home != null && l.gsKts > 50) {
            val dist = rangeNm(l.x, l.y, home.x, home.y)
            val need = dist / l.gsKts * ff
            Spacer(Modifier.height(8.dp))
            KV("Home", "${home.icao ?: home.name} · ${bra(l.x, l.y, home.x, home.y)} nm", mono = true, labelWidth = 60.dp)
            KV("Needed", "≈ ${"%,d".format(Locale.US, need.toInt())} lb (${hm(dist / l.gsKts)} at ${l.gsKts.toInt()} kt)", mono = true, labelWidth = 60.dp,
                color = if (fuel - need < l.bingo * 0.5) Hud.Red else Hud.Text)
        }
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { (fuel / maxOf(fuel, 7200.0 + l.fuelExternal)).toFloat().coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
            color = if (low) Hud.Red else Hud.Green, trackColor = Hud.Surface3,
        )
    }
}

/** "03:43:00z" → seconds of day. */
fun zuluSeconds(s: String?): Int? {
    val m = Regex("(\\d{1,2}):(\\d{2})(?::(\\d{2}))?").find(s ?: return null) ?: return null
    return m.groupValues[1].toInt() * 3600 + m.groupValues[2].toInt() * 60 + (m.groupValues[3].toIntOrNull() ?: 0)
}

@Composable
private fun TimeCard(l: Live?, mission: com.bmscompanion.app.data.mission.MissionData?) {
    val b = mission?.briefing
    val mine = b?.`package`?.firstOrNull { it.primary } ?: b?.`package`?.firstOrNull { it.callsign == l?.voice?.flight }
    SectionCard("Time & TOT", accent = Hud.Cyan) {
        val now = l?.timeSec?.takeIf { it > 0 }
        BigStat("ZULU", now?.let { zulu(it) } ?: "—", Modifier.fillMaxWidth(), Hud.Cyan)
        val events = listOfNotNull(
            mine?.takeoff?.let { "T/O" to it },
            mine?.push?.let { "PUSH" to it },
            (b?.overview?.tot ?: mine?.target)?.let { "TOT" to it },
        )
        if (events.isEmpty()) { Spacer(Modifier.height(6.dp)); Text("Press PRINT on the BMS briefing screen for T/O, push and TOT.", color = Hud.TextDim, fontSize = 12.sp) }
        events.forEach { (label, t) ->
            val sec = zuluSeconds(t)
            val delta = if (now != null && sec != null) ((sec - now + 43200).mod(86400)) - 43200 else null
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(label, Modifier.width(52.dp), fontSize = 12.sp, color = Hud.TextDim, fontWeight = FontWeight.SemiBold)
                Text(t.removeSuffix("z").removeSuffix("Z"), style = LocalExtra.current.mono, color = Hud.Amber, modifier = Modifier.weight(1f))
                if (delta != null) {
                    val abs = kotlin.math.abs(delta)
                    val txt = (if (delta >= 0) "in " else "+") + String.format(Locale.US, "%d:%02d", abs / 60, abs % 60)
                    Text(txt, style = LocalExtra.current.mono, color = when { delta < 0 -> Hud.TextFaint; delta < 120 -> Hud.Red; delta < 600 -> Hud.Amber; else -> Hud.Green })
                }
            }
        }
    }
}

@Composable
private fun BullseyeCard(d: MapData) {
    SectionCard("Bullseye", accent = Hud.Cyan) {
        val bull = d.bull
        val own = d.ownPos
        if (bull == null) { Text("No bullseye yet (in 3D, or from the AWACS feed).", color = Hud.TextDim, fontSize = 13.sp); return@SectionCard }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BigStat("YOU FROM BULLS", own?.let { bra(bull.first, bull.second, it.first, it.second) } ?: "—", Modifier.weight(1f), Hud.Cyan)
            BigStat("BULLS FROM YOU", own?.let { bra(it.first, it.second, bull.first, bull.second) } ?: "—", Modifier.weight(1f))
        }
        val nearest = d.ctcs.filter { it.hostile && it.kind in setOf("air", "heli") && !it.isCrew() }
            .minByOrNull { c -> own?.let { rangeNm(it.first, it.second, c.x, c.y) } ?: rangeNm(bull.first, bull.second, c.x, c.y) }
        if (nearest != null) {
            Spacer(Modifier.height(8.dp))
            Text("NEAREST BANDIT", style = LocalExtra.current.overline, color = Hud.TextFaint)
            Text(
                "${nearest.group ?: nearest.name ?: "Group"} · BULLS ${bra(bull.first, bull.second, nearest.x, nearest.y)} · ${flightLevel(nearest.altFt)}" +
                    (own?.let { " · BRAA ${bra(it.first, it.second, nearest.x, nearest.y)} ${aspect(nearest.hdg, nearest.x, nearest.y, it.first, it.second)}" } ?: ""),
                style = LocalExtra.current.monoSmall.copy(fontSize = 13.sp), color = Hostile,
            )
        }
    }
}

@Composable
private fun ThreatRingsCard(d: MapData) {
    SectionCard("Threat rings", accent = Hostile) {
        if (d.ppts.isEmpty()) { Text("No pre-planned threats: set PPTs in the DTC and save it.", color = Hud.TextDim, fontSize = 13.sp); return@SectionCard }
        val own = d.ownPos
        val rows = d.ppts.map { t -> t to own?.let { rangeNm(it.first, it.second, t.x, t.y) } }.sortedBy { (t, r) -> r?.minus(t.rangeNm) ?: 0.0 }
        rows.take(8).forEach { (t, r) ->
            val inside = r != null && r < t.rangeNm
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(t.name, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, color = if (inside) Hostile else Hud.Text, maxLines = 1)
                Text("%.0f nm ring".format(Locale.US, t.rangeNm), style = LocalExtra.current.monoSmall, color = Hud.TextDim)
                if (own != null && r != null) {
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (inside) "INSIDE" else "%.0f nm out".format(Locale.US, r - t.rangeNm),
                        style = LocalExtra.current.monoSmall.copy(fontWeight = FontWeight.Bold), color = if (inside) Hud.Red else if (r - t.rangeNm < 10) Hud.Amber else Hud.Green,
                    )
                }
            }
        }
    }
}
