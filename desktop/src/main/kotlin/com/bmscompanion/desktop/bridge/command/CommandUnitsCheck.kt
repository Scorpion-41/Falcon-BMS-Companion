package com.bmscompanion.desktop.bridge.command

import com.bmscompanion.desktop.bridge.Theaters
import java.io.File

/**
 * The unit editor's part of `--cmdroundtrip` (on a **copy**): what the saves of the copy hold, counted the way the
 * editor relies on (how a flight's roster follows its aircraft, what a squadron's header row carries…), then a
 * handful of unit edits — add, clone and delete a flight, a waypoint's time with the route retimed, a squadron moved
 * with its flights — each written, read back and compared, and undone back to the original bytes.
 */
object CommandUnitsCheck {
    fun run(set: Theaters.TheaterSet, folders: List<File>): String = facts(set, folders) + edits(set, folders)

    /** The modelled parts' bytes as the model has them now. */
    private fun parts(doc: CommandSave): Map<String, ByteArray> = buildMap {
        doc.uni?.let { put("uni", it.encode()) }
        doc.cmp?.let { put("cmp", it.encode()) }
        doc.plt?.let { put("plt", it.encode()) }
    }

    private fun same(a: Map<String, ByteArray>, b: Map<String, ByteArray>): String? =
        a.keys.firstOrNull { k -> b[k]?.contentEquals(a[k]) != true }?.let { ".$it differs" }

    /** Written as a file and read back: every modelled part walks exactly and reads back to what the model holds. */
    private fun rereads(doc: CommandSave, set: Theaters.TheaterSet): String? {
        val (bytes, _, why) = CommandWriter.build(doc, null)
        if (bytes == null) return why ?: "not put together"
        CommandWriter.verify(doc, bytes, doc.file)?.let { return it }
        val back = CommandSave.fromBytes(doc.file, bytes, set, withStart = false)
        if (!back.partEditable("uni")) return "the unit part does not walk exactly (${back.problems.firstOrNull()})"
        return same(parts(doc), parts(back))
    }

    fun edits(set: Theaters.TheaterSet, folders: List<File>): String = buildString {
        appendLine("## unit edits")
        val doc = folders.asSequence().flatMap { d -> d.listFiles()?.filter { it.isFile && ShippedSaves.isSave(it) && it.extension.equals("cam", true) }.orEmpty().sortedBy { it.name.lowercase() }.asSequence() }
            .map { CommandSave.open(it, set) }
            .firstOrNull { d -> d.uni?.editable == true && d.obj == null && d.flights.size > 20 && d.squadrons.isNotEmpty() && d.brigades.isNotEmpty() }
        if (doc == null) { appendLine("FAIL: no played campaign save with flights, squadrons and brigades"); return@buildString }
        appendLine("save: ${doc.file.name} (${doc.flights.size} flights, ${doc.squadrons.size} squadrons, ${doc.battalions.size} battalions)")
        val orig = parts(doc)
        var step = 0
        fun check(what: String, e: () -> CommandEdit, verify: () -> String? = { null }) {
            step++
            val before = parts(doc)
            val why = doc.edits.run(e())
            if (why != null) { appendLine("FAIL $step $what: refused: $why"); return }
            val bad = verify() ?: rereads(doc, set)
            appendLine((if (bad == null) "PASS" else "FAIL") + ": $step $what — written and read back exactly${bad?.let { " ($it)" } ?: ""}")
            val after = parts(doc)
            doc.edits.undo()
            val u = same(before, parts(doc))
            doc.edits.redo()
            val r = same(after, parts(doc))
            doc.edits.undo()
            appendLine((if (u == null && r == null) "PASS" else "FAIL") + ": $step undo gives the parts back byte for byte, redo the edit again${u?.let { " (undo: $it)" } ?: ""}${r?.let { " (redo: $it)" } ?: ""}")
        }
        fun refused(what: String, e: () -> CommandEdit) {
            val before = parts(doc)
            val why = doc.edits.run(e())
            appendLine((if (why != null && same(before, parts(doc)) == null) "PASS" else "FAIL") + ": $what is refused, nothing changed (${why ?: "it was done"})")
            if (why == null) doc.edits.undo()
        }
        val sq = doc.squadrons.first { s -> doc.flights.any { it.squadronId == s.id } && UnitClasses.of(doc).flightTypeFor(s.type) != null }
        val now = doc.cmp?.currentTime ?: 0L
        check("add a flight to ${AirOps.nameOf(doc, sq)}", { UnitLife.addFlight(doc, sq, 2, 0, now + AirOps.HOUR_MS) }) {
            val f = doc.flights.lastOrNull { it.squadronId == sq.id && it.waypoints.size == 2 } ?: return@check "no new flight"
            val p = doc.packageOf(f) ?: return@check "the new flight has no package"
            when {
                f.id !in p.elements -> "its package does not list it"
                CommandIds.callsignTaken(doc, f.callsignId, f.callsignNum, except = f) != null -> "its callsign is taken"
                f.pilots.take(2).any { it == AirOps.NO_PILOT } -> "it has no pilots"
                f.pilots.take(2).any { sq.pilots[it].status != AirOps.PILOT_IN_USE } -> "its pilots are not marked flying"
                else -> null
            }
        }
        val multi = doc.packages.firstOrNull { it.elements.size in 2..4 }
        val fl = multi?.let { doc.flightsOf(it).first() } ?: doc.flights.first()
        check("clone ${doc.callsign(fl)}", { UnitLife.clone(doc, fl) }) {
            val c = doc.flights.last { it !== fl && it.type == fl.type && it.waypoints.size == fl.waypoints.size && it.campId != fl.campId && it.id.num > fl.id.num }
            when {
                c.callsignId == fl.callsignId && c.callsignNum == fl.callsignNum -> "the clone has the same callsign"
                c.pilots.take(4).any { p -> p != AirOps.NO_PILOT && p in fl.pilots } -> "the clone shares pilots"
                doc.packageOf(c)?.elements?.contains(c.id) != true -> "the clone is in no package"
                else -> null
            }
        }
        check("delete ${doc.callsign(fl)} (package of ${multi?.elements?.size ?: 1})", { UnitLife.delete(doc, fl) }) {
            if (doc.unit(fl.id) != null) "still there" else if (multi != null && fl.id in multi.elements) "still in its package" else null
        }
        val lone = doc.packages.firstOrNull { it.elements.size == 1 }?.let { doc.flightsOf(it).firstOrNull() }
        if (lone != null) check("delete ${doc.callsign(lone)}, alone in its package", { UnitLife.delete(doc, lone) }) {
            if (doc.packages.any { lone.id in it.elements }) "its package is still there" else null
        }
        val fw = doc.flights.firstOrNull { f -> f.waypoints.size >= 5 && f.waypoints.any { AirOps.timeLocked(it) } } ?: doc.flights.first { it.waypoints.size >= 4 }
        val locked = fw.waypoints.mapIndexedNotNull { i, w -> if (AirOps.timeLocked(w) && i != 2) i to w.arrive else null }
        check("waypoint 3 of ${doc.callsign(fw)} 10 min later, route retimed (${locked.size} locked)", { AirOps.setArrival(doc, fw, 2, fw.waypoints[2].arrive + 600_000, true) }) {
            val moved = locked.filter { (i, t) -> fw.waypoints[i].arrive != t }
            when {
                moved.isNotEmpty() -> "locked waypoints moved: ${moved.map { it.first + 1 }}"
                (1 until fw.waypoints.size).any { fw.waypoints[it].arrive in 1 until fw.waypoints[it - 1].depart && fw.waypoints[it - 1].depart > 0 } -> "the times run backwards"
                else -> null
            }
        }
        check("shift ${doc.callsign(fw)} by +30 min", { AirOps.shiftTimes(doc, listOf(fw), 1_800_000, "Shift") })
        val ld = doc.flights.firstOrNull { f -> UnitClasses.of(doc).hardpoints(f.type).any { it.loadable } }
        if (ld != null) {
            val h = UnitClasses.of(doc).hardpoints(ld.type).first { it.loadable }
            val (w, max) = h.allowed.first()
            check("hardpoint ${h.n} of ${doc.callsign(ld)} carries ${UnitClasses.of(doc).weaponName(w)} × $max", { AirOps.setStore(doc, ld, (0 until ld.aircraft).toSet(), h.n, w, max) })
            refused("99 of weapon 1 on hardpoint ${h.n}") { AirOps.setStore(doc, ld, setOf(0), h.n, 1, 99) }
            val taken = doc.flights.first { it !== ld }
            refused("a callsign another flight flies") { AirOps.setCallsign(doc, ld, taken.callsignId, taken.callsignNum) }
        }
        val cls = UnitClasses.of(doc)
        val other = doc.objectives.firstOrNull { o -> cls.clsOfType(o.type)?.type == 1 && o.id != sq.airbaseId && AirOps.allied(doc, sq.owner, doc.ownerNow(o)) }
        if (other != null) check("move ${AirOps.nameOf(doc, sq)} to ${GroundOps.objName(doc, other)} with its flights", { GroundOps.moveBase(doc, sq, other) }) {
            val (x, y) = GroundOps.objCell(other)
            val si = doc.squadInfo(sq)
            val f = doc.flights.filter { it.squadronId == sq.id }
            when {
                sq.x != x || sq.y != y -> "the squadron is not on it"
                f.any { it.waypoints.firstOrNull()?.let { w -> w.gridX != x || w.gridY != y || w.targetId != other.id } == true } -> "a flight's take-off did not follow"
                si == null || si.airbaseName.text != GroundOps.objName(doc, other) -> "the header's row did not follow"
                else -> null
            }
        }
        check("resupply every squadron", { UnitBulk.resupplyAll(doc) })
        val bde = doc.brigades.first { it.elements.size < 5 }
        val free = doc.battalions.firstOrNull { it.parentId.isNone && AirOps.allied(doc, it.owner, bde.owner) && cls.clsOfType(it.type)?.sub != GroundOps.SUB_AIR_DEFENCE }
        if (free != null) check("battalion ${free.nameId} joins brigade ${bde.nameId}", { GroundOps.setBrigade(doc, free, bde) }) {
            if (free.id !in bde.elements || free.parentId != bde.id || free.unitFlags and GroundOps.U_PARENT != 0) "the link is not both ways" else null
        }
        val type = cls.typesOf(UnitKind.BATTALION).first().type
        check("add a battalion", { UnitLife.addBattalion(doc, type, bde.owner, bde.x + 2, bde.y, null) })
        check("delete brigade ${bde.nameId}, keeping its battalions", { UnitLife.delete(doc, bde) }) {
            if (doc.battalions.any { it.parentId == bde.id }) "a battalion still names it" else null
        }
        check("clone task force", { UnitLife.clone(doc, doc.taskForces.first(), doc.taskForces.first().x + 3 to doc.taskForces.first().y) })
        val issues = UnitChecks.run(doc)
        appendLine("unit checks: ${issues.size} issues (${issues.count { it.fix != null }} with a fix): " + issues.groupBy { it.group }.entries.joinToString { "${it.key} ${it.value.size}" })
        if (issues.any { it.fix != null }) {
            val before = issues.count { it.fix != null }
            val why = doc.edits.run(UnitChecks.fixAll(doc))
            val after = UnitChecks.run(doc).count { it.fix != null }
            val bad = rereads(doc, set)
            appendLine((if (why == null && after < before && bad == null) "PASS" else "FAIL") + ": fix all: $before fixable → $after${why?.let { " ($it)" } ?: ""}${bad?.let { " ($it)" } ?: ""}")
            doc.edits.undo()
            appendLine((if (same(orig, parts(doc)) == null) "PASS" else "FAIL") + ": undo of fix all gives the parts back")
        }
        // all at once, saved as a copy and read from disk
        val target = File(doc.file.absoluteFile.parentFile, "RT units.cam")
        listOf(UnitLife.addFlight(doc, sq, 4, 0, now + 2 * AirOps.HOUR_MS), UnitLife.clone(doc, fl), AirOps.setArrival(doc, fw, 2, fw.waypoints[2].arrive + 300_000, true), UnitBulk.resetSpotted(doc))
            .forEach { e -> doc.edits.run(e)?.let { appendLine("FAIL: $it") } }
        val r = CommandWriter.saveAs(doc, target, replace = true)
        if (!r.ok) appendLine("FAIL: save as ${target.name}: ${r.message}")
        else {
            val back = CommandSave.open(target, set)
            val d = same(parts(doc).filterKeys { it != "cmp" }, parts(back).filterKeys { it != "cmp" })
            appendLine((if (d == null && back.flights.size == doc.flights.size) "PASS" else "FAIL") + ": four edits saved as ${target.name} and read from disk: ${back.flights.size} flights${d?.let { ", $it" } ?: ", parts as edited"}")
            r.companions.forEach { File(target.parentFile, it).delete() }
            target.delete()
        }
        while (doc.edits.undo()) Unit
        appendLine((if (same(orig, parts(doc)) == null) "PASS" else "FAIL") + ": every edit undone gives the original parts")
    }

    fun facts(set: Theaters.TheaterSet, folders: List<File>): String = buildString {
        appendLine("## unit facts")
        val rosterByCount = HashMap<Int, HashMap<Int, Int>>()
        val notDepartedWp = HashMap<Int, Int>()
        val notDepartedAtWp0 = intArrayOf(0, 0)
        val fuelInit = HashMap<Int, Int>()
        val loadouts = HashMap<Int, Int>()
        val strength = intArrayOf(0, 0)
        val sqPos = intArrayOf(0, 0)
        val battDest = HashMap<String, Int>()
        val battParentFlag = HashMap<String, Int>()
        val skills = HashMap<Int, Int>()
        val status = HashMap<Int, Int>()
        val pltBits = intArrayOf(0, 0)
        val fuelRatio = ArrayList<Double>()
        val names = ArrayList<String>()
        val forms = HashMap<Int, Int>()
        val locks = intArrayOf(0, 0)
        val zs = ArrayList<String>()
        var saves = 0
        for (dir in folders) for (f in dir.listFiles()?.filter { it.isFile && ShippedSaves.isSave(it) }.orEmpty().sortedBy { it.name.lowercase() }) {
            val doc = CommandSave.open(f, set)
            if (doc.uni?.editable != true) continue
            saves++
            val now = doc.cmp?.currentTime ?: 0L
            val cls = UnitClasses.of(doc)
            for (fl in doc.flights) {
                val n = fl.aircraft
                rosterByCount.getOrPut(n) { HashMap() }.merge(fl.roster, 1, Int::plus)
                fl.waypoints.forEach { w -> forms.merge(w.formation, 1, Int::plus); if (w.flags and 0x4000L != 0L) locks[0]++; if (w.flags and 0x8000L != 0L) locks[1]++ }
                if (zs.size < 8) zs += "z=${fl.z} wp${fl.currentWp} gz=${fl.waypoints.getOrNull(fl.currentWp)?.gridZ}"
                loadouts.merge(fl.loadouts.size, 1, Int::plus)
                fl.fuelInitial.take(n).forEach { fuelInit.merge((it / 500) * 500, 1, Int::plus) }
                val w0 = fl.waypoints.firstOrNull()
                if (w0 != null && w0.depart > now) {
                    notDepartedWp.merge(fl.currentWp, 1, Int::plus)
                    notDepartedAtWp0[if (fl.x == w0.gridX && fl.y == w0.gridY) 0 else 1]++
                }
            }
            for (sq in doc.squadrons) {
                val si = doc.squadInfo(sq)
                if (si != null) {
                    strength[if (si.currentStrength == UnitClasses.rosterTotal(sq.roster)) 0 else 1]++
                    val cx = (si.y / CELL).toInt(); val cy = (si.x / CELL).toInt()
                    sqPos[if (cx == sq.x && cy == sq.y) 0 else 1]++
                    if (names.size < 6) names += "${si.squadronName.text} @ ${si.airbaseName.text} icon ${si.airbaseIcon} desc ${si.descriptionIndex} type ${sq.type} flags ${si.flags.toString(16)}/${sq.unitFlags.toString(16)}"
                }
                cls.ucd(sq)?.int("Fuel")?.takeIf { it > 0 }?.let { if (fuelRatio.size < 30) fuelRatio += sq.fuel.toDouble() / it }
                sq.pilots.forEach { p -> skills.merge(p.skillAndRating, 1, Int::plus); status.merge(p.status, 1, Int::plus) }
            }
            for (b in doc.battalions) {
                battDest.merge("${b.destX - b.x},${b.destY - b.y}", 1, Int::plus)
                battParentFlag.merge("${if (b.parentId.isNone) "free" else "inBrigade"} parent=${b.unitFlags and 0x20 != 0}", 1, Int::plus)
            }
            val plt = doc.plt
            if (plt != null) {
                val want = CommandIds.callsignUsage(doc)
                pltBits[if (want.contentEquals(plt.callsigns)) 0 else 1]++
            }
        }
        appendLine("saves read: $saves")
        appendLine("flight roster by aircraft count: " + rosterByCount.toSortedMap().entries.joinToString { (n, m) -> "$n ac → " + m.entries.sortedByDescending { it.value }.take(4).joinToString("/") { "${it.key}×${it.value}" } })
        appendLine("loadouts per flight: $loadouts")
        appendLine("fuel_initial per seat (500 lb bins): " + fuelInit.toSortedMap().entries.take(20).joinToString { "${it.key}:${it.value}" })
        appendLine("not departed: current_wp $notDepartedWp; at wp0 cell ${notDepartedAtWp0[0]}, elsewhere ${notDepartedAtWp0[1]}")
        appendLine("squad info strength = roster total: ${strength[0]} yes / ${strength[1]} no; position = cell: ${sqPos[0]} / ${sqPos[1]}")
        names.forEach { appendLine("  squad info: $it") }
        appendLine("squadron fuel / UCD Fuel: " + fuelRatio.joinToString { "%.3f".format(it) })
        appendLine("battalion dest − cell: " + battDest.entries.sortedByDescending { it.value }.take(6).joinToString { "${it.key}×${it.value}" })
        appendLine("battalion parent flag: $battParentFlag")
        appendLine("pilot skill: " + skills.entries.sortedByDescending { it.value }.take(12).joinToString { "${it.key}×${it.value}" } + "; status $status")
        appendLine("waypoint formation bytes: " + forms.entries.sortedByDescending { it.value }.take(12).joinToString { "${it.key}×${it.value}" } + "; time locks ${locks[0]}, speed locks ${locks[1]}")
        appendLine("flight z: " + zs.joinToString("; "))
        appendLine("plt callsign bits as the flights say: ${pltBits[0]} yes / ${pltBits[1]} no")
    }

    private const val CELL = com.bmscompanion.desktop.bridge.CampaignArchive.FEET_PER_CELL
}
