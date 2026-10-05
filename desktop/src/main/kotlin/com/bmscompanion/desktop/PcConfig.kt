package com.bmscompanion.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.mission.LinkMode
import com.bmscompanion.app.data.mission.MissionLink
import com.bmscompanion.desktop.bridge.Bridge
import java.util.concurrent.Executors

/** What the one BMS Companion window shows. */
enum class PcMode {
    /** A single light page: status, connecting devices, setup checklist and settings. Falcon BMS runs on this PC. */
    SERVER,
    /** The whole app (maps, AWACS, reference…), with data from this PC or from the BMS PC on the network. */
    APP,
}

/**
 * How the window is drawn.
 *
 * Compose draws through Skia, which on Windows picks Direct3D 12 by default. That is right for anything current and
 * wrong for a graphics chip of the sort still sitting in a perfectly serviceable office PC beside a flying rig: an
 * older Intel HD has no D3D12 driver at all, Skia falls back to drawing every frame on the processor, and the window
 * becomes a slide show. Those chips do have OpenGL, which Skia can use, so the choice is offered rather than guessed.
 *
 * [prop] is what Skiko reads out of the system properties; it has to be set before the first window is built.
 */
enum class PcRenderer(val label: String, val prop: String?, val note: String) {
    AUTO("Automatic", null, "Direct3D on anything recent. The right choice unless the window is slow."),
    OPENGL("OpenGL", "OPENGL", "For an older graphics chip — an Intel HD, or anything without Direct3D 12."),
    SOFTWARE("Software", "SOFTWARE", "Drawn entirely on the processor. Slowest, but it works everywhere."),
    DIRECT3D("Direct3D", "DIRECT3D", "Forced on, in case the automatic choice picks something else."),
}

/** PC program settings (in %APPDATA%\BMS Companion\pc-app.properties). */
object PcConfig {
    /**
     * Which way the window is drawn. Read once at start-up by [applyRenderer] and not again: Skia builds its
     * context with the first window, so changing this takes effect the next time BMS Companion starts.
     */
    var renderer by mutableStateOf(
        runCatching { PcRenderer.valueOf(Repo.getString("pc_render") ?: "") }.getOrDefault(PcRenderer.AUTO),
    )
        private set

    /** What was actually in force when this copy started, so the page can say a restart is needed and mean it. */
    var rendererInUse = renderer
        private set

    fun useRenderer(r: PcRenderer) {
        renderer = r
        Repo.putString("pc_render", r.name)
    }

    /**
     * Hands the chosen renderer to Skiko, before anything has drawn.
     *
     * Only ever sets the property when the pilot has chosen something: an unset property is what lets Skiko make its
     * own choice, and writing "DIRECT3D" into it by default would take that away. A property already set by hand on
     * the command line wins, so a machine that cannot start at all can be talked into starting.
     */
    fun applyRenderer() {
        rendererInUse = renderer
        val want = renderer.prop ?: return
        if (System.getProperty("skiko.renderApi").isNullOrBlank()) System.setProperty("skiko.renderApi", want)
    }

    /** Last used mode; a first start opens the server page. */
    var mode by mutableStateOf(if (Repo.getString("pc_mode") == PcMode.APP.name) PcMode.APP else PcMode.SERVER)
        private set

    /** App mode only: false when Falcon BMS runs on another PC and this one is a client. The server mode always reads BMS here. */
    var bmsOnThisPc by mutableStateOf(Repo.getString("pc_source") != "OTHER_PC")
        private set

    /** Browsers on the network can open the app (on by default: iPhone and iPad need it). */
    var webEnabled by mutableStateOf(Repo.getInt("pc_web", 1) == 1)
        private set

    /** Closing the window keeps BMS Companion in the tray (default: in server mode). */
    private var trayChoice by mutableStateOf(Repo.getInt("pc_close_tray", -1))
    val closeToTray: Boolean get() = if (trayChoice < 0) mode == PcMode.SERVER || bmsOnThisPc else trayChoice == 1

    /** Falcon BMS is read on this PC (and its data served to other devices). */
    val readsBms: Boolean get() = mode == PcMode.SERVER || bmsOnThisPc

    fun switchTo(m: PcMode) {
        if (m == PcMode.SERVER && !bmsOnThisPc) useBmsOnThisPc(true)
        mode = m
        Repo.putString("pc_mode", m.name)
        PcServices.apply()
    }

    /** App mode: Falcon BMS on this PC, or on another PC (this one is a client). */
    fun useBmsOnThisPc(here: Boolean) {
        bmsOnThisPc = here
        Repo.putString("pc_source", if (here) "THIS_PC" else "OTHER_PC")
        PcServices.apply()
    }

    fun setWeb(on: Boolean) {
        webEnabled = on
        Repo.putInt("pc_web", if (on) 1 else 0)
        PcServices.apply()
    }

    fun keepInTray(on: Boolean) {
        trayChoice = if (on) 1 else 0
        Repo.putInt("pc_close_tray", trayChoice)
    }
}

/** Keeps reading Falcon BMS and the HTTP server running as the settings say. */
object PcServices {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "pc-services").apply { isDaemon = true } }

    fun apply() {
        executor.execute {
            runCatching {
                if (PcConfig.readsBms) {
                    Bridge.start()
                    if (MissionLink.mode.value != LinkMode.LOCAL) MissionLink.useThisPc()
                } else {
                    Bridge.stop()
                    if (MissionLink.mode.value != LinkMode.REMOTE) {
                        // a client: reconnect to the BMS PC used last time, if any
                        val saved = Repo.getString("bridge_host")
                        if (saved != null) MissionLink.setBridge(saved, Repo.getInt("bridge_port", 47474)) else MissionLink.forget()
                    }
                }
                // Android devices and client PCs need the server whenever BMS is read here; browsers whenever browser access is on
                if (PcConfig.readsBms || PcConfig.webEnabled) PcServer.start(serverPort()) else PcServer.stop()
            }
        }
    }

    /** Port changed in the settings: restart the server on it. */
    fun restartServer() {
        executor.execute {
            PcServer.stop()
            if (PcConfig.readsBms || PcConfig.webEnabled) PcServer.start(serverPort())
        }
    }
}

/** The network port from the settings (env BMSC_PORT overrides it for development, without saving). */
fun serverPort(): Int = System.getenv("BMSC_PORT")?.toIntOrNull() ?: Bridge.settings.value.Port
