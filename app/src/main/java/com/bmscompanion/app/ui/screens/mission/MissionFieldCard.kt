package com.bmscompanion.app.ui.screens.mission

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bmscompanion.app.data.Airport
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.airfield.Airfield
import com.bmscompanion.app.ui.components.AirfieldChart
import com.bmscompanion.app.ui.go
import com.bmscompanion.app.ui.screens.DivertBlock
import com.bmscompanion.app.ui.screens.TaxiPrefs
import com.bmscompanion.app.ui.screens.airportKind
import com.bmscompanion.app.ui.theme.Hud
import com.bmscompanion.app.ui.theme.LocalExtra
import com.bmscompanion.app.ui.screens.wdp.WdpProbe
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.toSize

/**
 * The airfield card of the Mission map (1.4.0): a tap on an airfield — its symbol, its name, or anywhere on its diagram
 * when the map is close enough to draw one — opens this, densely packed, rather than a point card with a button to the
 * field's page. Name, ICAO, what the field is to the flight, bearing and range from the jet and from the bullseye, the
 * runways with their lengths, then the airfield page's own Info box ([DivertBlock], unframed, on narrower columns: the
 * coordinates and ELEV, then every frequency in divert order — a tap copies a figure), and **Airfield page ›** for the
 * rest (charts, ground chart, ATC). Where the card is wide enough (a tablet or the PC) a small ground chart of the field
 * sits beside its heading, which opens the page too. It scrolls inside whatever height its caller leaves it.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun MissionFieldCard(
    env: MissionEnv, a: Airport, d: MapData, modifier: Modifier,
    /** opens another selection's card (a steerpoint on the field); null where the card has nowhere to send it */
    onPick: ((MapSel) -> Unit)? = null,
    onClose: () -> Unit,
) {
    val th = env.theater
    val accent = Hud.Green
    val tag = fieldTag(a.id == d.depField?.id, a.id == d.arrField?.id, a.id == d.altField?.id)
    val role = when (tag) { "HOME" -> "Home"; "DEP" -> "Take-off"; "ARR" -> "Landing"; "ALT" -> "Alternate"; else -> null }
    val open = { th?.let { env.nav.go(missionAirportRoute(it.id, a.id)) }; Unit }
    val chart by produceState<Airfield?>(null, th?.airfieldSet, a.id) {
        value = th?.airfieldSet?.let { set -> runCatching { Repo.airfield(set, a.id) }.getOrNull() }
    }
    BoxWithConstraints(modifier) {
        val thumb = maxWidth >= 420.dp && chart != null
        Column(
            Modifier.clip(RoundedCornerShape(14.dp)).background(Hud.Surface.copy(alpha = 0.97f))
                .border(1.dp, accent.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
                // scrolls in the height its caller leaves it (inside a scrolling column it is simply as tall as it is)
                .then(if (constraints.hasBoundedHeight) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Row {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            a.name, style = MaterialTheme.typography.titleMedium, color = accent, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                        )
                        if (role != null) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                role.uppercase(), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Hud.Bg, maxLines = 1,
                                modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(accent).padding(horizontal = 5.dp, vertical = 1.dp),
                            )
                        }
                    }
                    val sub = listOfNotNull(a.icao, airportKind(a), a.fullName.takeIf { it.isNotBlank() && !it.startsWith(a.name) }).joinToString(" · ")
                    if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = Hud.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                    // where it is from the jet and from the bullseye: what a point card gave, kept
                    val own = d.ownPos
                    val bull = d.bull
                    if (own != null || bull != null) Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        own?.let { FactPair("FROM YOU", bra(it.first, it.second, a.x, a.y), Hud.Amber) }
                        bull?.let { FactPair("BULLS", bra(it.first, it.second, a.x, a.y), Hud.Cyan) }
                    }
                    if (a.runways.isNotEmpty()) Row(Modifier.padding(top = 3.dp)) {
                        Text("RWY", style = LocalExtra.current.overline.copy(fontSize = 9.5.sp, letterSpacing = 0.6.sp), color = Hud.TextDim, modifier = Modifier.width(40.dp).padding(top = 2.dp))
                        // one item a runway, so a runway never breaks across two lines
                        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            a.runways.forEach { r ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(r.name, style = LocalExtra.current.mono.copy(fontSize = 13.sp, lineHeight = 17.sp), fontWeight = FontWeight.Bold, color = Hud.Text, maxLines = 1)
                                    r.lengthFt?.let { Text("  %,d ft".format(it), style = LocalExtra.current.mono.copy(fontSize = 12.sp, lineHeight = 17.sp), color = Hud.TextDim, maxLines = 1) }
                                }
                            }
                        }
                    }
                    // the flight's steerpoints on the field (take-off, landing): a tap on the field opens this card,
                    // so each is a tap away from here
                    val onField = d.stpts.filter { it.hasPos && it.onRoute && rangeNm(a.x, a.y, it.x!!, it.y!!) < FIELD_STPT_NM }
                    if (onField.isNotEmpty()) Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("STPT", style = LocalExtra.current.overline.copy(fontSize = 9.5.sp, letterSpacing = 0.6.sp), color = Hud.TextDim, modifier = Modifier.width(40.dp))
                        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            onField.take(4).forEach { s ->
                                Text(
                                    listOfNotNull("${s.n}", s.title.uppercase(), s.time).joinToString(" "),
                                    style = LocalExtra.current.mono.copy(fontSize = 12.sp), fontWeight = FontWeight.Bold, color = Hud.Amber, maxLines = 1,
                                    modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(Hud.Amber.copy(alpha = 0.1f))
                                        .then(if (onPick != null) Modifier.clickable { onPick(MapSel.Stp(s.n)) } else Modifier)
                                        .padding(horizontal = 5.dp, vertical = 1.dp),
                                )
                            }
                        }
                    }
                }
                if (thumb) {
                    Spacer(Modifier.width(10.dp))
                    FieldThumb(chart!!, 112.dp, open)
                }
                Spacer(Modifier.width(6.dp))
                Icon(Icons.Default.Close, "Close", tint = Hud.TextDim, modifier = Modifier.size(22.dp).clickable(onClick = onClose))
            }
            if (th != null) {
                Box(Modifier.fillMaxWidth().padding(vertical = 7.dp).height(1.dp).background(Hud.Outline.copy(alpha = 0.55f)))
                DivertBlock(th, a, framed = false, colWidth = 150.dp, labelWidth = 58.dp)
            }
            Spacer(Modifier.height(9.dp))
            Text(
                "Airfield page ›",
                Modifier.clip(RoundedCornerShape(8.dp)).background(accent.copy(alpha = 0.15f))
                    .border(1.dp, accent.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
                    .clickable(onClick = open).padding(horizontal = 12.dp, vertical = 7.dp),
                color = accent, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1,
            )
        }
    }
}

/** Where the airfield card was laid out ("mission/fieldcard"), for `--airfieldcardrender`; nothing unless a check turned the probe on. */
internal fun Modifier.fieldCardProbe(): Modifier =
    if (!WdpProbe.on) this else onGloballyPositioned { WdpProbe.rects["mission/fieldcard"] = Rect(it.positionInRoot(), it.size.toSize()) }

@Composable
private fun FactPair(label: String, value: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = LocalExtra.current.overline.copy(fontSize = 9.5.sp, letterSpacing = 0.6.sp), color = Hud.TextDim, maxLines = 1)
        Spacer(Modifier.width(5.dp))
        Text(value, style = LocalExtra.current.mono.copy(fontSize = 13.sp), fontWeight = FontWeight.Bold, color = color, maxLines = 1)
    }
}

/** The field's ground chart, small and turned to fit, as the airfield page's own card shows it; a tap opens the page. */
@Composable
private fun FieldThumb(f: Airfield, side: Dp, open: () -> Unit) {
    Box(
        Modifier.size(side).clip(RoundedCornerShape(8.dp)).border(1.dp, Hud.Outline.copy(alpha = 0.6f), RoundedCornerShape(8.dp)).clickable(onClick = open),
    ) {
        AirfieldChart(
            field = f,
            route = if (f.ship == null) f.routes.firstOrNull() else null,
            inks = TaxiPrefs.inks,
            modifier = Modifier.fillMaxSize(),
            labelScale = 0.6f,
            fitRotation = true,
            interactive = false,
            // too small for ramp numbers: they buried the runways
            fieldOnly = true,
        )
    }
}
