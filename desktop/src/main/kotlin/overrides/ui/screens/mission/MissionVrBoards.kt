package com.bmscompanion.app.ui.screens.mission

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bmscompanion.app.data.mission.BoardConfig
import com.bmscompanion.app.data.mission.BoardSlot
import com.bmscompanion.app.data.mission.MissionLink
import com.bmscompanion.app.ui.Kneeboard
import com.bmscompanion.app.ui.board.BoardKind
import com.bmscompanion.app.ui.components.MapLook
import com.bmscompanion.app.ui.components.SectionCard
import com.bmscompanion.app.ui.theme.Hud
import com.bmscompanion.app.ui.theme.LocalExtra
import com.bmscompanion.desktop.PcConfig
import com.bmscompanion.desktop.SystemTools
import com.bmscompanion.desktop.serverPort
import kotlinx.coroutines.launch

/**
 * Opens one board in a browser window of its own, shaped like the board.
 *
 * A tab among the pilot's other tabs tells them nothing: a board is a tall sheet, and what a preview is for is
 * seeing whether the page fills it. The window is as tall as the screen sensibly allows and as wide as the chosen
 * board shape makes it. A browser that will not take a window of its own opens a tab, which is still a preview.
 */
private fun previewBoard(slot: Int) {
    val shape = Kneeboard.shapes[Kneeboard.shape]
    val screen = runCatching { java.awt.Toolkit.getDefaultToolkit().screenSize.height }.getOrDefault(1080)
    val h = (screen - 180).coerceIn(600, 1200)
    val w = (h.toLong() * shape.w / shape.h).toInt()
    SystemTools.openUrlInWindow("http://127.0.0.1:${serverPort()}/kneeboard/$slot", w, h)
}

/**
 * Setting up the boards a pilot flies with, on the PC, because the cockpit is no place to set anything up.
 *
 * Each row here is one OpenKneeboard tab. The row says what that board shows and hands over the address to paste into
 * OpenKneeboard; the board itself has no controls, which is the point — in a headset there is nothing to press with.
 * OpenKneeboard supports graphics tablets and nothing else, so everything a finger would have done is decided here
 * and the pilot's own next/previous page binding does the rest.
 *
 * The configuration lives on the PC (bridge-settings.json), so it survives a browser cache, a new headset and a
 * reinstall, and every board agrees about the light and the print size.
 */
@Composable
fun MissionVrBoardsPane() {
    val scope = rememberCoroutineScope()
    var cfg by remember { mutableStateOf<BoardConfig?>(null) }
    var saved by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { cfg = MissionLink.boards() ?: BoardConfig() }

    fun push(next: BoardConfig, note: String? = null) {
        cfg = next
        saved = note
        scope.launch { MissionLink.saveBoards(next) }
    }

    val port = serverPort()
    val address = remember(port) { SystemTools.lanAddresses().firstOrNull()?.let { "http://$it:$port" } }
    val c = cfg
    val boards = c?.slots?.size ?: 0

    // A section of the Kneeboards page now, not a page of its own: the scroll belongs to the page around it, and a
    // second one inside it would be an error rather than a nicety.
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Step 1. Nothing else here works until the headset PC can reach this one, so it is the first thing
        // asked and the first thing fixed — with the switch here rather than a sentence pointing at another page.
        SectionCard("1 · This PC", accent = if (PcConfig.webEnabled && address != null) Hud.Green else Hud.Amber) {
            Ready(
                ok = PcConfig.webEnabled,
                okText = "Browser access is on: the boards can reach this PC.",
                fixText = "Browser access is off. The boards are web pages served by this PC, so nothing will load without it.",
                fix = "Turn browser access on" to { PcConfig.setWeb(true) },
            )
            Ready(
                ok = address != null,
                okText = "This PC is at $address on your network.",
                fixText = "No network address yet. A headset PC on the same network needs one; a single-PC setup can use http://127.0.0.1:$port.",
            )
            Ready(
                ok = boards > 0,
                okText = "$boards board${if (boards == 1) "" else "s"} set up — that is $boards tab${if (boards == 1) "" else "s"} to add in OpenKneeboard.",
                fixText = "No boards yet. Add one below.",
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PcButton("Preview board 1 here", Icons.Default.Refresh) { previewBoard(1) }
                if (address != null && boards > 0) PcButton("Copy every address", Icons.Default.ContentCopy) {
                    SystemTools.copyToClipboard((1..boards).joinToString("\n") { "$address/kneeboard/$it" })
                    saved = "Copied $boards addresses, one to a line."
                }
            }
            Text(
                "Preview opens a board in a window of its own, in the shape the headset will see it — worth doing " +
                    "once before putting the headset on.",
                fontSize = 12.sp, color = Hud.TextFaint, modifier = Modifier.padding(top = 4.dp),
            )
        }

        SectionCard("2 · What each board shows", accent = Hud.Amber) {
            if (c == null) { Text("Reading the configuration…", fontSize = 13.sp, color = Hud.TextDim); return@SectionCard }
            c.slots.sortedBy { it.n }.forEach { slot ->
                BoardRow(
                    slot = slot,
                    address = address,
                    onKind = { k -> push(c.copy(slots = c.slots.map { if (it.n == slot.n) it.copy(kind = k.id, options = emptyMap()) else it })) },
                    onOptions = { o -> push(c.copy(slots = c.slots.map { if (it.n == slot.n) it.copy(options = o) else it })) },
                    onRemove = { push(c.copy(slots = c.slots.filter { it.n != slot.n })) },
                )
                Spacer(Modifier.height(6.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                PcButton("Add a board", Icons.Default.Add) {
                    val n = (c.slots.maxOfOrNull { it.n } ?: 0) + 1
                    push(c.copy(slots = c.slots + BoardSlot(n, BoardKind.FLIGHT.id)))
                }
                PcButton("Rebuild pages", Icons.Default.Refresh) {
                    push(c.copy(rev = c.rev + 1), "Every board rebuilds its pages within a few seconds.")
                }
            }
            saved?.let { Spacer(Modifier.height(6.dp)); Text(it, fontSize = 12.sp, color = Hud.Green) }
        }

        SectionCard("3 · How the boards read", accent = Hud.Green) {
            if (c == null) return@SectionCard
            Text("LIGHT", style = LocalExtra.current.overline, color = Hud.TextFaint)
            Row(Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Choice("Daylight paper", !c.night) { push(c.copy(night = false)) }
                Choice("Night", c.night) { push(c.copy(night = true)) }
            }
            Text("Pages that are pictures — the map and the charts — are printed as they are either way.", fontSize = 12.sp, color = Hud.TextFaint)
            Spacer(Modifier.height(8.dp))
            Text("PRINT SIZE", style = LocalExtra.current.overline, color = Hud.TextFaint)
            Row(Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Kneeboard.textSizes.forEachIndexed { i, t -> Choice(t.label, c.print == i) { push(c.copy(print = i)) } }
            }
            Text("A board is drawn at its full resolution whichever of these you pick; this is how large that resolution is used.", fontSize = 12.sp, color = Hud.TextFaint)
        }

        SectionCard("4 · Adding them to OpenKneeboard", accent = Hud.Cyan) {
            Step(1, "In OpenKneeboard: Settings → Tabs → Add a tab → Web Dashboard.")
            Step(2, "Paste the address from the row you want, and give the tab that row's name. One tab per row: board 1 is one tab, board 2 is another.")
            Step(3, "Repeat for each row. The order of the tabs in OpenKneeboard is yours to choose.")
            Step(4, "Boards follow the mission on their own. After printing a new briefing, give them a few seconds; Rebuild pages above forces it.")
            Spacer(Modifier.height(6.dp))
            Text(
                "There is no cursor on a kneeboard in VR — OpenKneeboard says so itself: \"Mice are not supported in-game\". " +
                    "That is why these boards have no buttons, and why what they show is chosen here.",
                fontSize = 12.sp, color = Hud.TextFaint,
            )
        }

        SectionCard("5 · The four bindings that fly it", accent = Hud.Cyan) {
            Text(
                "OpenKneeboard: Settings → Input → your controller → Bindings. Four is all it takes, and without them " +
                    "a board with eight pages shows page one for the whole flight.",
                fontSize = 13.sp, color = Hud.TextDim,
            )
            Spacer(Modifier.height(8.dp))
            Bind("Next page", "Turns to the next sheet of the board you are looking at: the next chart, the next threat, the next section of the briefing.")
            Bind("Previous page", "Back a sheet.")
            Bind("Next tab", "Moves to the next board — from the map to the flight plan to the charts.")
            Bind("Previous tab", "Back a board.")
            Spacer(Modifier.height(6.dp))
            Text(
                "Two more are worth having: Toggle visibility, to put the board away in the merge, and Recentre, " +
                    "for when it has drifted. Bind them to a hat or to spare pinky-switch positions — anything you " +
                    "can find without looking, because you will be using them with the headset on.",
                fontSize = 12.sp, color = Hud.TextFaint,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "The page counter at the bottom of every board (\"3 / 12\") is there to tell you the next-page " +
                    "binding is working.",
                fontSize = 12.sp, color = Hud.TextFaint,
            )
        }
    }
}

/** One board: its number, what it shows, its address, and the settings that kind of page understands. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BoardRow(
    slot: BoardSlot,
    address: String?,
    onKind: (BoardKind) -> Unit,
    onOptions: (Map<String, String>) -> Unit,
    onRemove: () -> Unit,
) {
    val kind = BoardKind.of(slot.kind)
    var open by remember { mutableStateOf(false) }
    var tuning by remember { mutableStateOf(false) }
    var copied by remember(slot.n) { mutableStateOf(false) }
    val url = address?.let { "$it/kneeboard/${slot.n}" }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Hud.Surface2)
            .border(1.dp, Hud.Outline, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        // everything about a board on one line: which board, what it shows, and the three things you do with it.
        // It wraps rather than clipping, so a narrow window loses no buttons.
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box(
                Modifier.size(24.dp).clip(RoundedCornerShape(6.dp)).background(Hud.Amber.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) { Text("${slot.n}", color = Hud.Amber, fontWeight = FontWeight.Bold, fontSize = 12.sp) }
            Box {
                Row(
                    Modifier.clip(RoundedCornerShape(7.dp)).background(Hud.Surface3).clickable { open = true }
                        .padding(horizontal = 8.dp, vertical = 5.dp).widthIn(min = 150.dp, max = 220.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(kind.label, Modifier.weight(1f), color = Hud.Text, fontSize = 13.sp, maxLines = 1)
                    Icon(Icons.Default.ExpandMore, null, tint = Hud.TextDim, modifier = Modifier.size(16.dp))
                }
                DropdownMenu(open, onDismissRequest = { open = false }, modifier = Modifier.background(Hud.Surface)) {
                    BoardKind.entries.forEach { k ->
                        Column(
                            Modifier.fillMaxWidth().clickable { onKind(k); open = false }.padding(horizontal = 12.dp, vertical = 6.dp),
                        ) {
                            Text(k.label, color = if (k == kind) Hud.Amber else Hud.Text, fontSize = 13.sp)
                            Text(k.hint, color = Hud.TextFaint, fontSize = 11.sp)
                        }
                    }
                }
            }
            PcButton(if (copied) "Copied" else "Copy address", Icons.Default.ContentCopy) {
                url?.let { SystemTools.copyToClipboard(it); copied = true }
            }
            PcButton("Preview", Icons.Default.OpenInBrowser) { previewBoard(slot.n) }
            if (kind.configurable) PcButton(if (tuning) "Done" else "Configure", Icons.Default.Tune) { tuning = !tuning }
            PcButton("Remove", Icons.Default.Delete) { onRemove() }
        }
        Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(url ?: "/kneeboard/${slot.n}", style = LocalExtra.current.monoSmall, color = Hud.Cyan)
            Spacer(Modifier.width(10.dp))
            // what the next-page binding has to work with on this board
            Text(
                if (kind.pages > 1) "up to ${kind.pages} pages" else "one page",
                style = LocalExtra.current.monoSmall, color = Hud.TextFaint,
            )
        }
        if (tuning && kind == BoardKind.MAP) MapOptions(slot.options, onOptions)
    }
}

/** What the map board is told before the flight, because in the cockpit there is nothing to tell it with. */
@Composable
private fun MapOptions(options: Map<String, String>, onChange: (Map<String, String>) -> Unit) {
    fun set(key: String, value: String) = onChange(options + (key to value))
    fun flag(key: String, default: Boolean = true) = options[key]?.let { it == "1" } ?: default
    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("MAP STYLE", style = LocalExtra.current.overline, color = Hud.TextFaint)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MapLook.styles.forEach { (key, name) ->
                Choice(name, (options["style"] ?: MapLook.style) == key) { set("style", key) }
            }
        }
        Text("ZOOM", style = LocalExtra.current.overline, color = Hud.TextFaint)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Theater" to "1", "Wide" to "2", "Route" to "4", "Close" to "8", "Very close" to "16").forEach { (label, z) ->
                Choice(label, (options["zoom"] ?: "4") == z) { set("zoom", z) }
            }
        }
        Text("The board opens at this zoom and stays there, centred on your jet.", fontSize = 11.sp, color = Hud.TextFaint)
        Text("LAYERS", style = LocalExtra.current.overline, color = Hud.TextFaint)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("route" to "Route", "sams" to "SAMs and threat rings", "traffic" to "Traffic", "hostiles" to "Hostiles").forEach { (key, label) ->
                Choice(label, flag(key)) { set(key, if (flag(key)) "0" else "1") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("support" to "Tanker/AWACS tracks").forEach { (key, label) ->
                Choice(label, flag(key)) { set(key, if (flag(key)) "0" else "1") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("labels" to "Labels", "fields" to "Airfields", "borders" to "Borders").forEach { (key, label) ->
                Choice(label, flag(key)) { set(key, if (flag(key)) "0" else "1") }
            }
            Choice("Follow the jet", flag("follow")) { set("follow", if (flag("follow")) "0" else "1") }
        }
        Text("TOWNS", style = LocalExtra.current.overline, color = Hud.TextFaint)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MapLook.placeOptions.forEach { (i, name) ->
                Choice(name, (options["towns"] ?: MapLook.places.toString()) == i.toString()) { set("towns", i.toString()) }
            }
        }
    }
}

@Composable
private fun Choice(label: String, on: Boolean, onClick: () -> Unit) {
    Text(
        label,
        Modifier.clip(RoundedCornerShape(8.dp))
            .background(if (on) Hud.Amber.copy(alpha = 0.18f) else Hud.Surface3)
            .border(1.dp, if (on) Hud.Amber.copy(alpha = 0.6f) else Hud.Outline, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick).padding(horizontal = 9.dp, vertical = 5.dp),
        color = if (on) Hud.Amber else Hud.TextDim, fontSize = 12.sp,
    )
}

@Composable
private fun PcButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(9.dp)).background(Hud.Surface3).border(1.dp, Hud.Outline, RoundedCornerShape(9.dp))
            .clickable(onClick = onClick).padding(horizontal = 11.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Hud.Amber, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = Hud.Text, fontSize = 13.sp)
    }
}

@Composable
private fun PcIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit) {
    Box(
        Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).background(Hud.Surface3).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, description, tint = Hud.TextDim, modifier = Modifier.size(16.dp)) }
}

/** One thing that has to be true before any of this works, with the switch that makes it true. */
@Composable
private fun Ready(ok: Boolean, okText: String, fixText: String, fix: Pair<String, () -> Unit>? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier.padding(top = 2.dp).size(9.dp).clip(RoundedCornerShape(5.dp)).background(if (ok) Hud.Green else Hud.Amber),
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(if (ok) okText else fixText, fontSize = 13.sp, color = if (ok) Hud.Text else Hud.Amber, lineHeight = 17.sp)
            if (!ok && fix != null) {
                Spacer(Modifier.height(4.dp))
                PcButton(fix.first, Icons.Default.Tune) { fix.second() }
            }
        }
    }
}

/** One OpenKneeboard binding and what it does to these boards. */
@Composable
private fun Bind(name: String, what: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
        Text(
            name,
            Modifier.width(112.dp).clip(RoundedCornerShape(6.dp)).background(Hud.Surface3)
                .padding(horizontal = 6.dp, vertical = 3.dp),
            color = Hud.Amber, fontSize = 12.sp, fontWeight = FontWeight.Bold,
            // the keys are a column of buttons: the label sits in the middle of its own, not against the left edge
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.width(8.dp))
        Text(what, color = Hud.TextDim, fontSize = 12.sp, lineHeight = 16.sp)
    }
}

@Composable
private fun Step(n: Int, text: String) {
    Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
        // the digit is centred on the circle, not on its line box: a text box is as tall as its line height, so
        // the number sat high in the disc until the line height was brought down to the size of the digit
        Box(
            Modifier.padding(top = 1.dp).size(20.dp).clip(RoundedCornerShape(10.dp)).background(Hud.Cyan.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "$n",
                color = Hud.Cyan,
                fontSize = 11.sp,
                lineHeight = 11.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                // centre the digit itself, not the line it sits on: a line box carries leading above and below the
                // glyph, and centring that put the number low in the disc
                style = LocalTextStyle.current.copy(
                    lineHeightStyle = LineHeightStyle(
                        alignment = LineHeightStyle.Alignment.Center,
                        trim = LineHeightStyle.Trim.Both,
                    ),
                ),
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(text, color = Hud.Text, fontSize = 13.sp, lineHeight = 18.sp)
    }
}
