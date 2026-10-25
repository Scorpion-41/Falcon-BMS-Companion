package com.bmscompanion.desktop.bridge.command

import com.bmscompanion.desktop.bridge.CampaignArchive
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToLong

/**
 * The air units' edits, each one undoable ([UnitEdit.mutate]) and keeping the records consistent the way BMS reads
 * them. Facts the rules rest on, counted on 89 saves of Falcon BMS 4.38.1 (`--cmdroundtrip` prints them):
 *
 * - a flight's aircraft are its seats whose aircraft state is not 0 (4 = available); its **roster** holds the same
 *   number, filled into its unit class's slots in order up to each slot's count (a two-ship of a class whose first
 *   slot takes two is 2, of one whose first slot takes none 8);
 * - a seat's pilot is an index into its squadron's 48 (255 none), that squadron pilot marked 4 (in use) while he flies;
 * - a callsign is a word (Strings 2000 + id) and a number 1-9, unique among the flights; the pilots' part keeps a bit
 *   per number in use (1-8);
 * - `fuel_initial` is each seat's fuel at take-off in pounds (internal and external together);
 * - a flight that has not taken off flies to waypoint 1 and stands on waypoint 0's cell;
 * - a waypoint's formation byte is the formation in its low nibble and the spacing + 8 in its high one; bit 0x4000 of
 *   its flags locks its time, 0x8000 the speed of the leg into it; a holding point (action 8) has a departure time of
 *   its own.
 */
object AirOps {
    const val CELL = CampaignArchive.FEET_PER_CELL
    const val HOUR_MS = 3_600_000L
    const val DAY_MS = 86_400_000L
    const val NM_PER_CELL = 1.0 / 1.852
    const val TIME_LOCK = 0x4000L
    const val SPEED_LOCK = 0x8000L
    const val HOLD_CURRENT = 0x800L
    const val ACTION_HOLD = 8
    const val ACTION_LAND = 7
    const val ACTION_TAKEOFF = 1
    const val NO_PILOT = 255
    const val SEAT_AVAILABLE = 4
    const val PILOT_FREE = 0
    const val PILOT_IN_USE = 4

    /** Waypoint actions (Strings 350 + action is BMS's own word), the numbers BMS uses. */
    val ACTIONS = listOf(
        0 to "Nav", 1 to "Take-off", 2 to "Push", 3 to "Split", 4 to "Refuel", 5 to "Rearm", 6 to "Pick-up", 7 to "Land", 8 to "Holding",
        9 to "CAS/CAP", 10 to "Escort", 11 to "Sweep", 12 to "CAP", 13 to "Intercept", 14 to "Ground strike", 15 to "Naval strike",
        16 to "Search & destroy", 17 to "Strike", 18 to "Bomb", 19 to "SEAD", 20 to "ELINT", 21 to "Recon", 22 to "Rescue", 23 to "ASW",
        24 to "Tanker", 25 to "Airdrop", 26 to "Jamming", 27 to "Land (2)", 30 to "FAC",
    )
    val FORMATIONS = listOf("Spread", "Wedge", "Trail", "Ladder", "Stack", "Res cell", "Box", "Arrowhead", "Fluid", "Vic", "Echelon right", "Finger four", "Line abreast", "Echelon left", "Diamond")

    /** The waypoint flag bits and their meaning. */
    val WP_FLAGS = listOf(
        0x1L to "Target", 0x2L to "Assemble", 0x4L to "Break point", 0x8L to "IP", 0x10L to "Turn point", 0x20L to "Contact point",
        0x40L to "Repeat", 0x80L to "Take-off", 0x100L to "Land", 0x200L to "Divert", 0x400L to "Alternate", 0x800L to "Hold to last moment",
        0x1000L to "Repeat continuous", 0x2000L to "In package", TIME_LOCK to "Time locked", SPEED_LOCK to "Speed locked",
        0x10000L to "Refuel info", 0x20000L to "Request help", 0x40000L to "Fuel timing", 0x80000L to "Timing",
    )

    // ---------------------------------------------------------------- reading helpers

    fun formation(w: CmdWaypoint): Int = w.formation and 0x0F
    fun spacing(w: CmdWaypoint): Int = ((w.formation ushr 4) and 0x0F) - 8
    fun formationByte(form: Int, spacing: Int): Int = (form.coerceIn(0, 15)) or (((spacing + 8).coerceIn(0, 15)) shl 4)

    fun timeLocked(w: CmdWaypoint) = w.flags and TIME_LOCK != 0L
    fun speedLocked(w: CmdWaypoint) = w.flags and SPEED_LOCK != 0L

    /** Leg length into waypoint [i], nautical miles (the grid's kilometres). */
    fun legNm(ws: List<CmdWaypoint>, i: Int): Double =
        if (i <= 0 || i >= ws.size) 0.0 else hypot((ws[i].gridX - ws[i - 1].gridX).toDouble(), (ws[i].gridY - ws[i - 1].gridY).toDouble()) * NM_PER_CELL

    /** The ground speed of the leg into [i] from the times (knots), null when the times do not move forward. */
    fun legKts(ws: List<CmdWaypoint>, i: Int): Double? {
        if (i <= 0 || i >= ws.size || ws[i].arrive == 0L) return null
        val dt = ws[i].arrive - ws[i - 1].depart
        if (dt <= 0) return null
        return legNm(ws, i) * HOUR_MS / dt
    }

    /** True airspeed to calibrated, standard atmosphere (for the table): CAS ≈ TAS × √σ. */
    fun casOf(tas: Double, altFt: Double): Double {
        val t = 1 - 6.8756e-6 * altFt.coerceIn(0.0, 36_089.0)
        return tas * Math.pow(t, 4.2559 / 2)
    }

    /** "D1 04:21:00" for campaign ms. */
    fun clock(ms: Long): String = CampaignArchive.clock(ms)

    /** Reads "D2 04:21", "2 04:21:30", "04:21" (day 1) into campaign ms; null when not a time. */
    fun parseClock(s: String): Long? {
        val t = s.trim().removePrefix("D").removePrefix("d").trim()
        val parts = t.split(Regex("\\s+"))
        val (day, hms) = when (parts.size) { 1 -> 1L to parts[0]; 2 -> (parts[0].toLongOrNull() ?: return null) to parts[1]; else -> return null }
        val f = hms.split(':').map { it.toLongOrNull() ?: return null }
        if (f.isEmpty() || f.size > 3 || day < 1) return null
        val h = f[0]; val m = f.getOrElse(1) { 0 }; val sec = f.getOrElse(2) { 0 }
        if (h !in 0..47 || m !in 0..59 || sec !in 0..59) return null
        return (day - 1) * DAY_MS + h * HOUR_MS + m * 60_000 + sec * 1000
    }

    /** Reads a signed duration "+0 01:30", "-00:15", "90" (minutes) into ms. */
    fun parseShift(s: String): Long? {
        val t = s.trim()
        val neg = t.startsWith("-")
        val body = t.removePrefix("-").removePrefix("+").trim()
        val ms = body.toLongOrNull()?.let { it * 60_000 } ?: run {
            val parts = body.split(Regex("\\s+"))
            val (day, hms) = when (parts.size) { 1 -> 0L to parts[0]; 2 -> (parts[0].toLongOrNull() ?: return null) to parts[1]; else -> return null }
            val f = hms.split(':').map { it.toLongOrNull() ?: return null }
            day * DAY_MS + f[0] * HOUR_MS + f.getOrElse(1) { 0 } * 60_000 + f.getOrElse(2) { 0 } * 1000
        }
        return if (neg) -ms else ms
    }

    fun allied(doc: CommandSave, a: Int, b: Int): Boolean {
        if (a == b) return true
        val ta = doc.teams.getOrNull(a)?.team ?: return false
        val s = ta.stance.getOrNull(b) ?: 0
        if (s == 1 || s == 2) return true
        val ca = ta.cteam
        return ca == b || doc.teams.getOrNull(b)?.team?.cteam == a
    }

    // ---------------------------------------------------------------- the time line of a route

    /**
     * Times the route again after waypoint [changed] got a new time (or position, or leg speed), keeping what is
     * locked: the waypoints with a time lock (and [changed] itself) are anchors; between two anchors the route is flown
     * at one constant speed — the legs whose speed is locked keep theirs; before the first anchor and after the last
     * one each leg keeps its own speed (so the times move with it). Station times (a hold's departure) are kept.
     * BMS's routes end with untimed points (an alternate landing at 00:00): the route is timed up to the first of
     * them, which is left untimed.
     */
    fun retime(all: MutableList<CmdWaypoint>, changed: Int) {
        val n = (1 until all.size).firstOrNull { all[it].arrive == 0L } ?: all.size
        if (changed >= n) return
        val ws = all.subList(0, n)
        if (ws.size < 2 || changed !in ws.indices) return
        val speeds = DoubleArray(ws.size) { legKts(ws, it) ?: 0.0 }
        val anchors = ws.indices.filter { it == changed || timeLocked(ws[it]) }
        val prev = anchors.lastOrNull { it < changed }
        val next = anchors.firstOrNull { it > changed }
        // before: between prev and changed, or backwards from changed keeping speeds
        if (prev != null) solveSegment(ws, prev, changed, speeds) else walkBack(ws, changed, speeds)
        if (next != null) solveSegment(ws, changed, next, speeds) else walkForward(ws, changed, speeds)
    }

    /** Waypoint times [a] (departure) and [b] (arrival) are fixed: the legs between share the time at one speed. */
    private fun solveSegment(ws: MutableList<CmdWaypoint>, a: Int, b: Int, speeds: DoubleArray) {
        if (b <= a + 1) return
        var lockedMs = 0.0
        var freeNm = 0.0
        var station = 0L
        for (i in a + 1..b) {
            val nm = legNm(ws, i)
            if (speedLocked(ws[i]) && speeds[i] > 1) lockedMs += nm * HOUR_MS / speeds[i] else freeNm += nm
            if (i < b) station += (ws[i].depart - ws[i].arrive).coerceAtLeast(0)
        }
        val window = (ws[b].arrive - ws[a].depart - station - lockedMs)
        val v = if (freeNm > 0 && window > 0) freeNm * HOUR_MS / window else 0.0
        var t = ws[a].depart.toDouble()
        for (i in a + 1 until b) {
            val nm = legNm(ws, i)
            val kts = if (speedLocked(ws[i]) && speeds[i] > 1) speeds[i] else v
            t += if (kts > 0) nm * HOUR_MS / kts else 0.0
            setArrive(ws[i], t.roundToLong())
            t = ws[i].depart.toDouble()
        }
    }

    private fun walkForward(ws: MutableList<CmdWaypoint>, from: Int, speeds: DoubleArray) {
        for (i in from + 1 until ws.size) {
            val kts = speeds[i].takeIf { it > 1 } ?: 350.0
            setArrive(ws[i], ws[i - 1].depart + (legNm(ws, i) * HOUR_MS / kts).roundToLong())
        }
    }

    private fun walkBack(ws: MutableList<CmdWaypoint>, from: Int, speeds: DoubleArray) {
        for (i in from - 1 downTo 0) {
            val kts = speeds[i + 1].takeIf { it > 1 } ?: 350.0
            val station = (ws[i].depart - ws[i].arrive).coerceAtLeast(0)
            val dep = ws[i + 1].arrive - (legNm(ws, i + 1) * HOUR_MS / kts).roundToLong()
            setArrive(ws[i], (dep - station).coerceAtLeast(0))
        }
    }

    /** A new arrival keeping the waypoint's station time (its departure moves with it). */
    fun setArrive(w: CmdWaypoint, t: Long) {
        val station = if (w.hasDepart) (w.departTime - w.arrive).coerceAtLeast(0) else 0
        val at = t.coerceIn(0, 0xFFFFFFFEL)
        w.arrive = at
        if (w.hasDepart) w.departTime = at + station
    }

    // ---------------------------------------------------------------- flights

    /** The free pilot of [sq] for seat [seat]: the lead the most senior available, wingmen the next; null when none. */
    fun freePilot(sq: CmdSquadron, seat: Int, except: Set<Int> = emptySet()): Int? {
        val free = sq.pilots.indices.filter { sq.pilots[it].status == PILOT_FREE && it !in except }
        if (free.isEmpty()) return null
        return if (seat == 0) free.first() else free.firstOrNull { it >= 1 } ?: free.first()
    }

    /** A flight's roster for [n] aircraft: filled into the class's slots in order, up to each slot's count. */
    fun flightRoster(cls: UnitClasses, type: Int, n: Int): Int {
        var left = n
        var r = 0
        for (k in 0 until 16) {
            if (left <= 0) break
            val take = minOf(cls.slotMax(type, k), left)
            if (take > 0) { r = UnitClasses.withSlot(r, k, take); left -= take }
        }
        if (left > 0) r = UnitClasses.withSlot(r, 0, minOf(3, UnitClasses.slotCount(r, 0) + left))
        return r
    }

    /** The callsign's usage bit (numbers 1-8) in the pilots' part. */
    private fun usageBit(s: UnitEdit.Scope, id: Int, num: Int, on: Boolean) {
        val plt = s.plt ?: return
        if (id !in plt.callsigns.indices || num !in 1..8) return
        val bit = 1 shl (num - 1)
        plt.callsigns[id] = if (on) plt.callsigns[id] or bit else plt.callsigns[id] and bit.inv()
    }

    fun setCallsign(doc: CommandSave, f: CmdFlight, id: Int, num: Int): CommandEdit = UnitEdit.mutate("Callsign of ${doc.callsign(f)}", doc, setOf("uni", "plt")) {
        if (num !in 1..9) UnitEdit.refuse("a callsign number is 1 to 9")
        CommandIds.callsignTaken(doc, id, num, except = f)?.let { UnitEdit.refuse("${doc.string(CampaignArchive.CALLSIGN_STRINGS + id) ?: "callsign $id"} $num is already in use by another flight") }
        touch(f)
        val oldId = f.callsignId; val oldNum = f.callsignNum
        f.callsignId = id; f.callsignNum = num
        usageBit(this, id, num, true)
        if ((oldId != id || oldNum != num) && CommandIds.callsignTaken(doc, oldId, oldNum, except = f) == null) usageBit(this, oldId, oldNum, false)
    }

    fun setMission(doc: CommandSave, f: CmdFlight, mission: Int): CommandEdit = UnitEdit.mutate("Mission of ${doc.callsign(f)}", doc) {
        touch(f); f.mission = mission; f.oldMission = mission
    }

    /** The number of aircraft (1-4): seats, roster, loadouts and pilots follow. */
    fun setAircraft(doc: CommandSave, f: CmdFlight, n: Int): CommandEdit = UnitEdit.mutate("Aircraft of ${doc.callsign(f)}", doc) {
        if (n !in 1..4) UnitEdit.refuse("a flight has 1 to 4 aircraft")
        val sq = doc.squadronOf(f)
        touch(f); touch(sq)
        val cls = UnitClasses.of(doc)
        for (k in 0 until 4) {
            if (k < n) {
                if (f.planeStats[k] == 0) f.planeStats[k] = SEAT_AVAILABLE
                if (f.pilots[k] == NO_PILOT && sq != null) {
                    val taken = f.pilots.filter { it != NO_PILOT }.toSet()
                    freePilot(sq, k, taken)?.let { p -> f.pilots[k] = p; sq.pilots[p].status = PILOT_IN_USE }
                }
            } else {
                f.planeStats[k] = 0
                val p = f.pilots[k]
                if (p != NO_PILOT && sq != null && p in sq.pilots.indices && othersFlying(doc, sq, p, f).not()) sq.pilots[p].status = PILOT_FREE
                f.pilots[k] = NO_PILOT
            }
        }
        f.roster = flightRoster(cls, f.type, n)
        if (f.loadouts.size > 1) {
            while (f.loadouts.size < n) f.loadouts += f.loadouts.last().copy()
            while (f.loadouts.size > n) f.loadouts.removeAt(f.loadouts.size - 1)
        }
    }

    /** Another flight of the squadron still flies squadron pilot [p]. */
    private fun othersFlying(doc: CommandSave, sq: CmdSquadron, p: Int, except: CmdFlight): Boolean =
        doc.flights.any { it !== except && it.squadronId == sq.id && p in it.pilots.take(4) }

    /** Seat [seat]'s pilot: an index into the squadron's pilots, or [NO_PILOT]. */
    fun setPilot(doc: CommandSave, f: CmdFlight, seat: Int, pilot: Int): CommandEdit = UnitEdit.mutate("Pilot ${seat + 1} of ${doc.callsign(f)}", doc) {
        val sq = doc.squadronOf(f) ?: UnitEdit.refuse("the flight has no squadron, whose pilots it flies")
        if (seat !in 0..3) UnitEdit.refuse("no such seat")
        if (pilot != NO_PILOT && pilot !in sq.pilots.indices) UnitEdit.refuse("no such pilot in the squadron")
        if (pilot != NO_PILOT && pilot in f.pilots.filterIndexed { i, _ -> i != seat }) UnitEdit.refuse("that pilot already flies another seat of this flight")
        touch(f); touch(sq)
        val old = f.pilots[seat]
        if (old != NO_PILOT && old in sq.pilots.indices && !othersFlying(doc, sq, old, f)) sq.pilots[old].status = PILOT_FREE
        f.pilots[seat] = pilot
        if (pilot != NO_PILOT) sq.pilots[pilot].status = PILOT_IN_USE
    }

    /**
     * The flight flies for squadron [sq] from now: its pilots, its paint, its base (the take-off and the landings at the
     * old base move there) and, when the squadron flies another aircraft, that aircraft's flight class with its
     * loadout cleared to the gun.
     */
    fun assignSquadron(doc: CommandSave, f: CmdFlight, sq: CmdSquadron): CommandEdit = UnitEdit.mutate("Squadron of ${doc.callsign(f)}", doc) {
        if (f.squadronId == sq.id) UnitEdit.refuse("it flies for that squadron already")
        if (!allied(doc, f.owner, sq.owner)) UnitEdit.refuse("the squadron belongs to a team not allied to the flight's")
        val cls = UnitClasses.of(doc)
        val ft = cls.flightTypeFor(sq.type) ?: UnitEdit.refuse("no flight class flies ${cls.leadVcd(sq.type)?.name ?: "that squadron's aircraft"}")
        val old = doc.squadronOf(f)
        touch(f); touch(old); touch(sq)
        for (k in 0 until 4) {
            val p = f.pilots[k]
            if (old != null && p != NO_PILOT && p in old.pilots.indices && !othersFlying(doc, old, p, f)) old.pilots[p].status = PILOT_FREE
            f.pilots[k] = NO_PILOT
        }
        for (k in 0 until 4) if (f.planeStats[k] != 0) freePilot(sq, k, f.pilots.toSet())?.let { p -> f.pilots[k] = p; sq.pilots[p].status = PILOT_IN_USE }
        if (ft != f.type) {
            f.type = ft; f.entityType = ft
            f.roster = flightRoster(cls, ft, f.aircraft.coerceAtLeast(1))
            f.loadouts.clear(); f.loadouts += cls.bareLoadout(ft)
        }
        f.squadronId = sq.id
        for (k in 0 until 4) f.texSet[k] = sq.texSet
        val oldBase = old?.airbaseId
        f.waypoints.firstOrNull()?.let { w -> w.gridX = sq.x; w.gridY = sq.y; w.setTarget(sq.airbaseId, 255) }
        f.waypoints.drop(1).filter { it.action == ACTION_LAND && (oldBase == null || it.targetId == oldBase) }.forEach { w -> w.gridX = sq.x; w.gridY = sq.y; w.setTarget(sq.airbaseId, 255) }
        if ((f.waypoints.firstOrNull()?.depart ?: 0) >= (doc.cmp?.currentTime ?: 0)) { f.x = sq.x; f.y = sq.y }
    }

    /** Laser codes are four digits, the first 1, the second 1-7, the others 1-8. */
    fun laserRefusal(code: Int): String? {
        val s = code.toString()
        if (s.length != 4 || s[0] != '1' || s[1] !in '1'..'7' || s[2] !in '1'..'8' || s[3] !in '1'..'8') return "A laser code is four digits: 1, then 1-7, then two of 1-8 (1688)."
        return null
    }

    /** A per-seat array field of a flight (fuel, laser code, paint, conformal tanks, aircraft state). */
    fun setSeat(doc: CommandSave, f: CmdFlight, what: String, seat: Int, value: Int, arr: (CmdFlight) -> IntArray): CommandEdit = UnitEdit.mutate("$what ${seat + 1} of ${doc.callsign(f)}", doc) {
        if (seat !in 0..3) UnitEdit.refuse("no such seat")
        touch(f); arr(f)[seat] = value
    }

    fun setLaser(doc: CommandSave, f: CmdFlight, seat: Int, code: Int): CommandEdit {
        laserRefusal(code)?.let { return Edits.failing("Laser code", "uni", it) }
        return setSeat(doc, f, "Laser code", seat, code) { it.laserCode }
    }

    /** Any field of a unit through [set] (one undoable step). */
    fun <U : CmdUnit> field(doc: CommandSave, u: U, label: String, set: U.() -> Unit): CommandEdit = UnitEdit.mutate(label, doc) { touch(u); u.set() }

    /** Every waypoint's formation (and spacing, when given). */
    fun setFormationAll(doc: CommandSave, u: CmdUnit, form: Int, spacing: Int?): CommandEdit = UnitEdit.mutate("Formation of ${nameOf(doc, u)}", doc) {
        touch(u)
        u.waypoints.forEach { w -> w.formation = formationByte(form, spacing ?: spacing(w)) }
    }

    fun lockAll(doc: CommandSave, u: CmdUnit, bit: Long, on: Boolean): CommandEdit =
        UnitEdit.mutate((if (on) "Lock " else "Unlock ") + (if (bit == TIME_LOCK) "times" else "speeds") + " of ${nameOf(doc, u)}", doc) {
            touch(u)
            u.waypoints.forEach { w -> w.flags = if (on) w.flags or bit else w.flags and bit.inv() }
        }

    /**
     * Moves every time of [flights] by [deltaMs] (clamped to the campaign's start), with each flight's time on target
     * and mission end; then where each flight is by the campaign clock: not yet off → on waypoint 0, flying to 1;
     * under way → on the leg it is flying, between its two cells.
     */
    fun shiftTimes(doc: CommandSave, flights: List<CmdFlight>, deltaMs: Long, label: String, pkg: CmdPackage? = null): CommandEdit = UnitEdit.mutate(label, doc) {
        if (deltaMs == 0L) UnitEdit.refuse("the shift is zero")
        val now = doc.cmp?.currentTime ?: 0L
        touch(pkg)
        pkg?.let { p ->
            if (p.request.tot > 0) p.request.tot = (p.request.tot + deltaMs).coerceAtLeast(0)
            if (!p.final) { if (p.takeoff > 0) p.takeoff = (p.takeoff + deltaMs).coerceAtLeast(0); if (p.tpTime > 0) p.tpTime = (p.tpTime + deltaMs).coerceAtLeast(0) }
        }
        for (f in flights) {
            touch(f)
            f.waypoints.forEachIndexed { wi, w -> if (wi > 0 && w.arrive == 0L) return@forEachIndexed
                val station = if (w.hasDepart) w.departTime - w.arrive else 0
                w.arrive = (w.arrive + deltaMs).coerceIn(0, 0xFFFFFFFEL)
                if (w.hasDepart) w.departTime = (w.arrive + station).coerceIn(0, 0xFFFFFFFEL)
            }
            if (f.timeOnTarget > 0) f.timeOnTarget = (f.timeOnTarget + deltaMs).coerceAtLeast(0)
            if (f.missionOverTime > 0) f.missionOverTime = (f.missionOverTime + deltaMs).coerceAtLeast(0)
            placeByClock(f, now)
        }
    }

    /** Where a flight is at campaign time [now] from its route. */
    fun placeByClock(f: CmdFlight, now: Long) {
        val ws = f.waypoints
        if (ws.isEmpty()) return
        if (ws[0].depart >= now) { f.currentWp = minOf(1, ws.size - 1); f.x = ws[0].gridX; f.y = ws[0].gridY; return }
        val k = (1 until ws.size).firstOrNull { ws[it].arrive > now } ?: run { f.currentWp = ws.size - 1; f.x = ws.last().gridX; f.y = ws.last().gridY; return }
        val a = ws[k - 1]; val b = ws[k]
        f.currentWp = k
        if (now < a.depart) { f.x = a.gridX; f.y = a.gridY; return }
        val frac = ((now - a.depart).toDouble() / (b.arrive - a.depart).coerceAtLeast(1)).coerceIn(0.0, 1.0)
        f.x = (a.gridX + (b.gridX - a.gridX) * frac).toInt(); f.y = (a.gridY + (b.gridY - a.gridY) * frac).toInt()
        val hdg = Math.toDegrees(kotlin.math.atan2((b.gridX - a.gridX).toDouble(), (b.gridY - a.gridY).toDouble())).let { if (it < 0) it + 360 else it }
        f.lastDirection = ((hdg + 22.5) / 45).toInt() % 8
    }

    /** The unit's name in an edit's label and a sentence ("Falcon 3", "156th KFS", "500th Air Defense Battalion"). */
    fun nameOf(doc: CommandSave, u: CmdUnit): String = UnitNames.short(doc, u)

    // ---------------------------------------------------------------- waypoints

    /** A waypoint's field through [set]; [retimeAfter] times the route again from it. */
    fun waypoint(doc: CommandSave, u: CmdUnit, i: Int, label: String, retimeAfter: Boolean = false, set: CmdWaypoint.() -> Unit): CommandEdit =
        UnitEdit.mutate("$label (waypoint ${i + 1} of ${nameOf(doc, u)})", doc) {
            val w = u.waypoints.getOrNull(i) ?: UnitEdit.refuse("waypoint ${i + 1} is not there")
            touch(u)
            w.set()
            if (retimeAfter) retime(u.waypoints, i)
        }

    /** A new arrival time; the route is timed again around it. */
    fun setArrival(doc: CommandSave, u: CmdUnit, i: Int, t: Long, retimeRoute: Boolean): CommandEdit =
        waypoint(doc, u, i, "Arrival", retimeRoute) { setArrive(this, t) }

    /** The station time (a hold's departure − arrival); the waypoints after move with it. */
    fun setStation(doc: CommandSave, u: CmdUnit, i: Int, ms: Long): CommandEdit = UnitEdit.mutate("Station time (waypoint ${i + 1} of ${nameOf(doc, u)})", doc) {
        val w = u.waypoints.getOrNull(i) ?: UnitEdit.refuse("waypoint ${i + 1} is not there")
        touch(u)
        val delta = w.arrive + ms.coerceAtLeast(0) - w.depart
        w.depart = w.arrive + ms.coerceAtLeast(0)
        if (ms > 0) w.haves = w.haves or CmdWaypoint.HAS_DEPART
        for (k in i + 1 until u.waypoints.size) {
            val x = u.waypoints[k]
            if (timeLocked(x)) { retime(u.waypoints, k); break }
            setArrive(x, x.arrive + delta)
        }
    }

    /** The speed of the leg into [i] (knots): its arrival follows, then the route around it. */
    fun setLegSpeed(doc: CommandSave, u: CmdUnit, i: Int, kts: Double): CommandEdit = UnitEdit.mutate("Speed into waypoint ${i + 1} of ${nameOf(doc, u)}", doc) {
        if (i <= 0 || i >= u.waypoints.size) UnitEdit.refuse("the first waypoint has no leg into it")
        if (kts !in 30.0..2000.0) UnitEdit.refuse("a speed between 30 and 2,000 knots")
        touch(u)
        val ws = u.waypoints
        setArrive(ws[i], ws[i - 1].depart + ceil(legNm(ws, i) * HOUR_MS / kts).toLong())
        retime(ws, i)
    }

    /** The action: a hold gets a departure of its own (and holds to the last moment); any other loses it. */
    fun setAction(doc: CommandSave, u: CmdUnit, i: Int, action: Int): CommandEdit = waypoint(doc, u, i, "Action") {
        this.action = action
        if (action == ACTION_HOLD) { haves = haves or CmdWaypoint.HAS_DEPART; if (departTime < arrive) departTime = arrive; flags = flags or HOLD_CURRENT }
        else if (hasDepart && departTime == arrive) haves = haves and CmdWaypoint.HAS_DEPART.inv()
    }

    /** Moves a waypoint to a cell; the legs around it are timed again ([retimeRoute]). */
    fun moveWaypoint(doc: CommandSave, u: CmdUnit, i: Int, gx: Int, gy: Int, retimeRoute: Boolean): CommandEdit = UnitEdit.mutate("Move waypoint ${i + 1} of ${nameOf(doc, u)}", doc) {
        val w = u.waypoints.getOrNull(i) ?: UnitEdit.refuse("waypoint ${i + 1} is not there")
        touch(u)
        w.gridX = gx; w.gridY = gy
        if (retimeRoute && u.waypoints.size > 1) {
            // the legs around keep their speeds, unless locked times hold them
            val ws = u.waypoints
            val before = DoubleArray(ws.size) { legKts(ws, it) ?: 0.0 }
            if (i > 0 && !timeLocked(w)) setArrive(w, ws[i - 1].depart + (legNm(ws, i) * HOUR_MS / (before[i].takeIf { it > 1 } ?: 350.0)).roundToLong())
            retime(ws, i)
        }
        if (i == 0 && u is CmdFlight && u.waypoints[0].depart >= (doc.cmp?.currentTime ?: 0)) { u.x = gx; u.y = gy }
    }

    /** A waypoint after [after] (the middle of the next leg, or 15 km on), timed by the legs around it. */
    fun insertWaypoint(doc: CommandSave, u: CmdUnit, after: Int, at: Pair<Int, Int>? = null): CommandEdit = UnitEdit.mutate("Add a waypoint to ${nameOf(doc, u)}", doc) {
        touch(u)
        val ws = u.waypoints
        if (ws.size >= 500) UnitEdit.refuse("a route holds at most 500 waypoints")
        val a = ws.getOrNull(after)
        val b = ws.getOrNull(after + 1)
        val nw = (a ?: b)?.copy()?.also { it.setTarget(null); it.flags = it.flags and (TIME_LOCK or SPEED_LOCK or 0x2000L); it.action = 0; it.routeAction = 0; it.haves = it.haves and CmdWaypoint.HAS_DEPART.inv() }
            ?: CmdWaypoint(0, u.x, u.y, 0, doc.cmp?.currentTime ?: 0, 0, 0, formationByte(1, 0), 0)
        val (gx, gy) = at ?: when {
            a != null && b != null -> (a.gridX + b.gridX) / 2 to (a.gridY + b.gridY) / 2
            a != null -> a.gridX + 15 to a.gridY
            else -> u.x to u.y
        }
        nw.gridX = gx; nw.gridY = gy
        nw.flags = nw.flags and TIME_LOCK.inv()
        val idx = (after + 1).coerceIn(0, ws.size)
        ws.add(idx, nw)
        if (a != null) {
            val kts = (b?.let { legKts(listOf(a, it), 1) } ?: 0.0).takeIf { it > 1 } ?: 350.0
            nw.arrive = a.depart + (legNm(ws, idx) * HOUR_MS / kts).roundToLong()
            nw.departTime = nw.arrive
            retime(ws, idx)
        }
        if (u.currentWp > idx) u.currentWp++
    }

    fun deleteWaypoint(doc: CommandSave, u: CmdUnit, i: Int): CommandEdit = UnitEdit.mutate("Delete waypoint ${i + 1} of ${nameOf(doc, u)}", doc) {
        if (i !in u.waypoints.indices) UnitEdit.refuse("waypoint ${i + 1} is not there")
        if (u is CmdFlight && u.waypoints.size <= 2) UnitEdit.refuse("a flight keeps at least its take-off and landing")
        touch(u)
        u.waypoints.removeAt(i)
        if (u.currentWp > i || u.currentWp >= u.waypoints.size) u.currentWp = (u.currentWp - 1).coerceAtLeast(0).coerceAtMost(maxOf(0, u.waypoints.size - 1))
        if (i in 1 until u.waypoints.size) retime(u.waypoints, i - 1)
    }

    /** The waypoint's target (an objective or a unit, building 255 = the whole) for the lead, and seat [seat]'s own. */
    fun setTarget(doc: CommandSave, u: CmdUnit, i: Int, target: VuId?, building: Int, seat: Int?): CommandEdit = waypoint(doc, u, i, "Target") {
        if (target == null || target.isNone) {
            if (seat == null) { setTarget(null); designated.forEach { it.id = VuId.NONE; it.building = 255 } }
            else designated.getOrNull(seat)?.let { it.id = VuId.NONE; it.building = 255 }
            return@waypoint
        }
        if (seat == null || seat == 0) setTarget(target, building)
        else if (!hasTarget) setTarget(target, 255)
        val seats = if (seat == null) 0 until 4 else seat..seat
        for (s in seats) designated.getOrNull(s)?.let { it.id = target; it.building = building }
    }

    // ---------------------------------------------------------------- loadouts

    /** The loadout of seat [seat] (the shared one when the flight carries one for all). */
    fun loadoutOf(f: CmdFlight, seat: Int): Loadout? = f.loadouts.getOrNull(if (f.loadouts.size == 1) 0 else seat) ?: f.loadouts.firstOrNull()

    /**
     * Hardpoint [hp] of the seats [seats] carries [count] of [weapon] (0 = empty): only a store the aircraft's weapon
     * list allows there, up to its number; the fixed hardpoints (the gun) are BMS's. The flight's loadouts become one
     * per aircraft when the seats differ, one shared when they all agree.
     */
    fun setStore(doc: CommandSave, f: CmdFlight, seats: Set<Int>, hp: Int, weapon: Int, count: Int): CommandEdit = UnitEdit.mutate("Loadout of ${doc.callsign(f)}", doc) {
        val cls = UnitClasses.of(doc)
        val h = cls.hardpoints(f.type).getOrNull(hp) ?: UnitEdit.refuse("no hardpoint ${hp}")
        if (h.fixed != null) UnitEdit.refuse("hardpoint $hp carries the aircraft's own ${cls.weaponName(h.fixed)}")
        if (weapon != 0 && count > 0) {
            val max = h.max(weapon)
            if (max == 0) UnitEdit.refuse("${cls.weaponName(weapon)} cannot be carried on hardpoint $hp of this aircraft")
            if (count > max) UnitEdit.refuse("hardpoint $hp takes at most $max ${cls.weaponName(weapon)}")
        }
        touch(f)
        val n = f.aircraft.coerceIn(1, 4)
        if (f.loadouts.isEmpty()) f.loadouts += cls.bareLoadout(f.type)
        if (f.loadouts.size == 1 && seats.containsAll((0 until n).toList())) {
            put(f.loadouts[0], hp, weapon, count)
        } else {
            while (f.loadouts.size < n) f.loadouts += f.loadouts.last().copy()
            seats.filter { it < f.loadouts.size }.forEach { put(f.loadouts[it], hp, weapon, count) }
            if (f.loadouts.size > 1 && f.loadouts.all { same(it, f.loadouts[0]) }) { val one = f.loadouts[0]; f.loadouts.clear(); f.loadouts += one }
        }
    }

    private fun put(l: Loadout, hp: Int, weapon: Int, count: Int) {
        if (count <= 0 || weapon == 0) { l.weapons[hp] = 0; l.counts[hp] = 0 } else { l.weapons[hp] = weapon; l.counts[hp] = count }
    }

    private fun same(a: Loadout, b: Loadout) = a.weapons.contentEquals(b.weapons) && a.counts.contentEquals(b.counts)

    /** Only the fixed stores (the gun) left, one shared loadout. */
    fun clearLoadout(doc: CommandSave, f: CmdFlight): CommandEdit = UnitEdit.mutate("Clear the loadout of ${doc.callsign(f)}", doc) {
        touch(f)
        f.loadouts.clear(); f.loadouts += UnitClasses.of(doc).bareLoadout(f.type)
    }

    /** Copies seat [from]'s loadout to every aircraft (one shared loadout). */
    fun loadoutToAll(doc: CommandSave, f: CmdFlight, from: Int): CommandEdit = UnitEdit.mutate("One loadout for ${doc.callsign(f)}", doc) {
        val l = loadoutOf(f, from)?.copy() ?: UnitEdit.refuse("the flight has no loadout")
        touch(f); f.loadouts.clear(); f.loadouts += l
    }

    /** What is wrong with a flight's loadout: a store its aircraft cannot carry there, or too many. */
    fun loadoutProblems(doc: CommandSave, f: CmdFlight): List<String> {
        val cls = UnitClasses.of(doc)
        val hps = cls.hardpoints(f.type)
        if (hps.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        f.loadouts.forEachIndexed { s, l ->
            for (k in 0 until 16) {
                val id = l.weapons[k]; val c = l.counts[k]
                if (id <= 0 || c <= 0) continue
                val max = hps[k].max(id)
                if (max == 0) out += "seat ${s + 1} hardpoint $k: ${cls.weaponName(id)} cannot hang there"
                else if (c > max) out += "seat ${s + 1} hardpoint $k: $c ${cls.weaponName(id)}, at most $max"
            }
        }
        return out
    }

    // ---------------------------------------------------------------- packages

    /** Moves [f] to package [to] (at most five flights, of an allied team); its old package goes when it is left empty. */
    fun moveToPackage(doc: CommandSave, f: CmdFlight, to: CmdPackage): CommandEdit = UnitEdit.mutate("Move ${doc.callsign(f)} to package ${to.campId}", doc) {
        if (to.id == f.packageId) UnitEdit.refuse("it is in that package already")
        if (to.elements.size >= 5) UnitEdit.refuse("package ${to.campId} has five flights, BMS's most")
        if (!allied(doc, f.owner, to.owner)) UnitEdit.refuse("package ${to.campId} belongs to a team not allied to the flight's")
        val from = doc.packageOf(f)
        touch(f); touch(to); touch(from)
        from?.elements?.remove(f.id)
        to.elements += f.id
        f.packageId = to.id
        if (from != null && from.elements.isEmpty()) { units.remove(from) }
    }

    /** A new package of its own for [f] (it leaves the one it is in, which goes when left empty). */
    fun ownPackage(doc: CommandSave, f: CmdFlight): CommandEdit = UnitEdit.mutate("A package of its own for ${doc.callsign(f)}", doc) {
        val from = doc.packageOf(f)
        if (from != null && from.elements.size == 1 && from.elements[0] == f.id) UnitEdit.refuse("it is alone in its package already")
        val pkgType = from?.type ?: UnitClasses.of(doc).typesOf(UnitKind.PACKAGE).firstOrNull()?.type ?: UnitEdit.refuse("no package class in the class table")
        touch(f); touch(from)
        val list = units
        from?.elements?.remove(f.id)
        val p = UnitLife.newPackage(doc, f, pkgType)
        list.add(list.indexOf(f).coerceAtLeast(0), p)
        f.packageId = p.id
        if (from != null && from.elements.isEmpty()) list.remove(from)
    }

    fun cellOf(feet: Double): Int = floor(feet / CELL).toInt()
}
