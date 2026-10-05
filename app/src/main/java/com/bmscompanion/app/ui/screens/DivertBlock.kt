package com.bmscompanion.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import com.bmscompanion.app.data.Airport
import com.bmscompanion.app.data.Platform
import com.bmscompanion.app.data.Theater
import com.bmscompanion.app.data.wdp.PopupCoords
import com.bmscompanion.app.data.wdp.WdpCoords
import com.bmscompanion.app.ui.components.Fmt
import com.bmscompanion.app.ui.components.SectionCard
import com.bmscompanion.app.ui.screens.wdp.WdpProbe
import com.bmscompanion.app.ui.theme.Hud
import com.bmscompanion.app.ui.theme.LocalExtra
import kotlinx.coroutines.delay

/**
 * A field's position as the F-16's DED STPT page takes it: "N 37°01.699'" / "E 127°53.116'", and the keys that type
 * it — the hemisphere first (ICP 2 N, 8 S, 6 E, 4 W; since 4.33 nothing else is accepted, Training Manual 4.38.1
 * p. 70), then the degrees and the minutes to a thousandth as plain digits, then ENTR: "N 3701699", "E 12753116".
 * [checked] is false where the theater has no BMS grid and the projection string stands in (the Falklands).
 */
data class DedCoords(val lat: String, val lng: String, val latKeys: String, val lngKeys: String, val checked: Boolean)

/**
 * [north]/[east] theater feet as [DedCoords], through the Planner's own conversion (`WdpCoords.coordData` +
 * `PopupCoords.feetToCoordsBoth`): BMS's own latitude and longitude, the figure its AIPs print as "BMS coord" and its
 * ACMI records. Null where the theater has no projection the app can honour.
 */
fun dedCoords(c: PopupCoords.CoordData?, north: Double, east: Double): DedCoords? {
    if (c == null || !c.enableNewTerrain) return null
    val s = runCatching { PopupCoords.feetToCoordsBoth(c, north, east) }.getOrNull() ?: return null
    if (s == PopupCoords.ZERO) return null
    val (n, e) = runCatching { PopupCoords.hemispheres(s) }.getOrNull() ?: return null
    fun part(h: String): Pair<String, String>? {
        if (h.length < 2) return null
        val hemi = h[0]
        val deg = h.substring(1).substringBefore(',', "")
        val min = h.substring(1).substringAfter(',', "")
        if (deg.isEmpty() || min.length != 6 || min[2] != '.') return null
        return "$hemi $deg°$min'" to "$hemi $deg${min.replace(".", "")}"
    }
    val lat = part(n) ?: return null
    val lng = part(e) ?: return null
    return DedCoords(lat.first, lng.first, lat.second, lng.second, checked = !c.tm.appProjection)
}

/** The airfield as one more steerpoint for a divert, in BMS's own figures. Null for a carrier or no projection. */
fun divertCoords(th: Theater, a: Airport): DedCoords? =
    if (divertIsShip(a)) null else dedCoords(WdpCoords.coordData(th), a.x, a.y)

/** A ship: no fixed position to give (by name, by type, or the Falklands' "Airbase" fleet — see [airportKind]). */
fun divertIsShip(a: Airport) = airportKind(a) == "Carrier" || a.type.equals("Carrier", ignoreCase = true)

/** A radio table column: label, figure and the short note after it on one line. */
private val RADIO_COL = 200.dp
private val RADIO_GAP = 10.dp
private val PAD_H = 12.dp

/**
 * Under an airfield's ground chart: what a pilot diverting there needs, in one compact card, **Info** — first
 * the coordinates as one tight block (LAT and LNG as BMS shows them — the DED's own format — and ELEV), a hairline,
 * then the radios as a dense table of label, figure and band: every frequency the field has, in the order a divert
 * uses them (Approach, the TACAN, Tower, Ground, the ILS, ATIS, then Base Ops and LSO). Both tables sit on the same
 * columns (as many as the chart's column has room for, at most three, each read downwards), so every label and
 * figure lines up. No DED keys and no note: the figures alone, readable at a glance in flight. A tap on a line copies its
 * figure. A carrier has no fixed position (it steams into wind), so it says so where the coordinates would be. The
 * radios are the page's only list of the field's frequencies (they replaced the old "Radio frequencies" card).
 */
@Composable
internal fun DivertBlock(th: Theater, a: Airport, modifier: Modifier = Modifier) {
    val ship = divertIsShip(a)
    val coords = remember(th, a.id) { divertCoords(th, a) }
    val clip = LocalClipboardManager.current
    var copied by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(copied) { if (copied != null) { delay(1500); copied = null } }
    val copy: (String, String) -> Unit = { key, text ->
        runCatching { Platform.copyText?.invoke(text) ?: clip.setText(AnnotatedString(text)) }
        copied = key
    }
    val radios = radioCells(a, ship)  // not remembered: its inks are the skin's live colours
    // The width is worked out here, once: both tables lay their columns on it, so the coordinates' labels and
    // figures stand exactly over the radios'. As many columns as fit (at most three), each read downwards.
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val cols = ((maxWidth - PAD_H * 2 + RADIO_GAP) / (RADIO_COL + RADIO_GAP)).toInt().coerceIn(1, 3)
        CompactBox("Info", Hud.Green, Modifier.fillMaxWidth().airfieldProbe("info")) {
            when {
                ship -> {
                    Text("Moves with the ship", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = Hud.Text)
                    Text(
                        "No fixed position: it steams into wind. Home on its TACAN, then call approach.",
                        fontSize = 10.5.sp, lineHeight = 13.sp, color = Hud.TextDim,
                        modifier = Modifier.padding(bottom = 2.dp),
                    )
                }
                coords == null -> Text(
                    "No latitude and longitude for this theater.", fontSize = 11.sp, color = Hud.TextDim,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
            // the coordinates, one tight block (LAT and LNG as BMS shows them, then ELEV), then a hairline, the radios
            val position = coordCells(a, coords)
            if (position.isNotEmpty()) FieldTable(position, cols, copied, copy)
            Box(Modifier.fillMaxWidth().padding(vertical = 6.dp).height(1.dp).background(Hud.Outline.copy(alpha = 0.55f)))
            FieldTable(radios, cols, copied, copy)
        }
    }
}

/**
 * Where a part of the airfield page was laid out ("airfield/chart", "airfield/info"), for the
 * headless checks (`--divertrender`), through the Planner's probe; nothing is recorded unless a check turned it on.
 */
internal fun Modifier.airfieldProbe(name: String): Modifier =
    if (!WdpProbe.on) this else onGloballyPositioned { WdpProbe.rects["airfield/$name"] = Rect(it.positionInRoot(), it.size.toSize()) }

/** A small card: the app's panel, with less padding and a smaller heading than a [SectionCard]. */
@Composable
private fun CompactBox(title: String, accent: Color, modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    if (Hud.onPaper) {
        SectionCard(title, modifier, accent = accent) { Column { content() } }
        return
    }
    Surface(
        modifier, color = Hud.Surface, shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, Hud.Outline.copy(alpha = 0.6f)),
    ) {
        Column(Modifier.padding(horizontal = PAD_H, vertical = 9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(width = 3.dp, height = 11.dp).background(accent, RoundedCornerShape(2.dp)))
                Spacer(Modifier.width(6.dp))
                Text(
                    title.uppercase(), style = LocalExtra.current.overline.copy(fontSize = 10.sp, letterSpacing = 1.2.sp),
                    color = accent, modifier = Modifier.weight(1f), maxLines = 1,
                )
                Text("tap to copy", fontSize = 10.sp, color = Hud.TextFaint, maxLines = 1)
            }
            Spacer(Modifier.height(5.dp))
            content()
        }
    }
}

/** LAT and LNG as BMS shows them (none for a ship or a theater without a projection), then the elevation. */
private fun coordCells(a: Airport, c: DedCoords?): List<InfoCell> = buildList {
    if (c != null) {
        add(InfoCell("lat", "LAT", c.lat, null, Hud.Green))
        add(InfoCell("lng", "LNG", c.lng, null, Hud.Green))
    }
    a.elevationFt?.let { e -> add(InfoCell("elev", "ELEV", "${Fmt.num(e)} ft", null, Hud.Green, copyText = e.toString())) }
}

/** A navaid's ident in its station name: "Songtan VORTAC (SOT) 116X" → SOT. */
private val IDENT = Regex("""\(([A-Z0-9]{2,4})\)""")

/** One line of the Info box: label, figure, a short note after it (band, ident, runway), and what a tap copies. */
private data class InfoCell(
    val key: String, val label: String, val value: String?, val sub: String?, val color: Color, val copyText: String? = null,
)

/** The field's frequencies in divert order: Approach, TACAN, Tower, Ground, ILS, ATIS, Base Ops, LSO. */
private fun radioCells(a: Airport, ship: Boolean): List<InfoCell> {
    val f = a.freqs
    val ilsEnds = a.runways.flatMap { r -> r.ends.filter { it.ils != null } }
    val ilsByFreq = ilsEnds.groupBy { it.ils!! }
    val ilsFirst = ilsByFreq.keys.firstOrNull()
    val ilsSub = ilsByFreq.entries.mapIndexed { i, (fq, ends) ->
        val rw = ends.joinToString(" ") { it.designator.removePrefix("Deck ").ifBlank { "deck" } }
        if (i == 0) "RWY $rw" else "$rw $fq"
    }.joinToString(" · ").ifBlank { null }
    return buildList {
        add(InfoCell("app", "APPROACH", f?.approachUhf, "UHF", Hud.Amber))
        // the station's ident where its name carries one ("Kunsan VORTAC (KUZ)" → KUZ), else the name; then the range
        val tcnSub = a.tacan?.let { t ->
            val id = t.station?.let { s -> IDENT.find(s)?.groupValues?.get(1) ?: s }
            listOfNotNull(id, t.rangeNm?.let { "$it nm" }).joinToString(" · ")
        }
        add(InfoCell("tcn", "TACAN", a.tacan?.label, tcnSub, Hud.Green))
        // a tower on VHF alone (Israel's Egyptian, Jordanian and Syrian fields, ~30 of them) shows its VHF, not a dash
        val twrUhf = f?.towerUhf?.takeIf { it.isNotBlank() }
        val twrVhf = f?.towerVhf?.takeIf { it.isNotBlank() }
        add(InfoCell("twr", "TOWER", twrUhf ?: twrVhf, if (twrUhf != null) twrVhf?.let { "VHF $it" } ?: "UHF" else if (twrVhf != null) "VHF" else null, Hud.Amber))
        f?.groundUhf?.let { add(InfoCell("gnd", "GROUND", it, "UHF", Hud.TextDim)) }
        if (ilsFirst != null || !ship) add(InfoCell("ils", "ILS", ilsFirst, ilsSub, Hud.Cyan))
        f?.atisVhf?.takeIf { it.isNotBlank() }?.let { add(InfoCell("atis", "ATIS", it, "VHF", Hud.TextDim)) }
        // the rest of what the field publishes (the old "Radio frequencies" card listed these too)
        f?.opsUhf?.takeIf { it.isNotBlank() }?.let { add(InfoCell("ops", "BASE OPS", it, "UHF", Hud.TextDim)) }
        f?.lsoUhf?.takeIf { it.isNotBlank() }?.let { add(InfoCell("lso", "LSO", it, "UHF", Hud.TextDim)) }
    }
}

/**
 * A dense table, one line a figure, on [cols] columns (always that many, so two tables over each other line up),
 * read down each column so the order holds: with two, Approach, TACAN, Tower, Ground down the first, ILS, ATIS,
 * Base Ops, LSO down the next.
 */
@Composable
private fun FieldTable(cells: List<InfoCell>, cols: Int, copied: String?, copy: (String, String) -> Unit) {
    val rows = (cells.size + cols - 1) / cols
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(RADIO_GAP)) {
        for (ci in 0 until cols) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                cells.drop(ci * rows).take(rows).forEach { cell -> InfoRow(cell, copied == cell.key) { v -> copy(cell.key, v) } }
            }
        }
    }
}

@Composable
private fun InfoRow(c: InfoCell, copied: Boolean, onCopy: (String) -> Unit) {
    val shape = RoundedCornerShape(5.dp)
    val value = c.value?.takeIf { it.isNotBlank() }
    val copyText = c.copyText ?: value
    Row(
        Modifier.fillMaxWidth().clip(shape).background(c.color.copy(alpha = 0.07f))
            .then(if (copyText != null) Modifier.clickable { onCopy(copyText) } else Modifier)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            c.label, style = LocalExtra.current.overline.copy(fontSize = 9.5.sp, letterSpacing = 0.6.sp), color = c.color,
            maxLines = 1, softWrap = false, modifier = Modifier.width(64.dp),
        )
        Text(
            value ?: "—", style = LocalExtra.current.mono.copy(fontSize = 14.5.sp, lineHeight = 18.sp), fontWeight = FontWeight.Bold,
            color = if (value != null) Hud.Text else Hud.TextFaint, maxLines = 1, softWrap = false,
        )
        Spacer(Modifier.width(6.dp))
        val sub = if (copied) "copied" else c.sub
        Text(
            sub.orEmpty(), fontSize = 10.sp, lineHeight = 12.sp, color = if (copied) Hud.Green else Hud.TextDim,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
    }
}
