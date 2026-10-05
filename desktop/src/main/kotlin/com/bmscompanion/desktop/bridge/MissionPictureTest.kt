package com.bmscompanion.desktop.bridge

import com.bmscompanion.app.data.mission.CampAto
import com.bmscompanion.app.data.mission.CampRef
import com.bmscompanion.app.data.mission.MissionData
import com.bmscompanion.app.data.mission.MissionMode
import com.bmscompanion.app.data.mission.MissionPicture
import com.bmscompanion.app.data.mission.MissionSourceInfo
import com.bmscompanion.app.data.mission.PcAnswer
import com.bmscompanion.app.data.mission.PopulateSend
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.math.hypot

/**
 * Part 10 of `--missiontest`: one mission picture for every map (docs/DATA-STORES.md, "One mission picture";
 * [MissionPicture], [MissionGrounds]). Against the copy, read only (a Populate writes only the scratch APPDATA):
 *
 * 1. **EZBoards mode after PRINT**: `/api/mission` carries the printed flight's threat picture from the save BMS is
 *    flying — only the sites the printed Threat Analysis names ([BriefedThreats]), no ground units, no package routes.
 * 2. **No cheating**: `/api/campaign/flight` and `/api/campaign/mapintel` carry no enemy site or unit the side has
 *    not seen, and `/api/contacts` lets a hostile live air defence through only at a known site ([KnownSams]).
 * 3. **WDP mode**: the same flight populated carries EZBoards mode's own picture when it is the printed flight (the
 *    snapshot carries the printed briefing), else the spotted sites whose ring reaches its route (a save's briefing has
 *    no threat section).
 */
internal object MissionPictureTest {
    private val client = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true; explicitNulls = false }

    fun run(ok: (Boolean, String) -> Unit, line: (String) -> Unit) {
        line("")
        line("== 10. One mission picture for every map")
        fun req(method: String, path: String, query: Map<String, String> = emptyMap(), body: String = ""): Pair<Int, String> {
            val r = Bridge.handle(ApiRequest(method, path, query, body.toByteArray(Charsets.UTF_8)))
            return r.status to r.body.toString(Charsets.UTF_8)
        }
        fun mission() = client.decodeFromString(MissionData.serializer(), req("GET", "/api/mission").second)
        fun source(r: Pair<Int, String>) = PcAnswer.read(r.first, r.second) { client.decodeFromString(MissionSourceInfo.serializer(), it) }
        try {
            MissionGrounds.forget()
            ModeSwitchReset.forget()
            // ---- 1. EZBoards mode
            val ez = mission()
            val g = ez.ground
            ok(ez.mode == MissionMode.EZBOARDS && g != null && g.save.isNotEmpty(),
                "EZBoards mode: /api/mission carries the printed flight's ground picture (${g?.save}, flight ${g?.flight}, ${g?.callsign})")
            if (g == null) return
            ok(g.callsign.equals(ez.briefing?.overview?.flight?.trim(), ignoreCase = true), "…of the printed flight itself (${ez.briefing?.overview?.flight})")
            val rows = MissionPicture.briefedThreats(ez.briefing)
            line("   the print's threats: " + rows.joinToString("; ") { "${it.system} ${it.where}" }.ifEmpty { "none" })
            ok(if (MissionPicture.hasThreatAnalysis(ez.briefing)) g.threatsFrom == "briefing" && g.airDefences.size <= rows.size && g.airDefences.size >= rows.size - 1
                else g.threatsFrom == "route",
                "…only the threats the briefing names (${g.airDefences.size} of ${rows.size} rows placed; ${g.airDefences.joinToString { it.system }}), from ${g.threatsFrom}")
            ok(g.units.isEmpty() && g.packageRoutes.isEmpty(), "…and no ground units or package routes (${g.units.size}, ${g.packageRoutes.size})")
            val save = runCatching { client.decodeFromString(CampAto.serializer(), req("GET", "/api/campaign/ato", mapOf("theater" to "Korea KTO", "file" to g.save)).second) }.getOrNull()
            val row = save?.packages?.flatMap { it.flights }?.firstOrNull { it.id == g.flight }
            val flight = row?.let {
                client.decodeFromString(com.bmscompanion.app.data.mission.CampFlight.serializer(),
                    req("GET", "/api/campaign/flight", mapOf("theater" to "Korea KTO", "file" to g.save, "flight" to it.id)).second)
            }

            // ---- 2. no cheating
            ok(flight != null && flight.airDefences.all { it.spotted } && flight.ships.all { it.spotted },
                "no route sends an unspotted site: /api/campaign/flight's ${flight?.airDefences?.size} air defences and ${flight?.ships?.size} ships are all spotted")
            val intel = runCatching {
                client.decodeFromString(com.bmscompanion.app.data.mission.CampMapIntel.serializer(),
                    req("GET", "/api/campaign/mapintel", mapOf("theater" to "Korea KTO", "file" to g.save, "flight" to g.flight)).second)
            }.getOrNull()
            ok(intel != null && intel.units.all { com.bmscompanion.app.data.wdp.PlannerIntel.seen(it) },
                "…nor /api/campaign/mapintel an unseen unit (${intel?.units?.size} units, all the side's or seen)")
            val first = g.airDefences.firstOrNull()
            if (first != null) {
                val near = com.bmscompanion.app.data.mission.Contact(id = "a", kind = "sam", x = first.x + 1500, y = first.y, name = first.system)
                val far = com.bmscompanion.app.data.mission.Contact(id = "b", kind = "sam", x = first.x + 40 * 6076.12, y = first.y + 40 * 6076.12, name = first.system)
                val own = com.bmscompanion.app.data.mission.Contact(id = "c", kind = "sam", x = 0.0, y = 0.0, name = "Patriot", friendly = true)
                val kept = KnownSams.filter(com.bmscompanion.app.data.mission.Contacts(contacts = listOf(near, far, own))).contacts.map { it.id }
                ok(kept == listOf("a", "c"), "a live hostile air defence passes only at a known site of its system (kept $kept of a, b far off, c own side)")
            }

            // ---- 3. WDP mode: the same flight populated, its spotted sites along the route
            val toWdp = source(req("POST", "/api/mission/source", mapOf("mode" to "wdp")))
            val pop = source(req("POST", "/api/mission/populate", body = Bridge.json.encodeToString(PopulateSend.serializer(), PopulateSend(CampRef("Korea KTO", g.save, g.flight), seat = 0))))
            val w = mission().ground
            val route = MissionPicture.routeOf(flight)
            val spotted = (intel?.units.orEmpty().map { it.x to it.y } + flight?.airDefences.orEmpty().map { it.x to it.y }).toSet()
            val off = w?.airDefences.orEmpty().filter { s ->
                val ring = w?.rings?.get(s.system) ?: (MissionPicture.UNKNOWN_RING_NM * 6076.12)
                MissionPicture.distanceToRoute(s.x, s.y, route) - ring > MissionPicture.ROUTE_MARGIN_NM * 6076.12 + 1
            }
            // the printed flight itself: the snapshot carries the printed briefing, so the picture is EZBoards mode's own
            if (row?.briefed == true && mission().populated?.briefingFrom == com.bmscompanion.app.data.mission.PlanMerge.ORIGIN_PRINTED)
                ok(toWdp.ok && pop.ok && w != null && w.flight == g.flight && w.threatsFrom == g.threatsFrom &&
                    w.airDefences.map { it.system to (it.x to it.y) }.toSet() == g.airDefences.map { it.system to (it.x to it.y) }.toSet() && w.units.isEmpty(),
                    "WDP mode, the printed flight populated: the same picture as EZBoards mode (${w?.airDefences?.size} sites from ${w?.threatsFrom})" +
                        (pop.error?.let { " — $it" } ?: ""))
            else ok(toWdp.ok && pop.ok && w != null && w.flight == g.flight && w.threatsFrom == "route" && off.isEmpty() &&
                w.airDefences.all { (it.x to it.y) in spotted } && w.units.isEmpty(),
                "WDP mode, the same flight populated: ${w?.airDefences?.size} spotted sites, each ringing the route (of ${spotted.size} spotted)" +
                    (pop.error?.let { " — $it" } ?: "") + (if (off.isNotEmpty()) " — not: ${off.take(3).map { it.system }}" else ""))
        } catch (e: Throwable) {
            ok(false, "part 10 threw: $e")
        } finally {
            runCatching { if (MissionSource.mode() != MissionMode.EZBOARDS) Bridge.update(reapply = false) { it.copy(MissionSource = MissionMode.EZBOARDS) } }
            ModeSwitchReset.forget()
        }
    }
}
