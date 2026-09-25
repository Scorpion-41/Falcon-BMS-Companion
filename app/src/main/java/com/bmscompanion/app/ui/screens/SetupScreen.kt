package com.bmscompanion.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import com.bmscompanion.app.ui.Routes
import com.bmscompanion.app.ui.components.BmsTopBar
import com.bmscompanion.app.ui.go
import com.bmscompanion.app.ui.screens.mission.MissionSetupPane
import com.bmscompanion.app.ui.theme.Hud

/**
 * Setup: where Falcon BMS runs, how this device reaches it, and the guides for getting both ready.
 *
 * It was the last tab of the Mission section, which was the wrong place twice over. It is not part of a mission —
 * it is what you do once, before any mission — and being a tab of Mission meant a pilot whose device could not
 * reach the PC had to open the very section that was empty because of it to find out why. As a section of its own
 * it sits in the rail beside the others, visible from anywhere, including from a page that has nothing to show.
 *
 * The page itself is whatever the platform's `MissionSetupPane` is: the PC's has the BMS folder, the checklist and
 * the server settings on it, a tablet's has finding the PC and the guides.
 */
@Composable
fun SetupScreen(nav: NavHostController) {
    Column(Modifier.fillMaxSize().background(Hud.Bg)) {
        BmsTopBar("Setup", "BMS Companion, and the PC that reads Falcon BMS")
        // Connecting is the thing this page exists for, so when it succeeds the pilot goes where the data is.
        MissionSetupPane(onConnected = { nav.go(Routes.MISSION) })
    }
}
