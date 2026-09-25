package com.bmscompanion.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.bmscompanion.app.data.CfgCatalog
import com.bmscompanion.app.data.CfgOption
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.mission.CfgFile
import com.bmscompanion.app.data.mission.CfgProfiles
import com.bmscompanion.app.data.mission.CfgState
import com.bmscompanion.app.data.mission.LinkState
import com.bmscompanion.app.data.mission.MissionLink
import com.bmscompanion.app.ui.Routes
import com.bmscompanion.app.ui.components.BmsTopBar
import com.bmscompanion.app.ui.components.HudChip
import com.bmscompanion.app.ui.components.SearchField
import com.bmscompanion.app.ui.components.SectionCard
import com.bmscompanion.app.ui.go
import com.bmscompanion.app.ui.theme.Hud
import com.bmscompanion.app.ui.theme.LocalExtra
import kotlinx.coroutines.launch

/**
 * Config: Falcon BMS's own settings files, edited from wherever the pilot is sitting.
 *
 * BMS keeps several hundred settings in `User/Config/Falcon BMS User.cfg` — and a second file for VR — that its
 * own setup screens never show. Changing one means finding the line, knowing what it does and knowing what it was
 * before. This page lists every option the version has, says what each does, and writes only the ones actually
 * changed, because BMS's stock `Falcon BMS.cfg` already holds every default: a User cfg that says nothing about a
 * setting is a setting at its default.
 *
 * Everything here happens on the BMS PC, so this works the same from a tablet on the desk.
 *
 * Three things keep it safe to hand to somebody who has never opened one of these files:
 *
 * - **Nothing is offered until a copy is taken.** The page is a single button until `Falcon BMS User.cfg` has been
 *   copied into `User/Config/BackUp`, and that copy is taken once and never replaced.
 * - **Three profiles, not one file.** The profiles live in the backup folder; selecting one copies it onto the
 *   file BMS reads. Profile 1 starts as whatever the pilot already had.
 * - **The list says what is in the file.** Settings the file actually holds are listed first and marked; the rest
 *   are shown at their default, and changing one moves it up into the first list, which is exactly what it does in
 *   the file.
 */

/** Which of the two files: what BMS reads on a monitor, and what it reads in a headset. */
private val KINDS = listOf("user" to "Falcon BMS User.cfg", "vr" to "Falcon BMS VR.cfg")

private fun CfgState.of(kind: String): CfgProfiles = if (kind == "vr") vr else user
private fun CfgState.backedUp(kind: String): Boolean = if (kind == "vr") vrBackedUp else userBackedUp

/**
 * Which options a file is offered.
 *
 * The VR file is read instead of the User one when BMS starts in a headset, and it may hold anything — but a list
 * of 219 settings is not a thing to hunt through for the dozen that matter in a headset, so it offers the VR
 * options and the graphics ones that decide whether a headset keeps up. The User file leaves the VR-only options
 * out, since setting one there does nothing.
 *
 * Either way, a line the file already holds is always shown: whatever put it there, it is in force, and a setting
 * a pilot can see but not find is worse than a long list.
 */
/**
 * A setting the catalogue does not know — one this BMS version does not list, or one a tool wrote — still gets
 * the right control, from the same naming BMS uses everywhere: g_b… a switch, g_n… a whole number, g_f… a decimal,
 * g_s… text. It was showing a switch as a text box with "1" in it.
 */
private fun unknownOption(key: String, value: String) = CfgOption(
    k = key,
    d = value,
    kind = when (key.getOrNull(2)) {
        'b' -> "toggle"
        'n' -> "int"
        'f' -> "float"
        else -> "text"
    },
    g = "In the file",
    t = "",
)

private fun offeredIn(kind: String, o: CfgOption): Boolean = when (kind) {
    "vr" -> o.vr == 1 || o.g == "Graphics" || o.g == "Terrain"
    else -> o.vr == 0
}

@Composable
private fun KeepLinked() {
    DisposableEffect(Unit) {
        MissionLink.acquire()
        onDispose { MissionLink.release() }
    }
}

@Composable
fun ConfigScreen(nav: NavHostController) {
    KeepLinked()
    val link by MissionLink.state.collectAsState()
    val online = link is LinkState.Online
    val scope = rememberCoroutineScope()
    val catalog by produceState(CfgCatalog()) { value = Repo.cfgOptions() }

    var state by remember { mutableStateOf<CfgState?>(null) }
    var file by remember { mutableStateOf<CfgFile?>(null) }
    var kind by rememberSaveable { mutableStateOf("user") }
    var profile by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    // Whether the PC has been asked yet, so "no answer" can be told from "not asked". A PC still on an older
    // version has no Config at all, and the page has to say so rather than spin for ever.
    var asked by remember { mutableStateOf(false) }

    /**
     * What there is, and then what is in it — in **one** pass.
     *
     * This used to be three effects: one asked the PC what profiles there were, a second worked out which one was
     * in use, and a third read that profile's lines. Each ran on its own composition, so opening the page left a
     * pass where the PC had answered but no profile had been settled on yet — and the list, with nothing in it,
     * said the profile was empty. It was not; picking another profile and coming back was enough to make it read
     * properly, which is exactly the shape of a race.
     *
     * Now the state and the file are fetched by the same coroutine, so the page never has one without the other in
     * flight. Which profile to read is settled here too, from the one Falcon BMS is actually reading, and it is
     * written back **last** — writing it earlier would invalidate this effect's own keys and cancel the read.
     */
    LaunchedEffect(online, refresh, kind, profile) {
        if (!online) return@LaunchedEffect
        val s = MissionLink.cfgState()
        state = s
        asked = true
        if (s == null) { file = null; return@LaunchedEffect }
        val p = profile.takeIf { it > 0 } ?: s.of(kind).selected
        file = if (s.backedUp(kind)) MissionLink.cfgLines(kind, p) else null
        if (profile != p) profile = p
    }

    val inFile = file?.lines.orEmpty()
    val values = remember(inFile) { inFile.associate { it.key to it.value } }

    /**
     * Takes the copy that unlocks the page.
     *
     * Also the way the VR file gets one: it only exists after BMS has been started in a headset, which can be long
     * after the first backup, and this takes a copy of whatever has no copy yet.
     */
    val backUp: () -> Unit = {
        busy = true
        scope.launch { MissionLink.cfgBackUp()?.let { state = it }; busy = false }
    }

    /** Sends one change and keeps the list in step with what the PC says the file now holds. */
    val apply: (String, String?) -> Unit = { key, value ->
        val p = profile
        busy = true
        scope.launch {
            val r = MissionLink.cfgSet(kind, p, key, value)
            if (r != null) file = r else note = "The PC did not answer; nothing was changed."
            busy = false
        }
    }

    Column(Modifier.fillMaxSize().background(Hud.Bg)) {
        BmsTopBar(
            "Config",
            state?.configDir ?: "Falcon BMS settings on the PC",
            actions = {
                IconButton({ refresh++ }) {
                    if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = Hud.Amber, strokeWidth = 2.dp)
                    else Icon(Icons.Default.Refresh, "Refresh", tint = Hud.TextDim)
                }
            },
        )
        val s = state
        when {
            MissionLink.host == null && !online ->
                ConfigEmpty("Connect to the BMS PC", "These are Falcon BMS's own settings files, read and written on the PC that runs it. Set the connection up first.", "Go to Setup") { nav.go(Routes.SETUP) }
            !online ->
                ConfigEmpty("Waiting for the PC", "BMS Companion is not answering on the PC. Start it there and this page fills in by itself.")
            s == null && !asked -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Hud.Amber) }
            s == null -> ConfigEmpty(
                "The PC needs updating",
                "BMS Companion on the BMS PC did not answer about Falcon BMS's settings. Config arrived in 1.3.7: update the PC program and this page fills itself in.",
                "Try again",
            ) { refresh++ }
            !s.available ->
                ConfigEmpty("No Falcon BMS install", "The PC does not know where Falcon BMS is installed, so there is no config file to edit. Set the BMS folder on the PC's settings page.")
            !s.userBackedUp -> ConfigLocked(s, busy, backUp)
            else -> ConfigList(
                state = s, kind = kind, profile = profile, file = file, catalog = catalog, values = values,
                query = query, busy = busy, note = note,
                onKind = { k -> kind = k; profile = s.of(k).selected },
                onProfile = { profile = it },
                onQuery = { query = it },
                onNote = { note = it },
                onSet = apply,
                onBackUp = backUp,
                onSelect = {
                    busy = true
                    scope.launch { MissionLink.cfgSelect(kind, profile)?.let { state = it }; busy = false; note = "Profile $profile is the one Falcon BMS now reads." }
                },
                onCopyFrom = { from ->
                    busy = true
                    scope.launch {
                        val r = MissionLink.cfgCopy(kind, from, profile)
                        if (r != null) { file = r; state = MissionLink.cfgState(); note = "Profile $profile now holds profile $from's settings." }
                        busy = false
                    }
                },
                onRestore = {
                    busy = true
                    scope.launch {
                        val r = MissionLink.cfgRestore(kind, profile)
                        if (r != null) { file = r; note = "Profile $profile is back to the file that was there before any of this." }
                        busy = false
                    }
                },
            )
        }
    }
}

/** The page before the copy is taken: one button, and the reason it is the only thing here. */
@Composable
private fun ConfigLocked(state: CfgState, busy: Boolean, onBackUp: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Warning()
        SectionCard("What this does", accent = Hud.Cyan) {
            Text(
                "Falcon BMS keeps several hundred settings in a text file its own screens never show. This page lists " +
                    "them, says what each one does, and writes only the ones you change.",
                color = Hud.TextDim, fontSize = 13.sp,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "Before anything is offered, your file is copied to a BackUp folder beside it. That copy is taken once " +
                    "and never replaced, so there is always a way back to exactly what you have now. Three profiles are " +
                    "laid down at the same time: profile 1 is a copy of what you already have, 2 and 3 start empty, which " +
                    "means every setting at its Falcon BMS default.",
                color = Hud.TextDim, fontSize = 13.sp,
            )
            state.configDir?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, color = Hud.TextFaint, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            }
        }
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                .background(if (busy) Hud.Surface2 else Hud.Amber)
                .clickable(enabled = !busy, onClick = onBackUp)
                .padding(vertical = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(22.dp), color = Hud.Amber, strokeWidth = 2.dp)
            else Text("Click to Back Up and Enable", color = Hud.Bg, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
        // If the copy could not be taken — almost always Windows refusing to write where BMS is installed — the
        // reason goes here, where the button is, rather than the button simply doing nothing.
        state.error?.let {
            Text(it, color = Hud.Red, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
    }
}

/** The red line. It says the same thing in both places a pilot can meet this page. */
@Composable
private fun Warning() {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(Hud.Red.copy(alpha = 0.12f))
            .border(1.dp, Hud.Red.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Warning, null, tint = Hud.Red, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            "Don't use this unless you know what you are doing. These are Falcon BMS's own settings, and a wrong one " +
                "can stop BMS starting. Your original file is kept in BackUp.",
            color = Hud.Red, fontSize = 12.sp, fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun ConfigEmpty(title: String, text: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Hud.Text)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = Hud.TextDim, textAlign = TextAlign.Center)
            if (action != null && onAction != null) {
                Text(
                    action,
                    Modifier.clip(RoundedCornerShape(10.dp)).background(Hud.Amber).clickable(onClick = onAction)
                        .padding(horizontal = 16.dp, vertical = 9.dp),
                    color = Hud.Bg, fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

/**
 * The page once it is unlocked: a header that stays put, and the settings under it.
 *
 * Laid out as a fixed top and one scrolling list, not as one long list, for two reasons a pilot feels immediately.
 * The **search box never scrolls away**, so narrowing three hundred settings is always one tap off. And the list
 * keeps its place: every row is keyed by its setting's own name, the same key whether it is in the file or not, so
 * changing a value — which moves the row from one group to the other — leaves the list exactly where it was
 * instead of throwing the pilot back to the top of the page mid-edit.
 *
 * The group titles stick under the search box while their own settings are on screen, which is the only thing that
 * says what you are looking at once "Campaign & AI" has scrolled past.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConfigList(
    state: CfgState,
    kind: String,
    profile: Int,
    file: CfgFile?,
    catalog: CfgCatalog,
    values: Map<String, String>,
    query: String,
    busy: Boolean,
    note: String?,
    onKind: (String) -> Unit,
    onProfile: (Int) -> Unit,
    onQuery: (String) -> Unit,
    onNote: (String?) -> Unit,
    onSet: (String, String?) -> Unit,
    onBackUp: () -> Unit,
    onSelect: () -> Unit,
    onCopyFrom: (Int) -> Unit,
    onRestore: () -> Unit,
) {
    val profiles = state.of(kind)
    val live = profiles.selected == profile
    // A file with no copy of its own has nothing to offer yet: the VR file appears the first time BMS is started in
    // a headset, which can be months after the User file was copied.
    val ready = state.backedUp(kind)
    val known = remember(catalog) { catalog.options.associateBy { it.k } }
    val everyOption = remember(catalog) { catalog.options.distinctBy { it.k } }

    // Everything the file holds, first and in the order the file holds it — including keys this version's
    // catalogue does not know, which are shown as plain text rather than quietly dropped. One row per setting,
    // whatever the file does: a list with the same key twice cannot be drawn, and BMS reads the last line anyway.
    val lines = file?.lines.orEmpty().filter { !it.launcher }.asReversed().distinctBy { it.key }.asReversed()
    val held = remember(lines, known, query) {
        lines.map { l -> known[l.key] ?: unknownOption(l.key, l.value) }
            .filter { matches(it, query) }
    }
    val heldKeys = remember(lines) { lines.map { it.key }.toSet() }
    val groups = remember(everyOption, heldKeys, kind, query) {
        everyOption.filter { it.k !in heldKeys && offeredIn(kind, it) && matches(it, query) }
            .sortedWith(compareBy({ it.g }, { it.k }))
            .groupBy { it.g }
            .toList()
    }
    val restCount = groups.sumOf { it.second.size }
    // Narrowing the list is a new list: staying where you were would leave the one match above the fold. Changing a
    // value is not — that is what the stable row keys are for, and the list holds its place through it.
    val listState = rememberLazyListState()
    LaunchedEffect(query, kind, profile) { listState.scrollToItem(0) }
    val problem = file?.error ?: state.error

    Column(Modifier.fillMaxSize()) {
        HeaderBand(
            state = state, kind = kind, profile = profile, live = live, busy = busy, ready = ready,
            problem = problem, note = note,
            onKind = onKind, onProfile = onProfile, onBackUp = onBackUp, onNote = onNote,
            onSelect = onSelect, onCopyFrom = onCopyFrom, onRestore = onRestore,
        )
        if (!ready) return@Column
        // Outside the list on purpose: three hundred settings and a search box that scrolls away is a page you
        // have to scroll back up to use.
        Box(Modifier.background(Hud.Bg).padding(horizontal = 14.dp, vertical = 6.dp)) {
            SearchField(query, onQuery, "Search settings: name or what it does")
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 28.dp),
        ) {
            stickyHeader(key = "h-in") {
                GroupHead(
                    "In this file",
                    when {
                        file == null -> "…"
                        lines.isEmpty() -> "none"
                        else -> "${held.size} of ${lines.size}"
                    },
                    Hud.Amber,
                )
            }
            if (held.isEmpty()) item(key = "in-empty") {
                Text(
                    when {
                        // Not loaded is not the same as empty. Saying "holds no settings" while the PC is still
                        // answering told the pilot their profile was blank when it was not.
                        file == null -> "Reading the file on the PC…"
                        lines.isEmpty() -> "This profile holds no settings at all, which means every setting is at its Falcon BMS default."
                        else -> "Nothing in this file matches the search."
                    },
                    color = Hud.TextFaint, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            // Keyed by the setting itself, not by which group it is in: that is what keeps the list still when a
            // value changes and the row moves up here.
            items(held, key = { it.k }) { o ->
                OptionRow(o, values[o.k], inFile = true, enabled = !busy) { v -> onSet(o.k, v) }
            }

            stickyHeader(key = "h-out") { GroupHead("Not in this file", "$restCount at their default", Hud.TextDim) }
            for ((group, options) in groups) {
                stickyHeader(key = "g-$group") { GroupHead(group, "${options.size}", Hud.Cyan) }
                items(options, key = { it.k }) { o ->
                    OptionRow(o, null, inFile = false, enabled = !busy) { v -> onSet(o.k, v) }
                }
            }
        }
    }
}

/**
 * The band above the list: the warning, which file, which profile, and anything the PC had to say.
 *
 * Kept to a few lines. It used to be a card of prose and a card of controls, which on a phone was most of the page
 * before a single setting appeared — and all of it is read once and then only glanced at afterwards.
 */
@Composable
private fun HeaderBand(
    state: CfgState,
    kind: String,
    profile: Int,
    live: Boolean,
    busy: Boolean,
    ready: Boolean,
    problem: String?,
    note: String?,
    onKind: (String) -> Unit,
    onProfile: (Int) -> Unit,
    onBackUp: () -> Unit,
    onNote: (String?) -> Unit,
    onSelect: () -> Unit,
    onCopyFrom: (Int) -> Unit,
    onRestore: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().background(Hud.Bg).padding(horizontal = 14.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Warning, null, tint = Hud.Red, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                "Falcon BMS's own settings. A wrong one can stop BMS starting — your original is in BackUp.",
                color = Hud.Red, fontSize = 11.sp, maxLines = 2,
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Two files and three profiles in one row of small chips. The one Falcon BMS actually reads carries a
            // dot, so which set is in force never needs a sentence of its own.
            CfgLabel("File")
            CfgChip("User", kind == "user") { onKind("user") }
            if (state.vrPresent) {
                Spacer(Modifier.width(5.dp))
                CfgChip("VR", kind == "vr") { onKind("vr") }
            }
            Spacer(Modifier.width(12.dp))
            Box(Modifier.size(width = 1.dp, height = 18.dp).background(Hud.Outline))
            Spacer(Modifier.width(12.dp))
            CfgLabel("Profile")
            (1..3).forEach { n ->
                CfgChip(if (state.of(kind).selected == n) "$n ●" else "$n", profile == n) { onProfile(n) }
                Spacer(Modifier.width(5.dp))
            }
            Spacer(Modifier.weight(1f))
            var open by remember { mutableStateOf(false) }
            Box {
                Text(
                    "⋯",
                    Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = !busy) { open = true }
                        .padding(horizontal = 10.dp, vertical = 2.dp),
                    color = Hud.TextDim, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                )
                DropdownMenu(open, onDismissRequest = { open = false }) {
                    if (!live) DropdownMenuItem(
                        text = { Text("Make profile $profile the one BMS reads") },
                        onClick = { open = false; onSelect() },
                    )
                    (1..3).filter { it != profile }.forEach { n ->
                        DropdownMenuItem(text = { Text("Copy profile $n into this one") }, onClick = { open = false; onCopyFrom(n) })
                    }
                    DropdownMenuItem(text = { Text("Restore this profile from the original backup") }, onClick = { open = false; onRestore() })
                }
            }
        }
        if (!live) {
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Editing profile $profile — Falcon BMS reads ${state.of(kind).selected}.", color = Hud.Amber, fontSize = 11.sp)
                Spacer(Modifier.width(8.dp))
                Text(
                    "Use this one",
                    Modifier.clip(RoundedCornerShape(6.dp)).background(Hud.Amber.copy(alpha = 0.18f))
                        .clickable(enabled = !busy, onClick = onSelect).padding(horizontal = 8.dp, vertical = 2.dp),
                    color = Hud.Amber, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                )
            }
        }
        // A file with no copy of its own: the VR one appears the first time BMS starts in a headset.
        if (!ready) {
            Spacer(Modifier.height(8.dp))
            Text(
                "This file has no copy yet — it appeared after the first backup, which is what happens the first time Falcon BMS starts in a headset.",
                color = Hud.TextDim, fontSize = 12.sp,
            )
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(if (busy) Hud.Surface2 else Hud.Amber)
                    .clickable(enabled = !busy, onClick = onBackUp)
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = Hud.Amber, strokeWidth = 2.dp)
                else Text("Click to Back Up and Enable", color = Hud.Bg, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
        }
        // Whatever the PC could not do, in its own words — never a page that quietly does nothing.
        problem?.let {
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Hud.Red.copy(alpha = 0.12f))
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Warning, null, tint = Hud.Red, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(7.dp))
                Text(it, color = Hud.Red, fontSize = 11.5.sp, modifier = Modifier.weight(1f))
            }
        }
        note?.let {
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Hud.Surface2)
                    .clickable { onNote(null) }.padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Check, null, tint = Hud.Green, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(7.dp))
                Text(it, color = Hud.TextDim, fontSize = 11.5.sp, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** What the chips beside it are for: a row of five unlabelled boxes says nothing about what it selects. */
@Composable
private fun CfgLabel(text: String) {
    Text("$text:", color = Hud.TextDim, fontSize = 12.sp, modifier = Modifier.padding(end = 7.dp))
}

/** A small chip: the file, or the profile. Smaller than [HudChip], because there are five of them in one row. */
@Composable
private fun CfgChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text,
        Modifier.clip(RoundedCornerShape(7.dp))
            .background(if (selected) Hud.Amber.copy(alpha = 0.18f) else Hud.Surface2)
            .border(1.dp, if (selected) Hud.Amber.copy(alpha = 0.7f) else Hud.Outline.copy(alpha = 0.6f), RoundedCornerShape(7.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 4.dp),
        color = if (selected) Hud.Amber else Hud.TextDim,
        fontSize = 12.sp,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
    )
}

private fun matches(o: CfgOption, q: String): Boolean {
    if (q.isBlank()) return true
    val n = q.trim().lowercase()
    return o.k.lowercase().contains(n) || o.t.lowercase().contains(n) || o.g.lowercase().contains(n)
}

@Composable
private fun GroupHead(title: String, count: String, accent: Color) {
    // Sticky, so it must be opaque: the settings scroll underneath it. Large enough to be the thing you see first
    // when you stop scrolling, because on a list of three hundred lines the group is what tells you where you are.
    Row(
        Modifier.fillMaxWidth().background(Hud.Bg).padding(top = 10.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(width = 4.dp, height = 18.dp).background(accent, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(9.dp))
        Text(
            title, color = accent, fontSize = 16.sp, fontWeight = FontWeight.Bold,
            letterSpacing = 0.2.sp, modifier = Modifier.weight(1f),
        )
        Text(count, color = Hud.TextFaint, fontSize = 11.sp)
    }
}

/**
 * One setting.
 *
 * [inFile] is the whole distinction the page is built on: a marked, full-strength row for a setting the file
 * actually carries, a dim one for a setting sitting at its default. Setting a value back to the default clears the
 * line rather than writing it, so the two lists always say the truth about the file.
 */
@Composable
private fun OptionRow(o: CfgOption, current: String?, inFile: Boolean, enabled: Boolean, onSet: (String?) -> Unit) {
    val value = current ?: o.d
    // the default is what BMS does anyway, so writing it is the same as saying nothing — and saying nothing is tidier
    val set: (String?) -> Unit = { v -> onSet(if (v == null || v.trim() == o.d.trim()) null else v.trim()) }
    // A list, not a stack of cards: three hundred settings in three hundred rounded panels is a page you cannot
    // read down. One hairline under each row, and the only thing that marks a setting the file carries is a bar
    // down its left edge in the accent colour.
    Row(
        Modifier.fillMaxWidth()
            .drawBehind {
                val line = Hud.Outline.copy(alpha = 0.45f)
                drawLine(line, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1f)
                if (inFile) drawRect(Hud.Amber.copy(alpha = 0.75f), size = Size(3.dp.toPx(), size.height))
            }
            .padding(start = if (inFile) 13.dp else 10.dp, end = 4.dp, top = 11.dp, bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            // The setting's name is what you search for and what a forum post quotes, so it is set in the monospace
            // the file itself uses. What it does is prose: lighter, smaller, and plainly a different thing.
            Text(
                o.k,
                color = if (inFile) Hud.Amber else Hud.Text,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.sp,
            )
            if (o.t.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(o.t, color = Hud.TextDim, fontSize = 11.5.sp, lineHeight = 15.sp)
            }
            if (inFile && current != null && current != o.d) {
                Spacer(Modifier.height(2.dp))
                Text("default ${o.d}", color = Hud.TextFaint, fontSize = 10.5.sp)
            }
        }
        Spacer(Modifier.width(10.dp))
        when {
            o.kind == "toggle" -> Switch(
                checked = value == "1",
                enabled = enabled,
                onCheckedChange = { on -> set(if (on) "1" else "0") },
                colors = SwitchDefaults.colors(checkedTrackColor = Hud.Amber, checkedThumbColor = Hud.Bg),
            )
            o.c.isNotEmpty() -> ChoiceField(o, value, enabled) { set(it) }
            else -> ValueField(value, o.d, enabled) { set(it) }
        }
    }
}

/** An option that takes one of a set of values: the values are BMS's, the words are ours. */
@Composable
private fun ChoiceField(o: CfgOption, value: String, enabled: Boolean, onSet: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val label = o.c.firstOrNull { it.v == value }?.t ?: value
    Box {
        Row(
            Modifier.clip(RoundedCornerShape(10.dp)).background(Hud.Surface2)
                .clickable(enabled = enabled) { open = true }
                .padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, color = Hud.Text, fontSize = 12.sp, maxLines = 1)
            Icon(Icons.Default.ArrowDropDown, null, tint = Hud.TextDim, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            o.c.forEach { c ->
                DropdownMenuItem(
                    text = { Text(if (c.v == o.d) "${c.t}  (default)" else c.t) },
                    onClick = { open = false; onSet(c.v) },
                )
            }
        }
    }
}

/**
 * A number, a string or a colour: typed in, and sent when the keyboard says done or the field is left.
 *
 * Typing is not saving here — a half-typed "0." would be written to the file on the way to "0.25" — so the value
 * goes to the PC once, when the line is finished.
 */
@Composable
private fun ValueField(value: String, default: String, enabled: Boolean, onSet: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    TextField(
        value = text,
        onValueChange = { text = it.replace("\n", "") },
        enabled = enabled,
        singleLine = true,
        modifier = Modifier.width(120.dp).onFocusChanged { if (!it.isFocused && text != value) onSet(text) },
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        placeholder = { Text(default, color = Hud.TextFaint, fontSize = 12.sp, maxLines = 1) },
        shape = RoundedCornerShape(10.dp),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onSet(text) }),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Hud.Surface2,
            unfocusedContainerColor = Hud.Surface2,
            disabledContainerColor = Hud.Surface2,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
            cursorColor = Hud.Cyan,
        ),
    )
}
