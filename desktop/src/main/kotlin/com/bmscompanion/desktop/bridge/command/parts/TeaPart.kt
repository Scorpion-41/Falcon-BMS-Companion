package com.bmscompanion.desktop.bridge.command

/** A team's counts (16 bytes): air-defence vehicles, aircraft, ground vehicles, ships, supply, fuel, airbases, levels. */
class TeamStatus(
    var airDefenseVehs: Int, var aircraft: Int, var groundVehs: Int, var ships: Int,
    var supply: Int, var fuel: Int, var airbases: Int, var supplyLevel: Int, var fuelLevel: Int,
) {
    fun write(w: LeOut) {
        w.i16(airDefenseVehs); w.i16(aircraft); w.i16(groundVehs); w.i16(ships); w.i16(supply); w.i16(fuel); w.i16(airbases)
        w.u8(supplyLevel); w.u8(fuelLevel)
    }
    companion object { fun read(r: LeIn) = TeamStatus(r.i16(), r.i16(), r.i16(), r.i16(), r.i16(), r.i16(), r.i16(), r.u8(), r.u8()) }
}

/** A team's ground posture: from [time] to [timeout], toward [objective]; [type] 1 defensive … 4 offensive. */
class GroundActionRec(var time: Long, var timeout: Long, var objective: VuId, var type: Int, var tempo: Int, var points: Int) {
    fun write(w: LeOut) { w.u32(time); w.u32(timeout); w.id(objective); w.u8(type); w.u8(tempo); w.u8(points) }
    companion object { fun read(r: LeIn) = GroundActionRec(r.u32(), r.u32(), r.id(), r.u8(), r.u8(), r.u8()) }
}

/** A team's air operation (28 bytes): start, stop, objective, last objective, type (1 DCA … 5 CAS), padding. */
class AirActionRec(var start: Long, var stop: Long, var objective: VuId, var lastObjective: VuId, var type: Int, var pad: ByteArray) {
    fun write(w: LeOut) { w.u32(start); w.u32(stop); w.id(objective); w.id(lastObjective); w.u8(type); w.bytes(pad) }
    companion object { fun read(r: LeIn) = AirActionRec(r.u32(), r.u32(), r.id(), r.id(), r.u8(), r.bytes(3)) }
}

/**
 * One team of the team part: id, class, its number ([who]) and the team controlling it ([cteam]), flags, members,
 * stance toward each of the eight teams (0 none, 1 allied, 2 friendly, 3 neutral, 4 hostile, 5 war), first
 * colonel/commander/wingmen (pilot numbers), experience, initiative, what is available, player rating, current and
 * start counts, reinforcement, bonus objectives, priorities (objective types 36, unit types 20, missions 50), max
 * vehicles, flag, colour, equipment, name (20), motto (200) and the ground and two air actions.
 */
class CmdTeam {
    var id = VuId.NONE
    var entityType = 0
    var who = 0
    var cteam = 0
    var flags = 0
    var member = IntArray(8)
    var stance = IntArray(8)
    var firstColonel = 0
    var firstCommander = 0
    var firstWingman = 0
    var lastWingman = 0
    var airExperience = 0
    var airDefenseExperience = 0
    var groundExperience = 0
    var navalExperience = 0
    var initiative = 0
    var supplyAvail = 0
    var fuelAvail = 0
    var replacementsAvail = 0
    var playerRatingBits = 0
    var lastPlayerMission = 0L
    var currentStats = TeamStatus(0, 0, 0, 0, 0, 0, 0, 0, 0)
    var startStats = TeamStatus(0, 0, 0, 0, 0, 0, 0, 0, 0)
    var reinforcement = 0
    var bonusObjs: MutableList<VuId> = MutableList(20) { VuId.NONE }
    var bonusTime = LongArray(20)
    var objtypePriority = IntArray(36)
    var unittypePriority = IntArray(20)
    var missionPriority = IntArray(50)
    var maxVehicle = IntArray(4)
    var teamFlag = 0
    var teamColor = 0
    var equipment = 0
    var name = FixedText.of(20)
    var motto = FixedText.of(200)
    var groundAction = GroundActionRec(0, 0, VuId.NONE, 0, 0, 0)
    var defensiveAir = AirActionRec(0, 0, VuId.NONE, VuId.NONE, 0, ByteArray(3))
    var offensiveAir = AirActionRec(0, 0, VuId.NONE, VuId.NONE, 0, ByteArray(3))

    var playerRating: Float get() = fl(playerRatingBits); set(v) { playerRatingBits = v.bits() }

    fun write(w: LeOut) {
        w.id(id); w.u16(entityType); w.u8(who); w.u8(cteam); w.i16(flags)
        w.u8s(member); w.ints16(stance)
        w.i16(firstColonel); w.i16(firstCommander); w.i16(firstWingman); w.i16(lastWingman)
        w.u8(airExperience); w.u8(airDefenseExperience); w.u8(groundExperience); w.u8(navalExperience)
        w.i16(initiative); w.u16(supplyAvail); w.u16(fuelAvail); w.u16(replacementsAvail)
        w.i32(playerRatingBits); w.u32(lastPlayerMission)
        currentStats.write(w); startStats.write(w)
        w.i16(reinforcement)
        bonusObjs.forEach { w.id(it) }
        bonusTime.forEach { w.u32(it) }
        w.u8s(objtypePriority); w.u8s(unittypePriority); w.u8s(missionPriority); w.u8s(maxVehicle)
        w.u8(teamFlag); w.u8(teamColor); w.u8(equipment)
        w.text(name); w.text(motto)
        groundAction.write(w); defensiveAir.write(w); offensiveAir.write(w)
    }

    companion object {
        /** Bytes of a team record before its tasking managers (save version 102 and later). */
        const val SIZE = 748

        fun read(r: LeIn): CmdTeam {
            val t = CmdTeam()
            t.id = r.id(); t.entityType = r.u16(); t.who = r.u8(); t.cteam = r.u8(); t.flags = r.i16()
            t.member = r.u8s(8); t.stance = r.ints16(8)
            t.firstColonel = r.i16(); t.firstCommander = r.i16(); t.firstWingman = r.i16(); t.lastWingman = r.i16()
            t.airExperience = r.u8(); t.airDefenseExperience = r.u8(); t.groundExperience = r.u8(); t.navalExperience = r.u8()
            t.initiative = r.i16(); t.supplyAvail = r.u16(); t.fuelAvail = r.u16(); t.replacementsAvail = r.u16()
            t.playerRatingBits = r.i32(); t.lastPlayerMission = r.u32()
            t.currentStats = TeamStatus.read(r); t.startStats = TeamStatus.read(r)
            t.reinforcement = r.i16()
            t.bonusObjs = MutableList(20) { r.id() }
            t.bonusTime = LongArray(20) { r.u32() }
            t.objtypePriority = r.u8s(36); t.unittypePriority = r.u8s(20); t.missionPriority = r.u8s(50); t.maxVehicle = r.u8s(4)
            t.teamFlag = r.u8(); t.teamColor = r.u8(); t.equipment = r.u8()
            t.name = r.text(20); t.motto = r.text(200)
            t.groundAction = GroundActionRec.read(r)
            t.defensiveAir = AirActionRec.read(r)
            t.offensiveAir = AirActionRec.read(r)
            return t
        }
    }
}

/** The base every tasking manager starts with (13 bytes): id, class, manager flags, owner. */
class ManagerBase(var id: VuId, var entityType: Int, var managerFlags: Int, var owner: Int) {
    fun write(w: LeOut) { w.id(id); w.u16(entityType); w.i16(managerFlags); w.u8(owner) }
    companion object { fun read(r: LeIn) = ManagerBase(r.id(), r.u16(), r.i16(), r.u8()) }
}

/** One airbase the air tasking manager plans from: its id and 32 schedule bytes. */
class AtmAirbase(var id: VuId, var schedule: ByteArray) {
    fun write(w: LeOut) { w.id(id); w.bytes(schedule) }
    companion object { fun read(r: LeIn) = AtmAirbase(r.id(), r.bytes(32)) }
}

/** A team's air tasking manager: flags, average CA strength and missions, sample cycles, airbases, cycle, the queue. */
class AirTaskingManager(
    var base: ManagerBase, var flags: Int, var averageCAStrength: Int, var averageCAMissions: Int, var sampleCycles: Int,
    var airbases: MutableList<AtmAirbase>, var cycle: Int, var requests: MutableList<MissionRequest>,
) {
    fun write(w: LeOut) {
        base.write(w); w.i16(flags); w.i16(averageCAStrength); w.i16(averageCAMissions); w.u8(sampleCycles)
        w.u8(airbases.size); airbases.forEach { it.write(w) }
        w.u8(cycle)
        w.u16(requests.size); requests.forEach { it.write(w) }
    }
    companion object {
        fun read(r: LeIn) = AirTaskingManager(
            ManagerBase.read(r), r.i16(), r.i16(), r.i16(), r.u8(),
            MutableList(r.u8()) { AtmAirbase.read(r) }, r.u8(), MutableList(r.u16()) { MissionRequest.read(r) },
        )
    }
}

/** A team's ground or naval tasking manager: its base and flags. */
class SimpleTaskingManager(var base: ManagerBase, var flags: Int) {
    fun write(w: LeOut) { base.write(w); w.i16(flags) }
    companion object { fun read(r: LeIn) = SimpleTaskingManager(ManagerBase.read(r), r.i16()) }
}

/** A team with its three tasking managers, as the team part stores them one after the other. */
class TeamEntry(var team: CmdTeam, var air: AirTaskingManager, var ground: SimpleTaskingManager, var naval: SimpleTaskingManager) {
    fun write(w: LeOut) { team.write(w); air.write(w); ground.write(w); naval.write(w) }
}

/**
 * The team part (`.tea`, stored as it is): an `i16` count, then per team (at most eight) the team record and its
 * air, ground and naval tasking managers. A full walk from the first byte: no signature search.
 */
class TeaPart(var countField: Int, var teams: MutableList<TeamEntry>, var tail: ByteArray, val stop: String?) {
    val editable: Boolean get() = stop == null && tail.isEmpty()

    fun team(n: Int): TeamEntry? = teams.getOrNull(n)

    fun encode(): ByteArray {
        val w = LeOut(teams.size * 2000 + tail.size)
        w.i16(if (countField in 0..8) teams.size else countField)
        teams.forEach { it.write(w) }
        w.bytes(tail)
        return w.toByteArray()
    }

    companion object {
        fun read(b: ByteArray): TeaPart {
            if (b.size < 2) return TeaPart(0, ArrayList(), b.copyOf(), "the team part is too short")
            val r = LeIn(b)
            val n = r.i16()
            val out = ArrayList<TeamEntry>()
            var stop: String? = null
            for (i in 0 until minOf(maxOf(n, 0), 8)) {
                val start = r.p
                try {
                    out += TeamEntry(CmdTeam.read(r), AirTaskingManager.read(r), SimpleTaskingManager.read(r), SimpleTaskingManager.read(r))
                } catch (e: Throwable) {
                    stop = "team ${i} at byte $start could not be read: ${e.message ?: e.javaClass.simpleName}"
                    r.p = start
                    break
                }
            }
            return TeaPart(n, out, b.copyOfRange(r.p, b.size), stop)
        }
    }
}
