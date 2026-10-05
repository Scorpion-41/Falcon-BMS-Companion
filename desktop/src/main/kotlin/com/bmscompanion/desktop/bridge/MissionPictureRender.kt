package com.bmscompanion.desktop.bridge

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Density
import androidx.navigation.compose.rememberNavController
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.mission.BridgeInfo
import com.bmscompanion.app.data.mission.LinkState
import com.bmscompanion.app.data.mission.MissionData
import com.bmscompanion.app.data.mission.MissionLink
import com.bmscompanion.app.ui.components.MapState
import com.bmscompanion.app.ui.screens.mission.MissionEnv
import com.bmscompanion.app.ui.screens.mission.MissionMapPane
import com.bmscompanion.app.ui.screens.wdp.PlannerMissionState
import com.bmscompanion.app.ui.screens.wdp.WdpDialogs
import com.bmscompanion.app.ui.screens.wdp.WdpMapIntel
import com.bmscompanion.app.ui.screens.wdp.WdpMapPage
import com.bmscompanion.app.ui.screens.wdp.WdpMapView
import com.bmscompanion.app.ui.screens.wdp.WdpSession
import com.bmscompanion.app.ui.screens.wdp.plannerMission
import com.bmscompanion.app.ui.screens.wdp.plannerTheater
import com.bmscompanion.app.ui.theme.BmsTheme
import com.bmscompanion.app.ui.theme.Hud
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/**
 * `--mappicturerender <a copy of the BMS folder> <out folder>` — one mission picture for every map, looked at side by
 * side (docs/DATA-STORES.md, "One mission picture"): the copy's printed flight drawn by the Mission section's map in
 * EZBoards mode after PRINT (`/api/mission` with its ground picture, [MissionGrounds]) and by the Planner's Map page
 * with that flight open, each at 1400 x 900 dp with every layer at its default, into `<out>/picture-mission.png` and
 * `<out>/picture-planner.png`. The Mission map is also timed while panning (a live tick four times a second), with the
 * mission's threats and again with every spotted site (`picture-mission-allsites.png`, the 1.3.8 test builds' picture
 * less their ground units) — the frame times go in the report. Read only: the settings' BMS folder is the copy, the
 * cartridge is held in memory.
 */
internal object MissionPictureRender {
    private val client = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true; explicitNulls = false }

    fun run(root: File, out: File): String = buildString {
        out.mkdirs()
        var fails = 0
        fun check(what: String, ok: Boolean, detail: String = "") {
            if (!ok) fails++
            appendLine((if (ok) "ok    " else "FAIL  ") + what + if (detail.isNotEmpty()) " — $detail" else "")
        }
        val set = Theaters.at(root)
        val bf = Theaters.resolveFile(root, "User\\Briefings\\briefing.txt")
        val printed = bf?.let { f -> runCatching { BriefingParser.parse(f.readBytes().toString(Charsets.UTF_8).removePrefix("﻿")) }.getOrNull() }
        val ctx = CampaignFiles.Context(set, null, printed, bf?.lastModified() ?: 0L)
        val briefed = CampaignFiles.list(ctx, all = false).theaters.flatMap { th -> th.files.filter { it.briefed }.map { th to it } }.firstOrNull()
        val cartridge = File(root, "User/Config").listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".ini", true) && runCatching { it.readText(Charsets.ISO_8859_1).contains("[STPT]") }.getOrDefault(false) }
            .maxByOrNull { it.lastModified() }
        if (briefed == null || cartridge == null || printed == null) { check("a briefing, the save holding its flight and a cartridge", false); return@buildString }
        val (th, file) = briefed
        val row = (CampaignFiles.ato(ctx, th.name, file.name) as? CampaignFiles.Answer.Ok)?.value?.packages?.flatMap { it.flights }?.firstOrNull { it.briefed }
        val flight = row?.let { (CampaignFiles.flight(ctx, th.name, file.name, it.id) as? CampaignFiles.Answer.Ok)?.value }
        val theater = runBlocking { plannerTheater(Repo.index().theaters, th.name) }
        check("the printed flight in the save", flight != null && theater != null, "${row?.callsign} (${th.name}, ${file.name})")
        if (flight == null || theater == null) return@buildString
        val airports = runBlocking { Repo.airportSet(theater.airportSet) }

        // ---- the Mission section's map, EZBoards mode after PRINT, from the PC's own /api/mission
        Bridge.startForCheck()
        MissionLink.useThisPc()
        val m = client.decodeFromString(MissionData.serializer(), Bridge.handle(ApiRequest("GET", "/api/mission", emptyMap(), ByteArray(0))).body.toString(Charsets.UTF_8))
        val info = client.decodeFromString(BridgeInfo.serializer(), Bridge.handle(ApiRequest("GET", "/api/info", emptyMap(), ByteArray(0))).body.toString(Charsets.UTF_8))
        check("EZBoards mode's /api/mission carries the ground picture", m.ground != null,
            "${m.ground?.airDefences?.size} sites, ${m.ground?.units?.size} units, ${m.ground?.packageRoutes?.size} package routes, ${m.tracks.size} tracks")
        val pts = flight.route.filter { it.x != 0.0 || it.y != 0.0 }
        val cx = (pts.maxOf { it.x } + pts.minOf { it.x }) / 2
        val cy = (pts.maxOf { it.y } + pts.minOf { it.y }) / 2
        setFlow("_state", LinkState.Online("test"))
        setFlow("_info", info)
        setFlow("_live", null)
        setFlow("_contacts", null)
        setFlow("_mission", m)
        runCatching {
            val t = set!!.all.first { it.name == th.name }
            val names = CampaignArchive.names(set, t)
            val save = CampaignArchive.cached(File(set.campaignDir(t), m.ground?.save?.ifEmpty { null } ?: file.name), names)
            val objs = CampaignArchive.startFile(save)?.let { CampaignArchive.objectives(it) }
            val units = (MapIntel.build(set, t, save.file, null, null) as? CampaignFiles.Answer.Ok)?.value?.units.orEmpty()
            val places = CampaignBriefing.Places(save, names, objs)
            BriefedThreats.place(com.bmscompanion.app.data.mission.MissionPicture.briefedThreats(printed), units, places).forEach { p ->
                appendLine("      briefed \"${p.row.system} ${p.row.where}\" → ${p.how}: ${p.site.system} ${p.site.name ?: ""} at ${p.site.x.toInt()}, ${p.site.y.toInt()}")
                if (p.how == "words") units.filter { it.side != "friendly" }.sortedBy { kotlin.math.hypot(it.x - p.site.x, it.y - p.site.y) }.take(3).forEach { u ->
                    appendLine("        near it: ${u.name} (${u.kind}, ${u.system}${u.shorad?.let { " + $it" } ?: ""}, ${u.side}) ${(kotlin.math.hypot(u.x - p.site.x, u.y - p.site.y) / 6076.12).toInt()} nm" +
                        ", worded \"${places.words(kotlin.math.floor(u.y / 3279.98).toInt(), kotlin.math.floor(u.x / 3279.98).toInt())}\"")
                }
            }
        }.onFailure { appendLine("      (the briefed rows could not be listed: ${it.message})") }
        check("…only the mission's threats (${m.ground?.threatsFrom})",m.ground?.units.isNullOrEmpty() && m.ground?.packageRoutes.isNullOrEmpty(),
            m.ground?.airDefences?.joinToString { it.system }.orEmpty())
        // the theater's other airfields are the pilot's to turn on (All airfields): the flight's own are always drawn
        check("by default the map draws only the flight's own airfields", !com.bmscompanion.app.ui.screens.mission.MapLayers.fields)
        try {
            val st = MapState().apply { initialized = true; flyTo(cx, cy, 2.2f) }
            shot(out, "picture-mission", ::check, st) {
                MissionMapPane(MissionEnv(rememberNavController(), theater, airports), st, null, {}, {})
            }?.let { appendLine("      frame time while panning, the mission's threats (${m.ground?.airDefences?.size} sites): $it") }
            // the same map with every site the side has spotted, as the 1.3.8 test builds drew it (their ground units
            // are no longer drawn at all), to show what the lean picture saves a frame
            val spotted = MissionGrounds.knownEz
            setFlow("_mission", m.copy(ground = m.ground?.copy(airDefences = spotted)))
            val st2 = MapState().apply { initialized = true; flyTo(cx, cy, 2.2f) }
            shot(out, "picture-mission-allsites", ::check, st2) {
                MissionMapPane(MissionEnv(rememberNavController(), theater, airports), st2, null, {}, {})
            }?.let { appendLine("      frame time while panning, every spotted site (${spotted.size}): $it") }
            // and the theater map alone (every mission layer off), what the frame costs before anything of the mission
            setFlow("_mission", m)
            val L = com.bmscompanion.app.ui.screens.mission.MapLayers
            val was = listOf(L.route, L.sams, L.support, L.labels, L.fields)
            L.route = false; L.sams = false; L.support = false; L.labels = false; L.fields = false
            try {
                val st3 = MapState().apply { initialized = true; flyTo(cx, cy, 2.2f) }
                shot(out, "picture-mission-bare", ::check, st3) {
                    MissionMapPane(MissionEnv(rememberNavController(), theater, airports), st3, null, {}, {})
                }?.let { appendLine("      frame time while panning, the theater map alone: $it") }
                // and without its landmarks (borders, provinces, towns), which leaves the relief tiles
                val ML = com.bmscompanion.app.ui.components.MapLook
                val look = Triple(ML.borders, ML.provinces, ML.places)
                ML.showBorders(false); ML.showProvinces(false); ML.showPlaces(0)
                try {
                    val st4 = MapState().apply { initialized = true; flyTo(cx, cy, 2.2f) }
                    shot(out, "picture-mission-tiles", ::check, st4) {
                        MissionMapPane(MissionEnv(rememberNavController(), theater, airports), st4, null, {}, {})
                    }?.let { appendLine("      frame time while panning, the relief tiles alone: $it") }
                    // each landmark layer alone over the tiles, to see which costs the frame
                    for ((what, set) in listOf<Pair<String, () -> Unit>>(
                        "borders" to { ML.showBorders(true); ML.showProvinces(false); ML.showPlaces(0) },
                        "provinces" to { ML.showBorders(false); ML.showProvinces(true); ML.showPlaces(0) },
                        "towns (${look.third})" to { ML.showBorders(false); ML.showProvinces(false); ML.showPlaces(look.third) },
                    )) {
                        set()
                        val st5 = MapState().apply { initialized = true; flyTo(cx, cy, 2.2f) }
                        shot(out, "picture-mission-only-${what.substringBefore(' ')}", ::check, st5) {
                            MissionMapPane(MissionEnv(rememberNavController(), theater, airports), st5, null, {}, {})
                        }?.let { appendLine("      frame time while panning, the tiles and the $what: $it") }
                    }
                } finally {
                    ML.showBorders(look.first); ML.showProvinces(look.second); ML.showPlaces(look.third)
                }
            } finally {
                L.route = was[0]; L.sams = was[1]; L.support = was[2]; L.labels = was[3]; L.fields = was[4]
            }
        } finally {
            setFlow("_mission", null)
        }

        // ---- the Planner's Map page, the same flight open
        PlannerMissionState.source = PlannerMissionState.SAVE
        PlannerMissionState.flight = flight
        PlannerMissionState.theater = th.name
        PlannerMissionState.ref = null
        PlannerMissionState.seat = 0
        val mission = plannerMission(MissionData(briefing = printed, dtc = DtcParser.parse(cartridge)), theater, flight)
        WdpDtcFixture.file = cartridge
        WdpSession.dtcSource = WdpDtcFixture.source()
        val w = WdpSession.dtc
        WdpDialogs.stack.clear()
        w.served = { MissionData(briefing = printed) }
        w.onMission(mission)
        runBlocking { w.prepareFacts() }
        repeat(100) { if (w.fileName != null) return@repeat; Thread.sleep(50) }
        WdpSession.appliedMission = mission
        WdpMapIntel.clear()
        runBlocking { WdpMapIntel.load(mission, WdpMapIntel.key(mission), force = true) }
        WdpMapView.resetForMission()
        WdpMapView.hsd = false
        WdpMapView.optionsOpen = false
        shot(out, "picture-planner", ::check) { WdpMapPage(mission, Modifier.fillMaxSize()) }
        setFlow("_state", LinkState.Idle)
        appendLine(if (fails == 0) "ALL PASS" else "$fails FAIL")
    }

    @Suppress("UNCHECKED_CAST")
    private fun setFlow(field: String, value: Any?) {
        val f = MissionLink::class.java.getDeclaredField(field)
        f.isAccessible = true
        (f.get(MissionLink) as MutableStateFlow<Any?>).value = value
    }

    /**
     * Renders [content] into `<dir>/<name>.png`; with [pan], then times 150 frames of a pan (the map moved a few pixels
     * each frame, a live tick every 15th, as the Android map redraws while a finger drags it) and answers the frame
     * time ("avg 4.1 ms, p95 6.0 ms, max 9.3 ms").
     */
    private fun shot(dir: File, name: String, check: (String, Boolean, String) -> Unit, pan: MapState? = null, content: @Composable () -> Unit): String? {
        val wDp = 1400
        val hDp = 900
        val scene = ImageComposeScene(wDp, hDp, Density(1f)) {
            CompositionLocalProvider(LocalConfiguration provides Configuration(wDp, hDp)) {
                BmsTheme { Box(Modifier.fillMaxSize().background(Hud.Bg)) { content() } }
            }
        }
        try {
            var t = 0L
            repeat(90) { scene.render(t); t += 50_000_000; Thread.sleep(50) }
            val file = File(dir, "$name.png")
            file.writeBytes(scene.render(t).encodeToData(EncodedImageFormat.PNG)!!.bytes)
            check("picture $name.png", file.length() > 1000, "${file.length()} bytes")
            if (pan == null) return null
            val times = ArrayList<Double>()
            repeat(150) { i ->
                pan.panX += if ((i / 40) % 2 == 0) 6f else -6f
                if (i % 15 == 0) setFlow("_live", com.bmscompanion.app.data.mission.Live(t = 1_000_000L + i))
                t += 16_000_000
                val s = System.nanoTime()
                scene.render(t).close()
                if (i >= 10) times += (System.nanoTime() - s) / 1e6
            }
            setFlow("_live", null)
            times.sort()
            return "avg %.1f ms, p95 %.1f ms, max %.1f ms".format(times.average(), times[(times.size * 95) / 100], times.last())
        } catch (e: Throwable) {
            check("picture $name", false, "${e::class.simpleName}: ${e.message}")
            return null
        } finally {
            scene.close()
        }
    }
}
