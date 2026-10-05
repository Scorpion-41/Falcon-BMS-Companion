package com.bmscompanion.desktop.bridge

import com.bmscompanion.app.data.mission.CampAto
import com.bmscompanion.app.data.mission.CampAtoTargets
import com.bmscompanion.app.data.mission.CampFeatures
import com.bmscompanion.app.data.mission.CampFiles
import com.bmscompanion.app.data.mission.CampFlight
import com.bmscompanion.app.data.mission.CampGround
import com.bmscompanion.app.data.mission.CampMagVar
import com.bmscompanion.app.data.mission.CampMapIntel
import com.bmscompanion.app.data.mission.CampObjectives
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer

/**
 * `/api/campaign/…`: Falcon BMS's campaign, TE and training files, for the Planner's Open mission window and flight
 * picker on any device (docs/PROTOCOL.md, "Planner integration"). The work is [CampaignFiles]'s; this is the door.
 *
 * - `GET /api/campaign/files[?all=1]` → `CampFiles` (starts and templates only with `all=1`)
 * - `GET /api/campaign/ato?theater=&file=` → `CampAto`
 * - `GET /api/campaign/atotargets?theater=&file=[&team=]` → `CampAtoTargets` (WDP's ATO Target List; `team` the side, 1-7)
 * - `GET /api/campaign/flight?theater=&file=&flight=` → `CampFlight`
 * - `GET /api/campaign/objectives?theater=&file=` → `CampObjectives` (the DataCard's target list)
 * - `GET /api/campaign/features?theater=&file=&objective=` → `CampFeatures` (one objective's buildings)
 * - `GET /api/campaign/ground?theater=&at=n,e;n,e…` → `CampGround` (the ground under points, from BMS's height map:
 *   the attack pages' target elevation, [TerrainHeights]); `theater` may be left out for the one BMS is set to
 * - `GET /api/campaign/mapintel?theater=&file=[&flight=][&team=]` → `CampMapIntel` (the Planner Map page's units,
 *   search radars, airfield owners and JSTARS, [MapIntel]; `file` left out = the save BMS briefed; 404 for a save
 *   not found)
 * - `GET /api/campaign/magvar[?theater=]` → `CampMagVar` (BMS's magnetic variation map, [MagVarMap]; 404 when the
 *   theater has none)
 *
 * `theater` is a theater-definition name from the listing and `file` a plain file name in that theater's campaign
 * folder, checked against the folder's own listing; a client never sends a path. A request that cannot be answered
 * gets `{"error": "<sentence>"}`: 400 for one the PC cannot take (an unknown theater or file, no flight id), 409 for
 * the PC's present state (no BMS folder, a file BMS is still writing, a file that cannot be read).
 *
 * Read only: nothing on these routes opens a file for writing, so the developer-run write guard has nothing to stop.
 */
object CampaignRoutes {
    /** The routes this object answers, below `/api/campaign/`. */
    val ROUTES = listOf(
        "GET /api/campaign/files", "GET /api/campaign/ato", "GET /api/campaign/flight", "GET /api/campaign/objectives",
        "GET /api/campaign/features", "GET /api/campaign/ground", "GET /api/campaign/atotargets",
        "GET /api/campaign/mapintel", "GET /api/campaign/magvar",
    )

    fun handle(req: ApiRequest): ApiResponse {
        val path = req.path.trimEnd('/')
        val known = path == "/api/campaign/files" || path == "/api/campaign/ato" || path == "/api/campaign/flight" ||
            path == "/api/campaign/objectives" || path == "/api/campaign/features" || path == "/api/campaign/ground" ||
            path == "/api/campaign/atotargets" || path == "/api/campaign/mapintel" || path == "/api/campaign/magvar"
        if (!known) return ApiResponse.notFound()
        if (req.method != "GET") return error("Only GET is answered on $path: the campaign files are read, never written.", 405)
        return try {
            val ctx = CampaignFiles.context()
            if (path == "/api/campaign/ground") return ground(ctx, req.query["theater"], req.query["at"])
            if (path == "/api/campaign/magvar") return magVar(ctx, req.query["theater"])
            if (path == "/api/campaign/mapintel") return answer(
                CampMapIntel.serializer(), seenOnly(MapIntel.answer(ctx, req.query["theater"], req.query["file"], req.query["flight"], req.query["team"])),
            )
            when (path) {
                "/api/campaign/files" -> {
                    val all = req.query["all"].let { it == "1" || it.equals("true", ignoreCase = true) }
                    encode(CampFiles.serializer(), CampaignFiles.list(ctx, all))
                }
                "/api/campaign/ato" -> answer(CampAto.serializer(), CampaignFiles.ato(ctx, req.query["theater"], req.query["file"]))
                "/api/campaign/atotargets" -> answer(
                    CampAtoTargets.serializer(), CampaignFiles.atoTargets(ctx, req.query["theater"], req.query["file"], req.query["team"]),
                )
                "/api/campaign/objectives" -> answer(CampObjectives.serializer(), CampaignFiles.objectives(ctx, req.query["theater"], req.query["file"]))
                "/api/campaign/features" -> answer(
                    CampFeatures.serializer(), CampaignFiles.features(ctx, req.query["theater"], req.query["file"], req.query["objective"]),
                )
                else -> answer(CampFlight.serializer(), CampaignFiles.flight(ctx, req.query["theater"], req.query["file"], req.query["flight"]))
            }
        } catch (e: Throwable) {
            BridgeLog.warn("$path: ${e.message}")
            error("The campaign files could not be read: ${(e.message ?: e.javaClass.simpleName).trimEnd('.')}.", 409)
        }
    }

    /**
     * No cheating: what leaves the PC of a save's units is only what the flight's side knows of — its own side's, and
     * the others' it has seen ([com.bmscompanion.app.data.wdp.PlannerIntel.seen]: spotted, seen recently, or in a JSTARS
     * area). WDP's "Cheat SAM" layers are not offered, so nothing on a device needs the rest.
     */
    private fun seenOnly(a: CampaignFiles.Answer<CampMapIntel>): CampaignFiles.Answer<CampMapIntel> = when (a) {
        is CampaignFiles.Answer.Ok -> CampaignFiles.Answer.Ok(a.value.copy(units = a.value.units.filter { com.bmscompanion.app.data.wdp.PlannerIntel.seen(it) }))
        is CampaignFiles.Answer.Refused -> a
    }

    /** The magnetic variation map of [theater] (a theater-definition name or the app's id), else of the one BMS is set to. */
    private fun magVar(ctx: CampaignFiles.Context, theater: String?): ApiResponse {
        val set = ctx.set ?: return error("No Falcon BMS folder is set, so there is no variation map to read.", 409)
        val want = theater?.trim()?.takeIf { it.isNotEmpty() }
        val t = (if (want != null) set.all.firstOrNull { it.name.equals(want, ignoreCase = true) } ?: set.byName(want) else set.current(ctx.curTheater))
            ?: return error("There is no theater called \"${want ?: ctx.curTheater ?: "?"}\" in Falcon BMS's theater list.", 400)
        val found = MagVarMap.of(set, t)
        return found.value?.let { encode(CampMagVar.serializer(), it) } ?: error(found.reason ?: "Falcon BMS has no magnetic variation map for ${t.name}.", 404)
    }

    /** The ground under [at] ("north,east;north,east…", theater feet, at most 200 points) in [theater], else BMS's own. */
    private fun ground(ctx: CampaignFiles.Context, theater: String?, at: String?): ApiResponse {
        val set = ctx.set ?: return error("No Falcon BMS folder is set, so there is no height map to read.", 409)
        val want = theater?.trim()?.takeIf { it.isNotEmpty() }
        val t = (if (want != null) set.all.firstOrNull { it.name.equals(want, ignoreCase = true) } ?: set.byName(want) else set.current(ctx.curTheater))
            ?: return error("There is no theater called \"${want ?: ctx.curTheater ?: "?"}\" in Falcon BMS's theater list.", 400)
        val points = at.orEmpty().split(';').filter { it.isNotBlank() }.map { p ->
            val v = p.split(',').map { it.trim().toDoubleOrNull() }
            if (v.size != 2 || v[0] == null || v[1] == null) return error("Each point is north,east in feet: \"$p\" is not one.", 400)
            v[0]!! to v[1]!!
        }
        if (points.isEmpty() || points.size > 200) return error("Say which points: at=north,east;north,east (1 to 200 of them).", 400)
        return encode(CampGround.serializer(), TerrainHeights.at(set, t, points))
    }

    private fun <T> answer(serializer: KSerializer<T>, a: CampaignFiles.Answer<T>): ApiResponse = when (a) {
        is CampaignFiles.Answer.Ok -> encode(serializer, a.value)
        is CampaignFiles.Answer.Refused -> error(a.sentence, a.status)
    }

    private fun <T> encode(serializer: KSerializer<T>, value: T) = ApiResponse.json(Bridge.json.encodeToString(serializer, value))

    private fun error(sentence: String, status: Int) =
        ApiResponse.json("""{"error":${Bridge.json.encodeToString(String.serializer(), sentence)}}""", status)
}
