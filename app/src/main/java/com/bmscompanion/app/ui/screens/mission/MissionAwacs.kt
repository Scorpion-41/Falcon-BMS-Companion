package com.bmscompanion.app.ui.screens.mission

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bmscompanion.app.data.Airport
import com.bmscompanion.app.data.mission.Contact
import com.bmscompanion.app.data.mission.MissionLink
import com.bmscompanion.app.ui.components.HudChip
import com.bmscompanion.app.ui.components.MapProjection
import com.bmscompanion.app.ui.components.SectionCard
import com.bmscompanion.app.ui.components.TheaterMap
import com.bmscompanion.app.ui.components.safeText
import com.bmscompanion.app.ui.screens.bearingRange
import com.bmscompanion.app.ui.theme.Hud
import com.bmscompanion.app.ui.theme.LocalExtra
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

// AWACS / GCI page: the full air picture on a map, with the tools a human controller needs to give
// bullseye, BRAA and picture calls, vectors, closure and fuel estimates.

/** One contact with its position advanced to "now" (contacts arrive about twice a second; the map moves smoothly). */
private class Track(val c: Contact, val x: Double, val y: Double)

@Composable
fun MissionAwacsPane(env: MissionEnv, aw: AwacsState, onOpenTab: (MissionTab) -> Unit) {
    val th = env.theater
    if (th == null) {
        PaneEmpty("No theater yet", "Connect to the BMS PC to load the mission theater.", "Setup") { onOpenTab(MissionTab.SETUP) }
        return
    }
    val contacts by MissionLink.contacts.collectAsState()
    val live by MissionLink.live.collectAsState()
    val mission by MissionLink.mission.collectAsState()
    DisposableEffect(Unit) {
        MissionLink.fastContacts(true)
        onDispose { MissionLink.fastContacts(false) }
    }
    val all = contacts?.contacts.orEmpty()
    LaunchedEffect(contacts) { contacts?.let { aw.sample(it.contacts, it.t) } }
    val receivedAt = remember(contacts) { System.currentTimeMillis() }
    val now by produceState(System.currentTimeMillis()) { while (true) { delay(250); value = System.currentTimeMillis() } }
    val dtH = ((now - receivedAt).coerceIn(0, 3000)) / 3_600_000.0
    val tracks = remember(all, now) {
        all.filter { it.kind != "bullseye" }.map { c ->
            if (c.kind == "air" || c.kind == "heli" || c.kind == "missile") {
                val (vn, ve) = velocity(c.hdg, c.gsKts)
                Track(c, c.x + vn * dtH * NM, c.y + ve * dtH * NM)
            } else Track(c, c.x, c.y)
        }
    }
    val bull = bullseye(live, all)
    val altMin = AwacsSettings.altMinK * 1000.0
    val altMax = AwacsSettings.altMaxK * 1000.0
    val visible = tracks.filter { t -> visibleOnAwacs(t.c, altMin, altMax) }
    val hostileAir = visible.map { it.c }.filter { !it.friendly && it.isAirborneTrack() && !it.isCrew() && !it.coalition.isNullOrBlank() }
    val groups = remember(all, AwacsSettings.groupNm) { groupContacts(hostileAir, AwacsSettings.groupNm) }
    val alerts = remember(all, AwacsSettings.commitNm) { awacsAlerts(all, AwacsSettings.commitNm) }
    val ref = all.firstOrNull { it.id == aw.reference }
    val airports = env.set?.airports.orEmpty()

    if (contacts?.connected != true && all.isEmpty()) {
        PaneEmpty(
            "No AWACS feed",
            "The AWACS page needs BMS's Tacview real-time stream: set g_bTacviewRealTime 1 in Falcon BMS User.cfg and start ACMI recording in 3D (Setup has the steps).",
            "Setup",
        ) { onOpenTab(MissionTab.SETUP) }
        return
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val map: @Composable (Modifier) -> Unit = { mod -> AwacsMap(env, aw, visible, tracks, groups, bull, ref, mission, airports, mod) }
        val side: @Composable (Modifier) -> Unit = { mod -> AwacsSide(aw, all, tracks, groups, bull, ref, alerts, airports, mod) }
        val w = maxWidth
        val h = maxHeight
        if (w >= 900.dp) {
            Row(Modifier.fillMaxSize()) {
                map(Modifier.weight(1f).fillMaxHeight())
                Box(Modifier.width(1.dp).fillMaxHeight().background(Hud.Outline.copy(alpha = 0.5f)))
                side(Modifier.width(if (w >= 1300.dp) 460.dp else 400.dp).fillMaxHeight())
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                map(Modifier.fillMaxWidth().height(h * 0.52f))
                side(Modifier.fillMaxWidth().weight(1f))
            }
        }
    }
}

private fun visibleOnAwacs(c: Contact, altMin: Double, altMax: Double): Boolean {
    if (c.isCrew()) return AwacsSettings.crews
    val side = when {
        c.friendly -> AwacsSettings.friendlies
        c.coalition.isNullOrBlank() -> AwacsSettings.unknowns
        else -> AwacsSettings.hostiles
    }
    if (!side) return false
    return when (c.kind) {
        "ship" -> AwacsSettings.ships
        "missile" -> AwacsSettings.missiles
        else -> c.altFt in altMin..altMax
    }
}

@Composable
private fun AwacsMap(
    env: MissionEnv, aw: AwacsState, visible: List<Track>, tracks: List<Track>, groups: List<AwGroup>, bull: Pair<Double, Double>?,
    ref: Contact?, mission: com.bmscompanion.app.data.mission.MissionData?, airports: List<Airport>, modifier: Modifier,
) {
    val th = env.theater ?: return
    val tm = rememberTextMeasurer(cacheSize = 256)
    val hitPx = with(LocalDensity.current) { 28.dp.toPx() }
    val state = aw.mapState
    LaunchedEffect(th.id, bull != null) {
        if (aw.framed) return@LaunchedEffect
        val target = bull ?: visible.firstOrNull()?.let { it.x to it.y } ?: return@LaunchedEffect
        state.flyTo(target.first, target.second, 3f)
        aw.framed = true
    }
    val byId = remember(tracks) { tracks.associateBy { it.c.id } }
    fun posOf(p: AwPick?): Pair<Double, Double>? = when (p) {
        is AwPick.Ctc -> byId[p.id]?.let { it.x to it.y }
        is AwPick.Pt -> p.x to p.y
        null -> null
    }
    val ppts = remember(mission) { preplannedThreats(mission, null) }
    val label = remember { TextStyle(color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, shadow = MapShadow) }
    val small = remember { TextStyle(color = Hud.Cyan, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, shadow = MapShadow) }

    Box(modifier) {
        TheaterMap(
            th.map, th.sizeFt, Modifier.fillMaxSize(), state, maxScale = 30f,
            onTap = { x, y, pr ->
                val tap = pr.toScreen(x, y)
                val best = visible.minByOrNull { t -> pr.toScreen(t.x, t.y).let { hypot((it.x - tap.x).toDouble(), (it.y - tap.y).toDouble()) } }
                val hit = best?.takeIf { t -> pr.toScreen(t.x, t.y).let { hypot((it.x - tap.x).toDouble(), (it.y - tap.y).toDouble()) } < hitPx * 1.5 }
                val pick: AwPick = hit?.let { AwPick.Ctc(it.c.id) } ?: AwPick.Pt(x, y)
                if (aw.measuring && aw.selA != null) {
                    aw.selB = pick
                    aw.panel = AwPanel.MEASURE
                } else {
                    aw.selA = pick
                    aw.selB = null
                    if (pick is AwPick.Ctc) aw.panel = AwPanel.CONTACT
                    else if (aw.measuring) aw.panel = AwPanel.MEASURE
                }
            },
        ) { pr ->
            val placed = ArrayList<androidx.compose.ui.geometry.Rect>()
            fun on(p: Offset) = p.x > -80 && p.y > -80 && p.x < size.width + 80 && p.y < size.height + 80

            if (AwacsSettings.fields) airports.forEach { a ->
                val p = pr.toScreen(a.x, a.y)
                if (!on(p)) return@forEach
                drawCircle(Hud.Green.copy(alpha = 0.6f), 3.5f, p)
                if (pr.scale >= 3f) placeText(tm, a.icao ?: a.name, p + Offset(6f, 2f), small.copy(color = Hud.Green.copy(alpha = 0.8f)), placed)
            }
            if (AwacsSettings.threats) ppts.forEach { t ->
                val c = pr.toScreen(t.x, t.y)
                drawCircle(Hostile.copy(alpha = 0.55f), (t.rangeNm * pr.pxPerNm).toFloat(), c, style = Stroke(1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))))
                placeText(tm, t.name, c + Offset(6f, 2f), small.copy(color = Hostile), placed)
            }

            // bullseye rings with bearing labels
            bull?.let { (bx, by) ->
                val c = pr.toScreen(bx, by)
                val ring = AwacsSettings.bullRingNm.coerceAtLeast(5)
                val rings = (200 / ring).coerceIn(3, 20)
                for (i in 1..rings) drawCircle(Hud.Cyan.copy(alpha = if (i % 2 == 0) 0.22f else 0.13f), pr.pxPerNm * ring * i, c, style = Stroke(1.1f))
                for (a in 0 until 360 step 30) {
                    val rad = Math.toRadians(a.toDouble())
                    val len = pr.pxPerNm * ring * rings
                    val dir = Offset(sin(rad).toFloat(), -cos(rad).toFloat())
                    drawLine(Hud.Cyan.copy(alpha = if (a % 90 == 0) 0.35f else 0.16f), c, c + dir * len, 1f)
                    if (pr.scale >= 2f) placeText(tm, b3(a.toDouble()), c + dir * (pr.pxPerNm * ring * 3) + Offset(3f, -12f), small, placed)
                }
                drawCircle(Hud.Cyan, 9f, c, style = Stroke(2.5f))
                drawCircle(Hud.Cyan, 3f, c)
            }

            // trails
            if (AwacsSettings.trails) visible.forEach { t ->
                val q = aw.trails[t.c.id] ?: return@forEach
                val col = awColor(t.c)
                q.forEachIndexed { i, pt ->
                    val p = pr.toScreen(pt.x, pt.y)
                    if (on(p)) drawCircle(col.copy(alpha = 0.12f + 0.5f * i / q.size), 2f, p)
                }
            }

            // group outlines
            if (AwacsSettings.groups) groups.filter { it.size > 1 }.forEach { g ->
                val c = pr.toScreen(g.x, g.y)
                val r = g.members.maxOf { hypot(it.x - g.x, it.y - g.y) } / NM * pr.pxPerNm + 16f
                drawCircle(Hostile.copy(alpha = 0.6f), r.toFloat(), c, style = Stroke(1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))))
            }

            // commit range around the controlled flight
            if (AwacsSettings.commitRings && ref != null) byId[ref.id]?.let { t ->
                drawCircle(Hud.Amber.copy(alpha = 0.5f), AwacsSettings.commitNm * pr.pxPerNm, pr.toScreen(t.x, t.y), style = Stroke(1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))))
            }

            // radar locks
            if (AwacsSettings.locks) visible.forEach { t ->
                val target = t.c.locked?.let { byId[it] } ?: return@forEach
                drawLine((if (t.c.friendly) Friendly else Hostile).copy(alpha = 0.7f), pr.toScreen(t.x, t.y), pr.toScreen(target.x, target.y), 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 6f)))
            }

            // contacts
            visible.forEach { t ->
                val p = pr.toScreen(t.x, t.y)
                if (!on(p)) return@forEach
                val col = awColor(t.c)
                if (AwacsSettings.vectorMin > 0 && t.c.gsKts > 30 && t.c.kind != "ship") {
                    val len = (t.c.gsKts / 60.0 * AwacsSettings.vectorMin * pr.pxPerNm).toFloat().coerceIn(8f, 400f)
                    val rad = Math.toRadians(t.c.hdg)
                    drawLine(col.copy(alpha = 0.85f), p, p + Offset((sin(rad) * len).toFloat(), (-cos(rad) * len).toFloat()), 2f)
                }
                drawAwSymbol(t.c, p, col)
                if (AwacsSettings.labels && t.c.kind != "missile" && !t.c.isCrew()) {
                    val who = listOfNotNull(supportLabel(t.c), t.c.group ?: t.c.name ?: t.c.kind).joinToString(" ")
                    placeText(tm, "$who ${flightLevel(t.c.altFt)}", p + Offset(11f, -2f), label.copy(color = col), placed)
                    if (pr.scale >= 4f) placeText(tm, "${t.c.gsKts.roundToInt()}kt${t.c.fuelLb?.let { " · ${(it / 100).roundToInt() / 10.0}k lb" } ?: ""}", p + Offset(11f, 11f), small.copy(color = col.copy(alpha = 0.8f)), placed)
                }
            }

            // controlled flight marker
            ref?.let { r -> byId[r.id]?.let { drawCircle(Hud.Amber, 15f, pr.toScreen(it.x, it.y), style = Stroke(2f)) } }

            // selection and measurement
            val a = posOf(aw.selA)
            val b = posOf(aw.selB)
            a?.let { drawCircle(Hud.Magenta, 20f, pr.toScreen(it.first, it.second), style = Stroke(3f)) }
            b?.let { drawCircle(Hud.Cyan, 20f, pr.toScreen(it.first, it.second), style = Stroke(3f)) }
            if (a != null && b != null) {
                val pa = pr.toScreen(a.first, a.second)
                val pb = pr.toScreen(b.first, b.second)
                drawLine(Color.Black.copy(alpha = 0.6f), pa, pb, 5f)
                drawLine(Hud.Magenta, pa, pb, 2.5f)
                val (brg, rng) = bearingRange(a.first, a.second, b.first, b.second)
                val mid = Offset((pa.x + pb.x) / 2, (pa.y + pb.y) / 2)
                safeText(tm, "${b3(brg)}/${rng.roundToInt()}", mid + Offset(8f, -18f), label.copy(color = Hud.Magenta, fontSize = 13.sp))
            }
        }

        // quick toggles
        Row(
            Modifier.align(Alignment.TopStart).padding(8.dp).clip(RoundedCornerShape(12.dp)).background(Hud.Bg.copy(alpha = 0.84f))
                .horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            HudChip(if (aw.measuring) "Measure A→B" else "Select", aw.measuring) { aw.measuring = !aw.measuring; if (!aw.measuring) aw.selB = null }
            com.bmscompanion.app.ui.components.MapLookButton()
            HudChip("Hostiles", AwacsSettings.hostiles) { AwacsSettings.hostiles = !AwacsSettings.hostiles; AwacsSettings.save() }
            HudChip("Friendlies", AwacsSettings.friendlies) { AwacsSettings.friendlies = !AwacsSettings.friendlies; AwacsSettings.save() }
            HudChip("Trails", AwacsSettings.trails) { AwacsSettings.trails = !AwacsSettings.trails; AwacsSettings.save() }
            HudChip("Vectors ${if (AwacsSettings.vectorMin == 0) "off" else "${AwacsSettings.vectorMin}m"}", AwacsSettings.vectorMin > 0) {
                AwacsSettings.vectorMin = when (AwacsSettings.vectorMin) { 0 -> 1; 1 -> 2; 2 -> 3; else -> 0 }; AwacsSettings.save()
            }
            HudChip("Labels", AwacsSettings.labels) { AwacsSettings.labels = !AwacsSettings.labels; AwacsSettings.save() }
        }
        // centre on bullseye
        Box(
            Modifier.align(Alignment.BottomEnd).padding(12.dp).size(46.dp).clip(RoundedCornerShape(23.dp)).background(Hud.Surface.copy(alpha = 0.95f))
                .border(1.dp, Hud.Outline, RoundedCornerShape(23.dp)).clickable { bull?.let { state.flyTo(it.first, it.second, maxOf(state.scale, 2.5f)) } },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Default.MyLocation, "Centre on bullseye", tint = Hud.Cyan) }
        AcmiReminder(Modifier.align(Alignment.TopCenter).padding(top = 58.dp, start = 12.dp, end = 12.dp))
        if (aw.measuring) OverlayPill(if (aw.selA == null) "Tap the start point (A)" else "Tap the end point (B)", Hud.Magenta, Modifier.align(Alignment.BottomStart).padding(12.dp))
    }
}

private fun awColor(c: Contact) = when {
    c.isCrew() -> Hud.TextDim
    supportColor(c) != null -> supportColor(c)!!
    c.friendly -> Friendly
    c.coalition.isNullOrBlank() -> Neutral
    else -> Hostile
}

private fun DrawScope.drawAwSymbol(c: Contact, p: Offset, col: Color) {
    supportRole(c.name)?.takeIf { c.kind == "air" }?.let { role -> drawSupportSymbol(role, p, col, c.hdg); return }
    when {
        c.kind == "missile" -> { drawCircle(Color.Black, 5f, p); drawCircle(col, 3.5f, p) }
        c.isCrew() -> { drawLine(col, p - Offset(5f, 5f), p + Offset(5f, 5f), 2f); drawLine(col, p + Offset(-5f, 5f), p + Offset(5f, -5f), 2f) }
        c.kind == "ship" -> {
            drawRect(Color.Black.copy(alpha = 0.6f), p - Offset(8f, 8f), androidx.compose.ui.geometry.Size(16f, 16f))
            drawRect(col, p - Offset(6f, 6f), androidx.compose.ui.geometry.Size(12f, 12f), style = Stroke(2.5f))
        }
        c.friendly -> {
            drawCircle(Color.Black.copy(alpha = 0.6f), 9f, p)
            drawCircle(col, 7f, p, style = Stroke(3f))
            if (c.kind == "heli") drawCircle(col, 2.5f, p)
        }
        c.coalition.isNullOrBlank() -> drawRect(col, p - Offset(7f, 7f), androidx.compose.ui.geometry.Size(14f, 14f), style = Stroke(2.5f))
        else -> {
            val path = Path().apply { moveTo(p.x, p.y - 9f); lineTo(p.x + 9f, p.y); lineTo(p.x, p.y + 9f); lineTo(p.x - 9f, p.y); close() }
            drawPath(path, Color.Black.copy(alpha = 0.6f), style = Stroke(6f))
            drawPath(path, col, style = Stroke(3f))
            if (c.kind == "heli") drawCircle(col, 2.5f, p)
        }
    }
}

// ---------------------------------------------------------------- side panel

@Composable
private fun AwacsSide(
    aw: AwacsState, all: List<Contact>, tracks: List<Track>, groups: List<AwGroup>, bull: Pair<Double, Double>?, ref: Contact?,
    alerts: List<AwAlert>, airports: List<Airport>, modifier: Modifier,
) {
    val focus: (Contact) -> Unit = { c -> aw.selA = AwPick.Ctc(c.id); aw.mapState.flyTo(c.x, c.y, maxOf(aw.mapState.scale, 4f)) }
    Column(modifier.background(Hud.Bg)) {
        if (alerts.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                alerts.take(3).forEach { a ->
                    val col = if (a.severity >= 3) Hud.Red else Hud.Amber
                    Text(
                        a.text.uppercase(Locale.US),
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(col.copy(alpha = 0.14f)).border(1.dp, col.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                            .clickable { focus(a.focus) }.padding(horizontal = 10.dp, vertical = 6.dp),
                        color = col, style = LocalExtra.current.monoSmall.copy(fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                if (alerts.size > 3) Text("+${alerts.size - 3} more alerts", fontSize = 11.sp, color = Hud.TextFaint)
            }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            AwPanel.entries.forEach { p -> HudChip(p.label, aw.panel == p) { aw.panel = p } }
        }
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(start = 10.dp, end = 10.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (aw.panel) {
                AwPanel.PICTURE -> PicturePanel(aw, all, groups, bull, ref, focus)
                AwPanel.CONTACT -> ContactPanel(aw, all, tracks, groups, bull, ref)
                AwPanel.CONTROL -> ControlPanel(aw, all, groups, bull, ref, airports, focus)
                AwPanel.MEASURE -> MeasurePanel(aw, tracks)
                AwPanel.CALC -> CalcPanel(aw)
                AwPanel.LAYERS -> LayersPanel()
            }
        }
    }
}

@Composable
private fun CallBox(title: String, text: String, accent: Color = Hud.Amber) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0xFF05090C)).border(1.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(10.dp)).padding(10.dp)) {
        Text(title, style = LocalExtra.current.overline, color = accent)
        Spacer(Modifier.height(4.dp))
        Text(text, style = LocalExtra.current.mono.copy(fontSize = 14.sp, lineHeight = 20.sp), color = Hud.Text)
    }
}

@Composable
private fun PicturePanel(aw: AwacsState, all: List<Contact>, groups: List<AwGroup>, bull: Pair<Double, Double>?, ref: Contact?, focus: (Contact) -> Unit) {
    // nearest first: to the controlled flight when one is picked, otherwise to the bullseye
    val sorted = groups.sortedBy { g -> ref?.let { hypot(it.x - g.x, it.y - g.y) } ?: bull?.let { hypot(it.first - g.x, it.second - g.y) } ?: 0.0 }
    CallBox("PICTURE CALL", pictureCall(sorted, bull))
    SectionCard("Hostile groups", accent = Hostile, trailing = { Text("${groups.size} · within ${AwacsSettings.groupNm} nm", fontSize = 11.sp, color = Hud.TextDim) }) {
        if (sorted.isEmpty()) Text("Picture clean", color = Hud.Green, style = LocalExtra.current.mono)
        sorted.forEach { g ->
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { focus(g.lead) }.padding(vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${g.name}${if (g.size > 1) " ×${g.size}" else ""}", fontWeight = FontWeight.SemiBold, color = Hostile, modifier = Modifier.weight(1f), maxLines = 1)
                    Text(if (g.maxAlt - g.minAlt >= 4000) "${flightLevel(g.minAlt)}-${flightLevel(g.maxAlt)}" else flightLevel(g.maxAlt), style = LocalExtra.current.mono, color = Hostile)
                }
                Text(
                    listOfNotNull(
                        g.types.takeIf { it.isNotBlank() },
                        bull?.let { "BULLS ${bra(it.first, it.second, g.x, g.y)}" },
                        "trk ${cardinal(g.hdg)} ${g.gs.roundToInt()}kt",
                        ref?.let { "from ${it.group ?: "ref"} ${bra(it.x, it.y, g.x, g.y)} ${aspect(g.hdg, g.x, g.y, it.x, it.y)}" },
                    ).joinToString(" · "),
                    style = LocalExtra.current.monoSmall, color = Hud.TextDim,
                )
            }
        }
    }
    val friends = all.filter { it.friendly && it.isAirborneTrack() && !it.isCrew() }.sortedBy { it.group ?: it.name }
    SectionCard("Friendlies", accent = Friendly, trailing = { Text("tap to control", fontSize = 11.sp, color = Hud.TextFaint) }) {
        if (friends.isEmpty()) Text("No friendly aircraft in the feed.", color = Hud.TextDim, fontSize = 13.sp)
        friends.forEach { f ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (f.id == aw.reference) Hud.Amber.copy(alpha = 0.12f) else Color.Transparent)
                    .clickable { aw.reference = f.id; focus(f); aw.panel = AwPanel.CONTROL }.padding(vertical = 5.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("${f.group ?: f.name}${if (f.own) " (you)" else ""}", fontWeight = FontWeight.SemiBold, color = if (f.id == aw.reference) Hud.Amber else Friendly, maxLines = 1)
                    Text(
                        listOfNotNull(f.name, bull?.let { "BULLS ${bra(it.first, it.second, f.x, f.y)}" }, "${f.gsKts.roundToInt()}kt").joinToString(" · "),
                        style = LocalExtra.current.monoSmall, color = Hud.TextDim, maxLines = 1,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(flightLevel(f.altFt), style = LocalExtra.current.mono, color = Friendly)
                    f.fuelLb?.let { Text("${"%,d".format(Locale.US, it.toInt())} lb", style = LocalExtra.current.monoSmall, color = if (it < 1500) Hud.Red else Hud.TextDim) }
                }
            }
        }
    }
}

@Composable
private fun ContactPanel(aw: AwacsState, all: List<Contact>, tracks: List<Track>, groups: List<AwGroup>, bull: Pair<Double, Double>?, ref: Contact?) {
    val pick = aw.selA
    val t = (pick as? AwPick.Ctc)?.let { p -> tracks.firstOrNull { it.c.id == p.id } }
    if (t == null) {
        val pt = pick as? AwPick.Pt
        SectionCard("Contact", accent = Hud.Magenta) {
            if (pt == null) Text("Tap a contact on the map to see its details and calls.", color = Hud.TextDim, fontSize = 13.sp)
            else {
                KV("Point", "map point", labelWidth = 90.dp)
                bull?.let { KV("Bullseye", bra(it.first, it.second, pt.x, pt.y), mono = true, labelWidth = 90.dp) }
                ref?.let { KV("From ${it.group ?: "ref"}", bra(it.x, it.y, pt.x, pt.y), mono = true, labelWidth = 90.dp) }
            }
        }
        return
    }
    val c = t.c
    val col = awColor(c)
    SectionCard(c.group ?: c.name ?: "Contact", accent = col, trailing = { Text(if (c.friendly) "FRIENDLY" else if (c.coalition.isNullOrBlank()) "UNKNOWN" else "HOSTILE", style = LocalExtra.current.overline, color = col) }) {
        KV("Type", listOfNotNull(c.name, c.pilot).joinToString(" · ").ifBlank { c.kind }, labelWidth = 90.dp)
        KV("Altitude", "%,d ft".format(Locale.US, c.altFt.toInt()), mono = true, labelWidth = 90.dp)
        KV("Track", "${b3(c.hdg)} ${cardinal(c.hdg)} · ${c.gsKts.roundToInt()} kt GS", mono = true, labelWidth = 90.dp)
        if (c.ias != null || c.mach != null) KV("Speed", listOfNotNull(c.ias?.let { "${it.roundToInt()} KIAS" }, c.mach?.let { "M%.2f".format(Locale.US, it) }).joinToString(" · "), mono = true, labelWidth = 90.dp)
        c.fuelLb?.let { KV("Fuel", "%,d lb".format(Locale.US, it.toInt()), mono = true, labelWidth = 90.dp, color = if (it < 1500) Hud.Red else Hud.Text) }
        c.locked?.let { id -> all.firstOrNull { it.id == id } }?.let { KV("Locked", it.group ?: it.name ?: "contact", labelWidth = 90.dp, color = Hud.Red) }
        all.filter { it.locked == c.id }.takeIf { it.isNotEmpty() }?.let { l -> KV("Locked by", l.joinToString { it.group ?: it.name ?: "?" }, labelWidth = 90.dp, color = Hud.Red) }
        bull?.let { KV("Bullseye", bra(it.first, it.second, t.x, t.y), mono = true, labelWidth = 90.dp, color = Hud.Cyan) }
        if (ref != null && ref.id != c.id) {
            KV("From ${ref.group ?: "ref"}", "${bra(ref.x, ref.y, t.x, t.y)} ${aspect(c.hdg, t.x, t.y, ref.x, ref.y)}", mono = true, labelWidth = 90.dp)
            KV("Closure", "${closureKts(ref.x, ref.y, ref.hdg, ref.gsKts, t.x, t.y, c.hdg, c.gsKts).roundToInt()} kt", mono = true, labelWidth = 90.dp)
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (c.friendly) SmallButton("Control this flight", null, primary = true) { aw.reference = c.id; aw.panel = AwPanel.CONTROL }
            SmallButton("Measure from here", null, primary = false) { aw.measuring = true; aw.selB = null; aw.panel = AwPanel.MEASURE }
        }
    }
    if (!c.friendly && c.isAirborneTrack()) {
        val g = groups.firstOrNull { gr -> gr.members.any { it.id == c.id } } ?: AwGroup(listOf(c))
        bull?.let { CallBox("BULLSEYE CALL", bullseyeCall(g, it), Hud.Cyan) }
        if (ref != null) CallBox("BRAA CALL", braaCall(ref, g))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ControlPanel(aw: AwacsState, all: List<Contact>, groups: List<AwGroup>, bull: Pair<Double, Double>?, ref: Contact?, airports: List<Airport>, focus: (Contact) -> Unit) {
    val friends = all.filter { it.friendly && it.isAirborneTrack() && !it.isCrew() }.sortedBy { it.group ?: it.name }
    SectionCard("Controlled flight", accent = Hud.Amber) {
        if (friends.isEmpty()) { Text("No friendly aircraft in the feed.", color = Hud.TextDim, fontSize = 13.sp); return@SectionCard }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            friends.distinctBy { it.group ?: it.id }.forEach { f -> HudChip(f.group ?: f.name ?: f.id, f.id == aw.reference) { aw.reference = f.id; focus(f) } }
        }
        if (ref == null) { Spacer(Modifier.height(6.dp)); Text("Pick the flight you are controlling.", color = Hud.TextDim, fontSize = 13.sp); return@SectionCard }
        Spacer(Modifier.height(8.dp))
        KV("Aircraft", ref.name, labelWidth = 80.dp)
        KV("Alt / speed", "${flightLevel(ref.altFt)} · ${ref.gsKts.roundToInt()} kt${ref.mach?.let { " · M%.2f".format(Locale.US, it) } ?: ""}", mono = true, labelWidth = 80.dp)
        bull?.let { KV("Bullseye", bra(it.first, it.second, ref.x, ref.y), mono = true, labelWidth = 80.dp, color = Hud.Cyan) }
        ref.fuelLb?.let { KV("Fuel", "%,d lb".format(Locale.US, it.toInt()), mono = true, labelWidth = 80.dp, color = if (it < 1500) Hud.Red else Hud.Text) }
        // nearest airfield and the fuel to reach it at the assumed cruise flow
        airports.minByOrNull { hypot(it.x - ref.x, it.y - ref.y) }?.let { a ->
            val dist = hypot(a.x - ref.x, a.y - ref.y) / NM
            val gs = ref.gsKts.coerceAtLeast(300.0)
            val need = dist / gs * AwacsSettings.cruiseFf
            KV("Nearest field", "${a.icao ?: a.name} ${bra(ref.x, ref.y, a.x, a.y)} · ≈${"%,d".format(Locale.US, need.toInt())} lb", mono = true, labelWidth = 80.dp,
                color = if (ref.fuelLb != null && ref.fuelLb < need + 1000) Hud.Red else Hud.Text)
        }
    }
    if (ref == null) return
    val threats = groups.map { g -> g to hypot(g.x - ref.x, g.y - ref.y) / NM }.sortedBy { it.second }
    val nearest = threats.firstOrNull()?.first
    if (nearest != null) {
        CallBox("BRAA CALL", braaCall(ref, nearest))
        intercept(ref.x, ref.y, ref.gsKts.coerceAtLeast(350.0), nearest.x, nearest.y, nearest.hdg, nearest.gs)?.let { CallBox("VECTOR (INTERCEPT)", vectorCall(ref, nearest, it), Hud.Green) }
    }
    SectionCard("Threats to ${ref.group ?: ref.name}", accent = Hostile) {
        if (threats.isEmpty()) Text("No hostile groups.", color = Hud.Green, fontSize = 13.sp)
        threats.take(8).forEach { (g, r) ->
            val closure = closureKts(ref.x, ref.y, ref.hdg, ref.gsKts, g.x, g.y, g.hdg, g.gs)
            val merge = if (closure > 30) r / closure * 60 else null
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { focus(g.lead) }.padding(vertical = 5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${g.name}${if (g.size > 1) " ×${g.size}" else ""}", fontWeight = FontWeight.SemiBold, color = if (r <= AwacsSettings.commitNm) Hud.Red else Hostile, modifier = Modifier.weight(1f))
                    Text("${bra(ref.x, ref.y, g.x, g.y)} ${aspect(g.hdg, g.x, g.y, ref.x, ref.y)}", style = LocalExtra.current.mono, color = Hud.Text)
                }
                Text(
                    listOfNotNull(stackLabel(g), "closure ${closure.roundToInt()} kt", merge?.let { "merge ${it.roundToInt()} min" }, if (r <= AwacsSettings.commitNm) "COMMIT" else null).joinToString(" · "),
                    style = LocalExtra.current.monoSmall, color = Hud.TextDim,
                )
            }
        }
    }
}

private fun stackLabel(g: AwGroup) = if (g.maxAlt - g.minAlt >= 4000) "${flightLevel(g.minAlt)}-${flightLevel(g.maxAlt)}" else flightLevel(g.maxAlt)

@Composable
private fun MeasurePanel(aw: AwacsState, tracks: List<Track>) {
    fun describe(p: AwPick?): Triple<String, Pair<Double, Double>?, Contact?> = when (p) {
        is AwPick.Ctc -> tracks.firstOrNull { it.c.id == p.id }?.let { Triple(it.c.group ?: it.c.name ?: "contact", it.x to it.y, it.c) } ?: Triple("lost contact", null, null)
        is AwPick.Pt -> Triple("map point", p.x to p.y, null)
        null -> Triple("—", null, null)
    }
    val (aName, a, ac) = describe(aw.selA)
    val (bName, b, bc) = describe(aw.selB)
    SectionCard("Measure", accent = Hud.Magenta) {
        Text("Turn on Measure A→B on the map, then tap the start and end (contacts or any point).", color = Hud.TextDim, fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        KV("A", aName, labelWidth = 30.dp, color = Hud.Magenta)
        KV("B", bName, labelWidth = 30.dp, color = Hud.Cyan)
        if (a != null && b != null) {
            val (brg, rng) = bearingRange(a.first, a.second, b.first, b.second)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BigStat("A → B", "${b3(brg)}/${rng.roundToInt()}", Modifier.weight(1f), Hud.Magenta)
                BigStat("B → A", "${b3(brg + 180)}/${rng.roundToInt()}", Modifier.weight(1f), Hud.Cyan)
            }
            if (ac != null && bc != null) {
                Spacer(Modifier.height(6.dp))
                KV("Δ altitude", "%+,d ft".format(Locale.US, (bc.altFt - ac.altFt).toInt()), mono = true, labelWidth = 90.dp)
                val closure = closureKts(a.first, a.second, ac.hdg, ac.gsKts, b.first, b.second, bc.hdg, bc.gsKts)
                KV("Closure", "${closure.roundToInt()} kt${if (closure > 30) " · ${(rng / closure * 60).roundToInt()} min" else ""}", mono = true, labelWidth = 90.dp)
                KV("Aspect", aspect(bc.hdg, b.first, b.second, a.first, a.second), labelWidth = 90.dp)
            }
            if (ac != null && ac.gsKts > 30) {
                val time = rng / ac.gsKts * 60
                KV("Time (A's GS)", "${time.roundToInt()} min at ${ac.gsKts.roundToInt()} kt", mono = true, labelWidth = 90.dp)
                if (bc != null) intercept(a.first, a.second, ac.gsKts, b.first, b.second, bc.hdg, bc.gsKts)?.let {
                    KV("Intercept", "hdg ${b3(it.heading)} · ${it.rangeNm.roundToInt()} nm · ${it.minutes.roundToInt()} min", mono = true, labelWidth = 90.dp, color = Hud.Green)
                }
                val fuel = time / 60 * AwacsSettings.cruiseFf
                KV("Fuel", "≈ ${"%,d".format(Locale.US, fuel.toInt())} lb at ${AwacsSettings.cruiseFf} pph", mono = true, labelWidth = 90.dp)
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallButton("Swap", null, primary = false) { val t = aw.selA; aw.selA = aw.selB; aw.selB = t }
            SmallButton("Clear", null, primary = false) { aw.selA = null; aw.selB = null }
            if (a != null && b != null) SmallButton("Use in calculator", null, primary = false) {
                aw.calcDist = bearingRange(a.first, a.second, b.first, b.second).second.roundToInt().toString()
                ac?.gsKts?.takeIf { it > 30 }?.let { aw.calcGs = it.roundToInt().toString() }
                aw.panel = AwPanel.CALC
            }
        }
    }
}

@Composable
private fun NumField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier) {
    OutlinedTextField(
        value, { v -> onChange(v.filter { it.isDigit() || it == '.' }.take(7)) }, modifier, singleLine = true, label = { Text(label, fontSize = 12.sp) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Hud.Amber, unfocusedBorderColor = Hud.Outline, cursorColor = Hud.Amber),
        textStyle = LocalExtra.current.mono,
    )
}

@Composable
private fun CalcPanel(aw: AwacsState) {
    SectionCard("Time & fuel for a distance", accent = Hud.Amber) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumField("Distance nm", aw.calcDist, { aw.calcDist = it }, Modifier.weight(1f))
            NumField("Ground speed kt", aw.calcGs, { aw.calcGs = it }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumField("Fuel flow pph", aw.calcFf, { aw.calcFf = it }, Modifier.weight(1f))
            NumField("Reserve lb", aw.calcReserve, { aw.calcReserve = it }, Modifier.weight(1f))
        }
        val dist = aw.calcDist.toDoubleOrNull()
        val gs = aw.calcGs.toDoubleOrNull()
        val ff = aw.calcFf.toDoubleOrNull()
        val res = aw.calcReserve.toDoubleOrNull() ?: 0.0
        if (dist != null && gs != null && gs > 0 && ff != null) {
            val h = dist / gs
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BigStat("TIME", String.format(Locale.US, "%d:%02d", (h * 60).toInt() / 60, (h * 60).roundToInt() % 60), Modifier.weight(1f), Hud.Cyan)
                BigStat("FUEL", "%,d".format(Locale.US, (h * ff).toInt()), Modifier.weight(1f), sub = "lb")
                BigStat("WITH RESERVE", "%,d".format(Locale.US, (h * ff + res).toInt()), Modifier.weight(1f), Hud.Amber, sub = "lb")
            }
        }
    }
    SectionCard("Bullseye → BRAA", accent = Hud.Cyan) {
        Text("Friendly and group positions from bullseye (e.g. from radio calls) give the BRAA between them.", color = Hud.TextDim, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumField("Friendly brg", aw.convFrBrg, { aw.convFrBrg = it }, Modifier.weight(1f))
            NumField("Friendly nm", aw.convFrRng, { aw.convFrRng = it }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumField("Group brg", aw.convGrBrg, { aw.convGrBrg = it }, Modifier.weight(1f))
            NumField("Group nm", aw.convGrRng, { aw.convGrRng = it }, Modifier.weight(1f))
        }
        val fb = aw.convFrBrg.toDoubleOrNull(); val fr = aw.convFrRng.toDoubleOrNull()
        val gb = aw.convGrBrg.toDoubleOrNull(); val gr = aw.convGrRng.toDoubleOrNull()
        if (fb != null && fr != null && gb != null && gr != null) {
            fun pt(b: Double, r: Double) = r * cos(Math.toRadians(b)) * NM to r * sin(Math.toRadians(b)) * NM
            val f = pt(fb, fr); val g = pt(gb, gr)
            val (brg, rng) = bearingRange(f.first, f.second, g.first, g.second)
            Spacer(Modifier.height(8.dp))
            BigStat("BRAA FROM FRIENDLY", "${b3(brg)}/${rng.roundToInt()}", Modifier.fillMaxWidth(), Hud.Cyan)
        }
    }
    SectionCard("Time to merge", accent = Hostile) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumField("Range nm", aw.mergeRange, { aw.mergeRange = it }, Modifier.weight(1f))
            NumField("Closure kt", aw.mergeClosure, { aw.mergeClosure = it }, Modifier.weight(1f))
        }
        val r = aw.mergeRange.toDoubleOrNull(); val cl = aw.mergeClosure.toDoubleOrNull()
        if (r != null && cl != null && cl > 0) {
            val sec = (r / cl * 3600).roundToInt()
            Spacer(Modifier.height(8.dp))
            BigStat("MERGE IN", String.format(Locale.US, "%d:%02d", sec / 60, sec % 60), Modifier.fillMaxWidth(), Hostile, sub = "${(cl / 60).roundToInt()} nm per minute")
        }
    }
}

@Composable
private fun LayersPanel() {
    fun toggle(label: String, on: Boolean, set: (Boolean) -> Unit): @Composable () -> Unit = { HudChip(label, on) { set(!on); AwacsSettings.save() } }
    SectionCard("Show", accent = Hud.Cyan) {
        FlowRowChips(
            toggle("Friendlies", AwacsSettings.friendlies) { AwacsSettings.friendlies = it },
            toggle("Hostiles", AwacsSettings.hostiles) { AwacsSettings.hostiles = it },
            toggle("Unknown", AwacsSettings.unknowns) { AwacsSettings.unknowns = it },
            toggle("Ships", AwacsSettings.ships) { AwacsSettings.ships = it },
            toggle("Missiles", AwacsSettings.missiles) { AwacsSettings.missiles = it },
            toggle("Ejected crews", AwacsSettings.crews) { AwacsSettings.crews = it },
            toggle("Labels", AwacsSettings.labels) { AwacsSettings.labels = it },
            toggle("Trails", AwacsSettings.trails) { AwacsSettings.trails = it },
            toggle("Group circles", AwacsSettings.groups) { AwacsSettings.groups = it },
            toggle("Radar locks", AwacsSettings.locks) { AwacsSettings.locks = it },
            toggle("Commit ring", AwacsSettings.commitRings) { AwacsSettings.commitRings = it },
            toggle("Airfields", AwacsSettings.fields) { AwacsSettings.fields = it },
            toggle("Threat rings (DTC)", AwacsSettings.threats) { AwacsSettings.threats = it },
        )
    }
    SectionCard("Settings", accent = Hud.Amber) {
        SettingRow("Speed vectors", listOf(0, 1, 2, 3), AwacsSettings.vectorMin, { if (it == 0) "off" else "${it} min" }) { AwacsSettings.vectorMin = it }
        SettingRow("Bullseye rings", listOf(10, 20, 30), AwacsSettings.bullRingNm, { "$it nm" }) { AwacsSettings.bullRingNm = it }
        SettingRow("Group radius", listOf(3, 5, 10), AwacsSettings.groupNm, { "$it nm" }) { AwacsSettings.groupNm = it }
        SettingRow("Commit range", listOf(20, 30, 40, 60), AwacsSettings.commitNm, { "$it nm" }) { AwacsSettings.commitNm = it }
        SettingRow("Lowest altitude", listOf(0, 1, 5, 10), AwacsSettings.altMinK, { if (it == 0) "all" else "${it}k" }) { AwacsSettings.altMinK = it }
        SettingRow("Highest altitude", listOf(15, 30, 50, 80), AwacsSettings.altMaxK, { if (it == 80) "all" else "${it}k" }) { AwacsSettings.altMaxK = it }
        SettingRow("Cruise fuel flow", listOf(2500, 3500, 5000, 8000), AwacsSettings.cruiseFf, { "$it pph" }) { AwacsSettings.cruiseFf = it }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowChips(vararg chips: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { chips.forEach { it() } }
}

@Composable
private fun SettingRow(label: String, options: List<Int>, value: Int, text: (Int) -> String, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 13.sp, color = Hud.TextDim, modifier = Modifier.weight(1f))
        Segmented(options.map(text), options.indexOf(value).coerceAtLeast(0)) { onSelect(options[it]); AwacsSettings.save() }
    }
}
