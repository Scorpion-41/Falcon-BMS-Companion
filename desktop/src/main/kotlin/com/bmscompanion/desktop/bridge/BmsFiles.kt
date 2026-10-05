package com.bmscompanion.desktop.bridge

import com.bmscompanion.app.data.mission.BriefOverview
import com.bmscompanion.app.data.mission.BriefSteerpoint
import com.bmscompanion.app.data.mission.Briefing
import com.bmscompanion.app.data.mission.CommEntry
import com.bmscompanion.app.data.mission.Dtc
import com.bmscompanion.app.data.mission.DtcComm
import com.bmscompanion.app.data.mission.DtcPoint
import com.bmscompanion.app.data.mission.DtcPpt
import com.bmscompanion.app.data.mission.NavOffset
import com.bmscompanion.app.data.mission.NavOffsets
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
import kotlin.math.roundToInt

/**
 * Parser for <BMS>\User\Briefings\briefing.txt (the "Print" button output, text mode).
 * Every section is also exported raw (sections) so the app can still show data if a future BMS changes a layout.
 * UPDATING: compare a new briefing.txt with resources/bridge/sample_briefing.txt; section titles are matched in [titles].
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

/**
 * Parser for the DTC format: the pilot's cartridge `<BMS>\User\Config\<callsign>.ini` (written by "Save" in BMS's DTC
 * window, by WDP and by the Planner), the mission file BMS keeps beside every save (`<campaign folder>\<save>.ini`,
 * the same `[STPT]` layout), and the cartridge text the Planner sends with Send to Mission ([parseText]).
 *
 * What each key means (BMS User Manual 4.38.1 p.56; WDP's `clsSaveDTC`):
 * - `[STPT] target_0…23` are STPT 1-24 and `target_80…98` STPT 81-99 (`x` north, `y` east, `z` negative, action,
 *   name). Both banks are in [Dtc.steerpoints] (n = index + 1); the 81-99 bank is also [Dtc.open].
 * - `wpntarget_0…99` are the weapon targets, `ppt_0…14` the pre-planned threats (STPT 56-70: `x, y, z, range in feet,
 *   code`, the code being the key of the theater's `Ppt.ini`, see [PptTable]), `lineSTPT_0…23` the four lines of six
 *   points (STPT 31-54: line 1 is points 0-5, line 2 6-11, line 3 12-17, line 4 18-23). A line point keeps its old
 *   [DtcPoint.n], the `lineSTPT` index (0-23); [DtcPoint.line] says which line it is on.
 * - `[NAV OFFSETS]`, `[COMMS]` TACAN/ILS, `[Laser]` and `[ICP]` are written by WDP and the Planner only; BMS 4.38.1's own
 *   cartridge has none of them (whether BMS reads them is not established).
 *
 * A point at 0,0 is an empty slot and is skipped. Nothing here throws on a strange value: a number that does not read
 * (or reads as NaN or infinity, which the JSON answer could not carry) counts as 0 or as absent.
 */
object DtcParser {
    /** A PPT whose range is under this many feet is a marker (AWACS, tanker, a friendly: `Ppt.ini` gives them 0.1). */
    const val MARKER_FT = 100.0
    const val FT_PER_NM = 6076.12

    /** [parseText] over the file, with the file's time as [Dtc.modified]. Read as UTF-8, as it always has been. */
    fun parse(file: File): Dtc = parseText(file.readText(Charsets.UTF_8)).copy(modified = file.lastModified())

    /** One DTC file's text (a cartridge, a mission file, or what the Planner sends), with [Dtc.modified] 0. */
    fun parseText(text: String): Dtc {
        val sections = HashMap<String, HashMap<String, String>>()
        var cur: HashMap<String, String>? = null
        for (raw in text.removePrefix("﻿").lines()) {
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
            fun n(i: Int) = num(f.getOrNull(i))
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
                    val code = f.getOrNull(4)?.takeIf { it.isNotEmpty() }
                    val marker = r < MARKER_FT
                    // PPTs are steerpoints 56-70 in the F-16. The range is in feet; a marker has no ring, so no range in nm
                    ppts += DtcPpt(
                        n = idx + 56, x = n(0), y = n(1), altFt = abs(n(2)), rangeNm = if (marker) 0.0 else r / FT_PER_NM, name = code,
                        code = code, rangeFt = r, marker = marker,
                    )
                }
                "linestpt" -> lines += DtcPoint(n = idx, x = n(0), y = n(1), altFt = abs(n(2)), line = if (idx in 0..23) idx / 6 + 1 else null)
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

        val sortedSteer = steer.sortedBy { it.n }
        val laser = sections["laser"]
        val icp = sections["icp"]
        return Dtc(
            steerpoints = sortedSteer, weaponTargets = weapons.sortedBy { it.n }, ppts = ppts.sortedBy { it.n }, lines = lines.sortedBy { it.n },
            uhf = uhf, vhf = vhf, iff = iff,
            open = sortedSteer.filter { it.n in 81..99 },
            navOffsets = sections["nav offsets"]?.let(::navOffsets),
            comm = sections["comms"]?.let(::comm),
            laserSt = laser?.let { int(it["laserst"]) },
            laserTgp = laser?.let { int(it["lasertgp"]) },
            laserLst = laser?.let { int(it["laserlst"]) },
            bingoLbs = icp?.let { int(it["bingo_fuel"]) },
            alowFt = icp?.let { int(it["alow agl"]) },
            mslFloorFt = icp?.let { int(it["alow msl"]) },
            ewsNames = sections["ews"]?.let(::ewsNames).orEmpty(),
        )
    }

    /**
     * `[NAV OFFSETS]` (WDP's `SaveCallsign_NavOffsets`; Dash-34 p.423-425). `Modesel` is `none`, `vip` or `vrp` (a
     * number, as WDP wrote it before BMS 4.36 build 25688, is one more than those: 1 none, 2 vip, 3 vrp). `VIP`,
     * `VIPPUP`, `VRP` and `VRPPUP` are `stpt,bearing,range,elevation`; `OA1-<stpt>` and `OA2-<stpt>` are
     * `bearing,range,elevation`. Bearings are true degrees, range and elevation feet. A reference point on steerpoint 0
     * is one nobody set (there is no steerpoint 0), and an offset that is all zero is no offset: both are left out.
     */
    private fun navOffsets(s: Map<String, String>): NavOffsets? {
        if (s.isEmpty()) return null
        val sel = s["modesel"]?.trim()?.lowercase(Locale.ROOT).orEmpty()
        val mode = when {
            sel == "vip" || sel == "vrp" || sel == "none" -> sel
            else -> when (sel.toDoubleOrNull()?.takeIf { it.isFinite() }?.roundToInt()) { 2 -> "vip"; 3 -> "vrp"; else -> "none" }
        }
        fun point(key: String): NavOffset? {
            val f = s[key.lowercase(Locale.ROOT)]?.split(',')?.map { it.trim() } ?: return null
            val stpt = num(f.getOrNull(0)).roundToInt()
            if (stpt < 1) return null
            return NavOffset(key, stpt, num(f.getOrNull(1)), num(f.getOrNull(2)), num(f.getOrNull(3)))
        }
        val oaKey = Regex("^oa([12])-(\\d+)$")
        val oa = s.mapNotNull { (k, v) ->
            val m = oaKey.find(k) ?: return@mapNotNull null
            val stpt = m.groupValues[2].toIntOrNull()?.takeIf { it >= 1 } ?: return@mapNotNull null
            val f = v.split(',').map { it.trim() }
            val o = NavOffset("OA${m.groupValues[1]}-$stpt", stpt, num(f.getOrNull(0)), num(f.getOrNull(1)), num(f.getOrNull(2)))
            o.takeUnless { it.bearing == 0.0 && it.rangeFt == 0.0 && it.elevFt == 0.0 }
        }.sortedWith(compareBy({ it.stpt }, { it.key }))
        return NavOffsets(mode = mode, vip = point("VIP"), vipPup = point("VIPPUP"), vrp = point("VRP"), vrpPup = point("VRPPUP"), oa = oa)
    }

    /**
     * `[COMMS]`: `Comm1`/`Comm2`, the presets BMS tunes after a DTC load (UM p.62), and what WDP adds there: `TACAN
     * Channel`, `TACAN Band` (0 X, 1 Y), `TACAN Domain` (0 T/R, 1 A/A TR), `ILS Frequency` (hundredths of a MHz, 10930
     * = 109.30) and `ILS CRS`. The TACAN reads as [com.bmscompanion.app.data.mission.Live.tacan] does ("94X", "12Y A/A").
     */
    private fun comm(s: Map<String, String>): DtcComm {
        val ch = int(s["tacan channel"])?.takeIf { it > 0 }
        val tacan = ch?.let { "$it${if (int(s["tacan band"]) == 1) "Y" else "X"}${if (int(s["tacan domain"]) == 1) " A/A" else ""}" }
        val ilsRaw = int(s["ils frequency"])?.takeIf { it > 0 }
        val mhz = when {
            ilsRaw == null -> null
            ilsRaw >= 100_000 -> ilsRaw / 1000.0   // kHz, as the [Radio] presets are written
            ilsRaw >= 10_000 -> ilsRaw / 100.0     // hundredths of a MHz, as WDP and the Planner write it
            else -> null
        }
        return DtcComm(
            comm1 = int(s["comm1"]), comm2 = int(s["comm2"]), tacan = tacan,
            ils = mhz?.let { String.format(Locale.ROOT, "%.2f", it) }, ilsCrs = int(s["ils crs"]),
        )
    }

    /** `[EWS] PGM 0…5 Comment`: the six countermeasure programs' names, in order; empty when the file names none. */
    private fun ewsNames(s: Map<String, String>): List<String> {
        val names = (0..5).map { s["pgm $it comment"]?.trim() }
        return if (names.all { it == null }) emptyList() else names.map { it.orEmpty() }
    }

    /** A number as the file writes it; 0 when it is missing, does not read, or is not finite. */
    private fun num(s: String?): Double = s?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() } ?: 0.0

    /** A whole number ("1688", "1500.000000"); null when it is missing, does not read, or is out of range. */
    private fun int(s: String?): Int? {
        val t = s?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        t.toIntOrNull()?.let { return it }
        val d = t.toDoubleOrNull()?.takeIf { it.isFinite() && it >= Int.MIN_VALUE && it <= Int.MAX_VALUE } ?: return null
        return d.roundToInt()
    }
}

/**
 * A theater's PPT types, `<campaign folder>\Ppt.ini`: one a line, `code range-in-feet name` (`SA3 72913.388671875
 * SA-3`, `10 303805.786132813 SA-10`, `AWC 0.1 AWACS`). A cartridge's `ppt_n` names its type by the code; this turns the
 * code into the name a pilot knows. Types with a range under [DtcParser.MARKER_FT] are markers, not threats. A code may
 * appear twice (Korea's `PUP` is both Push Point and Pop-Up Point); the first one is BMS's.
 */
object PptTable {
    data class Type(val code: String, val rangeFt: Double, val name: String) {
        val marker: Boolean get() = rangeFt < DtcParser.MARKER_FT
    }

    /** The file's rows in order; lines that do not read (fewer than three fields, no number) are skipped. */
    fun parse(text: String?): List<Type> = text.orEmpty().lines().mapNotNull { raw ->
        val parts = raw.trim().split(Regex("\\s+"))
        if (parts.size < 3) return@mapNotNull null
        val range = parts[1].toDoubleOrNull()?.takeIf { it.isFinite() } ?: return@mapNotNull null
        Type(parts[0], range, parts.drop(2).joinToString(" "))
    }

    /** The type [code] names: exactly first, then without case. Null for a code the table does not have. */
    fun resolve(table: List<Type>, code: String?): Type? {
        val c = code?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return table.firstOrNull { it.code == c } ?: table.firstOrNull { it.code.equals(c, ignoreCase = true) }
    }

    /** `Ppt.ini` in [campaignDir] (any case), or null. Read only. */
    fun read(campaignDir: File?): List<Type>? = try {
        campaignDir?.listFiles { f -> f.isFile && f.name.equals("ppt.ini", true) }?.firstOrNull()?.let { parse(it.readText(Charsets.ISO_8859_1)) }
    } catch (_: Exception) {
        null
    }
}

/**
 * A flight plan as a DTC file holds it, set against the printed briefing. BMS's mission file beside a save has the
 * route in `target_0…` (actions 1 TakeOff, 0 Nav, 12 CAP, 4 Refuel, 7 Land…) and the precision targets further up with
 * action -1; the briefing's Steerpoints table prints each route point's action word ("Takeoff", "--", "CAP") from the
 * theater's `Strings.txt` line 350 + action. That is how a mission file is told to be the briefed flight's (R3 A11).
 */
object DtcRoute {
    /** BMS's words for the actions, `Strings.txt` 350-377 of the stock theaters: the fallback when no Strings.txt is read. */
    private val WORDS = listOf(
        "--", "Takeoff", "Push", "Split", "Refuel", "Rearm", "Pickup", "Land", "Holding Pt", "Contact", "Escort", "Sweep", "CAP",
        "Intercept", "Grnd Attack", "Surf Attack", "S&D", "Strike", "Bomb", "SEAD", "ELINT", "Recon", "Rescue", "ASW", "Fuel",
        "Air Drop", "Jamming",
    )

    /** The route: STPT 1-24 that are not precision targets (action -1), in number order. Targets are never on the line. */
    fun points(dtc: Dtc): List<DtcPoint> = dtc.steerpoints.filter { it.n in 1..24 && !it.isTarget && it.action >= 0 }

    /** The word the briefing prints for [action]: [strings] (a theater's Strings.txt) line 350 + action, else BMS's own. */
    fun word(action: Int, strings: Map<Int, String> = emptyMap()): String? =
        if (action < 0) null else strings[350 + action] ?: WORDS.getOrNull(action)

    /**
     * The word BMS's briefing prints in a steerpoint row's Desc column for a campaign waypoint: its action's, or —
     * when the action is Nav (0) — its **route action**'s (the en-route task, e.g. SEAD on the legs either side of a
     * SEAD point). Seen on a real Korea save: Cajun1's waypoints 5 and 7 are action 0 with route action 19 and print
     * "SEAD", waypoint 6 is action 19; a waypoint with route action 0 prints "--". A mission file `.ini` writes the
     * action alone, which is why [mismatch] takes the save's route action as well.
     */
    fun printedWord(action: Int, routeAction: Int, strings: Map<Int, String> = emptyMap()): String? =
        word(if (action == 0 && routeAction > 0) routeAction else action, strings)

    /**
     * Null when [route] and the briefing's [rows] agree: as many points as rows, in the same order, and each row's
     * description is the word for that point's action — or [alsoWord] of that point (the save's waypoint's word by
     * [printedWord], which a mission file cannot carry). Otherwise the first difference, as a sentence.
     */
    fun mismatch(
        route: List<DtcPoint>, rows: List<BriefSteerpoint>, strings: Map<Int, String> = emptyMap(),
        alsoWord: (DtcPoint) -> String? = { null },
    ): String? {
        if (route.size != rows.size) return "the file has ${route.size} route points and the briefing ${rows.size} steerpoints"
        route.zip(rows).forEachIndexed { i, (p, r) ->
            if (p.n != r.n) return "route point ${i + 1} is STPT ${p.n} in the file and ${r.n} in the briefing"
            val want = word(p.action, strings)?.trim()
            val got = (r.desc ?: "--").trim()
            val also = alsoWord(p)?.trim()
            if ((want == null || !want.equals(got, ignoreCase = true)) && (also == null || !also.equals(got, ignoreCase = true))) {
                return "STPT ${p.n}: action ${p.action} is '${want ?: "?"}'" + (also?.takeIf { it != want }?.let { " (route action '$it')" } ?: "") + " but the briefing says '$got'"
            }
        }
        return null
    }
}
