package com.bmscompanion.app.ui.screens.editor

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.Theater
import com.bmscompanion.app.data.mission.LinkState
import com.bmscompanion.app.data.mission.MissionLink
import com.bmscompanion.app.data.mission.WeatherState
import com.bmscompanion.app.data.mission.WxTheater
import com.bmscompanion.app.data.weather.WxGenParams
import com.bmscompanion.app.ui.screens.mission.MissionEnv
import com.bmscompanion.app.ui.theme.Hud
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Choosing the weather Falcon BMS flies, on the theater map it flies over.
 *
 * The page is WeatherGen's generator (`WeatherGen.kt`): a whole theater of weather from a seed and a handful of
 * settings, with fronts, winds that follow the pressure, override regions and time — saved as a map of its own beside
 * BMS's, or as a series BMS's Maps Auto Update loads through the mission. Above it there is one thing only: which
 * theater. BMS's four ready-made maps (Sunny, Fair, Poor, Inclement — maps in its Map Model list, not its weather
 * models, which are Probabilistic, Deterministic and Map Model) are never written from here; the exceptions are
 * putting them back when an earlier version of the app changed them, and taking a new copy of them when something
 * else did, such as a BMS update ([StockChangedLine]).
 *
 * Everything is written through the PC's `WeatherStore`, under the reversible-write rules: nothing until the theater
 * is backed up and enabled, BMS's originals copied once beside them, and every change undone by a Restore. Nothing
 * written here reaches a mission until the pilot picks the map under Map Model in BMS and saves the weather (the
 * Save panel says how).
 */
@Composable
fun WeatherEditorPane(env: MissionEnv) {
    val link by MissionLink.state.collectAsState()
    val online = link is LinkState.Online

    var state by remember { mutableStateOf<WeatherState?>(null) }
    var theaterId by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var asked by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }

    LaunchedEffect(online, refresh, theaterId) {
        if (!online) return@LaunchedEffect
        // not the maps themselves: the generator never reads BMS's own grids, only whether they are BMS's own
        val s = MissionLink.weatherState(theaterId)
        state = s
        asked = true
        // a theater listed only to say why it cannot be written is never the one a page opens on
        if (theaterId == null) theaterId = s?.current ?: s?.theaters?.let { all -> all.firstOrNull { it.blocked == null } ?: all.firstOrNull() }?.id
    }

    val s = state?.takeIf { online }
    val theater = s?.theaters?.firstOrNull { it.id == theaterId } ?: s?.theaters?.firstOrNull()

    // The map under the weather is the map of the theater being **edited**, which is not necessarily the one Falcon
    // BMS is running: a pilot sets tomorrow's Balkans weather while today's Korea mission is loaded. With no PC to
    // ask, it is the theater the app is set to, and the generator still previews over it.
    // (kept with the theater it was worked out for: for a moment after a switch it is still the last theater's map)
    val mapFor by produceState<Pair<String?, Theater?>>(null to env.theater, theater?.id, env.theater) {
        value = theater?.id to (
            theater?.let { mapTheaterFor(it) } ?: env.theater
                ?: Repo.index().theaters.let { all -> all.firstOrNull { it.id == Repo.selectedTheater.value } ?: all.firstOrNull() }
            )
    }
    val mapTheater = mapFor.second

    // **Each theater has its own weather.** The generator's settings are kept per theater — under the PC's name for
    // it when there is a PC, else the bundled map's — and switching theater swaps in that theater's own, which is what
    // makes the grid and its symbols change with the map. Until the PC has said which theaters it has, the state
    // waits rather than opening under the bundled map's name and being thrown away a moment later.
    val genKey = theater?.id ?: mapTheater?.id?.takeIf { !online || (asked && theater == null) }
    // settings made with no PC connected are under the bundled map's name: found too, once that map is this theater's
    val alsoKey = mapTheater?.id?.takeIf { theater != null && mapFor.first == theater.id }
    val gen = remember(genKey, alsoKey) { WxGenState.open(genKey, alsoKey) }

    // A theater this device has kept nothing for opens with what the PC last saved for it, when the PC kept the
    // settings its generated map was made from — so the weather already flying in that theater is the weather shown.
    LaunchedEffect(gen, theater?.id, online) {
        val id = theater?.id ?: return@LaunchedEffect
        if (!online || gen.origin !is WxOrigin.Fresh) return@LaunchedEffect
        val fresh = gen.params
        val (name, p) = pcSavedParams(id, null) ?: return@LaunchedEffect
        // only if the pilot has not started on the fresh one in the meantime
        if (gen.params == fresh && gen.origin is WxOrigin.Fresh) {
            gen.params = p
            gen.origin = WxOrigin.Pc(name)
        }
    }

    // Why there is nowhere to save, when the PC is there but has no theater to save to. With no PC at all the Save
    // panel says so itself.
    val noSave = when {
        !online -> null
        !asked -> "Reading the PC…"
        s == null -> "The PC did not report any weather maps. Update BMS Companion on the PC that runs Falcon BMS. " +
            "The preview works without it."
        !s.available || theater == null -> (s.error ?: "No theater in this Falcon BMS install keeps weather maps of its own.") +
            " The preview works without it."
        else -> null
    }

    WeatherPage(
        s = s, theater = theater, mapTheater = mapTheater, online = online, noSave = noSave, busy = busy, gen = gen,
        onPick = { theaterId = it },
        onBusy = { busy = it },
        onState = { r -> if (r != null) state = r },
        reload = { refresh++ },
    )
}

/**
 * The page itself, with nothing of its own to fetch: the theater selector, the undo line when BMS's own maps are not
 * BMS's own, and the generator. Split from [WeatherEditorPane] so `--wxrender` can draw exactly what a pilot sees
 * from a made-up PC answer.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WeatherPage(
    s: WeatherState?,
    theater: WxTheater?,
    mapTheater: Theater?,
    online: Boolean,
    noSave: String?,
    busy: Boolean,
    gen: WxGenState,
    onPick: (String) -> Unit,
    onBusy: (Boolean) -> Unit,
    onState: (WeatherState?) -> Unit,
    reload: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    // what the last Restore said, for this theater only
    var restoreNote by remember(theater?.id) { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        // a flowing row: beside the picker on a wide screen, under it on a phone, where the picker takes the width
        FlowRow(
            Modifier.fillMaxWidth().heightIn(min = 50.dp).background(Hud.Surface.copy(alpha = 0.55f))
                .padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (s != null && s.theaters.isNotEmpty() && theater != null) {
                Text("THEATER", Modifier.align(Alignment.CenterVertically), color = Hud.TextFaint, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                TheaterPicker(s, theater.id, onPick)
            } else if (mapTheater != null) {
                Text("THEATER", Modifier.align(Alignment.CenterVertically), color = Hud.TextFaint, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text(
                    mapTheater.name, Modifier.align(Alignment.CenterVertically),
                    color = Hud.TextDim, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                )
            }
            // whose weather this is: a switch of theater has to be seen to switch the weather
            if (gen.key != null && (theater != null || mapTheater != null)) {
                Text(
                    originText(gen.origin),
                    Modifier.align(Alignment.CenterVertically).padding(start = 3.dp).wxProbe("origin"),
                    color = Hud.TextFaint, fontSize = 11.sp, lineHeight = 13.sp,
                )
            }
        }
        Rule(0.6f)

        if (online && theater != null && theater.models.any { it.edited || it.differs }) {
            // one press at a time, and what it answered said on the line
            fun act(calls: suspend () -> WeatherState?) {
                onBusy(true)
                scope.launch {
                    val r = calls()
                    restoreNote = when {
                        r == null -> "The PC did not answer. Check the connection and try again."
                        r.error != null -> r.error
                        else -> null
                    }
                    onState(r)
                    onBusy(false)
                }
            }
            StockChangedLine(
                theater, busy, restoreNote,
                // only the maps this app wrote: one that BMS changed since would otherwise get its older copy back
                onRestore = {
                    act {
                        var r: WeatherState? = null
                        for (m in theater.models.filter { it.edited }) {
                            r = MissionLink.weatherRestore(theater.id, m.id)
                            if (r == null || r.error != null) break
                        }
                        r
                    }
                },
                onRefresh = { act { MissionLink.weatherRefreshBackup(theater.id) } },
            )
            Rule(0.4f)
        }

        WeatherGenBody(
            th = mapTheater, wx = theater, online = online, busy = busy, error = s?.error, noSave = noSave,
            onBusy = onBusy, onState = onState, reload = reload, st = gen,
        )
    }
}

/**
 * The undo for BMS's four ready-made maps, and the answer when something else changed them.
 *
 * An earlier version of this page edited Sunny, Fair, Poor and Inclement in place. This one does not, but a pilot who
 * used it still has those edits in their install, and every write this app makes must stay undoable. So when the PC
 * reports a map this app wrote ([WxModel.edited]: it differs from the copy taken before the first write, and the
 * record beside that copy says the app wrote it), one line says so, and its button puts those maps back byte for byte
 * from that copy.
 *
 * A map that differs from its copy **without** such a record was changed by something else — most likely a BMS update
 * shipping new maps ([WxModel.differs]). Restore would put the older map back over BMS's new one, so that line offers
 * to take a new copy instead (the earlier one is kept). While every map matches its copy, nothing is shown.
 */
@Composable
private fun StockChangedLine(t: WxTheater, busy: Boolean, note: String?, onRestore: () -> Unit, onRefresh: () -> Unit) {
    val ours = t.models.filter { it.edited }
    val other = t.models.filter { it.differs && !it.edited }
    Column(Modifier.fillMaxWidth().background(Hud.Amber.copy(alpha = 0.07f)).padding(horizontal = 14.dp, vertical = 6.dp)) {
        if (ours.isNotEmpty()) {
            ChangedRow(
                if (ours.size == 1) "BMS's ready-made weather map ${ours[0].name} was changed by an earlier version of this app."
                else "BMS's ready-made weather maps (${ours.joinToString(", ") { it.name }}) were changed by an earlier version of this app.",
                null, if (busy) "Restoring…" else "Restore them", busy, onRestore,
            )
        }
        if (other.isNotEmpty()) {
            val names = other.joinToString(", ") { it.name }
            val taken = other.mapNotNull { it.copiedAt }.minOrNull()?.let { " taken on " + SimpleDateFormat("d MMM yyyy", Locale.US).format(Date(it)) }.orEmpty()
            ChangedRow(
                "$names ${if (other.size == 1) "differs" else "differ"} from the copy$taken (a BMS update?). This app did not write " +
                    (if (other.size == 1) "it" else "them") + ", so Restore would put an older map back.",
                "Refresh the backup: the maps as they are now become the copy; the earlier one is kept in Older copies.",
                if (busy) "Copying…" else "Refresh the backup", busy, onRefresh,
            )
        }
        note?.let { Text(it, color = Hud.Red, fontSize = 11.5.sp, lineHeight = 14.sp) }
    }
}

/** One line of [StockChangedLine]: the words take what the button leaves, so on a phone they wrap beside it. */
@Composable
private fun ChangedRow(text: String, sub: String?, button: String, busy: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(text, color = Hud.TextDim, fontSize = 12.sp, lineHeight = 15.sp)
            sub?.let { Text(it, color = Hud.TextFaint, fontSize = 11.sp, lineHeight = 14.sp) }
        }
        Spacer(Modifier.width(12.dp))
        EditorChip(button, enabled = !busy, onClick = onClick)
    }
}

/**
 * The bundled theater whose map goes under a weather map.
 *
 * Both sides name a theater after the same folder — BMS Companion's index from the theater definitions, the weather
 * store from `Data\Add-On <name>` — so the two slugs usually match outright. They do not always: "Korea (the base
 * theater)" is the weather store's own name for plain `Data`, and punctuation lands differently ("OFMKTO" against
 * "OFM KTO"). So the match is tried three ways, letters and digits only, and anything Korean falls back to KTO —
 * which is right for every add-on campaign that flies over Korea's terrain.
 */
private suspend fun mapTheaterFor(wx: WxTheater): Theater? {
    val all = Repo.index().theaters
    fun key(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
    val want = key(wx.name)
    all.firstOrNull { key(it.id) == key(wx.id) }?.let { return it }
    all.firstOrNull { key(it.name) == want }?.let { return it }
    all.firstOrNull { key(it.name).startsWith(want) || want.startsWith(key(it.name)) }?.let { return it }
    if ("korea" in want || "kto" in want) return all.firstOrNull { it.id == "korea-kto" }
    return null
}

/** The line beside the theater picker: where this theater's weather came from. */
internal fun originText(o: WxOrigin): String = when (o) {
    WxOrigin.Own -> "This theater's own weather settings"
    is WxOrigin.Pc -> "As saved on the PC: ${o.map}"
    is WxOrigin.Fresh -> "Nothing saved for this theater yet: a weather of its own (seed ${o.seed})"
}

/**
 * What the PC saved for a theater: the settings its generated map [name] was made from — or, with no name, the map
 * it wrote last — with that map's name. Null when the PC kept none (a map an older version wrote), has no such map,
 * or is a version that does not offer them.
 */
internal suspend fun pcSavedParams(theater: String, name: String?): Pair<String, WxGenParams>? {
    val path = "/api/weather/params?theater=" + urlEnc(theater) + (name?.let { "&name=" + urlEnc(it) } ?: "")
    val text = MissionLink.fetchBytes(path, 8_000)?.decodeToString() ?: return null
    val saved = runCatching { Repo.json.decodeFromString(WxGenSaved.serializer(), text) }.getOrNull() ?: return null
    return saved.name to saved.params
}

/** Percent-encoding for a query value, in plain Kotlin so it is the same on the phone, the PC and in a browser. */
internal fun urlEnc(s: String): String = buildString {
    for (b in s.encodeToByteArray()) {
        val c = b.toInt() and 0xFF
        if (c.toChar().isLetterOrDigit() && c < 0x80 || c.toChar() in "-_.~") append(c.toChar())
        else append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 15])
    }
}

// ---------------------------------------------------------------- the rest

/**
 * Which theater is being edited.
 *
 * A dropdown rather than a row of chips: an install with every add-on offers a dozen, and chips would wrap onto
 * several lines above the map. A dropdown is one line whatever is installed. The one Falcon BMS is running is marked,
 * since it is the one a pilot almost always wants.
 */
@Composable
private fun TheaterPicker(s: WeatherState, chosen: String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val here = s.theaters.firstOrNull { it.id == chosen }
    Box {
        Row(
            Modifier.wxProbe("theater").clip(RoundedCornerShape(4.dp))
                .background(Hud.Surface)
                .border(1.dp, Hud.Amber.copy(alpha = 0.8f), RoundedCornerShape(4.dp))
                .clickable { open = true }
                .padding(horizontal = 13.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(here?.name ?: "Choose", color = Hud.Amber, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            if (here != null && here.id == s.current) {
                Spacer(Modifier.width(8.dp))
                Text("running", color = Hud.Cyan, fontSize = 10.5.sp, maxLines = 1)
            }
            Spacer(Modifier.width(6.dp))
            // an icon, not "▾": the browser version's font has no such glyph and drew an empty box
            Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = Hud.Amber, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            for (t in s.theaters) {
                DropdownMenuItem(
                    text = {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    t.name,
                                    color = when {
                                        t.id == chosen -> Hud.Amber
                                        t.blocked != null -> Hud.TextFaint
                                        else -> Hud.Text
                                    },
                                    fontSize = 13.sp,
                                    fontWeight = if (t.id == chosen) FontWeight.Bold else FontWeight.Normal,
                                )
                                if (t.id == s.current) {
                                    Spacer(Modifier.width(10.dp))
                                    Text("running", color = Hud.Cyan, fontSize = 10.5.sp)
                                }
                            }
                            // listed rather than left out, so a pilot looking for it learns why it cannot be written
                            t.blocked?.let { Text(it, color = Hud.TextFaint, fontSize = 10.5.sp, lineHeight = 13.sp) }
                        }
                    },
                    onClick = { open = false; onPick(t.id) },
                    modifier = Modifier.wxProbe("theater-" + t.id),
                )
            }
        }
    }
}

@Composable
internal fun Middle(title: String, text: String) {
    Box(Modifier.fillMaxSize().heightIn(min = 160.dp).padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, color = Hud.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            if (text.isNotEmpty()) Text(text, color = Hud.TextDim, fontSize = 12.sp)
        }
    }
}
