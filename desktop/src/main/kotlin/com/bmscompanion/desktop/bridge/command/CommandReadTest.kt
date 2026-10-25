package com.bmscompanion.desktop.bridge.command

import com.bmscompanion.desktop.bridge.Theaters
import java.io.File

/**
 * `--cmdreadtest <a BMS folder> out.txt`: every save of every theater's `campaigndir` read whole by the Command
 * section's reader — read only. Per file: each modelled part walks exactly (record count = the header's, nothing
 * left over) **and** writes back from its records to exactly the bytes read (the writer's premise); the counts; whose
 * file it is ([ShippedSaves.identify]). FAIL on any part that does not.
 */
object CommandReadTest {
    fun run(root: File): String = buildString {
        appendLine("# --cmdreadtest ${root.path}")
        val set = runCatching { Theaters.at(root) }.getOrNull()
        if (set == null || set.theaters.isEmpty()) { appendLine("FAIL: no theater definitions under ${root.path}"); return@buildString }
        var files = 0; var fails = 0; var notes = 0
        val kinds = IntArray(UnitKind.values().size)
        var objectives = 0L; var deltas = 0L; var persistent = 0L; var requests = 0L
        val versions = sortedMapOf<Int, Int>()
        val idents = sortedMapOf<String, Int>()
        val seen = HashSet<String>()
        val t0 = System.nanoTime()
        for (t in set.theaters) {
            val dir = set.campaignDir(t) ?: run { appendLine("## ${t.name}: no campaign folder"); null } ?: continue
            if (!seen.add(dir.canonicalPath.lowercase())) continue
            val saves = dir.listFiles()?.filter { it.isFile && ShippedSaves.isSave(it) }?.sortedBy { it.name.lowercase() }.orEmpty()
            appendLine("## ${t.name}  ${dir.relativeToOrSelf(root).path}  (${saves.size} saves)")
            for (f in saves) {
                files++
                val doc = CommandSave.open(f, set)
                versions.merge(doc.version, 1, Int::plus)
                val bad = ArrayList<String>()
                for (e in doc.container.entries) {
                    if (e.ext !in CommandSave.MODELLED) continue
                    if (!doc.partEditable(e.ext)) { bad += ".${e.ext} did not walk exactly"; continue }
                    val enc = when (e.ext) {
                        "cmp" -> doc.cmp!!.encode() to 0
                        "uni" -> doc.uni!!.encode() to doc.uni.count
                        "obj" -> doc.obj!!.encode() to (if (doc.obj.headerOnly) e.records else doc.obj.count)
                        "obd" -> doc.obd!!.encode() to doc.obd.count
                        "tea" -> doc.tea!!.encode() to 0
                        "evt" -> doc.evt!!.encode() to 0
                        "pol" -> doc.pol!!.encode() to 0
                        "plt" -> doc.plt!!.encode() to 0
                        else -> doc.pst!!.encode() to 0
                    }
                    if (!enc.first.contentEquals(e.content)) bad += ".${e.ext} does not write back to the bytes read (${enc.first.size} vs ${e.content.size})"
                    if (e.packed && enc.second != e.records) bad += ".${e.ext} holds ${enc.second} records, its header says ${e.records}"
                }
                // every record written alone gives back its own bytes
                doc.units.firstOrNull { it.raw.isNotEmpty() && !it.encode().contentEquals(it.raw) }?.let { bad += "unit ${it.id} (${it.kind}) does not write back to its bytes" }
                doc.obj?.objectives?.firstOrNull { !it.encode().contentEquals(it.raw) }?.let { bad += "objective ${it.id} does not write back to its bytes" }
                if (doc.version >= CommandSave.OLDEST && doc.container.error == null && doc.uni == null && doc.container.entry("uni") != null) bad += "units not read"
                doc.units.forEach { kinds[it.kind.ordinal]++ }
                objectives += doc.obj?.count ?: 0
                deltas += doc.obd?.count ?: 0
                persistent += doc.pst?.objects?.size ?: 0
                requests += doc.teams.sumOf { it.air.requests.size }
                val id = doc.identity
                idents.merge(id.kind.name, 1, Int::plus)
                val counts = "v${doc.version} parts=${doc.container.entries.joinToString(",") { it.ext }}${if (doc.container.tail > 0) " tail=${doc.container.tail}" else ""} " +
                    "units=${doc.units.size} (fl ${doc.flights.size} pk ${doc.packages.size} sq ${doc.squadrons.size} bn ${doc.battalions.size} bde ${doc.brigades.size} tf ${doc.taskForces.size}) " +
                    "obj=${doc.obj?.count ?: "-"} obd=${doc.obd?.count ?: "-"} teams=${doc.teams.size} pst=${doc.pst?.objects?.size ?: "-"} " +
                    "${id.kind}${if (id.start) "/start" else ""}"
                if (bad.isNotEmpty()) { fails++; appendLine("FAIL ${f.name}: ${bad.joinToString("; ")}  [$counts]") }
                else appendLine("  ok ${f.name}  $counts")
                val unknown = doc.unknownDeltas()
                if (unknown.isNotEmpty()) appendLine("     note: ${unknown.size} deltas name objectives the start ${doc.startFile?.name ?: doc.scenario} does not have (first ${unknown.first().id})")
                if (doc.obj != null && !doc.objectivesFromOwn) appendLine("     note: has its own objectives, but flies those of ${doc.startFile?.name} (Scenario \"${doc.scenario}\")")
                val other = doc.problems.filterNot { "was not found beside it" in it }
                if (other.isNotEmpty()) { notes++; appendLine("     note: ${other.joinToString(" | ")}") }
                if (id.kind != ShippedSaves.Kind.PILOT && !ShippedSaves.startByName(f.nameWithoutExtension) && !ShippedSaves.shippedByName(f.nameWithoutExtension)) {
                    appendLine("     why: ${id.why}")
                }
            }
        }
        appendLine()
        appendLine("# summary: $files saves, $fails failing, $notes with notes, ${"%.1f".format((System.nanoTime() - t0) / 1e9)} s")
        appendLine("# versions ${versions.entries.joinToString { "${it.key}: ${it.value}" }}")
        appendLine("# identities ${idents.entries.joinToString { "${it.key}: ${it.value}" }}")
        appendLine("# units ${UnitKind.values().joinToString { "${it.name.lowercase()} ${kinds[it.ordinal]}" }}; objectives $objectives; deltas $deltas; persistent objects $persistent; ATM requests $requests")
        if (fails == 0) appendLine("PASS: every part of every save walks exactly and writes back byte for byte")
        // the Files page's database files: read and put together again in memory (nothing written)
        var dbFiles = 0; var dbFails = 0
        val dirs = set.theaters.mapNotNull { set.campaignDir(it) }.distinctBy { it.canonicalPath.lowercase() }
        for (d in dirs) for (e in CampaignDbFiles.list(d)) {
            dbFiles++
            val t = CampaignDbFiles.read(e.kind, e.file)
            val (b, why) = CampaignDbFiles.render(t, t.rows)
            val same = b != null && runCatching { e.file.readBytes() }.getOrNull()?.contentEquals(b) == true
            if (t.problems.isNotEmpty() || !same) { dbFails++; appendLine("FAIL database file ${d.name}/${e.file.name}: ${t.problems.firstOrNull() ?: why ?: "does not put together byte for byte"}") }
        }
        appendLine("${if (dbFails == 0) "PASS" else "FAIL"}: $dbFiles campaign database files (Stations+Ils, RadioMap, TE lists, InvalidAcTypes, MissionData, CampObjData) read whole and put together byte for byte ($dbFails failing)")
    }
}
