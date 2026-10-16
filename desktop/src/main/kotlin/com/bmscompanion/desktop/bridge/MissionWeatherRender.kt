package com.bmscompanion.desktop.bridge

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Density
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.mission.Briefing
import com.bmscompanion.app.data.mission.CampFlight
import com.bmscompanion.app.data.mission.MissionMode
import com.bmscompanion.app.data.mission.MissionWeather
import com.bmscompanion.app.data.mission.MissionWxMap
import com.bmscompanion.app.data.weather.CampaignTime
import com.bmscompanion.app.data.weather.WxGenParams
import com.bmscompanion.app.data.weather.Astro
import com.bmscompanion.app.ui.screens.editor.MissionSkies
import com.bmscompanion.app.ui.screens.editor.MissionWeatherPage
import com.bmscompanion.app.ui.screens.editor.MissionWxLook
import com.bmscompanion.app.ui.screens.editor.WxFill
import com.bmscompanion.app.ui.screens.editor.WxPicture
import com.bmscompanion.app.ui.screens.editor.WxRoutePt
import com.bmscompanion.app.ui.theme.Hud
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import java.security.MessageDigest

/**
 * `--wxviewrender <a copy of the BMS folder> <out folder>` — the Weather tab's **Mission weather** page drawn headless
 * from the PC's own answers ([MissionWeatherRoute]), at a phone's, a tablet's and a PC's size, for two saves of the
 * copy's base theater:
 *
 * 1. **Map Model**: a save whose `.twx` flies a map (BMS's `TR_BMS_08_TFR` in Korea) with MAPS AUTO UPDATE. A copy of an
 *    install ships no update series in Korea's base campaign folder, so the check **synthesises** one in the copy:
 *    hourly `WeatherMapsUpdates/DHHMM.fmap` from WeatherGen's model around the save's clock, and the `.twx`'s map
 *    updates flag set — only in a folder that is plainly a copy ([DevGuard.why]) and under the temp folder.
 * 2. **Deterministic**: a save whose `.twx` is model 2 (a TE or campaign save of the same folder).
 *
 * The report checks what a picture cannot: the frames (the one in force at the save's clock first, later ones in time
 * order, past the landing), a frame's cells against the `.fmap` read directly (the wind at several cells and levels),
 * the Deterministic schedule and chances, the routes answering in-process, and that reading changed no file of the
 * copy's campaign folder (hashes before and after, the files the check itself wrote aside).
 */
object MissionWeatherRender {
    fun run(root: File, out: File): String = buildString {
        out.mkdirs()
        // the built-in bridge, read from the settings' BMS folder (which must be this copy for the flight to be read)
        Bridge.startForCheck()
        val base = Bridge.install.baseDir?.let { runCatching { File(it).canonicalPath }.getOrNull() }
        if (base == null || !base.equals(runCatching { root.canonicalPath }.getOrDefault(root.path), ignoreCase = true))
            appendLine("note the settings' BMS folder is ${base ?: "not set"}, not ${root.path}: the flights and the routes read that one")
        val temp = File(System.getProperty("java.io.tmpdir")).canonicalPath.trimEnd('\\', '/')
        val set = Theaters.at(root)
        val t = set.byName("Korea KTO") ?: set.all.firstOrNull()
        if (t == null) { appendLine("FAIL: no theater in ${root.path} (${set.error})"); return@buildString }
        val dir = set.campaignDir(t)
        if (dir == null || !dir.isDirectory) { appendLine("FAIL: ${t.name} has no campaign folder in the copy"); return@buildString }
        appendLine("Mission weather page, headless — ${t.name}, <copy>\\${runCatching { dir.canonicalFile.relativeTo(root.canonicalFile).path }.getOrDefault(dir.name)}")
        val names = CampaignArchive.names(set, t)
        val th = runBlocking { Repo.index().theaters.firstOrNull { it.id == t.appId } }
        val airports = th?.let { runBlocking { Repo.airportSet(it.airportSet).airports } }.orEmpty()

        fun twxModel(f: File): Int = runCatching { (Twx.read(f.readBytes()) as? Twx.Read.Ok)?.twx?.model ?: 0 }.getOrDefault(0)
        fun saveOf(twx: File): File? = listOf("cam", "tac", "trn").map { File(dir, twx.nameWithoutExtension + "." + it) }.firstOrNull { it.isFile }
        val twxs = dir.listFiles { f -> f.isFile && f.name.endsWith(".twx", true) }.orEmpty().sortedBy { it.name }
        val mapTwx = twxs.firstOrNull { it.name.startsWith("TR_BMS_08", true) && twxModel(it) == 3 }
            ?: twxs.firstOrNull { twxModel(it) == 3 && File(dir, it.nameWithoutExtension + ".fmap").isFile && saveOf(it) != null }
        val detTwx = twxs.firstOrNull { it.name.startsWith("TE_BMS_03", true) && twxModel(it) == 2 }
            ?: twxs.firstOrNull { twxModel(it) == 2 && saveOf(it) != null }

        // what the folder holds before anything is read, to show reading wrote nothing
        val before = hashes(dir)

        // ---- 1. Map Model, with a synthesised update series
        val written = HashSet<String>()
        if (mapTwx == null) appendLine("FAIL: no Map Model save (a .twx of model 3 with its .fmap) in ${dir.path}")
        else {
            val save = saveOf(mapTwx)!!
            appendLine()
            appendLine("1. Map Model: ${save.name}")
            val copyOk = DevGuard.why(root) == null && root.canonicalPath.startsWith(temp + File.separator, ignoreCase = true)
            val upd = File(dir, "WeatherMapsUpdates")
            val twxBytes = mapTwx.readBytes()
            val clock = (java.nio.ByteBuffer.wrap(twxBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(16).toLong() and 0xFFFFFFFFL)
            val hasSeries = upd.listFiles()?.any { CampaignTime.ofFmapName(it.name)?.let { c -> c.minutes > clock / 60_000 } == true } == true
            if (!hasSeries) {
                if (!copyOk) appendLine("FAIL: no update series in the copy, and ${root.path} is not a copy under the temp folder to make one in")
                else {
                    // the flag BMS sets with MAPS AUTO UPDATE on, and hourly maps from the hour before the clock
                    java.nio.ByteBuffer.wrap(twxBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(216, 1)
                    mapTwx.writeBytes(twxBytes)
                    written += mapTwx.name
                    val template = File(dir, "Sunny.fmap").takeIf { it.isFile }?.let { Fmap.read(it) }?.takeIf { it.writable } ?: Fmap.blank()
                    upd.mkdirs()
                    val h0 = (clock / 3_600_000L).toInt() - 1
                    for (k in 0 until 10) {
                        val ct = CampaignTime.fromMinutes(((h0 + k) * 60).toDouble())
                        val p = WxGenParams(seed = 4711.0, current = ct)
                        val m = Fmap.generated(template, com.bmscompanion.app.data.weather.WxModel.grid(p), p) ?: continue
                        File(upd, ct.fmapName).writeBytes(m.toBytes())
                        written += "WeatherMapsUpdates/" + ct.fmapName
                    }
                    appendLine("ok   synthesised ${written.size - 1} hourly update maps from ${CampaignTime.fromMinutes(h0 * 60.0).label} and set the map updates flag in ${mapTwx.name} (the copy)")
                }
            }
            val archive = runCatching { CampaignArchive.cached(save, names) }.getOrNull()
            val f = archive?.flights?.firstOrNull { it.hasPlayer } ?: archive?.flights?.firstOrNull()
            val flight = f?.let { (CampaignFiles.flight(CampaignFiles.context(), t.name, save.name, it.id.toString()) as? CampaignFiles.Answer.Ok)?.value }
            if (flight == null) {
                val said = f?.let { (CampaignFiles.flight(CampaignFiles.context(), t.name, save.name, it.id.toString()) as? CampaignFiles.Answer.Refused)?.sentence }
                appendLine("note no flight of ${save.name} read through CampaignFiles (${f?.id ?: "no flight in the save"}: ${said ?: archive?.error ?: "?"}): no take-off, target or landing")
            }
            val (w, files) = MissionWeatherRoute.of(MissionMode.EZBOARDS, t, save, flight, f?.id?.toString(), archive)
            check(w.ok, "answer ok (${w.why ?: "no reason"})")
            check(w.model == 3, "model is Map Model (${w.model})")
            check(w.mapUpdates, "map updates on")
            check(w.frames.size >= 3, "${w.frames.size} frames: ${w.frames.joinToString { it.file }}")
            val minutes = w.frames.map { it.minute ?: -1 }
            check(minutes.zipWithNext().all { (a, b) -> a < b }, "frames in time order")
            val clockMin = (w.clock ?: 0) / 60_000
            check(minutes.firstOrNull()?.let { it <= clockMin } == true && minutes.getOrNull(1)?.let { it > clockMin } != false,
                "the first frame is the one in force at the save's clock ${w.clock?.let { CampaignFiles.clockShort(it) }}")
            val landing = w.places.maxOfOrNull { it.ms } ?: 0
            check(w.places.size >= 2, "places: " + w.places.joinToString { "${it.label} ${it.name ?: ""} ${CampaignFiles.clockShort(it.ms)}" })
            check(minutes.last() >= landing / 60_000 || w.frames.size == MissionWeatherRoute.MAX_FRAMES, "the frames reach past the landing")
            // the cells against the file itself
            val maps = LinkedHashMap<String, MissionWxMap>()
            for (fr in w.frames) {
                val file = files[fr.id] ?: continue
                val m = Fmap.read(file) ?: continue
                maps[fr.id] = MissionWeatherRoute.cells(m, fr.id, file.name)
            }
            check(maps.size == w.frames.size && maps.values.all { it.whole }, "every frame's cells read whole (${maps.size})")
            maps.entries.firstOrNull()?.let { (id, mm) ->
                val raw = Fmap.read(files[id]!!)!!
                val probes = listOf(0 to 0, 5 to 3, 1000 to 9, 2000 to 5, mm.cells - 1 to 1)
                val bad = probes.filter { (c, l) ->
                    val kt = Math.round(raw.windSpeed(c, l) / Fmap.KMH_PER_KT).toInt()
                    val deg = Math.round(raw.windDir(c, l)).toInt().mod(360)
                    mm.windKt[c * 10 + l] != kt || mm.windDeg[c * 10 + l] != deg
                }
                check(bad.isEmpty(), "the wind at five cells and levels matches the file ($bad)")
                check(mm.visKm.indices.all { mm.visKm[it] == Math.round(raw.visibilityKm(it) * 10f) / 10f }, "visibility from the right array (version ${mm.version})")
            }
            // the three pictures, and two more
            val bm = briefingOf(save, flight, t, archive, f?.id?.toString())
            val pic = pictureOf(flight, airports)
            fun shot(name: String, wDp: Int, hDp: Int, d: Float, frame: Int) = picture(out, name, wDp, hDp, d) {
                MissionWeatherPage(w, null, th, maps, emptyMap(), frame, {}, pic, bm, {})
            }
            val keep = Triple(MissionWxLook.fill, MissionWxLook.wind, MissionWxLook.level)
            val iso = MissionWxLook.isobars
            setLook(WxFill.CLOUDS, wind = true, level = 0, isobars = false)
            appendLine(shot("mapmodel-phone", 400, 860, 2f, 0))
            appendLine(shot("mapmodel-tablet", 1280, 800, 1.5f, 0))
            appendLine(shot("mapmodel-pc", 1600, 1000, 1f, 0))
            setLook(WxFill.PRECIP, wind = true, level = 7, isobars = true)
            appendLine(shot("mapmodel-pc-precip-isobars-fl300", 1600, 1000, 1f, minOf(3, w.frames.size - 1)))
            setLook(WxFill.VISIBILITY, wind = false, level = 0, isobars = true)
            appendLine(shot("mapmodel-tablet-visibility", 1280, 800, 1.5f, minOf(5, w.frames.size - 1)))
            // 1.4.1: every fill's colour ramp (smooth), the blocks for comparison, and the phone and tablet with a ramp
            val sm = MissionWxLook.smooth
            for (fill in WxFill.SHOWN) {
                setLook(fill, wind = fill == WxFill.WIND, level = if (fill == WxFill.WIND) 7 else 0, isobars = fill == WxFill.PRESSURE, smooth = true)
                appendLine(shot("ramp-${fill.name.lowercase()}-pc", 1600, 1000, 1f, 0))
            }
            setLook(WxFill.CLOUDS, wind = false, level = 0, isobars = false, smooth = false)
            appendLine(shot("ramp-clouds-blocks-pc", 1600, 1000, 1f, 0))
            setLook(WxFill.WIND, wind = false, level = 5, isobars = false, smooth = false)
            appendLine(shot("ramp-wind-blocks-pc", 1600, 1000, 1f, 0))
            setLook(WxFill.PRESSURE, wind = true, level = 0, isobars = true, smooth = true)
            appendLine(shot("ramp-pressure-phone", 400, 860, 2f, 0))
            setLook(WxFill.PRECIP, wind = false, level = 0, isobars = false, smooth = true)
            appendLine(shot("ramp-precip-tablet", 1280, 800, 1.5f, 0))
            // the three kinds side by side on a made-up copy of the first map (the copy's maps hold rain only): showers
            // over the west, rain in the middle, snow (inclement at or below 0 °C) in the east
            w.frames.firstOrNull()?.let { fr -> maps[fr.id]?.let { mm ->
                val inc = com.bmscompanion.app.data.weather.WxType.INCLEMENT.code
                val type = mm.type.toMutableList(); val temp = mm.tempC.toMutableList(); val shower = MutableList(mm.cells) { 0 }
                for (r in 18 until 34) for (c in 0 until mm.cols) {
                    val i = r * mm.cols + c
                    when (c) {
                        in 6 until 20 -> shower[i] = 1
                        in 22 until 34 -> { type[i] = inc; temp[i] = 6f }
                        in 36 until 50 -> { type[i] = inc; temp[i] = -4f }
                    }
                }
                val demo = mm.copy(type = type, tempC = temp, shower = shower)
                appendLine(picture(out, "ramp-precip-kinds-pc", 1600, 1000, 1f) {
                    MissionWeatherPage(w, null, th, maps + (fr.id to demo), emptyMap(), 0, {}, pic, bm, {})
                })
            } }
            // the image is built once per map, fill and level, not per frame
            val ramp = java.util.IdentityHashMap<Any, Int>()
            maps.values.firstOrNull()?.let { mm ->
                for (f in WxFill.SHOWN) {
                    val c = runCatching { com.bmscompanion.app.ui.screens.editor.WxRamps.ink(mm, mm.cells / 2, f, 0, com.bmscompanion.app.ui.screens.editor.WxRamps.pressureRange(mm)) }.getOrNull()
                    ramp[f] = if (c == null) -1 else 1
                }
            }
            check(ramp.values.all { it == 1 }, "every fill colours a cell (${ramp.size} fills)")
            skyLines(w, th)
            MissionWxLook.smooth(sm)
            setLook(keep.first, keep.second, keep.third, iso)
        }

        // ---- 2. Deterministic
        if (detTwx == null) appendLine("FAIL: no Deterministic save (a .twx of model 2 beside a save) in ${dir.path}")
        else {
            val save = saveOf(detTwx)!!
            appendLine()
            appendLine("2. Deterministic: ${save.name}")
            val archive = runCatching { CampaignArchive.cached(save, names) }.getOrNull()
            val f = archive?.flights?.firstOrNull { it.hasPlayer } ?: archive?.flights?.firstOrNull()
            val flight = f?.let { (CampaignFiles.flight(CampaignFiles.context(), t.name, save.name, it.id.toString()) as? CampaignFiles.Answer.Ok)?.value }
            val (w, _) = MissionWeatherRoute.of(MissionMode.WDP, t, save, flight, f?.id?.toString(), archive)
            check(w.ok, "answer ok (${w.why ?: "no reason"})")
            check(w.model == 2 && w.frames.isEmpty(), "model 2 and no map (${w.model}, ${w.frames.size} frames)")
            check(w.twx?.changes?.size == 6, "the schedule's six changes: " + w.twx?.changes?.joinToString { "${CampaignFiles.clockShort(it.ms)} ${it.type}" })
            check(w.twx?.probabilities?.let { p -> p.size == 4 && kotlin.math.abs(p.sum() - 1f) < 0.01f } == true, "four chances summing to 1: ${w.twx?.probabilities}")
            check(w.condition in 1..4, "the type now: ${w.condition}")
            val bm = briefingOf(save, flight, t, archive, f?.id?.toString())
            val pic = pictureOf(flight, airports)
            fun shot(name: String, wDp: Int, hDp: Int, d: Float) = picture(out, name, wDp, hDp, d) {
                MissionWeatherPage(w, null, th, emptyMap(), emptyMap(), 0, {}, pic, bm, {})
            }
            appendLine(shot("deterministic-phone", 400, 860, 2f))
            appendLine(shot("deterministic-tablet", 1280, 800, 1.5f))
            appendLine(shot("deterministic-pc", 1600, 1000, 1f))
            skyLines(w, th)
        }

        // ---- 3. the routes, in-process, and nothing written by reading
        appendLine()
        appendLine("3. Routes")
        MissionWeatherRoute.forget()
        val r = Bridge.handle(ApiRequest("GET", "/api/weather/mission"))
        val body = r.body.toString(Charsets.UTF_8)
        val ans = runCatching { Bridge.json.decodeFromString(MissionWeather.serializer(), body) }.getOrNull()
        check(r.status == 200 && ans != null, "GET /api/weather/mission answers (${r.status}): ${ans?.save} model ${ans?.model}, ${ans?.frames?.size} frames${ans?.why?.let { ", why: $it" } ?: ""}")
        ans?.frames?.firstOrNull()?.let { fr ->
            val m = Bridge.handle(ApiRequest("GET", "/api/weather/mission/map", mapOf("frame" to fr.id)))
            val mm = runCatching { Bridge.json.decodeFromString(MissionWxMap.serializer(), m.body.toString(Charsets.UTF_8)) }.getOrNull()
            check(m.status == 200 && mm?.whole == true, "GET /api/weather/mission/map?frame=${fr.id} (${m.status}, ${m.body.size / 1024} KB)")
        }
        val bad = Bridge.handle(ApiRequest("GET", "/api/weather/mission/map", mapOf("frame" to "..\\..\\Falcon BMS.cfg")))
        check(bad.status == 404, "a frame that is not the mission's is refused (${bad.status})")
        val after = hashes(dir)
        val changed = (before.keys + after.keys).filter { before[it] != after[it] && it !in written }
        check(changed.isEmpty(), "reading wrote nothing in the campaign folder" + if (changed.isEmpty()) "" else ": $changed")

        // ---- 4. Sun & Moon of the printed mission, against what BMS printed (when it printed figures, not 00:00:00)
        appendLine()
        appendLine("4. Sun & Moon of the mission the route serves")
        if (ans == null || !ans.ok) appendLine("note no mission weather to work the sky out for")
        else {
            val tha = runBlocking { Repo.index().theaters.firstOrNull { it.id == ans.appTheater } }
            check(ans.twx?.year?.let { it > 0 } == true, "the .twx's date: ${ans.twx?.year}-${ans.twx?.month}-${ans.twx?.day}")
            val o = Bridge.currentBriefing()?.overview
            val printed = o?.sunrise?.trim()?.takeIf { it.isNotEmpty() }
            val printedZone = MissionSkies.printedZone(printed)
            // the theater's weather.ini where the copy has it (BMS's zone and point); a copy without its terrain folder
            // has none, and the page then falls back on the printed briefing's zone, else the longitude
            val iniThere = tha?.let { t ->
                Theaters.at(root).let { s -> s.byName(ans.theater) ?: s.all.firstOrNull { it.appId == t.id } }?.let { MissionWeatherRoute.sky(it) != null }
            } ?: (ans.sky != null)
            if (iniThere) check(ans.sky != null, "weather.ini read: ${ans.sky}")
            else {
                appendLine("note weather.ini not in this copy: the fallback (the printed briefing's zone, else the longitude) is used")
                check(ans.sky == null, "no weather.ini served (${ans.sky})")
            }
            skyLines(ans, tha, printedZone)
            val sky = MissionSkies.of(ans, tha, printedZone)
            check(sky != null, "Sun & Moon worked out" + if (ans.sky == null) " without weather.ini" else "")
            sky?.sm?.let { s ->
                val wantZone = ans.sky?.timeZone?.toDouble() ?: printedZone
                check(s.tz in -12.0..14.0 && (wantZone == null || s.tz == wantZone),
                    "the zone ${MissionSkies.zoneText(s.tz)}" + when {
                        ans.sky != null -> " is weather.ini's"
                        printedZone != null -> " is the printed briefing's (${MissionSkies.zoneText(printedZone)})"
                        else -> " is the longitude's (${"%.1f".format(s.lon)}°)"
                    })
                check(s.midnightSun != null || (s.sunrise != null && s.sunset != null && s.sunrise < s.sunset),
                    "times shown: sunrise ${MissionSkies.both(s, s.sunrise)}, sunset ${MissionSkies.both(s, s.sunset)}")
            }
            if (printed == null || printed.startsWith("00:00:00z")) appendLine("note the printed briefing's sunrise is ${printed ?: "missing"}: BMS printed no figure to compare")
            else if (sky?.bms == null && ans.sky == null) appendLine("note the printed briefing's $printed is not compared: BMS's figure needs weather.ini's point, not in this copy")
            else if (sky?.bms == null) appendLine("FAIL no BMS figure worked out to compare with the printed $printed")
            else {
                fun hms(h: Double): String { val s = kotlin.math.floor(h * 3600 + 1e-6).toInt(); return listOf(s / 3600, s % 3600 / 60, s % 60).joinToString(":") { it.toString().padStart(2, '0') } }
                val want = printed.substringBefore('z') + " / " + (o.sunset?.trim()?.substringBefore('z') ?: "?")
                val got = hms(sky.bms.first) + " / " + hms(sky.bms.second)
                check(got == want, "BMS's own figures for ${ans.save}: $got (printed $want)")
            }
        }
    }

    /** The Sun & Moon block's figures, and how far the almanac at the take-off is from BMS's own briefing figure. */
    private fun StringBuilder.skyLines(w: MissionWeather, th: com.bmscompanion.app.data.Theater?, printedZone: Double? = null) {
        val sky = runCatching { MissionSkies.of(w, th, printedZone) }.getOrNull()
        if (sky == null) { appendLine("note no Sun & Moon for ${w.save} (twx date ${w.twx?.year}-${w.twx?.month}-${w.twx?.day})"); return }
        val s = sky.sm
        appendLine(
            "ok   Sun & Moon for ${w.save}: ${s.date} ${MissionSkies.zoneText(s.tz)} at ${sky.where} (${"%.3f".format(s.lat)}, ${"%.3f".format(s.lon)}): " +
                "sunrise ${MissionSkies.both(s, s.sunrise)}, sunset ${MissionSkies.both(s, s.sunset)}, twilight ${MissionSkies.short(s.dawn)}-${MissionSkies.short(s.dusk)}L, " +
                "moon ${MissionSkies.short(s.moonrise)}/${MissionSkies.short(s.moonset)}L ${s.moon.phase.label} ${(s.moon.illumination * 100).toInt()}%",
        )
        sky.bms?.let { (r, st) ->
            val dr = s.sunrise?.let { (it - s.tz - r).let { d -> ((d % 24) + 36) % 24 - 12 } * 60 }
            val ds = s.sunset?.let { (it - s.tz - st).let { d -> ((d % 24) + 36) % 24 - 12 } * 60 }
            appendLine("     BMS's own figure at ${sky.bmsPoint}: ${Astro.hm(r)}z / ${Astro.hm(st)}z; the almanac at the field is ${dr?.let { "%+.1f".format(it) }} / ${ds?.let { "%+.1f".format(it) }} min from it")
        }
    }

    private fun StringBuilder.check(ok: Boolean, what: String) = appendLine((if (ok) "ok   " else "FAIL ") + what)

    private fun setLook(fill: WxFill, wind: Boolean, level: Int, isobars: Boolean, smooth: Boolean = MissionWxLook.smooth) {
        if (MissionWxLook.fill != fill) { MissionWxLook.fill(fill); if (MissionWxLook.fill != fill) MissionWxLook.fill(fill) }
        MissionWxLook.wind(wind); MissionWxLook.level(level); MissionWxLook.isobars(isobars); MissionWxLook.smooth(smooth)
    }

    /** The briefing's weather block as WDP mode's Populate would make it from the save (the page's "briefing says"). */
    private fun briefingOf(save: File, flight: CampFlight?, t: Theaters.Theater, archive: CampaignArchive.Save?, id: String?): Briefing? =
        flight?.let { fl -> SaveWeather.read(save, fl, t.appId, archive, id).table?.let { Briefing(weather = it) } }

    /** The flight's route and fields, as the page draws the merged mission. */
    private fun pictureOf(flight: CampFlight?, airports: List<com.bmscompanion.app.data.Airport>): WxPicture {
        flight ?: return WxPicture()
        fun near(x: Double?, y: Double?) = if (x == null || y == null) null else
            airports.minByOrNull { (it.x - x) * (it.x - x) + (it.y - y) * (it.y - y) }?.takeIf { (it.x - x) * (it.x - x) + (it.y - y) * (it.y - y) < 18_000.0 * 18_000.0 }
        val home = near(flight.home?.x, flight.home?.y)
        val land = near(flight.landing?.x, flight.landing?.y)
        return WxPicture(
            route = flight.route.filter { it.x != 0.0 || it.y != 0.0 }.mapIndexed { i, wp ->
                WxRoutePt(wp.x, wp.y, i + 1, target = wp.action == 17 || wp.action == 16, land = wp.action == 7, refuel = wp.action == 4)
            },
            fields = listOfNotNull(home?.let { it to (if (it == land) "HOME" else "DEP") }, land?.takeIf { it != home }?.let { it to "ARR" }),
        )
    }

    private fun picture(out: File, name: String, wDp: Int, hDp: Int, density: Float, content: @androidx.compose.runtime.Composable () -> Unit): String {
        val scene = ImageComposeScene((wDp * density).toInt(), (hDp * density).toInt(), Density(density)) {
            CompositionLocalProvider(LocalConfiguration provides Configuration(wDp, hDp)) {
                Box(Modifier.fillMaxSize().background(Hud.Bg)) { content() }
            }
        }
        return try {
            var t = 0L
            repeat(40) { scene.render(t); t += 50_000_000; Thread.sleep(40) }   // map tiles load asynchronously
            File(out, "wxview-$name.png").writeBytes(scene.render(t).encodeToData(EncodedImageFormat.PNG)!!.bytes)
            "ok   picture $name (${wDp}x$hDp dp)"
        } catch (e: Throwable) {
            "FAIL picture $name: ${e::class.simpleName}: ${e.message}"
        } finally {
            scene.close()
        }
    }

    /** Every file of [dir] and its update folder, by SHA-256. */
    private fun hashes(dir: File): Map<String, String> {
        val md = { f: File -> MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) } }
        val out = HashMap<String, String>()
        dir.listFiles { f -> f.isFile && (f.name.endsWith(".twx", true) || f.name.endsWith(".fmap", true)) }.orEmpty().forEach { out[it.name] = md(it) }
        File(dir, "WeatherMapsUpdates").listFiles()?.filter { it.isFile }?.forEach { out["WeatherMapsUpdates/" + it.name] = md(it) }
        return out
    }
}
