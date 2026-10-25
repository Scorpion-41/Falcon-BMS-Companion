package com.bmscompanion.desktop.bridge.command

/**
 * Adding, cloning and deleting units, every kind, with the links BMS follows kept whole:
 *
 * - new ids from [CommandIds] (flights from the flights' pool, packages from theirs, the rest shared; creator 0) and a
 *   CampId no objective or unit has; each new unit goes into the unit list before the next is made, so two made in one
 *   step never share an id;
 * - a flight is always in a package (≤ 5 flights): a new or cloned flight joins a given one or gets its own;
 * - a squadron has its header row ([GroundOps.syncInfo]); its pilots are made from its team's ranks;
 * - a deleted flight frees its pilots and its callsign number; a deleted package takes its flights; a deleted unit is
 *   taken out of every list naming it (package and brigade elements) and out of every waypoint target, target and
 *   cargo id pointing at it.
 */
object UnitLife {
    private const val CELL = AirOps.CELL

    private fun <U : CmdUnit> base(doc: CommandSave, u: U, kind: UnitKind, type: Int, owner: Int, gx: Int, gy: Int): U {
        u.ver = doc.version
        u.type = type; u.entityType = type
        u.id = CommandIds.unitId(doc, kind)
        u.campId = CommandIds.campId(doc)
        u.owner = owner
        u.x = gx; u.y = gy
        u.destX = gx + 1; u.destY = gy + 1
        u.roster = UnitClasses.of(doc).maxRoster(type)
        u.unitFlags = GroundOps.U_PARENT
        return u
    }

    /** A package for flight [f] alone (the planned form BMS keeps: its mission and time on target). */
    fun newPackage(doc: CommandSave, f: CmdFlight, type: Int): CmdPackage {
        val p = base(doc, CmdPackage(), UnitKind.PACKAGE, type, f.owner, f.x, f.y)
        p.nameId = p.campId
        p.roster = 0
        p.unitFlags = GroundOps.U_PARENT or CmdUnit.U_FINAL
        p.elements += f.id
        p.request.mission = f.mission
        p.request.aircraft = f.aircraft
        p.request.tot = f.timeOnTarget
        p.request.requester = VuId.NONE
        return p
    }

    private fun insertUnit(s: UnitEdit.Scope, u: CmdUnit, after: CmdUnit? = null) {
        val list = s.units
        val at = after?.let { a -> list.indexOfFirst { it === a } }?.takeIf { it >= 0 }?.plus(1) ?: list.size
        list.add(at, u)
    }

    // ---------------------------------------------------------------- add

    /** A squadron of [type] at [base] (an airbase objective or a carrier's task force) for team [owner]. */
    fun addSquadron(doc: CommandSave, type: Int, base: Any, owner: Int): CommandEdit = UnitEdit.mutate("Add a squadron", doc, setOf("uni", "cmp", "plt")) {
        val cls = UnitClasses.of(doc)
        val (cell, baseId) = when (base) {
            is CmdObjective -> GroundOps.objCell(base) to base.id
            is CmdTaskForce -> (base.x to base.y) to base.id
            else -> UnitEdit.refuse("a squadron's base is an airbase or a carrier")
        }
        val sq = base(doc, CmdSquadron(), UnitKind.SQUADRON, type, owner, cell.first, cell.second)
        sq.airbaseId = baseId
        sq.destX = 0; sq.destY = 0
        sq.nameId = CommandIds.nameId(doc, UnitKind.SQUADRON, owner)
        sq.fuel = ((cls.ucdOfType(type)?.int("Fuel") ?: 0) * 24.0 * 4 * 119.993769 * 2 / 60).toInt()
        java.util.Arrays.fill(sq.campSpecificRating, 0xFF.toByte())
        cls.storesTemplate(type)?.let { GroundOps.fillStores(sq, it) }
        sq.pilots = GroundOps.newPilots(this, owner, sq.id.num)
        if (base is CmdTaskForce) sq.unitFlags = sq.unitFlags or GroundOps.U_NO_PLANNING
        insertUnit(this, sq, doc.squadrons.lastOrNull())
        GroundOps.syncInfo(this, sq, baseName = (base as? CmdObjective)?.let { GroundOps.objName(doc, it) } ?: cls.leadVcd((base as CmdTaskForce).type)?.name, icon = (base as? CmdObjective)?.let { cls.ocdOf(it)?.icon } ?: 0)
    }

    /**
     * A flight of [n] aircraft of squadron [sq], in a package of its own (or [pkg]), taking off from its base at
     * [takeoff] and landing there an hour later; callsign the first free of its aircraft's callsigns; pilots the
     * squadron's free ones; the loadout its aircraft's gun. The route is then the pilot's to draw.
     */
    fun addFlight(doc: CommandSave, sq: CmdSquadron, n: Int, mission: Int, takeoff: Long, pkg: CmdPackage? = null): CommandEdit = UnitEdit.mutate("Add a flight", doc, setOf("uni", "plt")) {
        val cls = UnitClasses.of(doc)
        val ft = cls.flightTypeFor(sq.type) ?: UnitEdit.refuse("no flight class flies ${cls.leadVcd(sq.type)?.name ?: "this squadron's aircraft"}")
        if (pkg != null && pkg.elements.size >= 5) UnitEdit.refuse("package ${pkg.campId} has five flights, BMS's most")
        val pkgType = pkg?.type ?: doc.packages.firstOrNull()?.type ?: cls.typesOf(UnitKind.PACKAGE).firstOrNull()?.type ?: UnitEdit.refuse("no package class in the class table")
        val template = doc.flights.firstOrNull { it.squadronId == sq.id } ?: doc.flights.firstOrNull { it.type == ft }
        val f = base(doc, CmdFlight(), UnitKind.FLIGHT, ft, sq.owner, sq.x, sq.y)
        f.destX = 0; f.destY = 0
        f.unitFlags = template?.unitFlags?.and(TEMPLATE_FLAGS) ?: 0
        f.squadronId = sq.id
        f.mission = mission; f.oldMission = mission
        f.slots = intArrayOf(0, 1, 2, 3)
        val (cid, cnum) = freeCallsign(doc, ft) ?: UnitEdit.refuse("every callsign number of this aircraft's callsigns is taken")
        f.callsignId = cid; f.callsignNum = cnum
        val plt = plt
        if (plt != null && cid in plt.callsigns.indices && cnum in 1..8) plt.callsigns[cid] = plt.callsigns[cid] or (1 shl (cnum - 1))
        touch(sq)
        for (k in 0 until 4) {
            if (k < n) {
                f.planeStats[k] = AirOps.SEAT_AVAILABLE
                val p = AirOps.freePilot(sq, k, f.pilots.take(k).toSet())
                f.pilots[k] = p ?: AirOps.NO_PILOT
                p?.let { sq.pilots[it].status = AirOps.PILOT_IN_USE }
            } else f.pilots[k] = AirOps.NO_PILOT
        }
        f.roster = AirOps.flightRoster(cls, ft, n)
        val fuel = cls.leadVcd(ft)?.int("FuelWeight") ?: 0
        for (k in 0 until n) f.fuelInitial[k] = fuel
        f.laserCode = IntArray(4) { 1688 }
        f.loadouts += cls.bareLoadout(ft)
        f.texSet = IntArray(4) { sq.texSet }
        val home = sq.airbaseId
        f.waypoints += CmdWaypoint(0, sq.x, sq.y, 0, takeoff, AirOps.ACTION_TAKEOFF, 0, AirOps.formationByte(1, 0), 0x80L or AirOps.TIME_LOCK).also { it.setTarget(home, 255) }
        f.waypoints += CmdWaypoint(0, sq.x, sq.y, 0, takeoff + AirOps.HOUR_MS, AirOps.ACTION_LAND, 0, AirOps.formationByte(1, 0), 0x100L).also { it.setTarget(home, 255) }
        f.currentWp = 1
        f.timeOnTarget = takeoff + AirOps.HOUR_MS / 2
        insertUnit(this, f, doc.flights.lastOrNull())
        if (pkg != null) { touch(pkg); pkg.elements += f.id; f.packageId = pkg.id }
        else { val p = newPackage(doc, f, pkgType); insertUnit(this, p, f); f.packageId = p.id }
    }

    /** The flight bits a new flight keeps from its template (the rest — dead, engaged, moving … — start clear). */
    private const val TEMPLATE_FLAGS = 0x2 or 0x10 or 0x80 or 0x200000

    /** The first callsign word of an aircraft's callsigns with a free number (1-9). */
    fun freeCallsign(doc: CommandSave, flightType: Int, prefer: Int? = null): Pair<Int, Int>? {
        val range = UnitClasses.of(doc).callsignRange(flightType)?.toList() ?: (0 until (doc.plt?.callsigns?.size ?: 100)).toList()
        val order = listOfNotNull(prefer) + range
        for (id in order) CommandIds.callsignNumber(doc, id)?.let { return id to it }
        return null
    }

    /** A battalion of [type] for [owner] at a cell; sent to [aobj] when given (and placed on it). */
    fun addBattalion(doc: CommandSave, type: Int, owner: Int, gx: Int, gy: Int, aobj: CmdObjective?, orders: Int? = null): CommandEdit = UnitEdit.mutate("Add a battalion", doc) {
        val cls = UnitClasses.of(doc)
        val (x, y) = aobj?.let { GroundOps.objCell(it) } ?: (gx to gy)
        val b = base(doc, CmdBattalion(), UnitKind.BATTALION, type, owner, x, y)
        b.nameId = CommandIds.nameId(doc, UnitKind.BATTALION, owner, type)
        b.morale = 100; b.supply = 100
        b.aobj = aobj?.id ?: VuId.NONE
        val ad = cls.clsOfType(type)?.sub == GroundOps.SUB_AIR_DEFENCE
        b.orders = orders ?: if (ad) 9 else 0
        if (ad) { b.unitFlags = b.unitFlags or GroundOps.U_ASSIGNED or GroundOps.U_ORDERED; b.baseFlags = b.baseFlags or 1 }
        insertUnit(this, b, doc.battalions.lastOrNull())
    }

    fun addBrigade(doc: CommandSave, type: Int, owner: Int, gx: Int, gy: Int, aobj: CmdObjective?): CommandEdit = UnitEdit.mutate("Add a brigade", doc) {
        val (x, y) = aobj?.let { GroundOps.objCell(it) } ?: (gx to gy)
        val b = base(doc, CmdBrigade(), UnitKind.BRIGADE, type, owner, x, y)
        b.nameId = CommandIds.nameId(doc, UnitKind.BRIGADE, owner)
        b.aobj = aobj?.id ?: VuId.NONE
        insertUnit(this, b, doc.brigades.lastOrNull())
    }

    fun addTaskForce(doc: CommandSave, type: Int, owner: Int, gx: Int, gy: Int): CommandEdit = UnitEdit.mutate("Add a task force", doc) {
        val t = base(doc, CmdTaskForce(), UnitKind.TASKFORCE, type, owner, gx, gy)
        t.nameId = CommandIds.nameId(doc, UnitKind.TASKFORCE, owner)
        t.supply = 100
        insertUnit(this, t, doc.taskForces.lastOrNull())
    }

    // ---------------------------------------------------------------- clone

    /** A copy of [u] with new ids; at cell [at] for a ground or naval unit (a squadron at the airbase there). */
    fun clone(doc: CommandSave, u: CmdUnit, at: Pair<Int, Int>? = null, intoPackage: CmdPackage? = null): CommandEdit = UnitEdit.mutate("Clone ${AirOps.nameOf(doc, u)}", doc, setOf("uni", "cmp", "plt")) {
        when (u) {
            is CmdFlight -> cloneFlight(this, u, intoPackage ?: doc.packageOf(u)?.takeIf { it.elements.size < 5 })
            is CmdPackage -> {
                val flights = doc.flightsOf(u)
                if (flights.isEmpty()) UnitEdit.refuse("the package has no flights to copy")
                val p = u.copy() as CmdPackage
                p.id = CommandIds.unitId(doc, UnitKind.PACKAGE); p.campId = CommandIds.campId(doc); p.nameId = p.campId
                p.elements.clear()
                insertUnit(this, p, u)
                flights.forEach { cloneFlight(this, it, p) }
            }
            is CmdSquadron -> {
                val c = u.copy() as CmdSquadron
                c.id = CommandIds.unitId(doc, UnitKind.SQUADRON); c.campId = CommandIds.campId(doc)
                c.nameId = CommandIds.nameId(doc, UnitKind.SQUADRON, u.owner)
                c.pilots = GroundOps.newPilots(this, u.owner, c.id.num)
                java.util.Arrays.fill(c.schedule, 0)
                c.aaKills = 0; c.agKills = 0; c.asKills = 0; c.anKills = 0; c.missionsFlown = 0; c.missionScore = 0; c.totalLosses = 0; c.pilotLosses = 0
                insertUnit(this, c, u)
                GroundOps.syncInfo(this, c, baseName = doc.squadInfo(u)?.airbaseName?.text, icon = doc.squadInfo(u)?.airbaseIcon)
            }
            is CmdBattalion -> {
                val c = u.copy() as CmdBattalion
                c.id = CommandIds.unitId(doc, UnitKind.BATTALION); c.campId = CommandIds.campId(doc)
                c.nameId = CommandIds.nameId(doc, UnitKind.BATTALION, u.owner, u.type)
                at?.let { GroundOps.placeGround(c, it.first, it.second); c.waypoints.clear(); c.currentWp = 0 }
                val br = doc.unit(u.parentId) as? CmdBrigade
                if (br != null && br.elements.size < 5) { touch(br); br.elements += c.id } else if (br != null) { c.parentId = VuId.NONE; c.unitFlags = c.unitFlags or GroundOps.U_PARENT }
                insertUnit(this, c, u)
            }
            is CmdBrigade -> {
                val c = u.copy() as CmdBrigade
                c.id = CommandIds.unitId(doc, UnitKind.BRIGADE); c.campId = CommandIds.campId(doc)
                c.nameId = CommandIds.nameId(doc, UnitKind.BRIGADE, u.owner)
                at?.let { GroundOps.placeGround(c, it.first, it.second); c.waypoints.clear(); c.currentWp = 0 }
                c.elements.clear()
                insertUnit(this, c, u)
                for (b in doc.battalionsOf(u)) {
                    val cb = b.copy() as CmdBattalion
                    cb.id = CommandIds.unitId(doc, UnitKind.BATTALION); cb.campId = CommandIds.campId(doc)
                    cb.nameId = CommandIds.nameId(doc, UnitKind.BATTALION, b.owner, b.type)
                    cb.parentId = c.id
                    at?.let { GroundOps.placeGround(cb, it.first, it.second); cb.waypoints.clear(); cb.currentWp = 0 }
                    insertUnit(this, cb, doc.battalions.lastOrNull())
                    c.elements += cb.id
                }
            }
            is CmdTaskForce -> {
                val c = u.copy() as CmdTaskForce
                c.id = CommandIds.unitId(doc, UnitKind.TASKFORCE); c.campId = CommandIds.campId(doc)
                c.nameId = CommandIds.nameId(doc, UnitKind.TASKFORCE, u.owner)
                at?.let { GroundOps.placeGround(c, it.first, it.second); c.waypoints.clear(); c.currentWp = 0 }
                insertUnit(this, c, u)
            }
        }
    }

    /**
     * A copy of flight [f]: new id, CampId and callsign number (the first free of its callsign word, else of its
     * aircraft's callsigns), in [pkg] or a package of its own, flying the squadron's free pilots (never sharing the
     * original's).
     */
    private fun cloneFlight(s: UnitEdit.Scope, f: CmdFlight, pkg: CmdPackage?): CmdFlight {
        val doc = s.doc
        val c = f.copy() as CmdFlight
        c.id = CommandIds.unitId(doc, UnitKind.FLIGHT); c.campId = CommandIds.campId(doc)
        val (cid, cnum) = freeCallsign(doc, f.type, prefer = f.callsignId) ?: UnitEdit.refuse("every callsign number of this aircraft's callsigns is taken")
        c.callsignId = cid; c.callsignNum = cnum
        s.plt?.let { plt -> if (cid in plt.callsigns.indices && cnum in 1..8) plt.callsigns[cid] = plt.callsigns[cid] or (1 shl (cnum - 1)) }
        c.playerSlots = IntArray(4) { 255 }
        val sq = doc.squadronOf(f)
        s.touch(sq)
        for (k in 0 until 4) {
            if (c.planeStats[k] == 0 || sq == null) { c.pilots[k] = AirOps.NO_PILOT; continue }
            val p = AirOps.freePilot(sq, k, c.pilots.take(k).toSet())
            c.pilots[k] = p ?: AirOps.NO_PILOT
            p?.let { sq.pilots[it].status = AirOps.PILOT_IN_USE }
        }
        insertUnit(s, c, f)
        if (pkg != null) { s.touch(pkg); pkg.elements += c.id; c.packageId = pkg.id }
        else { val p = newPackage(doc, c, doc.packageOf(f)?.type ?: doc.packages.firstOrNull()?.type ?: UnitEdit.refuse("no package class")); insertUnit(s, p, c); c.packageId = p.id }
        return c
    }

    // ---------------------------------------------------------------- delete

    /**
     * Deletes [u]. A package goes with its flights; a squadron only when it has no flights, or with them when
     * [withChildren]; a brigade frees its battalions (or deletes them with [withChildren]); a task force a carrier's
     * squadrons stand on is refused.
     */
    fun delete(doc: CommandSave, u: CmdUnit, withChildren: Boolean = false): CommandEdit = UnitEdit.mutate("Delete ${AirOps.nameOf(doc, u)}", doc, setOf("uni", "cmp", "plt")) {
        val gone = LinkedHashSet<CmdUnit>()
        when (u) {
            is CmdFlight -> gone += u
            is CmdPackage -> { gone += u; gone += doc.flightsOf(u) }
            is CmdSquadron -> {
                val fl = doc.flights.filter { it.squadronId == u.id }
                if (fl.isNotEmpty() && !withChildren) UnitEdit.refuse("${fl.size} flight${if (fl.size == 1) "" else "s"} fly from this squadron: delete them first, or delete the squadron with its flights")
                gone += u; gone += fl
            }
            is CmdBattalion -> gone += u
            is CmdBrigade -> { gone += u; if (withChildren) gone += doc.battalionsOf(u) }
            is CmdTaskForce -> {
                val sqs = doc.squadrons.filter { it.airbaseId == u.id }
                if (sqs.isNotEmpty()) UnitEdit.refuse("${sqs.size} squadron${if (sqs.size == 1) " is" else "s are"} based on it: move or delete them first")
                gone += u
            }
        }
        // a flight's package goes when it is left empty
        doc.packages.filter { it !in gone }.forEach { p -> if (p.elements.isNotEmpty() && p.elements.all { id -> gone.any { it.id == id } }) gone += p }
        remove(this, gone)
    }

    /** Takes [gone] out of the unit list and everything that names them out of the rest. */
    internal fun remove(s: UnitEdit.Scope, gone: Set<CmdUnit>) {
        val doc = s.doc
        val ids = gone.mapTo(HashSet()) { it.id }
        // flights: pilots and callsigns freed
        for (f in gone.filterIsInstance<CmdFlight>()) {
            val sq = doc.squadronOf(f)
            if (sq != null && sq !in gone) {
                s.touch(sq)
                f.pilots.take(4).forEach { p -> if (p != AirOps.NO_PILOT && p in sq.pilots.indices && doc.flights.none { it !in gone && it.squadronId == sq.id && p in it.pilots.take(4) }) sq.pilots[p].status = AirOps.PILOT_FREE }
            }
            val plt = s.plt
            if (plt != null && f.callsignId in plt.callsigns.indices && f.callsignNum in 1..8 &&
                doc.flights.none { it !in gone && it.callsignId == f.callsignId && it.callsignNum == f.callsignNum }) plt.callsigns[f.callsignId] = plt.callsigns[f.callsignId] and (1 shl (f.callsignNum - 1)).inv()
        }
        // the header's squadron rows
        if (gone.any { it is CmdSquadron }) s.cmp?.squadInfo?.removeAll { it.id in ids }
        // lists and references in the units left
        for (o in doc.units) {
            if (o in gone) continue
            var hit = false
            fun mark() { if (!hit) { s.touch(o); hit = true } }
            if (o.targetId in ids) { mark(); o.targetId = VuId.NONE }
            if (o.cargoId in ids) { mark(); o.cargoId = VuId.NONE }
            for (w in o.waypoints) {
                if (w.hasTarget && w.targetId in ids) { mark(); w.setTarget(null) }
                w.designated.forEach { if (it.id in ids) { mark(); it.id = VuId.NONE; it.building = 255 } }
            }
            when (o) {
                is CmdPackage -> {
                    if (o.elements.any { it in ids }) { mark(); o.elements.removeAll { it in ids } }
                    listOf(o.interceptor, o.awacs, o.jstar, o.ecm, o.tanker).forEach { if (it in ids) mark() }
                    if (o.interceptor in ids) o.interceptor = VuId.NONE
                    if (o.awacs in ids) o.awacs = VuId.NONE
                    if (o.jstar in ids) o.jstar = VuId.NONE
                    if (o.ecm in ids) o.ecm = VuId.NONE
                    if (o.tanker in ids) o.tanker = VuId.NONE
                }
                is CmdBrigade -> if (o.elements.any { it in ids }) { mark(); o.elements.removeAll { it in ids } }
                is CmdBattalion -> if (o.parentId in ids) { mark(); o.parentId = VuId.NONE; o.unitFlags = o.unitFlags or GroundOps.U_PARENT }
                is CmdFlight -> if (o.packageId in ids) { mark(); o.packageId = VuId.NONE }
                else -> Unit
            }
        }
        s.units.removeAll { it in gone }
    }
}
