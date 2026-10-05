package com.bmscompanion.app.data.mission

/**
 * **Hostile contacts from the live feed are off unless the pilot turns them on** (1.3.8): BMS's Tacview stream carries
 * every aircraft, ship and ground unit of the theater, and drawing the enemy's from it is a picture no F-16 pilot has.
 *
 * One setting on the PC (`BridgeSettings.ShowHostiles`, off by default and set off once for everyone who had the
 * program before — settings layout 3), read by every device from `BridgeInfo.tacview.hostiles` and changed from any
 * Setup page (`POST /api/contacts/hostiles?on=1|0`). It is **enforced at the source**: with it off the PC's
 * `/api/contacts` carries no hostile contact ([strip]), the way [KnownSams][com.bmscompanion.app.data.mission.MissionPicture.knownSite]
 * keeps an unspotted site back; each device strips the same again (an older PC sends them all) and its pages say the
 * setting is off rather than "no hostiles". Untouched: the briefing's threats, the cartridge's PPTs, the cockpit's own
 * data (RWR, shared memory). The briefed sites and PPT rings come from the briefing and the cartridge, not the feed, so
 * they stay; a live hostile air defence from the feed goes too (it would tell whether a site still stands). With it on,
 * the map's own Hostiles switch is a second filter, as it always was.
 */
object HostileContacts {
    /** the setting's name, on every Setup page */
    const val TITLE = "Show hostile contacts (live)"
    /** the one line under it */
    const val LINE = "Off: enemy aircraft and ground units from the live feed are never shown — the app shows only what " +
        "the pilot is briefed or sees in the cockpit."
    /** what a card, a page or a switch says while the setting is off */
    const val OFF = "Hostile contacts are off — Setup"
    /** the same, as a short tag */
    const val OFF_SHORT = "Hostiles off — Setup"

    /** Whether the PC sends hostile contacts: false without an answer, and from an older PC that does not say. */
    fun on(info: BridgeInfo?): Boolean = info?.tacview?.hostiles == true

    /**
     * What may leave the PC (and be kept on a device) with the setting off: no hostile contact of any kind, air defences
     * included, and only the weapon events of the pilot's own side.
     */
    fun strip(c: Contacts): Contacts {
        if (c.contacts.none { it.hostile } && c.events.all { it.mine }) return c
        return c.copy(contacts = c.contacts.filter { !it.hostile }, events = c.events.filter { it.mine })
    }

    /** [strip] unless [on]. */
    fun filter(c: Contacts, on: Boolean): Contacts = if (on) c else strip(c)
}
