package com.bmscompanion.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.ui.AppRoot
import com.bmscompanion.app.ui.theme.BmsTheme
import com.bmscompanion.app.ui.theme.Hud
import com.bmscompanion.desktop.bridge.SelfTest
import com.bmscompanion.desktop.ui.ServerScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlin.system.exitProcess

/**
 * BMS Companion for Windows: one program with the app, the part that reads Falcon BMS, and the server for phones, tablets,
 * browsers and client PCs. One window that is either the light server page or the full app, plus a tray icon.
 *
 * Arguments: `--tray` starts without a window (used by "Start with Windows"); `--route <route>` (or env BMSC_ROUTE) opens a
 * route in the app. Developer checks: `--selftest out.txt`, `--dumpstrings out.txt`, `--eztest <EZBoards copy> out.txt`.
 */
fun main(args: Array<String>) {
    Repo.init()
    // the VR board page is the PC's own: it exists only where there is a mouse to set the boards up with
    com.bmscompanion.app.ui.screens.mission.vrBoardsPane = { com.bmscompanion.app.ui.screens.mission.MissionVrBoardsPane() }
    com.bmscompanion.app.ui.railTop = railFullScreen
    com.bmscompanion.app.ui.railBottom = railServerPage
    PcLog.install() // there is no console here: a failure that reaches nobody goes to the log in the settings folder
    if (SelfTest.run(args)) exitProcess(0)
    val trayStart = "--tray" in args
    // one copy per user: a second start shows the running one (a start with Windows just leaves it alone)
    if (!SingleInstance.acquire(quiet = trayStart) { ShowRequests.value++ }) exitProcess(0)

    // Once per version, and only now that this is known to be the only copy running: what the version being
    // replaced left behind is thrown out (its cached installer, its stale lock, its rendered pages), while
    // everything the pilot set up — the Dashboard layouts, the VR boards, the folders — is left exactly as it is.
    Housekeeping.sweep()

    com.bmscompanion.app.data.Platform.imageActions = pcImageActions(PcConfig.readsBms)
    com.bmscompanion.app.data.Platform.openUrl = { SystemTools.openUrl(it) }
    com.bmscompanion.app.data.Platform.fetchText = { url -> fetchTextFromWeb(url) }
    com.bmscompanion.app.data.Platform.installer = PcInstaller
    com.bmscompanion.app.data.Platform.nowMillis = { System.currentTimeMillis() }
    // an installer left behind by an update that has already happened is a few hundred megabytes of nothing
    com.bmscompanion.app.data.update.Updates.tidyCache()
    runCatching { javax.swing.UIManager.setLookAndFeel(javax.swing.UIManager.getSystemLookAndFeelClassName()) } // native folder dialogs
    val startRoute = args.indexOf("--route").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) } ?: System.getenv("BMSC_ROUTE")
    PcServices.apply()

    application {
        var windowOpen by remember { mutableStateOf(!trayStart) }
        val showRequest by ShowRequests.collectAsState()
        LaunchedEffect(showRequest) { if (showRequest > 0) windowOpen = true }
        val tray = rememberTrayState()
        var trayNoticeShown by remember { mutableStateOf(false) }

        fun closeWindow() {
            if (PcConfig.closeToTray) {
                windowOpen = false
                if (!trayNoticeShown) {
                    trayNoticeShown = true
                    tray.sendNotification(Notification("BMS Companion is still running", "It keeps serving your devices. Use the tray icon to open it or exit.", Notification.Type.Info))
                }
            } else exitApplication()
        }

        Tray(
            icon = rememberVectorPainter(AppIcon),
            state = tray,
            tooltip = "BMS Companion",
            onAction = { windowOpen = true; ShowRequests.value++ },
            menu = {
                Item("Open BMS Companion", onClick = { windowOpen = true; ShowRequests.value++ })
                Separator()
                CheckboxItem("Server page", checked = PcConfig.mode == PcMode.SERVER, onCheckedChange = { PcConfig.switchTo(PcMode.SERVER); windowOpen = true; ShowRequests.value++ })
                CheckboxItem("Full app", checked = PcConfig.mode == PcMode.APP, onCheckedChange = { PcConfig.switchTo(PcMode.APP); windowOpen = true; ShowRequests.value++ })
                Separator()
                CheckboxItem("Browser access", checked = PcConfig.webEnabled, onCheckedChange = { PcConfig.setWeb(it) })
                Separator()
                Item("Exit", onClick = ::exitApplication)
            },
        )

        // kept outside the window so leaving or entering full screen (which recreates the window) keeps the app's place
        val nav = rememberNavController()
        val owner = remember { WindowOwner() }
        if (windowOpen) MainWindow(startRoute, nav, owner, showRequest, onClose = ::closeWindow)
    }
}

/** Incremented to bring the window to the front (second start, tray icon). */
private val ShowRequests = MutableStateFlow(0)

/** Lifecycle and view models of the app, shared by the window before and after switching full screen. */
private class WindowOwner : LifecycleOwner, ViewModelStoreOwner {
    private val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
    override val lifecycle: Lifecycle get() = registry
    override val viewModelStore = ViewModelStore()
}

@Composable
private fun ApplicationScope.MainWindow(startRoute: String?, nav: NavHostController, owner: WindowOwner, showRequest: Int, onClose: () -> Unit) {
    val normalState = rememberWindowState(
        placement = if (Repo.getInt("pc_win_max", 0) == 1) WindowPlacement.Maximized else WindowPlacement.Floating,
        position = Repo.getInt("pc_win_x", Int.MIN_VALUE).let { x ->
            if (x == Int.MIN_VALUE) WindowPosition(Alignment.Center) else WindowPosition(x.dp, Repo.getInt("pc_win_y", 0).dp)
        },
        size = DpSize(Repo.getInt("pc_win_w", 1360).dp, Repo.getInt("pc_win_h", 880).dp),
    )
    var fullscreenBounds by remember { mutableStateOf<java.awt.Rectangle?>(null) }
    var fullscreen by remember { mutableStateOf(false) }

    key(fullscreen) {
        val fsBounds = fullscreenBounds
        val state = if (fullscreen && fsBounds != null) {
            rememberWindowState(placement = WindowPlacement.Floating, position = WindowPosition(fsBounds.x.dp, fsBounds.y.dp), size = DpSize(fsBounds.width.dp, fsBounds.height.dp))
        } else normalState
        var windowRef by remember { mutableStateOf<java.awt.Window?>(null) }

        fun toggleFullscreen() {
            if (!fullscreen) fullscreenBounds = windowRef?.graphicsConfiguration?.bounds ?: return
            fullscreen = !fullscreen
        }

        Window(
            onCloseRequest = onClose,
            state = state,
            title = "BMS Companion",
            icon = rememberVectorPainter(AppIcon),
            undecorated = fullscreen,
            resizable = !fullscreen,
            alwaysOnTop = WindowControls.alwaysOnTop,
            onPreviewKeyEvent = { e ->
                when {
                    e.type == KeyEventType.KeyDown && e.key == Key.F11 -> { toggleFullscreen(); true }
                    e.type == KeyEventType.KeyDown && e.key == Key.Escape && fullscreen -> { toggleFullscreen(); true }
                    else -> UiScale.handleKey(e)
                }
            },
        ) {
            LaunchedEffect(Unit) {
                windowRef = window
                window.minimumSize = java.awt.Dimension(560, 600)
                if (!fullscreen) DarkTitleBar.apply(window)
            }
            LaunchedEffect(showRequest) {
                if (showRequest == 0) return@LaunchedEffect
                if (state.isMinimized) state.isMinimized = false
                // Windows only lets a background program take focus briefly on top
                window.isAlwaysOnTop = true
                window.toFront()
                window.requestFocus()
                window.isAlwaysOnTop = WindowControls.alwaysOnTop
            }
            if (!fullscreen) LaunchedEffect(state) {
                // remember size, position and maximized state (debounced while the user drags)
                snapshotFlow { Triple(state.placement, state.position, state.size) }.debounce(500).collect { (placement, pos, size) ->
                    Repo.putInt("pc_win_max", if (placement == WindowPlacement.Maximized) 1 else 0)
                    if (placement == WindowPlacement.Floating) {
                        if (pos is WindowPosition.Absolute) { Repo.putInt("pc_win_x", pos.x.value.toInt()); Repo.putInt("pc_win_y", pos.y.value.toInt()) }
                        Repo.putInt("pc_win_w", size.width.value.toInt()); Repo.putInt("pc_win_h", size.height.value.toInt())
                    }
                }
            }
            var pendingRoute by remember { mutableStateOf<String?>(null) }
            val actions = remember(fullscreen) {
                PcActions(switchToServer = { PcConfig.switchTo(PcMode.SERVER) }, toggleFullscreen = ::toggleFullscreen, isFullscreen = { fullscreen })
            }
            CompositionLocalProvider(
                LocalLifecycleOwner provides owner,
                LocalViewModelStoreOwner provides owner,
                LocalPcActions provides actions,
                // follows where Falcon BMS runs (download on a client, open the folder with BMS here)
                com.bmscompanion.app.data.LocalImageActions provides pcImageActions(PcConfig.readsBms),
            ) {
                Scaled {
                    when (PcConfig.mode) {
                        PcMode.SERVER -> ServerScreen(
                            onOpenApp = { PcConfig.switchTo(PcMode.APP) },
                            // "What's new" on the update card: the full app, opened on About, where the release notes are
                            onOpenAbout = { pendingRoute = "about"; PcConfig.switchTo(PcMode.APP) },
                            // the app's navigation graph only exists in APP mode, so the route is opened once it is composed
                            onUseAsClient = { PcConfig.useBmsOnThisPc(false); pendingRoute = "mission"; PcConfig.switchTo(PcMode.APP) },
                            onHide = onClose,
                        )
                        PcMode.APP -> AppMode(startRoute, nav, pendingRoute) { pendingRoute = null }
                    }
                }
            }
        }
    }
}

/** The full app, with the window's own buttons at the bottom of the navigation rail. */
@Composable
private fun AppMode(startRoute: String?, nav: NavHostController, pendingRoute: String? = null, onRouteOpened: () -> Unit = {}) {
    val actions = LocalPcActions.current
    // a route asked for while the server page was showing (e.g. "use this PC as a client"): open it now that AppRoot set the graph
    LaunchedEffect(pendingRoute) {
        val r = pendingRoute ?: return@LaunchedEffect
        runCatching { nav.navigate(r) { launchSingleTop = true } }.onFailure { println("navigate $r failed: $it") }
        onRouteOpened()
    }
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route.orEmpty()
    val immersive = route.startsWith("bullseye") || route.startsWith("chart") || route.startsWith("media/view")
    BoxWithConstraints(Modifier.fillMaxSize()) {
        AppRoot(startRoute, nav)
    }
}

/**
 * The window's own buttons, which live in the navigation rail: full screen at the top, the server page at the foot.
 *
 * Set once, at startup, and never reassigned — they read what they need themselves, so the rail has nothing to
 * subscribe to and nothing to rebuild. Stacked in the bottom corner they used to sit right under the last section
 * and read as two more sections; at the two ends of the rail, with a gap either side, they read as what they are.
 */
private val railFullScreen: @Composable () -> Unit = {
    val actions = LocalPcActions.current
    RailButton(
        if (actions.isFullscreen()) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
        if (actions.isFullscreen()) "Window" else "Full screen",
        actions.toggleFullscreen,
    )
}

private val railServerPage: @Composable () -> Unit = {
    val actions = LocalPcActions.current
    RailButton(Icons.Default.Dns, "Server", actions.switchToServer)
}

@Composable
private fun RailButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        Modifier.padding(vertical = 3.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, label, tint = Hud.TextDim, modifier = Modifier.size(22.dp))
        Text(label, fontSize = 11.sp, color = Hud.TextDim, maxLines = 1)
    }
}

/** Applies the UI zoom and tells the shared screens the window width in dp (phone or tablet layouts, as on Android). */
@Composable
private fun Scaled(content: @Composable () -> Unit) {
    val base = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(base.density * UiScale.factor, base.fontScale)) {
        BoxWithConstraints(Modifier.fillMaxSize().background(Hud.Bg)) {
            CompositionLocalProvider(LocalConfiguration provides Configuration(maxWidth.value.toInt(), maxHeight.value.toInt())) {
                BmsTheme { content() }
            }
        }
    }
}

/** Keep-on-top toggle (pin button in the Mission header). */
object WindowControls {
    var alwaysOnTop by mutableStateOf(Repo.getInt("pc_on_top", 0) == 1)
        private set

    fun toggleOnTop() {
        alwaysOnTop = !alwaysOnTop
        Repo.putInt("pc_on_top", if (alwaysOnTop) 1 else 0)
    }
}

/** UI zoom for high-resolution or distant screens: Ctrl + / Ctrl - / Ctrl 0, remembered. */
object UiScale {
    private const val MIN = 70
    private const val MAX = 200
    private var percent by mutableIntStateOf(Repo.getInt("pc_ui_scale", 100).coerceIn(MIN, MAX))
    val factor: Float get() = percent / 100f

    fun handleKey(e: KeyEvent): Boolean {
        if (e.type != KeyEventType.KeyDown || !e.isCtrlPressed) return false
        val next = when (e.key) {
            Key.Equals, Key.Plus, Key.NumPadAdd -> percent + 10
            Key.Minus, Key.NumPadSubtract -> percent - 10
            Key.Zero, Key.NumPad0 -> 100
            else -> return false
        }.coerceIn(MIN, MAX)
        percent = next
        Repo.putInt("pc_ui_scale", next)
        return true
    }
}

/** The Android launcher icon (res/drawable/ic_launcher_fg.xml on its background colour) as window, tray and web icon. */
val AppIcon: ImageVector by lazy {
    val green = SolidColor(Color(0xFF5BE38A))
    ImageVector.Builder("BmsCompanion", 108.dp, 108.dp, 108f, 108f)
        .addPath(addPathNodes("M0,0 H108 V108 H0 Z"), fill = SolidColor(Color(0xFF0B1218)))
        .addPath(addPathNodes("M54,24 A30,30 0 1,1 53.99,24 Z"), stroke = green, strokeLineWidth = 2.2f)
        .addPath(addPathNodes("M54,44 A10,10 0 1,1 53.99,44 Z"), stroke = green, strokeLineWidth = 1.6f)
        .addPath(addPathNodes("M54,18 L54,26 M54,82 L54,90 M18,54 L26,54 M82,54 L90,54"), stroke = green, strokeLineWidth = 2.2f)
        .addPath(
            addPathNodes("M54,30 L57,44 L57,52 L72,62 L72,66 L57,62 L56.5,70 L62,75 L62,78 L54,76 L46,78 L46,75 L51.5,70 L51,62 L36,66 L36,62 L51,52 L51,44 Z"),
            fill = SolidColor(Color(0xFFFFB547)),
        )
        .build()
}
