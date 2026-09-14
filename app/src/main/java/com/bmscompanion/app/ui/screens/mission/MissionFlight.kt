package com.bmscompanion.app.ui.screens.mission

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bmscompanion.app.data.mission.Live
import com.bmscompanion.app.data.mission.MissionLink
import com.bmscompanion.app.ui.components.Masonry
import com.bmscompanion.app.ui.components.SectionCard
import com.bmscompanion.app.ui.components.safeText
import com.bmscompanion.app.ui.theme.Hud
import com.bmscompanion.app.ui.theme.LocalExtra
import com.bmscompanion.app.ui.theme.Mono
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun MissionFlightPane(env: MissionEnv) {
    val live by MissionLink.live.collectAsState()
    val contacts by MissionLink.contacts.collectAsState()
    val info by MissionLink.info.collectAsState()
    val l = live
    if (l == null || !l.flying) {
        PaneEmpty(
            "Not flying",
            if (info?.bms?.running == true) "Live flight data appears once you are in the 3D world." else "Start Falcon BMS and the bridge on your PC.",
        )
        return
    }
    val ctcs = contacts?.contacts.orEmpty()
    val bull = bullseye(l, ctcs)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        // Ownship table spans the full width (its groups sit side by side on tablets); instruments below.
        FlightTiles(l, bull, compact = false)
        Spacer(Modifier.height(12.dp))
        Masonry(minColumn = 380.dp, maxColumns = 3) {
            SectionCard("RWR", accent = Hud.Green, trailing = { Text("${l.rwr.size} emitters", fontSize = 11.sp, color = Hud.TextDim) }) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    RwrScope(l, Modifier.widthIn(max = 360.dp).fillMaxWidth().aspectRatio(1f))
                }
                RwrList(l)
            }
            SectionCard("DED", accent = Hud.Green) { DedPanel(l) }
            PictureCard(ctcs, ownship(l), l.hdgTrue, bull, onPick = {})
        }
    }
}

@Composable
fun FlightTiles(live: Live?, bull: Pair<Double, Double>?, compact: Boolean) {
    val l = live?.takeIf { it.flying }
    SectionCard("Ownship", accent = Hud.Amber, trailing = { l?.aircraft?.let { Text(it, fontSize = 11.sp, color = Hud.TextDim) } }) {
        if (l == null) {
            Text("Not in 3D", color = Hud.TextDim, style = MaterialTheme.typography.bodySmall)
            return@SectionCard
        }
        val fuelLow = l.bingo > 0 && l.fuelTotal <= l.bingo
        val groups = buildList {
            add(
                "FLIGHT" to listOfNotNull(
                    FlightRow("Altitude", "%,d".format(Locale.US, l.altFt.toInt()), "ft"),
                    FlightRow("Airspeed", "${l.kias.toInt()}", "KIAS"),
                    FlightRow("Mach", "%.2f".format(Locale.US, l.mach), null),
                    FlightRow("Heading", "%03d°".format(Locale.US, l.hdgMag.toInt()), "mag"),
                    FlightRow("Ground speed", "${l.gsKts.toInt()}", "kt"),
                    if (!compact) FlightRow("Vertical speed", "%+,d".format(Locale.US, l.vviFpm.toInt()), "fpm") else null,
                    if (!compact) FlightRow("G / AOA", "%.1f".format(Locale.US, l.gLoad), "%.1f°".format(Locale.US, l.aoa)) else null,
                ),
            )
            add(
                "FUEL & COUNTERMEASURES" to listOfNotNull(
                    FlightRow("Fuel total", "%,d".format(Locale.US, l.fuelTotal.toInt()), "lb", if (fuelLow) Hud.Red else Hud.Text),
                    if (l.bingo > 0) FlightRow("Bingo", "%,d".format(Locale.US, l.bingo.toInt()), "lb", Hud.TextDim) else null,
                    if (!compact) FlightRow("Internal / ext", "%,d / %,d".format(Locale.US, l.fuelInternal.toInt(), l.fuelExternal.toInt()), "lb") else null,
                    FlightRow("Fuel flow", "%,d".format(Locale.US, l.fuelFlow.toInt()), "pph"),
                    FlightRow("Chaff", "${l.chaff}", null, if (l.chaff <= 10) Hud.Amber else Hud.Text),
                    FlightRow("Flares", "${l.flares}", null, if (l.flares <= 5) Hud.Amber else Hud.Text),
                ),
            )
            add(
                "NAV & RADIO" to listOfNotNull(
                    bull?.let { FlightRow("Bullseye", bra(it.first, it.second, l.x, l.y), "you", Hud.Cyan) },
                    l.tacan?.let { FlightRow("TACAN", it, l.beaconNm?.takeIf { d -> d > 0 }?.let { d -> "%03d° %.0fnm".format(Locale.US, (l.beaconBrg ?: 0.0).toInt(), d) }) },
                    if (l.uhfFreq > 0) FlightRow("UHF", "%.3f".format(Locale.US, l.uhfFreq / 1000.0), "ch ${l.uhfPreset}") else null,
                    if (!compact && l.timeSec > 0) FlightRow("Time", zulu(l.timeSec), null) else null,
                ),
            )
        }.filter { it.second.isNotEmpty() }

        if (compact) {
            // map side panel: one dense two-column table so the picture and steerpoints stay visible
            CompactFlightTable(groups.flatMap { it.second }.filter { it.label != "Mach" })
        } else BoxWithConstraints {
            // side-by-side groups when there is room, stacked otherwise
            val cols = when { maxWidth >= 760.dp -> 3; maxWidth >= 520.dp -> 2; else -> 1 }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                groups.chunkedInto(cols).forEach { colGroups ->
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        colGroups.forEach { (title, rows) -> FlightTable(title, rows) }
                    }
                }
            }
        }
        if (l.fuelTotal > 0) {
            Spacer(Modifier.height(10.dp))
            val maxFuel = maxOf(l.fuelTotal, 7200.0 + l.fuelExternal)
            LinearProgressIndicator(
                progress = { (l.fuelTotal / maxFuel).toFloat().coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                color = if (fuelLow) Hud.Red else Hud.Green, trackColor = Hud.Surface3,
            )
        }
    }
}

private data class FlightRow(val label: String, val value: String, val unit: String?, val color: Color = Hud.Text)

/** Splits groups into [n] columns while keeping their order. */
private fun <T> List<T>.chunkedInto(n: Int): List<List<T>> = if (n <= 1) listOf(this) else List(n) { i -> filterIndexed { idx, _ -> idx % n == i } }.filter { it.isNotEmpty() }

@Composable
private fun CompactFlightTable(rows: List<FlightRow>) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).border(1.dp, Hud.Outline.copy(alpha = 0.5f), RoundedCornerShape(8.dp))) {
        rows.chunked(2).forEachIndexed { i, pair ->
            Row(Modifier.fillMaxWidth().background(if (i % 2 == 0) Hud.Surface2 else Color.Transparent).padding(horizontal = 8.dp, vertical = 5.dp)) {
                pair.forEach { r ->
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Text(r.label.uppercase(Locale.US), fontSize = 10.sp, color = Hud.TextDim, maxLines = 1, modifier = Modifier.weight(1f))
                        Text(r.value, style = LocalExtra.current.monoSmall.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold), color = r.color, maxLines = 1, modifier = Modifier.padding(end = 8.dp))
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** Instrument-style table: label left, value right-aligned, unit in a narrow column. */
@Composable
private fun FlightTable(title: String, rows: List<FlightRow>) {
    Column {
        Text(title, style = LocalExtra.current.overline, color = Hud.TextFaint, modifier = Modifier.padding(bottom = 4.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).border(1.dp, Hud.Outline.copy(alpha = 0.5f), RoundedCornerShape(8.dp))) {
            rows.forEachIndexed { i, r ->
                Row(
                    Modifier.fillMaxWidth().background(if (i % 2 == 0) Hud.Surface2 else Color.Transparent).padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(r.label, fontSize = 13.sp, color = Hud.TextDim, modifier = Modifier.weight(1f), maxLines = 1)
                    Text(r.value, style = LocalExtra.current.mono.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold), color = r.color, maxLines = 1)
                    Text(r.unit ?: "", fontSize = 11.sp, color = Hud.TextFaint, maxLines = 1, modifier = Modifier.padding(start = 6.dp).widthIn(min = 38.dp))
                }
            }
        }
    }
}

/** ALR-56M style threat display. Symbols sit at their bearing relative to the nose; radius follows BMS lethality. */
@Composable
fun RwrScope(l: Live, modifier: Modifier) {
    val tm = rememberTextMeasurer()
    var flash by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { while (true) { delay(250); flash = !flash } }
    val green = Color(0xFF6CFF8E)
    Canvas(modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xFF020805))) {
        val c = center
        val r = size.minDimension / 2 * 0.94f
        drawCircle(green.copy(alpha = 0.35f), r, c, style = Stroke(2f))
        drawCircle(green.copy(alpha = 0.25f), r * 0.5f, c, style = Stroke(1.5f))
        drawCircle(green.copy(alpha = 0.18f), r * 0.2f, c, style = Stroke(1.5f))
        for (a in 0 until 360 step 30) {
            val rad = Math.toRadians(a.toDouble())
            val inner = if (a % 90 == 0) r * 0.9f else r * 0.95f
            drawLine(green.copy(alpha = 0.4f), c + Offset((sin(rad) * inner).toFloat(), (-cos(rad) * inner).toFloat()), c + Offset((sin(rad) * r).toFloat(), (-cos(rad) * r).toFloat()), 2f)
        }
        // own aircraft cross
        drawLine(green.copy(alpha = 0.5f), c - Offset(0f, 10f), c + Offset(0f, 10f), 2f)
        drawLine(green.copy(alpha = 0.5f), c - Offset(8f, 2f), c + Offset(8f, -2f), 2f)
        val base = TextStyle(color = green, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = (r / 11 / density).sp)
        l.rwr.forEach { e ->
            val rel = Math.toRadians(e.brg - l.hdgTrue)
            val rr = r * e.lethality.toFloat().coerceIn(0.25f, 0.8f) / 0.8f * 0.85f
            val p = c + Offset((sin(rel) * rr).toFloat(), (-cos(rel) * rr).toFloat())
            val col = if (e.launch || e.lock) Color(0xFFFF6B6B) else green
            val big = e.new && flash
            val style = base.copy(color = col, fontSize = base.fontSize * if (big) 1.35f else 1f)
            when (rwrGlyph(e.sym)) {
                "adv", "basic" -> {
                    val s = r / 14 * if (big) 1.35f else 1f
                    val hat = Path().apply { moveTo(p.x - s, p.y + s * 0.3f); lineTo(p.x, p.y - s * 0.7f); lineTo(p.x + s, p.y + s * 0.3f) }
                    drawPath(hat, col, style = Stroke(3f, cap = StrokeCap.Round))
                    if (e.sym == 2) drawLine(col, Offset(p.x, p.y - s * 0.7f), Offset(p.x, p.y + s * 0.6f), 3f)
                }
                "naval" -> {
                    val s = r / 16
                    drawRect(col, p - Offset(s, s * 0.5f), androidx.compose.ui.geometry.Size(s * 2, s), style = Stroke(3f))
                }
                else -> {
                    val txt = rwrText(e.sym) ?: "U"
                    val layout = tm.measure(txt, style)
                    safeText(tm, txt, p - Offset(layout.size.width / 2f, layout.size.height / 2f), style)
                }
            }
            val ring = r / 11
            if (e.selected) {
                val d = Path().apply { moveTo(p.x, p.y - ring * 1.4f); lineTo(p.x + ring * 1.4f, p.y); lineTo(p.x, p.y + ring * 1.4f); lineTo(p.x - ring * 1.4f, p.y); close() }
                drawPath(d, col, style = Stroke(2.5f))
            }
            if (e.launch && flash) drawCircle(col, ring * 1.5f, p, style = Stroke(3f))
            else if (e.lock) drawCircle(col, ring * 1.5f, p, style = Stroke(2.5f))
        }
    }
}

@Composable
private fun RwrList(l: Live) {
    if (l.rwr.isEmpty()) {
        Text("Scope clean", color = Hud.Green, style = LocalExtra.current.monoSmall, modifier = Modifier.padding(top = 8.dp))
        return
    }
    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        l.rwr.sortedByDescending { it.lethality }.take(8).forEach { e ->
            val rel = ((e.brg - l.hdgTrue) % 360 + 360) % 360
            val clock = ((rel + 15) / 30).toInt().let { if (it == 0) 12 else it }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text((rwrText(e.sym) ?: "^").padEnd(3), style = LocalExtra.current.monoSmall, color = Hud.Green)
                Text(rwrName(e.sym), fontSize = 12.sp, modifier = Modifier.weight(1f))
                Text("$clock o'clock", style = LocalExtra.current.monoSmall, color = Hud.TextDim)
                val state = when { e.launch -> "LAUNCH"; e.lock -> "LOCK"; e.new -> "NEW"; else -> null }
                if (state != null) Text("  $state", style = LocalExtra.current.monoSmall, color = if (e.new && !e.lock && !e.launch) Hud.Amber else Hud.Red)
            }
        }
    }
}

@Composable
fun DedPanel(l: Live) {
    val lines = l.ded.takeIf { it.any { s -> s.isNotBlank() } }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0xFF030A04)).border(1.dp, Hud.Green.copy(alpha = 0.3f), RoundedCornerShape(8.dp)).padding(10.dp),
    ) {
        (lines ?: listOf("", "   DED NOT AVAILABLE", "")).forEach { s ->
            Text(s.ifEmpty { " " }, fontFamily = Mono, fontSize = 15.sp, color = Color(0xFF7CFF9A), maxLines = 1, softWrap = false)
        }
    }
}

