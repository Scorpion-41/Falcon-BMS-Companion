package com.bmscompanion.desktop.bridge.command

import kotlin.math.floor
import kotlin.math.hypot

/**
 * Squadrons, battalions, brigades and task forces: their edits, each one undoable and keeping BMS's links in step.
 *
 * - **A squadron's name lives in the header** (its row of the squadron list): every squadron edit writes that row
 *   again ([syncInfo]: name, class, strength = the roster's aircraft, country, specialty, patch, flags, paint, the base's
 *   name and its position — x north, y east, feet), adding the row when it is missing.
 * - **A squadron's base** is an objective (or a carrier's task force); its flights take off from it (waypoint 0) and
 *   land at it (action 7): moving the base moves those waypoints and, for a flight not yet off, the flight.
 * - **A battalion in a brigade** names it ([CmdBattalion.parentId]) and is one of its ≤ 5 elements, takes its division
 *   and is not a parent itself; a free battalion is a parent (0x20) — as BMS's own saves have them (6,287 and 4,941 of
 *   them). An air-defence or supply battalion may instead protect or supply a brigade (its cargo id), staying a parent.
 * - **A ground unit's destination** is its cell + 1 on both axes when it stands still (9,049 of 9,636 battalions).
 * - **A carrier's squadrons** stand where its task force is: moving the task force moves them and their header rows.
 */
object GroundOps {
    private const val CELL = AirOps.CELL
    const val U_PARENT = 0x20
    const val U_MOVING = 0x400
    const val U_ASSIGNED = 0x4
    const val U_ORDERED = 0x8
    const val U_NO_PLANNING = 0x10
    const val U_INACTIVE = 0x20000
    const val SUB_AIR_DEFENCE = 1
    const val SUB_SUPPLY = 13
    const val SUB_ENGINEER = 5

    val ORDERS = listOf("Reserve", "Capture", "Secure", "Assault", "Airborne", "Commando", "Defend", "Support", "Repair", "Air defence", "Recon", "Radar")
    val SPECIALTIES = listOf("General", "Air-to-air", "Air-to-ground", "Anti-ship", "SEAD", "CSAR")
    val PILOT_STATUS = listOf("Available", "Killed", "Missing", "Rescued", "Flying")

    /** An objective's cell: from its position in feet where it has one (BMS places by it), else its stored cell. */
    fun objCell(o: CmdObjective): Pair<Int, Int> {
        val n = o.simNorth; val e = o.simEast
        return if (n.isFinite() && e.isFinite() && (n != 0.0 || e != 0.0)) floor(e / CELL).toInt() to floor(n / CELL).toInt() else o.x to o.y
    }

    fun objName(doc: CommandSave, o: CmdObjective): String = o.name.text.trim().ifEmpty { "objective ${o.campId}" }

    fun subOf(doc: CommandSave, u: CmdUnit): Int = UnitClasses.of(doc).clsOfType(u.type)?.sub ?: -1

    // ---------------------------------------------------------------- squadrons

    /** The squadron's header row, written again from the squadron (added when it is missing). */
    fun syncInfo(s: UnitEdit.Scope, sq: CmdSquadron, baseName: String? = null, icon: Int? = null) {
        val doc = s.doc
        val cmp = s.cmp ?: return
        var si = cmp.squadInfoOf(sq.id)
        if (si == null) {
            si = SquadInfo(0, 0, sq.id, 0, 0, 0, 0, 0, 0, 0, FixedText.of(80), 0, 0, 0, 0, FixedText.of(80))
            cmp.squadInfo += si
            val base = doc.objective(sq.airbaseId) ?: (doc.unit(sq.airbaseId) as? CmdTaskForce)?.let { null }
            si.airbaseName.text = baseName ?: base?.let { objName(doc, it) } ?: ""
            si.airbaseIcon = icon ?: base?.let { UnitClasses.of(doc).ocdOf(it)?.icon } ?: 0
        } else {
            if (baseName != null) si.airbaseName.text = baseName
            if (icon != null) si.airbaseIcon = icon
        }
        si.id = sq.id
        si.x = ((sq.y + 0.5) * CELL).toFloat()
        si.y = ((sq.x + 0.5) * CELL).toFloat()
        si.descriptionIndex = sq.type - 100
        si.nameId = sq.nameId
        si.squadronPath = sq.squadronPatch
        si.specialty = sq.specialty
        si.currentStrength = UnitClasses.rosterTotal(sq.roster)
        si.country = sq.owner
        si.flags = sq.unitFlags
        si.campId = sq.campId
        si.texSet = sq.texSet
    }

    fun squadron(doc: CommandSave, sq: CmdSquadron, label: String, set: CmdSquadron.() -> Unit): CommandEdit = UnitEdit.mutate(label, doc, setOf("uni", "cmp")) {
        touch(sq); sq.set(); syncInfo(this, sq)
    }

    /** The squadron's name (its header row only). */
    fun setSquadronName(doc: CommandSave, sq: CmdSquadron, name: String): CommandEdit = UnitEdit.mutate("Name of ${AirOps.nameOf(doc, sq)}", doc, setOf("uni", "cmp")) {
        if (doc.cmp == null) UnitEdit.refuse("the save has no header, where a squadron's name is kept")
        touch(sq); syncInfo(this, sq)
        doc.cmp.squadInfoOf(sq.id)!!.squadronName.text = name.trim()
    }

    /** The owner; [withFlights] gives its flights (and their packages, when all theirs are) the same. */
    fun setSquadronOwner(doc: CommandSave, sq: CmdSquadron, team: Int, withFlights: Boolean): CommandEdit = UnitEdit.mutate("Owner of ${AirOps.nameOf(doc, sq)}", doc, setOf("uni", "cmp")) {
        touch(sq); sq.owner = team
        if (withFlights) doc.flights.filter { it.squadronId == sq.id }.forEach { f -> touch(f); f.owner = team; doc.packageOf(f)?.let { p -> if (doc.flightsOf(p).all { it.owner == team || it === f }) { touch(p); p.owner = team } } }
        syncInfo(this, sq)
    }

    /**
     * A new base: an airbase objective or a carrier's task force. The squadron stands on it, its flights' take-off
     * (waypoint 0) and its landings at the old base (action 7) move with it.
     */
    fun moveBase(doc: CommandSave, sq: CmdSquadron, base: Any): CommandEdit = UnitEdit.mutate("Move ${AirOps.nameOf(doc, sq)}", doc, setOf("uni", "cmp")) {
        val (id, cell, name, icon) = when (base) {
            is CmdObjective -> Quad(base.id, objCell(base), objName(doc, base), UnitClasses.of(doc).ocdOf(base)?.icon ?: 0)
            is CmdTaskForce -> Quad(base.id, base.x to base.y, UnitClasses.of(doc).leadVcd(base.type)?.name ?: "carrier", 0)
            else -> UnitEdit.refuse("a squadron's base is an airbase or a carrier")
        }
        if (base is CmdObjective && !AirOps.allied(doc, sq.owner, doc.ownerNow(base))) UnitEdit.refuse("${objName(doc, base)} belongs to a team not allied to the squadron's")
        val old = sq.airbaseId
        val now = doc.cmp?.currentTime ?: 0L
        touch(sq)
        sq.airbaseId = id; sq.x = cell.first; sq.y = cell.second
        sq.unitFlags = if (base is CmdTaskForce) sq.unitFlags or U_NO_PLANNING else sq.unitFlags and U_NO_PLANNING.inv()
        for (f in doc.flights.filter { it.squadronId == sq.id && it.waypoints.isNotEmpty() }) {
            touch(f)
            val w0 = f.waypoints[0]
            w0.gridX = cell.first; w0.gridY = cell.second
            if (w0.targetId == old || !w0.hasTarget) w0.setTarget(id, 255)
            w0.designated.forEach { if (it.id == old) it.id = id }
            if (w0.depart >= now) { f.x = cell.first; f.y = cell.second }
            for (w in f.waypoints.drop(1)) if (w.action == AirOps.ACTION_LAND && (w.targetId == old || !w.hasTarget)) {
                w.gridX = cell.first; w.gridY = cell.second; w.setTarget(id, 255); w.designated.forEach { if (it.id == old) it.id = id }
            }
        }
        syncInfo(this, sq, baseName = name, icon = icon)
    }

    private data class Quad(val id: VuId, val cell: Pair<Int, Int>, val name: String, val icon: Int)

    fun setRosterSlot(doc: CommandSave, u: CmdUnit, slot: Int, n: Int): CommandEdit {
        val max = UnitClasses.of(doc).slotMax(u.type, slot)
        if (n !in 0..max) return Edits.failing("Roster", "uni", "slot ${slot + 1} takes 0 to $max")
        return if (u is CmdSquadron) squadron(doc, u, "Roster of ${AirOps.nameOf(doc, u)}") { roster = UnitClasses.withSlot(roster, slot, n) }
        else AirOps.field(doc, u, "Roster of ${AirOps.nameOf(doc, u)}") { roster = UnitClasses.withSlot(roster, slot, n) }
    }

    fun fullRoster(doc: CommandSave, u: CmdUnit): CommandEdit {
        val r = UnitClasses.of(doc).maxRoster(u.type)
        return if (u is CmdSquadron) squadron(doc, u, "Full roster of ${AirOps.nameOf(doc, u)}") { roster = r } else AirOps.field(doc, u, "Full roster of ${AirOps.nameOf(doc, u)}") { roster = r }
    }

    /** Stores as a new squadron of its class gets them. */
    fun resupply(doc: CommandSave, sq: CmdSquadron): CommandEdit = UnitEdit.mutate("Resupply ${AirOps.nameOf(doc, sq)}", doc) {
        val t = UnitClasses.of(doc).storesTemplate(sq.type) ?: UnitEdit.refuse("its class names no stores template")
        touch(sq); fillStores(sq, t)
    }

    internal fun fillStores(sq: CmdSquadron, t: Map<Int, Int>) {
        java.util.Arrays.fill(sq.stores, 0)
        t.forEach { (id, n) -> if (id in sq.stores.indices) sq.stores[id] = n.coerceIn(0, 255).toByte() }
    }

    fun setStore(doc: CommandSave, sq: CmdSquadron, weapon: Int, n: Int): CommandEdit = UnitEdit.mutate("Stores of ${AirOps.nameOf(doc, sq)}", doc) {
        if (weapon !in sq.stores.indices) UnitEdit.refuse("weapon $weapon is past the squadron's 1,000")
        if (n !in 0..255) UnitEdit.refuse("a squadron holds 0 to 255 of a store")
        touch(sq); sq.stores[weapon] = n.toByte()
    }

    fun clearSchedule(doc: CommandSave, sq: CmdSquadron): CommandEdit = AirOps.field(doc, sq, "Clear the schedule of ${AirOps.nameOf(doc, sq)}") { java.util.Arrays.fill(schedule, 0) }

    /** Pilots not flying a seat (status 4 without a flight, or rescued) are available again. */
    fun freeUsedPilots(doc: CommandSave, sq: CmdSquadron): CommandEdit = UnitEdit.mutate("Free the pilots of ${AirOps.nameOf(doc, sq)}", doc) {
        val flying = doc.flights.filter { it.squadronId == sq.id }.flatMap { f -> f.pilots.take(4).filterIndexed { i, p -> p != AirOps.NO_PILOT && f.planeStats[i] != 0 } }.toSet()
        touch(sq)
        sq.pilots.forEachIndexed { i, p -> if ((p.status == AirOps.PILOT_IN_USE && i !in flying) || p.status == 3) p.status = AirOps.PILOT_FREE }
    }

    fun resurrectPilots(doc: CommandSave, sq: CmdSquadron): CommandEdit = AirOps.field(doc, sq, "Bring back the lost pilots of ${AirOps.nameOf(doc, sq)}") {
        pilots.forEach { if (it.status == 1 || it.status == 2) it.status = AirOps.PILOT_FREE }
    }

    /** 48 new pilots from the team's ranks ([newPilots]); those flying a seat keep flying. */
    fun regeneratePilots(doc: CommandSave, sq: CmdSquadron): CommandEdit = UnitEdit.mutate("New pilots for ${AirOps.nameOf(doc, sq)}", doc, setOf("uni", "plt")) {
        touch(sq)
        val flying = doc.flights.filter { it.squadronId == sq.id }.flatMap { it.pilots.take(4) }.filter { it != AirOps.NO_PILOT }.toSet()
        val fresh = newPilots(this, sq.owner, sq.id.num)
        fresh.forEachIndexed { i, p -> if (i in flying) p.status = AirOps.PILOT_IN_USE }
        sq.pilots.clear(); sq.pilots.addAll(fresh)
    }

    /**
     * 48 pilots from team [owner]'s ranks (the team record's first colonel, commander and wingman numbers): the
     * squadron's leader a colonel, the next fifteen commanders, the rest wingmen — each the least used pilot of his
     * rank (the pilots' part counts the uses) — with the skill the team's other squadrons' pilots have.
     */
    fun newPilots(s: UnitEdit.Scope, owner: Int, seed: Long): MutableList<SquadronPilot> {
        val doc = s.doc
        val team = doc.teams.getOrNull(owner)?.team
        val plt = s.plt
        val skills = doc.squadrons.filter { it.owner == owner }.flatMap { q -> q.pilots.map { it.skillAndRating } }.filter { it > 0 }.sorted()
        val skill = skills.getOrNull(skills.size / 2) ?: 48
        val rnd = java.util.Random(seed)
        fun pick(from: Int, until: Int): Int {
            if (plt == null || until <= from || from !in plt.pilots.indices) return from.coerceAtLeast(0)
            val end = until.coerceAtMost(plt.pilots.size)
            val best = (from until end).minByOrNull { plt.pilots[it].usage } ?: from
            plt.pilots[best].usage = (plt.pilots[best].usage + 1).coerceAtMost(0xFFFF)
            return best
        }
        val fc = team?.firstColonel ?: 0; val fco = team?.firstCommander ?: 0; val fw = team?.firstWingman ?: 0; val lw = team?.lastWingman ?: 0
        return MutableList(48) { i ->
            val id = when { i == 0 -> pick(fc, fco); i < 16 -> pick(fco, fw); else -> pick(fw, lw + 1) }
            SquadronPilot(id, (skill + rnd.nextInt(3) - 1).coerceIn(0, 255), AirOps.PILOT_FREE, 0, 0, 0, 0, 0)
        }
    }

    /**
     * Another aircraft: the squadron becomes a squadron of [newType] at full strength and stocked from its template;
     * its flights fly the matching flight class (their loadouts cleared to the aircraft's own gun, their rosters kept
     * at their number of aircraft). Refused when no flight class flies the new aircraft and the squadron has flights.
     */
    fun changeSquadronType(doc: CommandSave, sq: CmdSquadron, newType: Int): CommandEdit = UnitEdit.mutate("Aircraft of ${AirOps.nameOf(doc, sq)}", doc, setOf("uni", "cmp")) {
        val cls = UnitClasses.of(doc)
        if (newType == sq.type) UnitEdit.refuse("it flies that aircraft already")
        val flights = doc.flights.filter { it.squadronId == sq.id }
        val ft = cls.flightTypeFor(newType)
        if (ft == null && flights.isNotEmpty()) UnitEdit.refuse("no flight class flies ${cls.leadVcd(newType)?.name ?: "that aircraft"}, so its ${flights.size} flights could not follow")
        touch(sq)
        sq.type = newType; sq.entityType = newType
        sq.roster = cls.maxRoster(newType)
        cls.storesTemplate(newType)?.let { fillStores(sq, it) }
        for (f in flights) {
            touch(f)
            f.type = ft!!; f.entityType = ft
            f.roster = AirOps.flightRoster(cls, ft, f.aircraft.coerceAtLeast(1))
            f.loadouts.clear(); f.loadouts += cls.bareLoadout(ft)
        }
        syncInfo(this, sq)
    }

    // ---------------------------------------------------------------- ground units

    /** Sets [u]'s cell (a battalion's or brigade's destination follows: the cell + 1 when it was standing still). */
    fun placeGround(u: CmdUnit, gx: Int, gy: Int) {
        val still = u.destX == u.x + 1 && u.destY == u.y + 1 || u.destX == 0 && u.destY == 0
        u.x = gx; u.y = gy
        if (still || u.waypoints.isEmpty()) { u.destX = gx + 1; u.destY = gy + 1 }
    }

    /** Moves a unit on the map: a squadron to the airbase under it, a task force with its carrier's squadrons. */
    fun moveUnit(doc: CommandSave, u: CmdUnit, gx: Int, gy: Int): CommandEdit = when (u) {
        is CmdSquadron -> {
            val base = doc.objectives.filter { o -> UnitClasses.of(doc).clsOfType(o.type)?.type in setOf(1, 2) }
                .minByOrNull { o -> objCell(o).let { (x, y) -> hypot((x - gx).toDouble(), (y - gy).toDouble()) } }
                ?.takeIf { o -> objCell(o).let { (x, y) -> hypot((x - gx).toDouble(), (y - gy).toDouble()) } <= 6 }
            if (base == null) Edits.failing("Move", "uni", "Drop a squadron on an airbase: that is its new base.") else moveBase(doc, u, base)
        }
        is CmdTaskForce -> moveTaskForce(doc, u, gx, gy)
        is CmdFlight -> AirOps.field(doc, u, "Move ${AirOps.nameOf(doc, u)}") { x = gx; y = gy }
        is CmdPackage -> Edits.failing("Move", "uni", "A package is where its flights are.")
        else -> UnitEdit.mutate("Move ${AirOps.nameOf(doc, u)}", doc) { touch(u); placeGround(u, gx, gy) }
    }

    /** A task force to a cell: the squadrons based on it (a carrier's) stand there too. */
    fun moveTaskForce(doc: CommandSave, tf: CmdTaskForce, gx: Int, gy: Int): CommandEdit = UnitEdit.mutate("Move ${AirOps.nameOf(doc, tf)}", doc, setOf("uni", "cmp")) {
        touch(tf); placeGround(tf, gx, gy)
        doc.squadrons.filter { it.airbaseId == tf.id }.forEach { sq -> touch(sq); sq.x = gx; sq.y = gy; syncInfo(this, sq) }
    }

    /** The objective a ground unit is sent to (its destination is that objective's cell + 1). */
    fun setAobj(doc: CommandSave, u: CmdGroundUnit, o: CmdObjective?): CommandEdit = UnitEdit.mutate("Objective of ${AirOps.nameOf(doc, u)}", doc) {
        touch(u)
        u.aobj = o?.id ?: VuId.NONE
        if (o != null) { val (x, y) = objCell(o); u.destX = x + 1; u.destY = y + 1 }
    }

    /**
     * Places a battalion (or a brigade, with its battalions when [withElements]) on objective [o]: there, sent to it,
     * its route cleared. An air-defence battalion is then assigned and ordered, not moving.
     */
    fun placeAt(doc: CommandSave, u: CmdGroundUnit, o: CmdObjective, withElements: Boolean): CommandEdit = UnitEdit.mutate("Place ${AirOps.nameOf(doc, u)} at ${objName(doc, o)}", doc) {
        val (x, y) = objCell(o)
        val list = if (u is CmdBrigade && withElements) listOf(u) + doc.battalionsOf(u) else listOf(u)
        for (g in list) {
            touch(g)
            g.x = x; g.y = y; g.destX = x + 1; g.destY = y + 1
            g.aobj = o.id
            g.waypoints.clear(); g.currentWp = 0
            if (g is CmdBattalion && subOf(doc, g) == SUB_AIR_DEFENCE) g.unitFlags = (g.unitFlags and U_MOVING.inv()) or U_ASSIGNED or U_ORDERED
        }
    }

    /** Another unit class of the same kind: full roster of it. */
    fun changeGroundType(doc: CommandSave, u: CmdUnit, newType: Int): CommandEdit = UnitEdit.mutate("Class of ${AirOps.nameOf(doc, u)}", doc) {
        if (newType == u.type) UnitEdit.refuse("it is of that class already")
        touch(u); u.type = newType; u.entityType = newType; u.roster = UnitClasses.of(doc).maxRoster(newType)
    }

    /**
     * Battalion [b] joins brigade [to] (null: leaves its brigade): ≤ 5 elements, the brigade's division, not a parent
     * while in it (a parent again when free).
     */
    fun setBrigade(doc: CommandSave, b: CmdBattalion, to: CmdBrigade?): CommandEdit = UnitEdit.mutate(if (to == null) "${AirOps.nameOf(doc, b)} leaves its brigade" else "${AirOps.nameOf(doc, b)} joins brigade ${to.nameId}", doc) {
        val from = doc.unit(b.parentId) as? CmdBrigade
        if (to != null) {
            if (from === to) UnitEdit.refuse("it is in that brigade already")
            if (to.elements.size >= 5) UnitEdit.refuse("brigade ${to.nameId} has five battalions, BMS's most")
            if (!AirOps.allied(doc, b.owner, to.owner)) UnitEdit.refuse("the brigade belongs to a team not allied to the battalion's")
        } else if (from == null && b.parentId.isNone) UnitEdit.refuse("it is in no brigade")
        touch(b); touch(from); touch(to)
        from?.elements?.removeAll { it == b.id }
        doc.brigades.filter { it !== from && it !== to && b.id in it.elements }.forEach { touch(it); it.elements.removeAll { e -> e == b.id } }
        if (to != null) {
            to.elements += b.id
            b.parentId = to.id; b.division = to.division
            b.unitFlags = b.unitFlags and U_PARENT.inv()
            if (b.cargoId == to.id) b.cargoId = VuId.NONE
        } else {
            b.parentId = VuId.NONE
            b.unitFlags = b.unitFlags or U_PARENT
        }
    }

    /** An air-defence or supply battalion protects or supplies brigade [to] (its cargo id), staying a parent. */
    fun setSupports(doc: CommandSave, b: CmdBattalion, to: CmdBrigade?): CommandEdit = UnitEdit.mutate(if (to == null) "${AirOps.nameOf(doc, b)} supports no brigade" else "${AirOps.nameOf(doc, b)} supports brigade ${to.nameId}", doc) {
        val sub = subOf(doc, b)
        if (to != null && sub != SUB_AIR_DEFENCE && sub != SUB_SUPPLY) UnitEdit.refuse("only an air-defence or a supply battalion protects or supplies a brigade")
        val from = doc.unit(b.parentId) as? CmdBrigade
        touch(b); touch(from)
        from?.elements?.removeAll { it == b.id }
        b.parentId = VuId.NONE
        b.cargoId = to?.id ?: VuId.NONE
        b.unitFlags = b.unitFlags or U_PARENT
        if (to != null) b.division = to.division
    }

    /** The brigade's orders, owner or division given to its battalions as well. */
    fun pushToElements(doc: CommandSave, br: CmdBrigade, what: String): CommandEdit = UnitEdit.mutate("$what of brigade ${br.nameId} to its battalions", doc) {
        val els = doc.battalionsOf(br)
        if (els.isEmpty()) UnitEdit.refuse("the brigade has no battalions")
        els.forEach { b ->
            touch(b)
            when (what) { "Orders" -> b.orders = br.orders; "Owner" -> b.owner = br.owner; "Division" -> b.division = br.division; "Objective" -> { b.aobj = br.aobj; b.destX = br.destX; b.destY = br.destY } }
        }
    }

    /**
     * An air-defence battalion on objective [o]: BMS stands its vehicles on the objective's SAM and AAA sites (the
     * point lists of types 4 and 6: launcher, gun, radar and support spots). A sentence when the battalion has more
     * vehicles than the objective has spots, else null.
     */
    fun siteShortfall(doc: CommandSave, type: Int, o: CmdObjective): String? {
        val cls = UnitClasses.of(doc)
        if (cls.clsOfType(type)?.sub != SUB_AIR_DEFENCE) return null
        val vehicles = UnitClasses.rosterTotal(cls.maxRoster(type))
        val spots = cls.pointCount(o, 4, setOf(4, 6, 7, 9)) + cls.pointCount(o, 6, setOf(4, 6, 7, 9))
        if (spots == 0) return "${objName(doc, o)} has no SAM or AAA sites: the battalion's vehicles stand wherever BMS finds room."
        return if (vehicles > spots) "${objName(doc, o)} has $spots SAM/AAA spots for the battalion's $vehicles vehicles." else null
    }
}
