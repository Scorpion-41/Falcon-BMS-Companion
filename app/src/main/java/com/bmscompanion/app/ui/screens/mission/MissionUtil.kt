package com.bmscompanion.app.ui.screens.mission

import com.bmscompanion.app.data.Airport
import com.bmscompanion.app.data.AirportSet
import com.bmscompanion.app.data.Theater
import com.bmscompanion.app.data.mission.Briefing
import com.bmscompanion.app.data.mission.Contact
import com.bmscompanion.app.data.mission.Dtc
import com.bmscompanion.app.data.mission.Live
import com.bmscompanion.app.data.mission.MergedMission
import com.bmscompanion.app.data.mission.MergedPoint
import com.bmscompanion.app.data.mission.MissionData
import com.bmscompanion.app.data.mission.NavPoint
import com.bmscompanion.app.data.mission.PlanItemSource
import com.bmscompanion.app.data.mission.PlanMerge
import com.bmscompanion.app.ui.screens.FT_PER_NM
import com.bmscompanion.app.ui.screens.airportKind
import com.bmscompanion.app.ui.screens.bearingRange
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
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

/**
 * Matches a BMS airbase name ("Larissa", "Nea Anchialos Airbase, 11 nm SW of Volos", "Nea Tower") to an airport.
 *
 * Where the app reads it, BMS names a base by the **first word** of its name: the VoiceHelpers string in shared
 * memory and the comm ladder both say "Osan" for Osan AB, "Nea" for Nea Anchialos — and "USS" for every one of
 * Korea's three US carriers. So one name can fit several bases, and [clues] (what else the mission says about that
 * base) choose between them. When they cannot, a ship is never guessed: taking the first in the file is what put
 * every Korean carrier pilot on the Carl Vinson. An airfield keeps the first match, as it always did.
 */
// Compiled once: the live map matches its three bases on each of the four live ticks a second, and compiling these
// for every field of the theater each time was half of the main thread's work while a mission map was panned.
private val CALL_WORDS = Regex("\\b(ATIS|Tower|Ground|Approach|Departure|Airbase|Air Base|Airport|AB|AFB)\\b", RegexOption.IGNORE_CASE)
private val FIELD_WORDS = Regex("\\b(Airbase|Air Base|Airport|AB|AFB|Airstrip|Heliport)\\b", RegexOption.IGNORE_CASE)
/** a field's name without its kind, normalised (matchAirport's `an`), by name */
private val BARE_NAMES = HashMap<String, String>()

fun matchAirport(set: AirportSet?, raw: String?, clues: BaseClues = BaseClues()): Airport? {
    if (set == null || raw.isNullOrBlank()) return null
    val whole = raw.substringBefore(',').trim()
    val name = whole.replace(CALL_WORDS, "").trim()
    if (name.isEmpty()) return null
    val q = name.norm()
    fun an(a: Airport) = synchronized(BARE_NAMES) { BARE_NAMES.getOrPut(a.name) { a.name.replace(FIELD_WORDS, "").norm() } }
    // most exact first, and every base that fits the first test anything fits; the whole name comes first because
    // two bases can be the same once the words are taken off ("Fuxin Airbase", "Fuxin Airport")
    val tests = listOf<(Airport) -> Boolean>(
        { it.name.norm() == whole.norm() },
        { an(it) == q },
        { an(it).startsWith(q) },
        { q.startsWith(an(it)) && an(it).length >= 4 },
        { it.icao?.norm() == q },
    )
    val tiers = tests.map { t -> set.airports.filter(t) }
    val fits = tiers.firstOrNull { it.isNotEmpty() } ?: return null
    // The clues are asked about every base the name could stand for, not just the most exact: "Panghyon" is all of
    // Panghyon Airbase and the start of Panghyon Highwaystrip South, and BMS says "Panghyon" for both.
    clues.pick(tiers.flatten().distinct())?.let { return it }
    if (fits.size == 1) return fits[0]
    // Only a ship BMS would call by this word counts against the guess: "C" (the Falklands' C Armado Tola) is the start
    // of CV40 Tarawa too, but BMS calls that ship "CV40", so the airfield keeps its first match.
    return fits[0].takeUnless { afloat(it) || fits.any { f -> afloat(f) && f.name.trim().substringBefore(' ').norm() == q } }
}

/** A ship: BMS gives it no position (0, or half a grid cell), and some theaters type it as an airbase (Falklands). */
fun afloat(a: Airport): Boolean = airportKind(a) == "Carrier" || (abs(a.x) <= 1640.0 && abs(a.y) <= 1640.0)

/**
 * What a mission says about one of its bases besides the name, to tell apart the bases that name fits: the
 * frequencies its comm ladder rows give, the TACAN channels in the jet, and the hull number of the ship the jet is
 * standing on. Each narrows the choice only when it fits one of them, and the answer must be a single base.
 */
data class BaseClues(
    /** "270.200", from the ladder rows of that base */
    val freqs: List<String> = emptyList(),
    /** "10X" in the DED; weaker than the ladder, because the pilot may have tuned another ship's */
    val tacans: List<String> = emptyList(),
    /** "71", from Tacview's "CVN-71 Roosevl" */
    val hulls: List<String> = emptyList(),
) {
    fun pick(fits: List<Airport>): Airport? {
        var left = fits
        fun narrow(score: (Airport) -> Int) {
            val best = left.maxOf(score)
            if (best > 0) left = left.filter { score(it) == best }
        }
        if (hulls.isNotEmpty()) narrow { a -> numbers("${a.name} ${a.fullName}").count { it in hulls } }
        val mine = freqs.mapNotNull(::khz).toSet()
        if (mine.isNotEmpty()) narrow { a -> a.freqs?.run { listOf(towerUhf, towerVhf, groundUhf, approachUhf, opsUhf, lsoUhf, atisVhf) }.orEmpty().mapNotNull(::khz).count { it in mine } }
        // an air-to-air channel is a tanker or a wingman, never a base
        val channels = tacans.filterNot { "A/A" in it }.mapNotNull { Regex("^(\\d+)\\s*([XY])").find(it.trim().uppercase(Locale.US))?.value?.replace(" ", "") }
        if (channels.isNotEmpty()) narrow { a -> if (a.tacan?.label in channels) 1 else 0 }
        return left.singleOrNull()
    }

    private fun khz(mhz: String?) = mhz?.trim()?.toDoubleOrNull()?.let { (it * 1000).roundToInt() }
}

private fun numbers(s: String) = Regex("\\d+").findAll(s).map { it.value.trimStart('0').ifEmpty { "0" } }.toList()

/** The hull number of the ship the jet is standing on, from the name Tacview gives it ("CVN-71 Roosevl" → 71). */
fun deckHulls(live: Live?, contacts: List<Contact>?): List<String> {
    val l = live?.takeIf { !it.flying && (it.x != 0.0 || it.y != 0.0) } ?: return emptyList()
    val ship = contacts.orEmpty().filter { it.kind == "ship" }.minByOrNull { hypot(it.x - l.x, it.y - l.y) }
        ?.takeIf { hypot(it.x - l.x, it.y - l.y) < 1500 } ?: return emptyList()
    return numbers(ship.name.orEmpty())
}

data class Airbases(
    val departure: String?, val arrival: String?, val alternate: String?,
    val departureClues: BaseClues = BaseClues(), val arrivalClues: BaseClues = BaseClues(), val alternateClues: BaseClues = BaseClues(),
) {
    fun departureIn(set: AirportSet?) = matchAirport(set, departure, departureClues)
    fun arrivalIn(set: AirportSet?) = matchAirport(set, arrival, arrivalClues)
    fun alternateIn(set: AirportSet?) = matchAirport(set, alternate, alternateClues)
}

/** The bare words a carrier's ladder rows are called by, and the notes BMS puts beside them (Comms.b). */
private val LADDER_WORDS = Regex("\\b(ATIS|Tower|Ground|Approach|Departure|Paddles)\\b", RegexOption.IGNORE_CASE)
private val LADDER_NOTES = Regex("^(Departure|Recovery|Alternate)\\s+(Airbase|Carrier)|^Landing Signal Officer", RegexOption.IGNORE_CASE)

/**
 * The flight's departure, recovery and alternate bases, by name, with what else the mission says about each.
 * [contacts] (the Tacview picture) tells which ship the jet is sitting on.
 */
fun airbases(live: Live?, b: Briefing?, contacts: List<Contact>? = null): Airbases {
    val v = live?.voice
    fun rows(prefix: String) = b?.comms?.filter { it.agency.startsWith(prefix, true) }.orEmpty()
    // An airfield's rows are "Osan ATIS", "Osan Tower". A carrier's are plain "ATIS", "Tower", "Ground", and the
    // ship's name goes in the notes of the Ground row (Approach for recovery and alternate): Comms.b, #IF_DEP_CARRIER.
    fun ladder(prefix: String): String? {
        val rows = rows(prefix)
        return rows.firstOrNull { it.callsign != null }?.callsign?.takeIf { LADDER_WORDS.replace(it, "").isNotBlank() }
            ?: rows.firstNotNullOfOrNull { r -> r.notes?.trim()?.takeIf { it.isNotEmpty() && !LADDER_NOTES.containsMatchIn(it) } }
    }
    // a briefing printed for another flight says nothing about this one's bases
    val same = v?.flight == null || b?.overview?.flight == null || b.overview.flight.norm() == v.flight.norm()
    val tacans = listOfNotNull(live?.tacanUfc, live?.tacanAux)
    fun clues(prefix: String, home: Boolean, hulls: List<String> = emptyList()) = BaseClues(
        freqs = if (same) rows(prefix).flatMap { listOfNotNull(it.uhf, it.vhf) } else emptyList(),
        tacans = if (home) tacans else emptyList(),
        hulls = hulls,
    )
    return Airbases(
        departure = v?.departure ?: ladder("Dep "),
        arrival = v?.arrival ?: ladder("Arr "),
        alternate = v?.alternate ?: b?.alternate ?: ladder("Alt "),
        departureClues = clues("Dep ", home = true, deckHulls(live, contacts)),
        arrivalClues = clues("Arr ", home = true),
        alternateClues = clues("Alt ", home = false),
    )
}

/**
 * The bases of the merged mission: the briefing's (or the jet's) as [airbases] finds them, and — for a flight made
 * from a save, which has no comm ladder — the save's own home, landing and alternate bases where those say nothing.
 */
fun airbases(live: Live?, merged: MergedMission, contacts: List<Contact>? = null): Airbases {
    val b = airbases(live, merged.briefing, contacts)
    val f = merged.plan?.flight?.takeIf { merged.fromSave } ?: return b
    return b.copy(
        departure = b.departure ?: f.home?.name,
        arrival = b.arrival ?: f.landing?.name,
        alternate = b.alternate ?: f.alternate?.name,
    )
}

/**
 * One steerpoint with everything we know: the briefing row and the position the merge settled on (PlanMerge: the jet in
 * 3D, then the plan sent from the Planner, the cartridge, BMS's mission file).
 */
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
    // 1.3.8, from the merge (PlanMerge): where the position came from, and what the plan did to it
    val source: PlanItemSource = PlanItemSource.BRIEFING,
    /** the plan's point differs from what the jet has (or will load): drawn hollow, tagged NOT IN JET */
    val notInJet: Boolean = false,
    /** a steerpoint the briefing does not have, added by the plan: a PLAN row */
    val planRow: Boolean = false,
    /** the plan cleared it while the cartridge still holds it */
    val cleared: Boolean = false,
    /** a flight-plan point: the route line joins it (precision targets such as the recon bank are not) */
    val onRoute: Boolean = true,
    /** the cartridge action code (−1 a precision target, 1 take-off, 7 land …) */
    val actionCode: Int = 0,
    /** where the other of plan and jet has it, when they differ (drawn hollow, joined by a thin line) */
    val otherX: Double? = null,
    val otherY: Double? = null,
) {
    val hasPos get() = x != null && y != null && (x != 0.0 || y != 0.0)
    val isAlternate get() = comments?.contains("Alternate", true) == true
    val title get() = desc ?: targetName ?: if (planRow) PlanMerge.actionWord(actionCode) else "STPT $n"
    /** came from the Planner's plan */
    val fromPlan get() = source == PlanItemSource.PLAN
}

/** Every steerpoint (STPT 1-25) of the merged mission, in number order: the briefing's rows first of all. */
fun steerpoints(m: MissionData?, live: Live?): List<Stpt> = steerpoints(PlanMerge.merge(m, live))

fun steerpoints(merged: MergedMission): List<Stpt> = merged.allSteerpoints.map { it.toStpt() }

fun MergedPoint.toStpt() = Stpt(
    n = n,
    x = if (hasPos) x else null, y = if (hasPos) y else null, altFt = if (hasPos) altFt else null,
    desc = desc, time = time, cas = cas, altText = altText, action = actionText, comments = comments,
    isTarget = isTarget, targetName = name,
    source = source, notInJet = notInJet, planRow = planRow, cleared = cleared, onRoute = onRoute, actionCode = action,
    otherX = planX ?: jetX, otherY = planY ?: jetY,
)

/**
 * A pre-planned threat (or, with [marker], a point with no ring — an AWACS, a tanker, a friendly). [source] and
 * [notInJet] say what the plan did to it; a differing plan PPT is drawn dashed beside the jet's.
 */
data class Threat(
    val name: String, val x: Double, val y: Double, val rangeNm: Double,
    val marker: Boolean = false,
    val code: String? = null,
    val n: Int = 0,
    val source: PlanItemSource = PlanItemSource.CARTRIDGE,
    val notInJet: Boolean = false,
    val cleared: Boolean = false,
) {
    val fromPlan get() = source == PlanItemSource.PLAN
}

/** Every pre-planned point of the merged mission — threats and markers — slot by slot (PlanMerge). */
fun preplannedPoints(merged: MergedMission): List<Threat> = merged.ppts.map {
    Threat(it.name ?: "PPT ${it.n}", it.x, it.y, if (it.marker) 0.0 else it.rangeNm, it.marker, it.code, it.n, it.source, it.notInJet, it.cleared)
}

fun preplannedPoints(m: MissionData?, live: Live?): List<Threat> = preplannedPoints(PlanMerge.merge(m, live))

/** The pre-planned threats: the rings. The markers (AWACS, tanker, friendly) are not threats. */
fun preplannedThreats(m: MissionData?, live: Live?): List<Threat> = preplannedPoints(m, live).filter { !it.marker && it.rangeNm > 0 }

/**
 * Where a tanker or an AWACS is planned to be: the station the briefing names, before anybody is airborne.
 *
 * BMS publishes no flight plan for its support aircraft — not in the briefing tables, not in the DTC, not in
 * shared memory. What it does publish is the sentence in the support section ("Friendly tanker aircraft will be
 * orbiting 20 nm northeast of Larissa"), which is the planned station written out in words. That sentence is read
 * here and turned back into a position, so the station can be drawn on the map from the moment the briefing is
 * printed, rather than waiting for the aircraft to fly its orbit.
 */
data class SupportStation(
    val callsign: String,
    val role: String,
    val x: Double,
    val y: Double,
    /** how far from the named place the briefing put it, for the label */
    val fromText: String,
)

/** The compass words a briefing uses, as true bearings. */
private val COMPASS = mapOf(
    "north" to 0.0, "nne" to 22.5, "north-northeast" to 22.5, "northeast" to 45.0, "north-east" to 45.0,
    "ene" to 67.5, "east-northeast" to 67.5, "east" to 90.0, "ese" to 112.5, "east-southeast" to 112.5,
    "southeast" to 135.0, "south-east" to 135.0, "sse" to 157.5, "south-southeast" to 157.5, "south" to 180.0,
    "ssw" to 202.5, "south-southwest" to 202.5, "southwest" to 225.0, "south-west" to 225.0,
    "wsw" to 247.5, "west-southwest" to 247.5, "west" to 270.0, "wnw" to 292.5, "west-northwest" to 292.5,
    "northwest" to 315.0, "north-west" to 315.0, "nnw" to 337.5, "north-northwest" to 337.5,
)

private val STATION = Regex(
    // the place is one or two words and stops there: a greedy match ran on into "Larissa. Available for air
    // refueling", which is not a town in any theater
    """(\d+(?:\.\d+)?)\s*(?:nm|nautical\s+miles?)\s+([a-z\-]+)\s+(?:of|from)\s+([A-Za-z][A-Za-z'\-]{1,20}(?:\s+[A-Za-z][A-Za-z'\-]{1,20})?)""",
    RegexOption.IGNORE_CASE,
)

/**
 * Reads "20 nm northeast of Larissa" out of [notes] and turns it into a point, with [place] resolving a name to
 * theater feet. Anything it cannot read — an unknown compass word, a place this theater does not have — is left
 * alone rather than guessed at.
 */
fun stationFromNotes(notes: String?, place: (String) -> Pair<Double, Double>?): Pair<Pair<Double, Double>, String>? {
    val m = STATION.find(notes.orEmpty()) ?: return null
    val nm = m.groupValues[1].toDoubleOrNull() ?: return null
    val brg = COMPASS[m.groupValues[2].lowercase()] ?: return null
    val name = m.groupValues[3].trim().trimEnd('.', ',')
    val at = place(name) ?: return null
    val r = Math.toRadians(brg)
    val d = nm * 6076.12
    return (at.first + Math.cos(r) * d to at.second + Math.sin(r) * d) to "$nm nm ${m.groupValues[2].lowercase()} of $name"
}

/**
 * An air-defence site on the map: what the feed calls it, what system it is, and how far that system reaches.
 *
 * BMS streams its ground air defences by name ("SA-6 Gainful TEL", "SA-2 Guideline"), which is enough to find the
 * system in the app's own threat reference and draw its engagement range. A site whose name matches nothing is
 * still drawn — it is still a launcher — but without a ring, because an invented radius is worse than none.
 */
data class SamSite(
    val id: String,
    val x: Double,
    val y: Double,
    val label: String,
    val rangeNm: Double?,
    val friendly: Boolean,
    val threatId: String?,
)

/**
 * The air defences in [contacts], each matched against [reference] for its range.
 *
 * The longest matching name wins, so "SA-10 Grumble" is not read as "SA-1": a shorter name is a substring of the
 * longer one often enough to matter.
 */
/**
 * The systems the briefing named, as ids of the threat reference.
 *
 * A pilot is told what is out there in the threat section of the briefing; everything else the campaign knows is
 * not theirs to know yet. Matching those sentences against the reference gives the set of systems that may be
 * drawn, so a site the campaign has not briefed stays off the map however plainly the feed reports it.
 */
fun briefedSystems(mission: MissionData?, reference: List<com.bmscompanion.app.data.Threat>): Set<String> {
    val said = mission?.briefing?.threats.orEmpty().flatMap { listOfNotNull(it.title) + it.lines }.joinToString(" ").lowercase()
    if (said.isBlank()) return emptySet()
    return reference.filter { t ->
        (listOf(t.name) + t.aliases).any { it.length >= 3 && said.contains(it.lowercase()) }
    }.map { it.id }.toSet()
}

fun samSites(contacts: List<com.bmscompanion.app.data.mission.Contact>, reference: List<com.bmscompanion.app.data.Threat>): List<SamSite> {
    if (contacts.isEmpty()) return emptyList()
    val keys = reference.flatMap { t -> (listOf(t.name) + t.aliases).filter { it.length >= 3 }.map { it.lowercase() to t } }
        .sortedByDescending { it.first.length }
    return contacts.filter { it.kind == "sam" }.map { c ->
        val name = c.name.orEmpty()
        val hit = name.lowercase().let { n -> keys.firstOrNull { (k, _) -> n.contains(k) } }?.second
        SamSite(
            id = c.id,
            x = c.x,
            y = c.y,
            label = hit?.name ?: name.ifBlank { "Air defence" },
            rangeNm = hit?.let { it.numbers["maxRangeNm"] ?: it.numbers["typicalRangeNm"] },
            friendly = c.friendly,
            threatId = hit?.id,
        )
    }
}

/** The Threat Guide's air-defence categories: what a pre-planned threat ring or a SAM site can be. */
private val AIR_DEFENCE = setOf("SAM", "AAA", "MANPADS", "SAM_RADAR", "SEARCH_RADAR")

/**
 * The Threat Guide entry a threat's name stands for: a PPT's "SA-3", "Hawk" or "SA-10", a feed's "SA-6 Gainful TEL".
 *
 * A whole name equal to an entry's name or alias wins. Otherwise the name's first word is matched against the first
 * word of each entry's name and aliases: equal when it carries a digit ("SA-3" is "SA-3 Goa"), or when it is a word
 * of four letters or more naming an air defence ("KSAM", "Nike" — never "Ulsan", a waypoint that is also a ship's
 * name); the entry's with a letter after it ("SA-10" is the guide's "SA-10B"), never a digit ("SA-1" is not
 * "SA-10B"); and "Crotale-NG" is "Crotale". Null when the guide has no such system (a generic "AAA", "ManPads").
 */
fun threatGuideEntry(name: String?, reference: List<com.bmscompanion.app.data.Threat>): com.bmscompanion.app.data.Threat? {
    val words = name?.trim()?.split(Regex("\\s+"))?.filter { it.isNotBlank() }.orEmpty()
    if (words.isEmpty()) return null
    val whole = words.joinToString("").norm()
    val first = words[0].norm()
    val stem = words[0].substringBefore('-').norm()
    if (whole.length < 3) return null
    val digit = first.any { it.isDigit() }
    var best: com.bmscompanion.app.data.Threat? = null
    var bestScore = 0
    for (t in reference) {
        val ad = t.category in AIR_DEFENCE
        for (label in listOf(t.name) + t.aliases) {
            val lk = label.norm()
            val lw = label.trim().split(Regex("\\s+")).firstOrNull()?.norm().orEmpty()
            if (lk.isEmpty() || lw.isEmpty()) continue
            var score = when {
                lk == whole -> 300
                lw == first && (digit || (ad && first.length >= 4)) -> 200
                digit && lw.length > first.length && lw.startsWith(first) && lw.substring(first.length).all { it.isLetter() } -> 150
                ad && lw.length >= 4 && '-' in words[0] && stem == lw -> 100
                else -> 0
            }
            if (score == 0) continue
            // the entry's own name before an alias, an air defence before an aircraft or a ship
            if (label == t.name) score += 10
            if (ad) score += 5
            if (score > bestScore) { bestScore = score; best = t }
        }
    }
    return best
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
