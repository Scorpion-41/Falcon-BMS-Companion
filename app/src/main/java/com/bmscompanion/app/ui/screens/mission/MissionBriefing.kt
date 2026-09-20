package com.bmscompanion.app.ui.screens.mission

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import com.bmscompanion.app.data.Weapon
import com.bmscompanion.app.data.mission.Briefing
import com.bmscompanion.app.data.mission.MissionLink
import com.bmscompanion.app.ui.components.CollapsibleCard
import com.bmscompanion.app.ui.components.Masonry
import com.bmscompanion.app.ui.components.SectionCard
import com.bmscompanion.app.ui.components.Tag
import com.bmscompanion.app.ui.components.TagFlow
import com.bmscompanion.app.ui.components.TextCard
import com.bmscompanion.app.ui.go
import com.bmscompanion.app.ui.theme.Hud
import com.bmscompanion.app.ui.theme.LocalExtra

@Composable
fun MissionBriefingPane(env: MissionEnv, showOnMap: (MapSel, Double, Double) -> Unit) {
    val mission by MissionLink.mission.collectAsState()
    val live by MissionLink.live.collectAsState()
    val contacts by MissionLink.contacts.collectAsState()
    val b = mission?.briefing
    if (b == null) {
        PaneEmpty(
            "No briefing yet",
            "In Falcon BMS open the mission Briefing and press PRINT (top right).\nThe app picks it up automatically. See Setup if nothing appears.",
        )
        return
    }
    val weapons by produceState<Map<String, Weapon>>(emptyMap()) { value = Repo.weapons().associateBy { it.name.norm() } }
    val threats by produceState<List<com.bmscompanion.app.data.Threat>>(emptyList()) { value = Repo.threats() }
    val stpts = steerpoints(mission, live)
    val own = ownship(live)
    val bull = bullseye(live, contacts?.contacts)
    val bases = airbases(live, b)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Masonry(minColumn = 420.dp, maxColumns = 3) {
            OverviewCard(b, live?.voice?.flight)
            // the kneeboard html_brief exported on the BMS PC, and the button that runs it
            KneeboardCard(env.nav)
            AirbasesCard(env, bases)
            SteerpointTable(stpts, own, bull) { s -> if (s.hasPos) showOnMap(MapSel.Stp(s.n), s.x!!, s.y!!) }
            TargetsCard(mission?.dtc, bull, showOnMap)
            PackageCard(b)
            LoadoutCard(b, weapons) { w -> env.nav.go("m/weapon/${android.net.Uri.encode(w.key)}") }
            ThreatCard(b, threats) { t -> env.nav.go("m/threat/${android.net.Uri.encode(t.id)}") }
            SupportCard(rememberSupportAssets(env))
            WeatherCard(b)
            TextCard("Situation", b.situation, Hud.Cyan, threshold = 200)
            if (b.roe.isNotEmpty()) TextCard("Rules of engagement", b.roe.joinToString("\n"), Hud.Amber, threshold = 200)
            if (b.emergency.isNotEmpty()) CollapsibleCard("Emergency procedures", accent = Hud.Red, preview = b.alternate?.let { "Alternate: $it" }) {
                b.emergency.forEach { blk ->
                    blk.title?.let { Text(it, fontWeight = FontWeight.SemiBold, color = Hud.Text, modifier = Modifier.padding(top = 6.dp)) }
                    blk.lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = Hud.TextDim) }
                }
            }
        }
        b.generated?.let { Text("Briefing printed $it", fontSize = 11.sp, color = Hud.TextFaint, modifier = Modifier.padding(top = 10.dp, start = 4.dp)) }
    }
}

@Composable
fun OverviewCard(b: Briefing, flight: String?) {
    val o = b.overview
    val mine = b.`package`.firstOrNull { it.primary } ?: b.`package`.firstOrNull { it.callsign == (flight ?: o.flight) }
    SectionCard("Mission", accent = Hud.Amber) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text((o.flight ?: mine?.callsign ?: "—").uppercase(), style = MaterialTheme.typography.headlineSmall, color = Hud.Text)
            Spacer(Modifier.width(10.dp))
            o.mission?.let { Tag(it, Hud.Amber, filled = true) }
        }
        listOfNotNull(mine?.count?.let { "$it × ${mine.aircraft}" } ?: mine?.aircraft, o.packageMission).takeIf { it.isNotEmpty() }?.let {
            Text(it.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = Hud.TextDim)
        }
        Spacer(Modifier.height(10.dp))
        BigRow(
            listOfNotNull(
                mine?.takeoff?.let { "T/O (Z)" to it },
                mine?.push?.let { "PUSH (Z)" to it },
                (o.tot ?: mine?.target)?.let { "TOT (Z)" to it },
            ),
        )
        Spacer(Modifier.height(10.dp))
        KV("Target area", o.targetArea)
        KV("Package", listOfNotNull(o.packageId?.let { "#$it" }, o.packageType).joinToString(" · ").ifBlank { null })
        KV("Task", mine?.task)
        KV("IFF", mine?.iff, mono = true)
    }
}

@Composable
fun BigRow(items: List<Pair<String, String>>) {
    if (items.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { (k, v) ->
            Column(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(Hud.Surface2).padding(horizontal = 10.dp, vertical = 7.dp)) {
                Text(k, fontSize = 10.sp, color = Hud.TextDim, fontWeight = FontWeight.SemiBold)
                // times come as "03:43:00z": drop the z so they fit on one line on phones (label says Z)
                Text(v.removeSuffix("z").removeSuffix("Z"), style = LocalExtra.current.mono.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold), color = Hud.Amber, maxLines = 1, softWrap = false)
            }
        }
    }
}

@Composable
fun KV(label: String, value: String?, mono: Boolean = false, color: Color = Hud.Text, labelWidth: Dp = 96.dp) {
    if (value.isNullOrBlank()) return
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, fontSize = 13.sp, color = Hud.TextDim, modifier = Modifier.width(labelWidth))
        Text(value, style = if (mono) LocalExtra.current.monoSmall.copy(fontSize = 13.sp) else MaterialTheme.typography.bodyMedium, color = color)
    }
}

@Composable
fun AirbasesCard(env: MissionEnv, bases: Airbases) {
    val rows = listOf("DEPARTURE" to bases.departure, "RECOVERY" to bases.arrival, "ALTERNATE" to bases.alternate).filter { it.second != null }
    if (rows.isEmpty()) return
    SectionCard("Airbases", accent = Hud.Green) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            rows.forEachIndexed { i, (role, name) ->
                if (i == 1 && bases.arrival != null && bases.arrival == bases.departure) return@forEachIndexed
                val a = matchAirport(env.set, name)
                AirbaseRow(env, if (i == 0 && bases.arrival == bases.departure) "HOME PLATE" else role, name!!, a)
            }
        }
    }
}

@Composable
private fun AirbaseRow(env: MissionEnv, role: String, name: String, a: Airport?) {
    val clickable = a != null && env.theater != null
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Hud.Green.copy(alpha = 0.07f)).border(1.dp, Hud.Green.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
            .clickable(enabled = clickable) { env.nav.go(missionAirportRoute(env.theater!!.id, a!!.id)) }.padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(role, style = LocalExtra.current.overline, color = Hud.Green)
            Spacer(Modifier.width(8.dp))
            Text(a?.name ?: name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (clickable) Text("Charts ›", color = Hud.Amber, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        if (a != null) {
            Spacer(Modifier.height(4.dp))
            TagFlow {
                a.tacan?.let { Tag("TCN ${it.label}", Hud.Green, filled = true) }
                a.freqs?.towerUhf?.let { Tag("TWR $it", Hud.Amber) }
                a.freqs?.groundUhf?.let { Tag("GND $it", Hud.TextDim) }
                a.runways.flatMap { r -> r.ends.filter { it.ils != null } }.forEach { Tag("ILS ${it.designator} ${it.ils}", Hud.Cyan) }
                a.runways.forEach { Tag("RWY ${it.name}", Hud.TextDim) }
                a.elevationFt?.takeIf { it > 0 }?.let { Tag("ELEV $it ft", Hud.TextFaint) }
            }
        }
    }
}

@Composable
private fun SteerpointTable(stpts: List<Stpt>, own: Pair<Double, Double>?, bull: Pair<Double, Double>?, onPick: (Stpt) -> Unit) {
    if (stpts.isEmpty()) return
    SectionCard("Flight plan", accent = Hud.Amber, trailing = { Text("tap to show on map", fontSize = 10.sp, color = Hud.TextFaint) }) {
        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
            Head("#", 28.dp); Head("STPT", null, Modifier.weight(1f)); Head("TOS", 74.dp); Head("ALT", 52.dp); Head(if (bull != null) "BULLS" else "", 62.dp)
        }
        stpts.forEach { s ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable(enabled = s.hasPos) { onPick(s) }.padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${s.n}", Modifier.width(28.dp), style = LocalExtra.current.monoSmall, color = if (s.isTarget) Hostile else Hud.Amber)
                Column(Modifier.weight(1f)) {
                    Text(s.title, fontSize = 13.sp, color = if (s.isTarget) Hostile else Hud.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    listOfNotNull(s.action, s.cas?.let { "$it kt" }, s.comments?.takeIf { it != s.desc }).joinToString(" · ").takeIf { it.isNotBlank() }?.let {
                        Text(it, fontSize = 11.sp, color = Hud.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Text(s.time?.removeSuffix("z") ?: "—", Modifier.width(74.dp), style = LocalExtra.current.monoSmall)
                Text(s.altText ?: "—", Modifier.width(52.dp), style = LocalExtra.current.monoSmall, color = Hud.TextDim)
                Text(if (bull != null && s.hasPos) bra(bull.first, bull.second, s.x!!, s.y!!) else "", Modifier.width(62.dp), style = LocalExtra.current.monoSmall, color = Hud.Cyan)
            }
        }
        if (stpts.none { it.hasPos }) Text("Save the DTC in BMS (or enter 3D) to get steerpoint positions on the map.", fontSize = 11.sp, color = Hud.TextFaint, modifier = Modifier.padding(top = 6.dp))
        if (own != null) Spacer(Modifier.height(2.dp))
    }
}

@Composable
private fun Head(text: String, width: Dp?, modifier: Modifier = Modifier) {
    Text(text, (if (width != null) Modifier.width(width) else modifier), fontSize = 10.sp, color = Hud.TextFaint, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun TargetsCard(dtc: com.bmscompanion.app.data.mission.Dtc?, bull: Pair<Double, Double>?, showOnMap: (MapSel, Double, Double) -> Unit) {
    val targets = dtc?.steerpoints.orEmpty().filter { it.isTarget } + dtc?.weaponTargets.orEmpty()
    if (targets.isEmpty()) return
    SectionCard("Targets (DTC)", accent = Hostile) {
        targets.forEach { t ->
            Row(Modifier.fillMaxWidth().clickable { showOnMap(MapSel.Pt(t.x, t.y), t.x, t.y) }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Tag(if (dtc!!.weaponTargets.contains(t)) "WPN ${t.n}" else "STPT ${t.n}", Hostile, filled = true)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.name ?: "Target", fontSize = 13.sp)
                    Text("%,d ft".format(t.altFt.toInt()), fontSize = 11.sp, color = Hud.TextDim)
                }
                bull?.let { Text("BULLS ${bra(it.first, it.second, t.x, t.y)}", style = LocalExtra.current.monoSmall, color = Hud.Cyan) }
            }
        }
    }
}

@Composable
private fun PackageCard(b: Briefing) {
    if (b.`package`.isEmpty()) return
    SectionCard("Package", accent = Hud.Cyan, trailing = { b.overview.packageId?.let { Text("#$it", style = LocalExtra.current.monoSmall, color = Hud.TextDim) } }) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            b.`package`.forEach { f ->
                val pilots = b.roster.firstOrNull { it.callsign == f.callsign }?.pilots.orEmpty()
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (f.primary) Hud.Amber.copy(alpha = 0.10f) else Hud.Surface2)
                        .border(1.dp, if (f.primary) Hud.Amber.copy(alpha = 0.5f) else Color.Transparent, RoundedCornerShape(10.dp)).padding(10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(f.callsign, style = MaterialTheme.typography.titleSmall, color = if (f.primary) Hud.Amber else Hud.Text)
                        Spacer(Modifier.width(8.dp))
                        f.role?.let { Tag(it, Hud.Cyan) }
                        Spacer(Modifier.weight(1f))
                        Text(listOfNotNull(f.count?.let { "$it ×" }, f.aircraft).joinToString(" "), fontSize = 12.sp, color = Hud.TextDim)
                    }
                    Text(listOfNotNull(f.takeoff?.let { "T/O $it" }, f.push?.let { "Push $it" }, f.target?.let { "Tgt $it" }).joinToString("  "), style = LocalExtra.current.monoSmall, color = Hud.TextDim)
                    if (pilots.any { it != "Unassigned" }) Text(pilots.joinToString(" · "), fontSize = 12.sp, color = Hud.Text)
                }
            }
        }
    }
}

@Composable
private fun LoadoutCard(b: Briefing, weapons: Map<String, Weapon>, onWeapon: (Weapon) -> Unit) {
    if (b.ordnance.isEmpty()) return
    SectionCard("Loadout", accent = Hud.Amber) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            b.ordnance.forEach { f ->
                Text(f.flight, style = LocalExtra.current.overline, color = Hud.Green)
                // Identical jets are merged ("×2") to keep the card short.
                f.aircraft.groupBy { a -> a.stores }.forEach { (stores, jets) ->
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Hud.Surface2).padding(10.dp)) {
                        Text(jets.joinToString(", ") { it.name }, fontSize = 12.sp, color = Hud.TextDim)
                        Spacer(Modifier.height(4.dp))
                        TagFlow {
                            stores.forEach { s ->
                                val w = weapons[s.name.norm()]
                                Row(
                                    Modifier.clip(RoundedCornerShape(8.dp)).background(Hud.Bg).border(1.dp, if (w != null) Hud.Amber.copy(alpha = 0.5f) else Hud.Outline, RoundedCornerShape(8.dp))
                                        .clickable(enabled = w != null) { onWeapon(w!!) }.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text("${s.qty}×", style = LocalExtra.current.monoSmall, color = Hud.Amber)
                                    Spacer(Modifier.width(5.dp))
                                    Text(s.name, fontSize = 13.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private val threatToken = Regex("""\b(SA-N-\d+|SA-\d+[A-Z]?|HQ-\d+[A-Z]?|ZSU-\d+(?:-\d+)?|S-\d{2,3}|2S6|Tunguska|Pantsir|Hawk|Patriot|Roland|Rapier|Crotale|Gepard|Chaparral|Nike)\b""", RegexOption.IGNORE_CASE)

@Composable
private fun ThreatCard(b: Briefing, threats: List<com.bmscompanion.app.data.Threat>, onThreat: (com.bmscompanion.app.data.Threat) -> Unit) {
    if (b.threats.isEmpty()) return
    val text = b.threats.flatMap { it.lines }.joinToString("\n")
    val linked = threatToken.findAll(text).map { it.value.norm() }.distinct().mapNotNull { tok ->
        threats.firstOrNull { t -> t.name.norm().startsWith(tok) || t.aliases.any { it.norm() == tok } || t.id.norm().startsWith(tok) }
    }.distinctBy { it.id }.toList()
    SectionCard("Threats", accent = Hostile) {
        b.threats.forEach { blk ->
            blk.title?.let { Text(it, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Hud.Text, modifier = Modifier.padding(top = 4.dp)) }
            blk.lines.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium, color = Hud.TextDim) }
        }
        if (linked.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("Threat guide", style = LocalExtra.current.overline, color = Hud.TextFaint)
            Spacer(Modifier.height(4.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                linked.forEach { t ->
                    Text(
                        "${t.name} ›", Modifier.clip(RoundedCornerShape(8.dp)).background(Hostile.copy(alpha = 0.14f)).clickable { onThreat(t) }.padding(horizontal = 10.dp, vertical = 6.dp),
                        color = Hostile, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun WeatherCard(b: Briefing) {
    val w = b.weather ?: return
    if (w.rows.isEmpty()) return
    SectionCard("Weather", accent = Hud.Cyan) {
        val same = w.rows.all { r -> r.values.distinct().size <= 1 }
        if (same) {
            w.rows.forEach { r -> KV(r.label, r.values.firstOrNull()) }
            Text("Same at take-off, target and landing", fontSize = 11.sp, color = Hud.TextFaint)
        } else {
            Row { Spacer(Modifier.width(96.dp)); w.columns.forEach { Text(it, Modifier.weight(1f), fontSize = 11.sp, color = Hud.TextFaint) } }
            w.rows.forEach { r ->
                Row(Modifier.padding(vertical = 3.dp)) {
                    Text(r.label, Modifier.width(96.dp), fontSize = 13.sp, color = Hud.TextDim)
                    r.values.forEach { Text(it, Modifier.weight(1f), fontSize = 13.sp) }
                }
            }
        }
    }
}
