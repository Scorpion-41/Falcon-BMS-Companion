package com.bmscompanion.app.ui.screens.mission

import com.bmscompanion.app.data.mission.ownFlight
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
    val live by MissionLink.live.collectAsState()
    val contacts by MissionLink.contacts.collectAsState()
    // the briefing with BMS's route and the sent plan merged in: with no printed briefing, the plan's own flight
    val merged = rememberMerged()
    val b = merged.briefing
    val own = ownship(live)
    // before 3D the save's own bullseye stands in for the live one (marked where it is used)
    val liveBull = bullseye(live, contacts?.contacts)
    val bull = liveBull ?: merged.saveBullseye?.takeIf { !merged.inJet }
    // the threat reference: the Threat Guide's entries for the systems the briefing and the PPTs name
    val threats by produceState<List<com.bmscompanion.app.data.Threat>>(emptyList()) { value = Repo.threats() }
    val ppts = androidx.compose.runtime.remember(merged) { preplannedPoints(merged) }
    val openThreat: (com.bmscompanion.app.data.Threat) -> Unit = { t -> env.nav.go("m/threat/${android.net.Uri.encode(t.id)}") }
    val showPpt: (Int) -> Unit = { i -> ppts.getOrNull(i)?.let { p -> showOnMap(MapSel.Ppt(i), p.x, p.y) } }
    // WDP mode: everything here is the Planner's populated flight, and the source line at the top says so
    val wdp = merged.plan?.source == com.bmscompanion.app.data.mission.PlanSource.POPULATED
    if (b == null) {
        if (!merged.planApplied) {
            PaneEmpty(
                "No briefing yet",
                "In Falcon BMS open the mission Briefing and press PRINT (top right).\nThe app picks it up automatically. See Setup if nothing appears.",
            )
            return
        }
        // a plan with no briefing and no flight: what the cartridge carries is still the pilot's
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
            if (wdp) BriefingSourceNote("No briefing", "The flight populated from the Planner carries no briefing: only your cartridge's items are shown.")
            else BriefingSourceNote("No briefing", "The plan from the Planner carries no flight and BMS has printed no briefing: only the cartridge's items are shown. Press PRINT in BMS for the briefing, or open a mission in the Planner and pick your flight.")
            Masonry(minColumn = 420.dp, maxColumns = 3) {
                SteerpointTable(steerpoints(merged), own, bull, anyPlan = merged.planApplied) { s -> if (s.hasPos) showOnMap(MapSel.Stp(s.n), s.x!!, s.y!!) }
                TargetsCard(merged, bull, showOnMap)
                ThreatCard(null, threats, ppts, bull, showPpt, openThreat)
                PlanCard(merged)
            }
        }
        return
    }
    val weapons by produceState<Map<String, Weapon>>(emptyMap()) { value = Repo.weapons().associateBy { it.name.norm() } }
    val stpts = steerpoints(merged)
    val bases = airbases(live, merged, contacts?.contacts)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        if (merged.fromSave && !wdp) {
            val file = merged.saveFile ?: "the Planner's save"
            BriefingSourceNote(
                "From your save $file" + if (merged.printed == null) " (no printed briefing)" else " (the printed briefing is for another flight)",
                "Built from the flight in the save: overview, steerpoints, package and loadout. Press PRINT in BMS for the situation, weather, comm ladder and ROE.",
            )
        }
        Masonry(minColumn = 420.dp, maxColumns = 3) {
            OverviewCard(b, live?.voice?.flight)
            AirbasesCard(env, bases)
            SteerpointTable(stpts, own, bull, anyPlan = merged.planApplied) { s -> if (s.hasPos) showOnMap(MapSel.Stp(s.n), s.x!!, s.y!!) }
            TargetsCard(merged, bull, showOnMap)
            PlanCard(merged)
            PackageCard(b)
            LoadoutCard(b, weapons) { w -> env.nav.go("m/weapon/${android.net.Uri.encode(w.key)}") }
            ThreatCard(b, threats, ppts, bull, showPpt, openThreat)
            SupportCard(rememberSupportAssets(env))
            // WDP mode's weather is the save's own file as Populate read it: where there is none, the card says why
            if (wdp && b.weather?.rows.isNullOrEmpty()) SectionCard("Weather", accent = Hud.Cyan) {
                Text(wdpNoWeather(), fontSize = 12.sp, color = Hud.TextDim, lineHeight = 17.sp)
            } else WeatherCard(b)
            TextCard("Situation", b.situation, Hud.Cyan, threshold = 200)
            if (b.roe.isNotEmpty()) TextCard("Rules of engagement", b.roe.joinToString("\n"), Hud.Amber, threshold = 200)
            if (b.emergency.isNotEmpty()) CollapsibleCard("Emergency procedures", accent = Hud.Red, preview = b.alternate?.let { "Alternate: $it" }) {
                b.emergency.forEach { blk ->
                    blk.title?.let { Text(it, fontWeight = FontWeight.SemiBold, color = Hud.Text, modifier = Modifier.padding(top = 6.dp)) }
                    blk.lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = Hud.TextDim) }
                }
            }
        }
        val foot = if (merged.fromSave) merged.printed?.generated?.let { "The printed briefing (${merged.printed.overview.flight ?: "another flight"}) is from $it" }
            else b.generated?.let { "Briefing printed $it" }
        foot?.let { Text(it, fontSize = 11.sp, color = Hud.TextFaint, modifier = Modifier.padding(top = 10.dp, start = 4.dp)) }
        if (bull != null && liveBull == null) Text("BULLS figures are from the save's own bullseye until BMS is in 3D.", fontSize = 11.sp, color = Hud.TextFaint, modifier = Modifier.padding(start = 4.dp))
    }
}

/** A strip above the cards saying where this briefing came from, when it is not BMS's printed one. */
@Composable
private fun BriefingSourceNote(title: String, text: String) {
    Column(
        Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(12.dp)).background(PlanInk.copy(alpha = 0.08f))
            .border(1.dp, PlanInk.copy(alpha = 0.4f), RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(title, color = PlanInk, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Text(text, color = Hud.TextDim, fontSize = 12.sp)
    }
}

@Composable
fun OverviewCard(b: Briefing, flight: String?) {
    val o = b.overview
    val mine = b.ownFlight(flight)
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
    val dep = bases.departureIn(env.set)
    val arr = bases.arrivalIn(env.set)
    // one base both ways is home plate; the same word for both ("USS") is not, when the two resolve to different ships
    val home = bases.arrival != null && bases.arrival == bases.departure && (dep == null || arr == null || dep.id == arr.id)
    val rows = listOf(Triple("DEPARTURE", bases.departure, dep), Triple("RECOVERY", bases.arrival, arr), Triple("ALTERNATE", bases.alternate, bases.alternateIn(env.set)))
        .filter { it.second != null }
    if (rows.isEmpty()) return
    SectionCard("Airbases", accent = Hud.Green) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            rows.forEach { (role, name, a) ->
                if (role == "RECOVERY" && home) return@forEach
                AirbaseRow(env, if (role == "DEPARTURE" && home) "HOME PLATE" else role, name!!, a)
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
            if (clickable) Text("Coords & charts ›", color = Hud.Amber, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
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
private fun SteerpointTable(stpts: List<Stpt>, own: Pair<Double, Double>?, bull: Pair<Double, Double>?, anyPlan: Boolean = false, onPick: (Stpt) -> Unit) {
    if (stpts.isEmpty()) return
    val planned = anyPlan && stpts.any { it.fromPlan || it.notInJet }
    SectionCard("Flight plan", accent = Hud.Amber, trailing = {
        if (planned) PlanTags(true, stpts.any { it.notInJet }, Modifier.padding(end = 6.dp))
        Text("tap to show on map", fontSize = 10.sp, color = Hud.TextFaint)
    }) {
        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
            Head("#", 28.dp); Head("STPT", null, Modifier.weight(1f)); Head("TOS", 74.dp); Head("ALT", 52.dp); Head(if (bull != null) "BULLS" else "", 62.dp)
        }
        stpts.forEach { s ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable(enabled = s.hasPos) { onPick(s) }.padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${s.n}", Modifier.width(28.dp), style = LocalExtra.current.monoSmall, color = if (s.fromPlan || s.notInJet) PlanInk else if (s.isTarget) Hostile else Hud.Amber)
                Column(Modifier.weight(1f)) {
                    Text(
                        s.title, fontSize = 13.sp, color = if (s.cleared) Hud.TextFaint else if (s.isTarget) Hostile else Hud.Text,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        textDecoration = if (s.cleared) androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
                    )
                    // the plan's mark leads the second line, so the name keeps the width on a phone
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PlanTags(s.fromPlan, s.notInJet, Modifier.padding(end = 6.dp), compact = true)
                        if (s.cleared) ClearedTag(Modifier.padding(end = 6.dp))
                        listOfNotNull(
                            (s.action ?: if (s.planRow) com.bmscompanion.app.data.mission.PlanMerge.actionWord(s.actionCode) else null)?.takeIf { it != s.title },
                            s.cas?.let { "$it kt" },
                            s.comments?.takeIf { it != s.desc },
                            if (!s.onRoute && s.isTarget && s.desc == null) "precision target" else null,
                        ).joinToString(" · ").takeIf { it.isNotBlank() }?.let {
                            Text(it, fontSize = 11.sp, color = Hud.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                Text(s.time?.removeSuffix("z") ?: "—", Modifier.width(74.dp), style = LocalExtra.current.monoSmall)
                Text(s.altText ?: "—", Modifier.width(52.dp), style = LocalExtra.current.monoSmall, color = Hud.TextDim)
                Text(if (bull != null && s.hasPos) bra(bull.first, bull.second, s.x!!, s.y!!) else "", Modifier.width(62.dp), style = LocalExtra.current.monoSmall, color = Hud.Cyan)
            }
        }
        if (stpts.none { it.hasPos }) Text("Save the DTC in BMS (or enter 3D) to get steerpoint positions on the map.", fontSize = 11.sp, color = Hud.TextFaint, modifier = Modifier.padding(top = 6.dp))
        if (stpts.any { it.planRow }) Text("PLAN rows are steerpoints the Planner added; the briefing does not have them.", fontSize = 11.sp, color = Hud.TextFaint, modifier = Modifier.padding(top = 4.dp))
        if (stpts.any { it.notInJet }) Text("NOT IN JET: Save to DTC in the Planner, then LOAD in BMS's DTC window.", fontSize = 11.sp, color = Hud.TextFaint, modifier = Modifier.padding(top = 2.dp))
        if (own != null) Spacer(Modifier.height(2.dp))
    }
}

@Composable
private fun Head(text: String, width: Dp?, modifier: Modifier = Modifier) {
    Text(text, (if (width != null) Modifier.width(width) else modifier), fontSize = 10.sp, color = Hud.TextFaint, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun TargetsCard(merged: com.bmscompanion.app.data.mission.MergedMission, bull: Pair<Double, Double>?, showOnMap: (MapSel, Double, Double) -> Unit) {
    // the plan's target steerpoints and weapon targets replace the cartridge's slot for slot; a slot the plan cleared
    // stays, struck through, because the jet still has it until the pilot saves
    val targets = merged.targets
    if (targets.isEmpty()) return
    val planned = merged.planApplied && targets.any { it.source == com.bmscompanion.app.data.mission.PlanItemSource.PLAN || it.notInJet || it.cleared }
    SectionCard("Targets (DTC)", accent = Hostile, trailing = { if (planned) PlanTags(true, targets.any { it.notInJet }) }) {
        targets.forEach { t ->
            val fromPlan = t.source == com.bmscompanion.app.data.mission.PlanItemSource.PLAN
            // a target steerpoint opens as itself on the map (its name, and the charts of the field it stands on)
            val on = if (!t.weapon && t.n in 1..25) MapSel.Stp(t.n) else MapSel.Pt(t.x, t.y)
            Row(Modifier.fillMaxWidth().clickable(enabled = t.hasPos) { showOnMap(on, t.x, t.y) }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Tag(if (t.weapon) "WPN ${t.n}" else "STPT ${t.n}", if (fromPlan || t.notInJet) PlanInk else Hostile, filled = !t.cleared)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        t.name ?: "Target", fontSize = 13.sp, color = if (t.cleared) Hud.TextFaint else Hud.Text,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                        textDecoration = if (t.cleared) androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PlanTags(fromPlan, t.notInJet, Modifier.padding(end = 6.dp), compact = true)
                        Text(
                            if (t.cleared) "cleared in the Planner — still in the jet until saved" else "%,d ft".format(t.altFt.toInt()),
                            fontSize = 11.sp, color = Hud.TextDim,
                        )
                    }
                }
                if (t.hasPos) bull?.let { Text("BULLS ${bra(it.first, it.second, t.x, t.y)}", style = LocalExtra.current.monoSmall, color = Hud.Cyan) }
            }
        }
    }
}

@Composable
private fun PackageCard(b: Briefing) {
    if (b.`package`.isEmpty()) return
    SectionCard("Package", accent = Hud.Cyan, trailing = { b.overview.packageId?.let { Text("#$it", style = LocalExtra.current.monoSmall, color = Hud.TextDim) } }) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // yours is highlighted; BMS's "x" (the package's primary flight, its lead tasking) is a small tag
            val own = b.ownFlight()
            b.`package`.forEach { f ->
                val mineRow = own != null && f === own
                val pilots = b.roster.firstOrNull { it.callsign == f.callsign }?.pilots.orEmpty()
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (mineRow) Hud.Amber.copy(alpha = 0.10f) else Hud.Surface2)
                        .border(1.dp, if (mineRow) Hud.Amber.copy(alpha = 0.5f) else Color.Transparent, RoundedCornerShape(10.dp)).padding(10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(f.callsign, style = MaterialTheme.typography.titleSmall, color = if (mineRow) Hud.Amber else Hud.Text)
                        Spacer(Modifier.width(8.dp))
                        if (mineRow) { Tag("YOU", Hud.Amber); Spacer(Modifier.width(6.dp)) }
                        if (f.primary) { Tag("PRIMARY", Hud.TextDim); Spacer(Modifier.width(6.dp)) }
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

/**
 * The briefing's threat section, then the pre-planned threats the cartridge carries — the Planner's among them, marked
 * PLAN and NOT IN JET — each a tap from its place on the map, and every system named a tap from its Threat Guide entry.
 */
@Composable
private fun ThreatCard(
    b: Briefing?, threats: List<com.bmscompanion.app.data.Threat>, ppts: List<Threat>, bull: Pair<Double, Double>?,
    onPpt: (Int) -> Unit, onThreat: (com.bmscompanion.app.data.Threat) -> Unit,
) {
    val blocks = b?.threats.orEmpty()
    if (blocks.isEmpty() && ppts.isEmpty()) return
    val text = blocks.flatMap { it.lines }.joinToString("\n")
    val fromText = threatToken.findAll(text).map { it.value.norm() }.distinct().mapNotNull { tok ->
        threats.firstOrNull { t -> t.name.norm().startsWith(tok) || t.aliases.any { it.norm() == tok } || t.id.norm().startsWith(tok) }
    }.toList()
    val fromPpts = ppts.filter { !it.marker && !it.cleared }.mapNotNull { threatGuideEntry(it.name, threats) }
    val linked = (fromPpts + fromText).distinctBy { it.id }
    val planned = ppts.any { it.fromPlan || it.notInJet }
    SectionCard("Threats", accent = Hostile, trailing = { if (planned) PlanTags(true, ppts.any { it.notInJet }) }) {
        blocks.forEach { blk ->
            blk.title?.let { Text(it, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Hud.Text, modifier = Modifier.padding(top = 4.dp)) }
            blk.lines.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium, color = Hud.TextDim) }
        }
        if (ppts.isNotEmpty()) {
            Text(
                "Pre-planned (DTC) · tap to show on map", style = LocalExtra.current.overline, color = Hud.TextFaint,
                modifier = Modifier.padding(top = if (blocks.isEmpty()) 0.dp else 10.dp, bottom = 2.dp),
            )
            // the rings first, then the markers (an AWACS, a tanker, a friendly: points with no ring)
            ppts.withIndex().sortedBy { it.value.marker }.forEach { (i, p) ->
                val plan = p.fromPlan || p.notInJet
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable { onPpt(i) }.padding(vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Tag("PPT ${p.n}", if (plan) PlanInk else if (p.marker) Friendly else Hostile, filled = !p.cleared)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            p.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (p.cleared) Hud.TextFaint else Hud.Text,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            textDecoration = if (p.cleared) androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PlanTags(p.fromPlan, p.notInJet, Modifier.padding(end = 6.dp), compact = true)
                            if (p.cleared) ClearedTag(Modifier.padding(end = 6.dp))
                            Text(if (p.marker) "marker" else "%.0f nm ring".format(java.util.Locale.US, p.rangeNm), fontSize = 11.sp, color = Hud.TextDim)
                        }
                    }
                    bull?.let { Text("BULLS ${bra(it.first, it.second, p.x, p.y)}", style = LocalExtra.current.monoSmall, color = Hud.Cyan) }
                }
            }
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
fun WeatherCard(b: Briefing) {
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
        // WDP mode: the save's weather file it was read from, and as of when (BMS's printed forecast names none)
        w.source?.let { Text(it, Modifier.padding(top = 6.dp), fontSize = 11.sp, color = Hud.TextFaint, lineHeight = 15.sp) }
    }
}
