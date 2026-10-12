package com.bmscompanion.desktop.bridge

import com.bmscompanion.app.data.mission.CampFlight
import com.bmscompanion.app.data.mission.WeatherTable
import java.io.File

/**
 * `--wxbrieftest out.txt` — the Mission section's weather as the pages get it, from the settings' BMS folder (a copy):
 *
 * 1. EZBoards mode: the printed briefing's weather block (`Briefing.weather`, what the Briefing page's Weather card and
 *    the Dashboard's WEATHER card draw), every row and column, and the checks a card relies on (three columns, every
 *    row filled, the figures parse back through `CardWeather.column`).
 * 2. WDP mode: for every theater, the newest saves with a weather file — the `.twx` as read (version, model, the type
 *    now, each type's table) and [SaveWeather.read] for a flight of the save, the block Populate puts in the snapshot.
 *
 * Read only: nothing is written anywhere but the report.
 */
object WxBriefTest {
    fun run(perTheater: Int = 3): String = buildString {
        fun ok(b: Boolean, what: String) = appendLine((if (b) "ok   " else "FAIL ") + what)
        Bridge.startForCheck()
        appendLine("BMS folder: ${Bridge.install.baseDir}")
        appendLine("")
        appendLine("== 1. The printed briefing's weather (EZBoards mode)")
        val printed = runCatching { Bridge.currentBriefing() }.getOrNull()
        if (printed == null) appendLine("     no printed briefing in the copy")
        else {
            val w = printed.weather
            table(w)
            ok(w != null && w.rows.isNotEmpty(), "the printed briefing has a weather block")
            if (w != null) {
                ok(w.columns.size == 3, "three columns: ${w.columns}")
                ok(w.rows.all { it.values.size == w.columns.size }, "every row has a value per column")
                ok(w.rows.none { it.label.isBlank() }, "every row is labelled")
                for (i in 0..2) {
                    val c = com.bmscompanion.app.ui.screens.wdp.CardWeather.column(printed, i)
                    ok(c != null && c.windKts != null && c.visM != null && c.tempC != null,
                        "column $i reads back: ${c?.situation} wind ${c?.windDir}/${c?.windKts} vis ${c?.visM} m temp ${c?.tempC} cloud ${c?.cloudFt}")
                }
            }
        }
        appendLine("")
        appendLine("== 2. Each theater's saves (WDP mode: Populate's weather)")
        val ctx = CampaignFiles.context()
        val set = ctx.set
        val files = CampaignFiles.list(ctx, false)
        for (th in files.theaters) {
            val t = set?.byName(th.name)
            val dir = set?.campaignDir(t) ?: continue
            val withTwx = th.files.filter { !it.start && File(dir, it.name.substringBeforeLast('.') + ".twx").isFile }
            // the newest, and up to two flown on a weather map (model 3), whose weather comes from the map's cells
            val maps = withTwx.filter { f ->
                runCatching { (Twx.read(File(dir, f.name.substringBeforeLast('.') + ".twx").readBytes()) as? Twx.Read.Ok)?.twx?.model == 3 }.getOrDefault(false)
            }.take(2)
            val saves = (withTwx.take(perTheater) + maps).distinct()
            if (saves.isEmpty()) continue
            appendLine("")
            appendLine("-- ${th.name} (${th.appTheater})")
            for (f in saves) {
                val save = File(dir, f.name)
                val twxFile = File(dir, f.name.substringBeforeLast('.') + ".twx")
                when (val r = Twx.read(twxFile.readBytes())) {
                    is Twx.Read.Bad -> { appendLine("   ${f.name}: .twx refused: ${r.why}"); continue }
                    is Twx.Read.Ok -> {
                        val x = r.twx
                        appendLine("   ${f.name}: twx v${x.version} model ${x.model} type now ${x.condition} windDeg ${x.windDeg} held ${x.windHeld} mapUpdates ${x.mapUpdates}")
                        x.types.forEachIndexed { k, ty ->
                            appendLine("      type $k: wind ${ty.windKt} kt, vis ${ty.fogEndFt.toInt()} ft, cumulus ${ty.cumulusFt} ft, stratus ${ty.stratusFt} ft, temp ${ty.tempC} C, qnh ${ty.qnhMb}")
                        }
                    }
                }
                val ato = (CampaignFiles.ato(ctx, th.name, f.name) as? CampaignFiles.Answer.Ok)?.value
                val briefed = printed?.overview?.flight?.trim()
                val row = ato?.packages?.flatMap { it.flights }?.let { all ->
                    all.firstOrNull { briefed != null && it.callsign.equals(briefed, true) } ?: all.firstOrNull { it.player } ?: all.firstOrNull()
                }
                if (row == null) { appendLine("      no flight"); continue }
                val flight = (CampaignFiles.flight(ctx, th.name, f.name, row.id) as? CampaignFiles.Answer.Ok)?.value ?: CampFlight()
                val archive = runCatching { CampaignArchive.cached(save, null) }.getOrNull()
                val read = SaveWeather.read(save, flight, th.appTheater, archive, row.id)
                appendLine("      flight ${row.callsign} (${row.mission}) clock ${flight.clock?.let(CampaignFiles::clockShort)}")
                if (read.table == null) appendLine("      no table: ${read.why}")
                else {
                    table(read.table, "      ")
                    ok(read.table.rows.none { r -> r.values.any { it == "–" } }, "      every figure filled (${th.name}, ${f.name})")
                    // the printed briefing's own flight: each row BMS prints must read the same from the save (the
                    // wind's speed only: a file of heading model 1 holds no direction)
                    val pw = printed?.weather
                    if (pw != null && briefed != null && row.callsign.equals(briefed, true) && set.current(Bridge.install.theater) === t) {
                        for (pr in pw.rows) {
                            val mine = read.table.rows.firstOrNull { it.label == pr.label }
                            if (mine == null) { ok(false, "      the save's block has a ${pr.label} row"); continue }
                            val a = if (pr.label == "Wind") pr.values.map { it.substringAfter('@').trim() } else pr.values.map { it.replace(" ", "") }
                            val b = if (pr.label == "Wind") mine.values.map { it.substringAfter('@').trim() } else mine.values.map { it.replace(" ", "") }
                            ok(a == b, "      ${pr.label} as printed: $a / from the save: $b")
                        }
                    }
                }
            }
        }
    }

    private fun StringBuilder.table(w: WeatherTable?, indent: String = "     ") {
        if (w == null) { appendLine("${indent}(no weather block)"); return }
        appendLine(indent + "columns: " + w.columns.joinToString(" | "))
        w.rows.forEach { appendLine(indent + it.label.padEnd(12) + it.values.joinToString(" | ")) }
        w.source?.let { appendLine("$indent(source) $it") }
    }
}
