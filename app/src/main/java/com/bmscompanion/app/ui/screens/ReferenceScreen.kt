package com.bmscompanion.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlightLand
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.ui.theme.Hud

/**
 * Airfields, the theater map, Threats and Arsenal, under one section.
 *
 * All four are the same kind of thing — the reference a pilot looks something up in, none of it live and none of
 * it about the flight in progress — and giving each its own place in the navigation rail made that rail long
 * enough to crowd out the sections that do change. One entry, four pages.
 *
 * The four are drawn as buttons, not as a quiet row of labels: pilots did not notice there was more than one page
 * here. Airfields comes first and is where the section opens. The theater map is a page of its own rather than a
 * sub-tab of Airfields (Airfields keeps Navaids and Radio), and the theater picker sits right beside the four,
 * because it decides what Airfields and the map show, and in the corner of a title bar nobody found it.
 *
 * Each page is still reachable on its own: `arsenal`, `threats` and `airports` open this section on that page, the
 * detail routes (an aircraft, a threat, an airfield) are unchanged, and [openAt] is what a link elsewhere uses to
 * land on the right page.
 */
enum class ReferenceTab(val id: String, val label: String, val icon: ImageVector) {
    AIRFIELDS("airfields", "Airfields", Icons.Default.FlightLand),
    MAP("map", "Map", Icons.Default.Map),
    THREATS("threats", "Threats", Icons.Default.Radar),
    ARSENAL("arsenal", "Arsenal", Icons.Default.RocketLaunch),
}

/** Which page the section opens on next time it is entered, so a deep link lands where it meant to. */
object ReferenceEntry {
    var pending: String? = null
}

/** Opens the Reference section on one of its pages. */
fun openAt(tab: ReferenceTab) { ReferenceEntry.pending = tab.id }

@Composable
fun ReferenceScreen(nav: NavHostController, initial: ReferenceTab = ReferenceTab.AIRFIELDS) {
    var tab by rememberSaveable { mutableStateOf(initial.id) }
    ReferenceEntry.pending?.let { tab = it; ReferenceEntry.pending = null }
    val current = ReferenceTab.entries.firstOrNull { it.id == tab } ?: ReferenceTab.AIRFIELDS

    Column(Modifier.fillMaxSize()) {
        ReferenceBar(current, Repo.selectedTheater.value, onTab = { tab = it.id }, onTheater = { Repo.setTheater(it) })
        Box(Modifier.weight(1f)) {
            when (current) {
                ReferenceTab.AIRFIELDS -> AirfieldsPage(nav)
                ReferenceTab.MAP -> AirfieldsMapPage(nav)
                ReferenceTab.THREATS -> ThreatsPage(nav)
                ReferenceTab.ARSENAL -> ArsenalPage(nav)
            }
        }
    }
}

/**
 * The four pages and the theater, together at the top of the section.
 *
 * Wide enough (a tablet, the PC window): one row, the four as large buttons and the theater picker straight after
 * them. Narrower: the four share the width evenly (icon over label on a phone) and the theater picker takes the
 * line under them, so nothing is ever scrolled out of sight.
 */
@Composable
private fun ReferenceBar(current: ReferenceTab, theaterId: String, onTab: (ReferenceTab) -> Unit, onTheater: (String) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().background(Hud.Surface)) {
        val oneRow = maxWidth >= 720.dp
        val stacked = maxWidth < 440.dp
        Column(Modifier.fillMaxWidth()) {
            if (oneRow) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ReferenceTab.entries.forEach { t -> ReferenceTabButton(t, t == current, stacked = false, Modifier) { onTab(t) } }
                    Spacer(Modifier.width(6.dp))
                    Box(Modifier.width(1.dp).height(34.dp).background(Hud.Outline))
                    Spacer(Modifier.width(6.dp))
                    TheaterPicker(theaterId, prominent = true, onPick = onTheater)
                }
            } else {
                Row(
                    Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(if (stacked) 6.dp else 8.dp),
                ) {
                    ReferenceTab.entries.forEach { t -> ReferenceTabButton(t, t == current, stacked, Modifier.weight(1f)) { onTab(t) } }
                }
                Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                    TheaterPicker(theaterId, Modifier.fillMaxWidth(), prominent = true, fill = true, onPick = onTheater)
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Hud.Outline.copy(alpha = 0.7f)))
        }
    }
}

/** One of the four: a button that looks like one, filled and outlined in amber while it is the page on show. */
@Composable
private fun ReferenceTabButton(t: ReferenceTab, on: Boolean, stacked: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val ink = if (on) Hud.Amber else Hud.Text
    val iconInk = if (on) Hud.Amber else Hud.TextDim
    val weight = if (on) FontWeight.Bold else FontWeight.SemiBold
    val base = modifier
        .clip(shape)
        .background(if (on) Hud.Amber.copy(alpha = 0.18f) else Hud.Surface2)
        .border(if (on) 1.5.dp else 1.dp, if (on) Hud.Amber.copy(alpha = 0.9f) else Hud.Outline, shape)
        .semantics { selected = on }
        .clickable(role = Role.Tab, onClick = onClick)
    if (stacked) {
        Column(base.padding(vertical = 8.dp, horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(t.icon, null, tint = iconInk, modifier = Modifier.size(22.dp))
            Spacer(Modifier.height(3.dp))
            Text(t.label, color = ink, fontSize = 13.sp, fontWeight = weight, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    } else {
        Row(
            base.heightIn(min = 46.dp).padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(t.icon, null, tint = iconInk, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(t.label, color = ink, fontSize = 15.sp, fontWeight = weight, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
