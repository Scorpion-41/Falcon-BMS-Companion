package com.bmscompanion.desktop.bridge

import androidx.compose.ui.graphics.asComposeCanvas
import com.bmscompanion.app.data.mission.CampMagVar
import com.bmscompanion.app.data.mission.CampMapIntel
import com.bmscompanion.app.ui.components.drawUnitSymbol
import java.io.File
import kotlin.math.abs

/**
 * `--mapinteltest out.txt <a copy of a BMS folder> [--theater=<name>] [<save> [<flight>]]`: what the Planner Map page's
 * two routes answer ([MapIntel], `/api/campaign/mapintel`; [MagVarMap], `/api/campaign/magvar`), read from a copy of
 * a Falcon BMS folder. Read only: nothing is written anywhere but the report.
 *
 * Per theater's newest save (or the one given, a file name in the theater's campaign folder, with the flight as
 * "num/creator" or a callsign; else the save's player flight, else its first): units per kind and side, what the
 * flight's side has seen of them, the radar states and the evidence for reading a roster slot as a count, the SAM
 * systems per side, the search radars, the airfields that changed hands, the JSTARS, the answer's size — and the air
 * defences against [CampaignBriefing.sites] (the count and the spotted count must agree: on the user's Korea save
 * 179 and 76). Then how WDP's nine radar feature numbers fare in the theater's feature table, and the variation map at
 * the four corners of every theater that has one, Korea's checked against BMS's file (-6.07, -7.18, -8.60, -10.47).
 */
object MapIntelTest {
    fun run(root: File, rest: List<String>): String = buildString {
        fun ok(what: String, pass: Boolean, detail: Any? = null) =
            appendLine((if (pass) "ok   " else "FAIL ") + what + (detail?.let { "  — $it" } ?: ""))
        val want = rest.firstOrNull { it.startsWith("--theater=") }?.substringAfter('=')?.trim()
        val plain = rest.filterNot { it.startsWith("--") }
        val saveName = plain.getOrNull(0)
        val flightArg = plain.getOrNull(1)
        appendLine("mapinteltest ${java.time.LocalDateTime.now().withNano(0)}  root ${root.path}")
        rest.firstOrNull { it.startsWith("--symbols=") }?.substringAfter('=')?.let { png ->
            val r = runCatching { symbolSheet(File(png)) }
            ok("the unit symbols are drawn to $png", r.isSuccess, r.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message}" })
        }
        val set = Theaters.at(root)
        ok("the copy's theater list reads", set.all.isNotEmpty(), set.error ?: "${set.all.size} theaters")
        if (set.all.isEmpty()) return@buildString
        val theaters = if (want != null) listOfNotNull(set.byName(want)) else set.all
        if (want != null) ok("theater \"$want\" is in the list", theaters.isNotEmpty())

        // ------------------------------------------------------------ 1. the intel of each theater's save
        appendLine()
        appendLine("== 1. map intel")
        var readAny = false
        for (t in theaters) {
            val file = if (saveName != null) set.saves(t).firstOrNull { it.name.equals(saveName, ignoreCase = true) } else set.newestSave(t, Long.MAX_VALUE)
            if (file == null) {
                if (saveName != null || want != null) ok("${t.name}: ${saveName ?: "a save"} is in the campaign folder", false)
                continue
            }
            readAny = true
            appendLine()
            appendLine("-- ${t.name}: ${file.name}")
            val names = CampaignArchive.names(set, t)
            val save = CampaignArchive.cached(file, names)
            if (save.uni == null) { ok("${file.name} reads", false, save.error); continue }
            val flight = flightArg?.let { a ->
                CampaignArchive.VuId.parse(a)?.let { save.flight(it) } ?: save.flights.firstOrNull { names.callsign(it).equals(a, ignoreCase = true) }
            } ?: save.playerFlights.firstOrNull() ?: save.flights.firstOrNull()
            if (flightArg != null) ok("flight $flightArg is in ${file.name}", flight != null && (flightArg.contains('/') || names.callsign(flight).equals(flightArg, true)))
            appendLine("flight: ${flight?.let { "${names.callsign(it)} (${it.id}), team ${it.owner}" } ?: "none"}; clock ${CampaignArchive.clock(save.header?.currentTime ?: 0)}")
            val t0 = System.nanoTime()
            val answer = MapIntel.build(set, t, file, flight?.id, if (flight == null) 0 else null)
            val ms = (System.nanoTime() - t0) / 1e6
            val intel = (answer as? CampaignFiles.Answer.Ok)?.value
            ok("the intel is made", intel != null, (answer as? CampaignFiles.Answer.Refused)?.sentence ?: "%.0f ms".format(ms))
            if (intel == null) continue
            report(intel, ::ok)
            appendLine(rosterEvidence(set, t, file))
            CampaignArchive.deltas(file)?.let { d ->
                val objs = CampaignArchive.startFile(save)?.let { CampaignArchive.objectives(it) }
                val damaged = d.deltas.count { dl -> (0 until dl.status.size * 4).any { CampaignArchive.featureStatus(dl.status, it) >= 2 } }
                val owners = d.deltas.count { dl -> objs?.get(dl.id)?.let { it.owner != dl.owner } == true }
                ok("the objective changes (.obd) read whole", d.exact, "${d.deltas.size} of ${d.records}, ${d.end} of ${d.unpacked} bytes${d.stop?.let { ", $it" } ?: ""}")
                appendLine("objective changes: ${d.deltas.size}; with a building damaged or destroyed $damaged; with another owner than the start's $owners; " +
                    "not in the start file ${d.deltas.count { objs?.get(it.id) == null }}")
            }
            if (saveName != null) {
                // one of each, for whoever draws them
                intel.units.firstOrNull { it.kind == "airdefence" && it.side == "hostile" }?.let { appendLine("sample unit: " + Bridge.json.encodeToString(com.bmscompanion.app.data.mission.CampMapUnit.serializer(), it)) }
                intel.radars.firstOrNull()?.let { appendLine("sample radar: " + Bridge.json.encodeToString(com.bmscompanion.app.data.mission.CampMapRadar.serializer(), it)) }
                intel.fields.firstOrNull { it.squadrons.isNotEmpty() }?.let { appendLine("sample field: " + Bridge.json.encodeToString(com.bmscompanion.app.data.mission.CampMapField.serializer(), it)) }
                intel.jstars.firstOrNull()?.let { appendLine("sample jstar: " + Bridge.json.encodeToString(com.bmscompanion.app.data.mission.CampMapJstar.serializer(), it)) }
            }
            // the air defences against what /api/campaign/flight serves
            if (flight != null) {
                val (sites, ships) = CampaignBriefing.sites(save, names, flight)
                val ad = intel.units.filter { it.kind == "airdefence" && it.domain == "land" && it.side == "hostile" && !it.dead }
                ok("hostile air defences = CampFlight.airDefences", ad.size == sites.size, "${ad.size} here, ${sites.size} there")
                ok("of them spotted = CampFlight.airDefences' spotted", ad.count { it.spotted } == sites.count { it.spotted },
                    "${ad.count { it.spotted }} here, ${sites.count { it.spotted }} there")
                val tf = intel.units.filter { it.domain == "sea" && it.side == "hostile" && !it.dead }
                ok("hostile task forces = CampFlight.ships", tf.size == ships.size, "${tf.size} here, ${ships.size} there")
                if (t.name.equals("Korea KTO", ignoreCase = true) && file.name.equals("Auto Save.cam", ignoreCase = true) && saveName != null) {
                    ok("Korea's Auto Save: 179 air defences, 76 spotted", ad.size == 179 && ad.count { it.spotted } == 76, "${ad.size} / ${ad.count { it.spotted }}")
                }
            }
            val (wdpNot, missed) = MapIntel.radarRule(set, t)
            appendLine("WDP's radar feature numbers not named \"Radar\" here: ${wdpNot.ifEmpty { listOf("none") }.joinToString("; ")}")
            appendLine("radar features WDP's numbers leave out: ${missed.ifEmpty { listOf("none") }.joinToString("; ")}")
        }
        if (!readAny) ok("a save was found to read", false, "no theater of the copy has a save")

        // ------------------------------------------------------------ 2. the variation maps
        appendLine()
        appendLine("== 2. magnetic variation")
        val koreaCorners = mapOf("SW" to -6.07, "SE" to -7.18, "NW" to -8.60, "NE" to -10.47)
        var anyMap = false
        for (t in set.all) {
            val found = MagVarMap.of(set, t)
            val g = found.value
            if (g == null) {
                appendLine("${t.name}: ${found.reason}")
                continue
            }
            anyMap = true
            val corners = corners(g)
            appendLine("${t.name}: ${g.file}, ${g.xKm.size} x ${g.yKm.size} (x ${g.xKm.first()}..${g.xKm.last()} km, y ${g.yKm.first()}..${g.yKm.last()} km); " +
                corners.entries.joinToString("  ") { (k, v) -> "$k %.2f".format(v) })
            if (t.terrainDir.orEmpty().replace('/', '\\').trimEnd('\\').substringAfterLast('\\').equals("korea", true) && t.name.equals("Korea KTO", true)) {
                ok("Korea KTO's corners are BMS's", koreaCorners.all { (k, v) -> abs((corners[k] ?: 99.0) - v) < 0.01 },
                    koreaCorners.entries.joinToString(" ") { (k, v) -> "$k want %.2f".format(v) })
                // between two rows and two columns the value is the mean of the four
                val y = (g.yKm[0] + g.yKm[1]) / 2 * CampMagVar.FEET_PER_KM
                val x = (g.xKm[0] + g.xKm[1]) / 2 * CampMagVar.FEET_PER_KM
                val mean = (g.deg[0] + g.deg[1] + g.deg[g.xKm.size] + g.deg[g.xKm.size + 1]) / 4.0
                ok("the variation is bilinear between the grid points", abs((g.at(y, x) ?: 99.0) - mean) < 1e-6)
                // what WDP reads: its rows stored one off, so a point takes the row 16 km north of it and the north edge 0
                val wdpNorthEdge = 0.0
                appendLine("WDP at Korea's north edge: %.2f (its row 0 is never filled); here: %.2f".format(wdpNorthEdge, corners["NW"]))
            }
        }
        ok("at least one theater has a variation map", anyMap)

        // ------------------------------------------------------------ 3. the routes, through the PC's own door
        appendLine()
        appendLine("== 3. the routes")
        val why = DevGuard.why(root)
        if (why != null) {
            appendLine("skipped: $why (the routes are only called against a copy)")
            return@buildString
        }
        val korea = set.byName("Korea KTO")
        val koreaSave = korea?.let { set.newestSave(it, Long.MAX_VALUE) }
        if (korea == null || koreaSave == null) {
            appendLine("skipped: the copy has no Korea KTO save to ask about")
            return@buildString
        }
        val before = Bridge.settings.value
        val wasRunning = Bridge.running
        try {
            Bridge.update { it.copy(BmsDirOverride = root.path) }
            Bridge.startForCheck()
            fun call(path: String, q: Map<String, String>): Pair<Int, String> {
                val r = Bridge.handle(ApiRequest("GET", path, q))
                return r.status to r.body.toString(Charsets.UTF_8)
            }
            val (s1, t1) = call("/api/campaign/mapintel", mapOf("theater" to korea.name, "file" to koreaSave.name, "team" to "1"))
            val m = runCatching { Bridge.json.decodeFromString(CampMapIntel.serializer(), t1) }.getOrNull()
            ok("GET /api/campaign/mapintel (${koreaSave.name}, team 1) answers 200 and decodes", s1 == 200 && m != null && m.team == 1, "$s1 ${t1.take(100)}")
            val (s2, t2) = call("/api/campaign/mapintel", mapOf("theater" to korea.name, "file" to "No Such Save.cam"))
            ok("a save that is not there is 404", s2 == 404 && t2.contains("\"error\""), "$s2 ${t2.take(100)}")
            val (s3, _) = call("/api/campaign/mapintel", mapOf("theater" to korea.name, "file" to koreaSave.name, "team" to "9"))
            ok("a team that is not one is 400", s3 == 400, s3)
            val (s4, _) = call("/api/campaign/mapintel", mapOf("theater" to korea.name, "file" to koreaSave.name, "flight" to "x"))
            ok("a flight that is not one is 400", s4 == 400, s4)
            set.saves(korea).firstOrNull { Regex("save\\d+\\.cam", RegexOption.IGNORE_CASE).matches(it.name) }?.let { start ->
                val (s5, t5) = call("/api/campaign/mapintel", mapOf("theater" to korea.name, "file" to start.name))
                ok("a campaign start (${start.name}) is refused, 409", s5 == 409, "$s5 ${t5.take(100)}")
            }
            val (s6, t6) = call("/api/campaign/magvar", mapOf("theater" to korea.name))
            val g = runCatching { Bridge.json.decodeFromString(CampMagVar.serializer(), t6) }.getOrNull()
            ok("GET /api/campaign/magvar (Korea KTO) answers 200 and decodes", s6 == 200 && g != null && g.deg.size == g.xKm.size * g.yKm.size, "$s6 ${t6.take(80)}")
            val (s7, _) = call("/api/campaign/magvar", mapOf("theater" to "No Such Theater"))
            ok("an unknown theater is 400", s7 == 400, s7)
            val (s8, t8) = call("/api/campaign/magvar", mapOf("theater" to korea.appId))
            ok("the app's theater id works too", s8 == 200, "$s8 ${t8.take(60)}")
        } finally {
            Bridge.update { before }
            if (!wasRunning) Bridge.stop()
        }
    }

    /**
     * Every unit kind in every side, still, moving and faded, on the dark map and on the light chart map
     * (`UnitSymbols.kt`), as one picture.
     */
    private fun symbolSheet(png: File) {
        val kinds = listOf(
            "airdefence", "armor", "cavalry", "infantry", "mechanized", "marine", "airmobile", "engineer", "artillery", "towed",
            "rocket", "missile", "supply", "hq", "recon", "other", "carrier", "cruiser", "destroyer", "frigate", "patrol", "tanker", "ship",
        )
        val sides = listOf("friendly", "hostile", "neutral", "unknown")
        val cell = 56f
        val s = 22f
        val cols = sides.size * 3
        val w = (cols * cell * 2 + cell).toInt()
        val h = (kinds.size * cell + cell).toInt()
        org.jetbrains.skia.Surface.makeRasterN32Premul(w, h).use { surface ->
            val canvas = surface.canvas.asComposeCanvas()
            androidx.compose.ui.graphics.drawscope.CanvasDrawScope().draw(
                androidx.compose.ui.unit.Density(1f), androidx.compose.ui.unit.LayoutDirection.Ltr, canvas, androidx.compose.ui.geometry.Size(w.toFloat(), h.toFloat()),
            ) {
                for (light in listOf(false, true)) {
                    val x0 = if (light) cols * cell + cell / 2 else 0f
                    drawRect(if (light) androidx.compose.ui.graphics.Color(0xFFEDEAE0) else androidx.compose.ui.graphics.Color(0xFF1B2229),
                        androidx.compose.ui.geometry.Offset(x0, 0f), androidx.compose.ui.geometry.Size(cols * cell + cell / 2, h.toFloat()))
                    com.bmscompanion.app.ui.theme.inMapInks(light) {
                        kinds.forEachIndexed { r, kind ->
                            sides.forEachIndexed { c, side ->
                                for (v in 0..2) {
                                    val at = androidx.compose.ui.geometry.Offset(x0 + cell * (c * 3 + v) + cell * 0.75f, cell * r + cell * 0.8f)
                                    drawUnitSymbol(kind, side, at, s, moving = v == 1, dim = v == 2)
                                }
                            }
                        }
                    }
                }
            }
            png.absoluteFile.parentFile?.mkdirs()
            png.writeBytes(surface.makeImageSnapshot().encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.bytes)
        }
    }

    private fun corners(g: CampMagVar): Map<String, Double> {
        val n = g.yKm.last() * CampMagVar.FEET_PER_KM
        val e = g.xKm.last() * CampMagVar.FEET_PER_KM
        val s = g.yKm.first() * CampMagVar.FEET_PER_KM
        val w = g.xKm.first() * CampMagVar.FEET_PER_KM
        return linkedMapOf(
            "SW" to (g.at(s, w) ?: Double.NaN), "SE" to (g.at(s, e) ?: Double.NaN),
            "NW" to (g.at(n, w) ?: Double.NaN), "NE" to (g.at(n, e) ?: Double.NaN),
        )
    }

    private fun StringBuilder.report(intel: CampMapIntel, ok: (String, Boolean, Any?) -> Unit) {
        appendLine("team ${intel.team} (controller ${intel.controller}); teams: " + intel.teams.joinToString(", ") { "${it.n} ${it.name} ${it.side} #%06X".format(it.colour) })
        intel.notes.forEach { appendLine("note: $it") }
        // units per kind x side
        val sides = listOf("friendly", "hostile", "neutral", "unknown")
        appendLine("units ${intel.units.size}: kind x side (friendly / hostile / neutral / unknown)")
        intel.units.groupBy { it.domain + " " + it.kind }.toSortedMap().forEach { (k, us) ->
            appendLine("  %-22s %s".format(k, sides.joinToString(" / ") { s -> "${us.count { it.side == s }}" }))
        }
        val hostile = intel.units.filter { it.side == "hostile" }
        val alive = hostile.filter { !it.dead }
        appendLine("hostile ${hostile.size}: dead ${hostile.count { it.dead }}; of the rest spotted ${alive.count { it.spotted }}, recent ${alive.count { it.recent }}, " +
            "jstar ${alive.count { it.jstar }}, seen any way ${alive.count { it.spotted || it.recent || it.jstar }}, moving ${alive.count { it.moving }}")
        appendLine("radar (all units): " + intel.units.groupingBy { it.radar }.eachCount().toSortedMap().entries.joinToString(", ") { "${it.key} ${it.value}" })
        val ad = intel.units.filter { it.kind == "airdefence" }
        appendLine("air defence radar: " + ad.groupingBy { it.radar }.eachCount().toSortedMap().entries.joinToString(", ") { "${it.key} ${it.value}" })
        ok("every unit has a kind and a system", intel.units.all { it.kind.isNotEmpty() && it.system.isNotEmpty() }, intel.units.firstOrNull { it.system.isEmpty() }?.let { "e.g. ${it.id} ${it.name}" })
        ok("units lie inside the theater (positive feet)", intel.units.all { it.x > 0 && it.y > 0 }, null)
        // SAM systems per side
        fun systems(side: String) = intel.units.filter { (it.kind == "airdefence" || it.shorad != null) && it.side == side && !it.dead }
            .groupBy { it.shorad ?: it.system }.entries.sortedByDescending { it.value.size }
            .joinToString(", ") { (s, us) -> "$s ${us.size}" + (us.count { it.spotted }.takeIf { it != us.size }?.let { "/$it" } ?: "") }
        appendLine("air-defence systems, hostile (count/spotted): ${systems("hostile")}")
        appendLine("air-defence systems, friendly: ${systems("friendly")}")
        val shorad = intel.units.filter { it.shorad != null }
        appendLine("units carrying a SHORAD: ${shorad.size}" + shorad.groupBy { "${it.kind}:${it.shorad}" }.entries.take(8).joinToString(", ", " (", ")") { "${it.key} ${it.value.size}" })
        // search radars
        val radars = intel.radars
        appendLine("search radars ${radars.size}: working ${radars.count { it.working }}; by side " +
            sides.joinToString(", ") { s -> "$s ${radars.count { it.side == s }}/${radars.count { it.side == s && it.working }}" } +
            "; by type " + radars.groupingBy { it.type }.eachCount().entries.sortedByDescending { it.value }.joinToString(", ") { "${it.key} ${it.value}" })
        radars.filter { it.intact < it.radars }.take(5).forEach { appendLine("  damaged: ${it.name} (${it.type}) ${it.intact}/${it.radars} intact, ${it.side}") }
        // fields
        val fields = intel.fields
        val changed = fields.filter { it.owner != it.startOwner }
        appendLine("fields ${fields.size} (airbases ${fields.count { it.type == "Airbase" }}, airstrips ${fields.count { it.type == "Airstrip" }}), " +
            "matched to an app airport ${fields.count { it.airport != null }}, changed hands ${changed.size}" +
            changed.take(6).joinToString(", ", if (changed.isEmpty()) "" else ": ") { "${it.name} ${it.startOwner}->${it.owner}" })
        val sq = fields.flatMap { it.squadrons }
        appendLine("squadrons at fields ${sq.size}; with a count ${sq.count { it.count != null }}; counts " +
            sq.mapNotNull { it.count }.groupingBy { it }.eachCount().toSortedMap().entries.joinToString(" ") { "${it.key}:${it.value}" })
        fields.firstOrNull { f -> f.squadrons.any { it.side == "hostile" } }?.let { f ->
            appendLine("  e.g. ${f.name} (${f.side}): " + f.squadrons.joinToString(", ") { "${it.aircraft} ${it.count ?: "?"}" + (it.name?.let { n -> " \"$n\"" } ?: "") })
        }
        // jstars
        appendLine("jstars ${intel.jstars.size}: " + intel.jstars.joinToString(", ") {
            "${it.callsign} ${CampaignArchive.clock(it.from)}-${CampaignArchive.clock(it.to)}${if (it.active) " ACTIVE" else ""}"
        }.ifEmpty { "none" })
        val size = Bridge.json.encodeToString(CampMapIntel.serializer(), intel).length
        appendLine("answer: %,d characters of JSON".format(size))
        ok("the answer decodes again", runCatching { Bridge.json.decodeFromString(CampMapIntel.serializer(), Bridge.json.encodeToString(CampMapIntel.serializer(), intel)) == intel }.getOrDefault(false), null)
    }

    /** The roster evidence: air-defence radar slots holding 2 or 3 vehicles, and what WDP's low-bit read makes of them. */
    fun rosterEvidence(set: Theaters.TheaterSet, t: Theaters.Theater, file: File): String {
        val names = CampaignArchive.names(set, t)
        val save = CampaignArchive.cached(file, names)
        val ucds = MapIntel.ucdTable(names)
        val counts = IntArray(4)
        var wdpDead = 0
        var over = 0
        var slots = 0
        for (u in save.units.filter { it is CampaignArchive.Battalion || it is CampaignArchive.TaskForce }) {
            val ucd = names.ct(u.type)?.let { ucds[it.entityIdx] } ?: continue
            for (i in 0 until 16) {
                val n = MapIntel.slotCount(u.core.roster, i)
                if (n > 0) slots++
                if (n > ucd.counts[i]) over++
            }
        }
        for (u in save.units.filterIsInstance<CampaignArchive.Battalion>()) {
            val ct = names.ct(u.type) ?: continue
            if (ct.subType != 1) continue
            val rv = ucds[ct.entityIdx]?.radarVehicle ?: continue
            if (rv !in 0 until 16) continue
            val n = MapIntel.slotCount(u.core.roster, rv)
            counts[n]++
            if (n == 2) wdpDead++
        }
        return "air-defence radar slots holding 0/1/2/3 vehicles: ${counts.joinToString("/")}; WDP's low-bit read calls $wdpDead of them destroyed while they have two\n" +
            (if (over == 0) "ok   " else "FAIL ") + "no roster slot holds more vehicles than the unit table gives it  — $over of $slots occupied slots over"
    }
}
