package com.bmscompanion.desktop.bridge

import com.bmscompanion.app.data.mission.CampFlight
import com.bmscompanion.app.data.mission.MissionGround
import com.bmscompanion.app.data.mission.MissionPicture
import java.io.File

/**
 * The mission's threat picture ([MissionGround], `MissionData.ground`), which every map draws by default
 * (docs/DATA-STORES.md, "One mission picture"): only the mission's threats, and only sites the side has spotted.
 * Read only; nothing here throws.
 *
 * - **EZBoards mode** ([forBriefing]): the sites the printed briefing's Threat Analysis names ([BriefedThreats]),
 *   placed in the save BMS is flying (the newest save of the current theater, the one [MissionDtcFile] checks the
 *   mission file against). When that save **provably holds the printed flight** — BMS's mission file beside it is
 *   believed for that flight ([MissionDtcFile.Verdict.believed]), or the save holds a flight with the briefing's
 *   callsign, package number and flight number all three — the picture also has the save's bullseye and spotted
 *   ships near the route, and a print with no Threat Analysis section falls back on the spotted sites near the route.
 * - **WDP mode** ([forFlight], `wdp`): when the printed briefing is the populated flight's, the sites its Threat
 *   Analysis names, exactly as EZBoards mode places them; else the populated flight's spotted sites whose ring reaches
 *   its route ([MissionPicture.alongRoute]) — a save's briefing has no threat section.
 *
 * The flight comes through [CampaignFiles.flight] (the Planner's own answer) and the sites through [MapIntel.build];
 * [MissionPicture.ground] keeps what matters to the mission. Cached by the save's file key and the briefing's time.
 * [knownEz]/[knownWdp] keep every spotted site for the live filter ([KnownSams]).
 */
object MissionGrounds {
    private var lastKey: String? = null
    private var last: MissionGround? = null
    private var lastAssigned: List<String> = emptyList()

    /**
     * The tanker and AWACS the printed flight's package was given (lower-case callsigns), from the save that provably
     * holds it — what makes a track "yours" before 3D, when the sim has not named them yet ([Bridge.supportTracks]),
     * as WDP mode's snapshot and the Planner's Map page take them. Empty when [forBriefing] has nothing.
     */
    @Synchronized
    fun assigned(): List<String> = if (last != null) lastAssigned else emptyList()

    /**
     * Every enemy air-defence site the current mission's side is known to have seen, and the briefed ones — what a live
     * air defence of the Tacview feed is checked against ([KnownSams]); never sent to a device as such. EZBoards mode's
     * from [forBriefing], WDP mode's from the last [forFlight] of this run.
     */
    @Volatile var knownEz: List<com.bmscompanion.app.data.mission.CampSite> = emptyList()
        private set
    @Volatile var knownWdp: List<com.bmscompanion.app.data.mission.CampSite> = emptyList()
        private set

    /**
     * EZBoards mode's threat picture for the printed briefing: the save's, when it provably holds the printed flight;
     * else, when the briefing names threats, only those, placed in the newest save of the theater (towns do not move);
     * null when there is nothing to draw.
     */
    @Synchronized
    fun forBriefing(): MissionGround? = try {
        val install = Bridge.install
        val set = Theaters.of(install)
        val v = MissionDtcFile.verdict()
        val saveFile = v.save
        val b = Bridge.currentBriefing()
        if (set == null || saveFile == null || b == null) null
        else {
            val t = v.theater?.let { set.byName(it) } ?: set.current(install.theater)
            if (t == null) null
            else {
                val names = CampaignArchive.names(set, t)
                val key = listOf(t.name, CampaignArchive.key(saveFile), names.key, Bridge.briefingModified.toString(), v.believed.toString()).joinToString("#")
                if (key == lastKey) last
                else {
                    lastAssigned = emptyList()
                    knownEz = emptyList()
                    val value = compute(set, t, saveFile, names, v.believed) ?: briefedOnly(set, t, saveFile, names)
                    lastKey = key
                    last = value
                    value
                }
            }
        }
    } catch (e: Throwable) {
        BridgeLog.info("The mission's ground picture could not be read: ${e.message}")
        null
    }

    private fun compute(set: Theaters.TheaterSet, t: Theaters.Theater, saveFile: File, names: CampaignArchive.Names, believed: Boolean): MissionGround? {
        val b = Bridge.currentBriefing() ?: return null
        val save = CampaignArchive.cached(saveFile, names)
        if (save.uni == null) return null
        val f = MissionDtcFile.briefedFlight(save, b) ?: return null
        // the proof: BMS's own mission file is that flight's route, or the briefing names the flight three ways
        val key = CampaignFiles.briefKey(b, Bridge.briefingModified)
        val named = key != null && key.packageId != null && key.flightId != null
        if (!believed && !named) return null
        val ctx = CampaignFiles.context()
        val flight = when (val a = CampaignFiles.flight(ctx, t.name, saveFile.name, f.id.toString())) {
            is CampaignFiles.Answer.Ok -> a.value
            is CampaignFiles.Answer.Refused -> return null
        }
        lastAssigned = flight.support.filter { it.role == "Tanker" || it.role == "AWACS" }.map { it.callsign.trim().lowercase() }
        val g = forFlight(set, t, saveFile, f.id.toString(), flight, b, save, names) ?: return null
        knownEz = (lastSpotted + g.airDefences).distinctBy { it.x to it.y }
        return g
    }

    /**
     * The printed briefing's threats alone, placed in [saveFile] (a save that does not provably hold the printed flight:
     * its towns are the theater's all the same, [BriefedThreats]); null when the briefing names none.
     */
    private fun briefedOnly(set: Theaters.TheaterSet, t: Theaters.Theater, saveFile: File, names: CampaignArchive.Names): MissionGround? {
        val rows = MissionPicture.briefedThreats(Bridge.currentBriefing())
        if (rows.isEmpty()) return null
        val save = CampaignArchive.cached(saveFile, names)
        val objs = CampaignArchive.startFile(save)?.let { CampaignArchive.objectives(it) }
        val units = (MapIntel.build(set, t, saveFile, null, null) as? CampaignFiles.Answer.Ok)?.value?.units.orEmpty()
        val sites = BriefedThreats.place(rows, units, CampaignBriefing.Places(save, names, objs)).map { it.site }
        if (sites.isEmpty()) return null
        knownEz = sites
        val g = MissionGround(theater = t.name, airDefences = sites, threatsFrom = "briefing")
        return g.copy(rings = rings(set, t, sites.map { it.system }))
    }

    /** What [forFlight] read last: every site the flight's side has spotted. */
    @Volatile private var lastSpotted: List<com.bmscompanion.app.data.mission.CampSite> = emptyList()

    /** Each system's reach by the Planner's rule: the theater's own Ppt.ini, else the threat reference. */
    private fun rings(set: Theaters.TheaterSet, t: Theaters.Theater, systems: List<String>): Map<String, Double> {
        val table = (runCatching { PptTable.read(set.campaignDir(t)) }.getOrNull()?.takeIf { it.isNotEmpty() }
            ?: runCatching { PptTable.read(File(set.data, "Campaign")) }.getOrNull().orEmpty())
            .map { Triple(it.code, it.name, it.rangeFt) }
        val reference = runCatching { kotlinx.coroutines.runBlocking { com.bmscompanion.app.data.Repo.threats() } }.getOrDefault(emptyList())
        return systems.filter { it.isNotBlank() }.toSet()
            .mapNotNull { s -> com.bmscompanion.app.ui.screens.mission.siteRingFt(s, table, reference)?.let { s to it } }.toMap()
    }

    /**
     * The threat picture of [flight] ([flightId], "num/creator") of [saveFile] in theater [t]; null when nothing can be
     * read. [printed]: the printed briefing when it is this flight's — its Threat Analysis names the sites (placed in
     * [save]); without one (WDP mode: a save's briefing has no threat section) the spotted sites near the route.
     */
    fun forFlight(
        set: Theaters.TheaterSet, t: Theaters.Theater, saveFile: File, flightId: String, flight: CampFlight,
        printed: com.bmscompanion.app.data.mission.Briefing? = null, save: CampaignArchive.Save? = null, names: CampaignArchive.Names? = null,
        /** WDP mode's snapshot (its spotted sites are what the live filter checks against, [knownWdp]) */
        wdp: Boolean = printed == null,
    ): MissionGround? = try {
        val id = CampaignArchive.VuId.parse(flightId)
        val intel = when (val a = MapIntel.build(set, t, saveFile, id, null)) {
            is CampaignFiles.Answer.Ok -> a.value
            is CampaignFiles.Answer.Refused -> null
        }
        val spotted = MissionPicture.spotted(flight, intel)
        val briefed = printed?.takeIf { MissionPicture.hasThreatAnalysis(it) }?.let { b ->
            val n = names ?: CampaignArchive.names(set, t)
            val s = save ?: CampaignArchive.cached(saveFile, n)
            val objs = CampaignArchive.startFile(s)?.let { CampaignArchive.objectives(it) }
            BriefedThreats.place(MissionPicture.briefedThreats(b), intel?.units.orEmpty(), CampaignBriefing.Places(s, n, objs)).map { it.site }
        }
        val reach = rings(set, t, (spotted + flight.ships).map { it.system } + briefed.orEmpty().map { it.system })
        val g = MissionPicture.ground(flight, intel, t.name, saveFile.name, flightId, briefed) { reach[it] }
        lastSpotted = spotted
        if (wdp) knownWdp =(spotted + g.airDefences).distinctBy { it.x to it.y }
        g.copy(rings = reach.filterKeys { k -> (g.airDefences + g.ships).any { it.system == k } })
    } catch (e: Throwable) {
        BridgeLog.info("The threat picture of ${saveFile.name} could not be read: ${e.message}")
        null
    }

    /** Forgets the cached answer (a developer check that changes the files between two looks). */
    @Synchronized
    fun forget() { lastKey = null; last = null; lastAssigned = emptyList(); knownEz = emptyList() }
}
