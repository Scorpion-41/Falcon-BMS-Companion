package com.bmscompanion.app.ui.screens.mission

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bmscompanion.app.data.RadioEntry
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.mission.Briefing
import com.bmscompanion.app.data.mission.Contact
import com.bmscompanion.app.data.mission.Live
import com.bmscompanion.app.data.mission.MergedMission
import com.bmscompanion.app.data.mission.MissionLink
import com.bmscompanion.app.data.mission.Preset
import com.bmscompanion.app.data.mission.SupportTrack
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import com.bmscompanion.app.ui.components.SectionCard
import com.bmscompanion.app.ui.components.Tag
import com.bmscompanion.app.ui.theme.Hud
import com.bmscompanion.app.ui.theme.LocalExtra

// Tankers and other support (AWACS, JSTARS, FAC) in one table, like WDP's support board:
// callsign and aircraft, TACAN, UHF (preset), location from bullseye, and notes.
// Sources, best first: the printed briefing (support list, package, comm ladder), the theater's RadioMap (frequencies follow
// the callsign in BMS), the AWACS feed (airborne tankers and their position), and BMS's default tanker TACAN channels.

/** One support asset of the mission. */
data class SupportAsset(
    val role: String,
    val callsign: String,
    val aircraft: String?,
    val tacan: String?,
    /** true when [tacan] is BMS's default allocation (first tanker 92Y, then 126Y, 125Y…) rather than read from the briefing */
    val tacanDefault: Boolean,
    /** the channel receivers set to tie on (63 apart) */
    val tieOn: String?,
    val uhf: String?,
    val uhfCh: Int?,
    val vhf: String?,
    val notes: String?,
    val live: Contact?,
    /** bullseye bearing/range of the airborne asset */
    val loc: String?,
    /** the tanker or AWACS assigned to your flight (shared memory VoiceHelpers) */
    val yours: Boolean,
    /** every comm ladder line for this callsign (AWACS: check-in and tactical), with its preset channel */
    val radios: List<SupportRadio> = emptyList(),
    // 1.3.8
    /**
     * Where the asset is planned to be before anybody is airborne: for your flight's tanker, the Refuel steerpoint of
     * the believed route ([refuelStation]); otherwise the briefing's own sentence ("20 nm northeast of Larissa").
     */
    val station: SupportStation? = null,
    /** [station] from bullseye ("045/32"), when a bullseye is known (live, or the save's before 3D) */
    val stationLoc: String? = null,
    /** [uhfCh] is a preset the plan sent from the Planner changed (the channel now holds this asset's frequency) */
    val uhfChFromPlan: Boolean = false,
)

/** One radio of a support asset: "Check-In" 342.275 on preset 5. */
data class SupportRadio(val label: String, val uhf: String?, val ch: Int?, val vhf: String?)

/** The tanker's mint and the AWACS's lilac are for a dark map; on the chart they wash out, so they go deeper. */
val TankerColor: Color get() = if (Hud.onLightMap) Color(0xFF0B7A48) else Color(0xFF6FE3A0)
val AwacsColor: Color get() = if (Hud.onLightMap) Color(0xFF6A25A8) else Color(0xFFC39BFF)

/** Map colour of a friendly tanker / AWACS / JSTARS, or null for other aircraft. */
fun supportColor(c: Contact): Color? = if (c.friendly && c.kind == "air") when (supportRole(c.name)) {
    "Tanker" -> TankerColor
    "AWACS", "JSTARS" -> AwacsColor
    else -> null
} else null

/** Map label prefix: "TANKER Texaco1", "AWACS Dragnet5". */
fun supportLabel(c: Contact): String? = if (c.kind == "air") supportRole(c.name)?.uppercase() else null

/** Tanker: ring with a centre dot and a refuelling boom trailing behind. AWACS/JSTARS: ring with a rotodome across it. */
fun DrawScope.drawSupportSymbol(role: String, p: Offset, col: Color, hdg: Double) {
    drawCircle(Color.Black.copy(alpha = 0.6f), 10f, p)
    drawCircle(col, 8f, p, style = Stroke(3f))
    if (role == "Tanker") {
        val rad = hdg * PI / 180
        val back = Offset((-sin(rad) * 20).toFloat(), (cos(rad) * 20).toFloat())
        drawLine(Color.Black.copy(alpha = 0.6f), p, p + back, 6f)
        drawLine(col, p + back * 0.4f, p + back, 3f)
        drawCircle(col, 3f, p)
    } else {
        drawOval(Color.Black.copy(alpha = 0.6f), p - Offset(15f, 6f), Size(30f, 12f), style = Stroke(5f))
        drawOval(col, p - Offset(14f, 5f), Size(28f, 10f), style = Stroke(2.5f))
    }
}

private val TANKER_TYPES = Regex("KC-?\\d|KDC|MRTT|IL-?78|HY-?6|A310|A330|K-?35|Voyager|Tanker", RegexOption.IGNORE_CASE)
private val AWACS_TYPES = Regex("E-?3\\b|E-?3[A-Z]|E-?2|E-?7|A-?50|KJ-?\\d|EMB-?145|Erieye|Hawkeye|Wedgetail|Sentry|AWACS|AEW", RegexOption.IGNORE_CASE)
private val JSTARS_TYPES = Regex("E-?8|JSTAR", RegexOption.IGNORE_CASE)

private fun norm(s: String?) = s.orEmpty().lowercase().filter { it.isLetterOrDigit() }

/**
 * A comm ladder callsign as a key: BMS prints the tanker's TACAN into the same cell ("Copper2 (TCN: 059Y)"), which
 * kept the ladder row from ever matching the tanker it names.
 */
private fun callKey(s: String?) = norm(s?.substringBefore('('))

private fun roleOf(text: String?): String? = when {
    text == null -> null
    Regex("tanker|refuel|aar", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "Tanker"
    Regex("awacs|aew|early warning", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "AWACS"
    Regex("jstar|elint|recon", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "JSTARS"
    Regex("\\bfac\\b|forward air control", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "FAC"
    else -> null
}

/** "Tanker", "AWACS" or "JSTARS" for an aircraft type such as "KC-135R" or "EMB-145H", else null. */
fun supportRole(type: String?): String? = when {
    type == null -> null
    JSTARS_TYPES.containsMatchIn(type) -> "JSTARS"
    TANKER_TYPES.containsMatchIn(type) -> "Tanker"
    AWACS_TYPES.containsMatchIn(type) -> "AWACS"
    else -> null
}

private val TACAN_RX = Regex("\\b(\\d{1,3})\\s?([XY])\\b")

private fun tieOn(tacan: String): String? {
    val m = TACAN_RX.find(tacan) ?: return null
    val ch = m.groupValues[1].toInt()
    return "${if (ch > 63) ch - 63 else ch + 63}${m.groupValues[2]}"
}

/**
 * The support table. [uhfPresets] are the presets the pilot will have (the plan's, when one is applied), used to find
 * the channel a support frequency is on; [changedUhf] the channels the plan changed, so a ladder channel that no
 * longer holds the asset's frequency is looked up again. [stations] are the stations the briefing's sentences name
 * ([plannedStations]); [refuel] is your flight's Refuel steerpoint, which beats the sentence for your tanker.
 */
fun supportAssets(
    b: Briefing?, live: Live?, contacts: List<Contact>, radio: List<RadioEntry>, bull: Pair<Double, Double>?, uhfPresets: List<Preset> = emptyList(),
    stations: List<SupportStation> = emptyList(), refuel: SupportStation? = null, changedUhf: Set<Int> = emptySet(),
    tracks: List<SupportTrack> = emptyList(),
): List<SupportAsset> {
    fun num(s: String?) = s?.filter { it.isDigit() || it == '.' }?.toDoubleOrNull()
    fun presetOf(freq: String?): Int? {
        val f = num(freq) ?: return null
        return uhfPresets.firstOrNull { p -> num(p.freq)?.let { kotlin.math.abs(it - f) < 0.001 } == true }?.ch
    }
    /** the ladder's channel, unless the plan changed that preset away from this frequency: then where it is now */
    fun channel(ch: Int?, freq: String?): Pair<Int?, Boolean> {
        if (ch == null || ch !in changedUhf || freq == null) return (ch ?: presetOf(freq)) to false
        val now = num(uhfPresets.firstOrNull { it.ch == ch }?.freq)
        val f = num(freq)
        return if (now != null && f != null && kotlin.math.abs(now - f) < 0.001) ch to false else presetOf(freq) to true
    }
    data class Row(val role: String, val callsign: String, var aircraft: String?, var notes: String?)
    val rows = ArrayList<Row>()
    fun add(role: String, callsign: String, aircraft: String?, notes: String?) {
        val existing = rows.firstOrNull { norm(it.callsign) == norm(callsign) }
        if (existing != null) { existing.aircraft = existing.aircraft ?: aircraft; existing.notes = existing.notes ?: notes } else rows += Row(role, callsign, aircraft, notes)
    }
    b?.support?.forEach { s ->
        val role = roleOf(s.role) ?: supportRole(s.aircraft) ?: s.role ?: "Support"
        add(role, s.callsign, s.aircraft?.replace(Regex("^\\d+\\s+"), ""), s.notes)
    }
    b?.`package`?.forEach { f ->
        val role = roleOf(f.role) ?: roleOf(f.task) ?: supportRole(f.aircraft) ?: return@forEach
        add(role, f.callsign, f.aircraft, listOfNotNull(f.takeoff?.let { "T/O $it" }, f.push?.let { "on station $it" }).joinToString(" · ").ifBlank { null })
    }
    // the tanker and AWACS the campaign gave your flight's package (a save's flight with no printed briefing)
    tracks.filter { it.yours }.forEach { t -> t.callsign?.takeIf { it.isNotBlank() }?.let { add(roleOf(t.role) ?: supportRole(t.role) ?: t.role, it, null, null) } }
    live?.voice?.tanker?.takeIf { it.isNotBlank() }?.let { add("Tanker", it, null, null) }
    live?.voice?.awacs?.takeIf { it.isNotBlank() }?.let { add("AWACS", it, null, null) }
    // airborne support seen on the AWACS feed that the briefing doesn't list
    contacts.filter { it.friendly && it.kind == "air" && supportRole(it.name) != null }.forEach { c ->
        add(supportRole(c.name)!!, c.group ?: c.name ?: c.id, c.name, null)
    }

    val order = listOf("Tanker", "AWACS", "JSTARS", "FAC")
    rows.sortBy { order.indexOf(it.role).let { i -> if (i < 0) 99 else i } }
    var tankerIndex = 0
    val tankers = rows.count { it.role == "Tanker" }
    return rows.map { r ->
        val comm = b?.comms?.firstOrNull { callKey(it.callsign) == norm(r.callsign) && it.uhf != null } ?: b?.comms?.firstOrNull { callKey(it.callsign) == norm(r.callsign) }
        val radioEntry = radio.firstOrNull { norm(it.agency) == norm(r.callsign) }
        val liveContact = contacts.firstOrNull { norm(it.group) == norm(r.callsign) && it.kind == "air" }
        val explicit = listOfNotNull(r.notes, comm?.notes, comm?.callsign).firstNotNullOfOrNull { TACAN_RX.find(it)?.value?.replace(" ", "") }
        var tacan = explicit
        var isDefault = false
        if (r.role == "Tanker") {
            if (tacan == null) {
                tacan = if (tankerIndex == 0) "92Y" else "${127 - tankerIndex}Y"
                isDefault = true
            }
            tankerIndex++
        }
        val yours = norm(r.callsign) == norm(live?.voice?.tanker) || norm(r.callsign) == norm(live?.voice?.awacs)
        // the campaign's own planned track for this callsign is where it will be; else your tanker is where your route
        // refuels, which beats any sentence; the others are where the briefing says
        val station = stations.firstOrNull { it.fromText == TRACK_STATION && norm(it.callsign) == norm(r.callsign) }
            ?: if (r.role == "Tanker" && refuel != null && (yours || tankers == 1)) refuel.copy(callsign = r.callsign)
            else stations.firstOrNull { norm(it.callsign) == norm(r.callsign) }
        val (uhfCh, moved) = channel(comm?.uhfCh, comm?.uhf ?: radioEntry?.uhf1)
        SupportAsset(
            role = r.role, callsign = r.callsign, aircraft = r.aircraft ?: liveContact?.name,
            tacan = tacan, tacanDefault = isDefault, tieOn = if (r.role == "Tanker") tacan?.let(::tieOn) else null,
            uhf = comm?.uhf ?: radioEntry?.uhf1, uhfCh = uhfCh, vhf = comm?.vhf ?: radioEntry?.vhf,
            notes = r.notes, live = liveContact,
            loc = liveContact?.let { c -> bull?.let { bra(it.first, it.second, c.x, c.y) } },
            yours = yours,
            radios = b?.comms.orEmpty().filter { callKey(it.callsign) == norm(r.callsign) && (it.uhf != null || it.vhf != null) }
                .map { SupportRadio(it.agency.trimEnd(':'), it.uhf, channel(it.uhfCh, it.uhf).first, it.vhf) }
                .ifEmpty { listOfNotNull(radioEntry?.takeIf { it.uhf1 != null }?.let { SupportRadio("Radio plan", it.uhf1, presetOf(it.uhf1), it.vhf) }) },
            station = station,
            stationLoc = station?.let { s -> bull?.let { bra(it.first, it.second, s.x, s.y) } },
            uhfChFromPlan = moved,
        )
    }
}

/**
 * Your flight's Refuel steerpoint (action 4), placed: where the flight meets its tanker. From the merged mission
 * (PlanMerge: the plan, the cartridge, BMS's own mission file — which is only there once it is provably the briefed
 * flight's), else BMS's mission file itself, else the flight the Planner opened from a save. Null when none is placed.
 */
fun refuelStation(merged: MergedMission): SupportStation? {
    fun at(n: Int, x: Double, y: Double) = SupportStation("", "Tanker", x, y, "your route's Refuel steerpoint $n")
    merged.allSteerpoints.firstOrNull { it.action == 4 && it.hasPos && it.onRoute }?.let { return at(it.n, it.x, it.y) }
    merged.route?.steerpoints?.firstOrNull { it.action == 4 && (it.x != 0.0 || it.y != 0.0) }?.let { return at(it.n, it.x, it.y) }
    merged.plan?.flight?.route?.firstOrNull { it.action == 4 && (it.x != 0.0 || it.y != 0.0) }?.let { return at(it.n, it.x, it.y) }
    return null
}

/**
 * The tanker and AWACS stations of the merged mission: your tanker at your route's Refuel steerpoint when the briefing
 * has one tanker (the route places it exactly, and beats the sentence), every other station where the briefing's
 * support section says ("orbiting 20 nm northeast of Larissa", [stationFromNotes]), resolved against this theater's
 * airfields ([set]) and towns ([geo]). A sentence naming a place this theater does not have is left out.
 */
fun plannedStations(
    merged: MergedMission, set: com.bmscompanion.app.data.AirportSet?, geo: com.bmscompanion.app.data.GeoLayers?,
    tracks: List<SupportTrack> = emptyList(),
): List<SupportStation> {
    val fromTracks = tracks.mapNotNull(::trackStation)
    val entries = merged.briefing?.support.orEmpty()
    // a save's flight with no printed briefing has no support sentences: its package's own tracks are the stations
    if (entries.isEmpty()) return fromTracks.filter { st -> tracks.any { it.yours && norm(it.callsign) == norm(st.callsign) } }
    fun one(name: String): Pair<Double, Double>? {
        val n = name.trim().lowercase()
        if (n.length < 3) return null
        set?.airports?.firstOrNull { it.name.lowercase().startsWith(n) || it.icao?.lowercase() == n }?.let { return it.x to it.y }
        geo?.places?.firstOrNull { it.n.lowercase() == n }?.let { return it.x to it.y }
        return geo?.places?.firstOrNull { it.n.lowercase().startsWith(n) }?.let { it.x to it.y }
    }
    // a briefing writes a name the way a pilot says it ("Larissa Airbase"), a map the way it is: try both
    fun place(name: String): Pair<Double, Double>? = one(name) ?: name.trim().substringBefore(' ').takeIf { it.length >= 3 }?.let { one(it) }
    val refuel = refuelStation(merged)
    val tankers = entries.count { it.role?.contains("tanker", true) == true }
    return entries.mapNotNull { e ->
        val role = e.role ?: return@mapNotNull null
        if (!role.contains("tanker", true) && !role.contains("awacs", true) && !role.contains("jstars", true)) return@mapNotNull null
        fromTracks.firstOrNull { norm(it.callsign) == norm(e.callsign) }?.let { return@mapNotNull it.copy(role = role) }
        if (refuel != null && tankers == 1 && role.contains("tanker", true)) return@mapNotNull refuel.copy(callsign = e.callsign, role = role)
        val (at, text) = stationFromNotes(e.notes, ::place) ?: return@mapNotNull null
        SupportStation(e.callsign, role, at.first, at.second, text)
    }
}

/** What a station placed on the campaign's own track says it is. */
const val TRACK_STATION = "its planned track (the campaign's)"

/**
 * A planned track's station: the middle of the legs it holds on, or null when the campaign gave it none. The place a
 * tanker or an AWACS will actually be, which beats both the briefing's sentence and your route's Refuel steerpoint
 * (on the 4.38.1 fixture the Refuel steerpoint sits on another tanker's track than the one the briefing names).
 */
fun trackStation(t: SupportTrack): SupportStation? {
    val on = t.points.filter { it.station }
    if (on.isEmpty() || t.callsign.isNullOrBlank()) return null
    return SupportStation(t.callsign, t.role, on.map { it.x }.average(), on.map { it.y }.average(), TRACK_STATION)
}

/**
 * The tanker and AWACS tracks to draw: the campaign's, as the PC read them for the briefed flight; with none (no
 * printed briefing, so no believed route) the tracks of the package's own tanker and AWACS that the save's flight
 * the Planner sent carries — the WDP-only path.
 */
fun plannedTracks(tracks: List<SupportTrack>, merged: MergedMission): List<SupportTrack> {
    if (tracks.isNotEmpty()) return tracks
    val plan = merged.plan ?: return emptyList()
    val flight = plan.flight ?: return emptyList()
    val its = merged.fromSave || merged.printed?.let { com.bmscompanion.app.data.mission.PlanMerge.sameFlight(it, plan) } == true
    if (!its) return emptyList()
    return flight.support.filter { it.track.size >= 2 && it.callsign.isNotBlank() }
        .map { SupportTrack(role = it.role, callsign = it.callsign, yours = true, points = it.track) }
}

/**
 * Support assets for the current mission, refreshed with the live data: from the merged mission (the printed
 * briefing, or the save's flight the Planner sent; the plan's presets), with each station placed before 3D.
 */
@Composable
fun rememberSupportAssets(env: MissionEnv): List<SupportAsset> {
    val merged = rememberMerged()
    val live by MissionLink.live.collectAsState()
    val contacts by MissionLink.contacts.collectAsState()
    val radio by produceState(emptyList<RadioEntry>(), env.theater?.radioSet) { value = env.theater?.radioSet?.takeIf { it.isNotBlank() }?.let { Repo.radio(it) }.orEmpty() }
    // the theater's towns, so "20 nm northeast of Larissa" can be turned back into a place
    val geo by produceState<com.bmscompanion.app.data.GeoLayers?>(null, env.theater?.mapId) {
        value = env.theater?.mapId?.let { runCatching { Repo.geo(it) }.getOrNull() }
    }
    val mission by MissionLink.mission.collectAsState()
    val ctcs = contacts?.contacts.orEmpty()
    return remember(merged, live?.voice, ctcs, radio, geo, env.set, mission?.tracks) {
        val tracks = plannedTracks(mission?.tracks.orEmpty(), merged)
        val uhf = merged.presets.filter { it.band == "UHF" }.map { Preset(it.ch, it.freq, it.comment) }
        // before 3D the save's own bullseye stands in, as on the map
        val bull = bullseye(live, ctcs) ?: merged.saveBullseye?.takeIf { !merged.inJet }
        supportAssets(
            merged.briefing, live, ctcs, radio, bull, uhf,
            stations = plannedStations(merged, env.set, geo, tracks), refuel = refuelStation(merged),
            changedUhf = merged.presets.filter { it.band == "UHF" && it.changed }.map { it.ch }.toSet(),
            tracks = tracks,
        )
    }
}

@Composable
fun SupportCard(assets: List<SupportAsset>, title: String = "Tankers & support") {
    val summary = assets.groupingBy { it.role }.eachCount().entries.joinToString(" · ") { (role, n) -> if (n == 1) role else "$n ${role}s" }
    SectionCard(title, accent = Hud.Cyan, trailing = { if (assets.isNotEmpty()) Text(summary, fontSize = 11.sp, color = Hud.TextDim) }) {
        if (assets.isEmpty()) {
            Text("No tankers or AWACS in this mission yet. They come from the printed briefing, and airborne ones from the AWACS feed.", fontSize = 13.sp, color = Hud.TextDim)
            return@SectionCard
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val wide = maxWidth >= 520.dp
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (wide) Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                    Head("ROLE", 70); Head("CALLSIGN · AIRCRAFT", 0, Modifier.weight(1f)); Head("TACAN", 78); Head("UHF", 96); Head("LOC", 70)
                }
                assets.forEach { a -> if (wide) WideRow(a) else CompactRow(a) }
                if (assets.none { it.role == "Tanker" }) Text(
                    "No tanker in the briefing or on the AWACS feed. Ask AWACS for \"Vector to nearest tanker\" to get one's TACAN and UHF.",
                    fontSize = 11.sp, color = Hud.TextFaint, lineHeight = 15.sp,
                )
                if (assets.any { it.tacanDefault }) Text(
                    "* BMS default tanker TACAN (first tanker 92Y, then 126Y, 125Y…). Tie on with the channel after \"set\". AWACS \"Vector to tanker\" confirms it.",
                    fontSize = 11.sp, color = Hud.TextFaint, lineHeight = 15.sp,
                )
            }
        }
    }
}

@Composable
private fun Head(text: String, widthDp: Int, modifier: Modifier = Modifier) =
    Text(text, if (widthDp > 0) modifier.width(widthDp.dp) else modifier, fontSize = 10.sp, color = Hud.TextFaint, fontWeight = FontWeight.SemiBold)

private fun roleColor(role: String) = when (role) { "Tanker" -> Hud.Green; "AWACS" -> Hud.Cyan; "JSTARS" -> Hud.Magenta; else -> Hud.Amber }

@Composable
private fun WideRow(a: SupportAsset) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (a.yours) Hud.Amber.copy(alpha = 0.10f) else Hud.Surface2).padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(a.role, Modifier.width(70.dp), fontSize = 12.sp, color = roleColor(a.role), fontWeight = FontWeight.Bold)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(a.callsign, fontSize = 15.sp, color = Hud.Text, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    if (a.yours) { Spacer(Modifier.width(6.dp)); Tag("YOURS", Hud.Amber, filled = true) }
                    if (a.live != null) { Spacer(Modifier.width(6.dp)); Tag("AIRBORNE", Hud.Green) }
                }
                a.aircraft?.let { Text(it, fontSize = 11.sp, color = Hud.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            Column(Modifier.width(78.dp)) {
                Text((a.tacan ?: "—") + if (a.tacanDefault) "*" else "", style = LocalExtra.current.mono, color = if (a.tacan != null) Hud.Text else Hud.TextFaint)
                a.tieOn?.let { Text("set $it", fontSize = 10.sp, color = Hud.TextDim) }
            }
            Column(Modifier.width(96.dp)) {
                Text(a.uhf ?: "—", style = LocalExtra.current.mono, color = if (a.uhf != null) Hud.Amber else Hud.TextFaint)
                listOfNotNull(a.uhfCh?.let { "ch $it" } ?: if (a.uhfChFromPlan) "no preset" else null, a.vhf?.let { "V $it" }).joinToString(" · ").takeIf { it.isNotBlank() }?.let {
                    Text(it, fontSize = 10.sp, color = if (a.uhfChFromPlan) PlanInk else Hud.TextDim, maxLines = 1)
                }
            }
            Column(Modifier.width(70.dp)) {
                val loc = a.loc ?: a.stationLoc
                Text(loc ?: "—", style = LocalExtra.current.mono, color = if (a.loc != null) Hud.Cyan else if (loc != null) Hud.Cyan.copy(alpha = 0.7f) else Hud.TextFaint)
                a.live?.let { Text(flightLevel(it.altFt), fontSize = 10.sp, color = Hud.TextDim) } ?: a.stationLoc?.let { Text("station", fontSize = 10.sp, color = Hud.TextDim) }
            }
        }
        RadiosLine(a, Modifier.padding(start = 70.dp, top = 3.dp))
        StationLine(a, Modifier.padding(start = 70.dp, top = 2.dp))
        a.notes?.let { Text(it, fontSize = 11.sp, color = Hud.TextDim, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 70.dp, top = 2.dp)) }
    }
}

/**
 * "STATION  your route's Refuel steerpoint 6" / "20 nm northeast of Larissa": where the asset is planned to be, and
 * what that came from. The channel the plan moved is said here too, because the ladder's channel is no longer it.
 */
@Composable
private fun StationLine(a: SupportAsset, modifier: Modifier = Modifier) {
    val st = a.station
    if (st == null && !a.uhfChFromPlan) return
    Column(modifier) {
        if (st != null) Row {
            Text("STATION", Modifier.alignByBaseline(), fontSize = 9.sp, color = Hud.TextFaint, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(8.dp))
            Text(st.fromText + (a.stationLoc?.let { " · bullseye $it" } ?: ""), Modifier.alignByBaseline(), fontSize = 11.sp, color = Hud.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (a.uhfChFromPlan) Text(
            "The plan changed the briefed preset for ${a.uhf ?: "this frequency"}: " + (a.uhfCh?.let { "it is on preset $it now" } ?: "no preset holds it now, dial it manually") + ".",
            fontSize = 11.sp, color = PlanInk, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** "COMMS  Check-In 342.275 · ch 5    Tactical 399.125 · ch 6" */
@Composable
private fun RadiosLine(a: SupportAsset, modifier: Modifier = Modifier) {
    if (a.radios.isEmpty()) return
    Row(modifier.horizontalScroll(rememberScrollState())) {
        Text("COMMS", Modifier.alignByBaseline(), fontSize = 9.sp, color = Hud.TextFaint, fontWeight = FontWeight.SemiBold)
        a.radios.forEach { r ->
            Spacer(Modifier.width(10.dp))
            Text(r.label, Modifier.alignByBaseline(), fontSize = 11.sp, color = Hud.TextDim)
            Spacer(Modifier.width(4.dp))
            Text(r.uhf ?: r.vhf ?: "—", Modifier.alignByBaseline(), style = LocalExtra.current.mono.copy(fontSize = 12.sp), color = Hud.Amber)
            r.ch?.let { Text(" · ch $it", Modifier.alignByBaseline(), fontSize = 11.sp, color = Hud.Text, fontWeight = FontWeight.SemiBold) }
            if (r.uhf != null && r.vhf != null) Text(" · V ${r.vhf}", Modifier.alignByBaseline(), fontSize = 11.sp, color = Hud.TextDim)
        }
    }
}

@Composable
private fun CompactRow(a: SupportAsset) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (a.yours) Hud.Amber.copy(alpha = 0.10f) else Hud.Surface2).padding(horizontal = 10.dp, vertical = 7.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(a.role.uppercase(), fontSize = 10.sp, color = roleColor(a.role), fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            Text(a.callsign, Modifier.weight(1f), fontSize = 15.sp, color = Hud.Text, fontWeight = FontWeight.SemiBold, maxLines = 1)
            if (a.yours) Tag("YOURS", Hud.Amber, filled = true)
            if (a.live != null) { Spacer(Modifier.width(4.dp)); Tag("AIRBORNE", Hud.Green) }
        }
        a.aircraft?.let { Text(it, fontSize = 11.sp, color = Hud.TextDim, maxLines = 1) }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Stat("TACAN", (a.tacan ?: "—") + if (a.tacanDefault) "*" else "", a.tieOn?.let { "set $it" }, Hud.Text)
            Stat("UHF", a.uhf ?: "—", a.uhfCh?.let { "ch $it" }, Hud.Amber)
            Stat("LOC", a.loc ?: a.stationLoc ?: "—", a.live?.let { flightLevel(it.altFt) } ?: a.stationLoc?.let { "station" }, Hud.Cyan)
        }
        RadiosLine(a, Modifier.padding(top = 4.dp))
        StationLine(a, Modifier.padding(top = 3.dp))
        a.notes?.let { Text(it, fontSize = 11.sp, color = Hud.TextDim, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp)) }
    }
}

@Composable
private fun Stat(label: String, value: String, sub: String?, color: Color) {
    Column {
        Text(label, fontSize = 9.sp, color = Hud.TextFaint, fontWeight = FontWeight.SemiBold)
        Text(value, style = LocalExtra.current.mono, color = if (value == "—") Hud.TextFaint else color)
        sub?.let { Text(it, fontSize = 10.sp, color = Hud.TextDim) }
    }
}
