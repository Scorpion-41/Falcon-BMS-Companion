package com.bmscompanion.desktop.bridge.command

import java.util.concurrent.CopyOnWriteArrayList

/** How much a check's finding matters: BMS trips over an [ERROR], a [WARNING] works but is likely a mistake. */
enum class CheckSeverity { ERROR, WARNING, INFO }

/** What a finding is about, for the page to select it: [kind] "objective", "flight", "package", "squadron", "battalion", "brigade", "taskforce", "team"; [id] "num/creator" or the team number. */
class CheckRef(val kind: String, val id: String)

/**
 * One finding of the Checks page: its [group] ("Ids", "References" …), what is wrong, what it is about, and its fix —
 * [fix] makes the undoable edit when the button is pressed (null: nothing to fix automatically, the text says what
 * to do). A fix runs through the save's edit log like any edit, so it is counted and undone the same way.
 */
class CheckIssue(
    val group: String,
    val severity: CheckSeverity,
    val text: String,
    val ref: CheckRef? = null,
    val fixLabel: String? = null,
    val fix: (() -> CommandEdit?)? = null,
)

/**
 * The Checks page's report: everything in an open save Falcon BMS would trip over or that is likely a mistake, each
 * with its fix where one is safe. Other editors add their own checks to [providers] (one function from the open save
 * to its findings; the unit editor's go there), and the page lists every provider's findings with these.
 */
object CommandChecks {
    /** Further checks (the unit editor's …): each is run on the open save; one that throws is reported, not fatal. */
    val providers = CopyOnWriteArrayList<(CommandSave) -> List<CheckIssue>>()

    fun run(doc: CommandSave): List<CheckIssue> {
        val out = ArrayList<CheckIssue>()
        fun safe(name: String, f: () -> List<CheckIssue>) {
            try { out += f() } catch (t: Throwable) { out += CheckIssue(name, CheckSeverity.WARNING, "The $name check could not finish: ${CommandFiles.reason(t)}.") }
        }
        safe("File") { file(doc) }
        safe("Ids") { ids(doc) }
        safe("References") { references(doc) }
        safe("Squadron list") { squadInfo(doc) }
        // (packages of more than five flights and two flights on one callsign: the unit editor's checks, with their fixes)
        safe("Rosters") { rosters(doc) }
        safe("Objective changes") { deltas(doc) }
        safe("Objective links") { links(doc) }
        safe("Air defence") { samSpots(doc) }
        safe("Callsigns") { callsigns(doc) }
        safe("Teams") { teams(doc) }
        for (p in providers) safe("Units") { p(doc) }
        return out.distinctBy { it.group + "|" + it.text }
    }

    private fun kindOf(u: CmdUnit): String = when (u.kind) {
        UnitKind.FLIGHT -> "flight"; UnitKind.PACKAGE -> "package"; UnitKind.SQUADRON -> "squadron"
        UnitKind.BATTALION -> "battalion"; UnitKind.BRIGADE -> "brigade"; UnitKind.TASKFORCE -> "taskforce"
    }

    private fun ref(u: CmdUnit) = CheckRef(kindOf(u), u.id.toString())
    private fun ref(o: CmdObjective) = CheckRef("objective", o.id.toString())

    fun unitName(doc: CommandSave, u: CmdUnit): String = UnitNames.full(doc, u)

    private fun file(doc: CommandSave): List<CheckIssue> = doc.problems.map { CheckIssue("File", CheckSeverity.WARNING, it) }

    private fun ids(doc: CommandSave): List<CheckIssue> {
        val out = ArrayList<CheckIssue>()
        val byId = HashMap<VuId, MutableList<String>>()
        doc.objectives.forEach { byId.getOrPut(it.id) { ArrayList() } += ObjectiveOps.displayName(doc, it) }
        doc.units.forEach { byId.getOrPut(it.id) { ArrayList() } += unitName(doc, it) }
        byId.filter { it.value.size > 1 }.forEach { (id, who) -> out += CheckIssue("Ids", CheckSeverity.ERROR, "Id $id is used ${who.size} times: ${who.take(4).joinToString()}. BMS finds only one of them.") }
        val byCamp = doc.units.groupBy { it.campId }.filter { it.key != 0 && it.value.size > 1 }
        for ((c, us) in byCamp) {
            val later = us.drop(1)
            out += CheckIssue("Ids", CheckSeverity.WARNING, "CampId $c is carried by ${us.size} units: ${us.take(4).joinToString { unitName(doc, it) }}.", ref(us[0]),
                "New CampIds for ${later.size}") {
                // the free CampIds are found when the fix is applied, so the fixes of a Fix all never take the same one
                UnitEdit.mutate("New CampIds for ${later.size} unit${if (later.size == 1) "" else "s"}", doc) {
                    val used = CommandIds.campIdsInUse(doc).toHashSet()
                    var next = (used.maxOrNull() ?: 0) + 1
                    for (u in later) {
                        touch(u)
                        while (next in used) next++
                        u.campId = next
                        used += next++
                    }
                }
            }
        }
        if (doc.objectivesFromOwn) doc.objectives.groupBy { it.campId }.filter { it.value.size > 1 }.forEach { (c, os) ->
            out += CheckIssue("Ids", CheckSeverity.WARNING, "CampId $c is carried by ${os.size} objectives: ${os.take(4).joinToString { ObjectiveOps.displayName(doc, it) }} (Stations+Ils.dat and the TE lists name objectives by CampId).", ref(os[0]))
        }
        return out
    }

    private fun references(doc: CommandSave): List<CheckIssue> {
        val out = ArrayList<CheckIssue>()
        val units = doc.units.associateBy { it.id }
        val objs = doc.objectives.associateBy { it.id }
        fun exists(id: VuId) = id.isNone || units.containsKey(id) || objs.containsKey(id)
        for (u in doc.units) {
            val n = unitName(doc, u)
            when (u) {
                is CmdFlight -> {
                    if (!u.packageId.isNone && units[u.packageId] !is CmdPackage) out += CheckIssue("References", CheckSeverity.ERROR, "$n belongs to package ${u.packageId}, which is not in the file.", ref(u))
                    if (!u.squadronId.isNone && units[u.squadronId] !is CmdSquadron) out += CheckIssue("References", CheckSeverity.ERROR, "$n flies for squadron ${u.squadronId}, which is not in the file.", ref(u))
                }
                is CmdPackage -> {
                    val missing = u.elements.filter { !it.isNone && units[it] !is CmdFlight }
                    if (missing.isNotEmpty()) out += CheckIssue("References", CheckSeverity.ERROR, "$n lists ${missing.size} flight${if (missing.size == 1) "" else "s"} not in the file (${missing.take(3).joinToString()}).", ref(u),
                        "Drop them") { Edits.group("Drop missing flights from $n", missing.map { m -> Edits.remove("Drop a missing flight", "uni", u.elements, u.elements.first { it == m }) }) }
                    val support = listOf("interceptor" to u.interceptor, "AWACS" to u.awacs, "JSTARS" to u.jstar, "ECM" to u.ecm, "tanker" to u.tanker).filter { !exists(it.second) }
                    if (support.isNotEmpty()) out += CheckIssue("References", CheckSeverity.WARNING, "$n's ${support.joinToString { it.first }} ${if (support.size == 1) "is" else "are"} not in the file.", ref(u),
                        "Clear") {
                        Edits.group("Clear $n's missing support", listOfNotNull(
                            if (!exists(u.interceptor)) Edits.set("", "uni", { u.interceptor }, { u.interceptor = it }, VuId.NONE) else null,
                            if (!exists(u.awacs)) Edits.set("", "uni", { u.awacs }, { u.awacs = it }, VuId.NONE) else null,
                            if (!exists(u.jstar)) Edits.set("", "uni", { u.jstar }, { u.jstar = it }, VuId.NONE) else null,
                            if (!exists(u.ecm)) Edits.set("", "uni", { u.ecm }, { u.ecm = it }, VuId.NONE) else null,
                            if (!exists(u.tanker)) Edits.set("", "uni", { u.tanker }, { u.tanker = it }, VuId.NONE) else null,
                        ))
                    }
                }
                is CmdSquadron -> if (!u.airbaseId.isNone && objs.isNotEmpty() && !exists(u.airbaseId)) out += CheckIssue("References", CheckSeverity.WARNING, "$n is based at ${u.airbaseId}, which is neither an objective nor a unit of the file: move it to an airbase.", ref(u))
                is CmdBrigade -> {
                    val missing = u.elements.filter { !it.isNone && units[it] !is CmdBattalion }
                    if (missing.isNotEmpty()) out += CheckIssue("References", CheckSeverity.ERROR, "$n lists ${missing.size} battalion${if (missing.size == 1) "" else "s"} not in the file.", ref(u),
                        "Drop them") { Edits.group("Drop missing battalions from $n", missing.map { m -> Edits.remove("Drop a missing battalion", "uni", u.elements, u.elements.first { it == m }) }) }
                }
                is CmdBattalion -> if (!u.parentId.isNone && units[u.parentId] !is CmdBrigade) out += CheckIssue("References", CheckSeverity.WARNING, "$n's brigade ${u.parentId} is not in the file.", ref(u),
                    "Clear it") { Edits.set("Clear $n's missing brigade", "uni", { u.parentId }, { u.parentId = it }, VuId.NONE) }
                is CmdTaskForce -> Unit
            }
            if (objs.isEmpty()) continue // the start's objectives are not known: nothing to hold the rest against
            if (u is CmdGroundUnit && !u.aobj.isNone && !objs.containsKey(u.aobj)) out += CheckIssue("References", CheckSeverity.WARNING, "$n is ordered to objective ${u.aobj}, which the file's objectives do not have.", ref(u),
                "Clear the order") { Edits.set("Clear $n's missing objective", "uni", { u.aobj }, { u.aobj = it }, VuId.NONE) }
            // (targets, cargo and waypoints' targets naming nothing: the unit editor's own check, a provider)
        }
        if (doc.objectivesFromOwn) for (o in doc.objectives) if (!o.parent.isNone && !objs.containsKey(o.parent)) {
            out += CheckIssue("References", CheckSeverity.WARNING, "${ObjectiveOps.displayName(doc, o)}'s parent ${o.parent} is not an objective.", ref(o),
                "Clear it") { Edits.set("Clear a missing parent", "obj", { o.parent }, { o.parent = it }, VuId.NONE) }
        }
        doc.pol?.let { pol ->
            val missing = pol.entries.filter { !objs.containsKey(it.id) }
            if (missing.isNotEmpty() && objs.isNotEmpty()) out += CheckIssue("References", CheckSeverity.WARNING, "The primary objective list names ${missing.size} objective${if (missing.size == 1) "" else "s"} the start does not have.", null,
                "Drop them") { Edits.group("Drop missing primary objectives", missing.map { Edits.remove("Drop a missing primary objective", "pol", pol.entries, it) }) }
        }
        return out
    }

    private fun squadInfo(doc: CommandSave): List<CheckIssue> {
        val out = ArrayList<CheckIssue>()
        val orphans = CampaignOps.orphanSquadInfo(doc)
        if (orphans.isNotEmpty()) out += CheckIssue("Squadron list", CheckSeverity.WARNING,
            "The header lists ${orphans.size} squadron${if (orphans.size == 1) "" else "s"} the file has no unit for: ${orphans.take(4).joinToString { it.squadronName.text.trim().ifEmpty { it.id.toString() } }}.", null,
            "Remove ${if (orphans.size == 1) "it" else "them"}") { CampaignOps.removeSquadInfo(doc, CampaignOps.orphanSquadInfo(doc), "Remove ${orphans.size} squadron list entries with no unit") }
        val unlisted = CampaignOps.unlistedSquadrons(doc)
        if (unlisted.isNotEmpty() && doc.cmp?.squadInfo?.isNotEmpty() == true) out += CheckIssue("Squadron list", CheckSeverity.WARNING,
            "${unlisted.size} squadron${if (unlisted.size == 1) " is" else "s are"} missing from the header's list (BMS's screens show no name or base for ${if (unlisted.size == 1) "it" else "them"}): ${unlisted.take(4).joinToString { unitName(doc, it) }}.",
            ref(unlisted[0]), "Add ${if (unlisted.size == 1) "it" else "them"}") { CampaignOps.addSquadInfo(doc, CampaignOps.unlistedSquadrons(doc), "List ${unlisted.size} squadrons in the header") }
        val units = doc.squadrons.associateBy { it.id }
        doc.cmp?.squadInfo.orEmpty().forEach { si ->
            val s = units[si.id] ?: return@forEach
            if (si.campId != s.campId) out += CheckIssue("Squadron list", CheckSeverity.INFO, "${si.squadronName.text.trim()}: the header says CampId ${si.campId}, the unit ${s.campId}.", ref(s),
                "Use the unit's") { Edits.set("Squadron list CampId", "cmp", { si.campId }, { si.campId = it }, s.campId) }
        }
        return out
    }

    private fun rosters(doc: CommandSave): List<CheckIssue> {
        val cls = runCatching { UnitClasses.of(doc) }.getOrNull() ?: return emptyList()
        val out = ArrayList<CheckIssue>()
        for (u in doc.units) {
            // a flight's roster is its aircraft's places, not the class's vehicle slots
            if (u is CmdPackage || u is CmdFlight) continue
            val max = cls.maxRoster(u.type)
            if (max == 0) continue
            val over = (0 until 16).filter { k -> ((u.roster ushr (2 * k)) and 3) > ((max ushr (2 * k)) and 3) }
            if (over.isEmpty()) continue
            val clamped = (0 until 16).fold(0) { r, k -> r or (minOf((u.roster ushr (2 * k)) and 3, (max ushr (2 * k)) and 3) shl (2 * k)) }
            out += CheckIssue("Rosters", CheckSeverity.WARNING, "${unitName(doc, u)} carries more than its class allows in slot${if (over.size == 1) "" else "s"} ${over.joinToString { "${it + 1}" }}.", ref(u),
                "Cut to the class's") { Edits.set("Roster of ${unitName(doc, u)} cut to its class's", "uni", { u.roster }, { u.roster = it }, clamped) }
        }
        return out
    }

    private fun deltas(doc: CommandSave): List<CheckIssue> {
        val out = ArrayList<CheckIssue>()
        if (doc.deltasIgnored && (doc.obd?.count ?: 0) > 0) out += CheckIssue("Objective changes", CheckSeverity.INFO,
            "${doc.file.name} names itself as its start, so BMS ignores its ${doc.obd!!.count} objective change${if (doc.obd.count == 1) "" else "s"}.")
        val unknown = doc.unknownDeltas()
        if (unknown.isNotEmpty()) out += CheckIssue("Objective changes", CheckSeverity.ERROR,
            "${unknown.size} objective change${if (unknown.size == 1) " names" else "s name"} an objective the start does not have (${unknown.take(3).joinToString { it.id.toString() }}): BMS stops reading the changes there and drops every one after it.",
            null, "Remove ${if (unknown.size == 1) "it" else "them"}") { ObjectiveOps.removeDeltas(doc, doc.unknownDeltas(), "Remove ${unknown.size} changes naming no objective") }
        val idle = ObjectiveOps.idleDeltas(doc) - unknown.toSet()
        if (idle.isNotEmpty() && !doc.deltasIgnored) out += CheckIssue("Objective changes", CheckSeverity.INFO,
            "${idle.size} objective change${if (idle.size == 1) " is" else "s are"} the same as the start.", null,
            "Clean") { ObjectiveOps.removeDeltas(doc, ObjectiveOps.idleDeltas(doc) - doc.unknownDeltas().toSet(), "Clean ${idle.size} changes equal to the start") }
        return out
    }

    private fun links(doc: CommandSave): List<CheckIssue> {
        if (!doc.objectivesFromOwn || doc.objectives.isEmpty()) return emptyList()
        return ObjectiveOps.checkLinks(doc).map { li ->
            // BMS's own starts carry one-way links (111 in Korea's Save1, mostly to bridges): a note, not a warning
            CheckIssue("Objective links", if (li.fix != null && !li.text.contains("no link back")) CheckSeverity.WARNING else CheckSeverity.INFO, li.text, ref(li.objective),
                if (li.fix != null) "Fix" else null, li.fix?.let { f -> { f } })
        }
    }

    private fun samSpots(doc: CommandSave): List<CheckIssue> {
        val names = doc.names ?: return emptyList()
        val out = ArrayList<CheckIssue>()
        val objs = doc.objectives.associateBy { it.id }
        for (b in doc.battalions) {
            if (names.ct(b.type)?.subType != 1) continue
            val o = objs[b.aobj] ?: continue
            if (b.x != o.x || b.y != o.y) continue
            val ocd = ObjectiveOps.ocdOf(doc, o)
            val spots = doc.tables.pointHeaders(ocd).count { it.type == 4 || it.type == 6 }
            val sam = o.objFlags and 0x400 != 0
            if (spots == 0 && doc.tables.ocd.isNotEmpty()) out += CheckIssue("Air defence", CheckSeverity.INFO,
                "${unitName(doc, b)} stands at ${ObjectiveOps.displayName(doc, o)}, which has no SAM or AAA spots: BMS places its vehicles round it as it can.", ref(b))
            if (!sam) out += CheckIssue("Air defence", CheckSeverity.INFO, "${unitName(doc, b)} stands at ${ObjectiveOps.displayName(doc, o)}, which is not marked SAM capable.", ref(o),
                if (doc.objectivesEditable) "Mark it" else null,
                if (doc.objectivesEditable) ({ ObjectiveOps.setFlag(doc, o, 0x400, true) }) else null)
        }
        return out
    }

    private fun callsigns(doc: CommandSave): List<CheckIssue> {
        val out = ArrayList<CheckIssue>()
        val plt = doc.plt ?: return out
        val want = CommandIds.callsignUsage(doc)
        val diff = plt.callsigns.indices.count { i -> (plt.callsigns[i] and 0xFF) != want.getOrElse(i) { 0 } }
        if (diff > 0 && plt.editable) out += CheckIssue("Callsigns", CheckSeverity.INFO, "$diff callsign${if (diff == 1) "'s" else "s'"} usage differ${if (diff == 1) "s" else ""} from the flights that fly ${if (diff == 1) "it" else "them"}.", null,
            "Recount from the flights") { CampaignOps.recountCallsigns(doc) }
        return out
    }

    private fun teams(doc: CommandSave): List<CheckIssue> {
        val out = ArrayList<CheckIssue>()
        val n = doc.teams.size
        if (n == 0) return out
        val badUnits = doc.units.filter { it.owner >= n }
        if (badUnits.isNotEmpty()) out += CheckIssue("Teams", CheckSeverity.ERROR, "${badUnits.size} unit${if (badUnits.size == 1) " belongs" else "s belong"} to team numbers the file does not have (${badUnits.map { it.owner }.distinct().joinToString()}).", ref(badUnits[0]))
        val badObjs = doc.objectives.filter { doc.ownerNow(it) >= n }
        if (badObjs.isNotEmpty()) out += CheckIssue("Teams", CheckSeverity.ERROR, "${badObjs.size} objective${if (badObjs.size == 1) " belongs" else "s belong"} to team numbers the file does not have.", ref(badObjs[0]))
        val used = (doc.units.map { it.owner } + doc.objectives.map { doc.ownerNow(it) }).toSet()
        for (a in used) for (b in used) if (a < b && a < n && b < n) {
            val ab = doc.teams[a].team.stance.getOrElse(b) { 0 }
            val ba = doc.teams[b].team.stance.getOrElse(a) { 0 }
            if ((ab >= 4) != (ba >= 4)) out += CheckIssue("Teams", CheckSeverity.INFO, "${doc.teamName(a)} is ${TeamOps.STANCES.getOrElse(ab) { "$ab" }.lowercase()} toward ${doc.teamName(b)}, which is ${TeamOps.STANCES.getOrElse(ba) { "$ba" }.lowercase()} in return.", CheckRef("team", "$a"))
        }
        return out
    }
}
