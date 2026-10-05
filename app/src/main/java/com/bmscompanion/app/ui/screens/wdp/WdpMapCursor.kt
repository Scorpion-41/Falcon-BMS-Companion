package com.bmscompanion.app.ui.screens.wdp

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bmscompanion.app.data.wdp.DataCardNet
import com.bmscompanion.app.data.wdp.DtcFromMission.Pt
import com.bmscompanion.app.data.wdp.PlannerIntel
import com.bmscompanion.app.data.wdp.PlannerMap
import com.bmscompanion.app.data.wdp.PopupCoords
import com.bmscompanion.app.ui.components.MapProjection
import com.bmscompanion.app.ui.theme.Hud
import kotlin.math.roundToLong

/**
 * Where the pointer is on the Map page, and what follows it: WDP's MAP tab's sunken labels (X/Y, lat/long, the ground,
 * the variation, the bullseye) as one strip over the map's top left ([ReadoutStrip]), its khaki info label as a box
 * beside the cursor ([HoverBox]) and the measure line. By mouse the strip follows the pointer; by finger it follows the
 * last tap (a crosshair marks it), before the first tap the map's centre.
 *
 * [proj] is the map's projection as it was last drawn: a plain holder, not state, so the draw that sets it never asks
 * for another composition.
 */
internal object MapCursor {
    /** the pointer over the map, in its pixels (a mouse); null when it is off the map */
    var at by mutableStateOf<Offset?>(null)
    /** the last tap on the map, theater feet (a finger, or a mouse click) */
    var tapAt by mutableStateOf<Pt?>(null)
    /** what the pointer rests on, for the info box; null for nothing */
    var hover by mutableStateOf<MapPick?>(null)
    var hoverAt by mutableStateOf<Offset?>(null)
    /** the projection the map last drew with */
    var proj: MapProjection? = null
    /** set once the map has drawn (and [proj] is known), so the strip appears before the pointer first moves */
    var ready by mutableStateOf(false)

    /** The point the strip describes: the pointer, else the last tap, else the map's centre. */
    fun point(w: Float, h: Float): Pt? {
        val pr = proj ?: return null
        at?.let { val (n, e) = pr.toTheater(it); return Pt(n, e) }
        tapAt?.let { return it }
        val (n, e) = pr.toTheater(Offset(w / 2, h / 2))
        return Pt(n, e)
    }

    fun pointer(): Pt? {
        val pr = proj ?: return null
        val a = at ?: return null
        val (n, e) = pr.toTheater(a)
        return Pt(n, e)
    }
}

/**
 * Follows the mouse over the map without taking anything from it ([MapCursor.at]), and takes a right-click alone:
 * [onSecondary] hears it and the map never sees it (WDP's right-click add menu).
 */
@Composable
internal fun Modifier.mapPointer(onSecondary: (Offset) -> Unit): Modifier {
    val cb = rememberUpdatedState(onSecondary)
    return pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val e = awaitPointerEvent(PointerEventPass.Initial)
                val ch = e.changes.firstOrNull() ?: continue
                val finger = ch.type == PointerType.Touch
                when (e.type) {
                    PointerEventType.Move, PointerEventType.Enter -> if (!finger) MapCursor.at = ch.position
                    PointerEventType.Exit -> if (!finger) { MapCursor.at = null; MapCursor.hover = null }
                    PointerEventType.Press -> if (!finger && e.buttons.isSecondaryPressed && !e.buttons.isPrimaryPressed) {
                        ch.consume()
                        cb.value(ch.position)
                    }
                    else -> Unit
                }
            }
        }
    }
}

/** "1,234": a whole number with thousands separators (the browser has no String.format for it). */
internal fun thousands(v: Long): String {
    val s = kotlin.math.abs(v).toString()
    val out = StringBuilder()
    for ((i, c) in s.withIndex()) { if (i > 0 && (s.length - i) % 3 == 0) out.append(','); out.append(c) }
    return (if (v < 0) "-" else "") + out
}

/** The position as the other pages print it: "N37,05.123  E127,01.456", or the feet where the theater has no lat/long. */
internal fun positionText(coords: DtcCoords, at: Pt): String {
    val (n, e) = coords.label(at.north.toFloat(), at.east.toFloat())
    return if (coords.latLon) PopupCoords.hemispheres("$n/$e").let { (hn, he) -> "$hn  $he" } else "$n  $e"
}

/** The ground at [at] as the strip says it: rounded to 100 ft for the asking, "—" until known, "no height map" for none. */
internal fun groundText(theater: String?, at: Pt): String {
    val n = (at.north / 100.0).roundToLong() * 100.0
    val e = (at.east / 100.0).roundToLong() * 100.0
    @Suppress("UNUSED_VARIABLE") val v = WdpGround.version
    return when {
        !WdpGround.known(theater, n, e) -> "—"
        else -> WdpGround.at(theater, n, e)?.let { thousands(it.toLong()) + " ft" } ?: "no height map"
    }
}

/** Asks the PC for the ground under [at] (to 100 ft), once. */
internal fun askGround(theater: String?, at: Pt) {
    val n = (at.north / 100.0).roundToLong() * 100.0
    val e = (at.east / 100.0).roundToLong() * 100.0
    if (!WdpGround.known(theater, n, e)) WdpGround.fetch(theater, listOf(n to e)) {}
}

/** The ground under [at] (to 100 ft) where it has been asked for and the PC has a height map; else null. */
internal fun groundAt(theater: String?, at: Pt): Int? =
    WdpGround.at(theater, (at.north / 100.0).roundToLong() * 100.0, (at.east / 100.0).roundToLong() * 100.0)

/**
 * The readout strip over the map's top left: X and Y (east and north, grid km, as WDP), lat/long, the ground, the
 * variation (where BMS ships a map of it) and, with Cursor bullseye, the bearing and range from the bullseye. A phone
 * gets one line (lat/long · ground · VAR) that a tap opens to two.
 */
@Composable
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
internal fun ReadoutStrip(d: WdpMapData, coords: DtcCoords, w: Float, h: Float, phone: Boolean, modifier: Modifier) {
    // read so the strip follows the pointer, a pan or zoom (the map's centre before a finger's first tap), and the first draw
    @Suppress("UNUSED_VARIABLE") val moved = MapCursor.at
    @Suppress("UNUSED_VARIABLE") val view = WdpMapView.map.let { it.scale + it.panX + it.panY }
    if (!MapCursor.ready) return
    val at = MapCursor.point(w, h) ?: return
    val x = d.x
    // the ground once the pointer has rested a quarter of a second, to 100 ft (the attack pages' cache)
    val cell = (at.north / 100.0).roundToLong() to (at.east / 100.0).roundToLong()
    androidx.compose.runtime.LaunchedEffect(cell, x.groundTheater) {
        kotlinx.coroutines.delay(250)
        askGround(x.groundTheater, at)
    }
    val xy = "X " + DataCardNet.fmt(at.east / PlannerIntel.FT_PER_KM, "0.0") + "  Y " + DataCardNet.fmt(at.north / PlannerIntel.FT_PER_KM, "0.0") + " km"
    val ll = positionText(coords, at)
    val elev = groundText(x.groundTheater, at)
    val vr = x.magVar?.at(at.north, at.east)?.let { "VAR " + PlannerIntel.varText(it) }
    val bulls = if (WdpMapPrefs.cursorBulls.on && WdpMapPrefs.bullseye.on) d.bull?.let { "Bulls " + PlannerMap.braText(it, at) } else null
    val open = WdpMapView.readoutOpen
    val style = androidx.compose.ui.text.TextStyle(color = Hud.Text, fontSize = if (phone) 11.5.sp else 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
    val sep = "  |  "
    Column(
        modifier.clip(RoundedCornerShape(8.dp)).background(Hud.Bg.copy(alpha = 0.78f)).border(1.dp, Hud.Outline.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
            .then(if (phone) Modifier.clickable { WdpMapView.readoutOpen = !open } else Modifier)
            .mapControl("Readout").padding(horizontal = 9.dp, vertical = 4.dp),
    ) {
        if (phone) {
            Text(listOfNotNull(ll, elev, vr).joinToString(" · "), style = style, maxLines = 1)
            if (open) Text(listOfNotNull(xy, bulls).joinToString(" · "), style = style.copy(color = Hud.TextDim), maxLines = 1)
        } else {
            // a narrow map wraps between the figures, never inside one
            androidx.compose.foundation.layout.FlowRow {
                val items = listOfNotNull(xy, ll, elev, vr, bulls)
                items.forEachIndexed { i, s -> Text(s + if (i < items.size - 1) sep else "", style = style, maxLines = 1) }
            }
        }
    }
}

/** WDP's info label: what the pointer rests on, beside the cursor (PC and browser). */
@Composable
internal fun HoverBox(lines: List<String>, at: Offset, modifier: Modifier) {
    if (lines.isEmpty()) return
    Box(modifier) {
        Column(
            Modifier
                .layout { m, c ->
                    val p = m.measure(Constraints(maxWidth = minOf(c.maxWidth, 320.dp.roundToPx())))
                    var x = at.x.toInt() + 16.dp.roundToPx()
                    var y = at.y.toInt() + 18.dp.roundToPx()
                    if (x + p.width > c.maxWidth - 8) x = at.x.toInt() - p.width - 12.dp.roundToPx()
                    if (y + p.height > c.maxHeight - 8) y = at.y.toInt() - p.height - 8.dp.roundToPx()
                    layout(c.maxWidth, c.maxHeight) { p.place(x.coerceAtLeast(4), y.coerceAtLeast(4)) }
                }
                .clip(RoundedCornerShape(6.dp)).background(Hud.Surface.copy(alpha = 0.96f)).border(1.dp, Hud.Amber.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                .padding(horizontal = 9.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            lines.forEachIndexed { i, l ->
                Text(l, color = if (i == 0) Hud.Amber else Hud.Text, fontSize = if (i == 0) 12.5.sp else 11.5.sp, fontWeight = if (i == 0) FontWeight.SemiBold else FontWeight.Normal, lineHeight = 15.sp)
            }
        }
    }
}

/** The Cursor bullseye label beside the mouse. */
@Composable
internal fun CursorBulls(text: String, at: Offset, modifier: Modifier) {
    Box(modifier) {
        Box(
            Modifier.layout { m, c ->
                val p = m.measure(Constraints())
                layout(c.maxWidth, c.maxHeight) { p.place(at.x.toInt() + 14.dp.roundToPx(), at.y.toInt() - p.height - 6.dp.roundToPx()) }
            }.clip(RoundedCornerShape(4.dp)).background(Hud.Bg.copy(alpha = 0.75f)).padding(horizontal = 5.dp, vertical = 1.dp),
        ) { Text(text, color = Hud.Cyan, fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) }
    }
}

/** The measure's result: "Trk 045.2°T (036.6°M)  Dst 32.4 nm" and both points' lat/long, or what to tap next. */
@Composable
internal fun MeasureBanner(d: WdpMapData, coords: DtcCoords, touch: Boolean) {
    val a = WdpMapView.m1
    val b = WdpMapView.m2
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Hud.Surface.copy(alpha = 0.97f)).border(1.dp, Hud.Cyan.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row {
            val text = when {
                a == null -> "Measure: " + (if (touch) "tap" else "click") + " the first point."
                b == null -> "Measure: " + (if (touch) "tap" else "click") + " the second point."
                else -> {
                    val variation = d.x.magVar?.at((a.north + b.north) / 2, (a.east + b.east) / 2)
                    val (t, m, nm) = PlannerIntel.measure(a, b, variation)
                    "Trk " + DataCardNet.fmt(t, "000.0") + "°T" + (m?.let { " (" + DataCardNet.fmt(it, "000.0") + "°M)" } ?: "") + "  Dst " + DataCardNet.fmt(nm, "0.0") + " nm"
                }
            }
            Text(text, color = Hud.Text, fontSize = 13.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(
                "×", color = Hud.TextDim, fontSize = 18.sp,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { WdpMapView.toggleMeasure() }.padding(horizontal = 8.dp),
            )
        }
        if (a != null) Text("1: " + positionText(coords, a) + (if (b != null) "    2: " + positionText(coords, b) else ""), color = Hud.TextDim, fontSize = 11.5.sp)
    }
}

/** The widest a banner at the foot of the map runs. */
internal val BANNER_MAX = 560.dp

internal fun Modifier.bannerWidth(): Modifier = this.widthIn(max = BANNER_MAX)
