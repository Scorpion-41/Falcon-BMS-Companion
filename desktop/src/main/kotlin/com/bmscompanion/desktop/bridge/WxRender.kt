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
import com.bmscompanion.app.data.weather.WxGenParams
import com.bmscompanion.app.data.weather.WxGrid
import com.bmscompanion.app.data.weather.WxModel
import com.bmscompanion.app.data.weather.WxType
import com.bmscompanion.app.data.mission.WeatherState
import com.bmscompanion.app.data.mission.WxTheater
import com.bmscompanion.app.ui.screens.editor.WeatherGenBody
import com.bmscompanion.app.ui.screens.editor.WeatherPage
import com.bmscompanion.app.ui.screens.editor.WxDisplay
import com.bmscompanion.app.ui.screens.editor.WxGenLook
import com.bmscompanion.app.ui.screens.editor.WxGenState
import com.bmscompanion.app.ui.screens.editor.WxOverlay
import com.bmscompanion.app.ui.theme.Hud
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/**
 * `--wxrender <out folder>` — the Weather page drawn headless, as PNGs: the whole page (theater selector, the undo
 * line for BMS's own maps, the generator) at a phone's, a tablet's and a PC's width from a made-up PC answer, then the
 * generator on its own stepped through time; with a report of what each grid holds.
 *
 * It is the check that the page **draws** and that what it draws is weather: the report counts the types across the
 * theater and the spread of pressure, temperature and wind in each picture, and how many cells changed type from
 * one time step to the next — a map that did not vary across the theater, or did not move with the clock, shows up
 * there as a zero before anyone opens a picture. The pictures are for looking at: phone layout usable, the legend
 * where it should be, barbs pointing into the wind.
 *
 * `BMSC_WX_THEATER` picks the bundled theater (default `korea-kto`); `BMSC_WX_ONLY` draws one picture. Writes only PNGs and the report, and only
 * under the temp folder.
 *
 * It also checks the two things a pilot does with the page that a picture cannot show: **picking several** (Shift- or
 * Ctrl-click, Select several — one change reaching every picked region, cells summed up and covered by a region) and
 * **switching theater** (each theater opens with its own settings, kept apart; two pictures of the same page before
 * and after the switch). With `BMSC_WX_DATA=<a copy of a Data folder under the temp folder>` it also writes a
 * generated map and a series into that copy and reads back the settings the PC keeps with them.
 */
object WxRender {
    fun run(out: File): String = buildString {
        val temp = File(System.getProperty("java.io.tmpdir")).canonicalPath.trimEnd('\\', '/')
        if (!out.canonicalPath.startsWith(temp + File.separator, ignoreCase = true)) {
            appendLine("REFUSED: $out is not under the temp folder ($temp).")
            return@buildString
        }
        out.mkdirs()
        val theaterId = System.getenv("BMSC_WX_THEATER") ?: "korea-kto"
        // BMSC_WX_ONLY=<picture> (e.g. phone-panels) draws that one picture, for a quick look
        val only = System.getenv("BMSC_WX_ONLY")
        val th = runBlocking { Repo.index().theaters.firstOrNull { it.id == theaterId } }
        if (th == null) { appendLine("FAIL: no theater $theaterId in the bundled data"); return@buildString }
        val airports = runBlocking { Repo.airportSet(th.airportSet).airports }
        appendLine("Weather generator page, headless — ${th.name}, ${airports.size} airfields")
        appendLine()

        // the page's view options are the pilot's, kept in the prefs: noted here and put back at the end
        val keep = listOf(WxGenLook.display, WxGenLook.overlay, WxGenLook.windLevel, WxGenLook.opacity, WxGenLook.mb, WxGenLook.grid, WxGenLook.bms)
        val savedParams = Repo.getString(WxGenState.KEY)

        fun stats(label: String, g: WxGrid) {
            val types = WxType.entries.associateWith { t -> g.cells.count { it.type == t } }
            val p = g.cells.map { it.pressureInHg }
            val t = g.cells.map { it.tempC }
            val w = g.cells.map { it.windKt[0] }
            val d = g.cells.map { it.windDeg[0] }
            appendLine(
                "  $label: " + types.entries.joinToString(" ") { "${it.key.label}=${it.value}" } +
                    "  pressure ${"%.2f".format(p.min())}..${"%.2f".format(p.max())} inHg" +
                    "  temp ${"%.1f".format(t.min())}..${"%.1f".format(t.max())} C" +
                    "  sfc wind ${"%.0f".format(w.min())}..${"%.0f".format(w.max())} kt from ${"%.0f".format(d.min())}..${"%.0f".format(d.max())}" +
                    "  distinct pressures ${p.distinct().size}",
            )
        }

        // What the page is drawn from when it is drawn whole: the PC's answer (null = not connected), which theater is
        // chosen in it, and why there is nowhere to save.
        class Page(val state: WeatherState?, val online: Boolean, val noSave: String? = null)

        fun shot(name: String, wDp: Int, hDp: Int, density: Float, st: WxGenState, wx: WxTheater? = null, page: Page? = null) {
            st.grid = WxModel.grid(st.params)
            if (only != null && name != only) return
            val scene = ImageComposeScene((wDp * density).toInt(), (hDp * density).toInt(), Density(density)) {
                CompositionLocalProvider(LocalConfiguration provides Configuration(wDp, hDp)) {
                    Box(Modifier.fillMaxSize().background(Hud.Bg)) {
                        if (page != null) {
                            val s = page.state
                            val chosen = s?.theaters?.firstOrNull { it.id == s.current } ?: s?.theaters?.firstOrNull()
                            WeatherPage(
                                s = s, theater = chosen, mapTheater = th, online = page.online, noSave = page.noSave,
                                busy = false, gen = st, onPick = {}, onBusy = {}, onState = {}, reload = {},
                            )
                        } else {
                            WeatherGenBody(th, wx, online = wx != null, busy = false, error = null, onBusy = {}, onState = {}, reload = {}, st = st)
                        }
                    }
                }
            }
            try {
                var t = 0L
                repeat(40) { scene.render(t); t += 50_000_000; Thread.sleep(40) }   // map tiles load asynchronously
                File(out, "wx-$name.png").writeBytes(scene.render(t).encodeToData(EncodedImageFormat.PNG)!!.bytes)
                appendLine("ok   $name (${wDp}x$hDp dp) ${st.params.current.label}")
                stats(name, st.grid!!)
            } catch (e: Exception) {
                appendLine("FAIL $name: ${e::class.simpleName}: ${e.message}")
            } finally {
                scene.close()
            }
        }

        val osan = airports.firstOrNull { it.name.contains("Osan", true) } ?: airports.firstOrNull()
        fun state(p: WxGenParams = WxGenParams(seed = 1234.0)) = WxGenState(p).also {
            it.selected = 30 to 26
            it.airport = osan
        }

        // A PC's answer as the page gets it: three theaters, Korea running and backed up. In `changed` two of BMS's own
        // four maps are not BMS's own (an earlier version of the app edited them), so the undo line shows; in `clean`
        // they are, and it does not. In `update` one differs although the app did not write it (a BMS update): the line
        // offers a new copy instead of Restore. Korea reports BMS's 741 hourly update maps, for the series note.
        fun pcAnswer(edited: Set<String>, differs: Set<String> = edited) = WeatherState(
            available = true,
            current = "korea-the-base-theater",
            theaters = listOf(
                WxTheater(
                    id = "korea-the-base-theater", name = "Korea (the base theater)", backedUp = true,
                    models = WeatherStore.Model.entries.map { m ->
                        com.bmscompanion.app.data.mission.WxModel(
                            id = m.id, name = m.label, readable = true, edited = m.id in edited, differs = m.id in differs,
                            copiedAt = 1_790_000_000_000L,
                        )
                    },
                    added = listOf("BMSC Frontal day.fmap"),
                    gridFrom = "SUNNY.fmap",
                    bmsUpdates = 741,
                    bmsUpdatesFirst = com.bmscompanion.app.data.weather.CampaignTime(1, 1, 0),
                    bmsUpdatesLast = com.bmscompanion.app.data.weather.CampaignTime(31, 21, 0),
                ),
                WxTheater(id = "balkans", name = "Balkans"),
                WxTheater(id = "israel", name = "Israel"),
                WxTheater(id = "hellas", name = "Hellas", stockMaps = false),
            ),
        )
        val changed = Page(pcAnswer(setOf("fair", "poor")), online = true)
        val clean = Page(pcAnswer(emptySet()), online = true)
        val update = Page(pcAnswer(setOf("fair"), setOf("fair", "sunny")), online = true)

        // the three widths, the whole page, WeatherGen's defaults, coloured by type with wind barbs
        WxGenLook.display(WxDisplay.TYPE); WxGenLook.overlay(WxOverlay.WIND); WxGenLook.windLevel(0)
        WxGenLook.opacity(0.45f); WxGenLook.mb(false); WxGenLook.grid(false); WxGenLook.bms(false)
        shot("phone", 400, 1500, 2f, state(), page = changed)
        shot("tablet", 800, 1280, 1.5f, state(), page = changed)
        shot("pc", 1400, 900, 1f, state(), page = changed)
        // BMS's own maps untouched: no undo line
        shot("pc-clean", 1400, 900, 1f, state(), page = clean)
        // one map this app wrote (Restore) and one a BMS update changed (a new copy), on a phone where both lines wrap
        shot("phone-bmsupdate", 400, 1500, 2f, state(), page = update)
        // no PC: the theater the app is set to, and a preview
        shot("phone-offline", 400, 900, 2f, state(), page = Page(null, online = false))

        // the clock: three steps of three hours, the pattern moving 135° at 20 kt as it evolves
        appendLine()
        var p = WxGenParams(seed = 1234.0, movement = WxGenParams().movement.copy(stepMin = 180.0))
        var last: WxGrid? = null
        for (k in 0..3) {
            val st = state(p)
            shot("time-$k", 1400, 900, 1f, st)
            val g = st.grid!!
            last?.let { prev ->
                val changed = g.cells.indices.count { g.cells[it].type != prev.cells[it].type }
                val dp = g.cells.indices.sumOf { kotlin.math.abs(g.cells[it].pressureInHg - prev.cells[it].pressureInHg) } / g.cells.size
                appendLine("  step ${k - 1} -> $k: $changed of ${g.cells.size} cells changed type, mean pressure change ${"%.3f".format(dp)} inHg")
            }
            last = g
            p = WxModel.step(p, 1)
        }

        // other seeds: the same page, a different weather system
        appendLine()
        for (seed in listOf(42.0, 3100.0)) shot("seed-${seed.toInt()}", 1400, 900, 1f, state(WxGenParams(seed = seed)))

        // the other displays and overlays, with an override region being edited and the light BMS 2D map
        appendLine()
        WxGenLook.display(WxDisplay.PRESSURE); WxGenLook.overlay(WxOverlay.COVER); WxGenLook.bms(true)
        shot("pressure-cover", 1400, 900, 1f, state().also { it.addOverride(18.0, 20.0) })
        WxGenLook.display(WxDisplay.TEMPERATURE); WxGenLook.overlay(WxOverlay.BASE); WxGenLook.bms(false); WxGenLook.grid(true)
        shot("temp-base", 1400, 900, 1f, state())
        WxGenLook.display(WxDisplay.TYPE); WxGenLook.overlay(WxOverlay.WIND); WxGenLook.windLevel(7); WxGenLook.grid(false)
        shot("wind-30k-phone", 400, 900, 2f, state())
        // a preset with its storm cells, at the hour they peak
        val storms = com.bmscompanion.app.ui.screens.editor.WX_GEN_PRESETS.first { it.name == "Afternoon storms" }
            .make(WxGenParams(seed = 1234.0)).let { WxModel.jumpToTime(it, it.current.copy(hour = 15)) }
        WxGenLook.overlay(WxOverlay.NONE)
        shot("preset-storms-1500", 1400, 900, 1f, state(storms))

        // every panel open at a phone's width, one region being edited with its fade times: the whole stack, to read
        val folds = listOf("inspect", "display", "presets", "params", "weights", "overrides", "atmosphere", "advanced", "time", "save")
        val foldsWere = folds.associateWith { Repo.getInt("open_wx_$it", -1) }
        folds.forEach { Repo.putInt("open_wx_$it", 1) }
        WxGenLook.overlay(WxOverlay.WIND); WxGenLook.windLevel(0)
        shot("phone-panels", 400, 6400, 1f, state().also { s -> s.addOverride(20.0, 22.0); s.editOverride(0) { it.copy(animate = true, tempC = 8.0) } },
            // a theater as the PC would report it after a map and a series: the Save panel as a connected client sees it
            com.bmscompanion.app.data.mission.WxTheater(
                id = th.id, name = th.name, backedUp = true,
                added = listOf("BMSC Frontal day.fmap", "WeatherMapsUpdates/99900.fmap"), replaced = listOf("WeatherMapsUpdates/10500.fmap"),
            ),
        )
        // a PC with no weather maps to save to: the page says why in the Save panel, at the foot of the stack
        shot("phone-nosave", 400, 5200, 1f, state(), page = Page(
            WeatherState(available = false), online = true,
            noSave = "No theater in this Falcon BMS install keeps weather maps of its own. The preview works without it.",
        ))
        // ---- several regions and several cells picked at once (Shift/Ctrl-click, or Select several on a touch screen)
        appendLine()
        appendLine("Picking several:")
        var failures = 0
        fun check(what: String, ok: Boolean, detail: String = "") {
            appendLine((if (ok) "ok   " else "FAIL ") + what + if (detail.isEmpty()) "" else "  — $detail")
            if (!ok) failures++
        }
        val multi = state().also { s ->
            s.addOverride(14.0, 18.0)
            s.addOverride(30.0, 34.0)
            s.addOverride(44.0, 20.0)
            // three regions that disagree: radius, type, and one without a temperature
            s.editOverride(0) { it.copy(radius = 6.0, type = com.bmscompanion.app.data.weather.WxType.INCLEMENT, tempC = 8.0) }
            s.editOverride(1) { it.copy(radius = 10.0, type = com.bmscompanion.app.data.weather.WxType.POOR) }
            s.editOverride(2) { it.copy(radius = 6.0, type = com.bmscompanion.app.data.weather.WxType.INCLEMENT, tempC = 12.0) }
            s.click(region = 0, cell = null, additive = false)
            check("a plain click picks one region", s.regions == setOf(0) && s.editing == 0, "${s.regions}")
            s.click(region = 2, cell = null, additive = true)
            check("Shift/Ctrl-click adds a second", s.regions == setOf(0, 2) && s.editing == null, "${s.regions}")
            s.click(region = 1, cell = null, additive = true)
            s.click(region = 1, cell = null, additive = true)
            check("Shift/Ctrl-click on a picked one takes it away", s.regions == setOf(0, 2), "${s.regions}")
            s.editOverrides(s.regions) { it.copy(strength = 0.6) }
            check(
                "one change goes to every picked region, and only to them",
                s.params.overrides.map { it.strength } == listOf(0.6, 1.0, 0.6),
                "${s.params.overrides.map { it.strength }}",
            )
            s.click(region = null, cell = 20 to 20, additive = true)
            s.click(region = null, cell = 21 to 20, additive = true)
            s.click(region = null, cell = 22 to 21, additive = true)
            check("cells are picked the same way", s.cells == setOf(20 to 20, 21 to 20, 22 to 21) && s.selected == (22 to 21), "${s.cells}")
            check("picking cells leaves the picked regions alone", s.regions == setOf(0, 2))
        }
        multi.grid = WxModel.grid(multi.params)
        folds.forEach { Repo.putInt("open_wx_$it", 1) }
        shot("multi-phone-panels", 400, 5600, 1f, multi)
        shot("multi-pc", 1400, 900, 1f, multi)
        // a plain click on a cell picks it alone and lets the regions go
        multi.click(region = null, cell = 5 to 5, additive = false)
        check("a plain click on a cell picks it alone", multi.cells == setOf(5 to 5) && multi.regions.isEmpty())
        // a region over several cells, to give them one weather
        multi.cells = setOf(40 to 40, 42 to 40, 41 to 43)
        val covered = multi.coverCells()
        val cover = covered?.let { multi.params.overrides.getOrNull(it) }
        check(
            "Cover them with a region: one region over the three cells, picked for editing",
            cover != null && multi.editing == covered && multi.cells.isEmpty() &&
                listOf(40 to 40, 42 to 40, 41 to 43).all { (x, y) -> kotlin.math.hypot(x - cover.x, y - cover.y) <= cover.radius },
            cover?.let { "centre ${it.x},${it.y} radius ${it.radius} type ${it.type}" } ?: "none",
        )
        multi.regions = setOf(0, 2)
        multi.removeOverrides(setOf(0))
        check("removing a region renumbers the rest of the selection", multi.regions == setOf(1), "${multi.regions}")
        foldsWere.forEach { (k, v) -> if (v < 0) Repo.putString("open_wx_$k", null) else Repo.putInt("open_wx_$k", v) }

        // ---- each theater its own weather: switching theater switches the settings, so the grid changes with the map
        appendLine()
        appendLine("Switching theater:")
        val keyA = "wxrender-test-a"
        val keyB = "wxrender-test-b"
        fun forget() { Repo.putString(WxGenState.keyFor(keyA), null); Repo.putString(WxGenState.keyFor(keyB), null) }
        forget()
        val legacy = Repo.getString(WxGenState.KEY)
        Repo.putString(WxGenState.KEY, null)
        run {
            val a = WxGenState.open(keyA)
            val b = WxGenState.open(keyB)
            check(
                "two theaters with nothing kept open with seeds of their own",
                a.origin is com.bmscompanion.app.ui.screens.editor.WxOrigin.Fresh && b.origin is com.bmscompanion.app.ui.screens.editor.WxOrigin.Fresh &&
                    a.params.seed != b.params.seed,
                "${a.params.seed} ${b.params.seed}",
            )
            val ga = WxModel.grid(a.params)
            val gb = WxModel.grid(b.params)
            val differ = ga.cells.indices.count { ga.cells[it].type != gb.cells[it].type || kotlin.math.abs(ga.cells[it].windDeg[0] - gb.cells[it].windDeg[0]) > 5 }
            check("… so their grids differ", differ > ga.cells.size / 4, "$differ of ${ga.cells.size} cells differ in type or wind")
            a.edit { it.copy(seed = 77.0, pressureVariance = 2.0) }
            a.save()
            val a2 = WxGenState.open(keyA)
            check("a theater's own settings come back when it is opened again", a2.params == a.params && a2.origin == com.bmscompanion.app.ui.screens.editor.WxOrigin.Own)
            val b2 = WxGenState.open(keyB)
            check("… and the other theater's are untouched", b2.params == b.params, "${b2.params.seed}")
            // the settings every theater shared before 1.3.8 kept them apart go to the first theater opened, once
            Repo.putString(WxGenState.KEY, Repo.json.encodeToString(WxGenParams.serializer(), WxGenParams(seed = 4242.0)))
            val c = WxGenState.open("wxrender-test-c")
            val d = WxGenState.open("wxrender-test-d")
            check(
                "the old shared settings are taken over by one theater only",
                c.params.seed == 4242.0 && d.params.seed != 4242.0 && Repo.getString(WxGenState.KEY) == null,
                "${c.params.seed} ${d.params.seed}",
            )
            Repo.putString(WxGenState.keyFor("wxrender-test-c"), null)
        }
        forget()
        Repo.putString(WxGenState.KEY, legacy)

        // the whole page for two theaters as a PC reports them, each with its own state: the picture a pilot sees
        // before and after choosing the other theater in the picker
        val bal = runBlocking { Repo.index().theaters.firstOrNull { it.id == "balkans" } }
        if (bal != null) {
            fun pageFor(id: String) = WeatherState(
                available = true, current = "korea-kto",
                theaters = listOf(
                    WxTheater(id = "korea-kto", name = "Korea KTO", backedUp = true, added = listOf("BMSC Frontal day.fmap")),
                    WxTheater(id = "balkans", name = "Balkans", backedUp = true),
                ),
            ).let { s -> s to s.theaters.first { it.id == id } }
            WxGenLook.display(WxDisplay.TYPE); WxGenLook.overlay(WxOverlay.WIND); WxGenLook.opacity(0.45f)
            // the two theaters at a PC width, and Balkans at a phone width, where the line under the picker wraps
            class Pic(val id: String, val map: com.bmscompanion.app.data.Theater, val name: String, val w: Int, val h: Int, val d: Float)
            val pics = listOf(
                Pic("korea-kto", th, "switch-korea-kto", 1400, 900, 1f),
                Pic("balkans", bal, "switch-balkans", 1400, 900, 1f),
                Pic("balkans", bal, "switch-balkans-phone", 400, 900, 2f),
            )
            for (pic in pics) {
                val id = pic.id
                val map = pic.map
                val st = WxGenState(WxGenParams(seed = WxGenState.seedFor(id).toDouble()), id)
                    .also { it.origin = com.bmscompanion.app.ui.screens.editor.WxOrigin.Fresh(WxGenState.seedFor(id)) }
                st.grid = WxModel.grid(st.params)
                val (s, chosen) = pageFor(id)
                if (only != null && pic.name != only) continue
                val scene = ImageComposeScene((pic.w * pic.d).toInt(), (pic.h * pic.d).toInt(), Density(pic.d)) {
                    CompositionLocalProvider(LocalConfiguration provides Configuration(pic.w, pic.h)) {
                        Box(Modifier.fillMaxSize().background(Hud.Bg)) {
                            WeatherPage(
                                s = s, theater = chosen, mapTheater = map, online = true, noSave = null,
                                busy = false, gen = st, onPick = {}, onBusy = {}, onState = {}, reload = {},
                            )
                        }
                    }
                }
                try {
                    var t = 0L
                    repeat(40) { scene.render(t); t += 50_000_000; Thread.sleep(40) }
                    File(out, "wx-${pic.name}.png").writeBytes(scene.render(t).encodeToData(EncodedImageFormat.PNG)!!.bytes)
                    appendLine("ok   ${pic.name} (${pic.w}x${pic.h} dp) seed ${st.params.seed.toInt()}")
                    stats(pic.name, st.grid!!)
                } catch (e: Exception) {
                    appendLine("FAIL ${pic.name}: ${e::class.simpleName}: ${e.message}"); failures++
                } finally {
                    scene.close()
                }
            }
        }

        // ---- the PC keeps the settings each generated map was made from, and hands them back (BMSC_WX_DATA=<a copy
        // of a Data folder under the temp folder>; this writes into that copy only)
        System.getenv("BMSC_WX_DATA")?.let { File(it) }?.let { data ->
            appendLine()
            appendLine("Settings kept with generated maps (${data.path}):")
            storeCheck(data, { w, ok, d -> check(w, ok, d) }, { appendLine(it) })
        }

        appendLine()
        appendLine(if (failures == 0) "PASS — picking and switching" else "FAIL — $failures check(s)")

        // put the pilot's view back as it was
        WxGenLook.display(keep[0] as WxDisplay); WxGenLook.overlay(keep[1] as WxOverlay); WxGenLook.windLevel(keep[2] as Int)
        WxGenLook.opacity(keep[3] as Float); WxGenLook.mb(keep[4] as Boolean); WxGenLook.grid(keep[5] as Boolean); WxGenLook.bms(keep[6] as Boolean)
        Repo.putString(WxGenState.KEY, savedParams)
    }

    /**
     * A generated map and a series written into a copy of a Data folder, their settings read back the way the page
     * asks for them, and gone again with the maps. Refuses anything that is not a copy under the temp folder.
     */
    private fun storeCheck(data: File, check: (String, Boolean, String) -> Unit, say: (String) -> Unit) {
        val temp = File(System.getProperty("java.io.tmpdir")).canonicalPath.trimEnd('\\', '/')
        var up: File? = data.canonicalFile
        repeat(6) {
            if (up != null && (File(up, "Bin/x64/Falcon BMS.exe").isFile || File(up, "Falcon BMS.exe").isFile)) {
                say("REFUSED: $data is inside a real Falcon BMS install."); return
            }
            up = up?.parentFile
        }
        if (!data.canonicalPath.startsWith(temp + File.separator, ignoreCase = true)) { say("REFUSED: $data is not under the temp folder."); return }
        val store = WeatherStore(BmsInstall(), dataOverride = data)
        val th = store.state().theaters.firstOrNull() ?: run { check("a theater with weather maps in the copy", false, data.path); return }
        say("  theater ${th.name} (${th.id})")
        check("nothing is kept before anything is written", store.savedParams(th.id, null) == null, "")
        val pA = WxGenParams(seed = 321.0, pressureVariance = 1.7)
        var s = store.writeGenerated(th.id, "Render A", pA)
        check("a generated map is written", s.error == null, s.error ?: "")
        val settings = File(File(File(th.dir), WeatherStore.BACKUP_DIR), WeatherStore.SETTINGS_DIR)
        check("its settings are kept in the backup folder", File(settings, "BMSC Render A.json").isFile, settings.path)
        val gotA = store.savedParams(th.id, "Render A")
        check("… and come back exactly", gotA?.params == pA && gotA?.name == "BMSC Render A", gotA?.name ?: "none")
        Thread.sleep(1100)     // file times are to the second on some drives: the newest must be newer
        val pB = WxGenParams(seed = 654.0)
        val from = com.bmscompanion.app.data.weather.CampaignTime(1, 6, 0)
        s = store.writeSeries(th.id, "Render S", pB, from, com.bmscompanion.app.data.weather.CampaignTime(1, 8, 0), 60)
        check("a series is written", s.error == null, s.error ?: "")
        val gotS = store.savedParams(th.id, "BMSC Render S.fmap")
        check("a series keeps the settings of its first map, at its start", gotS?.params == pB.copy(current = from), "${gotS?.params?.current?.label}")
        check("with no name, the map written last", store.savedParams(th.id, null)?.name == "BMSC Render S", store.savedParams(th.id, null)?.name ?: "none")
        val json = store.savedParamsJson(th.id, null)
        val back = json?.let { runCatching { Repo.json.decodeFromString(com.bmscompanion.app.ui.screens.editor.WxGenSaved.serializer(), it) }.getOrNull() }
        check("the JSON the route answers with reads back in the app", back?.params == gotS?.params, "${json?.length} bytes")
        check("a name that is not a plain name finds nothing", store.savedParams(th.id, "../Render A") == null, "")
        s = store.removeGenerated(th.id, "Render A")
        check("removing a map removes its settings", s.error == null && !File(settings, "BMSC Render A.json").exists(), s.error ?: "")
        check("… and they are not offered any more", store.savedParams(th.id, "Render A") == null, "")
        store.restoreSeries(th.id)
        s = store.removeGenerated(th.id, null)
        check("everything removed, nothing is offered", s.error == null && store.savedParams(th.id, null) == null, s.error ?: "")
        check("the README says what the settings folder is", File(File(File(th.dir), WeatherStore.BACKUP_DIR), "README.txt").readText().contains(WeatherStore.SETTINGS_DIR), "")
    }
}
