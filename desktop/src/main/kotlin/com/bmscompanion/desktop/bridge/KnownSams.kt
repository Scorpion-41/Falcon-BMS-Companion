package com.bmscompanion.desktop.bridge

import com.bmscompanion.app.data.mission.CampSite
import com.bmscompanion.app.data.mission.Contacts
import com.bmscompanion.app.data.mission.MissionPicture

/**
 * No cheating: BMS's Tacview stream carries every air defence of the theater, spotted or not, so a hostile one
 * (`kind == "sam"`) leaves the PC (`GET /api/contacts`) only when it is a site the mission knows — one of the save's
 * spotted sites of the flight's side or of the briefing ([MissionGrounds.knownEz]/[knownWdp], the mode's
 * `MissionData.ground`) or a pre-planned threat of the cartridge — the same system within
 * [MissionPicture.LIVE_MATCH_NM] ([MissionPicture.knownSite]). With nothing known, none leaves. Friendly and neutral
 * ones, and every aircraft, are passed as they are. Nothing here throws.
 */
object KnownSams {
    fun filter(c: Contacts): Contacts {
        if (c.contacts.none { it.kind == "sam" && it.hostile }) return c
        val known = runCatching { known() }.getOrDefault(emptyList())
        return c.copy(contacts = c.contacts.filter { k -> k.kind != "sam" || !k.hostile || MissionPicture.knownSite(k.name, k.x, k.y, known) })
    }

    /** What the mission knows of now, in the Mission section's mode. */
    fun known(): List<CampSite> {
        val wdp = MissionSource.wdp
        val m = if (wdp) MissionSource.mission() else null
        val ground = if (wdp) m?.ground else MissionGrounds.forBriefing()
        val dtc = if (wdp) m?.dtc else Bridge.cartridgeNow()
        val ppts = dtc?.ppts.orEmpty().filter { !it.marker && (it.rangeNm > 0 || it.rangeFt > 0) && (it.x != 0.0 || it.y != 0.0) }
            .map { CampSite(system = it.name.orEmpty(), x = it.x, y = it.y, spotted = true) }
        return ground?.airDefences.orEmpty() + ground?.ships.orEmpty() + (if (wdp) MissionGrounds.knownWdp else MissionGrounds.knownEz) + ppts
    }
}
