package com.bmscompanion.desktop.bridge

import java.io.File

/**
 * `--theatertest out.txt <root> [more…]`: [Theaters] against a BMS folder (the round-3 fixture, or any copy; it only
 * reads). One row per theater — its name, campaign folder and how many saves are in it, 3D folder and how many F-16
 * kneeboard pages, where its class tables come from — and then the facts R3-PLAN B0b demands of this install:
 * 19 theaters, the 7 campaign folders the old readers missed, which theaters share another's kneeboard pages, OFM KTO's
 * single page, the registry's `curTheater` resolving, trimmed names and the app's theater ids.
 */
object TheaterTest {
    fun run(root: File, more: List<String>): String = buildString {
        var fails = 0
        fun check(name: String, ok: Boolean, detail: String = "") {
            appendLine((if (ok) "PASS " else "FAIL ") + name + if (detail.isNotEmpty()) "  | $detail" else "")
            if (!ok) fails++
        }

        val t0 = System.nanoTime()
        val set = Theaters.at(root)
        val ms = (System.nanoTime() - t0) / 1_000_000
        appendLine("root: ${root.path}")
        appendLine("theaters: ${set.all.size} (read in $ms ms); error: ${set.error ?: "none"}")
        appendLine()

        fun rel(f: File?): String = f?.let { d ->
            val data = Theaters.canonical(set.data)
            val c = Theaters.canonical(d)
            if (c.startsWith(data)) d.canonicalPath.substring(set.data.canonicalPath.length).trimStart('\\', '/') else d.path
        } ?: "-"

        // the rows
        val saves = HashMap<String, Int>()
        appendLine("%-3s %-22s %-50s %5s  %-34s %5s  %s".format("#", "name", "campaign folder (under Data)", "saves", "3D folder (under Data)", "pages", "class tables"))
        for (t in set.all) {
            val camp = set.campaignDir(t)
            val n = set.saves(t).size
            saves[t.name] = n
            val pages = set.kneeboardPages(t).size
            val tables = listOf("Falcon4_CT.xml", "Falcon4_UCD.xml", "Falcon4_VCD.xml", "Falcon4_WCD.xml").joinToString(" ") { f ->
                val found = set.classFile(t, f)
                val own = found != null && set.objectDir(t)?.let { Theaters.canonical(found.parentFile) == Theaters.canonical(it) } == true
                f.removePrefix("Falcon4_").removeSuffix(".xml") + "=" + when { found == null -> "none"; own -> "own"; else -> "base" }
            }
            val name = if (t.rawName != t.name) "${t.name} [\"${t.rawName}\"]" else t.name
            appendLine("%-3d %-22s %-50s %5d  %-34s %5d  %s".format(t.index, name, rel(camp), n, rel(set.threeDDataDir(t)), pages, tables))
        }
        appendLine()
        appendLine("total saves (.cam/.tac/.trn directly in the campaign folders): ${saves.values.sum()}")
        appendLine("(pages are counted in this root: a copy may carry only some theaters' KoreaObj — the round-3 fixture has KTO, Hellas, Balkans and OFM KTO)")
        appendLine()

        // 1. how many
        check("19 theaters in theater.lst, every one read", set.all.size == 19 && set.error == null, "${set.all.size}, error ${set.error}")
        check("every campaigndir resolves to a folder", set.all.all { set.campaignDir(it) != null },
            set.all.filter { set.campaignDir(it) == null }.joinToString { it.name })

        // 2. what the old readers missed: they looked only at Data\Campaign and Data\Add-On*\Campaign
        val data = set.data
        val oldDirs = (listOf(data) + (data.listFiles { f -> f.isDirectory && f.name.startsWith("Add-On", true) }?.toList().orEmpty()))
            .map { File(it, "Campaign") }.filter { it.isDirectory }.map { Theaters.canonical(it) }.toSet()
        val missed = set.all.filter { t -> set.campaignDir(t)?.let { Theaters.canonical(it) !in oldDirs } ?: true }
        val expectMissed = setOf("Carrier War", "EuroWar", "Korea 2012", "MigAlley", "Carrier Redux", "X-Plane", "LKTO 4.38 + Papa")
        check("the 7 campaign folders the old readers missed (Korea 2012's six, LKTO Campaign+)",
            missed.map { it.name }.toSet() == expectMissed, missed.joinToString { it.name })
        appendLine("     saves in those folders: ${missed.sumOf { saves[it.name] ?: 0 }} of ${saves.values.sum()}")

        // 3. whose kneeboard pages each theater reads
        fun same3d(a: String, b: String): Boolean {
            val x = set.threeDDataDir(set.byName(a)); val y = set.threeDDataDir(set.byName(b))
            return x != null && y != null && Theaters.canonical(x) == Theaters.canonical(y)
        }
        check("LHTO, Hellas WCP -> Hellas's 3ddatadir", same3d("LHTO", "Hellas") && same3d("Hellas WCP", "Hellas"),
            "LHTO ${rel(set.threeDDataDir(set.byName("LHTO")))}, Hellas WCP ${rel(set.threeDDataDir(set.byName("Hellas WCP")))}")
        check("EF2000 BTO -> Balkans's 3ddatadir", same3d("EF2000 BTO", "Balkans"), rel(set.threeDDataDir(set.byName("EF2000 BTO"))))
        val kto = listOf("Korea TvT", "KTO 80s Revamp 4.38", "LKTO 4.38 + Papa", "LKTO 4.38 - Mike")
        check("Korea TvT, KTO 80s, LKTO + and - -> Korea KTO's 3ddatadir", kto.all { same3d(it, "Korea KTO") },
            kto.joinToString { "$it ${rel(set.threeDDataDir(set.byName(it)))}" })
        val ofm = set.kneeboardPages(set.byName("OFM KTO"))
        check("OFM KTO has 1 kneeboard page (7982 only)", ofm.size == 1 && ofm[0].name.equals("7982.dds", true), ofm.joinToString { it.name })
        val full = listOf("Korea KTO", "Hellas", "Balkans")
        check("Korea KTO, Hellas and Balkans have all 16 pages", full.all { set.kneeboardPages(set.byName(it)).size == 16 },
            full.joinToString { "$it ${set.kneeboardPages(set.byName(it)).size}" })

        // 4. the theater BMS is set to, from the registry (read only)
        val reg = BmsInstall().apply { refresh(null) }.theater
        val cur = set.current(reg)
        check("registry curTheater resolves", cur != null && cur.name == "Korea KTO", "curTheater='$reg' -> ${cur?.name}")

        // 5. names written freely
        val raw = listOf("Carrier War" to "Carrier War ", "Korea 2012" to "Korea 2012  ", "MigAlley" to "MigAlley ")
        check("trailing spaces trimmed (\"Carrier War \", \"Korea 2012  \", \"MigAlley \")",
            raw.all { (n, r) -> set.byName(n)?.rawName == r }, raw.joinToString { (n, _) -> "'${set.byName(n)?.rawName}'" })
        check("names match without case or padding", set.byName("  carrier war ")?.name == "Carrier War" && set.byName("KOREA KTO")?.index == 0)
        check("the campaign folder of KTO 80s is found though its case differs from the tdf's", set.campaignDir(set.byName("KTO 80s Revamp 4.38")) != null)

        // 6. the app's own ids (data/index.json), which the Planner and the maps use
        val appIds = runCatching {
            kotlinx.coroutines.runBlocking { com.bmscompanion.app.data.Repo.index().theaters.map { it.id } }
        }.getOrDefault(emptyList())
        val noId = set.all.filter { it.appId !in appIds }
        check("every theater's appId is one of the app's theater ids", appIds.isNotEmpty() && noId.isEmpty(),
            if (noId.isEmpty()) "${appIds.size} app ids" else noId.joinToString { "${it.name} -> ${it.appId}" })
        check("byName takes an app id", set.byName("lkto-4-38-plus-papa")?.name == "LKTO 4.38 + Papa")

        // 7. class tables per file, strings split on the first whitespace, the newest save
        val tvt = set.byName("Korea TvT")
        check("class tables resolve per file (Korea TvT falls back for the ones it lacks)",
            listOf("Falcon4_CT.xml", "Falcon4_UCD.xml", "Falcon4_VCD.xml", "Falcon4_WCD.xml").all { set.classFile(tvt, it) != null })
        val strings = set.strings(set.byName("Korea KTO"))
        check("Strings.txt split on the first whitespace (341 RELOCATE, written with a space)", strings[341] == "RELOCATE",
            "${strings.size} strings, 341='${strings[341]}'")
        val newest = set.newestSave(set.byName("Korea KTO"))
        check("newest save for Korea KTO is Data\\Campaign\\Auto Save.cam (not a start, not TvT's)",
            newest?.name == "Auto Save.cam" && newest.parentFile?.let { Theaters.canonical(it) } == Theaters.canonical(File(data, "Campaign")),
            newest?.let { rel(it.parentFile) + "\\" + it.name } ?: "none")
        val starts = set.saves(set.byName("Korea TvT")).map { "${it.name}=${if (Theaters.isStart(it)) "start" else "save"}" }
        appendLine("     Korea TvT's files: ${starts.joinToString()}")
        check("a campaign start is told by its structure (Save0.cam has an objectives part)",
            set.saves(set.byName("Korea KTO")).firstOrNull { it.name.equals("Save0.cam", true) }?.let(Theaters::isStart) == true)
        check("the owning theater of a campaign folder", set.owning(File(data, "Add-On LKTO\\Campaign+"))?.name == "LKTO 4.38 + Papa")

        // 8. nothing written: the check only reads
        appendLine()
        appendLine(if (fails == 0) "ALL PASS" else "FAIL: $fails check(s) failed")
    }
}
