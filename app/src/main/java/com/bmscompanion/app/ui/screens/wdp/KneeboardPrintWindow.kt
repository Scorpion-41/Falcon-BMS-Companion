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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.draw.drawBehind
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bmscompanion.app.data.PcFiles
import com.bmscompanion.app.data.Platform
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.mission.KbFileResult
import com.bmscompanion.app.data.mission.KbFileSend
import com.bmscompanion.app.data.mission.KbHalf
import com.bmscompanion.app.data.mission.KbKind
import com.bmscompanion.app.data.mission.KbOwner
import com.bmscompanion.app.data.mission.KbPrintState
import com.bmscompanion.app.data.mission.KbSlot
import com.bmscompanion.app.data.mission.LinkState
import com.bmscompanion.app.data.mission.MissionLink
import com.bmscompanion.app.data.mission.MissionMode
import com.bmscompanion.app.ui.theme.Hud
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/**
 * What the Print window keeps (R3-PLAN A15): what goes on each half page, the half shown in the preview, and what the
 * last print or restore answered. Reopening the window finds the plan as it was left, so printing again after a change
 * on the card is two presses. **The plan and each half's picture are kept on the device between launches** (its own
 * settings, [SAVED_KEY]), as WDP keeps its kneeboard choices and picture files in its Setup.ini: a picture page set
 * once — a checklist, say — is still planned the next evening.
 *
 * The first time the PC's pages are known the plan is the **Mission set**, laid for the Mission section's mode on the
 * PC ([KbPrintState.mode]):
 * - **WDP mode** (where the Planner is used): the DataCard on page 1 and the Coordination Card on page 2 — the first two
 *   pages that can be printed. EZBoards does not run at PRINT in WDP mode, so its pages are the Planner's.
 * - **EZBoards mode**: the DataCard on the first page pair EZBoards does not claim and the Coordination Card on the next
 *   (pages 2 and 3 on a stock install, beside EZBoards' page 1).
 *
 * Every other half is left as it is. A Mission set the pilot has not touched since is laid again when the mode it was
 * laid for is no longer the PC's ([laidFor]), and the window says so ([relaid]); a plan changed by hand is kept, with
 * a quiet line saying which mode it was made in.
 *
 * **Pictures** (Browse picture…, WDP's Browse Picture): a half of kind [KbKind.PICTURE] shows the file [pictures] names
 * for it, a path on the BMS PC ([KneeboardPictures]); [browse] is the chooser while it is open. A half keeps its
 * picture's path when it is set to another kind, as WDP keeps `m_Left[n].File`, so its Picture… opens the chooser on
 * that picture again rather than the file window.
 */
object KneeboardPrintSession {
    /** Where the plan is kept between launches, in the device's own settings: one JSON object ([Saved]). */
    const val SAVED_KEY = "kb_print_plan"

    /** `"<n><L|R>"` → a [KbKind]; a half not in the map is left as it is */
    val plan = mutableStateMapOf<String, String>()

    /** `"<n><L|R>"` → the picture file on the BMS PC that half shows or last showed (WDP's `m_Left[n].File`) */
    val pictures = mutableStateMapOf<String, String>()

    /** What is kept between launches. */
    @Serializable
    private class Saved(
        val plan: Map<String, String> = emptyMap(),
        val pictures: Map<String, String> = emptyMap(),
        val planned: Boolean = false,
        val laidFor: String? = null,
        val madeIn: String? = null,
    )

    /** The plan as the device kept it; nothing (a fresh start) when there is none or it cannot be read. */
    private fun restore() {
        val s = runCatching { Repo.getString(SAVED_KEY)?.let { Repo.json.decodeFromString(Saved.serializer(), it) } }.getOrNull() ?: return
        // a kind this build does not draw (kept by another version) is left out: it would never be drawn for the PC
        plan.clear(); plan.putAll(s.plan.filter { (k, v) -> halfKey(k) && v != KbKind.LEAVE && KneeboardPages.of(v) != null })
        pictures.clear(); pictures.putAll(s.pictures.filter { (k, v) -> halfKey(k) && v.isNotBlank() })
        planned = s.planned
        laidFor = s.laidFor
        madeIn = s.madeIn
    }

    private fun halfKey(k: String) = k.length in 2..3 && k.last() in "LR" && k.dropLast(1).toIntOrNull() in 1..16

    /** Keeps the plan on the device, for the next launch. Never throws. */
    private fun save() {
        runCatching {
            Repo.putString(SAVED_KEY, Repo.json.encodeToString(Saved.serializer(), Saved(plan.toMap(), pictures.toMap(), planned, laidFor, madeIn)))
        }
    }

    /** Forgets the plan here and on the device: the next time the pages are read, the window opens on the Mission set. */
    fun forget() {
        plan.clear(); pictures.clear()
        planned = false; laidFor = null; madeIn = null; relaid = null; browse = null
        runCatching { Repo.putString(SAVED_KEY, null) }
    }

    /** The plan as the device kept it, read again (what a launch finds): for the checks. */
    fun reloadSaved() {
        plan.clear(); pictures.clear(); planned = false; laidFor = null; madeIn = null; relaid = null
        restore()
    }

    fun picture(n: Int, side: Char): String? = pictures["$n$side"]

    /** whether the plan has been set once, so the Mission set is only put in by itself the first time */
    var planned by mutableStateOf(false)

    /** the mode ([MissionMode]) the plan is the untouched Mission set of; null once a half was changed by hand */
    var laidFor by mutableStateOf<String?>(null)

    /** the mode in force when the plan was last laid or changed */
    var madeIn by mutableStateOf<String?>(null)

    /** the sentence saying the Mission set was laid again for a new mode, until the plan is next changed */
    var relaid by mutableStateOf<String?>(null)

    /** Browse picture…'s chooser while it is open: the file picked, and the page it would go on. */
    var browse by mutableStateOf<PictureBrowse?>(null)

    class PictureBrowse(val path: String, page: Int, val side: Char?) {
        var page by mutableStateOf(page)
    }

    /** the PC's pages as last read, and the sentence it gave when it could not read them */
    var state by mutableStateOf<KbPrintState?>(null)
    var error by mutableStateOf<String?>(null)
    var loading by mutableStateOf(false)

    /** the half in the preview */
    var selN by mutableStateOf(2)
    var selSide by mutableStateOf('L')

    /** the half being drawn for the PC while a print runs, shown in the preview; null when not printing */
    var printing by mutableStateOf<Pair<Int, Char>?>(null)
    var busy by mutableStateOf(false)
    var progress by mutableStateOf<String?>(null)

    /** what the last Print or Put BMS's page back answered, one line per file, and what it was */
    var results by mutableStateOf<List<KbFileResult>>(emptyList())
    var resultsOf by mutableStateOf<String?>(null)

    /** bumped after anything that changes the files, so the thumbnails are fetched again */
    var stamp by mutableStateOf(0)

    // after every property above: the plan kept on the device is put back into them
    init { restore() }

    fun kind(n: Int, side: Char): String = plan["$n$side"] ?: KbKind.LEAVE

    fun set(n: Int, side: Char, kind: String) {
        if (kind == KbKind.LEAVE) plan.remove("$n$side") else plan["$n$side"] = kind
        touched()
    }

    /** The plan was changed by hand: it is no longer a Mission set to lay again for another mode. */
    private fun touched() {
        planned = true
        laidFor = null
        madeIn = mode()
        relaid = null
        save()
    }

    fun slot(n: Int): KbSlot? = state?.pages?.firstOrNull { it.n == n }

    /** a page file the PC can print into: there, readable and in a format it writes */
    fun printable(s: KbSlot?) = s != null && !s.missing && s.problem == null

    /**
     * The Mission section's mode the pages are planned for ([MissionMode.WDP] or [MissionMode.EZBOARDS]): the PC's, as
     * the pages came with it ([KbPrintState.mode]); from a PC that does not say, what its `/api/info` said.
     */
    fun mode(st: KbPrintState? = state): String =
        st?.mode?.let { MissionMode.of(it) } ?: MissionLink.info.value?.mission?.mode?.let { MissionMode.of(it) } ?: MissionMode.EZBOARDS

    /** WDP mode: EZBoards does not run at PRINT, so its claims leave the pages to the Planner. */
    fun wdpMode(st: KbPrintState? = state): Boolean = mode(st) == MissionMode.WDP

    /**
     * The pages the Mission set uses.
     * - **WDP mode**: the first two pages that exist and can be written (1 and 2 on a stock install): EZBoards is paused
     *   in WDP mode (`EzStatus.suspended`), so the halves it claims are not kept from the Planner.
     * - **EZBoards mode**: the first two page pairs EZBoards does not claim, that exist and can be written. When BMS
     *   Companion does not know EZBoards' configuration (its folder is not set), page 1 is left out all the same:
     *   EZBoards as it ships writes page 1 at every PRINT, so the Planner's pages are 2 and 3 there.
     */
    fun missionPages(st: KbPrintState? = state): List<Int> {
        val usable = st?.pages.orEmpty().filter { printable(it) }
        val free = if (wdpMode(st)) usable else usable.filter { !it.ezLeft && !it.ezRight && !(st?.ezConfig == null && it.n == 1) }
        return free.map { it.n }.sorted().take(2)
    }

    /** The Mission set, in place of whatever was planned, laid for the mode in force. Answers the pages it used. */
    fun missionSet(): List<Int> {
        val pages = missionPages()
        plan.clear()
        pages.getOrNull(0)?.let { plan["${it}L"] = KbKind.DATACARD_LEFT; plan["${it}R"] = KbKind.DATACARD_RIGHT }
        pages.getOrNull(1)?.let { plan["${it}L"] = KbKind.COORDINATION_LEFT; plan["${it}R"] = KbKind.COORDINATION_RIGHT }
        pages.firstOrNull()?.let { selN = it; selSide = 'L' }
        planned = true
        laidFor = mode()
        madeIn = laidFor
        relaid = null
        save()
        return pages
    }

    fun leaveAll() { plan.clear(); touched() }

    // ---------------------------------------------------------------- Browse picture… (WDP's Browse Picture)

    /**
     * WDP's Browse Picture: the Open picture window on the BMS PC, starting at [from] when given (the picture the
     * chooser shows), else at the picture this half last had, else at the last picture opened, else in the Falcon BMS
     * folder, as WDP does; then the chooser ([browse]) on page [n] with [side] offered first. Nothing changes until the
     * chooser's Insert.
     */
    suspend fun browsePicture(n: Int, side: Char? = null, from: String? = null) {
        if (busy) return
        val start = from ?: side?.let { pictures["$n$it"] } ?: WdpFiles.last(KneeboardPictures.LAST_FILE)
        val startName = WdpFiles.nameOf(start)
        val f = PcFiles.open(
            "Open picture",
            startDir = start?.dropLast(startName.length)?.trimEnd('\\', '/')?.ifEmpty { null } ?: "@bms",
            filters = KneeboardPictures.FILTERS,
            fileName = startName.ifEmpty { null },
        ) ?: return
        WdpFiles.remember(KneeboardPictures.LAST_FILE, f)
        browse = PictureBrowse(f.path, n.coerceIn(1, 16), side)
        KneeboardPictures.load(f.path, fresh = true)
    }

    /**
     * A half's own **Picture…**: when the half has had a picture (it keeps its path when set to another kind, as WDP's
     * `m_Left[n].File` stays), the chooser opens on that picture again, with Insert, the page buttons and Another
     * picture… as ever; a half that never had one opens the Open picture window ([browsePicture]).
     */
    suspend fun pictureFor(n: Int, side: Char) {
        if (busy) return
        val had = pictures["$n$side"]
        if (had == null) { browsePicture(n, side); return }
        browse = PictureBrowse(had, n.coerceIn(1, 16), side)
        KneeboardPictures.load(had, fresh = true)
    }

    /** The chooser's Insert: the picture on page [PictureBrowse.page]'s [side] half (WDP's `<- Insert` / `Insert ->`). */
    fun insertPicture(side: Char): Boolean {
        val b = browse ?: return false
        if (!printable(slot(b.page)) || KneeboardPictures.cached(b.path)?.image == null) return false
        pictures["${b.page}$side"] = b.path
        set(b.page, side, KbKind.PICTURE)
        selN = b.page; selSide = side
        browse = null
        return true
    }

    fun cancelPicture() { browse = null }

    /**
     * The untouched Mission set [missionSet] would lay now differs from the plan: the pages that can be printed changed
     * (another theater with fewer page files, EZBoards' claims). A plan changed by hand is never laid again. Halves
     * planned onto a page file that cannot take them now are kept, not dropped — [jobs] and the table leave them out —
     * so a theater without the pages, or a page held open for a moment, does not cost the pilot the plan (WDP keeps its
     * sixteen pages whatever the theater).
     */
    private fun missionSetMoved(): Boolean {
        if (laidFor == null) return false
        val pages = missionPages()
        if (pages.isEmpty()) return false
        val want = HashMap<String, String>()
        pages.getOrNull(0)?.let { want["${it}L"] = KbKind.DATACARD_LEFT; want["${it}R"] = KbKind.DATACARD_RIGHT }
        pages.getOrNull(1)?.let { want["${it}L"] = KbKind.COORDINATION_LEFT; want["${it}R"] = KbKind.COORDINATION_RIGHT }
        return want != plan.toMap()
    }

    /** The files a print would write: page n with its left and right kinds, for every page with anything on it. */
    fun jobs(): List<Triple<Int, String, String>> = (1..16).mapNotNull { n ->
        val l = kind(n, 'L')
        val r = kind(n, 'R')
        if (l == KbKind.LEAVE && r == KbKind.LEAVE || !printable(slot(n))) null else Triple(n, l, r)
    }

    /** Reads the PC's pages again. */
    suspend fun load() {
        loading = true
        val a = MissionLink.kbState()
        // a refusal may still carry the state (no page folder for this theater): both are kept
        state = a.value
        error = a.error ?: a.value?.error
        if (a.value != null && a.value.pages.isNotEmpty()) {
            val now = mode(a.value)
            when {
                !planned -> missionSet()
                // an untouched Mission set of the other mode is laid again: page 1 is the Planner's in WDP mode
                laidFor != null && laidFor != now -> {
                    missionSet()
                    relaid = if (now == MissionMode.WDP) "Mission set laid again for WDP mode: EZBoards is paused, so the Planner's pages start at page 1."
                    else "Mission set laid again for EZBoards mode: it leaves EZBoards the pages it writes at PRINT."
                }
                // an untouched Mission set whose pages moved (another theater, EZBoards' claims): laid on the pages now
                missionSetMoved() -> missionSet()
                // anything else is the pilot's plan, kept as it is
                else -> Unit
            }
        }
        loading = false
    }

    /**
     * Draws every planned half in [layer]'s page host, one after another, and sends each page file to the PC as soon as
     * both its halves are drawn. One answer per file comes back; the pages are read again at the end.
     */
    suspend fun print(layer: GraphicsLayer) {
        if (busy) return
        val jobs = jobs()
        if (jobs.isEmpty()) return
        busy = true
        resultsOf = "Print"
        results = emptyList()
        val out = ArrayList<KbFileResult>()
        try {
            val halves = jobs.sumOf { (_, l, r) -> (if (l != KbKind.LEAVE) 1 else 0) + (if (r != KbKind.LEAVE) 1 else 0) }
            var done = 0
            for ((n, l, r) in jobs) {
                val file = slot(n)?.file?.ifBlank { null } ?: KneeboardPages.fileOf(n)
                val sent = arrayOfNulls<KbHalf>(2)
                var failed: String? = null
                var skipped = 0
                for ((i, side) in listOf('L', 'R').withIndex()) {
                    val kind = if (i == 0) l else r
                    if (kind == KbKind.LEAVE) continue
                    done++
                    if (kind == KbKind.PICTURE) {
                        // not drawn here: the PC reads the file at this print, as WDP reads it at its save, and draws the
                        // half from the file's own pixels; a picture gone or unreadable by then leaves its half as it is
                        val path = picture(n, side)
                        if (path == null) {
                            out += KbFileResult("$file, ${KneeboardPages.knee(side)}", KbFileResult.REFUSED,
                                "Page $n, ${KneeboardPages.knee(side)}, was left as it is: no picture was chosen for it.")
                            results = out.toList()
                            skipped++
                            continue
                        }
                        sent[i] = KbHalf(kind, "Picture: ${KneeboardPictures.nameOf(path)}", picture = path)
                        continue
                    }
                    progress = "Drawing page $n, ${KneeboardPages.knee(side)} ($done of $halves)…"
                    KneeboardPages.preload(kind)
                    printing = n to side
                    // the composition hears of the page to draw now, not whenever the next global snapshot is sent: a
                    // half set from here was sometimes never composed, and the half before it was captured in its place
                    androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
                    KneeboardPages.settle()
                    // captured only once the host has drawn this very page, never the one before it
                    if (!KneeboardPages.awaitDrawn(layer, kind, n, side)) {
                        failed = "This device did not draw page $n (${KneeboardPages.knee(side)}) in time, so nothing was sent for it: press Print again."
                        break
                    }
                    val png = KneeboardPages.capturePng(layer)
                    if (png == null) { failed = "This device could not draw page $n (${KneeboardPages.knee(side)}): ${KneeboardPages.lastError ?: "no picture"}."; break }
                    sent[i] = KbHalf(kind, KneeboardPages.label(kind), KneeboardPages.base64(png))
                }
                printing = null
                if (failed != null) {
                    out += KbFileResult(file, KbFileResult.REFUSED, failed)
                } else if (sent.all { it == null }) {
                    // every half planned here was a picture with none chosen: the file is not touched
                    if (skipped == 0) out += KbFileResult(file, KbFileResult.UNCHANGED)
                } else {
                    val pics = sent.mapNotNull { it?.picture?.ifBlank { null } }
                    progress = if (pics.isEmpty()) "Writing $file…"
                    else "Writing $file (the PC reads ${pics.joinToString(" and ") { KneeboardPictures.nameOf(it) }})…"
                    val a = MissionLink.kbPrintFile(KbFileSend(n, sent[0], sent[1]))
                    val v = a.value
                    // a Picture half the PC could not read is its own line, as the window names a half
                    val halfWhy = listOf('L' to v?.leftRefused, 'R' to v?.rightRefused).filter { it.second != null }
                    for ((side, why) in halfWhy) out += KbFileResult("$file, ${KneeboardPages.knee(side)}", KbFileResult.REFUSED, why)
                    val onlyThose = halfWhy.isNotEmpty() && v?.status == KbFileResult.REFUSED &&
                        sent.indices.all { i -> sent[i] == null || (if (i == 0) v.leftRefused else v.rightRefused) != null }
                    if (!onlyThose) {
                        out += v?.let { if (a.error != null && it.reason == null) it.copy(reason = a.error) else it }
                            ?: KbFileResult(file, KbFileResult.REFUSED, a.error ?: "The PC did not answer.")
                    }
                }
                results = out.toList()
            }
            // the pages are read back while still busy, so the window shows the new owners the moment it is free
            printing = null
            results = out.toList()
            stamp++
            progress = "Reading the pages back…"
            load()
        } finally {
            printing = null
            progress = null
            results = out.toList()
            stamp++
            busy = false
        }
    }

    /** Puts BMS's own page back on page [n], or on every page when null. */
    suspend fun putBack(n: Int?) {
        if (busy) return
        busy = true
        resultsOf = if (n == null) "Put BMS's pages back" else "Put BMS's page back"
        progress = if (n == null) "Putting BMS's pages back…" else "Putting BMS's page $n back…"
        try {
            val a = MissionLink.kbShipped(n)
            results = a.value ?: listOf(KbFileResult(n?.let { KneeboardPages.fileOf(it) } ?: "All pages", KbFileResult.REFUSED, a.error ?: "The PC did not answer."))
            stamp++
            progress = "Reading the pages back…"
            load()
        } finally {
            progress = null
            stamp++
            busy = false
        }
    }
}

/**
 * Upd Kneeboard: the cockpit's kneeboard pages, and what goes on each (R3-PLAN A15-A18).
 *
 * The F-16's pages are sixteen files, each holding page n of the left knee in its left half and page n of the right
 * knee in its right half. The window lists the sixteen pages with, for each half, what is on it now (a picture from the
 * PC and who made it) and what to put there; **Print** draws every chosen half on this device ([KneeboardPages]) and
 * sends it to the PC, which writes it into the page file in the file's own format, with no backup: a page is made
 * again at will, by EZBoards, BMS's PRINT or here. **Put BMS's page back** restores BMS's own page where BMS ships one.
 *
 * It needs the PC: not linked, it says so and does nothing else. In EZBoards mode EZBoards' pages (its `SET KNEEBOARD`
 * lines) are marked, since its next PRINT replaces them; in WDP mode EZBoards is paused and its pages are the Planner's.
 *
 * **Browse picture…** (WDP's Browse Picture) opens the Open picture window on the BMS PC, then a chooser in place of the
 * table: the picture as it will fill the half, a page number, **← Insert left** and **Insert right →**, and Cancel.
 */
@Composable
fun ColumnScope.KneeboardPrintWindow(onClose: () -> Unit) {
    val link by MissionLink.state.collectAsState()
    val info by MissionLink.info.collectAsState()
    val linked = link is LinkState.Online
    val s = KneeboardPrintSession
    val scope = rememberCoroutineScope()
    val layer = rememberGraphicsLayer()
    // read again when the Mission section's mode changes on the PC (from any device): the Mission set follows it
    val modeNow = info?.mission?.mode
    LaunchedEffect(linked, modeNow) { if (linked && !s.busy) s.load() }

    if (!linked) {
        NotLinked(link)
        return
    }
    val st = s.state
    StatusLine(st, s.error, s.loading)
    s.browse?.let { b ->
        PictureChooser(b, Modifier.weight(1f).fillMaxWidth(), scope)
        return
    }
    // a half's Picture… runs in the window's scope: the table (and its cells' scopes) leave as the chooser opens
    val browse: (Int, Char) -> Unit = { n, side -> scope.launch { s.pictureFor(n, side) } }
    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
        val wide = maxWidth >= 560.dp
        // the preview is a whole 2:3 page under its two-line caption: as wide as the height allows, up to a third of
        // the window, so a bigger window shows a bigger page (and never less than the 230 dp it had)
        val previewW = minOf(maxWidth * 0.34f, maxOf(200.dp, (maxHeight - 40.dp) * (2f / 3f)))
        // a tall window (a tablet held upright): the table across the whole width, the preview under it
        val tall = wide && maxHeight > maxWidth * 1.2f
        val previewH = minOf(maxHeight * 0.3f, 360.dp)
        if (tall) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                PageTable(st, Modifier.weight(1f).fillMaxWidth(), browse)
                Preview(layer, Modifier.fillMaxWidth().height(previewH), across = true)
            }
        } else if (wide) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PageTable(st, Modifier.weight(1f).fillMaxHeight(), browse)
                Preview(layer, Modifier.width(previewW).fillMaxHeight())
            }
        } else {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                PageTable(st, Modifier.weight(1f).fillMaxWidth(), browse)
                Preview(layer, Modifier.fillMaxWidth().height(130.dp), across = true)
            }
        }
    }
    Warnings(st)
    Results()
    // (when BMS reads the pages, and the keys that turn them, are Print's tooltip and the guide's: not a footer)
    Buttons(st, layer, scope)
}

// ---------------------------------------------------------------- parts

@Composable
private fun NotLinked(link: LinkState) {
    Text(
        when (link) {
            is LinkState.Offline -> "Lost the link to BMS Companion on the PC (${link.host})."
            is LinkState.Connecting -> "Connecting to BMS Companion on the PC…"
            else -> "Printing to the cockpit kneeboard is done by BMS Companion on the PC, where Falcon BMS runs."
        },
        color = Hud.Text, fontSize = 14.sp, lineHeight = 19.sp,
    )
    Text(
        when (link) {
            is LinkState.Offline -> "The pages you chose are kept, and the window comes back with the link."
            else -> "Connect this device to it in Setup. The pages you choose are kept while the link is down."
        },
        color = Hud.TextDim, fontSize = 12.sp, lineHeight = 16.sp,
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Btn("Help", probe = "Print/Help") { PlannerGuide.open("print") }
        Spacer(Modifier.weight(1f))
        Btn("Print", enabled = false, primary = true, probe = "Print/Print") { }
    }
}

@Composable
private fun StatusLine(st: KbPrintState?, error: String?, loading: Boolean) {
    // one muted line (where the pages are, then the quiet notes; the whole of it is its tooltip and a tap's), and an
    // amber line only for what needs saying
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        val where = listOfNotNull(st?.theater?.trim()?.ifBlank { null }, st?.folder?.ifBlank { null }).joinToString(" · ")
        val s = KneeboardPrintSession
        val wdp = s.wdpMode(st)
        val notes = buildList {
            if (st?.bmsIn3d == true) add("Falcon BMS is in the cockpit now: a page printed now shows the next time you enter it.")
            if (st?.twin == true) add("The high-resolution copies (KoreaObj_HiRes) are written too.")
            if (!wdp && st != null && st.error == null && st.ezConfig == null && st.pages.isNotEmpty())
                add("EZBoards is not set up in BMS Companion, so no page is marked as one EZBoards writes; the Mission set leaves page 1 to it all the same.")
            // the card prints its own profile, the attack page the Planner's latest attack (AttackFocus): say when they differ
            val kinds = s.plan.values.toSet()
            if (KbKind.ATTACK in kinds && (KbKind.DATACARD_LEFT in kinds || KbKind.DATACARD_RIGHT in kinds))
                KneeboardExtraPages.cardAttackNotice()?.let { add(it) }
        }
        // WDP mode: quiet lines only — EZBoards is paused, so nothing here is its to write
        val quiet = buildList {
            s.relaid?.let { add(it) }
            if (wdp && st != null && st.error == null && st.pages.any { it.ezLeft || it.ezRight }) {
                val claimed = st.pages.flatMap { p -> listOfNotNull("${p.n} left".takeIf { p.ezLeft }, "${p.n} right".takeIf { p.ezRight }) }
                add("WDP mode: EZBoards is paused, so the pages it writes in EZBoards mode (${claimed.joinToString(", ")}) are the Planner's too.")
            }
            val made = s.madeIn
            if (st != null && s.planned && s.laidFor == null && made != null && made != s.mode(st))
                add("This plan was made in ${if (made == MissionMode.WDP) MissionMode.WDP_NAME else MissionMode.EZBOARDS_NAME}: Mission set lays it out for ${if (wdp) MissionMode.WDP_NAME else MissionMode.EZBOARDS_NAME}.")
        }
        val first = when {
            where.isNotEmpty() -> where
            loading -> "Reading the kneeboard pages on the PC…"
            else -> "The kneeboard pages on the PC"
        }
        OneLine((listOf(first) + quiet).joinToString("  ·  "), Hud.TextDim, "Print/Status")
        for ((i, n) in notes.withIndex()) OneLine(n, Hud.Amber, "Print/Note/$i")
        error?.let { Text(it, color = Hud.Red, fontSize = 12.sp, lineHeight = 15.sp) }
    }
}

/** A line of the window's small print: one line, the rest on a tap (and in its tooltip). */
@Composable
private fun OneLine(text: String, color: Color, probe: String) {
    var full by remember(text) { mutableStateOf(false) }
    Text(
        text, color = color, fontSize = fingerSp(11f), lineHeight = 14.sp, maxLines = if (full) 6 else 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().clickable { full = !full }.wdpTip("planner/$probe", text, touch = true),
    )
}

@Composable
private fun PageTable(st: KbPrintState?, modifier: Modifier, browse: (Int, Char) -> Unit) {
    val s = KneeboardPrintSession
    val pages = st?.pages.orEmpty()
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Page", Modifier.width(34.dp), color = Hud.TextFaint, fontSize = 10.sp)
            Text("Left knee", Modifier.weight(1f), color = Hud.TextFaint, fontSize = 10.sp)
            Text("Right knee", Modifier.weight(1f), color = Hud.TextFaint, fontSize = 10.sp)
        }
        if (pages.isEmpty()) {
            Text(
                if (s.loading) "Reading…" else "No kneeboard pages to show.",
                color = Hud.TextDim, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp),
            )
            return@Column
        }
        Hairline()
        LazyColumn(Modifier.fillMaxSize()) {
            items(pages, key = { it.n }) { slot -> PageRow(slot, browse) }
        }
    }
}

@Composable
private fun PageRow(slot: KbSlot, browse: (Int, Char) -> Unit) {
    val s = KneeboardPrintSession
    val usable = s.printable(slot)
    // a flat row, ruled off from the next (no card per page)
    Row(
        Modifier.fillMaxWidth()
            .drawBehind { drawLine(Hud.Outline.copy(alpha = 0.45f), androidx.compose.ui.geometry.Offset(0f, size.height - 0.5f), androidx.compose.ui.geometry.Offset(size.width, size.height - 0.5f), 1f) }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.width(30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${slot.n}", color = if (usable) Hud.Text else Hud.TextFaint, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, lineHeight = 16.sp)
            Text(slot.file.removeSuffix(".dds"), color = Hud.TextFaint, fontSize = 8.sp, lineHeight = 9.sp)
        }
        if (!usable) {
            Text(
                if (slot.missing) "No page file ${slot.file} in this theater: BMS Companion never makes one."
                else slot.problem ?: "This page cannot be printed.",
                color = Hud.TextDim, fontSize = 11.sp, lineHeight = 14.sp, modifier = Modifier.weight(1f).padding(start = 6.dp),
            )
        } else {
            HalfCell(slot, 'L', Modifier.weight(1f), browse)
            Spacer(Modifier.width(4.dp))
            HalfCell(slot, 'R', Modifier.weight(1f), browse)
        }
    }
}

/** [browse] opens Browse picture… for a half, in the window's own scope: the table leaves the screen while the chooser is up. */
@Composable
private fun HalfCell(slot: KbSlot, side: Char, modifier: Modifier, browse: (Int, Char) -> Unit) {
    val s = KneeboardPrintSession
    val kind = s.kind(slot.n, side)
    val selected = s.selN == slot.n && s.selSide == side
    // in WDP mode EZBoards does not run at PRINT: its claim is not marked (the status line says it is paused)
    val claimed = (if (side == 'L') slot.ezLeft else slot.ezRight) && !s.wdpMode()
    val owner = if (side == 'L') slot.left else slot.right
    val ownerKind = if (side == 'L') slot.leftKind else slot.rightKind
    var open by remember { mutableStateOf(false) }
    Row(
        modifier.clip(RoundedCornerShape(5.dp))
            .background(if (selected) Hud.Amber.copy(alpha = 0.10f) else Color.Transparent)
            .border(1.dp, if (selected) Hud.Amber.copy(alpha = 0.6f) else Color.Transparent, RoundedCornerShape(5.dp))
            .clickable(enabled = !s.busy) { s.selN = slot.n; s.selSide = side }
            .padding(2.dp)
            .plannerProbe("Print/Half/${slot.n}$side"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KbThumb(slot.n, side, slot.modified, Modifier.width(24.dp).aspectRatio(2f / 3f).plannerProbe("Print/Thumb/${slot.n}$side"))
        Spacer(Modifier.width(4.dp))
        Box(Modifier.weight(1f)) {
            val leave = kind == KbKind.LEAVE
            // what goes on the half (the choice) over who wrote what is on it now, in one box a finger's height
            Row(
                Modifier.fillMaxWidth().height(if (WdpTouch.device) 40.dp else 34.dp).clip(RoundedCornerShape(4.dp))
                    .background(if (leave) Hud.Surface3 else Hud.Amber.copy(alpha = 0.18f))
                    .clickable(enabled = !s.busy) { s.selN = slot.n; s.selSide = side; open = true }
                    .padding(horizontal = 6.dp)
                    .plannerProbe("Print/Pick/${slot.n}$side"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (kind == KbKind.PICTURE) KneeboardPictures.nameOf(s.picture(slot.n, side)).ifEmpty { "Picture" }
                        else KneeboardPages.of(kind)?.short ?: kind,
                        color = if (leave) Hud.TextDim else Hud.Amber, fontSize = fingerSp(11.5f), lineHeight = 14.sp,
                        fontWeight = if (leave) FontWeight.Normal else FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        if (claimed) "EZBoards writes it at PRINT" else "now: " + ownerText(owner, ownerKind),
                        color = if (claimed) Hud.Amber else Hud.TextFaint, fontSize = fingerSp(9.5f), lineHeight = 11.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = Hud.TextDim, modifier = Modifier.size(16.dp))
            }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    for (k in KneeboardPages.kinds) {
                        DropdownMenuItem(
                            text = { Text(k.label, fontSize = 13.sp, color = if (k.id == kind) Hud.Amber else Hud.Text) },
                            onClick = {
                                open = false
                                // Picture opens Browse picture… for this half: the picture goes on it with Insert
                                if (k.id == KbKind.PICTURE) browse(slot.n, side) else s.set(slot.n, side, k.id)
                            },
                            modifier = Modifier.plannerProbe("Print/Kind/${k.id}"),
                        )
                    }
                }
        }
    }
}

/** What is on a half page now, as the PC reads it from the file. */
@Composable
private fun KbThumb(n: Int, side: Char, modified: Long, modifier: Modifier, width: Int = 96) {
    val stamp = KneeboardPrintSession.stamp
    val img by produceState<ImageBitmap?>(null, n, side, modified, stamp, width) {
        value = MissionLink.fetchBytes(MissionLink.kbThumbPath(n, side, width))?.let { Repo.decodeBitmap(it) }?.asImageBitmap()
    }
    Box(modifier.clip(RoundedCornerShape(2.dp)).background(Color(0xFF30353C))) {
        img?.let { Image(it, contentDescription = "Page $n, ${KneeboardPages.knee(side)}, now", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) }
    }
}

private fun ownerText(owner: String, kind: String?): String = when (owner) {
    KbOwner.BMS -> "BMS's own page"
    KbOwner.EZBOARDS -> "EZBoards"
    KbOwner.HTMLBRIEF -> "html_brief"
    KbOwner.WDP -> "Weapon Delivery Planner"
    KbOwner.COMPANION -> "BMS Companion" + (kind?.let { ": " + (KneeboardPages.of(it)?.short ?: it) } ?: "")
    KbOwner.MISSING -> "No page file"
    else -> "Another tool"
}

/**
 * The half picked in the table, as it will be printed; a half left as it is shows what is on it now. While a print
 * runs it shows each half as it is drawn: this is where the page is captured ([KneeboardPageHost] records into [layer]).
 */
@Composable
private fun Preview(layer: GraphicsLayer, modifier: Modifier, across: Boolean = false) {
    val s = KneeboardPrintSession
    val at = s.printing
    val n = at?.first ?: s.selN
    val side = at?.second ?: s.selSide
    val kind = KneeboardPages.of(s.kind(n, side)) ?: KneeboardPages.LEAVE
    val slot = s.slot(n)
    val caption = @Composable {
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                "Page $n · ${KneeboardPages.knee(side)}  " + (
                    if (kind.draw == null) "Left as it is: " + ownerText((if (side == 'L') slot?.left else slot?.right) ?: KbOwner.OTHER, if (side == 'L') slot?.leftKind else slot?.rightKind)
                    else if (kind.id == KbKind.PICTURE) "Picture: " + KneeboardPictures.nameOf(s.picture(n, side)).ifEmpty { "none chosen" } + ", stretched to fill the half page"
                    else kind.label),
                color = if (kind.draw == null) Hud.TextDim else Hud.Amber, fontSize = fingerSp(11f), lineHeight = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            s.progress?.let { Text(it, color = Hud.Cyan, fontSize = 11.sp, lineHeight = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
    val page = @Composable { m: Modifier ->
        Box(m.clip(RoundedCornerShape(3.dp)).background(Color(0xFF30353C)).plannerProbe("Print/Preview"), contentAlignment = Alignment.Center) {
            if (kind.draw == null) KbThumb(n, side, slot?.modified ?: 0, Modifier.fillMaxSize(), width = 384)
            else KneeboardPageHost(kind, n, side, Modifier.fillMaxSize(), layer)
        }
    }
    if (across) {
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            page(Modifier.fillMaxHeight().aspectRatio(2f / 3f))
            Box(Modifier.weight(1f)) { caption() }
        }
    } else {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            caption()
            page(Modifier.fillMaxWidth().weight(1f, fill = false).aspectRatio(2f / 3f))
        }
    }
}

@Composable
private fun Warnings(st: KbPrintState?) {
    val s = KneeboardPrintSession
    val wdp = s.wdpMode(st)
    // in WDP mode EZBoards does not run at PRINT: nothing it claims is replaced
    val onEz = if (wdp) emptyList() else (1..16).flatMap { n ->
        val slot = s.slot(n) ?: return@flatMap emptyList()
        listOfNotNull(
            "${n} left".takeIf { slot.ezLeft && s.kind(n, 'L') != KbKind.LEAVE },
            "${n} right".takeIf { slot.ezRight && s.kind(n, 'R') != KbKind.LEAVE },
        )
    }
    if (onEz.isNotEmpty()) {
        OneLine("EZBoards writes page ${onEz.joinToString(", ")} at every PRINT: PRINT in BMS after this and it replaces what you print there.", Hud.Amber, "Print/OnEz")
    }
    if (st != null && st.error == null && st.pages.isNotEmpty() && s.missionPages(st).size < 2 && s.jobs().isEmpty()) {
        OneLine(
            if (wdp) "Fewer than two pages here can be printed: choose the pages yourself."
            else "Fewer than two pages here are free of EZBoards: choose the pages yourself.",
            Hud.TextDim, "Print/Few",
        )
    }
}

@Composable
private fun Results() {
    val s = KneeboardPrintSession
    val r = s.results
    if (r.isEmpty()) return
    val written = r.count { it.status == KbFileResult.WRITTEN }
    val same = r.count { it.status == KbFileResult.UNCHANGED }
    val refused = r.filter { it.status == KbFileResult.REFUSED }
    // flat, under a hairline: a line for the answer, and only the files that were not written under it
    var more by remember(r) { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().heightIn(max = if (more) 160.dp else 72.dp).clickable { more = !more }
            .drawBehind { drawLine(Hud.Outline.copy(alpha = 0.55f), androidx.compose.ui.geometry.Offset(0f, 0f), androidx.compose.ui.geometry.Offset(size.width, 0f), 1f) }
            .padding(top = 4.dp)
            .plannerProbe("Print/Results"),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        val summary = listOfNotNull(
            "$written written".takeIf { written > 0 },
            "$same unchanged".takeIf { same > 0 },
            "${refused.size} not written".takeIf { refused.isNotEmpty() },
        ).joinToString(", ")
        // the files that went, named on the same line when there are a few
        val went = r.filter { it.status != KbFileResult.REFUSED }
        val named = if (went.size in 1..4) " (" + went.joinToString(", ") { it.file + if (it.status == KbFileResult.UNCHANGED) " unchanged" else "" } + ")" else ""
        Text(
            "${s.resultsOf ?: "Done"}: $summary$named", color = if (refused.isEmpty()) Hud.Green else Hud.Amber, fontSize = fingerSp(11.5f),
            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (more) for (x in went.filter { it.reason != null }.take(2)) {
            Text("${x.file}: ${x.reason}", color = Hud.TextDim, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        for (x in refused.take(if (more) 6 else 2)) {
            Text("${x.file}: ${x.reason ?: "not written"}", color = Hud.Red, fontSize = 11.sp, lineHeight = 14.sp, maxLines = if (more) 3 else 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Buttons(st: KbPrintState?, layer: GraphicsLayer, scope: CoroutineScope) {
    val s = KneeboardPrintSession
    val ready = st != null && st.error == null && st.pages.isNotEmpty() && !s.busy
    val jobs = s.jobs()
    var backMenu by remember { mutableStateOf(false) }
    // one bar: the plan's buttons (sideways on a phone), and Print at its end
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Btn("Mission set", enabled = ready, probe = "Print/MissionSet") { s.missionSet() }
            Btn("Browse picture…", enabled = ready, probe = "Print/Browse") { scope.launch { s.browsePicture(s.selN, s.selSide) } }
            Btn("Test page", enabled = ready && s.printable(s.slot(s.selN)), probe = "Print/TestPage") {
                s.set(s.selN, 'L', KbKind.TEST); s.set(s.selN, 'R', KbKind.TEST)
            }
            Btn("Leave all", enabled = ready && jobs.isNotEmpty(), probe = "Print/LeaveAll") { s.leaveAll() }
            Box {
                Btn("Put BMS's page back", enabled = ready && st?.shipped == true, probe = "Print/PutBack") { backMenu = true }
                DropdownMenu(expanded = backMenu, onDismissRequest = { backMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Page ${s.selN} (both knees)", fontSize = 13.sp) },
                        onClick = { backMenu = false; scope.launch { s.putBack(s.selN) } },
                        modifier = Modifier.plannerProbe("Print/PutBackPage"),
                    )
                    DropdownMenuItem(
                        text = { Text("All 16 pages", fontSize = 13.sp) },
                        onClick = { backMenu = false; scope.launch { s.putBack(null) } },
                        modifier = Modifier.plannerProbe("Print/PutBackAll"),
                    )
                }
            }
            PlannerIconButton(Icons.Default.MenuBook, "Help", "Print/Help") { PlannerGuide.open("print") }
        }
        Spacer(Modifier.width(8.dp))
        Btn(
            when {
                s.busy -> "Printing…"
                jobs.isEmpty() -> "Print"
                else -> "Print ${jobs.size} page${if (jobs.size == 1) "" else "s"}"
            },
            enabled = ready && jobs.isNotEmpty() && Platform.encodePng != null,
            primary = true, probe = "Print/Print",
        ) { scope.launch { s.print(layer) } }
    }
}

/**
 * Browse picture…'s chooser, in place of the table (WDP's Preview group over its window): the picture as it will fill a
 * half page, the page it goes on, WDP's **← Insert left** and **Insert right →**, another picture, and Cancel, which
 * leaves the plan as it was.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PictureChooser(b: KneeboardPrintSession.PictureBrowse, modifier: Modifier, scope: CoroutineScope) {
    val s = KneeboardPrintSession
    val pic = KneeboardPictures.cached(b.path)
    val reading = pic == null || KneeboardPictures.isLoading(b.path)
    // a read cut short (the window closed while it ran) is started again when the chooser shows
    if (pic == null && !KneeboardPictures.isLoading(b.path)) LaunchedEffect(b.path) { KneeboardPictures.load(b.path) }
    val slot = s.slot(b.page)
    val usable = s.printable(slot)
    val canInsert = pic?.image != null && usable && !s.busy
    Column(modifier.plannerProbe("Print/Pic"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // one line for the file, one for what it is
        Text(
            "Browse picture  ·  " + b.path, color = Hud.Text, fontSize = fingerSp(12f), fontWeight = FontWeight.SemiBold, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.wdpTip("planner/Print/Pic/Path", b.path, touch = true),
        )
        val info = pic?.info
        when {
            pic?.error != null && !reading -> Text("${KneeboardPictures.nameOf(b.path)} cannot go on a page: ${pic.error}.", color = Hud.Red, fontSize = 12.sp, lineHeight = 15.sp)
            info != null && pic.image != null -> Text(
                "${info.format}, ${info.width} × ${info.height} px, stretched to fill the half page, as WDP does.",
                color = Hud.TextDim, fontSize = fingerSp(11f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            else -> Text("Reading the picture on the PC…", color = Hud.Cyan, fontSize = 11.sp)
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val wide = maxWidth >= 480.dp
            val preview = @Composable { m: Modifier ->
                Box(m.clip(RoundedCornerShape(3.dp)).background(Color.White).plannerProbe("Print/Pic/Preview"), contentAlignment = Alignment.Center) {
                    val img = pic?.image
                    if (img != null) Image(img, contentDescription = KneeboardPictures.nameOf(b.path), modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                    else Text(if (reading) "Reading…" else "No picture", color = Color(0xFF8A8A8A), fontSize = 12.sp)
                }
            }
            val controls = @Composable { m: Modifier ->
                Column(m, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Page", color = Hud.TextFaint, fontSize = 11.sp)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Btn("−", enabled = b.page > 1, probe = "Print/Pic/Prev") { b.page -= 1 }
                        Text("${b.page}", Modifier.width(34.dp), color = Hud.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        Btn("+", enabled = b.page < 16, probe = "Print/Pic/Next") { b.page += 1 }
                    }
                    Text(
                        when {
                            slot == null -> "Page ${b.page} is not known on the PC."
                            !usable -> if (slot.missing) "No page file ${slot.file} in this theater: BMS Companion never makes one." else slot.problem ?: "This page cannot be printed."
                            else -> "Planned now: left knee ${planned(b.page, 'L')}, right knee ${planned(b.page, 'R')}."
                        },
                        color = if (usable) Hud.TextDim else Hud.Amber, fontSize = 11.sp, lineHeight = 14.sp,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Btn("← Insert left", enabled = canInsert, primary = b.side != 'R', probe = "Print/Pic/InsertL") { s.insertPicture('L') }
                        Btn("Insert right →", enabled = canInsert, primary = b.side == 'R', probe = "Print/Pic/InsertR") { s.insertPicture('R') }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Btn("Another picture…", enabled = !s.busy, probe = "Print/Pic/Browse") { scope.launch { s.browsePicture(b.page, b.side, from = b.path) } }
                        Btn("Cancel", probe = "Print/Pic/Cancel") { s.cancelPicture() }
                    }
                }
            }
            if (wide) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    preview(Modifier.fillMaxHeight().aspectRatio(2f / 3f, matchHeightConstraintsFirst = true))
                    controls(Modifier.weight(1f))
                }
            } else {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    preview(Modifier.weight(1f).aspectRatio(2f / 3f, matchHeightConstraintsFirst = true))
                    controls(Modifier.fillMaxWidth())
                }
            }
        }
    }
}

/** What the plan puts on page [n]'s [side] half, in a few words ("DataCard L", "as it is", "photo.jpg"). */
private fun planned(n: Int, side: Char): String {
    val s = KneeboardPrintSession
    val k = s.kind(n, side)
    return when (k) {
        KbKind.LEAVE -> "as it is"
        KbKind.PICTURE -> KneeboardPictures.nameOf(s.picture(n, side)).ifEmpty { "a picture" }
        else -> KneeboardPages.of(k)?.short ?: k
    }
}

@Composable
private fun Btn(text: String, enabled: Boolean = true, primary: Boolean = false, probe: String, onClick: () -> Unit) {
    Box(
        Modifier.heightIn(min = toolHeight).clip(RoundedCornerShape(6.dp))
            .background(if (primary && enabled) Hud.Amber else Hud.Surface3)
            .plannerPress(enabled) { onClick() }
            .padding(horizontal = 11.dp, vertical = 4.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .plannerProbe(probe),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text, color = if (primary && enabled) Color(0xFF14171B) else Hud.Text, fontSize = fingerSp(13f),
            fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1,
        )
    }
}
