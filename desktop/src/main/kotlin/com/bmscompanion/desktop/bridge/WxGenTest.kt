package com.bmscompanion.desktop.bridge

import com.bmscompanion.app.data.weather.CampaignTime
import com.bmscompanion.app.data.weather.WxCell
import com.bmscompanion.app.data.weather.WxCover
import com.bmscompanion.app.data.weather.WxDefaults
import com.bmscompanion.app.data.weather.WxGenParams
import com.bmscompanion.app.data.weather.WxModel
import com.bmscompanion.app.data.weather.WxMovement
import com.bmscompanion.app.data.weather.WxOverride
import com.bmscompanion.app.data.weather.WxType
import java.io.File
import kotlin.math.abs

/**
 * `--wxgentest <reference.tsv> <out.txt>` — the weather generator against the program it was ported from.
 *
 * The reference is WeatherGen's own `model.cljc`, run on the JVM by `tools/wxref/wxref.clj`: fourteen whole grids
 * (three seeds at three times, a tuned set with two overrides at three times, and two runs of WeatherGen's own
 * `step`) and two six-step forecasts. The port is asked for the same thing with `exact` on and must give the same
 * type, cover and towering in every cell and every number to 1e-9. It runs on the same JVM as the reference, so the
 * sines are the same sines and anything short of that is a porting mistake.
 *
 * Then the one deliberate difference, measured: how many cells the app's short-way-round wind headings change.
 */
object WxGenTest {

    private val movement = WxMovement(headingDeg = 135.0, speedKt = 20.0, stepMin = 60.0)

    private val storm = WxOverride(
        x = 20.0, y = 30.0, radius = 12.0, falloff = 4.0, strength = 0.8, animate = true,
        begin = CampaignTime(1, 5, 0), peak = CampaignTime(1, 7, 0), taper = CampaignTime(1, 10, 0), end = CampaignTime(1, 12, 0),
        type = WxType.INCLEMENT, tempC = 5.0, visKm = 2.0, cover = WxCover.OVERCAST, baseFt = 800.0, size = 1.0,
        towering = true, windDeg = 270.0, windKt = 40.0, windAlts = listOf(0, 3000, 6000), excludeFromForecast = true,
    )
    private val clear = WxOverride(
        x = 45.0, y = 10.0, radius = 9.0, falloff = 2.0, strength = 1.0, animate = false,
        type = WxType.FAIR, towering = false, windDeg = 10.0, windAlts = WxDefaults.WIND_ALTS,
    )

    private val defaults = WxGenParams(movement = movement)
    private val tuned = defaults.copy(
        seed = 777.0, featureSize = 7.0, crossfade = 0.2, windUniformity = 0.5, evolution = 1800.0,
        originX = 250.5, originY = -40.25, turbulenceSize = 2.0, turbulencePower = 120.0, prevailingDeg = 10.0,
        timeOffset = -300.0, pressureVariance = 0.8,
        sunny = WxDefaults.SUNNY.copy(weight = 20.0),
        poor = WxDefaults.POOR.copy(weight = 70.0),
        fair = WxDefaults.FAIR.copy(lowClouds = WxDefaults.FAIR.lowClouds!!.copy(towering = 0.6)),
        overrides = listOf(storm, clear),
    )

    private fun at(p: WxGenParams, d: Int, h: Int, m: Int) = p.copy(current = CampaignTime(d, h, m))

    /** Must match `scenarios` in tools/wxref/wxref.clj. */
    val scenarios: List<Pair<String, WxGenParams>> = listOf(
        "s1234-d1-0500" to at(defaults.copy(seed = 1234.0), 1, 5, 0),
        "s1234-d1-1330" to at(defaults.copy(seed = 1234.0), 1, 13, 30),
        "s1234-d3-2210" to at(defaults.copy(seed = 1234.0), 3, 22, 10),
        "s42-d1-0500" to at(defaults.copy(seed = 42.0), 1, 5, 0),
        "s42-d1-1330" to at(defaults.copy(seed = 42.0), 1, 13, 30),
        "s42-d3-2210" to at(defaults.copy(seed = 42.0), 3, 22, 10),
        "s4711-d1-0500" to at(defaults.copy(seed = 4711.0), 1, 5, 0),
        "s4711-d1-1330" to at(defaults.copy(seed = 4711.0), 1, 13, 30),
        "s4711-d3-2210" to at(defaults.copy(seed = 4711.0), 3, 22, 10),
        "tuned-d1-0600" to at(tuned, 1, 6, 0),
        "tuned-d1-0800" to at(tuned, 1, 8, 0),
        "tuned-d1-1100" to at(tuned, 1, 11, 0),
        "tuned-step3" to WxModel.step(at(tuned, 1, 6, 0), 3),
        "s42-step5" to WxModel.step(at(defaults.copy(seed = 42.0), 1, 5, 0), 5),
    )

    private val forecasts = listOf(
        Triple("fc-tuned", at(tuned, 1, 6, 0), 20 to 30),
        Triple("fc-s42", at(defaults.copy(seed = 42.0), 1, 5, 0), 10 to 40),
    )

    private val typeName = mapOf(WxType.SUNNY to "sunny", WxType.FAIR to "fair", WxType.POOR to "poor", WxType.INCLEMENT to "inclement")
    private val coverName = mapOf(
        WxCover.NONE to "none", WxCover.FEW to "few", WxCover.SCATTERED to "scattered",
        WxCover.BROKEN to "broken", WxCover.OVERCAST to "overcast",
    )

    /** The port's answer in the reference's columns: value type pressure temp speed×10 heading×10 cover towering base size vis. */
    private fun columns(c: WxCell): List<String> =
        listOf(c.value.toString(), typeName.getValue(c.type), c.pressureInHg.toString(), c.tempC.toString()) +
            c.windKt.map { it.toString() } + c.windDeg.map { it.toString() } +
            listOf(coverName.getValue(c.cover), if (c.towering) "1" else "0", c.baseFt.toString(), c.size.toString(), c.visKm.toString())

    private val names = listOf("value", "type", "pressure", "temp") +
        WxDefaults.WIND_ALTS.map { "speed@$it" } + WxDefaults.WIND_ALTS.map { "heading@$it" } +
        listOf("coverage", "towering", "base", "size", "visibility")

    fun run(reference: File): String = buildString {
        if (!reference.isFile) { appendLine("FAIL: no reference at $reference (make it with tools/wxref/wxref.clj)"); return@buildString }
        val want = HashMap<String, List<String>>()
        reference.forEachLine { line ->
            if (line.startsWith("#") || line.isBlank()) return@forEachLine
            val f = line.split('\t')
            want["${f[0]}|${f[1]}|${f[2]}"] = f.drop(3)
        }
        appendLine("reference rows: ${want.size}")

        val got = HashMap<String, List<String>>()
        val t0 = System.nanoTime()
        for ((name, p) in scenarios) {
            val g = WxModel.grid(p, exact = true)
            for (y in 0 until g.rows) for (x in 0 until g.cols) got["$name|$x|$y"] = columns(g.at(x, y))
        }
        val ms = (System.nanoTime() - t0) / 1e6 / scenarios.size
        for ((name, p, cell) in forecasts) {
            for ((t, c) in WxModel.forecast(p, cell.first, cell.second, 60.0, 6, exact = true)) {
                got["$name@${t.minutes}|${cell.first}|${cell.second}"] = columns(c)
            }
        }
        appendLine("port rows: ${got.size}   (one 59 x 59 grid: ${"%.1f".format(ms)} ms)")

        var failures = 0
        val worst = DoubleArray(names.size)
        val bad = IntArray(names.size)
        val firstBad = arrayOfNulls<String>(names.size)
        val missing = want.keys - got.keys
        if (missing.isNotEmpty()) { failures++; appendLine("FAIL: ${missing.size} reference rows the port did not produce, e.g. ${missing.first()}") }
        for ((key, w) in want) {
            val g = got[key] ?: continue
            for (i in names.indices) {
                val a = w.getOrNull(i) ?: ""
                val b = g.getOrNull(i) ?: ""
                val da = a.toDoubleOrNull()
                val db = b.toDoubleOrNull()
                val ok = if (da != null && db != null) {
                    val d = abs(da - db)
                    if (d > worst[i]) worst[i] = d
                    d <= 1e-9 * maxOf(1.0, abs(da))
                } else a == b
                if (!ok) { bad[i]++; if (firstBad[i] == null) firstBad[i] = "$key: VMT $a, port $b" }
            }
        }
        appendLine()
        appendLine("column              mismatches   largest difference")
        for (i in names.indices) {
            appendLine("${names[i].padEnd(20)}${bad[i].toString().padStart(10)}   ${"%.3g".format(worst[i])}" + (firstBad[i]?.let { "   first: $it" } ?: ""))
            if (bad[i] > 0) failures++
        }

        // the one deliberate difference: winds aloft turned toward the prevailing wind the short way round
        appendLine()
        var changed = 0
        var cells = 0
        var biggest = 0.0
        var example = ""
        for ((name, p) in scenarios) {
            val a = WxModel.grid(p, exact = true)
            val b = WxModel.grid(p, exact = false)
            for (c in a.cells.indices) {
                cells++
                var d = 0.0
                for (k in 0 until 10) {
                    val diff = abs(((a.cells[c].windDeg[k] - b.cells[c].windDeg[k]) + 540.0).mod(360.0) - 180.0)
                    if (diff > d) d = diff
                }
                if (d > 0.5) changed++
                if (d > biggest) {
                    biggest = d
                    example = "$name cell ${c % a.cols},${c / a.cols}: VMT " +
                        a.cells[c].windDeg.joinToString(" ") { it.toInt().toString() } + " | app " +
                        b.cells[c].windDeg.joinToString(" ") { it.toInt().toString() }
                }
                // nothing else may move
                if (a.cells[c].type != b.cells[c].type || a.cells[c].cover != b.cells[c].cover || a.cells[c].windKt[9] != b.cells[c].windKt[9]) {
                    failures++
                    appendLine("FAIL: the heading fix moved something other than a heading at $name cell $c")
                }
            }
        }
        appendLine("heading fix: $changed of $cells cells have a wind aloft turned the short way (largest change ${"%.0f".format(biggest)}°)")
        appendLine("  $example")

        // the parameters are what crosses the network, so they must survive it exactly, and an empty body is the defaults
        appendLine()
        val text = Bridge.json.encodeToString(WxGenParams.serializer(), tuned)
        val back = runCatching { Bridge.json.decodeFromString(WxGenParams.serializer(), text) }.getOrNull()
        val empty = runCatching { Bridge.json.decodeFromString(WxGenParams.serializer(), "{}") }.getOrNull()
        val sameGrid = back != null && WxModel.grid(back).cells.zip(WxModel.grid(tuned).cells).all { (a, b) -> a.pressureInHg == b.pressureInHg && a.windDeg.contentEquals(b.windDeg) }
        val jsonOk = back == tuned && empty == WxGenParams() && sameGrid
        appendLine((if (jsonOk) "ok   " else "FAIL ") + "parameters survive JSON (${text.length} bytes) and {} is the defaults")
        if (!jsonOk) failures++

        appendLine()
        appendLine(if (failures == 0) "PASS — the port gives WeatherGen's weather, cell for cell" else "FAIL — $failures problem(s)")
    }
}

/**
 * The generated-weather half of `--wxtest`: a map of its own, a series of update maps, and undoing both — against the
 * same copy of a Data folder, after the stock-model checks have put it back as it was.
 *
 * The series is laid over the start of day 1 on purpose: in a copy of Korea's folder `10100`-`10300.fmap` are BMS's
 * own update maps and `10000.fmap` is not there, so one run has to back up and replace some files and add another,
 * and undoing it has to put back the first kind and delete the second.
 */
internal fun wxGeneratedTest(
    store: WeatherStore,
    theater: String,
    campaign: File,
    stockHashes: Map<String, String>,
    sha: (File) -> String,
    check: (String, Boolean, String) -> Unit,
) {
    fun listing(dir: File) = dir.listFiles()?.filter { it.isFile }?.associate { it.name.lowercase() to sha(it) } ?: emptyMap()
    val updates = File(campaign, WeatherStore.UPDATES)
    val backup = File(campaign, WeatherStore.BACKUP_DIR)
    val campaignBefore = listing(campaign)
    val updatesBefore = listing(updates)
    val gen = WxGenParams(seed = 42.0)

    // ---- one generated map, a file of its own
    var state = store.writeGenerated(theater, "Test Front", gen)
    check("generated map accepted", state.error == null, state.error ?: "")
    val genFile = File(campaign, "BMSC Test Front.fmap")
    val map = Fmap.read(genFile)
    val stock = Fmap.read(File(backup, "SUNNY.fmap")) ?: Fmap.read(File(campaign, "SUNNY.fmap"))
    check(
        "it is a version 5 map of the theater's own grid",
        map != null && map.writable && stock != null && map.cols == stock.cols && map.rows == stock.rows &&
            genFile.length() == File(campaign, "SUNNY.fmap").length(),
        "",
    )
    check("it is listed as added", state.theaters.first { it.id == theater }.added.any { it.equals("BMSC Test Front.fmap", true) }, "")
    check("BMS's four ready-made maps are untouched", stockHashes.all { (file, hash) -> sha(File(campaign, file)) == hash }, "")
    if (map != null) {
        val grid = WxModel.grid(gen)
        var bad = 0
        var first = ""
        fun miss(what: String) { bad++; if (first.isEmpty()) first = what }
        for (c in 0 until map.cells) {
            val w = grid.cells[c]
            if (map.int(Fmap.Field.TYPE, c) != w.type.code) miss("type at $c")
            if (map.cover(c) != Fmap.legalCover(w.type, w.cover)) miss("cover at $c: ${map.int(Fmap.Field.COVER, c)}")
            if ((map.int(Fmap.Field.TOWERING, c) != 0) != w.towering) miss("towering at $c")
            if (abs(map.float(Fmap.Field.PRESSURE, c) - WxModel.inHgToMb(w.pressureInHg)) > 0.01) miss("pressure at $c")
            if (abs(map.float(Fmap.Field.TEMPERATURE, c) - w.tempC) > 0.001) miss("temperature at $c")
            if (abs(map.float(Fmap.Field.CLOUD_BASE, c) - w.baseFt) > 0.5) miss("cloud base at $c")
            if (abs(map.float(Fmap.Field.CLOUD_SIZE, c) - w.size) > 0.001) miss("cloud size at $c")
            if (abs(map.float(Fmap.Field.VISIBILITY, c) - w.visKm) > 0.001) miss("visibility at $c")
            for (k in 0 until Fmap.Field.LEVELS) {
                if (abs(map.windSpeed(c, k) - w.windKt[k].coerceAtLeast(0.0) * Fmap.KMH_PER_KT) > 0.01) miss("wind speed at $c level $k")
                if (abs(map.windDir(c, k) - w.windDeg[k].mod(360.0)) > 0.01) miss("wind direction at $c level $k")
            }
        }
        check("every cell holds the model's weather (wind in km/h, cover as BMS's code)", bad == 0, first)
        val covers = (0 until map.cells).map { map.int(Fmap.Field.COVER, it) }.toSet()
        check("cover codes are only BMS's own", covers.all { it in setOf(0, 1, 5, 9, 13) }, covers.sorted().toString())
        check(
            "header: movement, stratus and contrails",
            map.moveHeading == 135 && map.moveSpeed == 20f && map.stratus == listOf(43300, 33000) &&
                map.contrails == listOf(34000, 28000, 25000, 20000),
            "${map.moveHeading} ${map.moveSpeed} ${map.stratus} ${map.contrails}",
        )
    }
    val once = sha(genFile)
    store.writeGenerated(theater, "Test Front", gen)
    check("the same parameters give the same file", sha(genFile) == once, "")

    // ---- it never takes the place of a file it did not write
    val foreign = File(campaign, "BMSC Foreign.fmap")
    foreign.writeText("not ours")
    state = store.writeGenerated(theater, "Foreign", gen)
    check("refuses to write over a file it did not write", state.error != null && foreign.readText() == "not ours", state.error ?: "no error")
    foreign.delete()
    state = store.writeGenerated(theater, "../escape", gen)
    check("refuses a name that is not a plain name", state.error != null, state.error ?: "no error")

    // ---- a series
    state = store.writeSeries(theater, "Test Series", gen, CampaignTime(1, 0, 0), CampaignTime(1, 3, 0), 30)
    check("refuses a step under BMS's 55 minutes", state.error != null, state.error ?: "no error")
    val names = listOf("10000.fmap", "10100.fmap", "10200.fmap", "10300.fmap")
    val hadBefore = names.filter { File(updates, it).isFile }
    state = store.writeSeries(theater, "Test Series", gen, CampaignTime(1, 0, 0), CampaignTime(1, 3, 0), 60)
    check("series accepted", state.error == null, state.error ?: "")
    check(
        "four update maps, and the first as a map of its own",
        names.all { File(updates, it).isFile } && File(campaign, "BMSC Test Series.fmap").isFile,
        "",
    )
    check(
        "each of BMS's own that was written over is in the backup, unchanged",
        hadBefore.all { File(backup, "${WeatherStore.UPDATES}/$it").let { b -> b.isFile && sha(b) == updatesBefore[it.lowercase()] } },
        "had before: $hadBefore",
    )
    val th = state.theaters.first { it.id == theater }
    check(
        "the manifest says which were added and which replaced",
        names.all { n ->
            val rel = "${WeatherStore.UPDATES}/$n"
            if (n in hadBefore) th.replaced.contains(rel) else th.added.contains(rel)
        },
        "added ${th.added} replaced ${th.replaced}",
    )
    val template = Fmap.read(File(backup, "SUNNY.fmap"))
    val at0200 = gen.copy(current = CampaignTime(1, 2, 0))
    val want0200 = template?.let { Fmap.generated(it, WxModel.grid(at0200), at0200)?.toBytes() }
    check("10200.fmap is the weather at day 1 02:00", want0200 != null && File(updates, "10200.fmap").readBytes().contentEquals(want0200), "")
    check("the weather evolves through the series", sha(File(updates, "10000.fmap")) != sha(File(updates, "10300.fmap")), "")
    check(
        "the first map of the series is its own start",
        File(campaign, "BMSC Test Series.fmap").readBytes().contentEquals(File(updates, "10000.fmap").readBytes()),
        "",
    )
    // written twice: nothing backed up a second time, nothing listed twice
    val backupsOnce = listing(File(backup, WeatherStore.UPDATES))
    store.writeSeries(theater, "Test Series", gen.copy(seed = 99.0), CampaignTime(1, 0, 0), CampaignTime(1, 3, 0), 60)
    check("a second series leaves the backups exactly as the first took them", listing(File(backup, WeatherStore.UPDATES)) == backupsOnce, "")

    // ---- undoing the series, then the generated maps
    state = store.restoreSeries(theater)
    check("series restore reported no error", state.error == null, state.error ?: "")
    check("WeatherMapsUpdates is byte for byte what it was", listing(updates) == updatesBefore, "")
    state = store.removeGenerated(theater, null)
    check("removing the generated maps reported no error", state.error == null, state.error ?: "")
    check(
        "the Campaign folder is byte for byte what it was",
        listing(campaign) == campaignBefore,
        (listing(campaign).keys - campaignBefore.keys).toString(),
    )
    val left = state.theaters.first { it.id == theater }
    check("nothing is listed as written any more", left.added.isEmpty() && left.replaced.isEmpty(), "${left.added} ${left.replaced}")

    // ---- and by hand, the way the README says
    store.writeSeries(theater, "Test Series", gen, CampaignTime(1, 0, 0), CampaignTime(1, 3, 0), 60)
    File(backup, WeatherStore.UPDATES).listFiles()?.forEach { it.copyTo(File(updates, it.name), overwrite = true) }
    File(backup, WeatherStore.MANIFEST).readLines().filter { it.startsWith("added\t") }
        .forEach { File(campaign, it.substringAfter('\t')).delete() }
    check(
        "undoing a series by hand, as the README says, works too",
        listing(updates) == updatesBefore && listing(campaign) == campaignBefore,
        "",
    )
    // and leave the manifest as the store would have it
    store.restoreSeries(theater)
    store.removeGenerated(theater, null)
}
