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
import com.bmscompanion.app.data.mission.MissionLink
import com.bmscompanion.app.data.mission.Preset
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
)

/** One radio of a support asset: "Check-In" 342.275 on preset 5. */
data class SupportRadio(val label: String, val uhf: String?, val ch: Int?, val vhf: String?)

val TankerColor = Color(0xFF6FE3A0)
val AwacsColor = Color(0xFFC39BFF)

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

fun supportAssets(b: Briefing?, live: Live?, contacts: List<Contact>, radio: List<RadioEntry>, bull: Pair<Double, Double>?, uhfPresets: List<Preset> = emptyList()): List<SupportAsset> {
    fun presetOf(freq: String?): Int? {
        val f = freq?.filter { it.isDigit() || it == '.' }?.toDoubleOrNull() ?: return null
        return uhfPresets.firstOrNull { p -> p.freq.filter { it.isDigit() || it == '.' }.toDoubleOrNull()?.let { kotlin.math.abs(it - f) < 0.001 } == true }?.ch
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
    live?.voice?.tanker?.takeIf { it.isNotBlank() }?.let { add("Tanker", it, null, null) }
    live?.voice?.awacs?.takeIf { it.isNotBlank() }?.let { add("AWACS", it, null, null) }
    // airborne support seen on the AWACS feed that the briefing doesn't list
    contacts.filter { it.friendly && it.kind == "air" && supportRole(it.name) != null }.forEach { c ->
        add(supportRole(c.name)!!, c.group ?: c.name ?: c.id, c.name, null)
    }

    val order = listOf("Tanker", "AWACS", "JSTARS", "FAC")
    rows.sortBy { order.indexOf(it.role).let { i -> if (i < 0) 99 else i } }
    var tankerIndex = 0
    return rows.map { r ->
        val comm = b?.comms?.firstOrNull { norm(it.callsign) == norm(r.callsign) && it.uhf != null } ?: b?.comms?.firstOrNull { norm(it.callsign) == norm(r.callsign) }
        val radioEntry = radio.firstOrNull { norm(it.agency) == norm(r.callsign) }
        val liveContact = contacts.firstOrNull { norm(it.group) == norm(r.callsign) && it.kind == "air" }
        val explicit = listOfNotNull(r.notes, comm?.notes).firstNotNullOfOrNull { TACAN_RX.find(it)?.value?.replace(" ", "") }
        var tacan = explicit
        var isDefault = false
        if (r.role == "Tanker") {
            if (tacan == null) {
                tacan = if (tankerIndex == 0) "92Y" else "${127 - tankerIndex}Y"
                isDefault = true
            }
            tankerIndex++
        }
        SupportAsset(
            role = r.role, callsign = r.callsign, aircraft = r.aircraft ?: liveContact?.name,
            tacan = tacan, tacanDefault = isDefault, tieOn = if (r.role == "Tanker") tacan?.let(::tieOn) else null,
            uhf = comm?.uhf ?: radioEntry?.uhf1, uhfCh = comm?.uhfCh ?: presetOf(comm?.uhf ?: radioEntry?.uhf1), vhf = comm?.vhf ?: radioEntry?.vhf,
            notes = r.notes, live = liveContact,
            loc = liveContact?.let { c -> bull?.let { bra(it.first, it.second, c.x, c.y) } },
            yours = norm(r.callsign) == norm(live?.voice?.tanker) || norm(r.callsign) == norm(live?.voice?.awacs),
            radios = b?.comms.orEmpty().filter { norm(it.callsign) == norm(r.callsign) && (it.uhf != null || it.vhf != null) }
                .map { SupportRadio(it.agency.trimEnd(':'), it.uhf, it.uhfCh ?: presetOf(it.uhf), it.vhf) }
                .ifEmpty { listOfNotNull(radioEntry?.takeIf { it.uhf1 != null }?.let { SupportRadio("Radio plan", it.uhf1, presetOf(it.uhf1), it.vhf) }) },
        )
    }
}

/** Support assets for the current mission, refreshed with the live data. */
@Composable
fun rememberSupportAssets(env: MissionEnv): List<SupportAsset> {
    val mission by MissionLink.mission.collectAsState()
    val live by MissionLink.live.collectAsState()
    val contacts by MissionLink.contacts.collectAsState()
    val radio by produceState(emptyList<RadioEntry>(), env.theater?.radioSet) { value = env.theater?.radioSet?.takeIf { it.isNotBlank() }?.let { Repo.radio(it) }.orEmpty() }
    val ctcs = contacts?.contacts.orEmpty()
    return remember(mission, live?.voice, ctcs, radio) { supportAssets(mission?.briefing, live, ctcs, radio, bullseye(live, ctcs), mission?.dtc?.uhf.orEmpty()) }
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
                listOfNotNull(a.uhfCh?.let { "ch $it" }, a.vhf?.let { "V $it" }).joinToString(" · ").takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 10.sp, color = Hud.TextDim, maxLines = 1) }
            }
            Column(Modifier.width(70.dp)) {
                Text(a.loc ?: "—", style = LocalExtra.current.mono, color = if (a.loc != null) Hud.Cyan else Hud.TextFaint)
                a.live?.let { Text(flightLevel(it.altFt), fontSize = 10.sp, color = Hud.TextDim) }
            }
        }
        RadiosLine(a, Modifier.padding(start = 70.dp, top = 3.dp))
        a.notes?.let { Text(it, fontSize = 11.sp, color = Hud.TextDim, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 70.dp, top = 2.dp)) }
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
            Stat("LOC", a.loc ?: "—", a.live?.let { flightLevel(it.altFt) }, Hud.Cyan)
        }
        RadiosLine(a, Modifier.padding(top = 4.dp))
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
