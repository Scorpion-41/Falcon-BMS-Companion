package com.bmscompanion.app.ui

import android.net.Uri
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.HOME, "Home", Icons.Default.Home),
    Tab(Routes.MISSION, "Mission", Icons.Default.MyLocation),
    Tab(Routes.ARSENAL, "Arsenal", Icons.Default.RocketLaunch),
    Tab(Routes.THREATS, "Threats", Icons.Default.Radar),
    Tab(Routes.AIRPORTS, "Airfields", Icons.Default.FlightLand),
    Tab(Routes.COCKPIT, "Cockpit", Icons.Default.SportsEsports),
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
fun AppRoot(startRoute: String? = null) {
    val nav = rememberNavController()
    androidx.compose.runtime.LaunchedEffect(startRoute) { if (startRoute != null) nav.navigate(startRoute) }
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route ?: Routes.HOME
    val head = route.substringBefore('/').substringBefore('?')
    val currentTab = tabs.firstOrNull { it.route == head }?.route ?: tabOwner[head] ?: Routes.HOME
    val immersive = head == "bullseye" || head == "chart"
    val rail = isMedium() && !immersive

    val onTab: (String) -> Unit = { r ->
        if (r == Routes.HOME) {
            // Home is the start destination: tab switches save their stacks under it, so a normal
            // navigate(restoreState) would just bring the previous section back. Pop straight to Home instead.
            if (!nav.popBackStack(Routes.HOME, inclusive = false)) {
                nav.navigate(Routes.HOME) { launchSingleTop = true }
            }
            nav.clearBackStack(Routes.HOME)
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

    CompositionLocalProvider(LocalContentColor provides Hud.Text) {
    Row(Modifier.fillMaxSize().background(Hud.Bg)) {
        if (rail) {
            NavigationRail(
                containerColor = Hud.Surface,
                modifier = Modifier.fillMaxHeight().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Start + WindowInsetsSides.Vertical)),
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
            Box(Modifier.width(1.dp).fillMaxHeight().background(Hud.Outline.copy(alpha = 0.5f)))
        }
        Column(Modifier.weight(1f)) {
            Box(
                Modifier.weight(1f).windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Top))
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(if (rail || immersive) WindowInsetsSides.End + WindowInsetsSides.Bottom + (if (immersive) WindowInsetsSides.Start else WindowInsetsSides.End) else WindowInsetsSides.Horizontal)),
            ) {
                NavHost(
                    navController = nav,
                    startDestination = Routes.HOME,
                    enterTransition = { fadeIn() },
                    exitTransition = { fadeOut() },
                ) {
                    composable(Routes.HOME) { HomeScreen(nav) }
                    composable(Routes.ARSENAL) { ArsenalScreen(nav) }
                    composable(Routes.THREATS) { ThreatsScreen(nav) }
                    composable(Routes.AIRPORTS) { AirportsScreen(nav) }
                    composable(Routes.COCKPIT) { CockpitHubScreen(nav) }
                    composable(Routes.MISSION) { com.bmscompanion.app.ui.screens.mission.MissionScreen(nav) }
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
                    composable("chart?file={file}&title={title}", arguments = listOf(navArgument("file") { defaultValue = "" }, navArgument("title") { defaultValue = "" })) {
                        com.bmscompanion.app.ui.screens.ChartViewerScreen(nav, it.arguments?.getString("file").orEmpty(), it.arguments?.getString("title").orEmpty())
                    }
                    composable("search?q={q}", arguments = listOf(navArgument("q") { defaultValue = "" })) {
                        SearchScreen(nav, it.arguments?.getString("q").orEmpty())
                    }
                }
            }
            if (!rail && !immersive) {
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
