package com.bmscompanion.desktop.bridge

import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.mission.CampAtoTargets
import com.bmscompanion.app.data.wdp.WdpCoords
import com.bmscompanion.app.ui.screens.wdp.AtoCol
import com.bmscompanion.app.ui.screens.wdp.AtoTargetList
import com.bmscompanion.app.ui.screens.wdp.plannerTheater
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.io.File

/**
 * `--atotargettest <a copy of a BMS folder> out.txt [save] [theater]`: the Planner's ATO Target List (WDP's
 * `fclsAtoTargetList`) from a save, the way the window gets it — `GET /api/campaign/atotargets` answered in-process by
 * the PC's own routes over the copy, decoded as a client decodes it, and every row printed with the window's own cells
 * ([AtoTargetList.cell], its latitude and longitude, its sort) in WDP's twelve columns, with the two colours as marks
 * (`*` a flight airborne, `!` a TOT passed). Read only: the routes never open a file for writing.
 *
 * Checks: the route answers the pilot's side and both lists; every row names its flight, aircraft and mission, and every
 * target with a position gets a real latitude and longitude (WDP printed 00,00.000, D67); the rows are sorted by target
 * as WDP opens the window; the other side (the first team whose side shares no team with the pilot's) gets a list of
 * its own, with none of the pilot's side's flights; a team that is not a team
 * number and an unknown file are refused in words.
 */
object AtoTargetsTest {
    private val client = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true; explicitNulls = false }

    fun run(root: File, more: List<String>): String = buildString {
        var fails = 0
        fun check(name: String, ok: Boolean, detail: Any? = null) {
            appendLine((if (ok) "PASS " else "FAIL ") + name + (detail?.let { "  | $it" } ?: ""))
            if (!ok) fails++
        }
        val file = more.getOrNull(0) ?: "Auto Save.cam"
        val theaterName = more.getOrNull(1) ?: "Korea KTO"
        appendLine("BMS folder copy: ${root.path}; save: $file ($theaterName)")
        DevGuard.why(root)?.let {
            appendLine("FAIL REFUSED: $it. Point this at a copy of a BMS folder.")
            return@buildString
        }
        val before = Bridge.settings.value
        val wasRunning = Bridge.running
        try {
            Bridge.update { it.copy(BmsDirOverride = root.path, AutoEzBoardsOnPrint = false) }
            Bridge.startForCheck()
            check("the PC reads the copy", Bridge.install.baseDir?.let { Theaters.canonical(File(it)) } == Theaters.canonical(root), Bridge.install.baseDir)

            fun call(query: Map<String, String>): Pair<Int, String> {
                val r = Bridge.handle(ApiRequest("GET", "/api/campaign/atotargets", query))
                return r.status to r.body.toString(Charsets.UTF_8)
            }
            val (status, text) = call(mapOf("theater" to theaterName, "file" to file))
            val a = runCatching { client.decodeFromString(CampAtoTargets.serializer(), text) }.getOrNull()
            check("GET /api/campaign/atotargets answers 200 and decodes", status == 200 && a != null, "$status ${text.take(160)}")
            if (a == null) return@buildString

            // the window's own printing: the save's theater, the Planner's projection
            val theater = runBlocking { runCatching { plannerTheater(Repo.index().theaters, a.theater) }.getOrNull() }
            val coords = WdpCoords.coordData(theater)
            appendLine()
            appendLine("ATO Target List: ${a.file}  (${a.theater}; the app's theater ${theater?.id ?: "not found"}, projection ${if (coords != null) "yes" else "none"})")
            appendLine("Current Time: " + com.bmscompanion.app.data.wdp.DataCardPlan.getTimeDay(a.clock) + "   side: team ${a.side} = " + a.sideTeams.joinToString(", "))
            a.notes.forEach { appendLine("note: $it") }
            check("the pilot's side is known", a.side > 0 && a.sideTeams.isNotEmpty(), "team ${a.side}: ${a.sideTeams}")
            check("both lists have rows (a campaign strikes units and objectives)", a.units.isNotEmpty() && a.objectives.isNotEmpty(),
                "${a.units.size} units, ${a.objectives.size} objectives")

            for ((k, rows) in listOf(a.units, a.objectives).withIndex()) {
                appendLine()
                appendLine("== ${AtoTargetList.GRIDS[k]} (${rows.size})   * flight is airborne   ! TOT has passed")
                appendLine(AtoCol.entries.joinToString(" | ") { it.head })
                for (r in AtoTargetList.sorted(k, rows)) {
                    val ll = AtoTargetList.latLong(coords, r)
                    val mark = (if (a.clock > r.takeoff) "*" else " ") + (if (a.clock > r.tot) "!" else " ")
                    appendLine(mark + " " + AtoCol.entries.joinToString(" | ") { c -> AtoTargetList.cell(c, r, ll) })
                }
                check("${AtoTargetList.GRIDS[k]}: every row names its flight, aircraft and mission",
                    rows.all { it.flight.isNotBlank() && it.aircraft.isNotBlank() && it.mission.isNotBlank() && it.flightId.isNotBlank() },
                    rows.firstOrNull { it.flight.isBlank() || it.aircraft.isBlank() || it.mission.isBlank() }?.toString())
                val placed = rows.filter { it.x != null && it.y != null }
                val zero = placed.filter { r -> AtoTargetList.latLong(coords, r)?.first?.contains("00,00.000") != false }
                check("${AtoTargetList.GRIDS[k]}: every target with a position has a real latitude and longitude (WDP: 00,00.000)",
                    coords != null && placed.isNotEmpty() && zero.isEmpty(), "${placed.size} placed, ${zero.size} without; e.g. " +
                        placed.firstOrNull()?.let { r -> AtoTargetList.latLong(coords, r)?.toList()?.joinToString(" / ") })
                check("${AtoTargetList.GRIDS[k]}: sorted by TARGET as the window opens", rows.map { it.target.lowercase() } == rows.map { it.target.lowercase() }.sorted())

            }
            val listed = (a.units + a.objectives).map { it.team }.toSet()
            appendLine()
            appendLine("teams of the flights listed: $listed")

            // the other side: the first team whose side shares no team with the pilot's (a team allied to the pilot's,
            // Japan in Korea, is on the same side and gets the same list) and has flights tasked against a target
            val other = (1..7).firstOrNull { n -> n !in listed && n != a.side && call(mapOf("theater" to theaterName, "file" to file, "team" to "$n")).let { (s, t) ->
                s == 200 && runCatching { client.decodeFromString(CampAtoTargets.serializer(), t) }.getOrNull()?.let { o ->
                    o.sideTeams.none { it in a.sideTeams } && o.units.size + o.objectives.size > 0
                } == true
            } }
            if (other != null) {
                val o = client.decodeFromString(CampAtoTargets.serializer(), call(mapOf("theater" to theaterName, "file" to file, "team" to "$other")).second)
                check("team $other's side gets its own list", o.side == other && o.sideTeams != a.sideTeams &&
                    (o.units + o.objectives).none { r -> (a.units + a.objectives).any { it.flightId == r.flightId } },
                    "${o.sideTeams}: ${o.units.size} units, ${o.objectives.size} objectives")
            } else appendLine("(no other side has a flight tasked against a target in this save)")
            val (bad, badText) = call(mapOf("theater" to theaterName, "file" to file, "team" to "x"))
            check("a team that is not a team number is refused in words (400)", bad == 400 && badText.contains("error"), badText.take(120))
            val (missing, missingText) = call(mapOf("theater" to theaterName, "file" to "No such save.cam"))
            check("an unknown save is refused in words (400)", missing == 400 && missingText.contains("error"), missingText.take(120))
        } catch (t: Throwable) {
            check("the check ran to its end", false, "${t::class.java.simpleName}: ${t.message}")
        } finally {
            Bridge.update { before }
            if (!wasRunning) Bridge.stop()
        }
        appendLine()
        appendLine(if (fails == 0) "PASS: all checks" else "FAIL: $fails check(s)")
    }
}
