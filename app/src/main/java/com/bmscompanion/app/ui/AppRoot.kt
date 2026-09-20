package com.bmscompanion.app.ui

import android.net.Uri
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.delay
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.FlightLand
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.Icon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.Density
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.bmscompanion.app.ui.components.isMedium
import com.bmscompanion.app.ui.screens.AboutScreen
import com.bmscompanion.app.ui.screens.AircraftDetailRoute
import com.bmscompanion.app.ui.screens.AirportDetailRoute
import com.bmscompanion.app.ui.screens.AirportsScreen
import com.bmscompanion.app.ui.screens.ArsenalScreen
import com.bmscompanion.app.ui.screens.BullseyeScreen
import com.bmscompanion.app.ui.screens.ChecklistScreen
import com.bmscompanion.app.ui.screens.ChecklistsScreen
import com.bmscompanion.app.ui.screens.CockpitHubScreen
import com.bmscompanion.app.ui.screens.CommsScreen
import com.bmscompanion.app.ui.screens.EncyDetailRoute
import com.bmscompanion.app.ui.screens.EncyclopediaScreen
import com.bmscompanion.app.ui.screens.FavoritesScreen
import com.bmscompanion.app.ui.screens.HarmRwrScreen
import com.bmscompanion.app.ui.screens.HomeScreen
import com.bmscompanion.app.ui.screens.HotasScreen
import com.bmscompanion.app.ui.screens.SearchScreen
import com.bmscompanion.app.ui.screens.ThreatDetailRoute
import com.bmscompanion.app.ui.screens.ThreatsScreen
import com.bmscompanion.app.ui.screens.ToolsScreen
import com.bmscompanion.app.ui.screens.WeaponDetailRoute
import com.bmscompanion.app.ui.theme.Hud

object Routes {
    const val HOME = "home"
    const val ARSENAL = "arsenal"
    const val THREATS = "threats"
    const val AIRPORTS = "airports"
    const val COCKPIT = "cockpit"
    const val MISSION = "mission"
    const val MEDIA = "media"
    fun aircraft(key: String) = "aircraft/${Uri.encode(key)}"
    fun weapon(key: String) = "weapon/${Uri.encode(key)}"
    fun threat(id: String) = "threat/${Uri.encode(id)}"
    fun airport(theater: String, id: Int) = "airport/${Uri.encode(theater)}/$id"
    fun ency(key: String) = "ency/${Uri.encode(key)}"
    fun hotas(ac: String) = "hotas/$ac"
    fun checklist(id: String) = "checklist/${Uri.encode(id)}"
    fun search(q: String = "") = "search?q=${Uri.encode(q)}"
    const val CHECKLISTS = "checklists"
    const val COMMS = "comms"
    const val HARM = "harm"
    const val ENCY = "encyclopedia"
    const val BULLSEYE = "bullseye"
    const val TOOLS = "tools"
    const val FAVORITES = "favorites"
    const val ABOUT = "about"
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.HOME, "Home", Icons.Default.Home),
    Tab(Routes.MISSION, "Mission", Icons.Default.MyLocation),
    Tab(Routes.ARSENAL, "Arsenal", Icons.Default.RocketLaunch),
    Tab(Routes.THREATS, "Threats", Icons.Default.Radar),
    Tab(Routes.AIRPORTS, "Airfields", Icons.Default.FlightLand),
    Tab(Routes.COCKPIT, "Cockpit", Icons.Default.SportsEsports),
    Tab(Routes.MEDIA, "Media", Icons.Default.PhotoLibrary),
)

private val tabOwner = mapOf(
    "aircraft" to Routes.ARSENAL, "weapon" to Routes.ARSENAL,
    "threat" to Routes.THREATS, "harm" to Routes.THREATS, "encyclopedia" to Routes.THREATS, "ency" to Routes.THREATS,
    "airport" to Routes.AIRPORTS,
    "hotas" to Routes.COCKPIT, "checklists" to Routes.COCKPIT, "checklist" to Routes.COCKPIT, "comms" to Routes.COCKPIT,
    "bullseye" to Routes.COCKPIT, "tools" to Routes.COCKPIT,
    // "m/..." = reference pages opened from the Mission section; they stay under the Mission tab
    "m" to Routes.MISSION,
)

fun NavHostController.go(route: String) = navigate(route) { launchSingleTop = true }

@Composable
fun AppRoot(startRoute: String? = null, nav: NavHostController = rememberNavController()) {
    androidx.compose.runtime.LaunchedEffect(startRoute) { if (startRoute != null) nav.navigate(startRoute) }
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route ?: Routes.HOME
    val head = route.substringBefore('/').substringBefore('?')
    val currentTab = tabs.firstOrNull { it.route == head }?.route ?: tabOwner[head] ?: Routes.HOME
    val immersive = head == "bullseye" || head == "chart" || route.startsWith("media/view")
    // A screen that took the keyboard (chart viewer, bullseye) can leave focus on a composable that is gone,
    // and then a search field cannot be clicked into. Clearing focus on every navigation keeps the fields clickable.
    val focusManager = LocalFocusManager.current
    LaunchedEffect(route) { focusManager.clearFocus(force = true) }
    val knee = Kneeboard.on
    val rail = isMedium() && !immersive && !knee

    val onTab: (String) -> Unit = { r ->
        if (r == Routes.HOME) {
            // Home is the start destination, so this pops the sections off and shows it. It is one navigate and no
            // more: popBackStack + clearBackStack took entries out from under the NavHost while it was still showing
            // them, and navigation-compose then asked a destroyed entry for its ViewModel store. restoreState is off
            // here on purpose — the stack saved under Home is whatever section was open, and restoring it would put
            // that section straight back instead of showing Home.
            nav.navigate(Routes.HOME) {
                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
            }
        } else if (r == currentTab) {
            // Re-selecting the current section returns to its main page.
            if (head != r && !nav.popBackStack(r, inclusive = false)) {
                nav.navigate(r) {
                    popUpTo(nav.graph.findStartDestination().id) { saveState = false }
                    launchSingleTop = true
                }
            }
        } else {
            nav.navigate(r) {
                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    // What the kneeboard's ☰ offers: the reference sections. The Home hub only leads to them, and nobody browses
    // screenshots from the cockpit, so both are left out.
    val kneeSections = tabs.filter { it.route != Routes.HOME && it.route != Routes.MEDIA }
        .map { t -> KneeboardSection(t.route, t.label, t.icon) { onTab(t.route) } }

    // The sections, as pages the VR program can flip between, and the flipping followed back into the app. Both are
    // no-ops outside a VR board: publishPages is only set where the program offers the API.
    // Only the board with the menu on it publishes the sections as pages; a numbered board publishes its own.
    if (knee && Kneeboard.slot == null) {
        val sections = rememberUpdatedState(kneeSections)
        val shape = Kneeboard.shapes[Kneeboard.shape]
        LaunchedEffect(shape, kneeSections.size) {
            // board 0: the one with the menu on it, whose pages are the sections rather than a board's sheets
            Kneeboard.publishPages?.invoke(0, sections.value.size, shape.w, shape.h)
        }
        LaunchedEffect(Unit) {
            while (true) {
                delay(200)
                val i = Kneeboard.takePage?.invoke() ?: -1
                if (i >= 0) sections.value.getOrNull(i)?.go?.invoke()
            }
        }
    }

    val density = LocalDensity.current
    // How wide the board really is, measured before anything is scaled. On a board the page is then laid out at a
    // few hundred points however many pixels that is (Kneeboard.densityFor), which is what makes the print readable
    // through a headset; everywhere else the platform's own density is used untouched.
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val boardPx = with(density) { maxWidth.toPx() }
    CompositionLocalProvider(
        LocalContentColor provides Hud.Text,
        LocalDensity provides if (knee) Density(Kneeboard.densityFor(boardPx, density.density), density.fontScale) else density,
    ) {
    Row(Modifier.fillMaxSize().background(Hud.Bg)) {
        if (rail) {
            NavigationRail(
                containerColor = Hud.Surface,
                modifier = Modifier.fillMaxHeight().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Start + WindowInsetsSides.Vertical)),
            ) {
                // Centred, not stacked from the top. Top-aligned, the first section sits in the very corner of the
                // screen — the hardest place on a display to hit with a mouse and the easiest to miss with a thumb —
                // and the rail is mostly empty below it. Centring puts every section within reach of the middle.
                // It scrolls rather than squashes on a short window, where the sections need more height than there is.
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                tabs.forEach { t ->
                    NavigationRailItem(
                        selected = currentTab == t.route,
                        onClick = { onTab(t.route) },
                        icon = { Icon(t.icon, t.label) },
                        label = { Text(t.label) },
                        colors = NavigationRailItemDefaults.colors(
                            selectedIconColor = Hud.Bg, indicatorColor = Hud.Amber,
                            selectedTextColor = Hud.Amber, unselectedIconColor = Hud.TextDim, unselectedTextColor = Hud.TextDim,
                        ),
                    )
                }
                }
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(Hud.Outline.copy(alpha = 0.5f)))
        }
        Column(Modifier.weight(1f)) {
            Box(
                Modifier.weight(1f).windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Top))
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(if (rail || immersive) WindowInsetsSides.End + WindowInsetsSides.Bottom + (if (immersive) WindowInsetsSides.Start else WindowInsetsSides.End) else WindowInsetsSides.Horizontal)),
            ) {
              // On a kneeboard the page gets the whole board: the ☰ sections button and the page's own ⋯ options
              // float over it and fade out with the mouse. Everywhere else this just draws the page.
              // A numbered board has no chrome at all: the ☰ is a button, and a board has nothing to press it with.
              KneeboardFrame(enabled = knee, chrome = !immersive && Kneeboard.slot == null, sections = kneeSections, current = currentTab) {
                val slot = Kneeboard.slot
                if (slot != null) {
                    com.bmscompanion.app.ui.board.VrBoardScreen(nav, slot)
                    return@KneeboardFrame
                }
                NavHost(
                    navController = nav,
                    startDestination = Routes.HOME,
                    // No transitions between sections, and that is deliberate. While one fades out, AnimatedContent
                    // goes on composing the page that has just been popped, and navigation-compose then asks that
                    // entry for its ViewModel store: "You cannot access the NavBackStackEntry's ViewModels until it is
                    // added to the NavController's back stack" — an error dialog on the PC, a dead page in a browser,
                    // for anyone who clicked through the sections at a normal pace. Switching instantly cannot race.
                    enterTransition = { EnterTransition.None },
                    exitTransition = { ExitTransition.None },
                    popEnterTransition = { EnterTransition.None },
                    popExitTransition = { ExitTransition.None },
                ) {
                    composable(Routes.HOME) { HomeScreen(nav) }
                    composable(Routes.ARSENAL) { ArsenalScreen(nav) }
                    composable(Routes.THREATS) { ThreatsScreen(nav) }
                    composable(Routes.AIRPORTS) { AirportsScreen(nav) }
                    composable(Routes.COCKPIT) { CockpitHubScreen(nav) }
                    composable(Routes.MISSION) { com.bmscompanion.app.ui.screens.mission.MissionScreen(nav) }
                    composable(Routes.MEDIA) { com.bmscompanion.app.ui.screens.MediaScreen(nav) }
                    composable("media/view?name={name}", arguments = listOf(navArgument("name") { defaultValue = "" })) {
                        com.bmscompanion.app.ui.screens.MediaViewerScreen(nav, it.arguments?.getString("name").orEmpty())
                    }
                    composable(
                        "m/airport/{theater}/{id}",
                        arguments = listOf(navArgument("id") { type = NavType.IntType }),
                    ) { AirportDetailRoute(nav, it.arguments?.getString("theater").orEmpty(), it.arguments?.getInt("id") ?: 0) }
                    composable("m/weapon/{key}") { WeaponDetailRoute(nav, it.arguments?.getString("key").orEmpty()) }
                    composable("m/threat/{id}") { ThreatDetailRoute(nav, it.arguments?.getString("id").orEmpty()) }
                    composable("aircraft/{key}") { AircraftDetailRoute(nav, it.arguments?.getString("key").orEmpty()) }
                    composable("weapon/{key}") { WeaponDetailRoute(nav, it.arguments?.getString("key").orEmpty()) }
                    composable("threat/{id}") { ThreatDetailRoute(nav, it.arguments?.getString("id").orEmpty()) }
                    composable("ency/{key}") { EncyDetailRoute(nav, it.arguments?.getString("key").orEmpty()) }
                    composable(
                        "airport/{theater}/{id}",
                        arguments = listOf(navArgument("id") { type = NavType.IntType }),
                    ) { AirportDetailRoute(nav, it.arguments?.getString("theater").orEmpty(), it.arguments?.getInt("id") ?: 0) }
                    composable("hotas/{ac}") { HotasScreen(nav, it.arguments?.getString("ac") ?: "f16") }
                    composable(Routes.CHECKLISTS) { ChecklistsScreen(nav) }
                    composable("checklist/{id}") { ChecklistScreen(nav, it.arguments?.getString("id").orEmpty()) }
                    composable(Routes.COMMS) { CommsScreen(nav) }
                    composable(Routes.HARM) { HarmRwrScreen(nav) }
                    composable(Routes.ENCY) { EncyclopediaScreen(nav) }
                    composable(Routes.BULLSEYE) { BullseyeScreen(nav) }
                    composable(Routes.TOOLS) { ToolsScreen(nav) }
                    composable(Routes.FAVORITES) { FavoritesScreen(nav) }
                    composable(Routes.ABOUT) { AboutScreen(nav) }
                    composable(
                        "chart?file={file}&title={title}&pages={pages}&start={start}&set={set}&id={id}",
                        arguments = listOf(
                            navArgument("file") { defaultValue = "" },
                            navArgument("title") { defaultValue = "" },
                            navArgument("pages") { defaultValue = "0" },
                            navArgument("start") { defaultValue = "1" },
                            navArgument("set") { defaultValue = "" },
                            navArgument("id") { defaultValue = "0" },
                        ),
                    ) {
                        com.bmscompanion.app.ui.screens.ChartViewerScreen(
                            nav,
                            it.arguments?.getString("file").orEmpty(),
                            it.arguments?.getString("title").orEmpty(),
                            it.arguments?.getString("pages")?.toIntOrNull() ?: 0,
                            it.arguments?.getString("start")?.toIntOrNull() ?: 1,
                            it.arguments?.getString("set").orEmpty(),
                            it.arguments?.getString("id")?.toIntOrNull() ?: 0,
                        )
                    }
                    composable("search?q={q}", arguments = listOf(navArgument("q") { defaultValue = "" })) {
                        SearchScreen(nav, it.arguments?.getString("q").orEmpty())
                    }
                }
              }
            }
            if (!rail && !immersive && !knee) {
                NavigationBar(containerColor = Hud.Surface, tonalElevation = 0.dp) {
                    tabs.forEach { t ->
                        NavigationBarItem(
                            selected = currentTab == t.route,
                            onClick = { onTab(t.route) },
                            icon = { Icon(t.icon, t.label) },
                            label = { Text(t.label, maxLines = 1, softWrap = false, fontSize = 11.sp, letterSpacing = 0.sp) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Hud.Bg, indicatorColor = Hud.Amber,
                                selectedTextColor = Hud.Amber, unselectedIconColor = Hud.TextDim, unselectedTextColor = Hud.TextDim,
                            ),
                        )
                    }
                }
            }
        }
    }
}
}
}
