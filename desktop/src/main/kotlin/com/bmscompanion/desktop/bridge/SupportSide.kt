package com.bmscompanion.desktop.bridge

import com.bmscompanion.app.data.mission.SupportTrack
import java.io.File
import kotlin.math.hypot

/**
 * Which side a save's tankers, AWACS and JSTARS are on (1.4.0). The planned tracks [PlannedRoutes] reads are every
 * flight of the save on a refuelling or AEW mission, whoever flies it — so an enemy AWACS's orbit reached the Mission
 * map and the Planner's Map page as a track to add, and its callsign the DataCard. A support flight is kept only when
 * it is on the side of the flight being planned: its team is the flight's own, or one the team **controlling** the
 * flight's team is allied or friendly toward (WDP's `CheckOwnSideFlt`, [CampaignFiles] `sideSupport`; in Korea ROK
 * controls the U.S. team, so the U.S. KC-135s and E-3s stay with a U.S. flight).
 *
 * Why an allied nation's tanker or AWACS stays: Falcon BMS itself tasks support across allied teams — in the Korea
 * test save the AWACS package (Magic4, an E-3 of the U.S. team) is owned by ROK, and the package of a U.S. flight is
 * given that AWACS and U.S. tankers whichever team planned it — and the briefing names them as the package's own, with
 * their frequencies, so they are the ones the pilot calls. A neutral or hostile one is never kept: it is not the
 * pilot's to plan with.
 *
 * Nothing here throws: a save that cannot be read, or a flight that cannot be found in it, leaves the tracks as they
 * were (with no side known, nothing can be said either way).
 */
internal object SupportSide {
    private const val NEAR_FT = 1.5 * 6076.12
    private const val ENOUGH = 2

    /** Callsigns as the tracks and the save write them, compared without spaces or case. */
    fun key(s: String?): String = s?.replace(" ", "")?.lowercase().orEmpty()

    /** Whether team [other] is on the side of team [own] in [save] (WDP's `CheckOwnSideFlt`, own team always). */
    fun ours(save: CampaignArchive.Save, own: Int, other: Int): Boolean {
        if (other == own) return true
        val teams = save.teams ?: return false
        val ctrl = teams.firstOrNull { it.n == own }?.controller ?: own
        val stance = teams.firstOrNull { it.n == ctrl }?.stance
        return stance?.getOrNull(other).let { it == TeamRelations.ALLIED || it == TeamRelations.FRIENDLY }
    }

    /**
     * The save's flight flying [own] (theater feet, north and east): the one with the most of its waypoints on the
     * route's points, at least two; else [callsign]'s; null when neither finds one.
     */
    fun ownFlight(save: CampaignArchive.Save, own: List<Pair<Double, Double>>, callsign: String? = null): CampaignArchive.Flight? {
        val names = save.names
        if (callsign != null && names != null) save.flights.firstOrNull { key(names.callsign(it)) == key(callsign) }?.let { return it }
        if (own.isEmpty()) return null
        return save.flights.map { f ->
            f to own.count { (n, e) -> f.waypoints.any { hypot(it.north - n, it.east - e) < NEAR_FT } }
        }.filter { it.second >= ENOUGH }.maxByOrNull { it.second }?.first
    }

    /** What [sides] decided: the tracks kept, and the callsigns left out as not of the flight's side. */
    class Kept(val tracks: List<SupportTrack>, val dropped: List<String>, val sideKnown: Boolean)

    /**
     * [tracks] without the support flights of another side than [flight]'s in [save]. A track whose callsign the save
     * does not have stays (nothing says it is not ours); one whose callsign only another side's flight carries goes.
     */
    fun sides(tracks: List<SupportTrack>, save: CampaignArchive.Save, flight: CampaignArchive.Flight?): Kept {
        val names = save.names
        if (flight == null || names == null || tracks.isEmpty()) return Kept(tracks, emptyList(), false)
        val mine = HashSet<String>()
        val theirs = HashSet<String>()
        for (f in save.flights) {
            val c = key(names.callsign(f)).takeIf { it.isNotEmpty() } ?: continue
            if (ours(save, flight.owner, f.owner)) mine += c else theirs += c
        }
        val kept = ArrayList<SupportTrack>()
        val dropped = ArrayList<String>()
        for (t in tracks) {
            val c = key(t.callsign)
            if (c.isNotEmpty() && c in theirs && c !in mine) dropped += t.callsign.orEmpty() else kept += t
        }
        return Kept(kept, dropped, true)
    }

    /** [sides] for the save in [file], read (cached) with the theater's names; the tracks unchanged when it cannot be read. */
    fun filter(tracks: List<SupportTrack>, file: File?, set: Theaters.TheaterSet?, t: Theaters.Theater?, own: List<Pair<Double, Double>>, callsign: String? = null): List<SupportTrack> {
        if (tracks.isEmpty() || file == null || set == null) return tracks
        return try {
            val save = CampaignArchive.cached(file, CampaignArchive.names(set, t))
            val r = sides(tracks, save, ownFlight(save, own, callsign))
            if (r.dropped.isNotEmpty()) BridgeLog.info("Support tracks of another side left out: ${r.dropped.joinToString()}")
            r.tracks
        } catch (e: Throwable) {
            tracks
        }
    }
}

/**
 * `--supportsidetest out.txt <a BMS folder>` (read only): every save of every theater, and in each the flights a pilot
 * sits in (else each team's first flight with a route): the save's tankers, AWACS and JSTARS (missions 28, 26, 27)
 * before [SupportSide.sides] and after, with what it left out and why — the counts the 1.4.0 change is judged by.
 * FAIL where a kept one is of a team neither the flight's nor allied or friendly to its controller, or where one of
 * the flight's own team was left out.
 */
internal object SupportSideTest {
    private val SUPPORT = mapOf(26 to "AWACS", 27 to "JSTARS", 28 to "Tanker")

    fun run(root: File): String {
        val out = StringBuilder()
        var fails = 0
        var before = 0
        var after = 0
        val set = Theaters.at(root)
        out.appendLine("Support flights by side (1.4.0), ${root.name}: ${set.all.size} theaters")
        for (t in set.all) {
            val names = runCatching { CampaignArchive.names(set, t) }.getOrNull() ?: continue
            for (file in runCatching { set.saves(t) }.getOrDefault(emptyList())) {
                val save = runCatching { CampaignArchive.cached(file, names) }.getOrNull() ?: continue
                if (save.start || save.teams == null) continue
                val flights = save.playerFlights.ifEmpty {
                    save.flights.filter { it.waypoints.size >= 3 && it.mission !in SUPPORT.keys }.groupBy { it.owner }.values.map { it.first() }
                }
                if (flights.isEmpty()) continue
                out.appendLine()
                out.appendLine("== ${t.name}: ${file.name}  (teams: ${save.teams?.joinToString { "${it.n} ${it.name.trim()}" }})")
                for (f in flights) {
                    val support = save.flights.filter { it.mission in SUPPORT.keys }
                    val tracks = support.map { s -> SupportTrack(role = SUPPORT[s.mission]!!, callsign = names.callsign(s)) }
                    val r = SupportSide.sides(tracks, save, f)
                    before += tracks.size; after += r.tracks.size
                    val teamOf = support.associate { SupportSide.key(names.callsign(it)) to it.owner }
                    fun team(n: Int?) = n?.let { k -> save.teams?.firstOrNull { it.n == k }?.name?.trim() ?: "team $k" } ?: "?"
                    out.appendLine("  ${names.callsign(f)} (${team(f.owner)}): ${tracks.size} support flights in the save, ${r.tracks.size} kept, ${r.dropped.size} left out")
                    for (k in r.tracks) out.appendLine("     kept  ${k.role} ${k.callsign} (${team(teamOf[SupportSide.key(k.callsign)])})")
                    for (d in r.dropped) out.appendLine("     out   ${d} (${team(teamOf[SupportSide.key(d)])})")
                    for (k in r.tracks) {
                        val owner = teamOf[SupportSide.key(k.callsign)] ?: continue
                        if (!SupportSide.ours(save, f.owner, owner)) { fails++; out.appendLine("FAIL  ${k.callsign} kept, of ${team(owner)}") }
                    }
                    for (d in r.dropped) if (teamOf[SupportSide.key(d)] == f.owner) { fails++; out.appendLine("FAIL  $d left out, of the flight's own team") }
                }
            }
        }
        out.appendLine()
        out.appendLine("All saves: $before support flights before, $after after (summed over the flights checked)")
        out.appendLine(if (fails == 0) "ALL PASS" else "$fails FAIL")
        return out.toString()
    }
}
