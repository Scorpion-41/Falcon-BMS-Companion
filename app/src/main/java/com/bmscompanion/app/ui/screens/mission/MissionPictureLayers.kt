package com.bmscompanion.app.ui.screens.mission

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.bmscompanion.app.data.mission.MissionGround
import com.bmscompanion.app.data.mission.MissionPicture
import com.bmscompanion.app.data.wdp.DtcFromMission
import com.bmscompanion.app.ui.components.MapProjection
import com.bmscompanion.app.ui.screens.wdp.NameSink
import com.bmscompanion.app.ui.theme.Hud

// The threat sites of the mission picture ([MissionPicture]) that every map draws the same way: the Mission section's
// map and the VR map board ([LiveMap]) and the Planner's Map page (WdpMapPage), so the three look alike by default —
// the Planner's "known, not in a PPT" threat sites (the SAM red, a dashed ring and square). Only the mission's threats
// (the briefing's, else those along the route) and never one the side has not spotted.

/**
 * A system's reach in feet, the one rule every map rings an air defence by: the theater's `Ppt.ini` range where its
 * [table] (code, name, range ft) has the type, else the threat reference's (`maxRangeNm`, else typical); null when
 * neither knows it.
 */
fun siteRingFt(system: String, table: List<Triple<String, String, Double>>, reference: List<com.bmscompanion.app.data.Threat>): Double? {
    val typed = if (table.isEmpty()) null else runCatching {
        DtcFromMission.threatType(table, com.bmscompanion.app.ui.screens.wdp.DtcMissionFacts.siteOf(system, 0.0, 0.0, reference, ""))
    }.getOrNull()
    return typed?.third ?: threatGuideEntry(system, reference)?.let { it.numbers["maxRangeNm"] ?: it.numbers["typicalRangeNm"] }
        ?.takeIf { it > 0.0 }?.times(DtcFromMission.NM)
}

/** The mission's air defences and ships with their reach: the PC's ([MissionGround.rings]), else the threat reference's. */
fun missionSites(g: MissionGround?, reference: List<com.bmscompanion.app.data.Threat>): List<MissionPicture.Site> =
    MissionPicture.sites(g) { system -> g?.rings?.get(system) ?: siteRingFt(system, emptyList(), reference) }

/** The short name a site is labelled with: "SA-2" of "SA-2 (S-75)", the whole name otherwise. */
fun siteLabel(name: String): String {
    val first = name.trim().substringBefore(' ')
    return if (first.any { it.isDigit() } && first.length >= 3) first else name.trim()
}

/** The air-defence sites and ships of the picture: the Planner's threat ink, a dashed ring of each system's reach. */
internal fun DrawScope.drawMissionSites(
    pr: MapProjection, sites: List<MissionPicture.Site>, labels: Boolean, fill: Float, names: NameSink,
    route: List<Pair<Double, Double>> = emptyList(),
) {
    for (s in sites) {
        val edge = if (route.isEmpty()) null else MissionPicture.distanceToRoute(s.x, s.y, route) - (s.ringFt ?: 0.0)
        drawThreatSite(pr, DtcFromMission.Pt(s.x, s.y), s.ringFt, dashed = true, fill = fill, label = if (labels) siteLabel(s.system.ifBlank { s.name.orEmpty() }) else null, edgeFt = edge, names = names)
    }
}

/**
 * One air-defence site the mission knows of, on every map: the SAM red ([SamRed]), its system's reach ([ringFt]) as a
 * ring filled at [fill] × 0.8 — [dashed] while the cartridge does not hold it — and the launcher's square; [label]
 * written near the route ([edgeFt]: how far its ring is from the route) or once the map is close in ([siteNamed]).
 */
internal fun DrawScope.drawThreatSite(
    pr: MapProjection, at: DtcFromMission.Pt, ringFt: Double?, dashed: Boolean, fill: Float, label: String?, edgeFt: Double?, names: NameSink,
) {
    val ink = SamRed
    val c = pr.toScreen(at.north, at.east)
    val r = ((ringFt ?: 0.0) * pr.pxPerNm / DtcFromMission.NM).toFloat()
    if (r > 2f) {
        if (!ringOnMap(c, r)) return
        drawSamRing(c, r, ink, dashed = dashed, fill = fill * 0.8f)
    } else if (!onMap(c)) return
    drawSamSquare(c, ink, 4f)
    if (label.isNullOrBlank() || !siteNamed(pr, edgeFt)) return
    names.add(NamePrio.SITE, label, c + Offset(8f, 1f), TextStyle(color = ink, fontSize = 10.sp, fontWeight = FontWeight.Bold, shadow = if (Hud.onLightMap) MapGlow else MapShadow))
}

