package com.bmscompanion.desktop.bridge.command

import com.bmscompanion.desktop.bridge.DevGuard
import com.bmscompanion.desktop.bridge.Theaters
import java.io.File

/**
 * `--cmdroundtrip <a copy of a BMS folder> out.txt` — the writer on a **copy** (refused on a real install):
 * 1. every save: read → put together unchanged → read back: same parts in the same order, nothing re-packed, every
 *    part byte-identical, nothing after the directory;
 * 2. LZSS: every packed part re-packed with [Lzss.encode] decodes to its bytes, carries the header Falcon BMS's own
 *    writers give it, and expands to its bytes through a transcription of Falcon BMS's own expander;
 * 3. a one-field edit (a waypoint's arrival; the header's bullseye) changes only that field's bytes, and undo gives
 *    the original back; a waypoint added walks exactly with one more, and undone gives the original;
 * 4. Save as on a few saves of each theater: written, verified, `SaveFile` and part names renamed, companions
 *    copied, recorded as the pilot's; Overwrite of that copy after an edit; Overwrite of a start refused;
 * 5. the folder backup ([CommandBackup.ensureFolder]) and Edit BMS's campaign start then Restore BMS's original.
 * The files it made are removed at the end (the backups stay: the copy is a scratch folder).
 */
object CommandRoundTrip {
    fun run(root: File): String = buildString {
        appendLine("# --cmdroundtrip ${root.path}")
        val guard = DevGuard.refusal(root.path)
        if (guard != null) { appendLine("FAIL: refused: $guard"); return@buildString }
        appendLine("PASS: DevGuard lets the copy be written")
        DevGuard.registryBaseDirs().firstOrNull()?.let { real ->
            val r = DevGuard.refusal(File(real, "Data/Campaign").path)
            appendLine(if (r != null) "PASS: the real install is refused ($r)" else "FAIL: the real install ${real.path} is not refused")
        }
        val set = runCatching { Theaters.at(root) }.getOrNull()
        if (set == null || set.theaters.isEmpty()) { appendLine("FAIL: no theater definitions under ${root.path}"); return@buildString }

        // ------------------------------------------------ 1 + 2: unchanged and LZSS
        var files = 0; var fails = 0; var lzssParts = 0; var lzssBytes = 0L; var lzssPacked = 0L
        val made = ArrayList<File>()
        val seen = HashSet<String>()
        val folders = ArrayList<Pair<Theaters.Theater, File>>()
        val t0 = System.nanoTime()
        for (t in set.theaters) {
            val dir = set.campaignDir(t) ?: continue
            if (!seen.add(dir.canonicalPath.lowercase())) continue
            folders += t to dir
            for (f in dir.listFiles()?.filter { it.isFile && ShippedSaves.isSave(it) }.orEmpty().sortedBy { it.name.lowercase() }) {
                files++
                val doc = CommandSave.open(f, set)
                if (doc.version < CommandSave.OLDEST || doc.container.error != null) { appendLine("  skip ${f.name}: ${doc.problems.firstOrNull()}"); continue }
                val (bytes, repacked, why) = CommandWriter.build(doc, null)
                val problem = when {
                    bytes == null -> why
                    repacked.isNotEmpty() -> "unchanged, yet re-packed: ${repacked.joinToString()}"
                    else -> CommandWriter.verify(doc, bytes, f) ?: sameParts(doc.container, SaveContainer.read(bytes))
                }
                if (problem != null) { fails++; appendLine("FAIL unchanged ${t.name} / ${f.name}: $problem") }
                for (e in doc.container.entries) {
                    val u = e.unpacked ?: continue
                    if (!e.packed || u.isEmpty()) continue
                    val packed = Lzss.encode(u)
                    val back = Lzss.decodeCounting(packed, 0, u.size)
                    lzssParts++; lzssBytes += u.size; lzssPacked += packed.size
                    if (back == null || !back.bytes.contentEquals(u) || back.used != packed.size) { fails++; appendLine("FAIL lzss ${f.name} .${e.ext}: encode/decode differs") }
                    // the part as written, read the way Falcon BMS reads it: header fields, then its own expander
                    val part = SaveContainer.pack(e.ext, u, e.records)
                    bmsHeaderProblem(e.ext, part, u.size, e.records)?.let { fails++; appendLine("FAIL header ${f.name} .${e.ext}: $it") }
                    val bms = bmsExpand(part.copyOf(part.size + 1), if (e.ext == "cmp") 8 else 10, u.size)
                    if (bms == null || !bms.contentEquals(u)) { fails++; appendLine("FAIL lzss ${f.name} .${e.ext}: Falcon BMS's expander reads it differently") }
                }
            }
        }
        appendLine("${if (fails == 0) "PASS" else "FAIL"}: $files saves put together unchanged byte for byte ($fails failing)")
        appendLine("${if (fails == 0) "PASS" else "FAIL"}: LZSS encode → decode identity, and Falcon BMS's own headers and expander, on $lzssParts parts ($lzssBytes bytes → $lzssPacked, ${"%.0f".format(100.0 * lzssPacked / maxOf(1, lzssBytes))} %)")
        appendLine("  (${"%.1f".format((System.nanoTime() - t0) / 1e9)} s)")

        // ------------------------------------------------ 3: one-field edits
        val kto = folders.firstOrNull { (_, d) -> d.listFiles()?.any { it.name.equals("Save1.cam", true) } == true } ?: folders.firstOrNull()
        val played = kto?.second?.listFiles()?.filter { it.isFile && ShippedSaves.isSave(it) }?.map { CommandSave.open(it, set) }
            ?.firstOrNull { d -> d.flights.any { it.waypoints.size >= 2 } && d.obj == null }
        if (played == null) appendLine("FAIL: no played save with a flight to edit")
        else {
            val f = played.flights.first { it.waypoints.size >= 2 }
            val wp = f.waypoints[1]
            oneField(this, played, "uni", "a waypoint's arrival (${played.callsign(f)}, WP2)", Edits.set("Arrival", "uni", { wp.arrive }, { wp.arrive = it }, wp.arrive + 60_000), 4)
            val cmp = played.cmp!!
            oneField(this, played, "cmp", "the bullseye's x", Edits.set("Bullseye", "cmp", { cmp.bullseyeX }, { cmp.bullseyeX = it }, cmp.bullseyeX + 1), 2)
            // a waypoint added: the record grows; undo gives the original back
            val orig = played.container.entry("uni")!!.unpacked!!
            val add = Edits.insert("Add waypoint", "uni", f.waypoints, f.waypoints.last().copy())
            played.edits.run(add)?.let { appendLine("FAIL add waypoint refused: $it") }
            val (b2, _, w2) = CommandWriter.build(played, null)
            val back = b2?.let { CommandSave.fromBytes(played.file, it, set, withStart = false) }
            val f2 = back?.unit(f.id) as? CmdFlight
            appendLine(if (back != null && back.partEditable("uni") && f2?.waypoints?.size == f.waypoints.size) "PASS: a waypoint added reads back exactly (${f.waypoints.size} waypoints)" else "FAIL: a waypoint added does not read back (${w2 ?: back?.problems})")
            played.edits.undo()
            appendLine(if (played.uni!!.encode().contentEquals(orig)) "PASS: undo gives the unit part back byte for byte" else "FAIL: undo does not give the unit part back")
        }

        // ------------------------------------------------ 4: Save as, Overwrite
        for ((t, dir) in folders) {
            val saves = dir.listFiles()?.filter { it.isFile && ShippedSaves.isSave(it) }.orEmpty().sortedBy { it.name.lowercase() }
            val picks = listOfNotNull(
                saves.firstOrNull { ShippedSaves.startByName(it.nameWithoutExtension) && it.extension.equals("cam", true) },
                saves.firstOrNull { ShippedSaves.shippedByName(it.nameWithoutExtension) },
                saves.firstOrNull { ShippedSaves.isAutoSave(it.nameWithoutExtension) },
            ).distinct()
            for (src in picks) {
                val doc = CommandSave.open(src, set)
                if (doc.version < CommandSave.OLDEST) continue
                val target = File(dir, "RT ${src.nameWithoutExtension}.${src.extension}")
                val r = CommandWriter.saveAs(doc, target, replace = true)
                if (!r.ok) { appendLine("FAIL save as ${t.name} / ${src.name}: ${r.message}"); continue }
                made += target
                r.companions.forEach { made += File(dir, it) }
                val copy = CommandSave.open(target, set)
                val names = copy.container.entries.all { it.name.substringBeforeLast('.') == target.nameWithoutExtension }
                val sf = copy.cmp?.saveFile?.text == target.nameWithoutExtension
                val sc = copy.cmp?.scenario?.text == doc.cmp?.scenario?.text
                val same = doc.container.entries.indices.all { i ->
                    val a = doc.container.entries[i]; val b = copy.container.entries[i]
                    if (a.ext == "cmp") true else a.content.contentEquals(b.content)
                }
                val companionsOk = CommandWriter.COMPANIONS.all { ext -> !File(dir, src.nameWithoutExtension + ext).isFile || File(dir, target.nameWithoutExtension + ext).isFile }
                val ok = names && sf && sc && same && companionsOk && copy.identity.kind == ShippedSaves.Kind.PILOT
                appendLine((if (ok) "PASS" else "FAIL") + ": save as ${t.name} / ${src.name} → ${target.name} (parts renamed $names, SaveFile $sf, Scenario kept $sc, other parts identical $same, companions ${r.companions.joinToString().ifEmpty { "none" }} $companionsOk, ${copy.identity.kind}${if (r.repacked.isNotEmpty()) ", re-packed ${r.repacked.joinToString()}" else ""})")
                // overwrite the copy after an edit
                val cc = copy.cmp
                if (cc != null) {
                    val old = cc.uiName.text
                    copy.edits.run(Edits.set("Name", "cmp", { cc.uiName.text }, { cc.uiName.text = it }, "Round trip"))
                    val o = CommandWriter.overwrite(copy)
                    val again = CommandSave.open(target, set)
                    appendLine((if (o.ok && again.cmp?.uiName?.text == "Round trip" && !copy.edits.dirty) "PASS" else "FAIL") + ": overwrite of the copy (${o.message}; was \"$old\")")
                    // a delta naming an objective the start does not have is refused (BMS would drop the deltas after it)
                    val obd = again.obd
                    if (obd != null && again.objectives.isNotEmpty() && !again.objectivesFromOwn) {
                        again.edits.run(Edits.insert("Bad delta", "obd", obd.deltas, CmdDelta(VuId(999_999, 0), 0, 0, 0, 0, 0, ByteArray(0)), 0))
                        val bad = CommandWriter.overwrite(again)
                        appendLine((if (!bad.ok && "does not have" in bad.message) "PASS" else "FAIL") + ": a delta naming no objective of the start is refused (${bad.message})")
                    }
                }
                if (doc.identity.kind != ShippedSaves.Kind.PILOT) {
                    val o = CommandWriter.overwrite(doc)
                    appendLine((if (!o.ok) "PASS" else "FAIL") + ": overwrite of ${src.name} refused (${o.message})")
                }
            }
        }

        // ------------------------------------------------ 5: backup, edit a start, restore
        kto?.let { (_, dir) ->
            val res = CommandBackup.ensureFolder(dir)
            val list = CommandBackup.list(dir)
            appendLine((if (res.error == null && list.isNotEmpty()) "PASS" else "FAIL") + ": folder backup of ${dir.name}: ${res.copied} copied, ${list.size} in the record${res.error?.let { ", $it" } ?: ""}")
            appendLine("  recorded: ${list.joinToString { it.name }}")
            val readme = File(CommandBackup.dirFor(dir), CommandBackup.README).isFile
            appendLine((if (readme) "PASS" else "FAIL") + ": README beside the copies")
            val again = CommandBackup.ensureFolder(dir)
            appendLine((if (again.copied == 0 && again.error == null) "PASS" else "FAIL") + ": a second visit copies nothing")
            val wrong = list.filter { ShippedSaves.neverShipped(File(dir, it.name)) }
            appendLine((if (wrong.isEmpty()) "PASS" else "FAIL") + ": no pilot's save (Auto Save, Save-Day …, the section's copies) in the record${if (wrong.isEmpty()) "" else ": " + wrong.joinToString { it.name }}")
            // an entry an earlier build wrote for a pilot's save is not trusted, and leaves the record at the next visit
            dir.listFiles()?.firstOrNull { it.isFile && ShippedSaves.isSave(it) && it.name.startsWith("Save-Day", true) }?.let { day ->
                val rec = File(CommandBackup.dirFor(dir), CommandBackup.RECORD)
                rec.appendText("${day.name}\t${day.length()}\t0\t${day.lastModified()}\tdated with Falcon BMS's files\r\n")
                val id = ShippedSaves.identify(day)
                CommandBackup.ensureFolder(dir)
                val gone = CommandBackup.list(dir).none { it.name.equals(day.name, true) }
                appendLine((if (id.kind == ShippedSaves.Kind.PILOT && gone) "PASS" else "FAIL") + ": a recorded ${day.name} is still the pilot's (${id.kind}) and leaves the record ($gone)")
            }
            val start = dir.listFiles()?.firstOrNull { it.name.equals("Save1.cam", true) }
            if (start != null) {
                val before = start.readBytes()
                val doc = CommandSave.open(start, set)
                val users = doc.savesUsingThisStart()
                appendLine("PASS: ${users.size} saves of the folder name ${start.nameWithoutExtension} as their start (${users.take(6).joinToString { it.name }}${if (users.size > 6) "…" else ""})")
                val o = doc.obj?.objectives?.firstOrNull()
                if (o != null && doc.objectivesEditable) {
                    doc.edits.run(Edits.objectiveOwner(doc, o, (o.owner + 1) % 8))?.let { appendLine("FAIL objective owner refused: $it") }
                    val w = CommandWriter.overwriteStart(doc)
                    val after = CommandSave.open(start, set)
                    val changed = after.objective(o.id)?.owner == o.owner
                    appendLine((if (w.ok && changed) "PASS" else "FAIL") + ": Edit BMS's campaign start wrote ${start.name} (${w.message})")
                    val rr = CommandBackup.restore(dir, start.name)
                    appendLine((if (rr == null && start.readBytes().contentEquals(before)) "PASS" else "FAIL") + ": Restore BMS's original gives ${start.name} back byte for byte${rr?.let { " ($it)" } ?: ""}")
                } else appendLine("FAIL: ${start.name} has no editable objectives")
            }
        }

        // ------------------------------------------------ 6: the unit editor (its own report)
        append(runCatching { CommandUnitsCheck.run(set, folders.map { it.second }) }.getOrElse { "FAIL: the unit editor's check stopped: ${it.javaClass.simpleName} ${it.message}\n" })
        // ------------------------------------------------ 7: the objective, team and campaign editors (their own report)
        append(runCatching { CommandObjectivesCheck.run(set, folders.map { it.second }) }.getOrElse { "FAIL: the objectives check stopped: ${it.javaClass.simpleName} ${it.message}\n" })
        // ------------------------------------------------ 8: the Files page (the theater's campaign database files)
        append(runCatching { CampaignDbCheck.run(folders.map { it.second }) }.getOrElse { "FAIL: the database files check stopped: ${it.javaClass.simpleName} ${it.message}\n" })

        // ------------------------------------------------ clean up
        for (f in made) runCatching { f.delete() }
        appendLine("# removed ${made.size} files made by the check")
    }

    private fun sameParts(a: SaveContainer.ReadResult, b: SaveContainer.ReadResult): String? {
        if (b.error != null) return b.error
        if (b.tail != 0) return "${b.tail} bytes after the directory"
        if (a.entries.size != b.entries.size) return "${b.entries.size} parts, not ${a.entries.size}"
        for (i in a.entries.indices) {
            val x = a.entries[i]; val y = b.entries[i]
            if (x.name != y.name) return "part ${i + 1} named ${y.name}, not ${x.name}"
            if (!x.stored.contentEquals(y.stored)) return "part ${x.name} differs"
        }
        return null
    }

    /** Runs [edit] on [doc], checks the unpacked part [ext] differs in at most [width] contiguous bytes, then undoes it. */
    private fun oneField(out: StringBuilder, doc: CommandSave, ext: String, what: String, edit: CommandEdit, width: Int) {
        val orig = doc.container.entry(ext)!!.unpacked ?: doc.container.entry(ext)!!.stored
        doc.edits.run(edit)?.let { out.appendLine("FAIL $what refused: $it"); return }
        val (bytes, repacked, why) = CommandWriter.build(doc, null)
        val now = bytes?.let { SaveContainer.read(it).entry(ext)?.content }
        if (now == null) { out.appendLine("FAIL $what: ${why ?: "not written"}"); doc.edits.undo(); return }
        val diff = orig.indices.filter { it >= now.size || orig[it] != now[it] }
        val ok = now.size == orig.size && diff.isNotEmpty() && diff.last() - diff.first() < width && repacked == listOf(ext)
        out.appendLine((if (ok) "PASS" else "FAIL") + ": one field changed ($what): ${diff.size} bytes differ at ${diff.firstOrNull()}..${diff.lastOrNull()}, only .$ext re-packed (${repacked.joinToString()})")
        doc.edits.undo()
        val (b2, r2, _) = CommandWriter.build(doc, null)
        out.appendLine((if (b2 != null && r2.isEmpty()) "PASS" else "FAIL") + ": undo of $what gives the file back unchanged")
    }

    /**
     * A packed part's header against what Falcon BMS's own writers put there (`CampaignClass::Encode`/`SaveData`,
     * `EncodeUnitData`/`SaveUnits`, `SaveObjectiveDeltas`, `SaveBaseObjectives`): `.cmp` `i32 part − 4, i32 unpacked`;
     * `.uni`/`.obd` `i32 part − 4, i16 count, i32 unpacked`; `.obj` `i16 count, i32 unpacked, i32 stream length`.
     */
    private fun bmsHeaderProblem(ext: String, part: ByteArray, unpacked: Int, records: Int): String? {
        val r = LeIn(part)
        return when (ext) {
            "cmp" -> { val first = r.i32(); val unc = r.i32()
                if (first != part.size - 4 || unc != unpacked) "first $first (BMS: ${part.size - 4}), unpacked $unc (BMS: $unpacked)" else null }
            "obj" -> { val n = r.i16(); val unc = r.i32(); val s = r.i32()
                if (n != records || unc != unpacked || s != part.size - 10) "count $n, unpacked $unc, stream $s (BMS: $records, $unpacked, ${part.size - 10})" else null }
            else -> { val first = r.i32(); val n = r.i16(); val unc = r.i32()
                if (first != part.size - 4 || n != records || unc != unpacked) "first $first, count $n, unpacked $unc (BMS: ${part.size - 4}, $records, $unpacked)" else null }
        }
    }

    /**
     * Falcon BMS's `LZSS_Expand` (Utils\lzss.cpp:541) transcribed: ring of 4,096 zeroed, written from 1; a flag byte
     * read first and again after eight decisions, least significant bit first; a literal is one byte; a run is two
     * (high nibble + 2 bytes from the 12-bit ring position), and **a run longer than what is left stops the expansion**.
     * Null when it stops short of [want].
     */
    private fun bmsExpand(s: ByteArray, from: Int, want: Int): ByteArray? {
        val out = ByteArray(want); val ring = ByteArray(4096)
        var r = from; var head = 1; var mask = 1; var flags = s[r++].toInt() and 0xFF; var w = 0; var left = want
        while (left != 0) {
            if (mask == 0x100) { mask = 1; flags = s[r++].toInt() and 0xFF }
            val bit = mask; mask = mask shl 1
            if (flags and bit != 0) { val c = s[r++]; left--; out[w++] = c; ring[head] = c; head = (head + 1) and 0xFFF }
            else {
                val a = s[r].toInt() and 0xFF; val b = s[r + 1].toInt() and 0xFF; r += 2
                var pos = ((a and 0xF) shl 8) or b
                val n1 = (a shr 4) + 1
                if (n1 >= left) break
                left -= n1 + 1
                for (k in 0..n1) { val c = ring[pos and 0xFFF]; pos++; out[w++] = c; ring[head] = c; head = (head + 1) and 0xFFF }
            }
        }
        return if (w == want) out else null
    }
}
