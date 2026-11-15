package com.bmscompanion.app.ui.screens.wdp

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.PostAdd
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bmscompanion.app.data.mission.KbKind
import com.bmscompanion.app.data.mission.MergedMission
import com.bmscompanion.app.data.mission.MissionData
import com.bmscompanion.app.data.mission.MissionLink
import com.bmscompanion.app.data.mission.PlanMerge
import com.bmscompanion.app.ui.screens.chartBitmap
import com.bmscompanion.app.ui.screens.isKneeboardPage
import com.bmscompanion.app.ui.screens.mission.radioSettings
import kotlinx.serialization.Serializable

/**
 * Two more Upd Kneeboard pages (1.4.2), in both modes, each one knee's half page in [KneeboardPages]' 450 x 675 units:
 * - **Radio presets** ([KbKind.RADIO]): what Mission → Comms shows — the cartridge's UHF and VHF presets (the preset
 *   each radio is tuned to after a DTC load in the band), the TACAN and ILS the cartridge sets, and the briefing's comm
 *   ladder — from the mission the Mission section shows (the populated one in WDP mode), else BMS's own files.
 * - **Chart** ([KbKind.CHART]): one page of a bundled instrument chart, put on a half by the chart viewer's **Add to
 *   kneeboard** ([ChartToKneeboard]); the half's chart is [KneeboardPrintSession.charts]. Drawn fitted to the half, a
 *   landscape page turned a quarter when that makes it markedly larger.
 */
object KneeboardMorePages {
    val kinds: List<KbPageKind> = listOf(
        KbPageKind(KbKind.RADIO, "Radio presets (as Mission → Comms shows them)", "Radio presets") { RadioPresetsPage(it) },
        KbPageKind(KbKind.CHART, "Chart page (Add to kneeboard on a chart)", "Chart") { ChartPage(it) },
    )

    val ids: Set<String> get() = kinds.map { it.id }.toSet()

    /** The chart pages' pictures, by their file under the app's assets (a few at most: a page is large). */
    internal val chartImages = mutableStateMapOf<String, ImageBitmap>()
    internal val chartFailed = mutableStateMapOf<String, Boolean>()

    suspend fun loadChart(file: String) {
        if (chartImages.containsKey(file) || chartFailed[file] == true) return
        val b = try { chartBitmap(file) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }
        if (b == null) { chartFailed[file] = true; return }
        if (chartImages.size >= 6) chartImages.keys.firstOrNull { k -> KneeboardPrintSession.charts.values.none { it.file == k } }?.let { chartImages.remove(it) }
        chartImages[file] = b.asImageBitmap()
    }

    /** What the page [kind] draws, loaded before it is captured: every chart the plan holds. */
    suspend fun preload(kind: String) {
        if (kind == KbKind.CHART) for (c in KneeboardPrintSession.charts.values.toList()) loadChart(c.file)
    }

    /**
     * The mission the Radio presets page reads, as the Comms page does: the Mission section's ([MissionLink.mission]: the
     * populated snapshot in WDP mode), else BMS's own files when that one has no comm data yet (WDP mode before Populate).
     */
    internal fun radioMission(): MissionData? {
        fun has(d: MissionData?) = d != null && (d.briefing?.comms?.isNotEmpty() == true || d.dtc?.uhf?.isNotEmpty() == true || d.dtc?.vhf?.isNotEmpty() == true)
        val m = MissionLink.mission.value
        if (has(m)) return m
        return MissionLink.bmsFiles.value?.takeIf { has(it) } ?: m
    }
}

/** A chart page a half shows ([KbKind.CHART]): its file under the app's assets and the words for it ("Osan AB · ILS RWY 09R, page 2 of 3"). */
@Serializable
data class KbChartPick(val file: String = "", val title: String = "")

/**
 * The chart viewer's **Add to kneeboard** (1.4.2, Reference → Airfields → a chart): Upd Kneeboard opens **over the
 * chart viewer** ([ChartKneeboardHost]) on its chart chooser ([KneeboardPrintSession.chartBrowse]): the page as it
 * will fill a half, a page number (the next half with nothing planned to begin with, so a second chart never takes
 * the first one's place unless the pilot picks that page), ← Insert left / Insert right →, then the page table and
 * Print. Each half keeps its own chart ([KneeboardPrintSession.charts]).
 */
object ChartToKneeboard {
    /** A bundled chart page; never one of the html_brief kneeboard's own pages. */
    fun can(file: String) = file.isNotBlank() && !isKneeboardPage(file)

    /** Upd Kneeboard is open over the chart viewer (not over Mission → Kneeboards) */
    var overViewer by mutableStateOf(false)

    fun add(file: String, title: String) {
        if (!can(file)) return
        KneeboardPrintSession.chartBrowse = KneeboardPrintSession.ChartBrowse(KbChartPick(file, title))
        overViewer = true
        // the chart viewer is no page of the Mission section's: the other devices do not open the window in theirs
        PlannerWindows.show(PlannerWindow.PRINT, share = false)
    }
}

/**
 * Upd Kneeboard drawn over the chart viewer (both copies of `Charts.kt`), while [ChartToKneeboard.add] has it open
 * there. Leaving the viewer closes it.
 */
@Composable
fun ChartKneeboardHost() {
    val w = PlannerWindows.open
    val over = ChartToKneeboard.overViewer
    LaunchedEffect(w) { if (w != PlannerWindow.PRINT) ChartToKneeboard.overViewer = false }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            if (ChartToKneeboard.overViewer) {
                ChartToKneeboard.overViewer = false
                KneeboardPrintSession.chartBrowse = null
                if (PlannerWindows.open == PlannerWindow.PRINT) PlannerWindows.close()
            }
        }
    }
    if (over && w == PlannerWindow.PRINT) PlannerWindowHost(open = w)
}

/**
 * The chart viewer's **Add to kneeboard** in its title bar (both copies of `Charts.kt`): [file] is the page on show,
 * [title] its words; Upd Kneeboard opens over the viewer on the chart chooser ([ChartToKneeboard]).
 * Not on a VR board, and not for the html_brief kneeboard's own pages.
 */
@Composable
fun AddChartToKneeboardButton(file: String, title: String) {
    if (com.bmscompanion.app.ui.Kneeboard.on || !ChartToKneeboard.can(file)) return
    Row(
        Modifier.padding(end = 4.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            .border(1.dp, com.bmscompanion.app.ui.theme.Hud.Amber.copy(alpha = 0.55f), androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            .clickable { ChartToKneeboard.add(file, title) }
            .padding(horizontal = 8.dp, vertical = 5.dp).plannerProbe("Chart/AddToKneeboard"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.Icon(
            androidx.compose.material.icons.Icons.Default.PostAdd, contentDescription = null,
            tint = com.bmscompanion.app.ui.theme.Hud.Amber, modifier = Modifier.padding(end = 4.dp).height(18.dp).width(18.dp),
        )
        Text("Add to kneeboard", color = com.bmscompanion.app.ui.theme.Hud.Amber, fontSize = 12.sp, maxLines = 1)
    }
}

// ==================================================================================================== paper

private val INK = Color(0xFF111111)
private val FAINT = Color(0xFF666B70)
private val RULE = Color(0xFFBBBFC2)
private val BAND = Color(0xFFF0F1F2)
private val TUNED = Color(0xFFFFE9A8)
private val ROW_H = 12.dp
private val CELL = 7.6.sp

private class RCol(val title: String, val width: Dp?, val mono: Boolean = false)

@Composable
private fun RCells(cols: List<RCol>, cells: List<String>, head: Boolean = false, band: Boolean = false, tuned: Boolean = false, into: MutableList<String>? = null) {
    into?.add(cells.joinToString(" | "))
    Row(
        Modifier.fillMaxWidth().height(ROW_H).background(if (tuned) TUNED else if (band) BAND else Color.Transparent),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        cols.forEachIndexed { i, c ->
            val m = if (c.width != null) Modifier.width(c.width) else Modifier.weight(1f)
            Text(
                cells.getOrElse(i) { "" }, m.padding(horizontal = 2.dp),
                color = if (head) FAINT else INK, fontSize = if (head) 7.sp else CELL,
                fontWeight = if (head) FontWeight.SemiBold else if (tuned) FontWeight.Bold else FontWeight.Normal,
                fontFamily = if (c.mono && !head) FontFamily.Monospace else FontFamily.Default,
                maxLines = 1, overflow = TextOverflow.Ellipsis, softWrap = false,
            )
        }
    }
    if (head) Box(Modifier.fillMaxWidth().height(0.8.dp).background(RULE))
}

@Composable
private fun RHead(title: String, note: String? = null) {
    Row(Modifier.fillMaxWidth().height(20.dp).padding(top = 5.dp), verticalAlignment = Alignment.Bottom) {
        Text(title, color = INK, fontSize = 9.5.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        if (note != null) Text("  $note", color = FAINT, fontSize = 7.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun chan(n: Int?) = n?.takeIf { it > 0 }?.toString().orEmpty()

/**
 * The Radio presets page: UHF and VHF side by side (each preset's number, frequency and the cartridge's comment; the
 * preset tuned after a load banded), the cartridge's TACAN and ILS, then the comm ladder as many rows as fit.
 */
@Composable
private fun RadioPresetsPage(scope: KbPageScope) {
    val data = KneeboardMorePages.radioMission()
    val merged: MergedMission = try { PlanMerge.merge(data, null) } catch (e: Exception) { MergedMission() }
    val b = merged.briefing
    val uhf = merged.presets.filter { it.band == "UHF" }.sortedBy { it.ch }
    val vhf = merged.presets.filter { it.band == "VHF" }.sortedBy { it.ch }
    val comm = merged.plan?.dtc?.comm ?: merged.dtc?.comm
    // the preset each radio is tuned to after a load, where the cartridge says one (0 or none: nothing banded)
    val tunedUhf = comm?.comm1?.takeIf { c -> c > 0 && uhf.any { it.ch == c } }
    val tunedVhf = comm?.comm2?.takeIf { c -> c > 0 && vhf.any { it.ch == c } }
    val ladder = b?.comms.orEmpty().filter { it.agency.isNotBlank() || !it.uhf.isNullOrBlank() || !it.vhf.isNullOrBlank() }
    val sub = listOfNotNull(b?.overview?.flight, b?.overview?.mission, if (merged.presets.isNotEmpty()) "presets from the cartridge" else null)
        .joinToString(" · ")
    KbSheet(scope, "RADIO PRESETS", sub, RouteInks.DAY) {
        if (merged.presets.isEmpty() && ladder.isEmpty()) {
            KbNote("No radio presets yet: save your DTC in BMS (and PRINT the briefing for the comm ladder).")
            KneeboardExtraPages.drawn[KbKind.RADIO] = "no presets, no ladder"
            KneeboardExtraPages.rows[KbKind.RADIO] = emptyList()
            return@KbSheet
        }
        val laid = ArrayList<String>()
        if (merged.presets.isNotEmpty()) {
            val cols = listOf(RCol("CH", 16.dp, mono = true), RCol("FREQ", 44.dp, mono = true), RCol("COMMENT", null))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((label, rows, tuned) in listOf(Triple("UHF", uhf, tunedUhf), Triple("VHF", vhf, tunedVhf))) {
                    Column(Modifier.weight(1f)) {
                        RHead(label, tuned?.let { "preset $it after a DTC load" })
                        RCells(cols, cols.map { it.title }, head = true)
                        if (rows.isEmpty()) Text("none", color = FAINT, fontSize = 7.sp)
                        for ((i, p) in rows.withIndex()) {
                            RCells(cols, listOf(chan(p.ch), p.freq, p.comment.orEmpty()), band = i % 2 == 1, tuned = tuned != null && p.ch == tuned, into = laid)
                        }
                    }
                }
            }
            val set = try { radioSettings(merged).filter { it.key == "tacan" || it.key == "ils" } } catch (e: Exception) { emptyList() }
            if (set.isNotEmpty()) Text(
                set.joinToString("   ·   ") { "${it.label} ${it.value}" }, Modifier.padding(top = 4.dp),
                color = INK, fontSize = 7.6.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (ladder.isNotEmpty()) BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val cols = listOf(
                RCol("AGENCY", null), RCol("CALLSIGN", 104.dp), RCol("UHF", 44.dp, mono = true), RCol("CH", 16.dp, mono = true),
                RCol("VHF", 44.dp, mono = true), RCol("CH", 16.dp, mono = true),
            )
            val fit = ((maxHeight - 20.dp - ROW_H - 12.dp) / ROW_H).toInt().coerceAtLeast(0)
            val shown = ladder.take(fit)
            Column(Modifier.fillMaxSize()) {
                RHead("Comm ladder", "${ladder.size} row" + (if (ladder.size == 1) "" else "s") + " · the briefing's")
                RCells(cols, cols.map { it.title }, head = true)
                for ((i, c) in shown.withIndex()) {
                    RCells(cols, listOf(c.agency, c.callsign.orEmpty(), c.uhf.orEmpty(), chan(c.uhfCh), c.vhf.orEmpty(), chan(c.vhfCh)), band = i % 2 == 1, into = laid)
                }
                if (ladder.size > shown.size) Text("… and ${ladder.size - shown.size} more on the briefing", color = FAINT, fontSize = 7.sp)
                Spacer(Modifier.weight(1f))
                if (tunedUhf != null || tunedVhf != null) Text("Banded: the preset each radio is tuned to after a DTC load.", color = FAINT, fontSize = 6.5.sp)
            }
        } else Spacer(Modifier.weight(1f))
        KneeboardExtraPages.rows[KbKind.RADIO] = laid
        KneeboardExtraPages.drawn[KbKind.RADIO] = "${uhf.size} UHF, ${vhf.size} VHF presets, ${ladder.size} ladder rows"
    }
}

/**
 * A chart page on a half: its name on one line, the page fitted under it on white, turned a quarter when the page is
 * landscape and turning it makes it at least 15 % larger (an approach plate printed sideways reads better turned than
 * shrunk to a strip).
 */
@Composable
private fun ChartPage(scope: KbPageScope) {
    val pick = KneeboardPrintSession.charts["${scope.n}${scope.side}"]
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.material3.LocalTextStyle provides androidx.compose.material3.LocalTextStyle.current.copy(
            lineHeight = TextUnit.Unspecified, letterSpacing = TextUnit.Unspecified,
        ),
    ) {
        Column(Modifier.fillMaxSize().background(Color.White).padding(start = 6.dp, end = 6.dp, top = 5.dp, bottom = 4.dp)) {
            Text(
                pick?.title?.ifBlank { null } ?: "Chart", Modifier.fillMaxWidth(), color = INK, fontSize = 8.sp,
                fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Box(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 2.dp).height(0.8.dp).background(INK))
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                if (pick == null) {
                    KbNote("No chart on this half: open a chart under Reference → Airfields and press Add to kneeboard.")
                    KneeboardExtraPages.drawn[KbKind.CHART] = "no chart"
                    return@Box
                }
                LaunchedEffect(pick.file) { KneeboardMorePages.loadChart(pick.file) }
                val img = KneeboardMorePages.chartImages[pick.file]
                when {
                    img != null -> {
                        Canvas(Modifier.fillMaxSize()) {
                            val w = size.width; val h = size.height
                            val iw = img.width.toFloat(); val ih = img.height.toFloat()
                            val fit0 = minOf(w / iw, h / ih)
                            val fit90 = minOf(w / ih, h / iw)
                            val turn = iw > ih && fit90 > fit0 * 1.15f
                            val k = if (turn) fit90 else fit0
                            val dw = iw * k; val dh = ih * k
                            val c = Offset(w / 2, h / 2)
                            rotate(if (turn) 90f else 0f, c) {
                                drawImage(
                                    img, dstOffset = IntOffset((c.x - dw / 2).toInt(), (c.y - dh / 2).toInt()),
                                    dstSize = IntSize(dw.toInt().coerceAtLeast(1), dh.toInt().coerceAtLeast(1)), filterQuality = FilterQuality.High,
                                )
                            }
                            KneeboardExtraPages.drawn[KbKind.CHART] = "chart ${pick.file} ${img.width}x${img.height}" + if (turn) " turned" else ""
                        }
                    }
                    KneeboardMorePages.chartFailed[pick.file] == true -> {
                        KbNote("This chart page could not be read on this device.")
                        KneeboardExtraPages.drawn[KbKind.CHART] = "chart unreadable"
                    }
                    else -> {
                        // the capture waits until the page is in (KneeboardPages.awaitDrawn)
                        KneeboardPages.PageLoading()
                        Text("Loading the chart…", color = FAINT, fontSize = 9.sp, textAlign = TextAlign.Center)
                    }
                }
            }
            Text(
                "Page ${scope.n} · ${KneeboardPages.knee(scope.side)} · BMS Companion", Modifier.fillMaxWidth(),
                color = FAINT, fontSize = 6.5.sp, textAlign = TextAlign.Center,
            )
        }
    }
}
