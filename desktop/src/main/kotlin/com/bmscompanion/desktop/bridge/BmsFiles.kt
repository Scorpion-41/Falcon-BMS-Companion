package com.bmscompanion.desktop.bridge

import com.bmscompanion.app.data.mission.BriefOverview
import com.bmscompanion.app.data.mission.BriefSteerpoint
import com.bmscompanion.app.data.mission.Briefing
import com.bmscompanion.app.data.mission.CommEntry
import com.bmscompanion.app.data.mission.Dtc
import com.bmscompanion.app.data.mission.DtcPoint
import com.bmscompanion.app.data.mission.DtcPpt
import com.bmscompanion.app.data.mission.OrdnanceAircraft
import com.bmscompanion.app.data.mission.OrdnanceFlight
import com.bmscompanion.app.data.mission.PackageFlight
import com.bmscompanion.app.data.mission.Preset
import com.bmscompanion.app.data.mission.RawSection
import com.bmscompanion.app.data.mission.RosterFlight
import com.bmscompanion.app.data.mission.Store
import com.bmscompanion.app.data.mission.SupportEntry
import com.bmscompanion.app.data.mission.TextBlock
import com.bmscompanion.app.data.mission.WeatherRow
import com.bmscompanion.app.data.mission.WeatherTable
import java.io.File
import java.util.Locale
import kotlin.math.abs

/**
 * Parser for <BMS>\User\Briefings\briefing.txt (the "Print" button output, text mode).
 * Every section is also exported raw (sections) so the app can still show data if a future BMS changes a layout.
 * UPDATING: compare a new briefing.txt with resources/bridge/demo_briefing.txt; section titles are matched in [titles].
 */
object BriefingParser {
    private val titles = listOf(
        "Mission Overview", "Situation", "Pilot Roster", "Package Elements", "Threat Analysis", "Steerpoints",
        "Comm Ladder", "Iff", "Link 16", "Ordnance", "Weather", "Support", "Rules of Engagement", "Emergency Procedures",
    )

    fun parse(fullText: String): Briefing {
        // With "Append new briefings" the file holds several records: use the last one.
        val recIdx = fullText.lastIndexOf("BRIEFING RECORD")
        val text = if (recIdx > 0) fullText.substring(recIdx) else fullText
        val lines = text.replace("\r", "").split('\n')
        val generated = Regex("BRIEFING RECORD generated at (.+?)\\.?\\s*$", RegexOption.MULTILINE).find(text)?.groupValues?.get(1)?.trim()

        // Sections: a header is an un-indented line starting with a known title.
        val sections = ArrayList<Pair<String, MutableList<String>>>()
        var cur: MutableList<String>? = null
        for (line in lines) {
            if (line.startsWith("END_OF_BRIEFING")) break
            val title = if (line.isNotEmpty() && !line[0].isWhitespace()) titles.firstOrNull { line.startsWith(it, ignoreCase = true) } else null
            if (title != null) {
                cur = ArrayList()
                sections += title to cur
                continue
            }
            cur?.add(line)
        }

        var b = Briefing(generated = generated)
        val raw = ArrayList<RawSection>()
        for ((title, body) in sections) {
            val rows = body.map(::cells).filter { it.isNotEmpty() }
            raw += RawSection(title, rows)
            try {
                b = when (title) {
                    "Mission Overview" -> b.copy(overview = overview(rows))
                    "Situation" -> b.copy(situation = rows.joinToString("\n\n") { it.joinToString(" ") })
                    "Pilot Roster" -> b.copy(roster = rows.drop(1).map { RosterFlight(it[0], it.drop(1)) })
                    "Package Elements" -> b.copy(`package` = packageFlights(rows))
                    "Threat Analysis" -> b.copy(threats = blocks(rows))
                    "Steerpoints" -> b.copy(steerpoints = steerpoints(rows))
                    "Comm Ladder" -> b.copy(comms = comms(body))
                    "Ordnance" -> b.copy(ordnance = ordnance(body))
                    "Weather" -> b.copy(weather = weather(rows))
                    "Support" -> b.copy(support = support(rows))
                    "Rules of Engagement" -> b.copy(roe = rows.map { it.joinToString(" ") })
                    "Emergency Procedures" -> blocks(rows).let { e ->
                        b.copy(emergency = e, alternate = e.firstOrNull { it.title?.startsWith("Alternate", ignoreCase = true) == true }?.lines?.firstOrNull())
                    }
                    else -> b
                }
            } catch (e: Exception) {
                BridgeLog.warn("Briefing section '$title' parse failed: ${e.message}")
            }
        }
        return b.copy(sections = raw)
    }

    /** Tab separated cells, trimmed, empty cells removed. */
    private fun cells(line: String) = line.split('\t').map { it.trim() }.filter { it.isNotEmpty() }

    private fun overview(rows: List<List<String>>): BriefOverview {
        var o = BriefOverview()
        for (r in rows) {
            val value = r.getOrNull(1)
            o = when (r[0].trimEnd(':').trim()) {
                "Package #" -> Regex("^(\\d+)\\s*\\((.+)\\)").find(value.orEmpty())
                    ?.let { o.copy(packageId = it.groupValues[1], packageType = it.groupValues[2].trim()) } ?: o.copy(packageId = value)
                "Pkg-Mission" -> o.copy(packageMission = value)
                "Target Area" -> o.copy(targetArea = value?.trimEnd('.'))
                "Time on Target" -> o.copy(tot = value)
                "Sunrise" -> o.copy(sunrise = value)
                "Sunset" -> o.copy(sunset = value)
                else -> if (o.flight == null && r.size == 1) {
                    Regex("^(\\S+)\\s*\\((.+)\\)").find(r[0])?.let { o.copy(flight = it.groupValues[1], mission = it.groupValues[2].trim()) } ?: o
                } else o
            }
        }
        return o
    }

    private fun packageFlights(rows: List<List<String>>): List<PackageFlight> {
        val list = ArrayList<PackageFlight>()
        for (r in rows) {
            if (r[0].startsWith("Callsign:")) continue
            if (r[0].startsWith("T/O:")) {
                var last = list.lastOrNull() ?: continue
                for (c in r) last = when {
                    c.startsWith("T/O:") -> last.copy(takeoff = c.substring(4).trim())
                    c.startsWith("Push:") -> last.copy(push = c.substring(5).trim())
                    c.startsWith("Tgt:") -> last.copy(target = c.substring(4).trim())
                    c.startsWith("IFF:") -> last.copy(iff = c.substring(4).trim())
                    else -> last
                }
                list[list.size - 1] = last
                continue
            }
            if (r.size < 3) continue
            val id = r[1]
            var f = PackageFlight(
                callsign = r[0], primary = id.contains("(x"), flightId = Regex("\\d+").find(id)?.value ?: "",
                role = r.getOrNull(2), aircraft = r.getOrNull(3), task = r.getOrNull(4),
            )
            Regex("^(\\d+)\\s+(.+)$").find(f.aircraft.orEmpty())?.let { f = f.copy(count = it.groupValues[1].toInt(), aircraft = it.groupValues[2].trim()) }
            list += f
        }
        return list
    }

    private fun blocks(rows: List<List<String>>): List<TextBlock> {
        val list = ArrayList<TextBlock>()
        var title: String? = null
        var lines: MutableList<String>? = null
        fun flush() { if (lines != null) list += TextBlock(title, lines!!.toList()) }
        for (r in rows) {
            val text = r.joinToString(" ")
            if (text.endsWith(':') && text.length < 60 && !text.startsWith("--")) {
                flush()
                title = text.trimEnd(':'); lines = ArrayList()
                continue
            }
            if (lines == null) { title = null; lines = ArrayList() }
            lines!!.add(if (text.startsWith("-- ")) text.substring(3) else text)
        }
        flush()
        return list
    }

    private fun steerpoints(rows: List<List<String>>) = rows.mapNotNull { r ->
        val n = r[0].toIntOrNull() ?: return@mapNotNull null
        fun at(i: Int) = r.getOrNull(i)?.takeUnless { it == "--" }
        BriefSteerpoint(n, at(1), at(2), at(3), at(4), at(5), at(6), at(7), at(8), at(9))
    }

    private fun comms(body: List<String>): List<CommEntry> {
        // keep the blank-line groups (flight, common, AWACS, departure, arrival, alternate)
        var group = 0
        val list = ArrayList<CommEntry>()
        for (line in body) {
            val r = cells(line)
            if (r.isEmpty()) { group++; continue }
            if (r[0].startsWith("Agency:") || r.size < 3) continue
            val (uhf, uhfCh) = freq(r.getOrNull(2))
            val (vhf, vhfCh) = freq(r.getOrNull(3))
            list += CommEntry(r[0].trimEnd(':'), r[1].takeUnless { it == "None" }, uhf, uhfCh, vhf, vhfCh, r.getOrNull(4), group.toString())
        }
        return list
    }

    private fun freq(s: String?): Pair<String?, Int?> {
        if (s == null || s == "--") return null to null
        val m = Regex("([\\d.]+)\\s*MHz\\s*(?:\\[(\\d+)])?").find(s) ?: return s to null
        return m.groupValues[1] to m.groupValues[2].toIntOrNull()
    }

    private fun ordnance(body: List<String>): List<OrdnanceFlight> {
        val flights = ArrayList<Pair<String, List<MutableList<Store>>>>()
        val names = ArrayList<List<String>>()
        for (line in body) {
            val r = cells(line)
            if (r.isEmpty() || r[0].startsWith("Callsign:")) continue
            if (r.size >= 2 && r[1].startsWith("--")) {
                // "<flight>  -- Name1 --  -- Name2 --"
                val aircraft = r.drop(1).map { it.trim('-', ' ') }
                names += aircraft
                flights += r[0] to aircraft.map { ArrayList() }
                continue
            }
            val stores = flights.lastOrNull()?.second ?: continue
            // store columns line up with the aircraft columns
            r.forEachIndexed { i, cell ->
                if (i >= stores.size) return@forEachIndexed
                val m = Regex("^(\\d+)x\\s+(.+)$").find(cell)
                stores[i] += if (m != null) Store(m.groupValues[1].toInt(), m.groupValues[2].trim()) else Store(1, cell)
            }
        }
        return flights.mapIndexed { k, (flight, stores) -> OrdnanceFlight(flight, names[k].mapIndexed { i, n -> OrdnanceAircraft(n, stores[i]) }) }
    }

    private fun weather(rows: List<List<String>>): WeatherTable {
        var columns = emptyList<String>()
        val list = ArrayList<WeatherRow>()
        for (r in rows) {
            if (r[0].startsWith("Conditions")) { columns = r.drop(1).map { it.trimEnd(':') }; continue }
            list += WeatherRow(r[0].trimEnd(':'), r.drop(1))
        }
        return WeatherTable(columns, list)
    }

    private fun support(rows: List<List<String>>) = rows.mapNotNull { r ->
        if (r[0].startsWith("Callsign:")) return@mapNotNull null
        if (r.size == 1 && r[0].startsWith("No ", ignoreCase = true)) return@mapNotNull null // "No support flights available."
        val m = Regex("^(\\S+)\\s*\\((.+)\\):?$").find(r[0])
        SupportEntry(m?.groupValues?.get(1) ?: r[0].trimEnd(':'), m?.groupValues?.get(2), r.getOrNull(1), r.getOrNull(2))
    }
}

/** Parser for the pilot DTC file <BMS>\User\Config\<callsign>.ini (written by "Save" in the DTC UI). */
object DtcParser {
    fun parse(file: File): Dtc {
        val sections = HashMap<String, HashMap<String, String>>()
        var cur: HashMap<String, String>? = null
        for (raw in file.readLines(Charsets.UTF_8)) {
            val line = raw.trim()
            if (line.startsWith('[') && line.endsWith(']')) {
                cur = HashMap()
                sections[line.substring(1, line.length - 1).lowercase(Locale.ROOT)] = cur
                continue
            }
            val eq = line.indexOf('=')
            if (cur == null || eq <= 0) continue
            cur[line.substring(0, eq).trim().lowercase(Locale.ROOT)] = line.substring(eq + 1).trim()
        }

        val steer = ArrayList<DtcPoint>()
        val weapons = ArrayList<DtcPoint>()
        val ppts = ArrayList<DtcPpt>()
        val lines = ArrayList<DtcPoint>()
        sections["stpt"]?.forEach { (k, v) ->
            val us = k.lastIndexOf('_')
            val idx = if (us < 0) null else k.substring(us + 1).toIntOrNull()
            if (idx == null) return@forEach
            val f = v.split(',').map { it.trim() }
            fun n(i: Int) = f.getOrNull(i)?.toDoubleOrNull() ?: 0.0
            if (n(0) == 0.0 && n(1) == 0.0) return@forEach
            when (k.substring(0, us)) {
                "target", "wpntarget" -> {
                    val action = n(3).toInt()
                    val p = DtcPoint(
                        n = idx + 1, x = n(0), y = n(1), altFt = abs(n(2)), action = action, isTarget = action == -1,
                        name = if (f.size > 4 && f[4].isNotEmpty() && f[4] != "Not set") f.drop(4).joinToString(", ").trim() else null,
                    )
                    if (k.startsWith("target")) steer += p else weapons += p
                }
                "ppt" -> {
                    val r = n(3)
                    // PPTs are steerpoints 56-70 in the F-16
                    ppts += DtcPpt(idx + 56, n(0), n(1), abs(n(2)), if (r > 500) r / 6076.12 else r, f.getOrNull(4)?.takeIf { it.isNotEmpty() })
                }
                "linestpt" -> lines += DtcPoint(n = idx, x = n(0), y = n(1), altFt = abs(n(2)))
            }
        }

        val uhf = ArrayList<Preset>()
        val vhf = ArrayList<Preset>()
        sections["radio"]?.let { radio ->
            fun comment(c: String?) = c?.takeUnless { it == "(open)" }
            for (ch in 1..20) {
                radio["uhf_$ch"]?.toIntOrNull()?.takeIf { it > 0 }?.let { uhf += Preset(ch, String.format(Locale.ROOT, "%.3f", it / 1000.0), comment(radio["uhf_comment_$ch"])) }
                radio["vhf_$ch"]?.toIntOrNull()?.takeIf { it > 0 }?.let { vhf += Preset(ch, String.format(Locale.ROOT, "%.3f", it / 1000.0), comment(radio["vhf_comment_$ch"])) }
            }
        }
        val iff = LinkedHashMap<String, String>()
        sections["iff"]?.let { s -> listOf("Mode1 Code", "Mode2 Code", "Mode3A Code", "Mode4 Key").forEach { key -> s[key.lowercase(Locale.ROOT)]?.let { iff[key] = it } } }

        return Dtc(
            modified = file.lastModified(),
            steerpoints = steer.sortedBy { it.n }, weaponTargets = weapons.sortedBy { it.n }, ppts = ppts.sortedBy { it.n }, lines = lines,
            uhf = uhf, vhf = vhf, iff = iff,
        )
    }
}
