package com.bmscompanion.desktop.bridge.command

import java.io.File

/**
 * The Files page's part of `--cmdroundtrip` (on a **copy**): every campaign database file of every campaign folder is
 * read and put together again unchanged — byte for byte — then, in the first folder, one field of each file is changed
 * and the file written ([CampaignDbFiles.save]: the original backed up once, only the field's line differs), a row
 * added and deleted where the file allows, a file changed on disk refused, and **Restore BMS's original** giving the
 * first bytes back.
 */
object CampaignDbCheck {
    fun run(folders: List<File>): String = buildString {
        appendLine("## campaign database files (Files page)")
        var files = 0; var fails = 0
        for (dir in folders) for (e in CampaignDbFiles.list(dir)) {
            files++
            val t = CampaignDbFiles.read(e.kind, e.file)
            val disk = runCatching { e.file.readBytes() }.getOrNull()
            val (bytes, why) = CampaignDbFiles.render(t, t.rows)
            if (t.problems.isNotEmpty() || bytes == null || disk == null || !bytes.contentEquals(disk)) {
                fails++
                appendLine("FAIL unchanged ${dir.name}/${e.file.name}: ${t.problems.firstOrNull() ?: why ?: "the bytes differ"}")
            }
        }
        appendLine("${if (fails == 0) "PASS" else "FAIL"}: $files database files read and put together unchanged byte for byte ($fails failing)")

        val dir = folders.firstOrNull { CampaignDbFiles.list(it).isNotEmpty() } ?: run { appendLine("FAIL: no campaign folder with database files"); return@buildString }
        for (e in CampaignDbFiles.list(dir).distinctBy { it.kind }) {
            val f = e.file
            val orig = f.readBytes()
            val t = CampaignDbFiles.read(e.kind, f)
            // the first editable column of a row that has a number in it
            if (e.kind == CampaignDbFiles.Kind.INVALID_AC) {
                // a squadron class added and taken out again: the file as before
                val (w1, b1) = CampaignDbFiles.save(t, t.rows + CampaignDbFiles.Row(-1, listOf("Check squadron", "5")))
                val one = b1 != null && b1.rows.size == t.rows.size + 1 && b1.rows.last().cells == listOf("Check squadron", "5")
                val (w2, b2) = if (b1 != null) CampaignDbFiles.save(b1, t.rows) else ("not added" to null)
                val back = w2 == null && b2 != null && f.readBytes().contentEquals(orig)
                appendLine("${if (w1 == null && one && back) "PASS" else "FAIL"}: ${f.name}: a squadron class added ($one${w1?.let { ", $it" } ?: ""}) and taken out, the file as before ($back${w2?.let { ", $it" } ?: ""})")
                CampaignDbFiles.restore(f)
                continue
            }
            val (ri, ci) = pick(t) ?: run { appendLine("  skip ${f.name}: no field to change"); null } ?: continue
            val old = t.rows[ri].cells[ci]
            val new = old.toLongOrNull()?.let { (it + 1).toString() } ?: "${old}X"
            val rows = t.rows.toMutableList().also { it[ri] = it[ri].with(ci, new) }
            val (whyBad, back) = CampaignDbFiles.save(t, rows)
            if (whyBad != null || back == null) { appendLine("FAIL write ${f.name}: $whyBad"); continue }
            val now = f.readBytes()
            val diff = lineDiffs(orig, now)
            val copy = CommandBackup.copyOf(dir, f.name)
            val okField = back.rows[ri].cells[ci].let { it == new || (new.toLongOrNull() != null && it.toLongOrNull() == new.toLongOrNull()) }
            val ok = okField && copy != null && copy.readBytes().contentEquals(orig) && diff in 1..1
            appendLine("${if (ok) "PASS" else "FAIL"}: ${f.name}: ${t.columns[ci].name} of row ${ri + 1} $old → $new written (reads back $okField, original backed up ${copy != null}, $diff line(s) differ)")

            if (back.canAddRows) {
                val more = back.rows.toMutableList().also { it.add(ri + 1, CampaignDbFiles.Row(-1, back.rows[ri].cells)) }
                val (w2, b2) = CampaignDbFiles.save(back, more)
                val added = w2 == null && b2 != null && b2.rows.size == back.rows.size + 1
                val (w3, b3) = if (b2 != null) CampaignDbFiles.save(b2, b2.rows.filterIndexed { i, _ -> i != ri + 1 }) else ("not added" to null)
                val gone = w3 == null && b3 != null && f.readBytes().contentEquals(now)
                appendLine("${if (added && gone) "PASS" else "FAIL"}: ${f.name}: a row added ($added${w2?.let { ", $it" } ?: ""}) and deleted again, the file as before ($gone${w3?.let { ", $it" } ?: ""})")
            }

            // changed on disk since it was read: refused
            val stale = CampaignDbFiles.read(e.kind, f)
            runCatching { f.appendBytes(" ".toByteArray()) }
            val (w4, _) = CampaignDbFiles.save(stale, stale.rows.toMutableList().also { it[ri] = it[ri].with(ci, old) })
            appendLine("${if (w4 != null && "changed on disk" in w4) "PASS" else "FAIL"}: ${f.name}: a file changed on disk since it was read is not written (${w4 ?: "it was written"})")

            val r = CampaignDbFiles.restore(f)
            appendLine("${if (r == null && f.readBytes().contentEquals(orig)) "PASS" else "FAIL"}: ${f.name}: Restore BMS's original gives the first bytes back${r?.let { " ($it)" } ?: ""}")
        }
    }

    private fun pick(t: CampaignDbFiles.Table): Pair<Int, Int>? {
        for ((ri, r) in t.rows.withIndex()) {
            if (ri == 0 && t.kind == CampaignDbFiles.Kind.TE_PLANES) continue
            for ((ci, c) in t.columns.withIndex()) if (c.editable && c.numeric && r.cells.getOrElse(ci) { "" }.trim().toLongOrNull() != null) return ri to ci
        }
        for ((ri, r) in t.rows.withIndex()) for ((ci, c) in t.columns.withIndex()) if (c.editable && r.cells.getOrElse(ci) { "" }.isNotBlank()) return ri to ci
        return null
    }

    private fun lineDiffs(a: ByteArray, b: ByteArray): Int {
        val x = String(a, Charsets.ISO_8859_1).split('\n')
        val y = String(b, Charsets.ISO_8859_1).split('\n')
        if (x.size != y.size) return -1
        return x.indices.count { x[it] != y[it] }
    }
}
