package com.bmscompanion.desktop.bridge

import com.bmscompanion.app.data.mission.MissionPicture
import java.io.File

/**
 * `--targetrender <a copy of the BMS folder> <out folder>`: the target pictures ([TargetPictures]) from a copy, read
 * only. Writes `targetrender.txt` and the pictures into the out folder.
 *
 * 1. Coverage: every vehicle and building of each theater's tables ([TargetModels.catalog]) — its model found, read
 *    ([BmlModel]) and given surfaces — counted by category, with the ones that do not read named.
 * 2. Known systems, both views and both looks: SA-2, SA-6 with its battalion (the Straight Flush among it), SA-8,
 *    ZSU-23-4, T-72, a ship and a building, with the time each took cold (read, draw, encode).
 * 3. A mission's set: the threats of the printed briefing kept as the parser's fixture, each as its battalion, every
 *    picture drawn, and the total time — which decides whether pictures are drawn without being asked for.
 * 4. The cache: pictures served again from memory and from the folder, and the folder trimmed to its cap.
 */
object TargetRenderTest {
    fun run(root: File, out: File): String {
        val sb = StringBuilder()
        fun line(s: String) { sb.appendLine(s) }
        out.mkdirs()
        val set = Theaters.at(root)
        if (set.theaters.isEmpty()) return "FAIL: no theaters under ${root.path} (${set.error})\n"
        TargetPictures.setOverride = set
        val cache = File(out, "cache").also { it.deleteRecursively(); it.mkdirs() }
        TargetPictures.cacheOverride = cache
        try {
            // ---------------------------------------------------------------- 1. coverage
            line("== 1. Coverage: every vehicle and building with a model, by theater tables")
            val doneTables = HashSet<String>()
            for (t in set.theaters) {
                val tables = TargetModels.tables(set, t) ?: run { line("  ${t.name}: no class table"); null } ?: continue
                val files = listOf("Falcon4_CT.xml", "Falcon4_VCD.xml").joinToString(",") { set.classFile(t, it)?.path ?: "-" }
                if (!doneTables.add(files + "|" + set.threeDDataDir(t)?.path)) continue
                val cat = TargetModels.catalog(tables)
                var ok = 0; var bad = 0
                val badNames = ArrayList<String>()
                val per = StringBuilder()
                val t0 = System.nanoTime()
                for ((c, list) in cat) {
                    var cok = 0
                    for (k in list) {
                        val f = TargetModels.modelFile(tables, k.gfx)
                        val m = f?.let { BmlModel.read(it) }
                        val tris = BmlModel.FloatList(1024)
                        m?.triangles(tris)
                        if (m != null && tris.size >= 9) { ok++; cok++ } else { bad++; badNames += "${k.name} (${k.gfx}: ${if (f == null) "no Model_0.bml" else BmlModel.lastError ?: "no surfaces"})" }
                    }
                    per.append("$c ${cok}/${list.size}; ")
                }
                val share = if (ok + bad == 0) 0.0 else ok * 100.0 / (ok + bad)
                line("  ${t.name}: $ok of ${ok + bad} read (%.1f %%) in %.1f s — $per".format(share, (System.nanoTime() - t0) / 1e9))
                if (badNames.isNotEmpty()) line("    not read: " + badNames.take(12).joinToString("; ") + if (badNames.size > 12) " … (${badNames.size})" else "")
                if (share < 90.0) line("FAIL: ${t.name}: only %.1f %% of the targets' models read".format(share))
            }

            // ---------------------------------------------------------------- 2. known systems
            line("")
            line("== 2. Known systems (the base theater's tables), cold: read / draw / encode")
            val base = set.theaters.firstOrNull { it.name.contains("Korea", true) && !it.name.contains("TvT", true) } ?: set.theaters.first()
            val tables = TargetModels.tables(set, base)
            if (tables == null) line("FAIL: no tables for ${base.name}")
            else {
                val wanted = listOf("SA-2 (S-75)", "SA-6 (2K12)", "Straight Flush", "SA-8 (9K33)", "ZSU-23-4", "T-72", "Sovremenny CLS", "Hangar")
                for (name in wanted) {
                    val k = TargetModels.byName(tables, name) ?: tables.kinds.values.firstOrNull { it.name.contains(name, true) }
                    if (k == null) { line("FAIL: $name is not in ${base.name}'s tables"); continue }
                    draw(tables, base, k, out, ::line)
                }
                val sa6 = TargetModels.byName(tables, "SA-6 (2K12)")
                if (sa6 != null) {
                    val bn = TargetModels.battalion(tables, sa6)
                    line("  SA-6 battalion: " + bn.joinToString(" | ") { "${it.count}x ${it.kind.name} (${it.role})" })
                    if (bn.none { it.kind.name.contains("Straight Flush", true) }) line("FAIL: the SA-6 battalion has no Straight Flush")
                    if (bn.firstOrNull()?.kind?.ct != sa6.ct) line("FAIL: the SA-6 battalion does not start with the launcher")
                }
                val sa2 = TargetModels.byName(tables, "SA-2")
                line("  SA-2 by its short name: ${sa2?.name ?: "none"}" + if (sa2 == null) "" else ", battalion: " + TargetModels.battalion(tables, sa2).joinToString(" | ") { it.kind.name })
                if (sa2 == null) line("FAIL: \"SA-2\" does not find the SA-2")
            }

            // ---------------------------------------------------------------- 3. a mission's set
            line("")
            line("== 3. A mission's set: the fixture briefing's threats and target")
            val text = TargetRenderTest::class.java.getResourceAsStream("/bridge/sample_briefing.txt")?.readBytes()?.toString(Charsets.UTF_8)
            val briefing = text?.let { BriefingParser.parse(it) }
            val rows = MissionPicture.briefedThreats(briefing)
            val balkans = set.theaters.firstOrNull { it.name.contains("Balkans", true) } ?: base
            val bt = TargetModels.tables(set, balkans)
            if (rows.isEmpty() || bt == null) line("FAIL: the fixture has no threats, or ${balkans.name} no tables")
            else {
                TargetPictures.forgetMemory()
                val t0 = System.nanoTime()
                var pictures = 0
                for (r in rows.distinctBy { it.system }) {
                    val k = TargetModels.byName(bt, r.system)
                    if (k == null) { line("FAIL: ${r.system} is not in ${balkans.name}'s tables"); continue }
                    val bn = TargetModels.battalion(bt, k)
                    line("  ${r.system} (${r.where}): " + bn.joinToString(" | ") { "${it.count}x ${it.kind.name} (${it.role})" })
                    for (m in bn) for (v in TargetRender.View.entries) {
                        when (val a = TargetPictures.picture(balkans.name, "ct${m.kind.ct}", v, TargetRender.Look.PLAIN, TargetPictures.DEFAULT_WIDTH)) {
                            is TargetPictures.Answer.Ok -> { pictures++; File(out, "mission-${safe(m.kind.name)}-${v.id}.jpg").writeBytes(a.bytes) }
                            is TargetPictures.Answer.Refused -> line("    ${m.kind.name} ${v.id}: ${a.why}")
                        }
                    }
                }
                val ms = (System.nanoTime() - t0) / 1e6
                line("  $pictures pictures in %.0f ms (%.0f ms each)".format(ms, if (pictures > 0) ms / pictures else 0.0))
                line(if (pictures > 0 && ms / pictures < 500) "  decision: quick enough to draw without asking (the Briefing card loads them by itself)" else "  decision: slow: offer a Generate button")
                if (pictures == 0) line("FAIL: no picture of the mission's set was drawn")
            }

            // ---------------------------------------------------------------- 3b. the Threats pages
            line("")
            line("== 3b. Threat guide: each air-defence and naval entry found in the base theater's tables")
            if (tables != null) {
                val guide = runCatching { kotlinx.coroutines.runBlocking { com.bmscompanion.app.data.Repo.threats() } }.getOrDefault(emptyList())
                    .filter { it.category != "AIRCRAFT" && it.category != "AAM" }
                val missing = ArrayList<String>()
                var found = 0
                for (th in guide) {
                    val names = (listOfNotNull(th.tacref, th.name) + th.aliases).joinToString("|")
                    val g = TargetPictures.system(base.name, names)
                    if (g.items.isNotEmpty()) found++ else missing += th.name
                    if (th.name.startsWith("SA-6") || th.name.startsWith("Patriot") || th.name.startsWith("Straight")) line("  ${th.name} → " + g.items.joinToString(" | ") { it.name })
                }
                line("  $found of ${guide.size} entries have pictures; none for: ${missing.joinToString(", ")}")
                if (guide.isNotEmpty() && found * 100 < guide.size * 70) line("FAIL: under 70 % of the threat guide's ground and naval entries resolve")
            }

            // ---------------------------------------------------------------- 4. cache
            line("")
            line("== 4. Cache")
            val k = tables?.let { TargetModels.byName(it, "T-72") }
            if (k == null || tables == null) line("FAIL: no T-72 for the cache check")
            else {
                val key = "ct${k.ct}"
                TargetPictures.forgetMemory()
                val t1 = System.nanoTime()
                TargetPictures.picture(base.name, key, TargetRender.View.FRONT_RIGHT, TargetRender.Look.INTEL, 480)
                val cold = (System.nanoTime() - t1) / 1e6
                val t2 = System.nanoTime()
                TargetPictures.picture(base.name, key, TargetRender.View.FRONT_RIGHT, TargetRender.Look.INTEL, 480)
                val warm = (System.nanoTime() - t2) / 1e6
                TargetPictures.forgetMemory()
                val t3 = System.nanoTime()
                val again = TargetPictures.picture(base.name, key, TargetRender.View.FRONT_RIGHT, TargetRender.Look.INTEL, 480)
                val disk = (System.nanoTime() - t3) / 1e6
                line("  drawn %.0f ms, from memory %.1f ms, from the folder %.1f ms".format(cold, warm, disk))
                if (again !is TargetPictures.Answer.Ok) line("FAIL: the picture did not come back from the folder")
                val before = cache.listFiles()?.toList().orEmpty()
                val beforeBytes = before.sumOf { it.length() }
                val cap = 512L shl 10
                TargetPictures.trim(cache, cap)
                val after = cache.listFiles()?.toList().orEmpty()
                val afterBytes = after.sumOf { it.length() }
                line("  folder ${before.size} pictures, $beforeBytes bytes; trimmed to a ${cap shr 10} KB cap: ${after.size} pictures, $afterBytes bytes")
                if (afterBytes > cap) line("FAIL: the folder is over its cap after trimming")
                if (after.isEmpty()) line("FAIL: trimming emptied the folder")
            }
        } catch (e: Throwable) {
            line("FAIL: ${e.javaClass.simpleName}: ${e.message}")
            e.stackTrace.take(6).forEach { line("    at $it") }
        } finally {
            TargetPictures.setOverride = null
            TargetPictures.cacheOverride = null
        }
        return sb.toString()
    }

    private fun safe(s: String) = s.replace(Regex("[^A-Za-z0-9-]+"), "_").trim('_')

    private fun draw(tables: TargetModels.Tables, t: Theaters.Theater, k: TargetModels.Kind, out: File, line: (String) -> Unit) {
        val f = TargetModels.modelFile(tables, k.gfx)
        if (f == null) { line("FAIL: ${k.name}: no model folder ${k.gfx}"); return }
        TargetPictures.forgetMemory()
        val times = ArrayList<String>()
        for (look in TargetRender.Look.entries) for (v in TargetRender.View.entries) {
            when (val a = TargetPictures.picture(t.name, "ct${k.ct}", v, look, TargetPictures.DEFAULT_WIDTH)) {
                is TargetPictures.Answer.Ok -> {
                    File(out, "${safe(k.name)}-${v.id}-${look.id}.jpg").writeBytes(a.bytes)
                    if (look == TargetRender.Look.PLAIN) times += "${v.id}: ${TargetPictures.lastTiming}, ${a.bytes.size / 1024} KB"
                }
                is TargetPictures.Answer.Refused -> line("FAIL: ${k.name} ${v.id} ${look.id}: ${a.why}")
            }
        }
        line("  ${k.name} [ct ${k.ct}, model ${k.gfx}, ${k.category}, slots ${k.slotModels.size}]: ${times.joinToString("; ")}")
    }
}
