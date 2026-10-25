package com.bmscompanion.desktop.bridge.command

/**
 * The units part (`.uni`, unpacked): records back to back, each an `i16` type (class-table index + 100) then the
 * record, whose layout the class table decides (air: flight, package, squadron; land: battalion, brigade; sea: task
 * force). Every field is held; [UnitPart.encode] writes them back in the same order, so an unedited record comes out
 * byte for byte as it was read (checked on every save by `--cmdreadtest`).
 *
 * Layouts are those of Falcon BMS 4.38.1's saves (format versions 107-110); [ver] gates the three later fields of a
 * flight (paint ≥105, TACAN ≥108, conformal tanks ≥109) and a squadron's.
 */

/** What a unit record is (class table: domain 2 air, 3 land, 4 sea; type 1, 2, 3). */
enum class UnitKind { FLIGHT, PACKAGE, SQUADRON, BATTALION, BRIGADE, TASKFORCE }

/** One of a waypoint's four seats' own targets. */
class Designated(var id: VuId, var building: Int)

/**
 * A waypoint: grid cell ([gridX] east, [gridY] north), altitude in tens of feet ([gridZ]), arrival and departure
 * (campaign ms), action, route action, formation, flags, and — when [haves] has bit 2 — the target (an objective or a
 * unit, [targetBuilding] 255 = the whole) with the four seats' designated targets. Bit 1 of [haves] = a departure
 * time of its own ([depart]); without it the departure is the arrival.
 */
class CmdWaypoint(
    var haves: Int, var gridX: Int, var gridY: Int, var gridZ: Int, var arrive: Long,
    var action: Int, var routeAction: Int, var formation: Int, var flags: Long,
    var targetId: VuId = VuId.NONE, var targetBuilding: Int = 255,
    var designated: MutableList<Designated> = MutableList(4) { Designated(VuId.NONE, 255) },
    var departTime: Long = 0,
) {
    val hasTarget: Boolean get() = haves and HAS_TARGET != 0
    val hasDepart: Boolean get() = haves and HAS_DEPART != 0

    /** The departure time: its own, or the arrival. Setting it different from the arrival sets bit 1 of [haves]. */
    var depart: Long
        get() = if (hasDepart) departTime else arrive
        set(v) { departTime = v; haves = if (v != arrive) haves or HAS_DEPART else haves }

    /** Sets or clears the target (bit 2 of [haves]). */
    fun setTarget(id: VuId?, building: Int = 255) {
        if (id == null || id.isNone) { haves = haves and HAS_TARGET.inv(); targetId = VuId.NONE; targetBuilding = 255 }
        else { haves = haves or HAS_TARGET; targetId = id; targetBuilding = building }
    }

    fun write(w: LeOut) {
        w.u8(haves); w.i16(gridX); w.i16(gridY); w.i16(gridZ); w.u32(arrive)
        w.u8(action); w.u8(routeAction); w.u8(formation); w.u32(flags)
        if (hasTarget) {
            w.id(targetId); w.u8(targetBuilding)
            for (i in 0 until 4) { val d = designated.getOrNull(i); w.id(d?.id ?: VuId.NONE); w.u8(d?.building ?: 255) }
        }
        if (hasDepart) w.u32(departTime)
    }

    fun copy() = CmdWaypoint(haves, gridX, gridY, gridZ, arrive, action, routeAction, formation, flags, targetId, targetBuilding,
        designated.map { Designated(it.id, it.building) }.toMutableList(), departTime)

    companion object {
        const val HAS_DEPART = 1
        const val HAS_TARGET = 2
        fun read(r: LeIn): CmdWaypoint {
            val w = CmdWaypoint(r.u8(), r.i16(), r.i16(), r.i16(), r.u32(), r.u8(), r.u8(), r.u8(), r.u32())
            if (w.hasTarget) {
                w.targetId = r.id(); w.targetBuilding = r.u8()
                w.designated = MutableList(4) { Designated(r.id(), r.u8()) }
            }
            if (w.hasDepart) w.departTime = r.u32()
            return w
        }
    }
}

/**
 * The fields every unit record starts with (the campaign base, then the unit's): id, class ([entityType] = [type]),
 * grid cell [x] east / [y] north and height, when and by whom it was spotted (team bits), base flags, owner (team),
 * CampId, last check, roster, unit flags, destination, target and cargo, moved, losses, tactic, the waypoint it flies
 * to, name number, reinforcement, and the route.
 */
sealed class CmdUnit(val kind: UnitKind) {
    /** the record's type prefix (class index + 100); normally equal to [entityType] */
    var type = 0
    var id = VuId.NONE
    var entityType = 0
    var x = 0
    var y = 0
    var zBits = 0
    var spotTime = 0L
    var spotted = 0
    var baseFlags = 0
    var owner = 0
    var campId = 0
    var lastCheck = 0L
    var roster = 0
    var unitFlags = 0
    var destX = 0
    var destY = 0
    var targetId = VuId.NONE
    var cargoId = VuId.NONE
    var moved = 0
    var losses = 0
    var tactic = 0
    var currentWp = 0
    var nameId = 0
    var reinforcement = 0
    var waypoints: MutableList<CmdWaypoint> = ArrayList()
    /** the save's format version (gates later fields) */
    var ver = 0
    /** the record's bytes as read (empty for a record made here) */
    var raw: ByteArray = ByteArray(0)

    var z: Float get() = fl(zBits); set(v) { zBits = v.bits() }

    protected fun readCore(r: LeIn) {
        id = r.id(); entityType = r.u16(); x = r.i16(); y = r.i16(); zBits = r.i32()
        spotTime = r.u32(); spotted = r.i16(); baseFlags = r.i16(); owner = r.u8(); campId = r.i16()
        lastCheck = r.u32(); roster = r.i32(); unitFlags = r.i32(); destX = r.i16(); destY = r.i16()
        targetId = r.id(); cargoId = r.id(); moved = r.u8(); losses = r.u8(); tactic = r.u8()
        currentWp = r.u16(); nameId = r.i16(); reinforcement = r.i16()
        waypoints = MutableList(r.u16()) { CmdWaypoint.read(r) }
    }

    protected fun writeCore(w: LeOut) {
        w.i16(type)
        w.id(id); w.u16(entityType); w.i16(x); w.i16(y); w.i32(zBits)
        w.u32(spotTime); w.i16(spotted); w.i16(baseFlags); w.u8(owner); w.i16(campId)
        w.u32(lastCheck); w.i32(roster); w.i32(unitFlags); w.i16(destX); w.i16(destY)
        w.id(targetId); w.id(cargoId); w.u8(moved); w.u8(losses); w.u8(tactic)
        w.u16(currentWp); w.i16(nameId); w.i16(reinforcement)
        w.u16(waypoints.size); waypoints.forEach { it.write(w) }
    }

    protected abstract fun readBody(r: LeIn)
    protected abstract fun writeBody(w: LeOut)

    fun write(w: LeOut) { writeCore(w); writeBody(w) }
    fun encode(): ByteArray = LeOut(256).also { write(it) }.toByteArray()

    /** A deep copy (same id: the caller gives it a new one). */
    fun copy(): CmdUnit = UnitPart.decodeOne(kind, encode(), ver).also { it.raw = ByteArray(0) }

    val isDead: Boolean get() = unitFlags and U_DEAD != 0

    companion object {
        const val U_PARENT = 0x20
        const val U_DEAD = 0x20000
        const val U_FINAL = 0x100000

        fun blank(kind: UnitKind): CmdUnit = when (kind) {
            UnitKind.FLIGHT -> CmdFlight()
            UnitKind.PACKAGE -> CmdPackage()
            UnitKind.SQUADRON -> CmdSquadron()
            UnitKind.BATTALION -> CmdBattalion()
            UnitKind.BRIGADE -> CmdBrigade()
            UnitKind.TASKFORCE -> CmdTaskForce()
        }

        internal fun readAs(kind: UnitKind, r: LeIn, ver: Int): CmdUnit {
            val u = blank(kind)
            u.ver = ver
            u.type = r.i16()
            u.readCore(r)
            u.readBody(r)
            return u
        }
    }
}

/** One loadout: 16 hardpoints, each a weapon id (the weapon table's number) and a count. */
class Loadout(var weapons: IntArray = IntArray(16), var counts: IntArray = IntArray(16)) {
    fun write(w: LeOut) { w.u16s(weapons); w.u8s(counts) }
    fun copy() = Loadout(weapons.copyOf(), counts.copyOf())
    companion object { fun read(r: LeIn) = Loadout(IntArray(16) { r.u16() }, r.u8s(16)) }
}

private fun LeOut.u16s(a: IntArray) = a.forEach { u16(it) }

/**
 * A flight. Its route is [waypoints]; per seat (four): fuel, laser code, squadron pilot ([pilots] — an index into the
 * squadron's 48), aircraft state ([planeStats], 0 = no aircraft), player slot (255 = none), paint, TACAN, conformal
 * tanks. [loadouts] holds 1 (all aircraft alike) or 4. [callsignId] is the callsign word (Strings 2000 + id), and
 * [callsignNum] its number.
 */
class CmdFlight : CmdUnit(UnitKind.FLIGHT) {
    var z2Bits = 0
    var fuelBurnt = 0
    var fuelInitial = IntArray(4)
    var laserCode = IntArray(4)
    var lastMove = 0L
    var lastCombat = 0L
    var timeOnTarget = 0L
    var missionOverTime = 0L
    var missionTarget = 0
    var loadouts: MutableList<Loadout> = ArrayList()
    var mission = 0
    var oldMission = 0
    var lastDirection = 0
    var priority = 0
    var missionId = 0
    var evalFlags = 0
    var missionContext = 0
    var packageId = VuId.NONE
    var squadronId = VuId.NONE
    var requesterId = VuId.NONE
    var slots = IntArray(4)
    var pilots = IntArray(4)
    var planeStats = IntArray(4)
    var playerSlots = IntArray(4) { 255 }
    var lastPlayerSlot = 0
    var callsignId = 0
    var callsignNum = 0
    var refuelQuantity = 0L
    var texSet = IntArray(4)
    var tacanChannel = IntArray(4)
    var tacanBand = IntArray(4)
    var loadedCft = IntArray(4)

    val aircraft: Int get() = planeStats.count { it != 0 }

    override fun readBody(r: LeIn) {
        z2Bits = r.i32(); fuelBurnt = r.i32(); fuelInitial = r.ints32(4); laserCode = r.ints16(4)
        lastMove = r.u32(); lastCombat = r.u32(); timeOnTarget = r.u32(); missionOverTime = r.u32(); missionTarget = r.i16()
        loadouts = MutableList(r.u8()) { Loadout.read(r) }
        mission = r.u8(); oldMission = r.u8(); lastDirection = r.u8(); priority = r.u8(); missionId = r.u8()
        evalFlags = r.u8(); missionContext = r.u8()
        packageId = r.id(); squadronId = r.id(); requesterId = r.id()
        slots = r.u8s(4); pilots = r.u8s(4); planeStats = r.u8s(4); playerSlots = r.u8s(4)
        lastPlayerSlot = r.u8(); callsignId = r.u8(); callsignNum = r.u8(); refuelQuantity = r.u32()
        if (ver >= 105) texSet = r.ints32(4)
        if (ver >= 108) { tacanChannel = r.u8s(4); tacanBand = r.u8s(4) }
        if (ver >= 109) loadedCft = r.u8s(4)
    }

    override fun writeBody(w: LeOut) {
        w.i32(z2Bits); w.i32(fuelBurnt); w.ints32(fuelInitial); w.ints16(laserCode)
        w.u32(lastMove); w.u32(lastCombat); w.u32(timeOnTarget); w.u32(missionOverTime); w.i16(missionTarget)
        w.u8(loadouts.size); loadouts.forEach { it.write(w) }
        w.u8(mission); w.u8(oldMission); w.u8(lastDirection); w.u8(priority); w.u8(missionId)
        w.u8(evalFlags); w.u8(missionContext)
        w.id(packageId); w.id(squadronId); w.id(requesterId)
        w.u8s(slots); w.u8s(pilots); w.u8s(planeStats); w.u8s(playerSlots)
        w.u8(lastPlayerSlot); w.u8(callsignId); w.u8(callsignNum); w.u32(refuelQuantity)
        if (ver >= 105) w.ints32(texSet)
        if (ver >= 108) { w.u8s(tacanChannel); w.u8s(tacanBand) }
        if (ver >= 109) w.u8s(loadedCft)
    }
}

/**
 * A mission request (76 bytes): who asked and what for — kept in full by a package still being planned and by the
 * air tasking manager's queue (`.tea`); a planned package keeps a few of its fields ([CmdPackage]).
 */
class MissionRequest(
    var requester: VuId = VuId.NONE, var target: VuId = VuId.NONE, var secondary: VuId = VuId.NONE, var packageId: VuId = VuId.NONE,
    var who: Int = 0, var vs: Int = 0, var pad1: ByteArray = ByteArray(2), var tot: Long = 0, var tx: Int = 0, var ty: Int = 0,
    var flags: Int = 0, var caps: Int = 0, var targetNum: Int = 0, var speed: Int = 0, var matchStrength: Int = 0, var priority: Int = 0,
    var totType: Int = 0, var actionType: Int = 0, var mission: Int = 0, var aircraft: Int = 0, var context: Int = 0, var roeCheck: Int = 0,
    var delayed: Int = 0, var startBlock: Int = 0, var finalBlock: Int = 0, var slots: IntArray = IntArray(4),
    var minTo: Int = 0, var maxTo: Int = 0, var pad2: ByteArray = ByteArray(3),
) {
    fun write(w: LeOut) {
        w.id(requester); w.id(target); w.id(secondary); w.id(packageId)
        w.u8(who); w.u8(vs); w.bytes(pad1); w.u32(tot); w.i16(tx); w.i16(ty)
        w.i32(flags); w.i16(caps); w.i16(targetNum); w.i16(speed); w.i16(matchStrength); w.i16(priority)
        w.u8(totType); w.u8(actionType); w.u8(mission); w.u8(aircraft); w.u8(context); w.u8(roeCheck)
        w.u8(delayed); w.u8(startBlock); w.u8(finalBlock); w.u8s(slots); w.u8(minTo); w.u8(maxTo); w.bytes(pad2)
    }

    companion object {
        const val SIZE = 76
        fun read(r: LeIn) = MissionRequest(
            r.id(), r.id(), r.id(), r.id(), r.u8(), r.u8(), r.bytes(2), r.u32(), r.i16(), r.i16(),
            r.i32(), r.i16(), r.i16(), r.i16(), r.i16(), r.i16(),
            r.u8(), r.u8(), r.u8(), r.u8(), r.u8(), r.u8(), r.u8(), r.u8(), r.u8(), r.u8s(4), r.u8(), r.u8(), r.bytes(3),
        )
    }
}

/**
 * A package: its flights ([elements]) and the support it is tied to. A package whose planning is finished — unit flag
 * [CmdUnit.U_FINAL] and no wait cycles — keeps only [requests], [responses] and a few of [request]'s fields (mission,
 * aircraft, context, ROE, requester, target, time on target, action type, priority); one still being planned keeps
 * the planning points, times, flags and its ingress and egress routes as well, and the whole request. Which form is
 * written follows the flags at the time of writing.
 */
class CmdPackage : CmdUnit(UnitKind.PACKAGE) {
    var elements: MutableList<VuId> = ArrayList()
    var interceptor = VuId.NONE
    var awacs = VuId.NONE
    var jstar = VuId.NONE
    var ecm = VuId.NONE
    var tanker = VuId.NONE
    var waitCycles = 0
    var requests = 0
    var responses = 0
    var request = MissionRequest()
    // only while planning
    var flights = 0
    var waitFor = 0
    /** ingress, egress, break and turn points (x, y each: 8 values) */
    var points = IntArray(8)
    var takeoff = 0L
    var tpTime = 0L
    var packageFlags = 0
    var caps = 0
    var ingress: MutableList<CmdWaypoint> = ArrayList()
    var egress: MutableList<CmdWaypoint> = ArrayList()

    val final: Boolean get() = unitFlags and U_FINAL != 0 && waitCycles == 0

    override fun readBody(r: LeIn) {
        elements = MutableList(r.u8()) { r.id() }
        interceptor = r.id(); awacs = r.id(); jstar = r.id(); ecm = r.id(); tanker = r.id()
        waitCycles = r.u8()
        if (final) {
            requests = r.i16(); responses = r.i16()
            request.mission = r.u8(); request.aircraft = r.u8(); request.context = r.u8(); request.roeCheck = r.u8()
            request.requester = r.id(); request.target = r.id(); request.tot = r.u32(); request.actionType = r.u8(); request.priority = r.i16()
        } else {
            flights = r.u8(); waitFor = r.i16(); points = r.ints16(8)
            takeoff = r.u32(); tpTime = r.u32(); packageFlags = r.i32(); caps = r.i16(); requests = r.i16(); responses = r.i16()
            ingress = MutableList(r.u8()) { CmdWaypoint.read(r) }
            egress = MutableList(r.u8()) { CmdWaypoint.read(r) }
            request = MissionRequest.read(r)
        }
    }

    override fun writeBody(w: LeOut) {
        w.u8(elements.size); elements.forEach { w.id(it) }
        w.id(interceptor); w.id(awacs); w.id(jstar); w.id(ecm); w.id(tanker)
        w.u8(waitCycles)
        if (final) {
            w.i16(requests); w.i16(responses)
            w.u8(request.mission); w.u8(request.aircraft); w.u8(request.context); w.u8(request.roeCheck)
            w.id(request.requester); w.id(request.target); w.u32(request.tot); w.u8(request.actionType); w.i16(request.priority)
        } else {
            w.u8(flights); w.i16(waitFor); w.ints16(points)
            w.u32(takeoff); w.u32(tpTime); w.i32(packageFlags); w.i16(caps); w.i16(requests); w.i16(responses)
            w.u8(ingress.size); ingress.forEach { it.write(w) }
            w.u8(egress.size); egress.forEach { it.write(w) }
            request.write(w)
        }
    }
}

/** One of a squadron's 48 pilots: id (Strings 2300 + id), skill and rating, status, kills, missions. */
class SquadronPilot(
    var pilotId: Int, var skillAndRating: Int, var status: Int,
    var aaKills: Int, var agKills: Int, var asKills: Int, var anKills: Int, var missionsFlown: Int,
) {
    fun write(w: LeOut) { w.i16(pilotId); w.u8(skillAndRating); w.u8(status); w.u8(aaKills); w.u8(agKills); w.u8(asKills); w.u8(anKills); w.i16(missionsFlown) }
    fun copy() = SquadronPilot(pilotId, skillAndRating, status, aaKills, agKills, asKills, anKills, missionsFlown)
    companion object { fun read(r: LeIn) = SquadronPilot(r.i16(), r.u8(), r.u8(), r.u8(), r.u8(), r.u8(), r.u8(), r.i16()) }
}

/**
 * A squadron: fuel, specialty, ratings, its stores (1,000 bytes: a count per weapon id), 48 pilots, the 16-slot
 * schedule, its airbase (an objective — or a carrier's task force), hot spot, ratings, kill and mission counts,
 * losses, patch, retasking and paint. Its name is in the header ([SquadInfo]).
 */
class CmdSquadron : CmdUnit(UnitKind.SQUADRON) {
    var fuel = 0
    var specialty = 0
    var campSpecificRating = ByteArray(16)
    var stores = ByteArray(1000)
    var pilots: MutableList<SquadronPilot> = ArrayList()
    var schedule = IntArray(16)
    var airbaseId = VuId.NONE
    var hotSpot = VuId.NONE
    var rating = ByteArray(16)
    var aaKills = 0
    var agKills = 0
    var asKills = 0
    var anKills = 0
    var missionsFlown = 0
    var missionScore = 0
    var totalLosses = 0
    var pilotLosses = 0
    var squadronPatch = 0
    var retaskAt = 0L
    var vehRelocate = 0
    var texSet = 0

    /** How many of weapon [id] the squadron holds (0-255). */
    fun store(id: Int): Int = if (id in stores.indices) stores[id].toInt() and 0xFF else 0

    override fun readBody(r: LeIn) {
        fuel = r.i32(); specialty = r.u8()
        if (ver >= 101) campSpecificRating = r.bytes(16)
        stores = r.bytes(1000)
        pilots = MutableList(48) { SquadronPilot.read(r) }
        schedule = r.ints32(16)
        airbaseId = r.id(); hotSpot = r.id(); rating = r.bytes(16)
        aaKills = r.i16(); agKills = r.i16(); asKills = r.i16(); anKills = r.i16(); missionsFlown = r.i16(); missionScore = r.i16()
        totalLosses = r.u8(); pilotLosses = r.u8(); squadronPatch = r.u16()
        if (ver >= 102) { retaskAt = r.u32(); vehRelocate = r.u8() }
        if (ver >= 105) texSet = r.i32()
    }

    override fun writeBody(w: LeOut) {
        w.i32(fuel); w.u8(specialty)
        if (ver >= 101) w.bytes(campSpecificRating)
        w.bytes(stores)
        for (i in 0 until 48) (pilots.getOrNull(i) ?: SquadronPilot(0, 0, 0, 0, 0, 0, 0, 0)).write(w)
        w.ints32(schedule)
        w.id(airbaseId); w.id(hotSpot); w.bytes(rating)
        w.i16(aaKills); w.i16(agKills); w.i16(asKills); w.i16(anKills); w.i16(missionsFlown); w.i16(missionScore)
        w.u8(totalLosses); w.u8(pilotLosses); w.u16(squadronPatch)
        if (ver >= 102) { w.u32(retaskAt); w.u8(vehRelocate) }
        if (ver >= 105) w.i32(texSet)
    }
}

/** What a land unit adds to every unit: orders, division and the objective it is ordered to. */
sealed class CmdGroundUnit(kind: UnitKind) : CmdUnit(kind) {
    var orders = 0
    var division = 0
    var aobj = VuId.NONE
    protected fun readGround(r: LeIn) { orders = r.u8(); division = r.i16(); aobj = r.id() }
    protected fun writeGround(w: LeOut) { w.u8(orders); w.i16(division); w.id(aobj) }
}

/** A battalion: times, parent brigade, last objective, supply, fatigue, morale, heading, final heading, position. */
class CmdBattalion : CmdGroundUnit(UnitKind.BATTALION) {
    var lastMove = 0L
    var lastCombat = 0L
    var parentId = VuId.NONE
    var lastObj = VuId.NONE
    var supply = 0
    var fatigue = 0
    var morale = 0
    var heading = 0
    var finalHeading = 0
    var position = 0

    override fun readBody(r: LeIn) {
        readGround(r)
        lastMove = r.u32(); lastCombat = r.u32(); parentId = r.id(); lastObj = r.id()
        supply = r.u8(); fatigue = r.u8(); morale = r.u8(); heading = r.u8(); finalHeading = r.u8(); position = r.u8()
    }

    override fun writeBody(w: LeOut) {
        writeGround(w)
        w.u32(lastMove); w.u32(lastCombat); w.id(parentId); w.id(lastObj)
        w.u8(supply); w.u8(fatigue); w.u8(morale); w.u8(heading); w.u8(finalHeading); w.u8(position)
    }
}

/** A brigade: its battalions ([elements]). */
class CmdBrigade : CmdGroundUnit(UnitKind.BRIGADE) {
    var elements: MutableList<VuId> = ArrayList()
    override fun readBody(r: LeIn) { readGround(r); elements = MutableList(r.u8()) { r.id() } }
    override fun writeBody(w: LeOut) { writeGround(w); w.u8(elements.size); elements.forEach { w.id(it) } }
}

/** A naval task force: orders and supply. */
class CmdTaskForce : CmdUnit(UnitKind.TASKFORCE) {
    var orders = 0
    var supply = 0
    override fun readBody(r: LeIn) { orders = r.u8(); supply = r.u8() }
    override fun writeBody(w: LeOut) { w.u8(orders); w.u8(supply) }
}

/**
 * The units part: the records ([units]) and whatever follows the last one the walk read ([tail], empty when the walk
 * is exact). [stop] says why a walk ended early; then the part is kept as stored and nothing in it may be edited.
 */
class UnitPart(var units: MutableList<CmdUnit>, var tail: ByteArray, val stop: String?, val headerCount: Int) {
    val editable: Boolean get() = stop == null && tail.isEmpty()
    val count: Int get() = units.size

    fun encode(): ByteArray {
        val w = LeOut(units.size * 300 + tail.size)
        units.forEach { it.write(w) }
        w.bytes(tail)
        return w.toByteArray()
    }

    companion object {
        /** Re-reads one record's bytes as [kind] (for [CmdUnit.copy]). */
        internal fun decodeOne(kind: UnitKind, bytes: ByteArray, ver: Int): CmdUnit = CmdUnit.readAs(kind, LeIn(bytes), ver)

        /**
         * Walks [body] (the unpacked part) for [count] records, asking [kindOf] what each type is. Never throws: a type
         * the class table does not know, or a record that runs out, ends the walk with a reason and the rest is
         * kept as [tail].
         */
        fun read(body: ByteArray, count: Int, ver: Int, kindOf: (Int) -> UnitKind?): UnitPart {
            val r = LeIn(body)
            val out = ArrayList<CmdUnit>(maxOf(0, count))
            var stop: String? = null
            while (out.size < count && r.p < body.size) {
                val start = r.p
                try {
                    val type = LeIn(body, start).i16()
                    val kind = kindOf(type)
                    if (kind == null) { stop = "unit type $type at byte $start is not a unit in the class table"; break }
                    val u = CmdUnit.readAs(kind, r, ver)
                    u.raw = body.copyOfRange(start, r.p)
                    out += u
                } catch (e: Throwable) {
                    stop = "the record at byte $start could not be read: ${e.message ?: e.javaClass.simpleName}"
                    r.p = start
                    break
                }
            }
            if (stop == null && out.size < count) stop = "the part ended after ${out.size} of $count records"
            return UnitPart(out, body.copyOfRange(r.p, body.size), stop, count)
        }
    }
}
