package com.bmscompanion.app.ui.screens.mission

import com.bmscompanion.app.data.Airport
import com.bmscompanion.app.data.AirportSet
import com.bmscompanion.app.data.Theater
import com.bmscompanion.app.data.mission.Briefing
import com.bmscompanion.app.data.mission.Dtc
import com.bmscompanion.app.data.mission.Live
import com.bmscompanion.app.data.mission.MissionData
import com.bmscompanion.app.data.mission.NavPoint
import com.bmscompanion.app.ui.screens.FT_PER_NM
import com.bmscompanion.app.ui.screens.bearingRange
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Finds our bundled theater for the BMS theater name (e.g. "Hellas WCP" → hellas-wcp). */
fun resolveTheater(theaters: List<Theater>, bmsName: String?): Theater? {
    if (bmsName.isNullOrBlank()) return null
    val n = bmsName.norm()
    return theaters.firstOrNull { it.name.norm() == n }
        ?: theaters.firstOrNull { it.id.norm() == n }
        ?: theaters.firstOrNull { n.startsWith(it.name.norm()) || it.name.norm().startsWith(n) }
}

fun String.norm() = lowercase(Locale.US).filter { it.isLetterOrDigit() }

/** Matches a BMS airbase name ("Larissa", "Nea Anchialos Airbase, 11 nm SW of Volos", "Nea Tower") to an airport. */
fun matchAirport(set: AirportSet?, raw: String?): Airport? {
    if (set == null || raw.isNullOrBlank()) return null
    val name = raw.substringBefore(',').replace(Regex("\\b(ATIS|Tower|Ground|Approach|Departure|Airbase|Air Base|Airport|AB|AFB)\\b", RegexOption.IGNORE_CASE), "").trim()
    if (name.isEmpty()) return null
    val q = name.norm()
    fun an(a: Airport) = a.name.replace(Regex("\\b(Airbase|Air Base|Airport|AB|AFB|Airstrip|Heliport)\\b", RegexOption.IGNORE_CASE), "").norm()
    return set.airports.firstOrNull { an(it) == q }
        ?: set.airports.firstOrNull { an(it).startsWith(q) }
        ?: set.airports.firstOrNull { q.startsWith(an(it)) && an(it).length >= 4 }
        ?: set.airports.firstOrNull { it.icao?.norm() == q }
}

data class Airbases(val departure: String?, val arrival: String?, val alternate: String?)

fun airbases(live: Live?, b: Briefing?): Airbases {
    val v = live?.voice
    fun ladder(prefix: String) = b?.comms?.firstOrNull { it.agency.startsWith(prefix, true) && it.callsign != null }?.callsign
    return Airbases(
        departure = v?.departure ?: ladder("Dep "),
        arrival = v?.arrival ?: ladder("Arr "),
        alternate = v?.alternate ?: b?.alternate ?: ladder("Alt "),
    )
}

/** One steerpoint with everything we know: briefing row + coordinates from shared memory (3D) or the DTC file. */
data class Stpt(
    val n: Int,
    val x: Double?,
    val y: Double?,
    val altFt: Double?,
    val desc: String?,
    val time: String?,
    val cas: String?,
    val altText: String?,
    val action: String?,
    val comments: String?,
    val isTarget: Boolean,
    val targetName: String?,
) {
    val hasPos get() = x != null && y != null && (x != 0.0 || y != 0.0)
    val isAlternate get() = comments?.contains("Alternate", true) == true
    val title get() = desc ?: targetName ?: "STPT $n"
}

fun steerpoints(m: MissionData?, live: Live?): List<Stpt> {
    val b = m?.briefing
    val dtc = m?.dtc
    val liveWp = live?.navPoints?.filter { it.type == "WP" }.orEmpty().associateBy { it.i }
    val dtcWp = dtc?.steerpoints.orEmpty().associateBy { it.n }
    val numbers = (b?.steerpoints.orEmpty().map { it.n } + liveWp.keys + dtcWp.keys.filter { it <= 25 }).distinct().sorted()
    return numbers.map { n ->
        val row = b?.steerpoints?.firstOrNull { it.n == n }
        val lp = liveWp[n]
        val dp = dtcWp[n]
        val desc = row?.desc
        Stpt(
            n = n,
            x = lp?.x ?: dp?.x, y = lp?.y ?: dp?.y, altFt = lp?.altFt ?: dp?.altFt,
            desc = desc, time = row?.time, cas = row?.cas, altText = row?.alt, action = row?.action, comments = row?.comments,
            isTarget = dp?.isTarget == true || desc?.contains("Attack", true) == true || desc?.contains("Target", true) == true || desc?.contains("Recon", true) == true,
            targetName = dp?.name,
        )
    }
}

data class Threat(val name: String, val x: Double, val y: Double, val rangeNm: Double)

fun preplannedThreats(m: MissionData?, live: Live?): List<Threat> {
    val fromLive = live?.navPoints?.filter { it.type == "PT" && (it.rangeNm ?: 0.0) > 0 }.orEmpty()
    if (fromLive.isNotEmpty()) return fromLive.map { Threat(it.name ?: "PPT ${it.i}", it.x, it.y, it.rangeNm ?: 0.0) }
    return m?.dtc?.ppts.orEmpty().map { Threat(it.name ?: "PPT ${it.n}", it.x, it.y, it.rangeNm) }
}

fun markpoints(live: Live?): List<NavPoint> = live?.navPoints?.filter { it.type == "MK" || it.type == "DL" }.orEmpty()

fun ownship(live: Live?): Pair<Double, Double>? = live?.takeIf { it.flying && (it.x != 0.0 || it.y != 0.0) }?.let { it.x to it.y }

fun bullseye(live: Live?, contacts: List<com.bmscompanion.app.data.mission.Contact>?): Pair<Double, Double>? =
    live?.bullX?.let { bx -> live.bullY?.let { by -> bx to by } }
        ?: contacts?.firstOrNull { it.kind == "bullseye" && it.friendly }?.let { it.x to it.y }
        ?: contacts?.firstOrNull { it.kind == "bullseye" }?.let { it.x to it.y }

/** "045/32" bearing/range from a reference point, bearings true like the theater map. */
fun bra(fromX: Double, fromY: Double, toX: Double, toY: Double): String {
    val (b, r) = bearingRange(fromX, fromY, toX, toY)
    return String.format(Locale.US, "%03d/%d", (b.roundToInt() % 360 + 360) % 360, r.roundToInt())
}

fun rangeNm(ax: Double, ay: Double, bx: Double, by: Double) = bearingRange(ax, ay, bx, by).second

/** Aspect of a target relative to the ownship (hot / flank / beam / drag). */
fun aspect(targetHdg: Double, targetX: Double, targetY: Double, ownX: Double, ownY: Double): String {
    val (brgToOwn, _) = bearingRange(targetX, targetY, ownX, ownY)
    var d = abs(((targetHdg - brgToOwn) % 360 + 540) % 360 - 180)
    return when { d <= 30 -> "hot"; d <= 60 -> "flank"; d <= 120 -> "beam"; else -> "drag" }
}

fun flightLevel(altFt: Double): String = if (altFt >= 1000) "${(altFt / 1000).roundToInt()}k" else "${altFt.roundToInt()}ft"

fun zulu(sec: Int): String = String.format(Locale.US, "%02d:%02d:%02dZ", sec / 3600 % 24, sec / 60 % 60, sec % 60)

/** ALR-56M symbol text for the BMS RWR symbol id (see Tools/RwrEmulator ScopeRenderer.cs). Null = draw a glyph. */
fun rwrText(sym: Int): String? = when (sym) {
    1 -> "U"; 4 -> "M"; 5 -> "H"; 6 -> "P"; 7 -> "2"; 8 -> "3"; 9 -> "4"; 10 -> "5"; 11 -> "6"; 12 -> "8"; 13 -> "9"
    14 -> "10"; 15 -> "13"; 16 -> "A"; 17 -> "S"; 19 -> "C"; 20 -> "15"; 21 -> "N"; 22, 23, 24 -> "A"; 25, 26 -> "P"
    27, 28, 29 -> "U"; 30 -> "C"; 32 -> "4"; 33 -> "5"; 34 -> "6"; 35 -> "14"; 36 -> "15"; 37 -> "16"; 38 -> "18"; 39 -> "19"
    40 -> "20"; 41 -> "21"; 42 -> "22"; 43 -> "23"; 44 -> "25"; 45 -> "27"; 46 -> "29"; 47 -> "30"; 48 -> "31"
    49 -> "P"; 50 -> "PD"; 51 -> "A"; 52 -> "B"; 53 -> "S"; 54, 55, 56 -> "A"
    else -> null
}

/** 2 = advanced interceptor, 3 = basic interceptor, 18 = naval: drawn as glyphs. */
fun rwrGlyph(sym: Int): String? = when (sym) { 2 -> "adv"; 3 -> "basic"; 18 -> "naval"; else -> null }

fun rwrName(sym: Int): String = when (sym) {
    2 -> "Advanced fighter"; 3 -> "Basic fighter"; 4 -> "Active missile"; 5 -> "Hawk"; 6 -> "Patriot"; 7 -> "SA-2"; 8 -> "SA-3"; 9 -> "SA-4"
    10 -> "SA-5"; 11 -> "SA-6"; 12 -> "SA-8"; 13 -> "SA-9"; 14 -> "SA-10"; 15 -> "SA-13"; 16 -> "AAA"; 17 -> "Search radar"; 18 -> "Naval"
    19 -> "Chaparral"; 20 -> "SA-15"; 21 -> "Nike"; 30 -> "KSAM"; 1, 27, 28, 29 -> "Unknown"
    else -> "Emitter $sym"
}

fun Dtc?.hasData() = this != null && (steerpoints.isNotEmpty() || uhf.isNotEmpty() || ppts.isNotEmpty())

const val NM = FT_PER_NM
