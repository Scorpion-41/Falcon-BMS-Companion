package com.bmscompanion.app.ui.screens.mission

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bmscompanion.app.data.mission.MissionData
import com.bmscompanion.app.data.mission.NoteBlock
import com.bmscompanion.app.data.mission.NoteRow
import com.bmscompanion.app.data.mission.Noteboard
import com.bmscompanion.app.ui.theme.Hud
import kotlin.math.roundToInt

/**
 * The inks a noteboard is drawn in: [PAPER] for the cockpit kneeboard (dark ink on white, as the app's other kneeboard
 * pages), [hud] for the VR board (the board's own dark skin and amber).
 */
class NoteInks(
    val page: Color, val ink: Color, val faint: Color, val rule: Color, val accent: Color, val box: Color, val band: Color,
) {
    companion object {
        val PAPER = NoteInks(
            Color.White, Color(0xFF111111), Color(0xFF5F656A), Color(0xFFBBBFC2), Color(0xFF111111), Color(0xFFF3F4F5), Color(0xFFEDEFF1),
        )

        /** The board's skin, read when drawn (Hud's colours follow the skin, so never kept in a value). */
        val hud: NoteInks
            get() = NoteInks(Hud.Bg, Hud.Text, Hud.TextDim, Hud.Outline, Hud.Amber, Hud.Amber.copy(alpha = 0.10f), Hud.Text.copy(alpha = 0.07f))
    }
}

/**
 * One noteboard as a page (1.4.1): its title and the flight it is for, a rule, then its blocks top to bottom — free
 * notes as typed, laser codes and figures in boxes set large (found by shape, as the VR boards' figures are), the comm
 * ladder and the steerpoints as small ruled tables from [mission] — and [foot] at the foot. What does not fit is cut
 * at the page's foot; a block with nothing in it is not drawn, so a board holding only an empty block of notes is a
 * blank page with its title.
 *
 * Drawn the same in the Upd Kneeboard print (450 x 675 units, [NoteInks.PAPER]), the editor's preview and the VR Notes
 * board ([NoteInks.hud]). [report] hears what was drawn, a line and the table rows, for the checks.
 */
@Composable
fun NoteboardSheet(
    board: Noteboard,
    index: Int,
    mission: MissionData?,
    inks: NoteInks,
    foot: String?,
    modifier: Modifier = Modifier,
    report: ((String, List<String>) -> Unit)? = null,
) {
    // the page's own type: the theme's 24-sp line height leaves a small row no line that fits (as Sheet does)
    CompositionLocalProvider(LocalTextStyle provides LocalTextStyle.current.copy(lineHeight = TextUnit.Unspecified, letterSpacing = TextUnit.Unspecified)) {
        val said = ArrayList<String>()
        val rows = ArrayList<String>()
        Column(modifier.fillMaxSize().background(inks.page).padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 5.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    board.title.trim().ifEmpty { if (index == 0) "NOTES" else "NOTES ${index + 1}" }.uppercase(),
                    color = inks.ink, fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.weight(1f))
                val flight = listOfNotNull(mission?.briefing?.overview?.flight?.trim()?.ifEmpty { null }, mission?.briefing?.overview?.mission?.trim()?.ifEmpty { null })
                if (flight.isNotEmpty()) Text(flight.joinToString(" · "), color = inks.faint, fontSize = 8.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box(Modifier.padding(top = 3.dp, bottom = 2.dp).fillMaxWidth().height(1.2.dp).background(inks.ink))
            Column(Modifier.fillMaxWidth().weight(1f).clipToBounds()) {
                var any = false
                for (b in board.blocks) {
                    val drew = NoteBlockView(b, mission, inks, rows)
                    if (drew != null) { any = true; said += drew }
                }
                if (!any) said += "blank"
            }
            if (foot != null) Text(foot, Modifier.fillMaxWidth().padding(top = 3.dp), color = inks.faint, fontSize = 7.sp, textAlign = TextAlign.Center)
        }
        if (report != null) SideEffect { report(said.joinToString("; "), rows.toList()) }
    }
}

/** A block's heading: its title (or its type's name) in capitals over a hairline. */
@Composable
private fun BlockHead(b: NoteBlock, inks: NoteInks) {
    Text(
        b.title.trim().ifEmpty { NoteBlock.typeName(b.type) }.uppercase(),
        Modifier.padding(top = 7.dp), color = inks.ink, fontSize = 9.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp, maxLines = 1,
    )
    Box(Modifier.padding(top = 1.dp, bottom = 3.dp).fillMaxWidth().height(0.8.dp).background(inks.rule))
}

/** Draws one block; answers what it drew in a few words, or null when it had nothing to draw. */
@Composable
private fun ColumnScope.NoteBlockView(b: NoteBlock, mission: MissionData?, inks: NoteInks, rows: MutableList<String>): String? {
    when (b.type) {
        NoteBlock.TEXT -> {
            val t = b.text.trimEnd()
            if (t.isBlank()) return null
            BlockHead(b, inks)
            Text(t, color = inks.ink, fontSize = 11.sp, lineHeight = 15.sp)
            return "notes ${t.length} characters"
        }
        NoteBlock.LASER, NoteBlock.FIGURES -> {
            // a row with no figure in it has nothing to say on the knee
            val filled = b.rows.filter { it.value.isNotBlank() }
            if (filled.isEmpty()) return null
            BlockHead(b, inks)
            val laser = b.type == NoteBlock.LASER
            val per = if (laser) 2 else 3
            for (line in filled.chunked(per)) {
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    for (r in line) FigureBox(r, laser, inks, Modifier.weight(1f))
                    repeat(per - line.size) { Spacer(Modifier.weight(1f)) }
                }
                line.forEach { rows += "${it.label} | ${it.value}" }
            }
            return (if (laser) "laser " else "figures ") + filled.joinToString(", ") { "${it.label.trim()} ${it.value.trim()}" }
        }
        NoteBlock.COMMS -> {
            val all = mission?.briefing?.comms.orEmpty().filter { it.agency.isNotBlank() }
            if (all.isEmpty()) return null
            val list = if (b.max > 0) all.take(b.max) else all
            BlockHead(b, inks)
            TableRow(listOf("AGENCY" to 1.6f, "CALLSIGN" to 1.3f, "UHF" to 1.3f, "VHF" to 1.1f), inks, head = true)
            list.forEachIndexed { i, c ->
                val cells = listOf(
                    c.agency to 1.6f, (c.callsign ?: "") to 1.3f,
                    listOfNotNull(c.uhfCh?.let { "CH $it" }, c.uhf?.takeIf { it.isNotBlank() }).joinToString(" · ") to 1.3f,
                    listOfNotNull(c.vhfCh?.let { "CH $it" }, c.vhf?.takeIf { it.isNotBlank() }).joinToString(" · ") to 1.1f,
                )
                TableRow(cells, inks, band = i % 2 == 1)
                rows += cells.joinToString(" | ") { it.first }
            }
            return "comm ladder ${list.size} rows"
        }
        NoteBlock.STEERPOINTS -> {
            val all = mission?.briefing?.steerpoints.orEmpty().filter { it.n > 0 }
            if (all.isEmpty()) return null
            val list = if (b.max > 0) all.take(b.max) else all
            BlockHead(b, inks)
            TableRow(listOf("#" to 0.4f, "WHAT" to 2.0f, "TIME" to 1.0f, "ALT" to 0.9f), inks, head = true)
            list.forEachIndexed { i, s ->
                val what = listOfNotNull(s.action?.trim()?.takeIf { it.isNotEmpty() && it != "--" }, s.desc?.trim()?.takeIf { it.isNotEmpty() && it != "--" }).distinct().joinToString(" · ")
                val cells = listOf("${s.n}" to 0.4f, what to 2.0f, (s.time ?: "") to 1.0f, (s.alt ?: "") to 0.9f)
                TableRow(cells, inks, band = i % 2 == 1)
                rows += cells.joinToString(" | ") { it.first }
            }
            return "steerpoints ${list.size} rows"
        }
        NoteBlock.FUEL -> {
            // the fuel plan as the Mission section's Fuel plan card works it out (MissionFuelPlan.kt), with this
            // device's settings; a print waits for the airports and the jet's load
            val (plan, ready) = rememberFuelPlanFor(mission)
            if (!ready) { com.bmscompanion.app.ui.screens.wdp.KneeboardPages.PageLoading(); return null }
            if (plan == null) return null
            BlockHead(b, inks)
            val play = plan.playLb
            val figures = listOfNotNull(
                NoteRow("Bingo", com.bmscompanion.app.data.mission.FuelPlan.lb(plan.bingoLb.toDouble())),
                NoteRow("Joker", com.bmscompanion.app.data.mission.FuelPlan.lb(plan.jokerLb.toDouble())),
                play?.let { NoteRow("Play" + (plan.targetAt?.let { t -> " STPT $t" } ?: ""), if (it <= 0) "none" else com.bmscompanion.app.data.mission.FuelPlan.oneDecimal(plan.playAbMin!!) + " min AB") },
            )
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                for (r in figures) FigureBox(r, false, inks, Modifier.weight(1f))
                repeat(3 - figures.size) { Spacer(Modifier.weight(1f)) }
            }
            figures.forEach { rows += "${it.label} | ${it.value}" }
            val list = if (b.max > 0) plan.rows.take(b.max) else plan.rows
            TableRow(listOf("#" to 0.4f, "WHAT" to 1.6f, "TO GO" to 0.8f, "MIN" to 0.9f, "EXP" to 0.9f), inks, head = true)
            list.forEachIndexed { i, r ->
                val cells = listOf(
                    "${r.n}" to 0.4f, (r.label + if (r.n == plan.bingoAt) " · BINGO" else "") to 1.6f,
                    "${r.toGoNm.roundToInt()}" to 0.8f, com.bmscompanion.app.data.mission.FuelPlan.lb(r.minLb) to 0.9f,
                    (r.expectedLb?.let { com.bmscompanion.app.data.mission.FuelPlan.lb(it) } ?: "") to 0.9f,
                )
                TableRow(cells, inks, band = i % 2 == 1)
                rows += cells.joinToString(" | ") { it.first }
            }
            Text(
                "Minimum at the recovery ${com.bmscompanion.app.data.mission.FuelPlan.lb(plan.recoveryLb)} lb (${if (plan.settings.imc) "IMC" else "VMC"}). Estimate: lb per nm along the route.",
                color = inks.faint, fontSize = 7.sp, modifier = Modifier.padding(top = 2.dp),
            )
            return "fuel plan bingo ${plan.bingoLb} joker ${plan.jokerLb}, ${list.size} rows"
        }
    }
    return null
}

/** A figure in a box of its own: its label small over it, the figure large (a laser code larger still, in fixed digits). */
@Composable
private fun FigureBox(r: NoteRow, laser: Boolean, inks: NoteInks, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(3.dp)).background(inks.box).border(0.8.dp, inks.rule, RoundedCornerShape(3.dp))
            .padding(horizontal = 6.dp, vertical = 3.dp),
    ) {
        Text(r.label.trim().ifEmpty { " " }.uppercase(), color = inks.faint, fontSize = 7.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            r.value.trim(), color = inks.ink, fontSize = if (laser) 20.sp else if (r.value.length > 9) 11.sp else 14.sp, fontWeight = FontWeight.Bold,
            fontFamily = if (laser) FontFamily.Monospace else FontFamily.Default, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A row of a small ruled table, cells at their weights; [head] for the column names. */
@Composable
private fun TableRow(cells: List<Pair<String, Float>>, inks: NoteInks, head: Boolean = false, band: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().height(if (head) 12.dp else 13.dp).background(if (band) inks.band else Color.Transparent),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for ((text, w) in cells) Text(
            text, Modifier.weight(w).padding(horizontal = 2.dp),
            color = if (head) inks.faint else inks.ink, fontSize = if (head) 7.sp else 8.5.sp,
            fontWeight = if (head) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis, softWrap = false,
        )
    }
    if (head) Box(Modifier.fillMaxWidth().height(0.8.dp).background(inks.rule))
}
