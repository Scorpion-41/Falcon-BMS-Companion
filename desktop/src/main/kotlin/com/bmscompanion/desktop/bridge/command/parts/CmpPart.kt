package com.bmscompanion.desktop.bridge.command

/**
 * The campaign header (`.cmp`, unpacked), every field. Times are campaign milliseconds (day 1 00:00 = 0). The fields
 * after the squadron table — tempo, the creator's address, the creation time and number — are read when the bytes are
 * there, and whatever follows them ([tail]: 4 bytes, the campaign period's first and last year, in every save
 * Falcon BMS 4.38.1 writes, whatever its `.ver` says) is kept by the bytes present.
 */
class CmpHeader {
    var currentTime = 0L
    var startTime = 0L
    var timeLimit = 0L
    var teVictoryPoints = 0
    var teType = 0
    var teNumberTeams = 0
    /** per team: the TE's aircraft and its F-16s */
    var teNumberAircraft = IntArray(8)
    var teNumberF16s = IntArray(8)
    var teTeam = 0
    var teTeamPoints = IntArray(8)
    var teFlags = 0
    /** the eight teams' flag, colour, name and motto as the header has them */
    var teams: MutableList<TeamBasicInfo> = ArrayList()
    var lastMajorEvent = 0L
    var lastResupply = 0L
    var lastRepair = 0L
    var lastReinforcement = 0L
    var timeStamp = 0
    var group = 0
    var groundRatio = 0
    var airRatio = 0
    var airDefenseRatio = 0
    var navalRatio = 0
    var brief = 0
    var theaterSizeX = 0
    var theaterSizeY = 0
    var currentDay = 0
    var activeTeam = 0
    var dayZero = 0
    var endgameResult = 0
    var situation = 0
    var enemyAirExp = 0
    var enemyADExp = 0
    var bullseyeName = 0
    /** the bullseye's grid cell: x east, y north */
    var bullseyeX = 0
    var bullseyeY = 0
    var theaterName = FixedText.of(40)
    /** the start this save takes its objectives from ("save1", "te_new") */
    var scenario = FixedText.of(40)
    /** the name BMS saves under: the parts' names and the mission file `<SaveFile>.ini` */
    var saveFile = FixedText.of(40)
    var uiName = FixedText.of(40)
    var playerSquadronId = VuId.NONE
    var recentEvents: MutableList<CampEvent> = ArrayList()
    var priorityEvents: MutableList<CampEvent> = ArrayList()
    /** the occupation map (ownership grid), as stored */
    var campMap = ByteArray(0)
    var lastIndexNum = 0
    var squadInfo: MutableList<SquadInfo> = ArrayList()
    /** tempo, creator's address, creation time and number: present when [hasCreation] */
    var hasCreation = false
    var tempo = 0
    var creatorIp = 0L
    var creationTime = 0L
    var creationRand = 0L
    /** everything after the creation fields (the campaign period, 4 bytes, in 4.38.1) */
    var tail = ByteArray(0)

    /** The campaign period's first and last year, when the tail carries them (0/9999 in a campaign, 1945/2100 in a TE). */
    var campPeriodStart: Int?
        get() = if (tail.size >= 4) LeIn(tail).i16() else null
        set(v) { if (v != null && tail.size >= 4) { tail = tail.copyOf(); tail[0] = v.toByte(); tail[1] = (v shr 8).toByte() } }
    var campPeriodEnd: Int?
        get() = if (tail.size >= 4) LeIn(tail, 2).i16() else null
        set(v) { if (v != null && tail.size >= 4) { tail = tail.copyOf(); tail[2] = v.toByte(); tail[3] = (v shr 8).toByte() } }

    fun squadInfoOf(id: VuId): SquadInfo? = squadInfo.firstOrNull { it.id == id }

    fun encode(): ByteArray {
        val w = LeOut(16384)
        w.u32(currentTime); w.u32(startTime); w.u32(timeLimit)
        w.i32(teVictoryPoints); w.i32(teType); w.i32(teNumberTeams)
        w.ints32(teNumberAircraft); w.ints32(teNumberF16s)
        w.i32(teTeam)
        w.ints32(teTeamPoints)
        w.i32(teFlags)
        teams.forEach { it.write(w) }
        w.u32(lastMajorEvent); w.u32(lastResupply); w.u32(lastRepair); w.u32(lastReinforcement)
        w.i16(timeStamp); w.i16(group); w.i16(groundRatio); w.i16(airRatio); w.i16(airDefenseRatio); w.i16(navalRatio)
        w.i16(brief); w.i16(theaterSizeX); w.i16(theaterSizeY)
        w.u8(currentDay); w.u8(activeTeam); w.u8(dayZero); w.u8(endgameResult); w.u8(situation)
        w.u8(enemyAirExp); w.u8(enemyADExp); w.u8(bullseyeName)
        w.i16(bullseyeX); w.i16(bullseyeY)
        w.text(theaterName); w.text(scenario); w.text(saveFile); w.text(uiName)
        w.id(playerSquadronId)
        w.u16(recentEvents.size); recentEvents.forEach { it.write(w) }
        w.u16(priorityEvents.size); priorityEvents.forEach { it.write(w) }
        w.u16(campMap.size); w.bytes(campMap)
        w.i16(lastIndexNum)
        w.u16(squadInfo.size); squadInfo.forEach { it.write(w) }
        if (hasCreation) { w.u8(tempo); w.u32(creatorIp); w.u32(creationTime); w.u32(creationRand) }
        w.bytes(tail)
        return w.toByteArray()
    }

    companion object {
        /** Reads the whole header; throws [ShortRead] when the bytes run out (the caller keeps the part as it is). */
        fun read(b: ByteArray): CmpHeader {
            val r = LeIn(b)
            val h = CmpHeader()
            h.currentTime = r.u32(); h.startTime = r.u32(); h.timeLimit = r.u32()
            h.teVictoryPoints = r.i32(); h.teType = r.i32(); h.teNumberTeams = r.i32()
            h.teNumberAircraft = r.ints32(8); h.teNumberF16s = r.ints32(8)
            h.teTeam = r.i32()
            h.teTeamPoints = r.ints32(8)
            h.teFlags = r.i32()
            h.teams = MutableList(8) { TeamBasicInfo.read(r) }
            h.lastMajorEvent = r.u32(); h.lastResupply = r.u32(); h.lastRepair = r.u32(); h.lastReinforcement = r.u32()
            h.timeStamp = r.i16(); h.group = r.i16(); h.groundRatio = r.i16(); h.airRatio = r.i16(); h.airDefenseRatio = r.i16(); h.navalRatio = r.i16()
            h.brief = r.i16(); h.theaterSizeX = r.i16(); h.theaterSizeY = r.i16()
            h.currentDay = r.u8(); h.activeTeam = r.u8(); h.dayZero = r.u8(); h.endgameResult = r.u8(); h.situation = r.u8()
            h.enemyAirExp = r.u8(); h.enemyADExp = r.u8(); h.bullseyeName = r.u8()
            h.bullseyeX = r.i16(); h.bullseyeY = r.i16()
            h.theaterName = r.text(40); h.scenario = r.text(40); h.saveFile = r.text(40); h.uiName = r.text(40)
            h.playerSquadronId = r.id()
            h.recentEvents = MutableList(r.u16()) { CampEvent.read(r) }
            h.priorityEvents = MutableList(r.u16()) { CampEvent.read(r) }
            h.campMap = r.bytes(r.u16())
            h.lastIndexNum = r.i16()
            h.squadInfo = MutableList(r.u16()) { SquadInfo.read(r) }
            if (r.left >= 13) {
                h.hasCreation = true
                h.tempo = r.u8(); h.creatorIp = r.u32(); h.creationTime = r.u32(); h.creationRand = r.u32()
            }
            h.tail = r.bytes(r.left)
            return h
        }
    }
}

/** A team as the header lists it: flag picture, colour, name (20) and motto (200). */
class TeamBasicInfo(var flag: Int, var color: Int, var name: FixedText, var motto: FixedText) {
    fun write(w: LeOut) { w.u8(flag); w.u8(color); w.text(name); w.text(motto) }
    companion object {
        fun read(r: LeIn) = TeamBasicInfo(r.u8(), r.u8(), r.text(20), r.text(200))
    }
}

/** A news event of the campaign (recent or priority): place (cell), time, flags, team, and its text. */
class CampEvent(
    var x: Int, var y: Int, var time: Long, var flags: Int, var team: Int,
    var pad: ByteArray, var textSkip: Int, var uiNode: Int, var text: ByteArray,
) {
    var textString: String
        get() = String(text, ANSI).trimEnd('\u0000')
        set(v) { text = v.toByteArray(ANSI) }

    fun write(w: LeOut) {
        w.i16(x); w.i16(y); w.u32(time); w.u8(flags); w.u8(team); w.bytes(pad); w.i32(textSkip); w.i32(uiNode)
        w.u16(text.size); w.bytes(text)
    }

    companion object {
        fun read(r: LeIn) = CampEvent(r.i16(), r.i16(), r.u32(), r.u8(), r.u8(), r.bytes(2), r.i32(), r.i32(), r.bytes(r.u16()))
    }
}

/**
 * A squadron as the header lists it (the units part does not carry its name): position in feet ([x] north, [y]
 * east — the sim's axes), id, class (type − 100), name number, the airbase's icon and name, patch, specialty,
 * strength, country, flags (its unit flags), CampId, paint and name. 196 bytes.
 */
class SquadInfo(
    var xBits: Int, var yBits: Int, var id: VuId,
    var descriptionIndex: Int, var nameId: Int, var airbaseIcon: Int, var squadronPath: Int,
    var specialty: Int, var currentStrength: Int, var country: Int,
    var airbaseName: FixedText, var padding: Int, var flags: Int, var campId: Int, var texSet: Int,
    var squadronName: FixedText,
) {
    var x: Float get() = fl(xBits); set(v) { xBits = v.bits() }
    var y: Float get() = fl(yBits); set(v) { yBits = v.bits() }

    fun write(w: LeOut) {
        w.i32(xBits); w.i32(yBits); w.id(id)
        w.i16(descriptionIndex); w.i16(nameId); w.i16(airbaseIcon); w.i16(squadronPath)
        w.u8(specialty); w.u8(currentStrength); w.u8(country)
        w.text(airbaseName); w.u8(padding); w.i32(flags); w.i16(campId); w.i16(texSet); w.text(squadronName)
    }

    fun copy() = SquadInfo(xBits, yBits, id, descriptionIndex, nameId, airbaseIcon, squadronPath, specialty, currentStrength, country,
        airbaseName.copy(), padding, flags, campId, texSet, squadronName.copy())

    companion object {
        const val SIZE = 196
        fun read(r: LeIn) = SquadInfo(
            r.i32(), r.i32(), r.id(), r.i16(), r.i16(), r.i16(), r.i16(), r.u8(), r.u8(), r.u8(),
            r.text(80), r.u8(), r.i32(), r.i16(), r.i16(), r.text(80),
        )
    }
}
