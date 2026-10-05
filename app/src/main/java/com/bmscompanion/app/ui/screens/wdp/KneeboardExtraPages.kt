package com.bmscompanion.app.ui.screens.wdp

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bmscompanion.app.data.Airport
import com.bmscompanion.app.data.PptType
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.Theater
import com.bmscompanion.app.data.airfield.AfRoute
import com.bmscompanion.app.data.airfield.Airfield
import com.bmscompanion.app.data.airfield.taxiInRoute
import com.bmscompanion.app.data.mission.Dtc
import com.bmscompanion.app.data.mission.DtcPoint
import com.bmscompanion.app.data.mission.DtcPpt
import com.bmscompanion.app.data.mission.KbKind
import com.bmscompanion.app.data.mission.PlanMerge
import com.bmscompanion.app.data.pptTable
import com.bmscompanion.app.data.wdp.DataCardNet
import com.bmscompanion.app.data.wdp.DtcLoad
import com.bmscompanion.app.data.wdp.DtcModel
import com.bmscompanion.app.data.wdp.DtcPage
import com.bmscompanion.app.data.wdp.DtcStpt
import com.bmscompanion.app.data.wdp.PopupCoords
import com.bmscompanion.app.data.wdp.PopupPlan
import com.bmscompanion.app.data.wdp.WdpControl
import com.bmscompanion.app.data.wdp.WdpForm
import com.bmscompanion.app.data.wdp.WdpValues
import com.bmscompanion.app.ui.components.AirfieldChart
import com.bmscompanion.app.ui.components.ChartInks
import com.bmscompanion.app.ui.components.MapProjection
import com.bmscompanion.app.ui.components.mapIdOf
import com.bmscompanion.app.ui.components.tileSample
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * The Upd Kneeboard pages beyond the card ones [KneeboardPages] draws (R3-PLAN A15, K3): the Briefing page and its
 * weather list, the target list in two halves, a route map, the attack profile, and the departure, arrival and
 * alternate ground charts. Each is one knee's half page, laid out in [KneeboardPages]' 450 x 675 units.
 *
 * **What each page shows is what the Planner holds now**, as the card pages do: the Briefing page's own panels
 * (`pnlBrief`, `pnlWx`) drawn as paper; the cartridge **as the DTC page holds it**, unsaved edits and all ([DtcWiring.cartridgeText]), with each steerpoint placed by the Planner's per-slot rule
 * (A4: the cartridge's slot, else BMS's route, else the save); the attack the Planner would send (the current attack,
 * [AttackFocus]: the attack page last changed or applied — [WdpAttackOverlay.toSend]); and the
 * three airfields the DataCard shows, each drawn from BMS's own airfield data in the chart's day inks.
 *
 * **Everything a page loads is kept here** ([images], [fields], [forms], [ppt]) and fetched ahead: the first time the
 * Print window lists the kinds ([kinds]) the pages' data for the Planner's mission are loaded in the background
 * ([warm]), so a page drawn during a print — which waits only a few frames before it is captured — finds its map
 * tiles, its airfield and its form already there. [preload] does the same for one kind and waits for it.
 */
object KneeboardExtraPages {
    /** The kinds, in the order the Print window offers them after the card pages. Reading them warms the pages' data. */
    val kinds: List<KbPageKind>
        get() { warm(); return KINDS }

    private val KINDS: List<KbPageKind> = listOf(
        KbPageKind(KbKind.BRIEFING, "Briefing (the Briefing page)", "Briefing") { CardPanelPage("pnlBrief") },
        KbPageKind(KbKind.WEATHER, "Weather (the Briefing page's weather list)", "Weather") { CardPanelPage("pnlWx") },
        KbPageKind(KbKind.TARGETS_LEFT, "Target list, left: steerpoints and targets", "Targets L") { TargetListLeft(it) },
        KbPageKind(KbKind.TARGETS_RIGHT, "Target list, right: threats, lines, weapon targets", "Targets R") { TargetListRight(it) },
        KbPageKind(KbKind.ROUTE_MAP, "Route map", "Route map") { RouteMapPage(it) },
        KbPageKind(KbKind.ATTACK, "Attack profile (Pop-up, HADB or TOSS)", "Attack") { AttackPage(it) },
        KbPageKind(KbKind.DEPARTURE, "Departure ground chart", "Dep. chart") { GroundChartPage(it, 0) },
        KbPageKind(KbKind.ARRIVAL, "Arrival ground chart", "Arr. chart") { GroundChartPage(it, 1) },
        KbPageKind(KbKind.ALTERNATE, "Alternate ground chart", "Alt. chart") { GroundChartPage(it, 2) },
    )

    /** The ids of the kinds drawn here. */
    val ids: Set<String> get() = KINDS.map { it.id }.toSet()

    /**
     * What each page drew the last time it was drawn, one line per kind ("16 steerpoints, 8 of them targets"): for the
     * checks, which read it back (`--planneroutcome printpages`). Nothing in the app reads it.
     */
    val drawn = HashMap<String, String>()

    // ---------------------------------------------------------------- what the pages load

    /** Map tiles and pictures, by their path under the app's assets. */
    internal val images = mutableStateMapOf<String, ImageBitmap>()
    /** Airfields by "<airfield set>:<airport id>". */
    internal val fields = mutableStateMapOf<String, Airfield>()
    /** Airfields BMS has no chart for (or that could not be read), by the same key. */
    internal val noField = mutableStateMapOf<String, Boolean>()
    /** WDP's page layouts, by form name. */
    internal val forms = mutableStateMapOf<String, WdpForm>()
    /** A theater's PPT types (its `Ppt.ini`), by the theater's PPT set. */
    internal val ppt = mutableStateMapOf<String, List<PptType>>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var warmedFor: Any? = null

    /**
     * Loads, in the background, what every page needs for the Planner's mission as it is now: once per mission (and per
     * change of the card's airfields). Cheap to call again and again: the Print window reads [kinds] as it draws.
     */
    fun warm() {
        val key = try {
            listOf(WdpSession.appliedMission, WdpSession.dataCard.plan.tblApt.take(3).map { it.name })
        } catch (e: Exception) { return }
        if (key == warmedFor) return
        warmedFor = key
        scope.launch {
            for (k in KINDS) try { preload(k.id) } catch (e: CancellationException) { throw e } catch (e: Exception) { /* drawn with what there is */ }
        }
    }

    /** Loads what the page [kind] draws and waits for it; nothing for a kind that is not drawn here. */
    suspend fun preload(kind: String) {
        val m = mission()
        when (kind) {
            KbKind.BRIEFING -> { form(KneeboardPages.CARD_FORM); designerPicture("${KneeboardPages.CARD_FORM}.pnlBrief.BackgroundImage") }
            KbKind.WEATHER -> form(KneeboardPages.CARD_FORM)
            KbKind.TARGETS_LEFT, KbKind.TARGETS_RIGHT -> pptTypes(m.theater)
            KbKind.ROUTE_MAP -> {
                pptTypes(m.theater)
                routeFrame(planData(), ROUTE_W_PX, ROUTE_H_PX)?.let { loadTiles(it.keys) }
            }
            KbKind.ATTACK -> attackPage()?.let { p ->
                val f = form(p.form)
                val v = attackWiring(p)?.values(p.hiddenHere())
                v?.get("picProfile")?.takeIf { it.isNotBlank() && '/' !in it }?.let { Repo.bitmap("data/wdp/pictures/" + it.lowercase()) }
                if (f != null) for (c in f.all()) c.image?.let { if (it.startsWith(p.form + ".pnlDED")) designerPicture(it) }
            }
            KbKind.DEPARTURE -> field(0)
            KbKind.ARRIVAL -> field(1)
            KbKind.ALTERNATE -> field(2)
        }
    }

    internal suspend fun form(name: String): WdpForm? {
        forms[name]?.let { return it }
        val f = try { Repo.wdpForm(name) } catch (e: CancellationException) { throw e } catch (e: Exception) { null } ?: return null
        forms[name] = f
        return f
    }

    /** A designer picture as the renderer loads it (`data/wdp/img/<name>.<ext>`), into the app's picture cache. */
    private suspend fun designerPicture(name: String) {
        for (ext in listOf("jpg", "png", "bmp", "gif")) if (Repo.bitmap("data/wdp/img/$name.$ext") != null) return
    }

    internal suspend fun pptTypes(theater: Theater?): List<PptType> {
        val set = theater?.pptSet ?: return emptyList()
        ppt[set]?.let { return it }
        val rows = try { Repo.pptTable(set) } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }
        ppt[set] = rows
        return rows
    }

    /** Loads every map picture in [keys] that is not here yet, several at once, and lets go of tiles no page wants. */
    internal suspend fun loadTiles(keys: List<String>) {
        val want = keys.toSet()
        for (k in images.keys.toList()) if (k.startsWith("maps/") && k !in want) images.remove(k)
        val missing = keys.filter { it !in images }
        if (missing.isEmpty()) return
        coroutineScope {
            missing.map { k ->
                async {
                    val b = try { Repo.bitmap(k, sample = tileSample)?.asImageBitmap() } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
                    if (b != null) images[k] = b
                }
            }.awaitAll()
        }
    }

    /** The airfield the card shows in row [i] (0 departure, 1 arrival, 2 alternate), loaded. */
    internal suspend fun field(i: Int): Airfield? {
        val (key, set, apt) = fieldKey(i) ?: return null
        fields[key]?.let { return it }
        if (noField[key] == true) return null
        val f = try { Repo.airfield(set, apt.id) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
        if (f == null) noField[key] = true else fields[key] = f
        return f
    }

    internal fun fieldKey(i: Int): Triple<String, String, Airport>? {
        val apt = cardAirport(i) ?: return null
        val set = (WdpSession.dataCard.sources.theater ?: mission().theater)?.airfieldSet ?: return null
        return Triple("$set:${apt.id}", set, apt)
    }

    // ---------------------------------------------------------------- the Planner's state, as the pages read it

    internal fun mission(): WdpMission = WdpSession.appliedMission ?: WdpMission()

    /** The airport in row [i] of the card: the one its name box shows (the pilot may have picked another), else the briefing's. */
    internal fun cardAirport(i: Int): Airport? {
        val card = WdpSession.dataCard
        val name = card.plan.tblApt.getOrNull(i)?.name?.trim()?.takeIf { it.isNotEmpty() }
        val all = card.sources.airports?.airports.orEmpty()
        return name?.let { n -> all.firstOrNull { it.name == n } } ?: mission().let { m -> card.sources.bases(m.briefing, m.flight) }.getOrNull(i)
    }

    /**
     * The attack page this page prints: the current attack ([AttackFocus], the attack page last changed or applied),
     * as Populate from Planner sends it — and nothing else (1.3.8: no "page on show" or "card's choice" rule).
     */
    internal fun attackPage(): WdpPage? = AttackFocus.profile

    /**
     * The Upd Kneeboard window's line when it prints both the card and the attack page and they are not the same
     * attack ([AttackFocus.cardNotice]); null when they agree.
     */
    internal fun cardAttackNotice(): String? = runCatching { AttackFocus.cardNotice(WdpSession.dataCard.plan.attack.strProfile) }.getOrNull()

    internal fun attackWiring(p: WdpPage): WdpWiring? = when (p) {
        WdpPage.POPUP -> WdpSession.popup
        WdpPage.HADB -> WdpSession.hadb
        WdpPage.TOSS -> WdpSession.toss
        else -> null
    }

    internal fun hasAttack(p: WdpPage): Boolean = try { WdpAttackOverlay.current(p, attackWiring(p)) != null } catch (e: Exception) { false }

    /** The attack page's drawing of its plan over the map, as its own map and the DataCard's picMap draw it (the one attack drawing). */
    internal fun attackPicture(p: WdpPage): PopupPlan.MapPicture? = WdpAttackOverlay.picture(p, false)
}

// ==================================================================================================== the plan's points

/** A point on the lists and the map: theater feet, north and east. */
internal class KbPoint(
    val n: Int,
    val north: Double,
    val east: Double,
    val altFt: Double,
    val action: Int,
    val name: String?,
    val source: StptSource? = null,
    val line: Int = 0,
)

/** A pre-planned threat: [rangeFt] 0 for a marker (a point with no ring). */
internal class KbPpt(val n: Int, val north: Double, val east: Double, val rangeFt: Double, val code: String?, val label: String)

/**
 * The plan as the target list and the route map print it. [route] is the flight plan (STPT 1-24 that are not target
 * points, as the jet flies them), [stptTargets] the target points among STPT 1-24 (BMS's Recon, the pilot's), [open]
 * STPT 81-99, [weapons] the weapon target points, [ppts] the threats and [lines] the four lines.
 */
internal class KbPlanData(
    val mission: WdpMission,
    val coords: PopupCoords.CoordData?,
    val route: List<KbPoint>,
    val stptTargets: List<KbPoint>,
    val open: List<KbPoint>,
    val weapons: List<KbPoint>,
    val ppts: List<KbPpt>,
    val lines: List<Pair<Int, List<KbPoint>>>,
    /** STPT numbers the Planner counts as flight-plan targets (its target choices) */
    val targetStpts: Set<Int>,
    /** where the cartridge part came from, for the page's small print */
    val cartridgeFrom: String,
) {
    val empty: Boolean get() = route.isEmpty() && stptTargets.isEmpty() && open.isEmpty() && weapons.isEmpty() && ppts.isEmpty() && lines.isEmpty()
}

/** The plan now: the Planner's mission with the cartridge as the DTC page holds it. */
internal fun planData(): KbPlanData {
    val m0 = KneeboardExtraPages.mission()
    val coords = m0.coords
    val text = try { WdpSession.dtc.cartridgeText() } catch (e: Exception) { null }
    val types = m0.theater?.pptSet?.let { KneeboardExtraPages.ppt[it] }.orEmpty()
    val fromPage = text?.let { dtcOfText(it, coords, types) }
    val dtc = fromPage ?: m0.dtc
    val m = if (fromPage != null) m0.copy(dtc = fromPage) else m0
    val slots = m.slots.filter { it.point.placed() }
    fun kb(s: WdpStpt) = KbPoint(s.point.n, s.point.x, s.point.y, s.point.altFt, s.point.action, s.point.name, s.source)
    val route = slots.filter { it.point.n in 1..24 && it.point.action != -1 }.map(::kb)
    val stptTargets = slots.filter { it.point.n in 1..24 && it.point.action == -1 }.map(::kb)
    val open = slots.filter { it.point.n in 81..99 }.map(::kb)
    val weapons = dtc?.weaponTargets.orEmpty().filter { it.placed() }.map { KbPoint(it.n, it.x, it.y, it.altFt, it.action, it.name) }
    val ppts = dtc?.ppts.orEmpty().filter { it.x != 0.0 || it.y != 0.0 }.map { p ->
        KbPpt(p.n, p.x, p.y, PlanMerge.rangeFt(p), p.code, PlanMerge.pptLabel(p.name, p.code, p.n))
    }
    // each line in its own order, and each point by its own steerpoint number (lineSTPT_0 is STPT 31)
    val lines = dtc?.lines.orEmpty().filter { it.placed() }.groupBy { it.line ?: (it.n / 6 + 1) }.filterKeys { it in 1..4 }
        .entries.sortedBy { it.key }
        .map { (k, pts) -> k to pts.sortedBy { it.n }.map { KbPoint(31 + it.n, it.x, it.y, it.altFt, 0, null, line = k) } }
    val targets = try { m.choices.filter { it.key.startsWith("STPT ") }.map { it.waypoint }.toSet() } catch (e: Exception) { emptySet() }
    return KbPlanData(
        m, coords, route, stptTargets, open, weapons, ppts, lines, targets,
        when {
            fromPage != null -> "the cartridge as the DTC page holds it"
            m0.dtc != null -> "the cartridge as BMS has it"
            else -> "no cartridge"
        },
    )
}

/** The cartridge's points out of its text, read by the Planner's own reader, as the app's [Dtc] holds them. */
private fun dtcOfText(text: String, coords: PopupCoords.CoordData?, types: List<PptType>): Dtc? {
    val m = DtcModel()
    val c = coords ?: PopupCoords.CoordData()
    fun safe(f: () -> Unit): Boolean = try { f(); true } catch (e: Exception) { false }
    if (!safe { DtcLoad.stpt(text, m, c) }) return null
    safe { DtcLoad.ppt(text, m, types.filter { it.code.isNotBlank() }.map { it.code to it.name }, c) }
    safe { DtcLoad.lines(text, m, c) }
    safe { DtcLoad.wpnTarget(text, m, c) }
    safe { DtcLoad.open(text, m, c) }
    safe { DtcLoad.harpoon(text, m, c) }
    fun name(s: String?) = s?.trim()?.takeIf { it.isNotEmpty() && !it.equals("Not set", true) && it != "-1" }
    fun pt(s: DtcStpt, n: Int) = DtcPoint(
        n = n, x = s.falconY.toDouble(), y = s.falconX.toDouble(), altFt = abs(s.falconZ.toDouble()), action = s.action,
        isTarget = s.action == -1, name = name(s.target),
    )
    val steer = (0 until 24).map { pt(m.stpt[it], it + 1) } + (0 until 9).map { pt(m.open[it], 81 + it) } + (0 until 10).map { pt(m.hpn[it], 90 + it) }
    val weapons = (0 until 100).map { i ->
        val t = m.tgt[i]
        DtcPoint(n = i + 1, x = t.falconY.toDouble(), y = t.falconX.toDouble(), altFt = abs(t.falconZ.toDouble()), action = t.action, isTarget = true, name = name(t.target))
    }
    val ppts = (0 until 15).map { i ->
        val p = m.ppt[i]
        val r = p.falconRng.toDouble()
        DtcPpt(
            n = i + 56, x = p.falconY.toDouble(), y = p.falconX.toDouble(), altFt = abs(p.falconZ.toDouble()),
            rangeNm = if (r < 100.0) 0.0 else r / FT_PER_NM, name = p.name?.takeIf { it.isNotBlank() } ?: p.code?.takeIf { it.isNotBlank() },
            code = p.code?.takeIf { it.isNotBlank() }, rangeFt = r, marker = r < 100.0,
        )
    }
    val lines = (0 until 24).map { i ->
        val l = m.line[i]
        DtcPoint(n = i, x = l.falconY.toDouble(), y = l.falconX.toDouble(), altFt = abs(l.falconZ.toDouble()), line = i / 6 + 1)
    }
    return Dtc(steerpoints = steer.filter { it.placed() || it.name != null }, weaponTargets = weapons.filter { it.placed() }, ppts = ppts, lines = lines)
}

private const val FT_PER_NM = 6076.12

/** A position as the lists print it: latitude and longitude where the theater has a projection, else feet. */
internal fun latLon(c: PopupCoords.CoordData?, north: Double, east: Double): Pair<String, String> = try {
    if (c == null || !c.enableNewTerrain) "${north.roundToLong()}' N" to "${east.roundToLong()}' E"
    else PopupCoords.hemispheres(PopupCoords.feetToCoordsBoth(c, north, east))
} catch (e: Exception) { "?" to "?" }

/** "12.5" */
private fun one(v: Double): String {
    val t = (v * 10).roundToLong()
    return "${t / 10}.${abs(t % 10)}"
}

// ==================================================================================================== paper

private val INK = Color(0xFF111111)
private val FAINT = Color(0xFF666B70)
private val RULE = Color(0xFFBBBFC2)
private val BAND = Color(0xFFF0F1F2)
private val ROUTE_INK = Color(0xFF0B3D91)
private val RED = Color(0xFFC0271E)
/** a hostile SAM's ring and name on the route map: every map's SAM red for a light ground */
private val THREAT = com.bmscompanion.app.ui.screens.mission.SamInk.light
private val LINE_INK = Color(0xFF7B2FA8)
private val BASE_INK = Color(0xFF1B6E3C)

/**
 * A page of this app's own: a title, a line under it, the body, and the page's own words at its foot. The body is
 * given what is left of the page (so a table can count its rows, and a chart can know its box).
 */
@Composable
private fun Sheet(scope: KbPageScope, title: String, sub: String?, body: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 5.dp)) {
        Text(title, color = INK, fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (!sub.isNullOrBlank()) Text(sub, color = FAINT, fontSize = 8.sp, lineHeight = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Box(Modifier.padding(top = 3.dp, bottom = 4.dp).fillMaxWidth().height(1.2.dp).background(INK))
        Column(Modifier.fillMaxWidth().weight(1f)) { body() }
        Text(
            "Page ${scope.n} · ${KneeboardPages.knee(scope.side)} · BMS Companion",
            Modifier.fillMaxWidth().padding(top = 3.dp), color = FAINT, fontSize = 7.sp, textAlign = TextAlign.Center,
        )
    }
}

/** A sentence in place of what a page has nothing for. */
@Composable
private fun Note(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(vertical = 30.dp, horizontal = 16.dp), contentAlignment = Alignment.Center) {
        Text(text, color = FAINT, fontSize = 10.sp, lineHeight = 14.sp, textAlign = TextAlign.Center)
    }
}

// ==================================================================================================== Briefing and weather

/**
 * The Briefing page's own panels, drawn as paper: `pnlBrief` (the mission, its situation, intelligence, objectives and
 * notes on WDP's briefing sheet) or `pnlWx` (the weather list the card's Reload WX makes from the briefing). Both are
 * 450 x 675, exactly one page.
 */
@Composable
private fun CardPanelPage(panel: String) {
    LaunchedEffect(Unit) { KneeboardExtraPages.form(KneeboardPages.CARD_FORM) }
    val f = KneeboardExtraPages.forms[KneeboardPages.CARD_FORM] ?: return
    val card = WdpSession.dataCard
    // the weather list goes on paper only when it is weather: what the card says when there is none (the save's weather
    // file not found or another save's, PRINT for the rest) is for the Planner's screen, not the cockpit
    val values = card.values(WdpPage.BRIEFING.hiddenHere()).let { v -> if (panel == "pnlWx" && !card.weatherOnPaper) v.with("pnlWx.lines", "") else v }
    Box(Modifier.fillMaxSize()) {
        WdpPanelView(f, panel, values, Modifier.fillMaxSize(), content = card.controlContent())
        if (panel == "pnlWx") {
            val lines = values["pnlWx.lines"].orEmpty().lines().count { it.isNotBlank() }
            KneeboardExtraPages.drawn[KbKind.WEATHER] = "$lines weather lines"
            if (lines == 0) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Note("No weather")
            }
        } else {
            val filled = values.values.count { (k, v) -> (k.startsWith("txtBrf") || k.startsWith("lblBrf")) && v.isNotBlank() && '.' !in k }
            KneeboardExtraPages.drawn[KbKind.BRIEFING] = "$filled briefing lines filled"
        }
    }
}

// ==================================================================================================== the target list

private val ROW_H = 12.dp
private val HEAD_H = 20.dp
private val CELL = 7.6.sp

/** One column of a table: a fixed width, or null for the one that takes what is left. */
private class Col(val title: String, val width: Dp?, val mono: Boolean = false, val end: Boolean = false)

@Composable
private fun TableHead(title: String, cols: List<Col>, note: String? = null) {
    Row(Modifier.fillMaxWidth().height(HEAD_H).padding(top = 5.dp), verticalAlignment = Alignment.Bottom) {
        Text(title, color = INK, fontSize = 9.5.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        if (note != null) Text("  $note", color = FAINT, fontSize = 7.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    Cells(cols, cols.map { it.title }, head = true)
}

@Composable
private fun Cells(cols: List<Col>, cells: List<String>, head: Boolean = false, band: Boolean = false, color: Color = INK) {
    Row(
        Modifier.fillMaxWidth().height(ROW_H).background(if (band) BAND else Color.Transparent),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        cols.forEachIndexed { i, c ->
            val m = if (c.width != null) Modifier.width(c.width) else Modifier.weight(1f)
            Text(
                cells.getOrElse(i) { "" }, m.padding(horizontal = 2.dp),
                color = if (head) FAINT else color, fontSize = if (head) 7.sp else CELL,
                fontWeight = if (head) FontWeight.SemiBold else FontWeight.Normal,
                fontFamily = if (c.mono && !head) FontFamily.Monospace else FontFamily.Default,
                textAlign = if (c.end) TextAlign.End else TextAlign.Start,
                maxLines = 1, overflow = TextOverflow.Ellipsis, softWrap = false,
            )
        }
    }
    if (head) Box(Modifier.fillMaxWidth().height(0.8.dp).background(RULE))
}

/** How many rows fit in [height] beside [sections] section heads (each a title and a column row). */
private fun rowsFitting(height: Dp, sections: Int): Int =
    ((height - (HEAD_H + ROW_H + 1.dp) * sections - 14.dp) / ROW_H).toInt().coerceAtLeast(0)

/** The words for a steerpoint: the briefing's description, else the cartridge's name, else its action. */
private fun what(d: KbPlanData, p: KbPoint): String {
    val row = d.mission.briefing?.steerpoints?.firstOrNull { it.n == p.n }
    val desc = row?.desc?.trim()?.takeIf { it.isNotEmpty() && it != "--" }
    val name = p.name?.trim()?.takeIf { it.isNotEmpty() }
    return when {
        p.action == -1 -> name ?: "Target"
        desc != null -> desc + (row?.comments?.trim()?.takeIf { it.isNotEmpty() && it != "--" && !it.equals(desc, true) }?.let { " · $it" } ?: "")
        name != null -> name
        else -> PlanMerge.actionWord(p.action)
    }
}

private fun srcWord(s: StptSource?): String = when (s) { StptSource.DTC -> "DTC"; StptSource.ROUTE -> "BMS"; StptSource.SAVE -> "save"; null -> "" }

/**
 * The left half of the target list: the flight plan (STPT 1-24) with what each point is, its time on the briefing, its
 * planned altitude and its position, where each position came from (the cartridge, BMS's route or the save), then the
 * target points among them and STPT 81-99.
 */
@Composable
private fun TargetListLeft(scope: KbPageScope) {
    val d = remember { planData() }
    val flight = d.mission.briefing?.overview?.flight ?: d.mission.flight?.row?.callsign
    Sheet(scope, "STEERPOINTS", listOfNotNull(flight, d.mission.briefing?.overview?.mission, d.mission.sourceLine.ifEmpty { null }).joinToString(" · ")) {
        if (d.route.isEmpty() && d.stptTargets.isEmpty() && d.open.isEmpty()) {
            Note("No steerpoint has a position yet. The flight plan comes from BMS's route or the save you opened; press PRINT in BMS, or Open mission… in the Planner.")
            KneeboardExtraPages.drawn[KbKind.TARGETS_LEFT] = "0 steerpoints"
            return@Sheet
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val cols = listOf(
                Col("STPT", 25.dp, end = true), Col("WHAT", null), Col("TOS", 40.dp, mono = true), Col("ALT", 34.dp, end = true),
                Col("LATITUDE", 58.dp, mono = true), Col("LONGITUDE", 64.dp, mono = true), Col("SRC", 24.dp),
            )
            val tcols = listOf(
                Col("STPT", 25.dp, end = true), Col("TARGET", null), Col("ELEV", 36.dp, end = true),
                Col("LATITUDE", 58.dp, mono = true), Col("LONGITUDE", 64.dp, mono = true), Col("SRC", 24.dp),
            )
            val targets = d.stptTargets + d.open
            val sections = (if (d.route.isNotEmpty()) 1 else 0) + (if (targets.isNotEmpty()) 1 else 0)
            var budget = rowsFitting(maxHeight, sections)
            var shownRoute = 0
            var shownTargets = 0
            Column(Modifier.fillMaxSize()) {
                if (d.route.isNotEmpty()) {
                    TableHead("Flight plan", cols, "${d.route.size} steerpoints")
                    val take = min(d.route.size, if (targets.isEmpty()) budget else max(budget - min(targets.size, budget / 2), budget / 2))
                    for ((i, p) in d.route.take(take).withIndex()) {
                        val row = d.mission.briefing?.steerpoints?.firstOrNull { it.n == p.n }
                        val (lat, lon) = latLon(d.coords, p.north, p.east)
                        Cells(
                            cols,
                            listOf(
                                p.n.toString(), what(d, p), row?.time?.removeSuffix("z")?.takeIf { it != "--" } ?: "",
                                row?.alt?.takeIf { it != "--" } ?: "", lat, lon, srcWord(p.source),
                            ),
                            band = i % 2 == 1, color = if (p.n in d.targetStpts) RED else INK,
                        )
                    }
                    shownRoute = take
                    budget -= take
                    if (take < d.route.size) Text("… and ${d.route.size - take} more on the DTC page", color = FAINT, fontSize = 7.sp)
                }
                if (targets.isNotEmpty()) {
                    TableHead("Target points", tcols, "STPT ${DtcPage.ranges(targets.map { it.n })}")
                    val take = min(targets.size, budget)
                    for ((i, p) in targets.take(take).withIndex()) {
                        val (lat, lon) = latLon(d.coords, p.north, p.east)
                        Cells(
                            tcols,
                            listOf(p.n.toString(), p.name?.trim() ?: what(d, p), p.altFt.roundToInt().toString(), lat, lon, srcWord(p.source)),
                            band = i % 2 == 1,
                        )
                    }
                    shownTargets = take
                    if (take < targets.size) Text("… and ${targets.size - take} more on the DTC page", color = FAINT, fontSize = 7.sp)
                }
                Spacer(Modifier.weight(1f))
                Text(
                    "Red: a target steerpoint of the flight plan (a strike, or a point the briefing calls a target). SRC: where the position comes from — DTC your cartridge, BMS its route, save the save you opened.",
                    color = FAINT, fontSize = 6.5.sp, lineHeight = 8.5.sp,
                )
            }
            KneeboardExtraPages.drawn[KbKind.TARGETS_LEFT] =
                "$shownRoute of ${d.route.size} flight-plan steerpoints, $shownTargets of ${targets.size} target points (${d.cartridgeFrom})"
        }
    }
}

/**
 * The right half of the target list: the pre-planned threats with their rings, the four lines point by point, and the
 * weapon target points — what the DTC page holds, whether it was typed on the Planner or made with WDP or in BMS.
 */
@Composable
private fun TargetListRight(scope: KbPageScope) {
    val d = remember { planData() }
    Sheet(scope, "THREATS, LINES, WEAPON TARGETS", "From ${d.cartridgeFrom}") {
        if (d.ppts.isEmpty() && d.lines.isEmpty() && d.weapons.isEmpty()) {
            Note("The cartridge has no pre-planned threats, lines or weapon target points. Add them on the Planner's DTC page (or in BMS's DTC) and print again.")
            KneeboardExtraPages.drawn[KbKind.TARGETS_RIGHT] = "0 PPTs, 0 line points, 0 weapon targets"
            return@Sheet
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val pcols = listOf(
                Col("PPT", 24.dp, end = true), Col("TYPE", null), Col("RING", 40.dp, end = true), Col("LATITUDE", 64.dp, mono = true), Col("LONGITUDE", 70.dp, mono = true),
            )
            val lcols = listOf(Col("STPT", 24.dp, end = true), Col("LINE", null), Col("LATITUDE", 64.dp, mono = true), Col("LONGITUDE", 70.dp, mono = true))
            val wcols = listOf(
                Col("#", 24.dp, end = true), Col("WEAPON TARGET", null), Col("ELEV", 36.dp, end = true), Col("LATITUDE", 64.dp, mono = true), Col("LONGITUDE", 70.dp, mono = true),
            )
            val linePts = d.lines.flatMap { it.second }
            val sections = listOf(d.ppts.isNotEmpty(), linePts.isNotEmpty(), d.weapons.isNotEmpty()).count { it }
            var budget = rowsFitting(maxHeight, sections)
            val counts = IntArray(3)
            Column(Modifier.fillMaxSize()) {
                if (d.ppts.isNotEmpty()) {
                    TableHead("Pre-planned threats", pcols, "STPT ${DtcPage.ranges(d.ppts.map { it.n })}")
                    val take = min(d.ppts.size, budget)
                    for ((i, p) in d.ppts.take(take).withIndex()) {
                        val (lat, lon) = latLon(d.coords, p.north, p.east)
                        Cells(pcols, listOf(p.n.toString(), p.label, if (p.rangeFt > 0) one(p.rangeFt / FT_PER_NM) + " nm" else "point", lat, lon), band = i % 2 == 1, color = RED)
                    }
                    counts[0] = take
                    budget -= take
                }
                if (linePts.isNotEmpty()) {
                    TableHead("Lines", lcols, d.lines.joinToString(", ") { (k, pts) -> "line $k: ${pts.size} points" })
                    val take = min(linePts.size, budget)
                    for ((i, p) in linePts.take(take).withIndex()) {
                        val (lat, lon) = latLon(d.coords, p.north, p.east)
                        val first = d.lines.firstOrNull { it.first == p.line }?.second?.firstOrNull() === p
                        Cells(lcols, listOf(p.n.toString(), if (first) "Line ${p.line}" else "", lat, lon), band = p.line % 2 == 0)
                    }
                    counts[1] = take
                    budget -= take
                    if (take < linePts.size) Text("… and ${linePts.size - take} more line points on the DTC page", color = FAINT, fontSize = 7.sp)
                }
                if (d.weapons.isNotEmpty()) {
                    TableHead("Weapon target points", wcols, "for SPICE, not steerpoints")
                    val take = min(d.weapons.size, budget)
                    for ((i, p) in d.weapons.take(take).withIndex()) {
                        val (lat, lon) = latLon(d.coords, p.north, p.east)
                        Cells(wcols, listOf(p.n.toString(), p.name ?: "Target ${p.n}", p.altFt.roundToInt().toString(), lat, lon), band = i % 2 == 1)
                    }
                    counts[2] = take
                    if (take < d.weapons.size) Text("… and ${d.weapons.size - take} more on the DTC page", color = FAINT, fontSize = 7.sp)
                }
            }
            KneeboardExtraPages.drawn[KbKind.TARGETS_RIGHT] =
                "${counts[0]} of ${d.ppts.size} PPTs, ${counts[1]} of ${linePts.size} line points, ${counts[2]} of ${d.weapons.size} weapon targets (${d.cartridgeFrom})"
        }
    }
}

// ==================================================================================================== the route map

/** The route map's box as a page is drawn (its pixel size), which is what the tiles are chosen for ahead of time. */
private const val ROUTE_W_PX = 969
private const val ROUTE_H_PX = 1290

/**
 * The stretch of the theater a route map shows: [upperN] and [leftE] its top-left corner in theater feet, [k] pixels
 * per foot, [w] x [h] its pixels; [keys] the map pictures it needs (the chart style's overview and the tiles of the
 * level that suits the scale), [off] the target points left off it.
 */
internal class RouteFrame(
    val theater: Theater,
    val mapId: String?,
    val upperN: Double,
    val leftE: Double,
    val k: Double,
    val w: Int,
    val h: Int,
    val keys: List<String>,
    val z: Int,
    val off: List<KbPoint>,
) {
    val projection: MapProjection
        get() {
            val side = (theater.sizeFt * k).toFloat()
            return MapProjection((-leftE * k).toFloat(), (-(theater.sizeFt - upperN) * k).toFloat(), side, theater.sizeFt, 1f)
        }
    fun at(north: Double, east: Double) = Offset(((east - leftE) * k).toFloat(), ((upperN - north) * k).toFloat())
}

private const val MAP_STYLE = "chart"

/** Frames the route (and the targets near it) in a [w] x [h] pixel box; null with no theater or nothing placed. */
internal fun routeFrame(d: KbPlanData, w: Int, h: Int): RouteFrame? {
    val theater = d.mission.theater ?: return null
    if (w <= 0 || h <= 0) return null
    val core = d.route.ifEmpty { d.stptTargets + d.open }.ifEmpty { d.weapons }
    if (core.isEmpty()) return null
    // the targets, lines and weapon points near the route join the frame; the far ones are named under the map
    val cn = core.map { it.north }.average()
    val ce = core.map { it.east }.average()
    val reach = max(60 * FT_PER_NM, core.maxOf { hypot(it.north - cn, it.east - ce) } + 40 * FT_PER_NM)
    val extra = (d.stptTargets + d.open + d.weapons + d.lines.flatMap { it.second }).filter { it !in core }
    val near = extra.filter { hypot(it.north - cn, it.east - ce) <= reach }
    val off = extra.filter { it !in near && it.line == 0 }
    val pts = core + near
    var minN = pts.minOf { it.north }; var maxN = pts.maxOf { it.north }
    var minE = pts.minOf { it.east }; var maxE = pts.maxOf { it.east }
    // at least 30 nm across, and a margin round it
    val minSpan = 30 * FT_PER_NM
    if (maxN - minN < minSpan) { val c = (maxN + minN) / 2; minN = c - minSpan / 2; maxN = c + minSpan / 2 }
    if (maxE - minE < minSpan) { val c = (maxE + minE) / 2; minE = c - minSpan / 2; maxE = c + minSpan / 2 }
    val padN = (maxN - minN) * 0.10; val padE = (maxE - minE) * 0.10
    minN -= padN; maxN += padN; minE -= padE; maxE += padE
    // the box's shape: widen whichever side is short
    val aspect = w.toDouble() / h
    var spanE = maxE - minE
    var spanN = maxN - minN
    if (spanE / spanN < aspect) { val grow = spanN * aspect - spanE; minE -= grow / 2; spanE += grow } else { val grow = spanE / aspect - spanN; maxN += grow / 2; spanN += grow }
    val k = w / spanE
    val top = maxN
    val id = mapIdOf(theater.map)
    val keys = ArrayList<String>()
    var z = 0
    if (id != null) {
        keys += "maps/$id/$MAP_STYLE.webp"
        val side = theater.sizeFt * k
        if (side >= 1300.0) {
            z = ceil(ln(side / 512.0) / ln(2.0)).toInt().coerceIn(2, 4)
            val count = 1 shl z
            val ts = side / count
            val left = -minE * k
            val topPx = -(theater.sizeFt - top) * k
            val c0 = floor(-left / ts).toInt().coerceIn(0, count - 1)
            val c1 = floor((w - left) / ts).toInt().coerceIn(0, count - 1)
            val r0 = floor(-topPx / ts).toInt().coerceIn(0, count - 1)
            val r1 = floor((h - topPx) / ts).toInt().coerceIn(0, count - 1)
            for (r in r0..r1) for (c in c0..c1) keys += "maps/$id/$MAP_STYLE/$z/${r}_$c.webp"
        }
    }
    return RouteFrame(theater, id, top, minE, k, w, h, keys, z, off)
}

/**
 * The route map: the flight's stretch of the theater on the chart-style map, with the flight plan, the target points,
 * the pre-planned threats with their rings, the lines, the weapon target points and the card's three airfields — what
 * the DataCard's small map shows, at the size of a page and in paper inks.
 */
@Composable
private fun RouteMapPage(scope: KbPageScope) {
    val d = remember { planData() }
    val flight = d.mission.briefing?.overview?.flight ?: d.mission.flight?.row?.callsign
    Sheet(scope, "ROUTE MAP", listOfNotNull(flight, d.mission.briefing?.overview?.mission, d.mission.theater?.name).joinToString(" · ")) {
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).border(1.dp, INK)) {
            val frame = remember(d, constraints.maxWidth, constraints.maxHeight) { routeFrame(d, constraints.maxWidth, constraints.maxHeight) }
            if (frame == null) {
                Note(
                    if (d.mission.theater == null) "Falcon BMS's theater is not known to this copy of the app, so there is no map to draw."
                    else "No steerpoint has a position yet: press PRINT in BMS, or Open mission… in the Planner.",
                    Modifier.align(Alignment.Center),
                )
                KneeboardExtraPages.drawn[KbKind.ROUTE_MAP] = "no map"
                return@BoxWithConstraints
            }
            LaunchedEffect(frame.keys) { KneeboardExtraPages.loadTiles(frame.keys) }
            val tm = rememberTextMeasurer(cacheSize = 64)
            val have = frame.keys.count { it in KneeboardExtraPages.images }
            val airports = (0..2).map { KneeboardExtraPages.cardAirport(it) }
            Canvas(Modifier.fillMaxSize().clipToBounds()) {
                drawRouteMap(frame, d, airports, tm)
            }
            KneeboardExtraPages.drawn[KbKind.ROUTE_MAP] =
                "${d.route.size} flight-plan steerpoints, ${d.ppts.size} PPTs, ${d.lines.size} lines; map ${frame.mapId}/$MAP_STYLE z${frame.z}: $have of ${frame.keys.size} pictures"
        }
        Legend(d, frameOffNote(d))
    }
}

/** The target points the map leaves off, as a sentence under it. */
private fun frameOffNote(d: KbPlanData): String? {
    val f = routeFrame(d, ROUTE_W_PX, ROUTE_H_PX) ?: return null
    if (f.off.isEmpty()) return null
    val cn = (d.route.ifEmpty { f.off }).map { it.north }.average()
    val ce = (d.route.ifEmpty { f.off }).map { it.east }.average()
    val on = f.off.map { it.north }.average()
    val oe = f.off.map { it.east }.average()
    val nm = hypot(on - cn, oe - ce) / FT_PER_NM
    val brg = ((atan2(oe - ce, on - cn) * 180 / PI) + 360) % 360
    val stpts = f.off.filter { it.source != null }.map { it.n }
    val what = listOfNotNull(
        stpts.takeIf { it.isNotEmpty() }?.let { "STPT ${DtcPage.ranges(it)}" },
        (f.off.size - stpts.size).takeIf { it > 0 }?.let { "$it weapon target points" },
    ).joinToString(" and ")
    return "Off the map: $what, about ${nm.roundToInt()} nm ${compass(brg)}."
}

private fun compass(brg: Double): String = listOf("north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west")[((brg + 22.5) / 45).toInt() % 8]

@Composable
private fun Legend(d: KbPlanData, off: String?) {
    Column(Modifier.fillMaxWidth().padding(top = 3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LegendMark { drawLine(ROUTE_INK, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 2.dp.toPx()) }
            LegendWord("Flight plan")
            LegendMark { drawCircle(Color.White, 3.5.dp.toPx()); drawCircle(ROUTE_INK, 3.5.dp.toPx(), style = Stroke(1.4.dp.toPx())) }
            LegendWord("Steerpoint")
            LegendMark { drawPath(triangle(Offset(size.width / 2, size.height / 2), 4.5.dp.toPx()), RED) }
            LegendWord("Target")
            LegendMark { drawCircle(THREAT, size.height / 2 - 1f, style = Stroke(1.6.dp.toPx())) }
            LegendWord("Threat ring")
            LegendMark { drawLine(LINE_INK, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1.8.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f))) }
            LegendWord("Line")
            LegendMark { drawRect(BASE_INK, Offset(size.width / 2 - 3.dp.toPx(), size.height / 2 - 3.dp.toPx()), Size(6.dp.toPx(), 6.dp.toPx())) }
            LegendWord("Airfield")
        }
        if (off != null) Text(off, color = FAINT, fontSize = 7.sp, lineHeight = 9.sp, maxLines = 2)
    }
}

@Composable
private fun LegendMark(draw: DrawScope.() -> Unit) {
    Canvas(Modifier.size(width = 16.dp, height = 10.dp)) { draw() }
    Spacer(Modifier.width(3.dp))
}

@Composable
private fun LegendWord(text: String) {
    Text(text, color = INK, fontSize = 7.sp, maxLines = 1)
    Spacer(Modifier.width(8.dp))
}

private fun triangle(c: Offset, r: Float): Path = Path().apply {
    moveTo(c.x, c.y - r)
    lineTo(c.x + r * 0.87f, c.y + r * 0.5f)
    lineTo(c.x - r * 0.87f, c.y + r * 0.5f)
    close()
}

/** Labels that must not sit on each other: each one tried at a few places beside its point, and left off if all are taken. */
private class Labels(private val tm: TextMeasurer) {
    private val taken = ArrayList<Rect>()

    fun block(r: Rect) { taken += r }

    fun DrawScope.put(text: String, at: Offset, color: Color, textSize: TextUnit, bold: Boolean = false, gap: Float = 6f): Boolean {
        val layout = tm.measure(text, TextStyle(color = color, fontSize = textSize, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal))
        val w = layout.size.width.toFloat()
        val h = layout.size.height.toFloat()
        val g = gap * density
        val tries = listOf(
            Offset(at.x + g, at.y - h / 2), Offset(at.x - g - w, at.y - h / 2), Offset(at.x - w / 2, at.y - g - h), Offset(at.x - w / 2, at.y + g),
            Offset(at.x + g, at.y - h), Offset(at.x + g, at.y), Offset(at.x - g - w, at.y - h), Offset(at.x - g - w, at.y),
        )
        for (p in tries) {
            val r = Rect(p.x - 1f, p.y, p.x + w + 1f, p.y + h)
            if (r.left < 0 || r.top < 0 || r.right > size.width || r.bottom > size.height) continue
            if (taken.any { it.overlaps(r) }) continue
            taken += r
            drawText(layout, color = Color.White, topLeft = p, drawStyle = Stroke(2f * density))
            drawText(layout, topLeft = p)
            return true
        }
        return false
    }
}

private fun DrawScope.drawRouteMap(f: RouteFrame, d: KbPlanData, airports: List<Airport?>, tm: TextMeasurer) {
    drawRect(Color(0xFFF4F3EE))
    // the map: the chart style's overview, and the sharper tiles over it
    val pr = f.projection
    val imgs = KneeboardExtraPages.images
    f.mapId?.let { id ->
        imgs["maps/$id/$MAP_STYLE.webp"]?.let {
            drawImage(it, dstOffset = IntOffset(pr.left.roundToInt(), pr.top.roundToInt()), dstSize = IntSize(pr.side.roundToInt(), pr.side.roundToInt()))
        }
        if (f.z > 0) {
            val count = 1 shl f.z
            val ts = pr.side / count
            for (key in f.keys) {
                if (!key.contains("/$MAP_STYLE/${f.z}/")) continue
                val img = imgs[key] ?: continue
                val rc = key.substringAfterLast('/').removeSuffix(".webp").split('_')
                val r = rc.getOrNull(0)?.toIntOrNull() ?: continue
                val c = rc.getOrNull(1)?.toIntOrNull() ?: continue
                val x0 = (pr.left + c * ts).roundToInt(); val x1 = (pr.left + (c + 1) * ts).roundToInt()
                val y0 = (pr.top + r * ts).roundToInt(); val y1 = (pr.top + (r + 1) * ts).roundToInt()
                drawImage(img, dstOffset = IntOffset(x0, y0), dstSize = IntSize(x1 - x0, y1 - y0))
            }
        }
    }
    // a wash, so the inks stand out from the map
    drawRect(Color.White.copy(alpha = 0.30f))
    // one of the page's units, in pixels
    val px = density
    val labels = Labels(tm)

    // threats first, under everything
    for (p in d.ppts) {
        val c = f.at(p.north, p.east)
        if (p.rangeFt > 0) {
            val r = (p.rangeFt * f.k).toFloat()
            drawCircle(THREAT.copy(alpha = 0.10f), r, c)
            drawCircle(Color.White.copy(alpha = 0.7f), r, c, style = Stroke(3.6f * px))
            drawCircle(THREAT, r, c, style = Stroke(2f * px))
        }
        drawLine(THREAT, Offset(c.x - 4 * px, c.y - 4 * px), Offset(c.x + 4 * px, c.y + 4 * px), 1.8f * px)
        drawLine(THREAT, Offset(c.x - 4 * px, c.y + 4 * px), Offset(c.x + 4 * px, c.y - 4 * px), 1.8f * px)
    }
    // the lines, each joined in its own order
    for ((k, pts) in d.lines) {
        val path = Path()
        pts.forEachIndexed { i, p -> val o = f.at(p.north, p.east); if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y) }
        drawPath(path, LINE_INK, style = Stroke(1.8f * px, pathEffect = PathEffect.dashPathEffect(floatArrayOf(7f * px, 4f * px))))
        pts.forEach { p -> drawCircle(LINE_INK, 2f * px, f.at(p.north, p.east)) }
    }
    // the flight plan: a leg joins steerpoints next to each other in number only
    val route = d.route.sortedBy { it.n }
    for (i in 1 until route.size) if (route[i].n == route[i - 1].n + 1) {
        val a = f.at(route[i - 1].north, route[i - 1].east)
        val b = f.at(route[i].north, route[i].east)
        drawLine(Color.White, a, b, 5f * px)
        drawLine(ROUTE_INK, a, b, 2.2f * px)
    }
    // the airfields of the card
    val baseWords = listOf("DEP", "ARR", "ALT")
    val bases = airports.withIndex().filter { it.value != null }.groupBy { it.value!!.id }
    for ((_, rows) in bases) {
        val a = rows.first().value!!
        val c = f.at(a.x, a.y)
        drawRect(Color.White, Offset(c.x - 4.5f * px, c.y - 4.5f * px), Size(9f * px, 9f * px))
        drawRect(BASE_INK, Offset(c.x - 3.5f * px, c.y - 3.5f * px), Size(7f * px, 7f * px))
        labels.block(Rect(c.x - 5 * px, c.y - 5 * px, c.x + 5 * px, c.y + 5 * px))
    }
    // the steerpoints over the line, and the targets
    for (p in route) {
        val c = f.at(p.north, p.east)
        val tgt = p.n in d.targetStpts
        if (tgt) drawPath(triangle(c, 6f * px), RED) else {
            drawCircle(Color.White, 4.2f * px, c)
            drawCircle(ROUTE_INK, 4.2f * px, c, style = Stroke(1.5f * px))
        }
        labels.block(Rect(c.x - 5 * px, c.y - 5 * px, c.x + 5 * px, c.y + 5 * px))
    }
    val targets = (d.stptTargets + d.open).filter { t -> f.off.none { it === t } }
    for (p in targets) {
        val c = f.at(p.north, p.east)
        drawPath(triangle(c, 5f * px), RED)
        labels.block(Rect(c.x - 4 * px, c.y - 4 * px, c.x + 4 * px, c.y + 4 * px))
    }
    for (p in d.weapons.filter { t -> f.off.none { it === t } }) {
        val c = f.at(p.north, p.east)
        val r = 3.5f * px
        val path = Path().apply { moveTo(c.x, c.y - r); lineTo(c.x + r, c.y); lineTo(c.x, c.y + r); lineTo(c.x - r, c.y); close() }
        drawPath(path, RED, style = Stroke(1.3f * px))
    }
    // labels, most important first
    with(labels) {
        for (p in route) put(p.n.toString(), f.at(p.north, p.east), if (p.n in d.targetStpts) RED else ROUTE_INK, 9.sp, bold = true)
        // target points close together are named once, by their range ("15-22")
        val groups = ArrayList<MutableList<KbPoint>>()
        for (t in targets) {
            val g = groups.firstOrNull { gr -> hypot(gr[0].north - t.north, gr[0].east - t.east) < 2 * FT_PER_NM }
            if (g != null) g += t else groups += mutableListOf(t)
        }
        for (g in groups) put(DtcPage.ranges(g.map { it.n }), f.at(g.map { it.north }.average(), g.map { it.east }.average()), RED, 8.sp, bold = true, gap = 9f)
        for ((_, rows) in bases) {
            val a = rows.first().value!!
            put(rows.joinToString("/") { baseWords[it.index] } + " " + a.name, f.at(a.x, a.y), BASE_INK, 7.5.sp, bold = true, gap = 8f)
        }
        // a threat is named at the top of its ring, clear of the route that usually runs through the middle of it
        for (p in d.ppts) {
            val c = f.at(p.north, p.east)
            val r = (p.rangeFt * f.k).toFloat()
            val at = if (r > 12 * px) Offset(c.x, c.y - r) else c
            put("${p.n} ${p.label}" + if (p.rangeFt > 0) " ${one(p.rangeFt / FT_PER_NM)} nm" else "", at, THREAT, 7.sp, bold = true, gap = 4f)
        }
        for ((k, pts) in d.lines) pts.firstOrNull()?.let { put("L$k", f.at(it.north, it.east), LINE_INK, 7.sp, bold = true) }
    }
    // north, and a scale
    val nx = size.width - 16f * px
    val ny = 14f * px
    drawPath(Path().apply { moveTo(nx, ny - 8 * px); lineTo(nx + 4.5f * px, ny + 5 * px); lineTo(nx, ny + 2 * px); lineTo(nx - 4.5f * px, ny + 5 * px); close() }, INK)
    drawText(tm.measure("N", TextStyle(color = INK, fontSize = 8.sp, fontWeight = FontWeight.Bold)), topLeft = Offset(nx - 3f * px, ny + 6f * px))
    val pxPerNm = (FT_PER_NM * f.k).toFloat()
    val nice = listOf(5, 10, 20, 25, 50, 100).firstOrNull { it * pxPerNm >= 60 * px } ?: 100
    val len = nice * pxPerNm
    val sy = size.height - 10f * px
    val sx = 10f * px
    drawRect(Color.White.copy(alpha = 0.8f), Offset(sx - 3 * px, sy - 12 * px), Size(len + 6 * px, 16 * px))
    drawLine(INK, Offset(sx, sy), Offset(sx + len, sy), 1.5f * px)
    drawLine(INK, Offset(sx, sy - 3 * px), Offset(sx, sy + 1 * px), 1.5f * px)
    drawLine(INK, Offset(sx + len, sy - 3 * px), Offset(sx + len, sy + 1 * px), 1.5f * px)
    drawText(tm.measure("$nice nm", TextStyle(color = INK, fontSize = 7.sp)), topLeft = Offset(sx + 2 * px, sy - 12 * px))
}

// ==================================================================================================== the attack profile

/**
 * The attack page's plan on one page: its profile drawing (picProfile), its DED data (what the pilot types into the
 * jet: the VIP or VRP, the pull-up point, the offset aimpoints), its map of the run-in, and its figures (ingress,
 * release, headings, the numbers under them), each read from the page as the pilot left it.
 */
@Composable
private fun AttackPage(scope: KbPageScope) {
    val page = KneeboardExtraPages.attackPage()
    if (page != null) LaunchedEffect(page) { KneeboardExtraPages.form(page.form) }
    val wiring = page?.let { KneeboardExtraPages.attackWiring(it) }
    val form = page?.let { KneeboardExtraPages.forms[it.form] }
    val values = try { page?.let { p -> wiring?.values(p.hiddenHere()) } } catch (e: Exception) { null }
    val has = page != null && KneeboardExtraPages.hasAttack(page)
    val tgt = values?.get("numWaypoint")?.takeIf { it.isNotBlank() && it != "hidden" }
    // VIP mode names the IP the lines hang on (D87: the one before the target, or the IP STPT picked); VRP needs none
    val attack = page?.let { runCatching { WdpAttackOverlay.of(it, wiring) }.getOrNull() }
    val ref = when {
        attack?.mode == "VIP" -> "VIP STPT ${attack.refStpt}"
        attack != null -> "VRP"
        else -> null
    }
    val sub = if (page == null) null else listOfNotNull(page.label, tgt?.let { "TGT STPT $it" }, ref, targetLine(values)).joinToString(" · ")
    Sheet(scope, "ATTACK PROFILE", sub) {
        if (page == null || !has || form == null || values == null) {
            Note(
                if (page == null) "No attack planned: set up the attack on the Planner's Pop-up, HADB or TOSS page (and press its Save to DTC), then print again."
                else if (!has) "The ${page.label} page has no target yet: choose its TGT STPT, then print again."
                else "Loading the ${page.label} page…",
            )
            KneeboardExtraPages.drawn[KbKind.ATTACK] = if (page == null) "no attack page" else if (!has) "${page.label}: no target" else "${page.label}: loading"
            return@Sheet
        }
        // the profile drawing, across the page
        Box(Modifier.fillMaxWidth().height(150.dp).background(Color.White), contentAlignment = Alignment.Center) {
            Box(Modifier.fillMaxHeight().aspectRatio(324f / 150f)) {
                WdpPanelView(form, "picProfile", values, Modifier.fillMaxSize())
            }
        }
        Spacer(Modifier.height(5.dp))
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            // the DED data: the four DED pages and their distances, as the page shows them
            WdpPanelView(
                form, "pnlDEDData", values.with("pnlDEDData", "shown"), Modifier.width(232.dp).fillMaxHeight(),
                crop = intArrayOf(88, 36, 312, 530),
            )
            Column(Modifier.weight(1f).fillMaxHeight()) {
                val pic = KneeboardExtraPages.attackPicture(page)
                Box(Modifier.fillMaxWidth().aspectRatio(1f).border(0.8.dp, INK)) {
                    WdpMapPicture(KneeboardExtraPages.mission().theater, pic)
                }
                Spacer(Modifier.height(4.dp))
                val rows = figureRows(form, values)
                Column(Modifier.fillMaxWidth().weight(1f).clipToBounds()) {
                    for ((heading, text) in rows) {
                        if (heading) Text(text, Modifier.padding(top = 3.dp), color = INK, fontSize = 7.5.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        else Text(text, color = INK, fontSize = 7.sp, lineHeight = 9.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                KneeboardExtraPages.drawn[KbKind.ATTACK] = "${page.label}, TGT STPT ${tgt ?: "?"}: ${rows.count { !it.first }} figures, map ${if (pic != null) "drawn" else "none"}"
            }
        }
    }
}

/** "TGT N37,05.123 E127,01.234 1234 ft" from the page's coordinates box, or null while it shows none. */
private fun targetLine(v: WdpValues?): String? {
    v ?: return null
    val n = v["lblTGT_N"]?.takeIf { it.isNotBlank() && !it.startsWith("00,00") } ?: return null
    val e = v["lblTGT_E"] ?: ""
    val ns = v["lblTGT_NS"] ?: "N"
    val ew = v["lblTGT_EW"] ?: "E"
    val elv = v["lblTGT_elv"]?.takeIf { it.isNotBlank() }
    return "TGT $ns$n $ew$e" + (elv?.let { " · $it ft" } ?: "")
}

/** One label of an attack page's panel, where it sits on the page. */
private class FigLabel(val c: WdpControl, val x: Int, val y: Int, val text: String)

/** The slider scales under the heading sliders: numbers and compass letters, no figures of their own. */
private val SCALE_LABELS = setOf("lbl0", "lbl30", "lbl60", "lbl90", "lblN", "lblE", "lblS", "lblW", "lblN360")

/** The panels on the right of an attack page, one shown at a time; the figures are on the others. */
private val RIGHT_PANELS = setOf("pnlName", "pnlSelections", "pnlDEDData", "pnlProfile")

/**
 * An attack page's figures as lines of text, read off the panels on its left the way the eye reads them: the labels of
 * one row joined left to right ("Ingress Height 1100 feet"), a lone bold label as a heading. The sliders are left out;
 * their values are the labels beside them. True in the pair marks a heading.
 */
internal fun figureRows(form: WdpForm, values: WdpValues): List<Pair<Boolean, String>> {
    val out = ArrayList<Pair<Boolean, String>>()
    fun visible(c: WdpControl): Boolean {
        val s = values[c.name]
        return s != "hidden" && !(c.hidden && s != "shown")
    }
    fun text(c: WdpControl): String? {
        val s = values[c.name]
        values["${c.name}.text"]?.let { return it }
        if (s != null && s != "shown") return s
        val t = c.text ?: return null
        if (t == c.name || c.name.endsWith(t) && c.name.length - t.length <= 3 && t.last().isDigit() && t.any { it.isLetter() }) return null
        return t
    }
    for (panel in form.roots.filter { it.kind == "panel" && it.name !in RIGHT_PANELS && visible(it) }.sortedBy { it.y }) {
        val labels = ArrayList<FigLabel>()
        fun walk(c: WdpControl, dx: Int, dy: Int) {
            for (k in c.children) {
                if (!visible(k)) continue
                when (k.kind) {
                    "label" -> if (k.name !in SCALE_LABELS) text(k)?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() }?.let { labels += FigLabel(k, dx + k.x, dy + k.y + k.h / 2, it) }
                    "panel", "group" -> walk(k, dx + k.x, dy + k.y)
                }
            }
        }
        walk(panel, 0, 0)
        val rows = ArrayList<MutableList<FigLabel>>()
        for (l in labels.sortedBy { it.y }) {
            val r = rows.lastOrNull()
            if (r != null && abs(r[0].y - l.y) <= 7) r += l else rows += mutableListOf(l)
        }
        for (r in rows) {
            val cells = r.sortedBy { it.x }
            val one = cells.singleOrNull()
            if (one != null && (one.c.bold || (one.c.fontSize ?: 0.0) >= 11.0 || one.c.h >= 18)) out += true to one.text
            else out += false to cells.joinToString(" ") { it.text }
        }
    }
    return out
}

// ==================================================================================================== the ground charts

/**
 * One of the card's airfields as BMS built it — runways, taxiways, the ramp and its spots for the runway the card has
 * in use — in the chart's day inks, turned to fill the page, with the card's figures for it above.
 */
@Composable
private fun GroundChartPage(scope: KbPageScope, i: Int) {
    val kind = listOf(KbKind.DEPARTURE, KbKind.ARRIVAL, KbKind.ALTERNATE)[i]
    val word = listOf("DEPARTURE", "ARRIVAL", "ALTERNATE")[i]
    val row = WdpSession.dataCard.plan.tblApt.getOrNull(i)
    val fk = KneeboardExtraPages.fieldKey(i)
    val apt = fk?.third
    if (fk != null) LaunchedEffect(fk.first) { KneeboardExtraPages.field(i) }
    val field = fk?.let { KneeboardExtraPages.fields[it.first] }
    val none = fk?.let { KneeboardExtraPages.noField[it.first] } == true
    val name = apt?.name ?: row?.name?.takeIf { it.isNotBlank() }
    Sheet(scope, "$word" + (name?.let { " — $it" } ?: ""), facts(row, apt, field)) {
        when {
            name == null -> Note("The DataCard has no ${word.lowercase()} airfield: press PRINT in BMS for the briefing, or pick one on the DataCard.")
            apt == null || fk == null -> Note("$name is not in this app's airfield list for the theater, so there is no chart to draw.")
            field == null -> Note(if (none) "Falcon BMS has no ground chart for $name." else "Loading $name…")
            else -> Box(Modifier.fillMaxWidth().weight(1f)) {
                AirfieldChart(
                    field = field,
                    route = routeFor(field, row?.rwy, inbound = i > 0),
                    inks = ChartInks.day,
                    modifier = Modifier.fillMaxSize(),
                    interactive = false,
                    fitRotation = true,
                )
            }
        }
        KneeboardExtraPages.drawn[kind] = when {
            field != null -> "${field.name}: ${field.runways.size} runways, ${field.routes.sumOf { it.parking.size }} spots, runway in use ${routeFor(field, row?.rwy)?.designator ?: "none"}" +
                (routeFor(field, row?.rwy, inbound = i > 0)?.designator?.takeIf { i > 0 && it != routeFor(field, row?.rwy)?.designator }?.let { ", ramp numbered as Ground does after landing (runway $it's network)" } ?: "") +
                (if (field.ship != null) " (a carrier)" else "")
            name == null -> "no airfield"
            none -> "$name: no chart"
            else -> "$name: loading"
        }
    }
}

/**
 * The runway the card has in use, as BMS's taxi network for that end; a carrier has none. For a field landed at
 * ([inbound]: the arrival and the alternate) it is the **taxi-in** network, the reciprocal end's, so the spot
 * numbers on the page are the ones Ground says after landing ([taxiInRoute]).
 */
private fun routeFor(field: Airfield, rwy: String?, inbound: Boolean = false): AfRoute? {
    if (field.ship != null) return null
    fun norm(s: String?) = s?.trim()?.uppercase()?.trimStart('0').orEmpty()
    val want = norm(rwy)
    val named = field.routes.firstOrNull { want.isNotEmpty() && norm(it.designator) == want } ?: return field.routes.firstOrNull()
    return if (inbound) taxiInRoute(field, named) else named
}

/** The card's figures for an airfield, on the line under the title. */
private fun facts(row: com.bmscompanion.app.data.wdp.DataCardPlan.Airport?, apt: Airport?, field: Airfield?): String {
    fun f3(v: Float) = if (v == 0f) null else DataCardNet.fmt(v, "#0.000")
    val parts = listOfNotNull(
        (apt?.icao ?: field?.icao)?.takeIf { it.isNotBlank() },
        row?.rwy?.takeIf { it.isNotBlank() }?.let { "RWY $it" },
        (row?.elv?.takeIf { it != 0f }?.roundToInt() ?: apt?.elevationFt)?.let { "ELV $it ft" },
        row?.tcn?.takeIf { it.isNotBlank() }?.let { "TCN $it" },
        row?.ils?.takeIf { it != 0f }?.let { "ILS " + DataCardNet.fmt(it, "#0.00") },
        row?.let { r -> listOfNotNull(f3(r.uhf), f3(r.vhf)).takeIf { it.isNotEmpty() }?.let { "TWR " + it.joinToString(" / ") } },
        row?.let { f3(it.gndUHF) }?.let { "GND $it" },
        row?.let { f3(it.appUHF) }?.let { "APP $it" },
        row?.let { f3(it.atisVHF) }?.let { "ATIS $it" },
    )
    return parts.joinToString(" · ")
}
