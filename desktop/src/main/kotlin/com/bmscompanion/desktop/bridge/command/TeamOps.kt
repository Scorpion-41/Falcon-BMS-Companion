package com.bmscompanion.desktop.bridge.command

/**
 * The Command section's team edits on an open save, as undoable [CommandEdit]s: the team part (`.tea`: the team
 * record and its three tasking managers) and the header's own copy of each team's flag, colour, name and motto
 * (`.cmp` [TeamBasicInfo]), which BMS's screens read — a name or a colour is written to both.
 */
object TeamOps {
    val STANCES = listOf("None", "Allied", "Friendly", "Neutral", "Hostile", "At war")
    val GROUND_ACTIONS = listOf("None", "Defensive", "Consolidate", "Minor offensive", "Offensive")
    val AIR_ACTIONS = listOf("Nothing", "DCA", "OCA", "Interdict", "Attrition", "CAS")
    val TEAM_FLAGS = listOf(0x1 to "Team active", 0x2 to "Updated", 0x4 to "Has satellites", 0x8 to "A member has satellites", 0x10 to "Player's priorities")

    fun entry(doc: CommandSave, n: Int): TeamEntry? = doc.tea?.teams?.getOrNull(n)

    /** Team [n]'s stance toward team [other] (0 none … 5 at war). */
    fun stance(doc: CommandSave, n: Int, other: Int, value: Int): CommandEdit? {
        val t = entry(doc, n)?.team ?: return null
        if (other !in t.stance.indices) return null
        val v = t.stance.copyOf().also { it[other] = value }
        return Edits.set("${doc.teamName(n)} toward ${doc.teamName(other)}: ${STANCES.getOrElse(value) { "$value" }}", "tea", { t.stance }, { t.stance = it }, v)
    }

    /** Both teams' stances toward each other. */
    fun mutualStance(doc: CommandSave, a: Int, b: Int, value: Int): CommandEdit? {
        val e1 = stance(doc, a, b, value) ?: return null
        val e2 = stance(doc, b, a, value) ?: return e1
        return Edits.group("${doc.teamName(a)} and ${doc.teamName(b)}: ${STANCES.getOrElse(value) { "$value" }}", listOf(e1, e2))
    }

    /** The team controlling team [n] (BMS's "part of team"). */
    fun controller(doc: CommandSave, n: Int, c: Int): CommandEdit? {
        val t = entry(doc, n)?.team ?: return null
        return Edits.set("${doc.teamName(n)} controlled by ${doc.teamName(c)}", "tea", { t.cteam }, { t.cteam = it }, c)
    }

    /** The team's name in the team part and the header. */
    fun rename(doc: CommandSave, n: Int, name: String): CommandEdit? {
        val t = entry(doc, n)?.team
        val h = doc.cmp?.teams?.getOrNull(n)
        val label = "Name of team $n: $name"
        val edits = listOfNotNull(
            t?.let { tt -> Edits.set(label, "tea", { tt.name }, { tt.name = it }, tt.name.copy().also { it.text = name.take(19) }) },
            h?.let { hh -> Edits.set(label, "cmp", { hh.name }, { hh.name = it }, hh.name.copy().also { it.text = name.take(19) }) },
        )
        return if (edits.isEmpty()) null else Edits.group(label, edits)
    }

    fun motto(doc: CommandSave, n: Int, text: String): CommandEdit? {
        val t = entry(doc, n)?.team
        val h = doc.cmp?.teams?.getOrNull(n)
        val label = "Motto of ${doc.teamName(n)}"
        val edits = listOfNotNull(
            t?.let { tt -> Edits.set(label, "tea", { tt.motto }, { tt.motto = it }, tt.motto.copy().also { it.text = text.take(199) }) },
            h?.let { hh -> Edits.set(label, "cmp", { hh.motto }, { hh.motto = it }, hh.motto.copy().also { it.text = text.take(199) }) },
        )
        return if (edits.isEmpty()) null else Edits.group(label, edits)
    }

    /** BMS's team colour index (0 white … 7 grey): in both copies. */
    fun colour(doc: CommandSave, n: Int, c: Int): CommandEdit? {
        val t = entry(doc, n)?.team
        val h = doc.cmp?.teams?.getOrNull(n)
        val label = "Colour of ${doc.teamName(n)}"
        val edits = listOfNotNull(
            t?.let { tt -> Edits.set(label, "tea", { tt.teamColor }, { tt.teamColor = it }, c) },
            h?.let { hh -> Edits.set(label, "cmp", { hh.color }, { hh.color = it }, c) },
        )
        return if (edits.isEmpty()) null else Edits.group(label, edits)
    }

    fun flag(doc: CommandSave, n: Int, f: Int): CommandEdit? {
        val t = entry(doc, n)?.team
        val h = doc.cmp?.teams?.getOrNull(n)
        val label = "Flag of ${doc.teamName(n)}"
        val edits = listOfNotNull(
            t?.let { tt -> Edits.set(label, "tea", { tt.teamFlag }, { tt.teamFlag = it }, f) },
            h?.let { hh -> Edits.set(label, "cmp", { hh.flag }, { hh.flag = it }, f) },
        )
        return if (edits.isEmpty()) null else Edits.group(label, edits)
    }

    /** One number of a team record. */
    fun <T> field(doc: CommandSave, n: Int, what: String, get: (CmdTeam) -> T, set: (CmdTeam, T) -> Unit, value: T): CommandEdit? {
        val t = entry(doc, n)?.team ?: return null
        return Edits.set("$what of ${doc.teamName(n)}", "tea", { get(t) }, { set(t, it) }, value)
    }

    /** One element of a priority table ([which] "objective", "unit" or "mission"). */
    fun priority(doc: CommandSave, n: Int, which: String, i: Int, v: Int): CommandEdit? {
        val t = entry(doc, n)?.team ?: return null
        val (get, set) = when (which) {
            "objective" -> Pair({ t.objtypePriority }, { a: IntArray -> t.objtypePriority = a })
            "unit" -> Pair({ t.unittypePriority }, { a: IntArray -> t.unittypePriority = a })
            else -> Pair({ t.missionPriority }, { a: IntArray -> t.missionPriority = a })
        }
        val cur = get()
        if (i !in cur.indices) return null
        return Edits.set("${which.replaceFirstChar { it.uppercase() }} priority ${i} of ${doc.teamName(n)}", "tea", get, set, cur.copyOf().also { it[i] = v.coerceIn(0, 255) })
    }

    /** Every mission request of team [n]'s air tasking manager removed ([all]: of every team). */
    fun clearRequests(doc: CommandSave, n: Int, all: Boolean = false): CommandEdit {
        val label = if (all) "Delete every team's air tasks" else "Delete the air tasks of ${doc.teamName(n)}"
        val teams = if (all) doc.teams else listOfNotNull(entry(doc, n))
        return Edits.group(label, teams.flatMap { e -> e.air.requests.toList().map { Edits.remove(label, "tea", e.air.requests, it) } })
    }

    fun removeRequest(doc: CommandSave, n: Int, r: MissionRequest): CommandEdit? {
        val e = entry(doc, n) ?: return null
        return Edits.remove("Delete an air task of ${doc.teamName(n)}", "tea", e.air.requests, r)
    }

    /** Every airbase schedule of team [n]'s air tasking manager removed ([all]: of every team); BMS plans them again. */
    fun clearSchedules(doc: CommandSave, n: Int, all: Boolean = false): CommandEdit {
        val label = if (all) "Delete every team's airbase schedules" else "Delete the airbase schedules of ${doc.teamName(n)}"
        val teams = if (all) doc.teams else listOfNotNull(entry(doc, n))
        return Edits.group(label, teams.flatMap { e -> e.air.airbases.toList().map { Edits.remove(label, "tea", e.air.airbases, it) } })
    }

    fun removeSchedule(doc: CommandSave, n: Int, a: AtmAirbase): CommandEdit? {
        val e = entry(doc, n) ?: return null
        return Edits.remove("Delete an airbase schedule of ${doc.teamName(n)}", "tea", e.air.airbases, a)
    }

    // ---------------------------------------------------------------- adding and deleting

    /** Why a team cannot be added, or null. */
    fun addRefusal(doc: CommandSave): String? = when {
        doc.tea == null -> "${doc.file.name} has no team part."
        !doc.tea.editable -> "The team part could not be read exactly."
        doc.teams.size >= 8 -> "A campaign has at most eight teams; ${doc.file.name} has eight."
        doc.teams.isEmpty() -> "There is no team to model the new one on."
        else -> null
    }

    /**
     * A new team after the last: a copy of the last team's record and managers with the new number, its own control,
     * ids continuing the teams' own run (four a team: the team and its three managers), [name] and [colour], neutral
     * toward every team and every team neutral toward it, no tasks, schedules, bonus objectives or actions.
     */
    fun add(doc: CommandSave, name: String, colour: Int): CommandEdit {
        val why = addRefusal(doc)
        if (why != null) return Edits.failing("Add a team", "tea", why)
        val tea = doc.tea!!
        val n = tea.teams.size
        val last = tea.teams.last()
        val buf = LeOut(2000).also { last.write(it) }.toByteArray()
        val r = LeIn(buf)
        val e = TeamEntry(CmdTeam.read(r), AirTaskingManager.read(r), SimpleTaskingManager.read(r), SimpleTaskingManager.read(r))
        val step = (last.team.id.num - (tea.teams.getOrNull(n - 2)?.team?.id?.num ?: (last.team.id.num - 4))).takeIf { it in 4L..8L } ?: 4L
        var base = last.team.id.num + step
        val used = (doc.units.map { it.id.num } + doc.objectives.map { it.id.num } + tea.teams.flatMap { listOf(it.team.id.num, it.air.base.id.num, it.ground.base.id.num, it.naval.base.id.num) }).toHashSet()
        while ((0 until 4).any { base + it in used }) base += 4
        val t = e.team
        t.id = VuId(base, last.team.id.creator); t.who = n; t.cteam = n
        t.member = IntArray(8).also { it[n] = 1 }
        t.stance = IntArray(8) { if (it == n) 1 else 3 }
        t.name = t.name.copy().also { it.text = name.take(19) }
        t.teamColor = colour
        t.bonusObjs = MutableList(20) { VuId.NONE }; t.bonusTime = LongArray(20)
        t.groundAction = GroundActionRec(0, 0, VuId.NONE, 0, 0, 0)
        t.defensiveAir = AirActionRec(0, 0, VuId.NONE, VuId.NONE, 0, ByteArray(3))
        t.offensiveAir = AirActionRec(0, 0, VuId.NONE, VuId.NONE, 0, ByteArray(3))
        e.air.base.id = VuId(base + 1, last.team.id.creator); e.air.base.owner = n
        e.air.airbases = ArrayList(); e.air.requests = ArrayList()
        e.ground.base.id = VuId(base + 2, last.team.id.creator); e.ground.base.owner = n
        e.naval.base.id = VuId(base + 3, last.team.id.creator); e.naval.base.owner = n
        val label = "Add team $n: $name"
        val edits = ArrayList<CommandEdit>()
        edits += Edits.insert(label, "tea", tea.teams, e)
        for (o in tea.teams) edits += Edits.set(label, "tea", { o.team.stance }, { o.team.stance = it }, o.team.stance.copyOf().also { it[n] = 3 })
        doc.cmp?.teams?.getOrNull(n)?.let { h ->
            edits += Edits.set(label, "cmp", { h.name }, { h.name = it }, h.name.copy().also { it.text = name.take(19) })
            edits += Edits.set(label, "cmp", { h.color }, { h.color = it }, colour)
            edits += Edits.set(label, "cmp", { h.flag }, { h.flag = it }, t.teamFlag)
        }
        return Edits.group(label, edits)
    }

    /** Why team [n] cannot be deleted, or null: only the last team, and only once it holds nothing. */
    fun deleteRefusal(doc: CommandSave, n: Int): String? {
        val tea = doc.tea ?: return "${doc.file.name} has no team part."
        if (!tea.editable) return "The team part could not be read exactly."
        if (n == 0) return "Team 0 is BMS's own neutral team: it stays."
        if (n != tea.teams.size - 1) return "Only the last team (${doc.teamName(tea.teams.size - 1)}) can be deleted: every other team's number is used by units, objectives and stances."
        val units = doc.units.count { it.owner == n }
        val objs = doc.objectives.count { doc.ownerNow(it) == n || it.firstOwner == n }
        if (units > 0 || objs > 0) return "${doc.teamName(n)} still holds $units unit${if (units == 1) "" else "s"} and $objs objective${if (objs == 1) "" else "s"}: give them to another team first."
        if (tea.teams.any { it.team.who != n && it.team.cteam == n }) return "Another team is controlled by ${doc.teamName(n)}: change its controller first."
        return null
    }

    fun delete(doc: CommandSave, n: Int): CommandEdit {
        deleteRefusal(doc, n)?.let { return Edits.failing("Delete team $n", "tea", it) }
        val tea = doc.tea!!
        val label = "Delete team ${doc.teamName(n)}"
        val edits = ArrayList<CommandEdit>()
        for (o in tea.teams) if (o.team.who != n) edits += Edits.set(label, "tea", { o.team.stance }, { o.team.stance = it }, o.team.stance.copyOf().also { it[n] = 0 })
        edits += Edits.remove(label, "tea", tea.teams, tea.teams[n])
        return Edits.group(label, edits)
    }
}
