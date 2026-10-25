package com.bmscompanion.app.ui.screens.mission

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.mission.MissionLink
import com.bmscompanion.app.data.mission.TargetCatalog
import com.bmscompanion.app.data.mission.TargetGroup
import com.bmscompanion.app.data.mission.TargetItem
import com.bmscompanion.app.data.mission.TargetPictureApi
import com.bmscompanion.app.data.mission.TargetSet
import com.bmscompanion.app.ui.components.SearchField
import com.bmscompanion.app.ui.components.CollapsibleCard
import com.bmscompanion.app.ui.components.SectionCard
import com.bmscompanion.app.ui.theme.Hud
import com.bmscompanion.app.ui.theme.LocalExtra
import kotlinx.serialization.KSerializer

// Pictures of the mission's targets, drawn by the PC from Falcon BMS's own 3-D models (docs/PROTOCOL.md, "Target
// pictures"): the Briefing's Target pictures card, the Threats page's pictures of one system, and the Add list. Every
// picture comes from the PC on request (`/api/targets/picture`) and only the ones on screen are kept here, decoded.

/** Fetching, decoding and keeping the pictures on this device; the look the pilot chose. */
object TargetPics {
    /** Targets added from the list, for one mission: "<mission>\n<key>\t<name>\t<category>" lines. */
    private const val ADDED_PREF = "tgt_added"
    const val WIDTH = 640

    /** The pictures always have the targeting-pod look (black and white): the plain renders were dropped in 1.4.0. */
    fun intel(): Boolean = true

    /** Percent-encodes a query value (no JVM encoder: the browser version shares this file). */
    fun enc(s: String): String = buildString {
        for (b in s.encodeToByteArray()) {
            val c = b.toInt() and 0xFF
            if (c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code || c in '0'.code..'9'.code || c == '-'.code || c == '_'.code || c == '.'.code || c == '~'.code) append(c.toChar())
            else { append('%'); append("0123456789ABCDEF"[c shr 4]); append("0123456789ABCDEF"[c and 15]) }
        }
    }

    fun picturePath(theater: String, key: String, view: String, intel: Boolean): String =
        "/api/targets/picture?theater=${enc(theater)}&key=${enc(key)}&view=$view&look=${if (intel) TargetPictureApi.LOOK_INTEL else TargetPictureApi.LOOK_PLAIN}&w=$WIDTH"

    // insertion order, a hit moved to the end by hand (Kotlin/Wasm's LinkedHashMap has no access order)
    private val cache = LinkedHashMap<String, ImageBitmap>()

    fun cached(path: String): ImageBitmap? = cache.remove(path)?.also { cache[path] = it }

    suspend fun picture(path: String): ImageBitmap? {
        cached(path)?.let { return it }
        val bytes = MissionLink.fetchBytes(path, timeoutMs = 30_000) ?: return null
        val img = Repo.decodeBitmap(bytes)?.asImageBitmap() ?: return null
        cache[path] = img
        while (cache.size > 48) cache.remove(cache.keys.first())
        return img
    }

    suspend fun <T> json(path: String, s: KSerializer<T>): T? =
        MissionLink.fetchBytes(path, timeoutMs = 30_000)?.let { runCatching { Repo.json.decodeFromString(s, it.decodeToString()) }.getOrNull() }

    fun added(mission: String): List<TargetItem> {
        val text = Repo.getString(ADDED_PREF) ?: return emptyList()
        val lines = text.split('\n')
        if (lines.firstOrNull() != mission) return emptyList()
        return lines.drop(1).mapNotNull { l -> l.split('\t').takeIf { it.size >= 2 }?.let { TargetItem(key = it[0], name = it[1], category = it.getOrNull(2), role = "added") } }
    }

    fun saveAdded(mission: String, items: List<TargetItem>) =
        Repo.putString(ADDED_PREF, (listOf(mission) + items.map { "${it.key}\t${it.name}\t${it.category.orEmpty()}" }).joinToString("\n"))

    /** The mission's own entries the pilot removed, for one mission: "<mission>\n<id>" lines ([groupId]). */
    private const val REMOVED_PREF = "tgt_removed"

    /** What names a group of the mission's set across launches: its kind and its title. */
    fun groupId(g: TargetGroup): String = g.kind + "|" + g.title

    fun removed(mission: String): List<String> {
        val lines = Repo.getString(REMOVED_PREF)?.split('\n') ?: return emptyList()
        return if (lines.firstOrNull() != mission) emptyList() else lines.drop(1).filter { it.isNotBlank() }
    }

    fun saveRemoved(mission: String, ids: List<String>) =
        Repo.putString(REMOVED_PREF, (listOf(mission) + ids.map { it.replace('\n', ' ') }).joinToString("\n"))
}

/** A red ✕ with a finger-sized target (40 dp) that removes one entry of the card at once. */
@Composable
private fun RemoveButton(what: String, onRemove: () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onRemove),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Default.Close, "Remove $what", tint = Hostile, modifier = Modifier.size(22.dp))
    }
}


/**
 * The Briefing's **Target pictures** card: the mission's targets (the flight's own enemy targets, then each threat
 * system as its battalion, then the spotted enemy ships) drawn from BMS's own models, two views each, and anything else
 * from the Add list. Every entry has a red ✕ that removes it at once; what was removed and what was added are kept per
 * mission on the device, and **Reload** forgets both and asks the PC for the mission's targets again. The PC draws the
 * first pictures in the background as soon as the list is asked for, so they are there when a group is opened.
 */
@Composable
fun TargetPicturesCard() {
    val data by MissionLink.mission.collectAsState()
    val missionKey = data?.missionKey ?: ""
    var reload by remember { mutableStateOf(0) }
    val set by produceState<TargetSet?>(null, missionKey, reload) {
        value = null
        value = TargetPics.json("/api/targets/mission", TargetSet.serializer()) ?: TargetSet(error = "The BMS PC did not answer (an older BMS Companion draws no target pictures).")
    }
    val intel = TargetPics.intel()
    // read again when another device removed or added one (MissionSync: PrefWatch.stamp moves)
    val synced = com.bmscompanion.app.data.mission.PrefWatch.stamp
    val added = remember(missionKey, synced) { mutableStateListOf<TargetItem>().also { it.addAll(TargetPics.added(missionKey)) } }
    val removed = remember(missionKey, synced) { mutableStateListOf<String>().also { it.addAll(TargetPics.removed(missionKey)) } }
    var adding by remember { mutableStateOf(false) }
    SectionCard("Target pictures", accent = Hostile, trailing = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.clip(RoundedCornerShape(6.dp)).clickable { adding = true }.padding(horizontal = 6.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Add, "Add a target", tint = Hud.Cyan, modifier = Modifier.size(16.dp))
                Text("Add", fontSize = 12.sp, color = Hud.Cyan, fontWeight = FontWeight.SemiBold)
            }
        }
    }) {
        val s = set
        when {
            s == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), color = Hud.Amber, strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Finding the mission's targets…", fontSize = 12.sp, color = Hud.TextDim)
            }
            else -> {
                Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Drawn from Falcon BMS's own 3-D models: what the targeting pod will show.", fontSize = 11.sp, color = Hud.TextFaint,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    // Reload: forget what was added and removed for this mission and ask for its targets again
                    Row(
                        Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(8.dp)).border(1.dp, Hud.Outline, RoundedCornerShape(8.dp))
                            .clickable {
                                added.clear(); TargetPics.saveAdded(missionKey, emptyList())
                                removed.clear(); TargetPics.saveRemoved(missionKey, emptyList())
                                reload++
                            }.padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.Refresh, null, tint = Hud.Cyan, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Reload", fontSize = 12.sp, color = Hud.Cyan, fontWeight = FontWeight.SemiBold)
                    }
                }
                s.error?.let { Text(it, fontSize = 12.sp, color = Hud.TextDim) }
                val shown = s.groups.filter { TargetPics.groupId(it) !in removed }
                if (s.error == null && shown.isEmpty() && added.isEmpty()) Text(
                    if (s.groups.isEmpty()) "The briefing names no enemy target or threat that has a model."
                    else "Every entry was removed. Reload brings the mission's targets back.",
                    fontSize = 12.sp, color = Hud.TextDim,
                )
                shown.forEachIndexed { i, g ->
                    TargetGroupView(s.theater, g, intel, initiallyOpen = i == 0, onRemove = {
                        removed += TargetPics.groupId(g); TargetPics.saveRemoved(missionKey, removed.toList())
                    })
                }
                added.toList().forEach { item ->
                    TargetGroupView(
                        s.theater, TargetGroup(title = item.name, kind = "added", note = item.category, items = listOf(item)), intel, initiallyOpen = true,
                        onRemove = { added.remove(item); TargetPics.saveAdded(missionKey, added.toList()) },
                    )
                }
            }
        }
    }
    if (adding) TargetCatalogDialog(set?.theater.orEmpty(), onDismiss = { adding = false }) { item ->
        if (added.none { it.key == item.key }) { added += item.copy(role = "added"); TargetPics.saveAdded(missionKey, added.toList()) }
        adding = false
    }
}

/**
 * One group (a battalion, the flight's target, a ship, a target added from the list), folding; its pictures load only
 * while it is open. [onRemove] puts a red ✕ at the end of its heading.
 */
@Composable
fun TargetGroupView(theater: String, g: TargetGroup, intel: Boolean, initiallyOpen: Boolean, onRemove: (() -> Unit)? = null) {
    var open by remember(g.title, g.kind) { mutableStateOf(initiallyOpen) }
    val tint = when (g.kind) { "target" -> Hud.Amber; "threat" -> Hostile; "ship" -> Hud.Cyan; else -> Hud.TextDim }
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).heightIn(min = 40.dp).clip(RoundedCornerShape(6.dp)).clickable { open = !open }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    when (g.kind) { "target" -> "TARGET"; "threat" -> "THREAT"; "ship" -> "SHIP"; else -> "ADDED" },
                    Modifier.border(1.dp, tint, RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
                    fontSize = 10.sp, fontWeight = FontWeight.Bold, color = tint,
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(g.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Hud.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val sub = listOfNotNull(g.note, "${g.items.size} " + if (g.items.size == 1) "picture pair" else "kinds").joinToString(" · ")
                    Text(sub, fontSize = 11.sp, color = Hud.TextDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = Hud.TextDim)
            }
            if (onRemove != null) {
                Spacer(Modifier.width(4.dp))
                RemoveButton(g.title, onRemove)
            }
        }
        if (open) g.items.forEach { TargetItemView(theater, it, intel) }
    }
}

/** One vehicle or building: its name, its part, and the two views side by side. */
@Composable
fun TargetItemView(theater: String, item: TargetItem, intel: Boolean) {
    var big by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(item.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Hud.Text, modifier = Modifier.weight(1f, fill = false))
            val sub = listOfNotNull(item.role?.takeIf { it != "added" }, item.count?.takeIf { it > 1 }?.let { "×$it" }, item.category.takeIf { item.role == "added" }).joinToString(" · ")
            if (sub.isNotEmpty()) Text("  $sub", fontSize = 11.sp, color = Hud.TextDim, maxLines = 1)
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((view, label) in TargetPictureApi.VIEWS) {
                val path = TargetPics.picturePath(theater, item.key, view, intel)
                Column(Modifier.weight(1f)) {
                    TargetPicture(path, Modifier.fillMaxWidth().clickable { big = path })
                    Text(label, fontSize = 10.sp, color = Hud.TextFaint, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
    }
    big?.let { p ->
        Dialog(onDismissRequest = { big = null }) {
            Surface(color = Color.Black, shape = RoundedCornerShape(8.dp)) {
                Column(Modifier.widthIn(max = 1000.dp).clickable { big = null }.padding(8.dp)) {
                    Text(item.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 6.dp))
                    TargetPicture(p, Modifier.fillMaxWidth())
                }
            }
        }
    }
}

/** One picture from the PC (8:5), a quiet placeholder while it is drawn, a line when it cannot be. */
@Composable
fun TargetPicture(path: String, modifier: Modifier = Modifier) {
    // produceState keeps its last value when the key changes, so a new path must not trust it: the picture of the
    // previous path stayed when another threat was opened (1.4.0 test build)
    val state by produceState<Pair<ImageBitmap?, Boolean>>(TargetPics.cached(path) to false, path) {
        TargetPics.cached(path)?.let { value = it to false; return@produceState }
        value = null to false
        value = TargetPics.picture(path) to true
    }
    Box(
        modifier.aspectRatio(8f / 5f).clip(RoundedCornerShape(6.dp)).background(Color(0xFF1E2227)),
        contentAlignment = Alignment.Center,
    ) {
        val img = state.first
        when {
            img != null -> Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            state.second -> Text("No picture", fontSize = 11.sp, color = Hud.TextFaint)
            else -> CircularProgressIndicator(Modifier.size(18.dp), color = Hud.TextFaint, strokeWidth = 2.dp)
        }
    }
}

/** The Add list: every ground target of the theater by category, searchable; a tap adds it. */
@Composable
fun TargetCatalogDialog(theater: String, onDismiss: () -> Unit, onPick: (TargetItem) -> Unit) {
    val catalog by produceState<TargetCatalog?>(null, theater) {
        value = TargetPics.json("/api/targets/catalog" + if (theater.isNotBlank()) "?theater=${TargetPics.enc(theater)}" else "", TargetCatalog.serializer())
            ?: TargetCatalog(error = "The BMS PC did not answer.")
    }
    var query by remember { mutableStateOf("") }
    var open by remember { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(color = Hud.Surface, shape = RoundedCornerShape(12.dp), modifier = Modifier.widthIn(max = 560.dp).heightIn(max = 640.dp)) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Add a target", style = LocalExtra.current.overline, color = Hud.Amber, modifier = Modifier.weight(1f))
                    Icon(Icons.Default.Close, "Close", tint = Hud.TextDim, modifier = Modifier.size(22.dp).clip(RoundedCornerShape(4.dp)).clickable(onClick = onDismiss))
                }
                Spacer(Modifier.height(8.dp))
                SearchField(query, { query = it }, "Search vehicles, ships, buildings")
                Spacer(Modifier.height(6.dp))
                val c = catalog
                if (c == null) CircularProgressIndicator(Modifier.size(18.dp), color = Hud.Amber, strokeWidth = 2.dp)
                else if (c.error != null) Text(c.error, fontSize = 12.sp, color = Hud.TextDim)
                else {
                    val q = query.trim().lowercase()
                    LazyColumn(Modifier.fillMaxWidth()) {
                        for (cat in c.categories) {
                            val items = if (q.isEmpty()) cat.items else cat.items.filter { it.name.lowercase().contains(q) }
                            if (items.isEmpty()) continue
                            val shown = q.isNotEmpty() || open == cat.name
                            item(key = "h-" + cat.name) {
                                Row(
                                    Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable { open = if (open == cat.name) null else cat.name }.padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(cat.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Hud.Text, modifier = Modifier.weight(1f))
                                    Text("${items.size}", fontSize = 11.sp, color = Hud.TextDim)
                                    Icon(if (shown) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = Hud.TextDim)
                                }
                            }
                            if (shown) items(items, key = { cat.name + "|" + it.key }) { it2 ->
                                Text(
                                    it2.name,
                                    Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable { onPick(it2) }.padding(start = 12.dp, top = 7.dp, bottom = 7.dp),
                                    fontSize = 13.sp, color = Hud.TextDim,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The Threats page's pictures of one system: its battalion as BMS's unit table has it (the launcher, its radars and
 * the vehicles that go with it), drawn by the PC from BMS's own models. [names] are tried in order (the TacRef name,
 * the guide's name, its aliases). Says so in a line when the PC is not linked.
 */
@Composable
fun ThreatModelPictures(names: List<String>) {
    val q = names.filter { it.isNotBlank() }.distinct()
    val intel = TargetPics.intel()
    // folded until opened (the fold is kept per device); nothing is asked of the PC while it is folded
    CollapsibleCard("In-game pictures", initiallyOpen = false, accent = Hostile, rememberKey = "threat_pictures") {
        // null while asking; a group with no items and no note when the PC did not answer
        val group by produceState<TargetGroup?>(null, q) {
            value = null
            if (q.isEmpty()) return@produceState
            value = TargetPics.json("/api/targets/system?name=${TargetPics.enc(q.joinToString("|"))}", TargetGroup.serializer())
                ?: TargetGroup(note = "Link to the BMS PC (Setup) to see this system drawn from Falcon BMS's own 3-D models.")
        }
        val g = group
        when {
            g == null -> CircularProgressIndicator(Modifier.size(16.dp), color = Hud.Amber, strokeWidth = 2.dp)
            g.items.isEmpty() -> Text(g.note ?: "Falcon BMS's tables have no model for this system.", fontSize = 12.sp, color = Hud.TextDim)
            else -> {
                Text(
                    "Drawn from Falcon BMS's own 3-D models: the battalion as BMS fields it.", fontSize = 11.sp, color = Hud.TextFaint,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                g.items.forEach { TargetItemView("", it, intel) }
            }
        }
    }
}
