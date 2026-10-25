package com.bmscompanion.desktop.bridge.command

import com.bmscompanion.desktop.bridge.CampaignArchive
import kotlin.math.hypot

/**
 * The Command section's campaign-wide edits on an open save, as undoable [CommandEdit]s: shifting every time of the
 * file, the header's squadron list ([SquadInfo]) against the units, the occupation map, the primary objective list,
 * the event flags, and the pilots' and callsigns' usage counts.
 */
object CampaignOps {
    const val DAY_MS = 86_400_000L

    /** "D2 04:30:00" or "2 04:30" or "04:30" (day 1) → campaign ms; null when it does not read. */
    fun parseClock(s: String): Long? {
        val t = s.trim().removePrefix("D").removePrefix("d").trim()
        val m = Regex("^(?:(\\d+)\\s+)?(\\d{1,2}):(\\d{2})(?::(\\d{2}))?$").find(t) ?: return null
        val day = m.groupValues[1].takeIf { it.isNotEmpty() }?.toLong() ?: 1L
        val h = m.groupValues[2].toLong(); val mi = m.groupValues[3].toLong(); val se = m.groupValues[4].takeIf { it.isNotEmpty() }?.toLong() ?: 0L
        if (day < 1 || h > 23 || mi > 59 || se > 59) return null
        return ((day - 1) * 86400 + h * 3600 + mi * 60 + se) * 1000
    }

    /** "+1 02:00", "-0:30", "90m", "2h" → a signed shift in ms; null when it does not read. */
    fun parseShift(s: String): Long? {
        val t = s.trim().replace(" ", "")
        if (t.isEmpty()) return null
        val sign = if (t.startsWith("-")) -1 else 1
        val b = t.trimStart('+', '-')
        Regex("^(\\d+)([dhms])$").find(b)?.let { m ->
            val n = m.groupValues[1].toLong()
            return sign * n * when (m.groupValues[2]) { "d" -> DAY_MS; "h" -> 3_600_000L; "m" -> 60_000L; else -> 1000L }
        }
        val m = Regex("^(?:(\\d+)d)?(\\d{1,3}):(\\d{2})(?::(\\d{2}))?$").find(b) ?: return null
        val d = m.groupValues[1].takeIf { it.isNotEmpty() }?.toLong() ?: 0L
        return sign * (d * DAY_MS + m.groupValues[2].toLong() * 3_600_000L + m.groupValues[3].toLong() * 60_000L + (m.groupValues[4].takeIf { it.isNotEmpty() }?.toLong() ?: 0L) * 1000L)
    }

    /**
     * Every time of the file moved by [deltaMs]: the clock (and the start time where it is the clock, as a TE has it;
     * the current day with it), the time limit, every route's arrival and departure, flights' time on target and
     * mission end, packages' take-off, turn point and requested time on target, every team's ground and air action
     * times. A time of 0 (unset) stays 0. Refused when a time would fall before day 1.
     */
    fun shiftTimes(doc: CommandSave, deltaMs: Long): CommandEdit {
        val label = "Shift every time by ${shiftText(deltaMs)}"
        val h = doc.cmp ?: return Edits.failing(label, "cmp", "the file has no campaign header")
        if (deltaMs == 0L) return Edits.failing(label, "cmp", "a shift of nothing changes nothing")
        val edits = ArrayList<CommandEdit>()
        var tooEarly: String? = null
        fun shift(part: String, what: String, get: () -> Long, set: (Long) -> Unit) {
            val v = get()
            if (v == 0L) return
            val n = v + deltaMs
            if (n < 0 || n > 0xFFFFFFFFL) { if (tooEarly == null) tooEarly = what; return }
            edits += Edits.set(label, part, get, set, n)
        }
        val clock = h.currentTime
        shift("cmp", "the clock", { h.currentTime }, { h.currentTime = it })
        if (h.startTime == clock) shift("cmp", "the start time", { h.startTime }, { h.startTime = it })
        if (h.timeLimit > clock) shift("cmp", "the time limit", { h.timeLimit }, { h.timeLimit = it })
        val newDay = ((clock + deltaMs) / DAY_MS + 1).toInt()
        if (h.currentDay == (clock / DAY_MS + 1).toInt() && newDay in 0..255) edits += Edits.set(label, "cmp", { h.currentDay }, { h.currentDay = it }, newDay)
        if (doc.uni?.editable == true) for (u in doc.units) {
            for (w in u.waypoints) {
                val hadDepart = w.hasDepart
                shift("uni", "a waypoint of unit ${u.campId}", { w.arrive }, { w.arrive = it })
                if (hadDepart) shift("uni", "a waypoint of unit ${u.campId}", { w.departTime }, { w.departTime = it })
            }
            when (u) {
                is CmdFlight -> {
                    shift("uni", "flight ${u.campId}'s time on target", { u.timeOnTarget }, { u.timeOnTarget = it })
                    shift("uni", "flight ${u.campId}'s mission end", { u.missionOverTime }, { u.missionOverTime = it })
                }
                is CmdPackage -> {
                    shift("uni", "package ${u.campId}'s take-off", { u.takeoff }, { u.takeoff = it })
                    shift("uni", "package ${u.campId}'s turn point", { u.tpTime }, { u.tpTime = it })
                    shift("uni", "package ${u.campId}'s time on target", { u.request.tot }, { u.request.tot = it })
                    (u.ingress + u.egress).forEach { w -> shift("uni", "package ${u.campId}'s route", { w.arrive }, { w.arrive = it }); if (w.hasDepart) shift("uni", "package ${u.campId}'s route", { w.departTime }, { w.departTime = it }) }
                }
                else -> Unit
            }
        }
        if (doc.tea?.editable == true) for (e in doc.teams) {
            val t = e.team
            shift("tea", "a team's ground action", { t.groundAction.time }, { t.groundAction.time = it })
            shift("tea", "a team's ground action", { t.groundAction.timeout }, { t.groundAction.timeout = it })
            for (a in listOf(t.defensiveAir, t.offensiveAir)) {
                shift("tea", "a team's air action", { a.start }, { a.start = it })
                shift("tea", "a team's air action", { a.stop }, { a.stop = it })
            }
            e.air.requests.forEach { r -> shift("tea", "a team's air task", { r.tot }, { r.tot = it }) }
        }
        tooEarly?.let { return Edits.failing(label, "cmp", "$it would fall before day 1") }
        return Edits.group(label, edits)
    }

    fun shiftText(ms: Long): String {
        val a = kotlin.math.abs(ms) / 1000
        val d = a / 86400; val hh = (a % 86400) / 3600; val mm = (a % 3600) / 60; val ss = a % 60
        return (if (ms < 0) "−" else "+") + (if (d > 0) "${d}d " else "") + "%d:%02d".format(hh, mm) + (if (ss > 0) ":%02d".format(ss) else "")
    }

    // ---------------------------------------------------------------- the header's squadron list

    /** Header entries naming no squadron of the file. */
    fun orphanSquadInfo(doc: CommandSave): List<SquadInfo> {
        val ids = doc.squadrons.map { it.id }.toSet()
        if (doc.uni == null) return emptyList()
        return doc.cmp?.squadInfo.orEmpty().filter { it.id !in ids }
    }

    /** Squadrons the header does not list (BMS's screens then have no name or base for them). */
    fun unlistedSquadrons(doc: CommandSave): List<CmdSquadron> {
        val ids = doc.cmp?.squadInfo.orEmpty().map { it.id }.toSet()
        return doc.squadrons.filter { it.id !in ids }
    }

    fun removeSquadInfo(doc: CommandSave, list: List<SquadInfo>, label: String): CommandEdit {
        val h = doc.cmp ?: return Edits.failing(label, "cmp", "the file has no campaign header")
        return Edits.group(label, list.map { Edits.remove(label, "cmp", h.squadInfo, it) })
    }

    /**
     * A header entry for squadron [s], modelled on another entry of the file: its id, class, name number, CampId,
     * flags and paint, its airbase's position and name, the class's name for its own.
     */
    fun squadInfoFor(doc: CommandSave, s: CmdSquadron): SquadInfo? {
        val h = doc.cmp ?: return null
        val model = h.squadInfo.firstOrNull() ?: return null
        val si = model.copy()
        si.id = s.id; si.descriptionIndex = s.type - 100; si.nameId = s.nameId; si.campId = s.campId
        si.flags = s.unitFlags; si.texSet = s.texSet; si.currentStrength = rosterTotal(s.roster)
        val base = doc.objective(s.airbaseId)
        val north = base?.simNorth?.takeIf { it.isFinite() && it != 0.0 } ?: ((s.y + 0.5) * CampaignArchive.FEET_PER_CELL)
        val east = base?.simEast?.takeIf { it.isFinite() && it != 0.0 } ?: ((s.x + 0.5) * CampaignArchive.FEET_PER_CELL)
        si.x = north.toFloat(); si.y = east.toFloat()
        si.airbaseName = si.airbaseName.copy().also { it.text = base?.name?.text?.trim().orEmpty() }
        si.squadronName = si.squadronName.copy().also { it.text = (doc.unitClass(s) ?: "Squadron ${s.campId}").take(79) }
        return si
    }

    fun addSquadInfo(doc: CommandSave, list: List<CmdSquadron>, label: String): CommandEdit {
        val h = doc.cmp ?: return Edits.failing(label, "cmp", "the file has no campaign header")
        val made = list.mapNotNull { squadInfoFor(doc, it) }
        if (made.isEmpty()) return Edits.failing(label, "cmp", "there is no header entry to model a new one on")
        return Edits.group(label, made.map { Edits.insert(label, "cmp", h.squadInfo, it) })
    }

    /** Aircraft in a roster: 16 slots of two bits. */
    fun rosterTotal(roster: Int): Int = (0 until 16).sumOf { (roster ushr (2 * it)) and 3 }

    // ---------------------------------------------------------------- the occupation map

    /**
     * The header's occupation map: a team (0-15) per cell of [cellKm] grid kilometres, [width] cells a row, rows from
     * the theater's south edge, two cells a byte (the even cell in the low half). Read from the shipped starts: the
     * owner of nearly every objective (98 % in Korea's) is its cell's.
     */
    class OccMap(val width: Int, val height: Int, val cellKm: Int) {
        fun index(cx: Int, cy: Int): Int = cy * width + cx
        fun get(map: ByteArray, cx: Int, cy: Int): Int {
            if (cx !in 0 until width || cy < 0) return -1
            val i = index(cx, cy)
            val b = map.getOrNull(i / 2)?.toInt() ?: return -1
            return if (i % 2 == 0) b and 15 else (b shr 4) and 15
        }
        fun set(map: ByteArray, cx: Int, cy: Int, team: Int) {
            if (cx !in 0 until width || cy < 0) return
            val i = index(cx, cy)
            if (i / 2 >= map.size) return
            val b = map[i / 2].toInt() and 0xFF
            map[i / 2] = (if (i % 2 == 0) (b and 0xF0) or (team and 15) else (b and 0x0F) or ((team and 15) shl 4)).toByte()
        }
    }

    const val OCC_CELL_KM = 6

    fun occMap(doc: CommandSave): OccMap? {
        val h = doc.cmp ?: return null
        if (h.campMap.isEmpty()) return null
        val w = (h.theaterSizeX.takeIf { it > 0 } ?: 1024) / OCC_CELL_KM
        if (w <= 0) return null
        return OccMap(w, (h.campMap.size * 2 + w - 1) / w, OCC_CELL_KM)
    }

    /** How many objectives' owners agree with the occupation map (a check of the layout). */
    fun occAgreement(doc: CommandSave): Pair<Int, Int> {
        val m = occMap(doc) ?: return 0 to 0
        val map = doc.cmp!!.campMap
        var ok = 0; var n = 0
        for (o in doc.objectives) {
            val v = m.get(map, o.x / m.cellKm, o.y / m.cellKm)
            if (v < 0) continue
            n++; if (v == doc.ownerNow(o)) ok++
        }
        return ok to n
    }

    /** The whole occupation map replaced by [map] (a painted or built one). */
    fun setOccMap(doc: CommandSave, map: ByteArray, label: String): CommandEdit {
        val h = doc.cmp ?: return Edits.failing(label, "cmp", "the file has no campaign header")
        return Edits.set(label, "cmp", { h.campMap }, { h.campMap = it }, map)
    }

    /** The occupation map built from the objectives: every cell the owner (now) of its nearest objective, at most [maxKm] off. */
    fun buildOccMap(doc: CommandSave, maxKm: Double = 40.0): ByteArray? {
        val m = occMap(doc) ?: return null
        val old = doc.cmp!!.campMap
        val out = old.copyOf()
        val objs = doc.objectives
        if (objs.isEmpty()) return null
        val bucket = HashMap<Long, MutableList<CmdObjective>>()
        val b = 20
        objs.forEach { bucket.getOrPut((it.x / b).toLong() * 10_000 + it.y / b) { ArrayList() } += it }
        for (cy in 0 until m.height) for (cx in 0 until m.width) {
            if (m.index(cx, cy) / 2 >= out.size) continue
            val px = cx * m.cellKm + m.cellKm / 2.0; val py = cy * m.cellKm + m.cellKm / 2.0
            var best: CmdObjective? = null; var bd = Double.MAX_VALUE
            val r = (maxKm / b).toInt() + 1
            for (dx in -r..r) for (dy in -r..r) bucket[((px.toInt() / b) + dx).toLong() * 10_000 + (py.toInt() / b) + dy]?.forEach { o ->
                val d = hypot(o.x + 0.5 - px, o.y + 0.5 - py)
                if (d < bd) { bd = d; best = o }
            }
            if (best != null && bd <= maxKm) m.set(out, cx, cy, doc.ownerNow(best!!))
        }
        return out
    }

    // ---------------------------------------------------------------- the primary objective list

    /**
     * The primary objective list made again from the start's priority cities: every city (type 8) of priority 80 and
     * above, most important first, each team's priority left to BMS (−1) and no flags, for the teams the list had.
     */
    fun rebuildPol(doc: CommandSave, minPriority: Int = 80): CommandEdit {
        val label = "Rebuild the primary objective list"
        val pol = doc.pol ?: return Edits.failing(label, "pol", "the file has no primary objective part")
        val cities = doc.objectives.filter { ObjectiveOps.typeOf(doc, it) == 8 && it.priority >= minPriority }
            .sortedWith(compareByDescending<CmdObjective> { it.priority }.thenBy { it.campId })
        val entries = cities.map { PolEntry(it.id, IntArray(8) { -1 }, IntArray(8)) }
        val old = pol.entries.toList()
        return object : CommandEdit {
            override val label = label
            override val parts = setOf("pol")
            override fun apply(doc: CommandSave) { pol.entries.clear(); pol.entries.addAll(entries) }
            override fun undo(doc: CommandSave) { pol.entries.clear(); pol.entries.addAll(old) }
        }
    }

    // ---------------------------------------------------------------- events, pilots, callsigns

    /** An event flag's bit on or off (bit 0 one time only, bit 3 triggered). */
    fun eventFlag(doc: CommandSave, e: CampEventFlag, bit: Int, on: Boolean): CommandEdit =
        Edits.set("Event ${e.id}: ${if (bit == 1) "one time only" else "triggered"} ${if (on) "on" else "off"}", "evt", { e.flags }, { e.flags = it },
            if (on) e.flags or bit else e.flags and bit.inv())

    /** Each callsign's usage bits made again from the flights (numbers 1-8 in use). */
    fun recountCallsigns(doc: CommandSave): CommandEdit {
        val label = "Callsign usage from the flights"
        val plt = doc.plt ?: return Edits.failing(label, "plt", "the file has no pilot part")
        return Edits.set(label, "plt", { plt.callsigns }, { plt.callsigns = it }, CommandIds.callsignUsage(doc))
    }

    /** How many squadron pilot places carry each pilot name (the usage BMS counts when it hands names out). */
    fun pilotUsageFromSquadrons(doc: CommandSave): IntArray {
        val n = doc.plt?.pilots?.size ?: 0
        val out = IntArray(n)
        for (s in doc.squadrons) for (p in s.pilots) if (p.pilotId in 0 until n && p.status != 0) out[p.pilotId]++
        return out
    }

    fun recountPilots(doc: CommandSave): CommandEdit {
        val label = "Pilot usage from the squadrons"
        val plt = doc.plt ?: return Edits.failing(label, "plt", "the file has no pilot part")
        val use = pilotUsageFromSquadrons(doc)
        return Edits.group(label, plt.pilots.mapIndexedNotNull { i, p -> if (p.usage != use[i]) Edits.set(label, "plt", { p.usage }, { p.usage = it }, use[i]) else null })
    }

    fun clearCallsign(doc: CommandSave, i: Int): CommandEdit? {
        val plt = doc.plt ?: return null
        if (i !in plt.callsigns.indices) return null
        return Edits.set("Clear the usage of callsign ${doc.string(CampaignArchive.CALLSIGN_STRINGS + i) ?: "$i"}", "plt", { plt.callsigns }, { plt.callsigns = it }, plt.callsigns.copyOf().also { it[i] = 0 })
    }
}
