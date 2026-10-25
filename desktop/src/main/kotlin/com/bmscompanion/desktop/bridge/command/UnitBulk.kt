package com.bmscompanion.desktop.bridge.command

/**
 * The unit editor's operations on many units at once, each one undoable step: resupply every squadron, full
 * rosters, spotting reset, a team's units to another team, every squadron or battalion of a team from one class to
 * another, and air-defence battalions on every objective of a kind.
 */
object UnitBulk {
    /** Every squadron's stores as its class's template has them. */
    fun resupplyAll(doc: CommandSave): CommandEdit = UnitEdit.mutate("Resupply every squadron", doc) {
        val cls = UnitClasses.of(doc)
        var n = 0
        for (sq in doc.squadrons) { val t = cls.storesTemplate(sq.type) ?: continue; touch(sq); GroundOps.fillStores(sq, t); n++ }
        if (n == 0) UnitEdit.refuse("no squadron's class names a stores template")
    }

    /** Full rosters for every unit of [kinds] (squadrons: their header rows too). */
    fun fullRosters(doc: CommandSave, kinds: Set<UnitKind>): CommandEdit = UnitEdit.mutate("Full rosters", doc, setOf("uni", "cmp")) {
        val cls = UnitClasses.of(doc)
        for (u in doc.units.filter { it.kind in kinds && it.kind != UnitKind.FLIGHT && it.kind != UnitKind.PACKAGE }) {
            val r = cls.maxRoster(u.type)
            if (r == 0 || r == u.roster) continue
            touch(u); u.roster = r
            if (u is CmdSquadron) GroundOps.syncInfo(this, u)
        }
    }

    /** No unit spotted by anyone (BMS spots them again as the campaign runs). */
    fun resetSpotted(doc: CommandSave): CommandEdit = UnitEdit.mutate("Reset spotting", doc) {
        doc.units.filter { it.spotted != 0 || it.spotTime != 0L }.forEach { touch(it); it.spotted = 0; it.spotTime = 0 }
    }

    /** Every unit of team [from] becomes [to]'s (objectives and team relations are not changed). */
    fun changeTeam(doc: CommandSave, from: Int, to: Int): CommandEdit = UnitEdit.mutate("Units of team $from to team $to", doc, setOf("uni", "cmp")) {
        if (from == to) UnitEdit.refuse("choose two different teams")
        val us = doc.units.filter { it.owner == from }
        if (us.isEmpty()) UnitEdit.refuse("team $from has no units")
        us.forEach { touch(it); it.owner = to; if (it is CmdSquadron) GroundOps.syncInfo(this, it) }
    }

    /**
     * Every squadron (with its flights) or battalion of team [owner] (-1: every team) of class [fromType] becomes
     * [toType], at full strength of it.
     */
    fun changeClassAll(doc: CommandSave, kind: UnitKind, owner: Int, fromType: Int, toType: Int): CommandEdit = UnitEdit.mutate("Change every ${kind.name.lowercase()} of a class", doc, setOf("uni", "cmp")) {
        if (fromType == toType) UnitEdit.refuse("choose two different classes")
        val cls = UnitClasses.of(doc)
        val us = doc.units.filter { it.kind == kind && it.type == fromType && (owner < 0 || it.owner == owner) }
        if (us.isEmpty()) UnitEdit.refuse("no ${kind.name.lowercase()} of that class")
        val ft = if (kind == UnitKind.SQUADRON) cls.flightTypeFor(toType) else null
        for (u in us) {
            touch(u)
            u.type = toType; u.entityType = toType; u.roster = cls.maxRoster(toType)
            if (u is CmdSquadron) {
                cls.storesTemplate(toType)?.let { GroundOps.fillStores(u, it) }
                val fl = doc.flights.filter { it.squadronId == u.id }
                if (fl.isNotEmpty() && ft == null) UnitEdit.refuse("no flight class flies ${cls.leadVcd(toType)?.name ?: "the new aircraft"}, so the squadrons' flights could not follow")
                fl.forEach { f -> touch(f); f.type = ft!!; f.entityType = ft; f.roster = AirOps.flightRoster(cls, ft, f.aircraft.coerceAtLeast(1)); f.loadouts.clear(); f.loadouts += cls.bareLoadout(ft) }
                GroundOps.syncInfo(this, u)
            }
        }
    }

    /** What [addAirDefence] would do: the objectives it places on, and those it leaves out with why. */
    class AdPlan(val place: List<Pair<CmdObjective, Int>>, val skipped: List<Pair<CmdObjective, String>>)

    /**
     * Air-defence battalions of [types] (taken in turn) on every objective of class type [objType] team [owner] holds
     * now that has none sent to it yet; with [siteCheck] an objective whose SAM/AAA spots are fewer than the
     * battalion's vehicles is left out.
     */
    fun planAirDefence(doc: CommandSave, owner: Int, objType: Int, types: List<Int>, siteCheck: Boolean): AdPlan {
        val cls = UnitClasses.of(doc)
        val has = doc.battalions.filter { cls.clsOfType(it.type)?.sub == GroundOps.SUB_AIR_DEFENCE }.map { it.aobj }.toSet()
        val place = ArrayList<Pair<CmdObjective, Int>>()
        val skipped = ArrayList<Pair<CmdObjective, String>>()
        if (types.isEmpty()) return AdPlan(place, skipped)
        doc.objectives.filter { doc.ownerNow(it) == owner && cls.clsOfType(it.type)?.type == objType }.forEach { o ->
            if (o.id in has) { skipped += o to "has an air-defence battalion already"; return@forEach }
            val t = types[place.size % types.size]
            val why = if (siteCheck) GroundOps.siteShortfall(doc, t, o) else null
            if (why != null) skipped += o to why else place += o to t
        }
        return AdPlan(place, skipped)
    }

    fun addAirDefence(doc: CommandSave, owner: Int, plan: AdPlan): CommandEdit {
        if (plan.place.isEmpty()) return Edits.failing("Air defence", "uni", "no objective to place on")
        return Edits.group("Air defence on ${plan.place.size} objectives", plan.place.map { (o, t) ->
            val (x, y) = GroundOps.objCell(o)
            UnitLife.addBattalion(doc, t, owner, x, y, o, 9)
        })
    }
}

/**
 * What the unit records hold that BMS would trip over or that contradicts itself, each with its Fix: an undoable edit.
 * The Checks page lists them ([run]); every fix is its own edit, and [fixAll] ties them into one.
 */
object UnitChecks {
    enum class Severity { ERROR, WARNING }

    class Issue(val severity: Severity, val unit: VuId?, val text: String, val fixLabel: String?, val fix: ((CommandSave) -> CommandEdit)?) {
        /** the Checks page's group */
        var group = "Units"
        /** the Checks page finds this already ([CommandChecks]'s own checks): not handed to it again */
        var shared = false
    }

    class Tag(val group: String, val shared: Boolean)
    private fun tag(group: String, shared: Boolean) = Tag(group, shared)
    private operator fun Tag.plus(i: Issue): Issue = i.also { it.group = group; it.shared = shared }

    /**
     * Hands the unit editor's findings to the Checks page ([CommandChecks.providers]) — those its own checks do not
     * already make — each with its fix.
     */
    fun install() {
        if (installed) return
        installed = true
        CommandChecks.providers += { doc ->
            run(doc).filter { !it.shared }.map { i ->
                val u = i.unit?.let { doc.unit(it) }
                val kind = when (u?.kind) {
                    UnitKind.FLIGHT -> "flight"; UnitKind.PACKAGE -> "package"; UnitKind.SQUADRON -> "squadron"
                    UnitKind.BATTALION -> "battalion"; UnitKind.BRIGADE -> "brigade"; UnitKind.TASKFORCE -> "taskforce"; null -> null
                }
                CheckIssue(i.group, if (i.severity == Severity.ERROR) CheckSeverity.ERROR else CheckSeverity.WARNING, i.text,
                    if (kind != null && i.unit != null) CheckRef(kind, i.unit.toString()) else null, i.fixLabel, i.fix?.let { f -> { f(doc) } })
            }
        }
    }
    @Volatile private var installed = false

    fun run(doc: CommandSave): List<Issue> {
        val out = ArrayList<Issue>()
        if (doc.uni?.editable != true) return out
        val ids = doc.units.associateBy { it.id }
        val objIds = doc.objectives.mapTo(HashSet()) { it.id }
        fun name(u: CmdUnit) = AirOps.nameOf(doc, u)
        val cls = UnitClasses.of(doc)

        // packages
        for (p in doc.packages) {
            if (p.elements.size > 5) out += tag("Packages", false) + Issue(Severity.ERROR, p.id, "Package ${p.campId} has ${p.elements.size} flights; BMS takes five.", "Give the extra flights packages of their own") { d ->
                Edits.group("Split package ${p.campId}", doc.flightsOf(p).drop(5).map { AirOps.ownPackage(d, it) })
            }
            val missing = p.elements.filter { ids[it] !is CmdFlight }
            if (missing.isNotEmpty()) out += tag("Packages", true) + Issue(Severity.ERROR, p.id, "Package ${p.campId} names ${missing.size} flight${if (missing.size == 1) "" else "s"} not in the file.", "Take them out of the package") { d ->
                UnitEdit.mutate("Tidy package ${p.campId}", d) { touch(p); p.elements.removeAll { ids[it] !is CmdFlight }; if (p.elements.isEmpty()) units.remove(p) }
            }
            if (p.elements.isEmpty()) out += tag("Packages", false) + Issue(Severity.WARNING, p.id, "Package ${p.campId} has no flights.", "Delete it") { d -> UnitLife.delete(d, p) }
        }
        // flights
        val seenCall = HashMap<Pair<Int, Int>, CmdFlight>()
        for (f in doc.flights) {
            val p = ids[f.packageId] as? CmdPackage
            if (p == null) out += tag("Packages", false) + Issue(Severity.ERROR, f.id, "${name(f)} is in no package (BMS keeps every flight in one).", "Give it a package of its own") { d -> AirOps.ownPackage(d, f) }
            else if (f.id !in p.elements) out += tag("Packages", false) + Issue(Severity.ERROR, f.id, "${name(f)} names package ${p.campId}, which does not list it.", "Put it in that package's list") { d ->
                if (p.elements.size >= 5) AirOps.ownPackage(d, f) else UnitEdit.mutate("List ${name(f)} in package ${p.campId}", d) { touch(p); p.elements += f.id }
            }
            if (ids[f.squadronId] !is CmdSquadron) {
                val other = doc.squadrons.firstOrNull { cls.flightTypeFor(it.type) == f.type && it.owner == f.owner }
                out += tag("References", true) + Issue(Severity.ERROR, f.id, "${name(f)} names no squadron of the file.", other?.let { "Give it to ${name(it)}" }, other?.let { o -> { d -> AirOps.field(d, f, "Squadron of ${name(f)}") { squadronId = o.id } } })
            }
            val key = f.callsignId to f.callsignNum
            seenCall[key]?.let { first ->
                out += tag("Callsigns", false) + Issue(Severity.WARNING, f.id, "${name(f)} and another flight share the callsign ${doc.callsign(first)}.", "Give it a free number") { d ->
                    val (id, num) = UnitLife.freeCallsign(d, f.type, prefer = f.callsignId) ?: (f.callsignId to f.callsignNum)
                    AirOps.setCallsign(d, f, id, num)
                }
            } ?: run { seenCall[key] = f }
            val plt = doc.plt
            if (plt != null && f.callsignId in plt.callsigns.indices && f.callsignNum in 1..8 && plt.callsigns[f.callsignId] and (1 shl (f.callsignNum - 1)) == 0)
                out += tag("Callsigns", true) + Issue(Severity.WARNING, f.id, "${doc.callsign(f)} is flown but not marked in use in the pilots' part.", "Mark every flown callsign") { d ->
                    UnitEdit.mutate("Mark the callsigns in use", d, setOf("plt")) { val pl = plt; touch(pl); d.flights.forEach { x -> if (x.callsignId in pl.callsigns.indices && x.callsignNum in 1..8) pl.callsigns[x.callsignId] = pl.callsigns[x.callsignId] or (1 shl (x.callsignNum - 1)) } }
                }
            val lp = AirOps.loadoutProblems(doc, f)
            if (lp.isNotEmpty()) out += tag("Loadouts", false) + Issue(Severity.WARNING, f.id, "${name(f)}: ${lp.first()}${if (lp.size > 1) " (+${lp.size - 1} more)" else ""}.", "Take those stores off") { d ->
                UnitEdit.mutate("Legal loadout for ${name(f)}", d) {
                    touch(f)
                    val hps = cls.hardpoints(f.type)
                    f.loadouts.forEach { l -> for (k in 0 until 16) { val id = l.weapons[k]; if (id > 0 && l.counts[k] > 0) { val m = hps[k].max(id); if (m == 0) { l.weapons[k] = 0; l.counts[k] = 0 } else if (l.counts[k] > m) l.counts[k] = m } } }
                }
            }
            val back = (1 until f.waypoints.size).firstOrNull { f.waypoints[it].arrive in 1 until f.waypoints[it - 1].depart }
            if (back != null) out += tag("Routes", false) + Issue(Severity.WARNING, f.id, "${name(f)} arrives at waypoint ${back + 1} before it leaves waypoint $back.", "Time the route again from waypoint $back") { d ->
                // timed again from the last locked time before the late leg: from waypoint [back] itself the retime would
                // keep its (late) time as an anchor and change nothing between two locked waypoints
                val from = (back - 1 downTo 0).firstOrNull { AirOps.timeLocked(f.waypoints[it]) } ?: (back - 1)
                AirOps.waypoint(d, f, from, "Retime", retimeAfter = true) {}
            }
        }
        // squadrons and their header rows, pilots
        val cmp = doc.cmp
        for (sq in doc.squadrons) {
            val si = cmp?.squadInfoOf(sq.id)
            val stale = si != null && (si.country != sq.owner || si.descriptionIndex != sq.type - 100 || si.currentStrength != UnitClasses.rosterTotal(sq.roster) || si.nameId != sq.nameId)
            if (cmp != null && (si == null || stale)) out += tag("Squadron list", si == null) + Issue(if (si == null) Severity.ERROR else Severity.WARNING, sq.id,
                if (si == null) "${name(sq)} is missing from the header's squadron list (BMS names and lists squadrons from it)." else "${name(sq)}'s row in the header's squadron list is out of date.",
                "Write its row again") { d -> GroundOps.squadron(d, sq, "Header row of ${name(sq)}") {} }
            val flying = doc.flights.filter { it.squadronId == sq.id }.flatMap { f -> f.pilots.take(4).filterIndexed { i, p -> p != AirOps.NO_PILOT && f.planeStats[i] != 0 } }.toSet()
            val wrong = sq.pilots.indices.count { i -> (i in flying) != (sq.pilots[i].status == AirOps.PILOT_IN_USE) && sq.pilots[i].status in setOf(0, 4) }
            if (wrong > 0) out += tag("Pilots", false) + Issue(Severity.WARNING, sq.id, "${name(sq)}: $wrong pilot${if (wrong == 1) "'s" else "s'"} \"flying\" mark does not match the flights' seats.", "Mark them by the seats") { d ->
                UnitEdit.mutate("Pilots of ${name(sq)} by the seats", d) { touch(sq); sq.pilots.forEachIndexed { i, p -> if (p.status in setOf(0, 4)) p.status = if (i in flying) AirOps.PILOT_IN_USE else AirOps.PILOT_FREE } }
            }
            if (sq.airbaseId.isNone || (sq.airbaseId !in objIds && ids[sq.airbaseId] !is CmdTaskForce)) out += tag("References", true) + Issue(Severity.WARNING, sq.id, "${name(sq)}'s base is not an objective or a task force of the file.", null, null)
        }
        cmp?.squadInfo?.filter { ids[it.id] !is CmdSquadron }?.forEach { si ->
            out += tag("Squadron list", true) + Issue(Severity.ERROR, si.id, "The header lists a squadron (${si.squadronName.text.ifBlank { "id ${si.id}" }}) the units do not have.", "Take the row out") { d ->
                UnitEdit.mutate("Remove a stale squadron row", d, setOf("cmp")) { cmp.also { touch(it) }.squadInfo.removeAll { it.id == si.id } }
            }
        }
        // brigades and battalions
        for (b in doc.battalions) {
            val br = ids[b.parentId]
            if (!b.parentId.isNone && br !is CmdBrigade) out += tag("References", true) + Issue(Severity.ERROR, b.id, "${name(b)} names a brigade that is not in the file.", "Make it independent") { d -> GroundOps.setBrigade(d, b, null) }
            else if (br is CmdBrigade && b.id !in br.elements) out += tag("Brigades", false) + Issue(Severity.ERROR, b.id, "${name(b)} names brigade ${br.nameId}, which does not list it.",
                if (br.elements.size < 5) "List it in the brigade" else "Make it independent") { d ->
                if (br.elements.size < 5) UnitEdit.mutate("List ${name(b)} in brigade ${br.nameId}", d) { touch(br); br.elements += b.id } else GroundOps.setBrigade(d, b, null)
            }
        }
        for (br in doc.brigades) {
            val bad = br.elements.filter { e -> (ids[e] as? CmdBattalion)?.parentId != br.id }
            if (bad.isNotEmpty()) out += tag("Brigades", false) + Issue(Severity.ERROR, br.id, "Brigade ${br.nameId} lists ${bad.size} element${if (bad.size == 1) "" else "s"} that are not its battalions.", "Take them out of its list") { d ->
                UnitEdit.mutate("Tidy brigade ${br.nameId}", d) { touch(br); br.elements.removeAll { e -> (ids[e] as? CmdBattalion)?.parentId != br.id } }
            }
            if (br.elements.size > 5) out += tag("Brigades", false) + Issue(Severity.WARNING, br.id, "Brigade ${br.nameId} has ${br.elements.size} battalions; BMS's brigades take five.", null, null)
        }
        // references to nothing
        for (u in doc.units) {
            val dead = (listOf(u.targetId, u.cargoId).filter { !it.isNone && it !in ids && it !in objIds }) +
                u.waypoints.filter { it.hasTarget && !it.targetId.isNone && it.targetId !in ids && it.targetId !in objIds }.map { it.targetId }
            if (dead.isNotEmpty()) out += tag("References", false) + Issue(Severity.WARNING, u.id, "${name(u)} points at ${dead.size} thing${if (dead.size == 1) "" else "s"} not in the file (target, cargo or a waypoint's target).", "Clear those") { d ->
                UnitEdit.mutate("Clear dangling targets of ${name(u)}", d) {
                    touch(u)
                    if (u.targetId in dead) u.targetId = VuId.NONE
                    if (u.cargoId in dead) u.cargoId = VuId.NONE
                    u.waypoints.forEach { w -> if (w.hasTarget && w.targetId in dead) w.setTarget(null) }
                }
            }
            if (u !is CmdFlight && u !is CmdPackage) {
                val max = cls.maxRoster(u.type)
                if (max != 0 && (0 until 16).any { UnitClasses.slotCount(u.roster, it) > UnitClasses.slotCount(max, it) })
                    out += tag("Rosters", true) + Issue(Severity.WARNING, u.id, "${name(u)} has more vehicles than its class allows.", "Full roster of its class") { d -> GroundOps.fullRoster(d, u) }
            }
        }
        return out
    }

    /** Every issue with a fix, fixed in one step (each fix made against the state its predecessors left). */
    fun fixAll(doc: CommandSave): CommandEdit = object : CommandEdit {
        override val label = "Fix every unit issue"
        override val parts = setOf("uni", "cmp", "plt").filter { p -> when (p) { "cmp" -> doc.cmp != null; "plt" -> doc.plt != null; else -> true } }.toSet()
        private val done = ArrayList<CommandEdit>()
        override fun apply(doc: CommandSave) {
            done.clear()
            repeat(3) {
                val fixes = run(doc).mapNotNull { it.fix }
                if (fixes.isEmpty()) return
                for (f in fixes) { val e = f(doc); runCatching { e.apply(doc); done += e } }
            }
        }
        override fun undo(doc: CommandSave) { done.asReversed().forEach { runCatching { it.undo(doc) } }; done.clear() }
    }
}
