@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)

package com.bmscompanion.desktop.bridge

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
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
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.mission.BridgeInfo
import com.bmscompanion.app.data.mission.LinkState
import com.bmscompanion.app.data.mission.MissionData
import com.bmscompanion.app.data.mission.MissionLink
import com.bmscompanion.app.ui.components.AirfieldDiagrams
import com.bmscompanion.app.ui.components.MapState
import com.bmscompanion.app.ui.screens.mission.MapLayers
import com.bmscompanion.app.ui.screens.mission.MapSel
import com.bmscompanion.app.ui.screens.mission.MissionEnv
import com.bmscompanion.app.ui.screens.mission.MissionMapPane
import com.bmscompanion.app.ui.screens.mission.airbases
import com.bmscompanion.app.ui.screens.wdp.WdpProbe
import com.bmscompanion.app.ui.screens.wdp.plannerTheater
import com.bmscompanion.app.ui.theme.BmsTheme
import com.bmscompanion.app.ui.theme.Hud
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

/**
 * `--airfieldcardrender <out folder>` — the Mission map's airfield card (MissionFieldCard), headless, at a phone
 * (400 x 800 dp), a tablet (1280 x 800) and a PC window (1600 x 1000), with the mission of the settings' BMS folder (a
 * copy; EZBoards mode's `/api/mission`, read only): the flight's take-off field, else Osan with All airfields on.
 * Per size: (1) a tap on the field's symbol opens its card (not STPT 1, which sits on it), the card's picture, and its
 * place — on a phone a sheet over at most 62 % of the map, elsewhere a card floating over the map, not covering it;
 * (2) a tap on nothing closes it; (3) close in, where the field is drawn as its diagram, a tap on a runway half a mile
 * from the field's reference point opens the card too (a point card up to 1.3.9). Pictures beside the report.
 */
internal object AirfieldCardRender {
    private val client = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true; explicitNulls = false }

    private class Owner : LifecycleOwner, ViewModelStoreOwner {
        private val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
        override val viewModelStore = ViewModelStore()
    }

    private fun <T> edt(f: () -> T): T {
        var r: Result<T>? = null
        javax.swing.SwingUtilities.invokeAndWait { r = runCatching(f) }
        return r!!.getOrThrow()
    }

    @Suppress("UNCHECKED_CAST")
    private fun setFlow(field: String, value: Any?) {
        val f = MissionLink::class.java.getDeclaredField(field)
        f.isAccessible = true
        (f.get(MissionLink) as MutableStateFlow<Any?>).value = value
    }

    fun run(out: File): String = buildString {
        out.mkdirs()
        org.jetbrains.skia.Surface.makeRasterN32Premul(1, 1).close()
        var fails = 0
        fun check(what: String, ok: Boolean, detail: String = "") {
            if (!ok) fails++
            appendLine((if (ok) "ok    " else "FAIL  ") + what + if (detail.isNotEmpty()) " — $detail" else "")
        }
        Bridge.startForCheck()
        MissionLink.useThisPc()
        fun <T> api(path: String, s: kotlinx.serialization.KSerializer<T>): T? = runCatching {
            client.decodeFromString(s, Bridge.handle(ApiRequest("GET", path, emptyMap(), ByteArray(0))).body.toString(Charsets.UTF_8))
        }.getOrNull()
        val m = api("/api/mission", MissionData.serializer())
        val info = api("/api/info", BridgeInfo.serializer())
        val name = System.getenv("BMSC_DEV_THEATER") ?: "Korea KTO"
        val theater = runBlocking { plannerTheater(Repo.index().theaters, name) ?: Repo.theater("korea-kto") }
        if (theater == null) { check("the theater ($name)", false); return@buildString }
        val airports = runBlocking { Repo.airportSet(theater.airportSet) }
        setFlow("_state", LinkState.Online("test"))
        setFlow("_info", info)
        setFlow("_live", null)
        setFlow("_contacts", null)
        setFlow("_mission", m)
        val merged = m?.let { com.bmscompanion.app.data.mission.PlanMerge.merge(it, null) }
        val home = merged?.let { airbases(null, it).departureIn(airports) }
        val wasFields = MapLayers.fields
        val wasFollow = MapLayers.follow
        MapLayers.follow = false
        val field = home ?: airports.airports.firstOrNull { it.icao == "RKSO" } ?: airports.airports.first()
        if (home == null) MapLayers.fields = true
        check("the flight's take-off field from the briefing", home != null, "${field.name} (${field.icao})")
        val stpt1OnField = merged?.let { com.bmscompanion.app.ui.screens.mission.steerpoints(it) }.orEmpty()
            .firstOrNull { it.hasPos && com.bmscompanion.app.ui.screens.mission.rangeNm(field.x, field.y, it.x!!, it.y!!) < 1.5 }
        appendLine("      steerpoint on the field: ${stpt1OnField?.let { "STPT ${it.n} ${it.action ?: ""}" } ?: "none"}")
        WdpProbe.on = true
        val owner = Owner()
        try {
            for ((label, wDp, hDp, dens) in listOf(
                Quad("phone", 400, 800, 2f), Quad("tablet", 1280, 800, 1.5f), Quad("pc", 1600, 1000, 1f),
            )) {
                val w = (wDp * dens).toInt()
                val h = (hDp * dens).toInt()
                var sel by mutableStateOf<MapSel?>(null)
                val st = MapState().apply { initialized = true }
                val wide = wDp >= 840
                val mapW = (if (wide) wDp - 401 else wDp) * dens
                val center = Offset(mapW / 2f, h / 2f)
                val scene = edt {
                    ImageComposeScene(w, h, Density(dens)) {
                        CompositionLocalProvider(
                            LocalLifecycleOwner provides owner, LocalViewModelStoreOwner provides owner,
                            LocalConfiguration provides Configuration(wDp, hDp),
                        ) {
                            val nav = rememberNavController()
                            BmsTheme { Box(Modifier.fillMaxSize().background(Hud.Bg)) { MissionMapPane(MissionEnv(nav, theater, airports), st, sel, { sel = it }, {}) } }
                        }
                    }
                }
                var nanos = 0L
                var ms = 1_000L
                fun settle(n: Int, sleep: Long = 30) = repeat(n) { edt { Snapshot.sendApplyNotifications(); nanos += 16_666_667L; scene.render(nanos) }.close(); Thread.sleep(sleep) }
                fun tap(at: Offset) {
                    ms += 50
                    edt {
                        scene.sendPointerEvent(PointerEventType.Move, at, timeMillis = ms)
                        scene.sendPointerEvent(PointerEventType.Press, at, timeMillis = ms, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
                    }
                    settle(2)
                    ms += 80
                    edt { scene.sendPointerEvent(PointerEventType.Release, at, timeMillis = ms, buttons = PointerButtons(), button = PointerButton.Primary) }
                    // the map waits out the double-tap time before it takes a tap
                    settle(16, 40)
                    ms += 600
                }
                fun shot(file: String) {
                    val img = edt { Snapshot.sendApplyNotifications(); nanos += 16_666_667L; scene.render(nanos) }
                    File(out, "$file.png").writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
                    img.close()
                    appendLine("      picture $file.png")
                }
                try {
                    appendLine("[$label] ${wDp} x ${hDp} dp at ${dens}x")
                    // (1) the field's symbol, the map well out
                    st.flyTo(field.x, field.y, 3f)
                    settle(40)
                    tap(center)
                    check("$label: a tap on the field's symbol opens its card", sel == MapSel.Field(field.id), "selected $sel")
                    settle(50)
                    shot("card-$label")
                    val r = WdpProbe.rects["mission/fieldcard"]
                    check("$label: the card is laid out", r != null && r.width > 100f && r.height > 100f, "$r")
                    if (r != null) {
                        if (wide) check("$label: the card floats over the map and leaves most of it", r.width < mapW * 0.6f && r.left < mapW && r.height < h * 0.92f,
                            "card ${r.width.toInt()} x ${r.height.toInt()} px over a ${mapW.toInt()} px map")
                        else check("$label: the sheet covers at most 62 % of the map", r.height <= h * 0.62f + 2f && r.width >= w * 0.9f,
                            "sheet ${r.width.toInt()} x ${r.height.toInt()} px of ${w} x ${h}")
                    }
                    // (2) a tap on nothing closes it
                    // (the card sits at the foot of the map: the top right of the map, clear of the layer chips)
                    val away = Offset(mapW * 0.72f, h * 0.22f)
                    tap(away)
                    check("$label: a tap elsewhere on the map closes the card", sel !is MapSel.Field, "selected $sel")
                    sel = null
                    WdpProbe.rects.remove("mission/fieldcard")
                    // (3) close in: the field drawn as its diagram, a tap on a runway half a mile from the reference point
                    st.flyToSpan(field.x, field.y, 3.0)
                    settle(90, 40)
                    val hdg = field.runways.firstOrNull()?.ends?.firstOrNull()?.headingTrue ?: 90.0
                    val pxPerNm = minOf(mapW, h.toFloat()) / 3f
                    val off = 0.45f * pxPerNm
                    val onRunway = center + Offset((sin(Math.toRadians(hdg)) * off).toFloat(), (-cos(Math.toRadians(hdg)) * off).toFloat())
                    appendLine("      close in: ${pxPerNm.toInt()} px a nm, tap ${off.toInt()} px from the reference point along ${hdg.toInt()}°; diagram there: ${AirfieldDiagrams.fieldAt(onRunway) != null}")
                    tap(onRunway)
                    check("$label: close in, a tap on the field's diagram opens its card", sel == MapSel.Field(field.id), "selected $sel")
                    settle(30)
                    shot("diagram-$label")
                    sel = null
                } finally {
                    edt { scene.close() }
                }
            }
        } finally {
            WdpProbe.on = false
            MapLayers.fields = wasFields
            MapLayers.follow = wasFollow
            setFlow("_mission", null)
            setFlow("_state", LinkState.Idle)
        }
        appendLine(if (fails == 0) "ALL PASS" else "$fails FAIL")
    }

    private data class Quad(val a: String, val b: Int, val c: Int, val d: Float)
}
