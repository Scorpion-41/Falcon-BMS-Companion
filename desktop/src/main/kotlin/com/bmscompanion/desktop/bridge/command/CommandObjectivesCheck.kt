package com.bmscompanion.desktop.bridge.command

import com.bmscompanion.desktop.bridge.Theaters
import java.io.File

/**
 * The objective, team and campaign editors' part of `--cmdroundtrip` (on a **copy**; nothing is written but a record
 * the check removes again): each edit is made on an open save, the file is put together ([CommandWriter.build]) and
 * read back ([CommandSave.fromBytes]), the field compared, the writer's verification run, and the edit undone back to
 * the original bytes —
 * an objective's owner in a start, a link added to a start, an owner and a building in a played save's deltas, a
 * team's stance, Shift Times on a TE; then the Checks report on every save of the copy, the occupation map's layout
 * against the objectives' owners, and "This file is mine" recorded and taken back.
 */
object CommandObjectivesCheck {
    fun run(set: Theaters.TheaterSet, folders: List<File>): String = buildString {
        appendLine("## objectives, teams, campaign")
        val saves = folders.flatMap { d -> d.listFiles()?.filter { it.isFile && ShippedSaves.isSave(it) }.orEmpty().sortedBy { it.name.lowercase() } }
        val start = saves.firstOrNull { it.name.equals("Save1.cam", true) } ?: saves.firstOrNull { ShippedSaves.startByName(it.nameWithoutExtension) && it.extension.equals("cam", true) }
        val played = saves.firstOrNull { it.name.startsWith("Save-Day", true) } ?: saves.firstOrNull { it.name.startsWith("Auto Save", true) && it.extension.equals("cam", true) }
        val te = saves.firstOrNull { it.name.startsWith("TE_BMS_", true) && it.extension.equals("tac", true) }

        if (start != null) {
            val doc = CommandSave.open(start, set)
            val o = doc.objectives.firstOrNull { it.links.size in 1..6 }
            if (!doc.objectivesEditable || o == null) appendLine("FAIL: ${start.name} has no editable objectives")
            else {
                val team = if (o.owner == 2) 6 else 2
                edit(this, doc, "an objective's owner in a start (${ObjectiveOps.displayName(doc, o)} → team $team)", setOf("obj"), ObjectiveOps.owner(doc, o, team, true)) { back ->
                    back.obj?.objectives?.firstOrNull { it.id == o.id }?.owner == team
                }
                val b = doc.objectives.filter { it !== o && it.links.size < 6 && o.links.none { l -> l.id == it.id } }.minByOrNull { ObjectiveOps.cellKm(o, it) }
                if (b != null) {
                    val e = ObjectiveOps.link(doc, o, b)
                    if (e == null) appendLine("FAIL: no link could be made") else edit(this, doc, "a link added to a start (${ObjectiveOps.displayName(doc, o)} ↔ ${ObjectiveOps.displayName(doc, b)}, ${"%.1f".format(ObjectiveOps.cellKm(o, b))} km)", setOf("obj"), e) { back ->
                        val bo = back.obj!!.objectives.first { it.id == o.id }
                        val bb = back.obj.objectives.first { it.id == b.id }
                        // (the edit is in force while this runs: o has the new link too)
                        bo.links.size == o.links.size && bo.links.last().id == b.id && bb.links.last().id == o.id && bo.links.last().costs.contentEquals(bb.links.last().costs)
                    }
                    val m = ObjectiveOps.costModel(doc)
                    appendLine("  cost model of ${start.name}: per km ${m.perKm.joinToString { "%.2f".format(it) }}; closed ${(0 until 8).filter { m.closed[it] }}")
                }
                val issues = ObjectiveOps.checkLinks(doc)
                appendLine("  link check of ${start.name}: ${issues.size} findings (${issues.count { it.fix != null }} with a fix): " +
                    listOf("itself", "not an objective", "twice", "no link back", "linked across", "has no links", "links (BMS uses").joinToString { k -> "$k ${issues.count { it.text.contains(k) }}" })
                issues.filter { it.text.contains("no link back") }.take(3).forEach { appendLine("    e.g. ${it.text}") }
                val (ok, n) = CampaignOps.occAgreement(doc)
                appendLine((if (n > 0 && ok * 100 >= n * 90) "PASS" else "FAIL") + ": the occupation map's layout agrees with $ok of $n objectives' owners in ${start.name}")
                // add then delete an objective: count changes, ids stay unique, undo gives the bytes back
                val (add, made) = ObjectiveOps.add(doc, o, o.simNorth, o.simEast + 3280.84, "Check objective")
                if (add != null && made != null) edit(this, doc, "an objective added to a start", setOf("obj"), add) { back ->
                    back.obj!!.objectives.size == doc.objectives.size && back.objectives.map { it.id }.toSet().size == back.objectives.size && back.objectives.any { it.name.text == "Check objective" }
                }
            }
        } else appendLine("  (no campaign start in the copy)")

        if (played != null) {
            val doc = CommandSave.open(played, set)
            val o = doc.objectives.firstOrNull { doc.delta(it.id) == null && ObjectiveOps.featureCount(doc, it) > 0 }
            if (ObjectiveOps.mode(doc, false) != ObjectiveOps.Mode.DELTAS || o == null) appendLine("FAIL: ${played.name} cannot record objective changes (${ObjectiveOps.modeWhy(doc, false)})")
            else {
                val team = if (o.owner == 2) 6 else 2
                edit(this, doc, "an owner delta in ${played.name} (${ObjectiveOps.displayName(doc, o)} → team $team)", setOf("obd"), ObjectiveOps.owner(doc, o, team, false)) { back ->
                    back.obd?.delta(o.id)?.owner == team && back.obd?.count == doc.obd?.count
                }
                edit(this, doc, "a building destroyed in ${played.name}", setOf("obd"), ObjectiveOps.building(doc, o, 0, 3, false)) { back ->
                    back.obd?.delta(o.id)?.let { ObjectiveOps.FeatureStates.get(it.fstatus, 0, ObjectiveOps.featureCount(doc, o)) } == 3
                }
            }
        }

        val teamDoc = te?.let { CommandSave.open(it, set) } ?: start?.let { CommandSave.open(it, set) }
        if (teamDoc != null && teamDoc.teams.size >= 3) {
            val cur = teamDoc.teams[1].team.stance[2]
            val v = if (cur == 5) 3 else 5
            TeamOps.stance(teamDoc, 1, 2, v)?.let { e ->
                edit(this, teamDoc, "a team's stance in ${teamDoc.file.name} (team 1 toward team 2: ${TeamOps.STANCES[v]})", setOf("tea"), e) { back -> back.teams[1].team.stance[2] == v }
            }
        }

        if (te != null) {
            val doc = CommandSave.open(te, set)
            val clock = doc.cmp?.currentTime ?: 0
            val hour = 3_600_000L
            val before = doc.units.flatMap { u -> u.waypoints.map { it.arrive } }
            edit(this, doc, "Shift Times +1h on ${te.name}", setOf("cmp", "uni", "tea"), CampaignOps.shiftTimes(doc, hour)) { back ->
                val after = back.units.flatMap { u -> u.waypoints.map { it.arrive } }
                back.cmp?.currentTime == clock + hour && after.size == before.size && after.indices.all { i -> before[i] == 0L && after[i] == 0L || after[i] == before[i] + hour }
            }
        }

        // the Checks report on every save
        var checked = 0; var findings = 0; var errors = 0
        val failing = ArrayList<String>()
        for (f in saves) {
            val doc = CommandSave.open(f, set)
            val r = runCatching { CommandChecks.run(doc) }
            r.onSuccess { list ->
                checked++; findings += list.size; errors += list.count { it.severity == CheckSeverity.ERROR }
                list.filter { it.severity == CheckSeverity.ERROR }.take(2).forEach { appendLine("  check ${f.name}: ${it.text}") }
            }.onFailure { failing += "${f.name}: ${it.message}" }
        }
        appendLine((if (failing.isEmpty()) "PASS" else "FAIL") + ": the Checks report ran on $checked saves ($findings findings, $errors errors)" + failing.take(3).joinToString("; ", prefix = if (failing.isEmpty()) "" else " — "))

        // This file is mine, and back
        saves.firstOrNull { !ShippedSaves.startByName(it.nameWithoutExtension) && !ShippedSaves.shippedByName(it.nameWithoutExtension) && !ShippedSaves.neverShippedName(it.nameWithoutExtension) }?.let { f ->
            val before = ShippedSaves.identify(f)
            val a = MineFiles.add(f)
            val mine = ShippedSaves.identify(f)
            val r = MineFiles.remove(f)
            val after = ShippedSaves.identify(f)
            val ok = a == null && r == null && mine.kind == ShippedSaves.Kind.PILOT && mine.mine && after.kind == before.kind && !after.mine
            appendLine((if (ok) "PASS" else "FAIL") + ": \"This file is mine\" on ${f.name}: ${before.kind}${if (before.claimable) " (claimable)" else ""} → ${mine.kind}${if (mine.mine) " (mine)" else ""} → ${after.kind}" + listOfNotNull(a, r).joinToString("; ", prefix = " "))
            var d: File? = f.absoluteFile.parentFile
            while (d != null && !File(d, "User").isDirectory) d = d.parentFile
            d?.let { File(it, "User/BMS Companion Command/mine.json") }?.let { runCatching { it.delete() } }
        }
    }

    /**
     * Makes [e] on [doc], puts the file together and reads it back: [holds] on the re-read file, only [parts]
     * re-packed, the writer's verification passing; then undoes it and demands the original bytes.
     */
    private fun edit(out: StringBuilder, doc: CommandSave, what: String, parts: Set<String>, e: CommandEdit, holds: (CommandSave) -> Boolean) {
        val (orig, _, why0) = CommandWriter.build(doc, null)
        if (orig == null) { out.appendLine("FAIL: $what: the file could not be put together ($why0)"); return }
        val refused = doc.edits.run(e)
        if (refused != null) { out.appendLine("FAIL: $what: refused ($refused)"); return }
        val (bytes, repacked, why) = CommandWriter.build(doc, null)
        val problem = when {
            bytes == null -> why
            !parts.containsAll(repacked) -> "re-packed ${repacked.joinToString()} (expected only ${parts.joinToString()})"
            else -> CommandWriter.verify(doc, bytes, doc.file) ?: CommandSave.fromBytes(doc.file, bytes, doc.theaters).let { back ->
                if (back.problems.isNotEmpty() && back.problems != doc.problems) "read back with problems: ${back.problems.first()}"
                else if (!runCatching { holds(back) }.getOrDefault(false)) "the change did not read back"
                else null
            }
        }
        doc.edits.undo()
        val (again, _, _) = CommandWriter.build(doc, null)
        val undone = again != null && again.contentEquals(orig)
        out.appendLine(if (problem == null && undone) "PASS: $what (re-packed ${repacked.joinToString().ifEmpty { "nothing" }}; undo gives the file back)" else "FAIL: $what: ${problem ?: "undo did not give the file back"}")
    }
}
