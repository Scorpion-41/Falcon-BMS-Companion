package com.bmscompanion.desktop.bridge

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation.compose.rememberNavController
import com.bmscompanion.app.data.Platform
import com.bmscompanion.app.data.mission.MissionLink
import com.bmscompanion.app.ui.screens.ThreatDetail
import com.bmscompanion.app.ui.screens.mission.TargetPics
import com.bmscompanion.app.ui.screens.mission.TargetPicturesCard
import com.bmscompanion.app.ui.theme.BmsTheme
import com.bmscompanion.app.ui.theme.Hud
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/**
 * `--targetcards <out folder>` — the Briefing's Target pictures card and a Threats page drawn headless as the PC window
 * draws them, the BMS folder read through the built-in [Bridge] (the scratch settings' `BmsDirOverride`: a copy that
 * holds the models the briefing's threats need). PNGs and `targetcards.txt` into the out folder; read only.
 */
internal object TargetCardRender {
    private class Owner : LifecycleOwner, ViewModelStoreOwner {
        private val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
        override val viewModelStore = ViewModelStore()
    }

    fun run(out: File): String = buildString {
        out.mkdirs()
        Platform.encodePng = { com.bmscompanion.desktop.skiaPng(it) }
        Platform.encodeJpeg = { img, q -> com.bmscompanion.desktop.skiaJpeg(img, q) }
        Platform.decodeImage = { com.bmscompanion.desktop.skiaDecode(it) }
        Platform.nowMillis = { System.currentTimeMillis() }
        kotlinx.coroutines.runBlocking { com.bmscompanion.app.data.Repo.index() }
        Bridge.startForCheck()
        MissionLink.useThisPc()
        TargetPictures.cacheOverride = File(out, "cache")
        val set = Bridge.handle(ApiRequest("GET", "/api/targets/mission"))
        appendLine("/api/targets/mission → ${set.status}: ${set.body.toString(Charsets.UTF_8).take(1500)}")
        val parsed = runCatching { Bridge.json.decodeFromString(com.bmscompanion.app.data.mission.TargetSet.serializer(), set.body.toString(Charsets.UTF_8)) }.getOrNull()
        appendLine("the card's entries (${parsed?.groups?.size ?: "?"}):")
        parsed?.groups?.forEach { appendLine("   ${it.kind.uppercase()}  ${it.title}${it.note?.let { n -> "  ($n)" } ?: ""}  ${it.items.size} kinds") }
        // the mission's own fields never among them
        val fields = parsed?.groups.orEmpty().filter { g -> g.kind == "target" && Regex("""\b(AB|AFB|Airport|Air Base|Airbase|AAF|Strip)\b|\(R[A-Z]{3}\)""").containsMatchIn(g.title) }
        for (g in fields) appendLine("FAIL: an airfield among the targets: ${g.title}")
        val missionKey = MissionLink.mission.value?.missionKey ?: ""
        val threatId = kotlinx.coroutines.runBlocking { com.bmscompanion.app.data.Repo.threats() }.firstOrNull { it.name.startsWith("SA-6") }?.id
        TargetPics.saveAdded(missionKey, emptyList()); TargetPics.saveRemoved(missionKey, emptyList())
        // one look only since 1.4.0: the targeting pod's (black and white)
        shot(out, "briefing-card-intel", 520, 2400) {
            Column(Modifier.width(500.dp).padding(10.dp)) { TargetPicturesCard() }
        }
        // the Threats page's card is folded by default; drawn folded, then opened
        val openKey = "open_threat_pictures"
        val wasOpen = com.bmscompanion.app.data.Repo.getInt(openKey, 0)
        if (threatId != null) {
            com.bmscompanion.app.data.Repo.putInt(openKey, 0)
            shot(out, "threat-sa6-folded", 1280, 1700) { ThreatDetail(rememberNavController(), threatId, onBack = null) }
            com.bmscompanion.app.data.Repo.putInt(openKey, 1)
            shot(out, "threat-sa6-intel", 1280, 1700) { ThreatDetail(rememberNavController(), threatId, onBack = null) }
            // another threat right after: its own pictures, never the SA-6's (produceState kept the last picture)
            val other = kotlinx.coroutines.runBlocking { com.bmscompanion.app.data.Repo.threats() }.firstOrNull { it.name.startsWith("SA-8") }?.id
            if (other != null) shot(out, "threat-sa8-after-sa6", 1280, 1700) { ThreatSwitch(threatId, other) }
            com.bmscompanion.app.data.Repo.putInt(openKey, wasOpen)
        }
        shot(out, "phone-briefing-card", 393, 2600, density = 2f) {
            Column(Modifier.padding(8.dp)) { TargetPicturesCard() }
        }
        // the pilot's own list: the first threat removed (a red ✕), a target added from the list; then Reload's state
        val firstThreat = parsed?.groups?.firstOrNull { it.kind == "threat" }
        if (firstThreat != null) TargetPics.saveRemoved(missionKey, listOf(TargetPics.groupId(firstThreat)))
        TargetPics.saveAdded(missionKey, listOf(com.bmscompanion.app.data.mission.TargetItem(key = parsed?.groups?.lastOrNull()?.items?.firstOrNull()?.key ?: "ct1", name = "Added from the list", category = "Example", role = "added")))
        appendLine("edited: removed ${firstThreat?.title ?: "nothing"}, one target added")
        shot(out, "phone-card-edited", 393, 2600, density = 2f) {
            Column(Modifier.padding(8.dp)) { TargetPicturesCard() }
        }
        TargetPics.saveAdded(missionKey, emptyList()); TargetPics.saveRemoved(missionKey, emptyList())
        appendLine("wrote ${out.listFiles { f -> f.name.endsWith(".png") }?.size ?: 0} pictures")
        TargetPictures.cacheOverride = null
    }

    /** The Threats page opened on [first], then on [then] in the same composition, as a tap in the list does. */
    @Composable
    private fun ThreatSwitch(first: String, then: String) {
        var id by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(first) }
        androidx.compose.runtime.LaunchedEffect(Unit) { kotlinx.coroutines.delay(2500); id = then }
        ThreatDetail(rememberNavController(), id, onBack = null)
    }

    private fun StringBuilder.shot(out: File, name: String, w: Int, h: Int, density: Float = 1.5f, content: @Composable () -> Unit) {
        val owner = Owner()
        fun <T> edt(f: () -> T): T {
            var r: Result<T>? = null
            javax.swing.SwingUtilities.invokeAndWait { r = runCatching(f) }
            return r!!.getOrThrow()
        }
        var scene: ImageComposeScene? = null
        try {
            scene = edt {
                ImageComposeScene((w * density).toInt(), (h * density).toInt(), Density(density)) {
                    CompositionLocalProvider(
                        LocalLifecycleOwner provides owner,
                        LocalViewModelStoreOwner provides owner,
                        LocalConfiguration provides Configuration(w, h),
                    ) {
                        BmsTheme { Box(Modifier.fillMaxSize().background(Hud.Bg)) { content() } }
                    }
                }
            }
            var t = 0L
            // long enough for the list and every picture of the open group to arrive
            repeat(120) {
                val at = t
                edt {
                    androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
                    scene.render(at).close()
                }
                t += 50_000_000; Thread.sleep(50)
            }
            val bytes = edt {
                androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
                scene.render(t).use { it.encodeToData(EncodedImageFormat.PNG)?.bytes }
            }
            if (bytes != null) { File(out, "$name.png").writeBytes(bytes); appendLine("$name.png") }
            else appendLine("FAIL: $name: nothing drawn")
        } catch (e: Throwable) {
            appendLine("FAIL: $name: ${e.javaClass.simpleName} ${e.message}")
        } finally {
            runCatching { edt { scene?.close() } }
        }
    }
}
