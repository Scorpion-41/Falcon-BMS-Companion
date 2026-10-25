package com.bmscompanion.app.ui.screens.mission

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bmscompanion.app.data.AirportSet
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.mission.Briefing
import com.bmscompanion.app.data.mission.FuelPlan
import com.bmscompanion.app.data.mission.Live
import com.bmscompanion.app.data.mission.MergedMission
import com.bmscompanion.app.data.mission.MissionData
import com.bmscompanion.app.data.mission.MissionLink
import com.bmscompanion.app.data.mission.PlanMerge
import com.bmscompanion.app.data.mission.ownFlight
import com.bmscompanion.app.ui.components.SectionCard
import com.bmscompanion.app.ui.theme.Hud
import com.bmscompanion.app.ui.theme.LocalExtra
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt

/**
 * The fuel plan on screen (data/mission/FuelPlan.kt does the arithmetic): the **Fuel plan** card on the Briefing page
 * and the Dashboard's card of the same name ([DashCard.FUEL_PLAN]), in both modes and on every device. Bingo, Joker,
 * the playtime over the target area and the take-off fuel; in 3D the jet's fuel against the minimum where it is; the
 * steerpoint table (to go, minimum, expected, margin); the recovery minimum's parts; the settings folded, kept per
 * device ([FuelPrefs]).
 */
object FuelPrefs {
    private const val KEY = "fuel_plan"
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; encodeDefaults = true }

    // and another device's change of them is taken (MissionSync, PrefWatch)
    var settings by com.bmscompanion.app.data.mission.prefState(KEY) { load() }
        private set

    private fun load(): FuelPlan.Settings = runCatching {
        Repo.getString(KEY)?.let { json.decodeFromString(FuelPlan.Settings.serializer(), it) }
    }.getOrNull() ?: FuelPlan.Settings()

    fun set(s: FuelPlan.Settings) {
        settings = s
        runCatching { Repo.putString(KEY, json.encodeToString(FuelPlan.Settings.serializer(), s)) }
    }

    /** The jet's fuel last seen on the ground in 3D this session: the take-off fuel once it is flying. */
    var groundFuel: Double? = null
}

/**
 * A full jet by BMS's data: the own flight's aircraft (the briefing's Package Elements) and its internal fuel, and the
 * tanks the briefing's Ordnance lists for the flight's first jet, each at its BMS capacity (the store's strength, as
 * the Planner's Performance page counts it).
 */
data class FuelLoad(val internalLb: Double?, val tanksLb: Double, val tanks: Int, val aircraft: String?) {
    val totalLb: Double? get() = internalLb?.plus(tanksLb)
    val words: String get() = listOfNotNull(aircraft, if (tanks > 0) "$tanks tank" + (if (tanks == 1) "" else "s") else null).joinToString(" + ")
}

suspend fun fuelLoad(b: Briefing?, callsign: String?): FuelLoad {
    if (b == null) return FuelLoad(null, 0.0, 0, null)
    val own = b.ownFlight(callsign)
    val type = own?.aircraft?.trim()?.replace(Regex("^\\d+\\s+"), "")?.trim()
    val aircraft = Repo.aircraft()
    val ac = type?.norm()?.let { t -> aircraft.firstOrNull { it.name.norm() == t } ?: aircraft.firstOrNull { it.name.norm().startsWith(t) || t.startsWith(it.name.norm()) && it.name.length >= 5 } }
    val internal = ac?.variants?.firstNotNullOfOrNull { it.spec.internalFuelLbs }
    val cs = (own?.callsign ?: b.overview.flight)?.norm()
    val flight = b.ordnance.firstOrNull { it.flight.norm() == cs } ?: b.ordnance.firstOrNull()
    val weapons = Repo.weapons().filter { it.category == "FUEL_TANK" }.associateBy { it.name.norm() }
    var tanks = 0
    var fuel = 0.0
    flight?.aircraft?.firstOrNull()?.stores?.forEach { s ->
        val w = weapons[s.name.norm()] ?: return@forEach
        tanks += s.qty
        fuel += s.qty * (w.strength ?: 0.0)
    }
    return FuelLoad(internal, fuel, tanks, ac?.name ?: type)
}

/** The airport set of the theater the Mission section shows (as [rememberMissionEnv] finds it), for a page with no env. */
@Composable
fun rememberMissionAirports(): AirportSet? = rememberAirportsLoaded().first

/** The same, and whether the lookup is done (a page captured for print waits for it). */
@Composable
private fun rememberAirportsLoaded(): Pair<AirportSet?, Boolean> {
    val info by MissionLink.info.collectAsState()
    val mission by MissionLink.mission.collectAsState()
    val name = mission?.populated?.theater?.takeIf { info.wdpMode && it.isNotBlank() } ?: info?.bms?.theater
    val set by produceState<Pair<AirportSet?, Boolean>>(null to false, name) {
        val all = Repo.index().theaters
        val t = resolveTheater(all, name) ?: all.firstOrNull { it.id == Repo.selectedTheater.value }
        value = runCatching { t?.let { Repo.airportSet(it.airportSet) } }.getOrNull() to true
    }
    return set
}

/**
 * The plan for a page drawn from [mission] alone (the Noteboard's Fuel block: the print, the editor's preview, the VR
 * Notes board), with this device's settings; [Pair.second] false until the airports and the jet's load are in.
 */
@Composable
fun rememberFuelPlanFor(mission: MissionData?): Pair<FuelPlan.Result?, Boolean> {
    val merged = remember(mission) { PlanMerge.merge(mission) }
    val (set, setReady) = rememberAirportsLoaded()
    val src = rememberFuelLoad(merged, null)
    val settings = FuelPrefs.settings
    val plan = remember(merged, src.load, set, settings) { fuelPlanOf(merged, null, src.load, set, settings) }
    return plan to (setReady && src.loaded)
}

/** What the fuel plan needs that the merge does not carry: the jet's load, the bases' positions. */
data class FuelSources(val load: FuelLoad?, val loaded: Boolean)

@Composable
fun rememberFuelLoad(merged: MergedMission, callsign: String?): FuelSources {
    val b = merged.briefing
    val load by produceState<FuelLoad?>(null, b, callsign) { value = runCatching { fuelLoad(b, callsign) }.getOrNull() ?: FuelLoad(null, 0.0, 0, null) }
    return FuelSources(load, load != null)
}

/**
 * The plan for [merged] with this device's settings: the take-off fuel from the jet when it sits on the ground in 3D
 * (else the fuel last seen there, else a full jet by BMS's data), the alternate's and the recovery field's positions
 * from the theater's airports when the route does not place them.
 */
fun fuelPlanOf(merged: MergedMission, live: Live?, load: FuelLoad?, set: AirportSet?, settings: FuelPlan.Settings): FuelPlan.Result? {
    val onGround = live != null && !live.flying && live.fuelTotal > 0
    if (onGround) FuelPrefs.groundFuel = live!!.fuelTotal
    val ground = if (onGround) live!!.fuelTotal else FuelPrefs.groundFuel
    val full = load?.totalLb
    val (start, from) = when {
        ground != null -> ground to "jet"
        full != null -> full to (load?.words?.ifEmpty { null } ?: "aircraft")
        else -> null to null
    }
    val bases = airbases(live, merged)
    val alt = bases.alternateIn(set)?.takeUnless(::afloat)
    val arr = bases.arrivalIn(set)?.takeUnless(::afloat)
    val saveAlt = merged.plan?.flight?.route.orEmpty().associate { it.n to it.altFt }
    val input = FuelPlan.inputOf(
        merged, saveAlt = saveAlt, alternatePos = alt?.let { it.x to it.y }, alternateName = alt?.icao ?: alt?.name,
        takeoffLb = start, takeoffFrom = from, capacityLb = full ?: start, recoveryPos = arr?.let { it.x to it.y },
    )
    return FuelPlan.plan(input, settings)
}

private fun lb(v: Double) = FuelPlan.lb(v)
private fun lb(v: Int) = FuelPlan.lb(v.toDouble())

/** The Fuel plan card. [compact]: a Dashboard card of one column (the table keeps its columns, the notes fold away). */
@Composable
fun FuelPlanCard(merged: MergedMission, env: MissionEnv, compact: Boolean = false) {
    val live by MissionLink.live.collectAsState()
    val src = rememberFuelLoad(merged, live?.voice?.flight)
    val settings = FuelPrefs.settings
    val l = live
    // the plan follows the mission, the settings and the load; in 3D only the take-off fuel on the ground moves it
    val groundKey = l?.takeIf { !it.flying && it.fuelTotal > 0 }?.fuelTotal?.let { (it / 50).roundToInt() }
    val plan = remember(merged, src.load, env.set, settings, groundKey) { fuelPlanOf(merged, l, src.load, env.set, settings) }
    SectionCard("Fuel plan", accent = Hud.Amber, trailing = { if (!Hud.onPaper) ArrivalSwitch(settings) }) {
        if (plan == null) {
            Text(fillHint("No flight plan yet: press PRINT on the BMS briefing screen.", "The flight populated from the Planner has no steerpoints."), color = Hud.TextDim, fontSize = 13.sp)
            return@SectionCard
        }
        FuelPlanBody(plan, l, compact)
        if (!Hud.onPaper) FuelToDataCard(plan)
    }
}

/**
 * The Planner has the Mission section's flight open (WDP mode, the populated flight: the save and flight it planned,
 * or the printed briefing when the snapshot carries it) and its DataCard page made, so the card may take this plan's
 * Bingo and Joker.
 */
internal fun plannerHasFlightOf(m: MissionData?): Boolean {
    if (m == null || m.mode != com.bmscompanion.app.data.mission.MissionMode.WDP) return false
    if (!com.bmscompanion.app.ui.screens.wdp.WdpSession.dataCardMadeYet) return false
    val p = m.populated ?: return false
    val st = com.bmscompanion.app.ui.screens.wdp.PlannerMissionState
    val ref = st.ref
    return if (st.source == com.bmscompanion.app.ui.screens.wdp.PlannerMissionState.SAVE)
        ref != null && ref.file.trim().equals(p.save.trim(), ignoreCase = true) && ref.flight.trim() == p.flight.trim()
    else p.briefingFrom == PlanMerge.ORIGIN_PRINTED
}

/**
 * **Fill Bingo/Joker on the DataCard** (after 1.4.1): this plan's figures typed into the Planner's DataCard boxes, as
 * the pilot would type them (the bingo then counts on Save to DTC). Offered only while the Planner has this flight
 * open; a figure the pilot typed there is replaced only after asking.
 */
@Composable
private fun FuelToDataCard(plan: FuelPlan.Result) {
    val mission by MissionLink.mission.collectAsState()
    if (!plannerHasFlightOf(mission)) return
    val card = com.bmscompanion.app.ui.screens.wdp.WdpSession.dataCard
    var asking by remember { mutableStateOf<List<String>?>(null) }
    var said by remember(plan.bingoLb, plan.jokerLb) { mutableStateOf<String?>(null) }
    fun fill() {
        card.fillFuelStates(plan.bingoLb, plan.jokerLb)
        said = "On the DataCard: Bingo ${lb(plan.bingoLb)}, Joker ${lb(plan.jokerLb)} lb. Save to DTC writes the bingo to the cartridge."
    }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        SmallLink("Fill Bingo/Joker on the DataCard") {
            val over = card.fuelTypedOver(plan.bingoLb, plan.jokerLb)
            if (over.isEmpty()) fill() else asking = over
        }
        val (b, j) = card.fuelBoxes()
        Text(
            said ?: "The Planner's card holds Bingo ${b.ifEmpty { "—" }}, Joker ${j.ifEmpty { "—" }}.",
            Modifier.padding(start = 8.dp).weight(1f), fontSize = 11.5.sp, color = Hud.TextDim, lineHeight = 15.sp,
        )
    }
    asking?.let { over ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { asking = null },
            title = { Text("Replace your figures on the DataCard?") },
            text = { Text("You typed ${over.joinToString(" and ")} on the Planner's DataCard. The fuel plan says Bingo ${lb(plan.bingoLb)} and Joker ${lb(plan.jokerLb)} lb.") },
            confirmButton = { SmallLink("Replace them") { asking = null; fill() } },
            dismissButton = { SmallLink("Keep mine") { asking = null } },
        )
    }
}

@Composable
private fun ArrivalSwitch(s: FuelPlan.Settings) {
    Segmented(listOf("VMC", "IMC"), if (s.imc) 1 else 0) { FuelPrefs.set(s.copy(imc = it == 1)) }
}

@Composable
fun FuelPlanBody(plan: FuelPlan.Result, l: Live?, compact: Boolean) {
    val s = plan.settings
    val now = if (l != null && l.flying && l.fuelTotal > 0) FuelPlan.live(plan, l.x, l.y, l.fuelTotal, l.hdgTrue) else null
    if (now != null) LiveStrip(now)
    // the four figures: two by two on a narrow card
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val stats = listOf<@Composable (Modifier) -> Unit>(
            { m -> BigStat("BINGO lb", lb(plan.bingoLb), m, Hud.Amber, sub = "at STPT ${plan.bingoAt}") },
            { m -> BigStat("JOKER lb", lb(plan.jokerLb), m, sub = "Bingo + ${lb(s.jokerExtra)}") },
            { m ->
                val play = plan.playLb
                BigStat(
                    "PLAYTIME" + (plan.targetAt?.let { " · STPT $it" } ?: ""),
                    if (play == null) "—" else if (play <= 0) "none" else FuelPlan.oneDecimal(plan.playAbMin!!) + " min AB",
                    m, if (play != null && play <= 0) Hud.Red else Hud.Green,
                    sub = play?.takeIf { it > 0 }?.let { "${plan.playCruiseMin!!.roundToInt()} min at 350 kt · ${lb(it)} lb" }
                        ?: if (plan.targetAt == null) "no target area" else null,
                )
            },
            { m -> BigStat("T/O FUEL lb", plan.takeoffLb?.let { lb(it) } ?: "—", m, sub = takeoffWords(plan.takeoffFrom)) },
        )
        val perRow = if (maxWidth < 520.dp) 2 else 4
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            stats.chunked(perRow).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { row.forEach { it(Modifier.weight(1f)) } }
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    FuelTable(plan, compact, next = now?.nextN)
    Spacer(Modifier.height(8.dp))
    val parts = buildList {
        add("Secu Carb ${lb(s.secuCarb)}")
        add("divert " + (if (plan.divertNm > 0) "${plan.divertNm.roundToInt()} nm " else "") + lb(plan.divertBurn))
        s.recoveryParts().forEach { (k, v) -> if (v > 0) add("${k.replaceFirstChar { it.lowercase() }} ${lb(v)}") }
    }
    Text(
        "Minimum at the recovery (${if (s.imc) "IMC, at the FAF" else "VMC, at the Initial"}): ${lb(plan.recoveryLb)} lb = " + parts.joinToString(" + "),
        fontSize = 11.5.sp, color = Hud.TextDim, lineHeight = 16.sp,
    )
    if (s.missionAllowance > 0) Text("Bingo = the minimum at STPT ${plan.bingoAt} + ${lb(s.missionAllowance)} lb for the mission (${FuelPlan.oneDecimal(s.missionAllowance / maxOf(1, s.abPerMin).toDouble())} min AB).", fontSize = 11.5.sp, color = Hud.TextDim, lineHeight = 16.sp)
    if (plan.stationMin > 0) Text("The plan holds ${plan.stationMin.roundToInt()} min on station (${lb(plan.stationMin * s.cruisePerMin)} lb at ${lb(s.cruisePerMin)} lb/min), counted in the expected fuel.", fontSize = 11.5.sp, color = Hud.TextDim, lineHeight = 16.sp)
    plan.shortAt?.let { Text("The expected fuel falls below the minimum at STPT ${it.n}: the plan does not close on fuel.", fontSize = 12.sp, color = Hud.Red, fontWeight = FontWeight.SemiBold, lineHeight = 16.sp) }
    plan.notes.forEach { Text(it, fontSize = 11.5.sp, color = Hud.TextDim, lineHeight = 16.sp) }
    Text(FuelPlan.ESTIMATE_LINE, fontSize = 10.5.sp, color = Hud.TextFaint, lineHeight = 14.sp, modifier = Modifier.padding(top = 2.dp))
    if (!Hud.onPaper) FuelSettingsFold(s)
}

private fun takeoffWords(from: String?): String? = when (from) {
    null -> "set it in the settings"
    "jet" -> "the jet's, in 3D"
    "set" -> "as set"
    else -> "full: $from"
}

@Composable
private fun LiveStrip(v: FuelPlan.Live) {
    val (word, ink) = when (v.state) {
        FuelPlan.LiveState.BELOW_MIN -> "BELOW MINIMUM" to Hud.Red
        FuelPlan.LiveState.BINGO -> "AT BINGO" to Hud.Red
        FuelPlan.LiveState.JOKER -> "JOKER" to Hud.Amber
        FuelPlan.LiveState.OK -> "ON PLAN" to Hud.Green
    }
    Row(
        Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(10.dp)).background(ink.copy(alpha = 0.10f))
            .border(1.dp, ink.copy(alpha = 0.45f), RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(word, color = ink, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 0.6.sp)
        Spacer(Modifier.width(10.dp))
        Text(
            "${lb(v.fuelLb)} lb" + (v.minHereLb?.let { " · min here ${lb(it)}" + (v.nextN?.let { n -> " (to STPT $n)" } ?: "") } ?: ""),
            Modifier.weight(1f), style = LocalExtra.current.monoSmall.copy(fontSize = 12.sp), color = Hud.Text, maxLines = 2,
        )
        v.marginLb?.let { Text(FuelPlan.signedLb(it), style = LocalExtra.current.mono.copy(fontWeight = FontWeight.Bold), color = ink) }
    }
}

@Composable
private fun FuelTable(plan: FuelPlan.Result, compact: Boolean, next: Int? = null) {
    val mono = LocalExtra.current.monoSmall.copy(fontSize = if (compact) 11.sp else 12.sp)
    val head = TextStyle(fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold, color = Hud.TextFaint, letterSpacing = 0.4.sp)
    val cols = listOf(0.45f, 1.7f, 0.8f, 0.95f, 0.95f, 1.0f)
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
            listOf("#", "WHAT", "TO GO nm", "MIN lb", "EXP lb", "MARGIN").forEachIndexed { i, t ->
                Text(t, Modifier.weight(cols[i]).padding(horizontal = 2.dp), style = head, maxLines = 1, textAlign = if (i >= 2) TextAlign.End else TextAlign.Start)
            }
        }
        androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().height(1.dp).background(Hud.Outline.copy(alpha = 0.6f)))
        plan.rows.forEachIndexed { k, r ->
            val bingo = r.n == plan.bingoAt
            val margin = r.marginLb
            val mInk = when {
                margin == null -> Hud.TextFaint
                margin < 0 -> Hud.Red
                margin < plan.settings.missionAllowance -> Hud.Amber
                else -> Hud.Green
            }
            val tags = listOfNotNull(
                if (bingo) "BINGO" else null,
                if (r.n in plan.tankers) "TANKER" else null,
                if (r.legLow) "low" else null,
                if (r.holdMin >= 1) "${r.holdMin.roundToInt()} min" else null,
            )
            Row(
                Modifier.fillMaxWidth().background(if (bingo) Hud.Amber.copy(alpha = 0.10f) else if (k % 2 == 1) Hud.Text.copy(alpha = 0.035f) else Color.Transparent)
                    .padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // in 3D the steerpoint the jet is flying to is marked
                Text(
                    (if (r.n == next) "▸" else "") + r.n.toString(), Modifier.weight(cols[0]).padding(horizontal = 2.dp), style = mono,
                    color = if (r.n == next) Hud.Cyan else if (bingo) Hud.Amber else Hud.Text, maxLines = 1,
                )
                Text(
                    r.label + if (tags.isNotEmpty()) " · " + tags.joinToString(" · ") else "",
                    Modifier.weight(cols[1]).padding(horizontal = 2.dp), fontSize = if (compact) 11.sp else 12.sp,
                    color = if (bingo) Hud.Amber else Hud.Text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(if (r.legKnown || k == 0) r.toGoNm.roundToInt().toString() else "?", Modifier.weight(cols[2]).padding(horizontal = 2.dp), style = mono, color = Hud.TextDim, textAlign = TextAlign.End, maxLines = 1)
                Text(lb(r.minLb), Modifier.weight(cols[3]).padding(horizontal = 2.dp), style = mono, textAlign = TextAlign.End, maxLines = 1)
                Text(r.expectedLb?.let { lb(it) } ?: "—", Modifier.weight(cols[4]).padding(horizontal = 2.dp), style = mono, color = Hud.TextDim, textAlign = TextAlign.End, maxLines = 1)
                Text(margin?.let { FuelPlan.signedLb(it) } ?: "—", Modifier.weight(cols[5]).padding(horizontal = 2.dp), style = mono.copy(fontWeight = FontWeight.SemiBold), color = mInk, textAlign = TextAlign.End, maxLines = 1)
            }
        }
    }
}

/** The settings, folded until opened (remembered per device, `open_fuel_settings`). */
@Composable
private fun FuelSettingsFold(s: FuelPlan.Settings) {
    var open by com.bmscompanion.app.data.mission.rememberPrefState("open_fuel_settings") { Repo.getInt("open_fuel_settings", 0) == 1 }
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(8.dp))
            .clickable { open = !open; Repo.putInt("open_fuel_settings", if (open) 1 else 0) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text((if (open) "▾ " else "▸ ") + "Settings", color = Hud.Amber, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        if (open && s != FuelPlan.Settings(imc = s.imc)) SmallLink("Defaults") { FuelPrefs.set(FuelPlan.Settings(imc = s.imc)) }
    }
    if (!open) return
    fun set(f: FuelPlan.Settings.() -> FuelPlan.Settings) = FuelPrefs.set(s.f())
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val two = maxWidth >= 560.dp
        val fields = buildList<@Composable () -> Unit> {
            add { NumField("Secu Carb", s.secuCarb.toDouble(), "lb") { set { copy(secuCarb = it.toInt()) } } }
            add { NumField("Take-off fuel (0 = auto)", s.takeoffLb.toDouble(), "lb") { set { copy(takeoffLb = it.toInt()) } } }
            add { NumField("Taxi and take-off", s.taxiTakeoff.toDouble(), "lb") { set { copy(taxiTakeoff = it.toInt()) } } }
            add { NumField("Cruise burn", s.cruisePerNm, "lb/nm", decimals = true) { set { copy(cruisePerNm = it) } } }
            add { NumField("Low-level burn", s.lowPerNm, "lb/nm", decimals = true) { set { copy(lowPerNm = it) } } }
            add { NumField("Low leg at or below", s.lowAltFt.toDouble(), "ft") { set { copy(lowAltFt = it.toInt()) } } }
            add { NumField("Divert burn", s.divertPerNm, "lb/nm", decimals = true) { set { copy(divertPerNm = it) } } }
            add { NumField("Divert distance (0 = auto)", s.divertNm, "nm", decimals = true) { set { copy(divertNm = it) } } }
            if (s.imc) {
                add { NumField("Approach", s.imcApproach.toDouble(), "lb") { set { copy(imcApproach = it.toInt()) } } }
                add { NumField("Go-around", s.imcGoAround.toDouble(), "lb") { set { copy(imcGoAround = it.toInt()) } } }
                add { NumField("GCA racetrack", s.imcRacetrack.toDouble(), "lb") { set { copy(imcRacetrack = it.toInt()) } } }
                add { NumField("2nd approach", s.imcApproach2.toDouble(), "lb") { set { copy(imcApproach2 = it.toInt()) } } }
                add { NumField("2nd go-around", s.imcGoAround2.toDouble(), "lb") { set { copy(imcGoAround2 = it.toInt()) } } }
                add { NumField("Go-around (ejection)", s.imcEjectGoAround.toDouble(), "lb") { set { copy(imcEjectGoAround = it.toInt()) } } }
            } else {
                add { NumField("Go-around", s.vmcGoAround.toDouble(), "lb") { set { copy(vmcGoAround = it.toInt()) } } }
                add { NumField("10 min at 1,500 ft", s.vmcPattern.toDouble(), "lb") { set { copy(vmcPattern = it.toInt()) } } }
            }
            add { NumField("Mission allowance", s.missionAllowance.toDouble(), "lb") { set { copy(missionAllowance = it.toInt()) } } }
            add { NumField("Joker above Bingo", s.jokerExtra.toDouble(), "lb") { set { copy(jokerExtra = it.toInt()) } } }
            add { NumField("Afterburner", s.abPerMin.toDouble(), "lb/min") { set { copy(abPerMin = it.toInt()) } } }
            add { NumField("Cruise at 350 kt", s.cruisePerMin.toDouble(), "lb/min") { set { copy(cruisePerMin = it.toInt()) } } }
            add { NumField("Bingo at STPT (0 = auto)", s.bingoStpt.toDouble(), "") { set { copy(bingoStpt = it.toInt()) } } }
            add { NumField("Tanker at STPT (0 = route, -1 none)", s.tankerStpt.toDouble(), "") { set { copy(tankerStpt = it.toInt()) } } }
            add {
                Row(Modifier.fillMaxWidth().clickable { set { copy(holdsBurn = !holdsBurn) } }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    // a drawn box, not a glyph: the browser's fonts have no ballot boxes
                    androidx.compose.foundation.layout.Box(
                        Modifier.width(16.dp).height(16.dp).clip(RoundedCornerShape(3.dp))
                            .background(if (s.holdsBurn) Hud.Amber else Color.Transparent)
                            .border(1.5.dp, if (s.holdsBurn) Hud.Amber else Hud.TextDim, RoundedCornerShape(3.dp)),
                        contentAlignment = Alignment.Center,
                    ) { if (s.holdsBurn) androidx.compose.foundation.layout.Box(Modifier.width(6.dp).height(6.dp).clip(RoundedCornerShape(1.dp)).background(Hud.Bg)) }
                    Spacer(Modifier.width(8.dp))
                    Text("Planned holds burn the cruise rate", fontSize = 12.5.sp, color = Hud.TextDim)
                }
            }
        }
        Column {
            if (two) fields.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) { pair.forEach { f -> Column(Modifier.weight(1f)) { f() } }; if (pair.size == 1) Spacer(Modifier.weight(1f)) }
            } else fields.forEach { it() }
        }
    }
}

/** One setting: its name, the figure (typed, kept on every valid change) and its unit. */
@Composable
private fun NumField(label: String, value: Double, unit: String, decimals: Boolean = false, onChange: (Double) -> Unit) {
    fun shown(v: Double) = if (decimals && v != v.roundToInt().toDouble()) FuelPlan.oneDecimal(v) else v.roundToInt().toString()
    var text by remember(value) { mutableStateOf(shown(value)) }
    Row(Modifier.fillMaxWidth().height(34.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = 12.5.sp, color = Hud.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        BasicTextField(
            text,
            onValueChange = { t ->
                val clean = t.filter { it.isDigit() || it == '.' || it == '-' }.take(7)
                text = clean
                clean.toDoubleOrNull()?.let { v -> if (v != value) onChange(v) }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            textStyle = LocalExtra.current.monoSmall.copy(fontSize = 13.sp, color = Hud.Text, textAlign = TextAlign.End),
            cursorBrush = SolidColor(Hud.Amber),
            modifier = Modifier.width(72.dp).clip(RoundedCornerShape(6.dp)).background(Hud.Surface2)
                .border(1.dp, Hud.Outline.copy(alpha = 0.7f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 5.dp),
        )
        Text(unit, Modifier.width(44.dp).padding(start = 6.dp), fontSize = 11.sp, color = Hud.TextFaint, maxLines = 1)
    }
}
