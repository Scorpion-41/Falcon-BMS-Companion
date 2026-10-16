package com.bmscompanion.desktop.bridge

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import com.bmscompanion.app.data.mission.BmsInstallEntry
import com.bmscompanion.app.data.mission.BmsInstallList
import com.bmscompanion.app.data.mission.PcAnswer
import com.bmscompanion.app.ui.screens.mission.BmsInstallsCard
import com.bmscompanion.app.ui.screens.mission.BmsInstallsSource
import com.bmscompanion.app.ui.theme.BmsTheme
import com.bmscompanion.app.ui.theme.Hud
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/**
 * `--installsrender <out folder>` — the Falcon BMS installs card (1.4.1, [BmsInstallsCard]) drawn headless at phone,
 * tablet and PC widths and at the server page's column, from a made-up list (no real folder or registry is read, so the
 * pictures carry no path of this PC): two registry installs, a copy the pilot added and named, a folder that is gone.
 * PNGs and `installsrender.txt` into the out folder. The routes themselves are `--installstest`'s.
 */
internal object InstallsRender {
    private class Owner : LifecycleOwner, ViewModelStoreOwner {
        private val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
        override val viewModelStore = ViewModelStore()
    }

    private fun list(active: String) = BmsInstallList(
        installs = listOf(
            BmsInstallEntry("r:Falcon BMS 4.38", "Falcon BMS 4.38", "D:\\Games\\Falcon BMS 4.38", registryKey = "Falcon BMS 4.38", registryDefault = true,
                exists = true, looksLikeBms = true, theater = "Korea KTO"),
            BmsInstallEntry("r:Falcon BMS 4.37", "Falcon BMS 4.37", "D:\\Games\\Falcon BMS 4.37", registryKey = "Falcon BMS 4.37",
                exists = true, looksLikeBms = true, theater = "Balkans"),
            BmsInstallEntry("a:1", "Test copy", "E:\\BMS copies\\4.38 test", label = "Test copy", added = true, exists = true, looksLikeBms = true),
            BmsInstallEntry("a:2", "Falcon BMS 4.35", "E:\\Old\\Falcon BMS 4.35", added = true, exists = false),
        ).map { it.copy(active = it.id == active) },
        active = active, followsRegistry = active == "r:Falcon BMS 4.38",
    )

    private class Fake(val active: String, val message: String? = null) : BmsInstallsSource {
        override suspend fun list(refresh: Boolean) = PcAnswer(value = list(active).copy(message = message), status = 200)
        override suspend fun change(op: String, args: Map<String, String>) = PcAnswer(value = list(args["id"] ?: active), status = 200)
        override suspend fun pickFolder(start: String?): String? = null
    }

    fun run(out: File): String = buildString {
        out.mkdirs()
        // phone, tablet (the Setup page's 440-dp column, two of them), the PC window, the server page's column
        shot(out, "phone-setup", 393, 760, 2.75f) { Column(Modifier.padding(12.dp)) { BmsInstallsCard(Fake("r:Falcon BMS 4.38"), linkedOverride = true) } }
        shot(out, "tablet-setup", 560, 700, 2f) { Column(Modifier.width(520.dp).padding(12.dp)) { BmsInstallsCard(Fake("r:Falcon BMS 4.37"), linkedOverride = true) } }
        shot(out, "pc-setup", 720, 620, 1.25f) { Column(Modifier.width(680.dp).padding(12.dp)) { BmsInstallsCard(Fake("a:1"), linkedOverride = true) } }
        shot(out, "server-page-card", 460, 640, 1.25f) { Column(Modifier.width(440.dp).padding(10.dp)) { BmsInstallsCard(Fake("r:Falcon BMS 4.38"), linkedOverride = true) } }
        shot(out, "phone-not-linked", 393, 360, 2.75f) { Column(Modifier.padding(12.dp)) { BmsInstallsCard(Fake("r:Falcon BMS 4.38"), linkedOverride = false) } }
        appendLine("wrote ${out.listFiles { f -> f.name.endsWith(".png") }?.size ?: 0} pictures")
    }

    private fun StringBuilder.shot(out: File, name: String, w: Int, h: Int, density: Float, content: @Composable () -> Unit) {
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
            repeat(20) {
                val at = t
                edt {
                    androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
                    scene.render(at).close()
                }
                t += 50_000_000; Thread.sleep(30)
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
