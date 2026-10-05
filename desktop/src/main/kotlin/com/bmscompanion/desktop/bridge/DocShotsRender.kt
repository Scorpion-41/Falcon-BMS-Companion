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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation.compose.rememberNavController
import com.bmscompanion.app.data.Platform
import com.bmscompanion.app.data.mission.Live
import com.bmscompanion.app.data.mission.MissionData
import com.bmscompanion.app.data.mission.MissionLink
import com.bmscompanion.app.ui.AppRoot
import com.bmscompanion.app.ui.screens.mission.DashCard
import com.bmscompanion.app.ui.screens.mission.DashItem
import com.bmscompanion.app.ui.screens.mission.DashLayouts
import com.bmscompanion.app.ui.screens.mission.DashWidth
import com.bmscompanion.app.ui.screens.mission.MissionTab
import com.bmscompanion.app.ui.screens.mission.MissionTabRequest
import com.bmscompanion.app.ui.theme.BmsTheme
import com.bmscompanion.app.ui.theme.Hud
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/**
 * `--docshots <a copy of the BMS folder> <out folder> [shot…]` — the whole app (navigation rail, Mission header, the
 * EZBoards | WDP switch, the tab strip) drawn headless the way the PC window draws it, for the README's and the release
 * posts' pictures: Home, every Mission tab that reads the copy (Dashboard, Map, Briefing, Comms, Radio, Kneeboards), the
 * Reference pages and a phone. The copy is read through the built-in [Bridge] as on "This PC"; nothing is written into
 * it. Live flight data is made up (a jet on the route, no contacts), since no Falcon BMS is running.
 */
internal object DocShotsRender {
    private val client = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true; explicitNulls = false }

    private class Owner : LifecycleOwner, ViewModelStoreOwner {
        private val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
        override val viewModelStore = ViewModelStore()
    }

    private class Size(val name: String, val w: Int, val h: Int, val d: Float)

    private val PC = Size("pc", 1280, 800, 1.25f)
    private val PHONE = Size("phone", 393, 852, 2f)

    fun run(root: File, out: File, only: List<String>): String = buildString {
        out.mkdirs()
        Platform.encodePng = { com.bmscompanion.desktop.skiaPng(it) }
        Platform.encodeJpeg = { img, q -> com.bmscompanion.desktop.skiaJpeg(img, q) }
        Platform.decodeImage = { com.bmscompanion.desktop.skiaDecode(it) }
        Platform.nowMillis = { System.currentTimeMillis() }
        // the bundled data the pages ask for first, loaded before the first frame
        kotlinx.coroutines.runBlocking { com.bmscompanion.app.data.Repo.index() }
        Bridge.startForCheck()
        MissionLink.useThisPc()
        val m = runCatching {
            client.decodeFromString(MissionData.serializer(), Bridge.handle(ApiRequest("GET", "/api/mission")).body.toString(Charsets.UTF_8))
        }.getOrNull()
        val route = (m?.route?.steerpoints ?: m?.dtc?.steerpoints).orEmpty().filter { it.x != 0.0 || it.y != 0.0 }
        appendLine("mission: ${if (m?.briefing != null) "printed briefing" else "no briefing"}, ${route.size} route points")
        // a jet two thirds of the way from STPT 2 to 3, at the CAP's altitude: what the flying cards show
        // (seg=<n>: on the leg from route point n+1 to n+2 instead)
        val seg = only.firstOrNull { it.startsWith("seg=") }?.substringAfter('=')?.toIntOrNull() ?: 1
        val a = route.getOrNull(seg)
        val b = route.getOrNull(seg + 1) ?: a
        val live: Live? = if (a != null && b != null) {
            val x = a.x + (b.x - a.x) * 0.66
            val y = a.y + (b.y - a.y) * 0.66
            val hdg = Math.toDegrees(kotlin.math.atan2(b.y - a.y, b.x - a.x)).let { if (it < 0) it + 360 else it }
            Live(
                t = System.currentTimeMillis(), flying = true, theater = "Korea KTO", aircraft = "F-16CM-40",
                x = x, y = y, altFt = 21_040.0, hdgTrue = hdg, hdgMag = (hdg + 8.5) % 360, kias = 302.0, mach = 0.71, gsKts = 418.0,
                vviFpm = 0.0, gLoad = 1.0, aoa = 4.1, fuelInternal = 5_820.0, fuelExternal = 1_900.0, fuelFlow = 4_350.0, bingo = 2_400.0,
                chaff = 60, flares = 30, timeSec = 4 * 3600 + 29 * 60 + 12, tacan = "92Y", tacanUfc = "94X", uhfPreset = 6, uhfFreq = 384875,
                ded = listOf(
                    "  UHF  384.87   STPT A  3 ",
                    "                04:29:12  ",
                    "  VHF   15        ",
                    "                  M34   4",
                    "  M1  43  M3 0554   T 94X",
                ),
            )
        } else null
        val wanted = only.filter { '=' !in it }.toSet()
        fun want(n: String) = wanted.isEmpty() || n in wanted

        // (the Radio tab reads the newest *_xlog.txt in the copy's User\Logs, put there by the caller)
        if (want("home")) shot(out, "home", PC, "home")
        // the Dashboard: page 4 laid out for before take-off, page 3 (the flying) with the made-up jet
        DashLayouts.state("wide", 3).value = listOf(
            DashItem(DashCard.MAP, DashWidth.M, 2), DashItem(DashCard.WEATHER), DashItem(DashCard.SUPPORT),
            DashItem(DashCard.AIRBASES), DashItem(DashCard.STEERPOINTS),
        )
        if (want("dash-preflight")) { DashLayouts.choose(3); shot(out, "mission-dash-preflight", PC, "mission", MissionTab.DASH) }
        if (want("dash-flying")) { DashLayouts.choose(2); shot(out, "mission-dash-flying", PC, "mission", MissionTab.DASH, live) }
        if (want("dash-map")) { DashLayouts.choose(0); shot(out, "mission-dash-map", PC, "mission", MissionTab.DASH, live) }
        if (want("map")) shot(out, "mission-map", PC, "mission", MissionTab.MAP)
        if (want("map-live")) shot(out, "mission-map-live", PC, "mission", MissionTab.MAP, live)
        if (want("briefing")) shot(out, "mission-briefing", PC, "mission", MissionTab.BRIEF)
        if (want("comms")) shot(out, "mission-comms", PC, "mission", MissionTab.COMMS)
        if (want("radio")) {
            // BMS is not running, so the bridge's tick does not read its log: the copy's newest log is read here
            RadioLog.newestLog(RadioLog.logsDir(Bridge.install))?.let { RadioLog.reader.follow(it); RadioLog.reader.poll() }
            appendLine("     radio calls read: ${RadioLog.reader.all().size}")
            shot(out, "mission-radio", PC, "mission", MissionTab.RADIO)
        }
        if (want("kneeboards")) shot(out, "mission-kneeboards", PC, "mission", MissionTab.BOARDS)
        if (want("planner-locked")) shot(out, "mission-planner-locked", PC, "mission", MissionTab.PLANNER)
        if (want("reference")) shot(out, "reference-airfields", PC, "reference")
        if (want("threats")) shot(out, "reference-threats", PC, "threats")
        if (want("arsenal")) shot(out, "reference-arsenal", PC, "arsenal")
        if (want("setup")) shot(out, "setup", PC, "setup")
        if (want("phone-briefing")) shot(out, "phone-briefing", PHONE, "mission", MissionTab.BRIEF)
        if (want("phone-map")) shot(out, "phone-map", PHONE, "mission", MissionTab.MAP, live)
        if (want("phone-dash")) { DashLayouts.choose(2); shot(out, "phone-dash-flying", PHONE, "mission", MissionTab.DASH, live) }
        // the switch into WDP mode, and the Mission section's page before the first Populate
        if (want("wdp")) {
            val r = Bridge.handle(ApiRequest("POST", "/api/mission/source", mapOf("mode" to "wdp")))
            appendLine("switch to WDP mode: HTTP ${r.status}")
            shot(out, "mission-wdp-notpopulated", PC, "mission", MissionTab.BRIEF)
            Bridge.handle(ApiRequest("POST", "/api/mission/source", mapOf("mode" to "ezboards")))
        }
        // any other page of the app by its route ("route:hotas/f16" → route-hotas-f16.png)
        for (r in only.filter { it.startsWith("route:") }.map { it.substringAfter(':') }) {
            shot(out, "route-" + r.replace(Regex("[^A-Za-z0-9]+"), "-").trim('-'), PC, r)
        }
        // any Planner page in WDP mode, on the printed flight ("planner:DATACARD", "planner:MAP", …; WdpPage names)
        val guideKey = com.bmscompanion.app.ui.screens.wdp.PlannerGuide.SEEN_KEY
        if (want("planner-guide")) {
            // the Guide, as it opens by itself the first time the Planner is opened on a device
            com.bmscompanion.app.data.Repo.putInt(guideKey, 0)
            Bridge.handle(ApiRequest("POST", "/api/mission/source", mapOf("mode" to "wdp")))
            shot(out, "planner-guide", PC, "mission", MissionTab.PLANNER, then = { MissionTabRequest.open(MissionTab.PLANNER) })
            Bridge.handle(ApiRequest("POST", "/api/mission/source", mapOf("mode" to "ezboards")))
        }
        for (p in only.filter { it.startsWith("planner:") }.map { it.substringAfter(':') }) {
            com.bmscompanion.app.data.Repo.putInt(guideKey, 1)
            Bridge.handle(ApiRequest("POST", "/api/mission/source", mapOf("mode" to "wdp")))
            shot(out, "planner-${p.lowercase()}", PC, "mission", MissionTab.PLANNER, then = {
                MissionTabRequest.open(MissionTab.PLANNER)
                com.bmscompanion.app.ui.screens.wdp.WdpSession.page = p
            })
            Bridge.handle(ApiRequest("POST", "/api/mission/source", mapOf("mode" to "ezboards")))
        }
        // the Planner's ATO Targets page, in WDP mode, on the newest save of the copy's theater
        if (want("planner-ato")) {
            com.bmscompanion.app.data.Repo.putInt(guideKey, 1)
            Bridge.handle(ApiRequest("POST", "/api/mission/source", mapOf("mode" to "wdp")))
            val save = only.firstOrNull { it.startsWith("save=") }?.substringAfter('=') ?: "Auto Save.cam"
            shot(out, "planner-ato", PC, "mission", MissionTab.PLANNER, then = {
                // (asked again once the link says WDP mode: before that the Planner tab is locked and the request is dropped)
                MissionTabRequest.open(MissionTab.PLANNER)
                com.bmscompanion.app.ui.screens.wdp.AtoTargetList.open(com.bmscompanion.app.data.mission.CampRef("Korea KTO", save))
            })
            Bridge.handle(ApiRequest("POST", "/api/mission/source", mapOf("mode" to "ezboards")))
        }
        setFlow("_live", null)
        appendLine("done")
    }

    /** The made-up jet, and the PC saying BMS is in 3D (its poll says otherwise every second, so this is said before every frame). */
    private fun flying(live: Live) {
        setFlow("_live", live.copy(t = System.currentTimeMillis()))
        MissionLink.info.value?.let { i -> if (!i.bms.flying) setFlow("_info", i.copy(bms = i.bms.copy(running = true, flying = true))) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun setFlow(field: String, value: Any?) {
        val f = MissionLink::class.java.getDeclaredField(field)
        f.isAccessible = true
        (f.get(MissionLink) as MutableStateFlow<Any?>).value = value
    }

    private fun StringBuilder.shot(out: File, name: String, s: Size, route: String, tab: MissionTab? = null, live: Live? = null, then: (() -> Unit)? = null) {
        if (tab != null) MissionTabRequest.open(tab)
        val owner = Owner()
        // the navigation's lifecycles insist on the main thread, which on the desktop is Swing's: every step of the
        // scene runs there, and the waiting between frames outside it, so the app's own main-thread work gets its turn
        fun <T> edt(f: () -> T): T {
            var r: Result<T>? = null
            javax.swing.SwingUtilities.invokeAndWait { r = runCatching(f) }
            return r!!.getOrThrow()
        }
        var scene: ImageComposeScene? = null
        var nav: androidx.navigation.NavHostController? = null
        try {
            scene = edt {
                ImageComposeScene((s.w * s.d).toInt(), (s.h * s.d).toInt(), Density(s.d)) {
                    CompositionLocalProvider(
                        LocalLifecycleOwner provides owner,
                        LocalViewModelStoreOwner provides owner,
                        LocalConfiguration provides Configuration(s.w, s.h),
                    ) {
                        val n = rememberNavController().also { nav = it }
                        BmsTheme { Box(Modifier.fillMaxSize().background(Hud.Bg)) { AppRoot(null, n) } }
                    }
                }
            }
            var t = 0L
            // long enough for the link's first rounds (info, mission, the radio) and the map's tiles
            repeat(140) { i ->
                if (live != null) flying(live)
                val at = t
                edt {
                    // the section is opened once the graph is set (as the PC window opens a route asked for early)
                    if (i == 5 && route != "home") runCatching { nav?.navigate(route) }.onFailure { appendLine("     navigate $route: $it") }
                    // what the page does once it is up (a page turned to, a window opened)
                    if (i == 40) then?.let { f -> runCatching(f).onFailure { appendLine("     then: $it") } }
                    androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
                    scene.render(at).close()
                }
                t += 50_000_000; Thread.sleep(50)
            }
            val bytes = edt {
                androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
                if (live != null) flying(live)
                scene.render(t).close(); t += 50_000_000
                if (live != null) flying(live)
                scene.render(t).encodeToData(EncodedImageFormat.PNG)!!.bytes
            }
            File(out, "$name.png").writeBytes(bytes)
            appendLine("     picture $name.png (${s.name})")
        } catch (e: Throwable) {
            appendLine("FAIL picture $name: ${e::class.simpleName}: ${e.message}")
        } finally {
            runCatching { edt { scene?.close() } }
            if (live != null) setFlow("_live", null)
        }
    }
}
