package com.bmscompanion.app.ui.screens.wdp

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.mission.KbKind
import com.bmscompanion.app.data.mission.MissionLink
import com.bmscompanion.app.data.mission.TargetGroup
import com.bmscompanion.app.data.mission.TargetItem
import com.bmscompanion.app.data.mission.TargetPictureApi
import com.bmscompanion.app.data.mission.TargetSet
import com.bmscompanion.app.ui.screens.mission.TargetPics
import com.bmscompanion.app.ui.theme.Hud
import kotlinx.serialization.Serializable
import kotlin.math.min

/**
 * Upd Kneeboard's **Target pictures** page (1.4.0, [KbKind.TARGET_PICTURES]): the Briefing's Target pictures card on a
 * kneeboard half page — up to [PER_PAGE] targets, each a row with its name, its part and how many, and the two views
 * (front right, rear left) the PC draws from Falcon BMS's own 3-D models, in the targeting pod's look.
 *
 * **Why four**: a half page is 450 x 675 units and a picture 8:5. Two views side by side take the page's width at about
 * 207 x 129 units each, so four rows (a name line over the two views) fill the page's height; three rows would leave a
 * quarter of the page empty at the same picture size, and five would shrink every picture by a fifth.
 *
 * **What goes on a half** is the card's list for **the flight open in the Planner** (1.4.1, [query]: no Briefing card
 * visit and no Populate needed) — its targets (`/api/targets/mission`) less the entries the pilot removed on the card,
 * plus the ones added there or with the picker's own **Add…** ([add]), both kept per mission by [TargetPics] — opened up into its vehicles,
 * radars and buildings ([entries]). A half the pilot has not chosen for takes the next [PER_PAGE] of the list in its
 * default order ([defaultOrder]: each target's main vehicle, each threat system's launcher and main radar, each ship and
 * each added target, in the card's order; then the rest), so a second Target pictures half takes the next four, and an
 * untouched half follows the mission's list. A half chosen in the picker ([TargetPicker]) keeps its choice
 * ([choices], kept with the plan in `kb_print_plan`) for the mission it was made for; another mission drops it.
 *
 * **Printing never sends a blank**: [prepare] reads the mission's list again and has every picture of the half drawn by
 * the PC before the page is captured; a half whose list or pictures the PC cannot give is left as it is, with a sentence.
 */
object KneeboardTargets {
    /** Targets on one half page: four rows of two views. */
    const val PER_PAGE = 4

    /** The kind as the Print window offers it. */
    val kind = KbPageKind(KbKind.TARGET_PICTURES, "Target pictures (up to $PER_PAGE targets, two views each)", "Target pics") { TargetPicturesPage(it) }

    /** The width the pictures are asked for: the card's, so the PC's cache serves both. */
    private const val WIDTH = TargetPics.WIDTH

    /** The mission's list as last read from the PC, and why it could not be read. */
    var set by mutableStateOf<TargetSet?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var loading by mutableStateOf(false)
        private set

    /** The halves the pilot chose for, `"<n><L|R>"` → the choice. Kept with the plan ([KneeboardPrintSession]). */
    val choices = mutableStateMapOf<String, KbTargetChoice>()

    /** The picker while it is open. */
    var picking by mutableStateOf<Picking?>(null)

    class Picking(val n: Int, val side: Char, ids: List<String>) {
        val draft: SnapshotStateList<String> = mutableStateListOf<String>().also { it.addAll(ids) }
    }

    /** The pictures drawn, by their path; and the paths the PC could not draw. */
    internal val images = mutableStateMapOf<String, ImageBitmap>()
    private val failed = mutableStateMapOf<String, Boolean>()

    /** What each half drew the last time, `"<n><L|R>"` → a line: for the checks (`--planneroutcome printpages`). */
    val drawn = HashMap<String, String>()

    /** One thing that can go on the page: an item of a group of the card's list. */
    class Entry(val id: String, val item: TargetItem, val group: TargetGroup, val primary: Boolean)

    // ---------------------------------------------------------------- the list

    /**
     * The card's list as entries: the mission's groups less those removed on the card, then the targets added there or
     * in the picker. (It reads [revision], never negative, so a list made of it is made again after an Add.)
     */
    fun entries(s: TargetSet? = set): List<Entry> {
        if (s == null || revision < 0) return emptyList()
        val removed = TargetPics.removed(s.mission).toSet()
        val groups = s.groups.filter { TargetPics.groupId(it) !in removed } +
            TargetPics.added(s.mission).map { TargetGroup(title = it.name, kind = "added", note = it.category, items = listOf(it)) }
        val out = ArrayList<Entry>()
        val seen = HashSet<String>()
        for (g in groups) {
            val launcher = g.items.firstOrNull { it.role == "launcher" || it.role == "system" } ?: g.items.firstOrNull()
            val radar = g.items.firstOrNull { it.role == "radar" }
            for ((i, item) in g.items.withIndex()) {
                val id = TargetPics.groupId(g) + "#" + item.key
                if (!seen.add(id)) continue
                val primary = when (g.kind) {
                    "threat" -> item === launcher || item === radar
                    else -> i == 0
                }
                out += Entry(id, item, g, primary)
            }
        }
        return out
    }

    /** The order the halves not chosen for take the entries in: the main ones first, then the rest, each in the card's order. */
    fun defaultOrder(list: List<Entry>): List<Entry> = list.filter { it.primary } + list.filter { !it.primary }

    /** The Target pictures halves of the plan, in page order (1L, 1R, 2L …). */
    private fun halves(): List<String> = (1..16).flatMap { n -> listOf("${n}L", "${n}R") }
        .filter { KneeboardPrintSession.plan[it] == KbKind.TARGET_PICTURES }

    /** A choice that still holds: made for the mission now, and naming entries it still has. */
    private fun live(key: String, s: TargetSet?, ids: Set<String>): List<String>? {
        val c = choices[key] ?: return null
        if (s == null || c.mission != s.mission) return null
        return c.ids.filter { it in ids }.take(PER_PAGE)
    }

    /** The ids on each Target pictures half: its own choice, else the next [PER_PAGE] of [defaultOrder] not on another half. */
    fun allocation(s: TargetSet? = set): Map<String, List<String>> {
        val list = entries(s)
        val ids = list.map { it.id }.toSet()
        val hs = halves()
        val chosen = hs.associateWith { live(it, s, ids) }
        val taken = chosen.values.filterNotNull().flatten().toMutableSet()
        val queue = defaultOrder(list).map { it.id }.filter { it !in taken }.toMutableList()
        return hs.associateWith { h -> chosen[h] ?: List(min(PER_PAGE, queue.size)) { queue.removeAt(0) } }
    }

    /** The entries on page [n]'s [side] half, in their order. */
    fun itemsFor(n: Int, side: Char, s: TargetSet? = set): List<Entry> {
        val list = entries(s)
        val key = "$n$side"
        val ids = allocation(s)[key] ?: defaultOrder(list).take(PER_PAGE).map { it.id }
        return ids.mapNotNull { id -> list.firstOrNull { it.id == id } }
    }

    /** Whether page [n]'s [side] half follows the mission's list (no choice of its own for this mission). */
    fun isDefault(n: Int, side: Char): Boolean = live("$n$side", set, entries().map { it.id }.toSet()) == null

    /** The picker's Done: [ids] on the half; the list's own default for it forgets the choice, so the half follows the mission. */
    fun choose(n: Int, side: Char, ids: List<String>) {
        val key = "$n$side"
        choices.remove(key)
        val s = set
        val dflt = allocation(s)[key]
        if (s != null && ids != dflt) choices[key] = KbTargetChoice(s.mission, ids.take(PER_PAGE))
        if (KneeboardPrintSession.kind(n, side) != KbKind.TARGET_PICTURES) KneeboardPrintSession.set(n, side, KbKind.TARGET_PICTURES)
        else KneeboardPrintSession.keep()
    }

    /** Opens the picker on a half, with what is on it now. */
    fun pick(n: Int, side: Char) { picking = Picking(n, side, itemsFor(n, side).map { it.id }) }

    /**
     * Which mission the list is asked for (1.4.1): **the Planner's own flight** — the save's flight opened with Open
     * mission… or Pick a flight (`save=`, `flight=`, `theater=`), else the printed briefing's (`printed=1`) — so the
     * picker works straight from the Planner, with no visit to the Briefing card and no Populate first. The PC answers
     * WDP mode's snapshot when it is that same flight (the Briefing card's additions are then the same); an older PC
     * ignores the query and answers the Mission section's mission, as before.
     */
    fun query(): String {
        val r = PlannerMissionState.ref
        // in EZBoards mode the printed briefing's flight, never a save the Planner had open in WDP mode (1.4.1)
        return if (KneeboardPrintSession.wdpMode() && PlannerMissionState.fromSave && r != null && r.file.isNotBlank() && r.flight.isNotBlank())
            "?theater=${TargetPics.enc(PlannerMissionState.theater ?: r.theater)}&save=${TargetPics.enc(r.file)}&flight=${TargetPics.enc(r.flight)}"
        else "?printed=1"
    }

    /** The query [set] was read for: another flight planned since reads the list again. */
    private var loadedFor: String? = null

    /** Bumped when the device's additions change ([add], [unadd]), so the lists made of [entries] are made again. */
    var revision by mutableStateOf(0)
        private set

    /** Reads the mission's list from the PC ([force]: even when it has it for the Planner's flight). */
    suspend fun load(force: Boolean = false) {
        val q = query()
        if (loading || (!force && set != null && loadedFor == q)) return
        loading = true
        try {
            val s = TargetPics.json("/api/targets/mission$q", TargetSet.serializer())
            if (s == null) error = "The BMS PC did not give the mission's targets (an older BMS Companion draws no target pictures)."
            else { set = s; loadedFor = q; error = null }
        } finally { loading = false }
    }

    /** The picker's **Add…** list while it is open. */
    var adding by mutableStateOf(false)

    /**
     * Adds [item] (from the Add list, [com.bmscompanion.app.ui.screens.mission.TargetCatalogDialog]) to the mission's
     * list on this device, as the Briefing card's Add does (kept per mission, [TargetPics.added]); the open picker ticks
     * it while the half has room. Answers the entry's id, or null without a list.
     */
    fun add(item: TargetItem): String? {
        val s = set ?: return null
        val now = TargetPics.added(s.mission)
        if (now.none { it.key == item.key }) TargetPics.saveAdded(s.mission, now + item.copy(role = "added"))
        revision++
        val id = entries(s).firstOrNull { it.group.kind == "added" && it.item.key == item.key }?.id
        picking?.let { p -> if (id != null && id !in p.draft && p.draft.size < PER_PAGE) p.draft.add(id) }
        return id
    }

    /** Takes a target added on this device ([add], or on the Briefing card) off the mission's list again. */
    fun unadd(key: String) {
        val s = set ?: return
        TargetPics.saveAdded(s.mission, TargetPics.added(s.mission).filter { it.key != key })
        revision++
        picking?.draft?.removeAll { it.startsWith("added|") && it.endsWith("#$key") }
    }

    // ---------------------------------------------------------------- the pictures

    fun path(theater: String, key: String, view: String): String =
        "/api/targets/picture?theater=${TargetPics.enc(theater)}&key=${TargetPics.enc(key)}&view=$view&look=${TargetPictureApi.LOOK_INTEL}&w=$WIDTH"

    /** The picture at [path], drawn by the PC; null when it cannot be (tried twice). */
    internal suspend fun picture(path: String): ImageBitmap? {
        images[path]?.let { return it }
        repeat(2) {
            val img = MissionLink.fetchBytes(path, timeoutMs = 30_000)?.let { Repo.decodeBitmap(it) }?.asImageBitmap()
            if (img != null) {
                failed.remove(path)
                images[path] = img
                // a few pages' worth kept, the oldest let go (Kotlin/Wasm's maps keep insertion order)
                while (images.size > 48) images.remove(images.keys.first())
                return img
            }
        }
        failed[path] = true
        return null
    }

    internal fun hasFailed(path: String) = failed[path] == true

    /**
     * Gets page [n]'s [side] half ready to print: the mission's list read again and every one of its pictures drawn by
     * the PC. Null when it is ready; else the sentence saying why the half is left as it is.
     */
    suspend fun prepare(n: Int, side: Char): String? {
        load(force = true)
        val head = "Page $n, ${KneeboardPages.knee(side)}, was left as it is"
        val s = set ?: return "$head: ${error ?: "the BMS PC did not give the mission's targets."}"
        val all = entries(s)
        if (all.isEmpty()) return "$head: " + (s.error ?: "the flight names no target that has a model (the picker's Add… puts one on the list).")
        val items = itemsFor(n, side, s)
        if (items.isEmpty()) return "$head: no target is left for it — the Target pictures halves before it hold all ${all.size} of the mission's."
        for (e in items) for ((view, label) in TargetPictureApi.VIEWS) {
            if (picture(path(s.theater, e.item.key, view)) == null)
                return "$head: the PC could not draw ${e.item.name} (${label.lowercase()}), so no blank picture was printed."
        }
        return null
    }
}

/** A Target pictures half's own choice: the [ids] of [KneeboardTargets.Entry], for the mission [mission] (`TargetSet.mission`). */
@Serializable
class KbTargetChoice(val mission: String = "", val ids: List<String> = emptyList())

// ---------------------------------------------------------------- the page

private val INK = Color(0xFF111111)
private val FAINT = Color(0xFF6E6E6E)
private val NO_PIC = Color(0xFFE6E6E6)

/** The page as printed: page [scope]'s half, or [items] when given (the picker's preview). */
@Composable
internal fun TargetPicturesPage(scope: KbPageScope, items: List<KneeboardTargets.Entry>? = null) {
    val t = KneeboardTargets
    LaunchedEffect(Unit) { t.load() }
    val s = t.set
    val list = items ?: t.itemsFor(scope.n, scope.side)
    // the page's own type: the theme's 24-sp lines leave no room in a row (as the other pages of this app)
    CompositionLocalProvider(
        androidx.compose.material3.LocalTextStyle provides androidx.compose.material3.LocalTextStyle.current.copy(
            lineHeight = TextUnit.Unspecified, letterSpacing = TextUnit.Unspecified,
        ),
    ) {
        Column(Modifier.fillMaxSize().background(Color.White).padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 5.dp)) {
            val m = KneeboardExtraPages.mission()
            val flight = m.briefing?.overview?.flight ?: m.flight?.row?.callsign
            Row(verticalAlignment = Alignment.Bottom) {
                Text("TARGET PICTURES", color = INK, fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp, maxLines = 1)
                Spacer(Modifier.weight(1f))
                Text("FRONT RIGHT  |  REAR LEFT", color = FAINT, fontSize = 7.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.4.sp, maxLines = 1)
            }
            Text(
                listOfNotNull(flight, m.briefing?.overview?.mission, s?.theater?.ifBlank { null }, "drawn from Falcon BMS's 3-D models").joinToString(" · "),
                color = FAINT, fontSize = 8.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Box(Modifier.padding(top = 3.dp, bottom = 4.dp).fillMaxWidth().height(1.2.dp).background(INK))
            Column(Modifier.fillMaxWidth().weight(1f)) {
                if (list.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            when {
                                s == null && t.error != null -> t.error!!
                                s == null -> "Reading the mission's targets…"
                                s.error != null -> s.error
                                else -> "No target is chosen for this page."
                            },
                            color = FAINT, fontSize = 10.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(16.dp),
                        )
                    }
                } else {
                    val theater = s?.theater.orEmpty()
                    val pics = list.sumOf { e -> TargetPictureApi.VIEWS.count { (v, _) -> KneeboardTargets.images[KneeboardTargets.path(theater, e.item.key, v)] != null } }
                    for (i in 0 until KneeboardTargets.PER_PAGE) {
                        val e = list.getOrNull(i)
                        Box(Modifier.fillMaxWidth().weight(1f)) {
                            if (e != null) TargetRow(e, theater)
                        }
                    }
                    SideEffect {
                        KneeboardTargets.drawn["${scope.n}${scope.side}"] =
                            "${list.size} targets, $pics of ${list.size * 2} pictures: " + list.joinToString(" / ") { it.item.name }
                    }
                }
            }
            Text(
                "Page ${scope.n} · ${KneeboardPages.knee(scope.side)} · BMS Companion",
                Modifier.fillMaxWidth().padding(top = 3.dp), color = FAINT, fontSize = 7.sp, textAlign = TextAlign.Center,
            )
        }
    }
}

/** What a target is on its line: its part and how many ("launcher · ×6"). */
private fun partOf(item: TargetItem): String = listOfNotNull(
    item.role?.takeIf { it != "added" && it != "unit" },
    item.count?.takeIf { it > 1 }?.let { "×$it" },
    item.category.takeIf { item.role == "added" || item.role == "unit" },
).joinToString(" · ")

/** Where it belongs: "THREAT SA-5 (S-200) · 9 nm northwest of Majdal Shams". */
private fun groupOf(g: TargetGroup, itemName: String): String {
    val word = when (g.kind) { "target" -> "TARGET"; "threat" -> "THREAT"; "ship" -> "SHIP"; else -> "ADDED" }
    val what = listOfNotNull(
        g.title.takeIf { g.kind != "added" && g.kind != "ship" && it != itemName },
        g.note?.takeIf { g.kind != "added" },
    ).joinToString(" · ")
    return if (what.isEmpty()) word else "$word  $what"
}

/** One target: its name line, then its two views as large as the row allows. */
@Composable
private fun TargetRow(e: KneeboardTargets.Entry, theater: String) {
    Column(Modifier.fillMaxSize().padding(bottom = 3.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(e.item.name, color = INK, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            partOf(e.item).ifEmpty { null }?.let { Text("  $it", color = INK, fontSize = 8.5.sp, maxLines = 1) }
            // where it belongs, in the rest of the line (the group's name left out when it is the item's own)
            Text(groupOf(e.group, e.item.name), color = FAINT, fontSize = 7.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End,
                modifier = Modifier.weight(1f).padding(start = 8.dp))
        }
        Spacer(Modifier.height(2.dp))
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val gap = 6.dp
            val w = min(((maxWidth - gap) / 2).value, (maxHeight * 1.6f).value).dp
            val h = w / 1.6f
            Row(horizontalArrangement = Arrangement.spacedBy(gap), modifier = Modifier.align(Alignment.TopCenter)) {
                for ((view, label) in TargetPictureApi.VIEWS) {
                    val path = KneeboardTargets.path(theater, e.item.key, view)
                    LaunchedEffect(path) { KneeboardTargets.picture(path) }
                    val img = KneeboardTargets.images[path]
                    Box(Modifier.size(w, h).border(0.8.dp, INK).background(if (img != null) Color.Black else NO_PIC)) {
                        if (img != null) Image(img, contentDescription = "${e.item.name}, $label", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        else Text(
                            if (KneeboardTargets.hasFailed(path)) "No picture" else "Drawing…", color = FAINT, fontSize = 8.sp,
                            modifier = Modifier.align(Alignment.Center),
                        )
                        Text(
                            label.uppercase(), color = Color.White, fontSize = 6.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.align(Alignment.BottomEnd).padding(3.dp).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- the picker

/**
 * The picker for one Target pictures half, in place of the table (as Browse picture…'s chooser is): the card's list
 * opened up into its vehicles, radars and buildings, a tick for each up to [KneeboardTargets.PER_PAGE], and the page as
 * it will print beside it on a wide window. **Mission's first four** gives the half back to the list's default order;
 * **Done** keeps the choice with the plan; **Cancel** leaves the half as it was. By finger the window is the whole
 * Planner, so on a phone the list is a full-screen sheet with a finger's rows.
 */
@Composable
internal fun ColumnScope.TargetPicker(p: KneeboardTargets.Picking, modifier: Modifier) {
    val t = KneeboardTargets
    LaunchedEffect(p) {
        t.load(force = true)
        // opened before the list was read: the half's own targets once it is
        if (p.draft.isEmpty()) p.draft.addAll(t.itemsFor(p.n, p.side).map { it.id })
    }
    val s = t.set
    val list = t.entries(s)
    val alloc = t.allocation(s)
    val elsewhere = HashMap<String, String>()
    for ((h, ids) in alloc) if (h != "${p.n}${p.side}") for (id in ids) elsewhere.getOrPut(id) { h }
    val max = KneeboardTargets.PER_PAGE
    Column(modifier.plannerProbe("Print/Tgt"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "Target pictures  ·  page ${p.n}, ${KneeboardPages.knee(p.side)}  ·  ${p.draft.size} of $max chosen",
            color = Hud.Text, fontSize = fingerSp(12f), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Text(
            when {
                s == null && t.loading -> "Reading the mission's targets on the PC…"
                s == null -> t.error ?: "The BMS PC did not give the mission's targets."
                s.error != null && list.isEmpty() -> s.error
                list.isEmpty() -> "The flight names no target that has a model. Add… puts any ground target of the theater on the list."
                else -> "The targets of the flight open in the Planner (removed on the Briefing card stays out; added there or with Add… is in). Up to $max, each printed in its two views."
            },
            color = Hud.TextDim, fontSize = fingerSp(11f), maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val wide = maxWidth >= 560.dp
            val previewW = minOf(maxWidth * 0.36f, maxHeight * (2f / 3f))
            val rows =@Composable { m: Modifier ->
                LazyColumn(m.plannerProbe("Print/Tgt/List")) {
                    var last: TargetGroup? = null
                    for (e in list) {
                        if (e.group !== last) {
                            last = e.group
                            val g = e.group
                            item(key = "g-" + e.id) {
                                Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                    val tint = when (g.kind) { "target" -> Hud.Amber; "threat" -> com.bmscompanion.app.ui.screens.mission.Hostile; "ship" -> Hud.Cyan; else -> Hud.TextDim }
                                    Text(
                                        when (g.kind) { "target" -> "TARGET"; "threat" -> "THREAT"; "ship" -> "SHIP"; else -> "ADDED" },
                                        Modifier.border(1.dp, tint, RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
                                        fontSize = 10.sp, fontWeight = FontWeight.Bold, color = tint,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        listOfNotNull(g.title, g.note).joinToString("  ·  "), color = Hud.Text, fontSize = fingerSp(12f),
                                        fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false),
                                    )
                                    // a target added on this device comes off the list again here
                                    if (g.kind == "added") {
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            "✕", color = com.bmscompanion.app.ui.screens.mission.Hostile, fontSize = fingerSp(13f), fontWeight = FontWeight.Bold,
                                            modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable { g.items.firstOrNull()?.let { t.unadd(it.key) } }
                                                .padding(horizontal = 8.dp, vertical = 2.dp).plannerProbe("Print/Tgt/Unadd/${g.items.firstOrNull()?.key}"),
                                        )
                                    }
                                }
                            }
                        }
                        item(key = e.id) {
                            val on = e.id in p.draft
                            val can = on || p.draft.size < max
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = if (WdpTouch.device) 44.dp else 30.dp).clip(RoundedCornerShape(5.dp))
                                    .background(if (on) Hud.Amber.copy(alpha = 0.12f) else Color.Transparent)
                                    .clickable(enabled = can) { if (on) p.draft.remove(e.id) else p.draft.add(e.id) }
                                    .padding(start = 14.dp, end = 6.dp)
                                    .alpha(if (can) 1f else 0.45f)
                                    .plannerProbe("Print/Tgt/Item/${e.id}"),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                // a tick box drawn here (the windows' own furniture), with its place on the page in it
                                Box(
                                    Modifier.size(18.dp).clip(RoundedCornerShape(3.dp)).background(if (on) Hud.Amber else Color.Transparent)
                                        .border(1.4.dp, if (on) Hud.Amber else Hud.TextDim, RoundedCornerShape(3.dp)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (on) Text(
                                        "${p.draft.indexOf(e.id) + 1}", color = Color(0xFF14171B), fontSize = 11.sp, lineHeight = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                                Spacer(Modifier.width(10.dp))
                                Text(e.item.name, color = Hud.Text, fontSize = fingerSp(12.5f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                partOf(e.item).ifEmpty { null }?.let { Text("  $it", color = Hud.TextDim, fontSize = fingerSp(11f), maxLines = 1) }
                                Spacer(Modifier.weight(1f))
                                elsewhere[e.id]?.let { h ->
                                    Text(
                                        "on page ${h.dropLast(1)} ${if (h.last() == 'L') "left" else "right"}", color = Hud.TextFaint,
                                        fontSize = fingerSp(10f), maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (wide) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    rows(Modifier.weight(1f).fillMaxHeight())
                    val chosen = p.draft.mapNotNull { id -> list.firstOrNull { it.id == id } }
                    val kind = remember(chosen.map { it.id }) {
                        KbPageKind(KbKind.TARGET_PICTURES, "Target pictures", "Targets") { TargetPicturesPage(it, chosen) }
                    }
                    Box(
                        Modifier.fillMaxHeight().width(previewW).clip(RoundedCornerShape(3.dp))
                            .background(Color(0xFF30353C)).plannerProbe("Print/Tgt/Preview"),
                        contentAlignment = Alignment.TopCenter,
                    ) { KneeboardPageHost(kind, p.n, p.side, Modifier.fillMaxSize()) }
                }
            } else rows(Modifier.fillMaxSize())
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Btn("Mission's first $max", enabled = s != null, probe = "Print/Tgt/Default") {
                // what the half would take with no choice of its own
                val saved = t.choices.remove("${p.n}${p.side}")
                val d = t.allocation(s)["${p.n}${p.side}"] ?: t.defaultOrder(list).take(max).map { it.id }
                saved?.let { t.choices["${p.n}${p.side}"] = it }
                p.draft.clear(); p.draft.addAll(d)
            }
            Btn("Clear", enabled = p.draft.isNotEmpty(), probe = "Print/Tgt/Clear") { p.draft.clear() }
            Btn("Add…", enabled = s != null, probe = "Print/Tgt/Add") { t.adding = true }
            Spacer(Modifier.weight(1f))
            Btn("Cancel", probe = "Print/Tgt/Cancel") { t.picking = null }
            Btn("Done", enabled = s != null && p.draft.isNotEmpty(), primary = true, probe = "Print/Tgt/Done") {
                t.choose(p.n, p.side, p.draft.toList())
                KneeboardPrintSession.selN = p.n; KneeboardPrintSession.selSide = p.side
                t.picking = null
            }
        }
    }
    // the Briefing card's Add list, here too: any ground target of the flight's theater onto the list (and onto this half)
    if (t.adding && s != null) com.bmscompanion.app.ui.screens.mission.TargetCatalogDialog(s.theater, onDismiss = { t.adding = false }) { item ->
        t.add(item)
        t.adding = false
    }
}
