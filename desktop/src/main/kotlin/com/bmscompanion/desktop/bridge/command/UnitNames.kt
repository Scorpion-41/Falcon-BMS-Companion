package com.bmscompanion.desktop.bridge.command

import com.bmscompanion.desktop.bridge.CampaignArchive

/**
 * How the Command section names a unit everywhere (tables, ATO, inspectors' titles, the hover box, map labels, Find,
 * Checks, edit labels), the way a campaign designer reads them:
 *
 * - a flight by its callsign and number, with its aircraft and how many: "Falcon 3 · F-16CM-50 ×4";
 * - a squadron by its name with its aircraft: "156th KFS · F-16PB-52" (the name BMS keeps in the squadron list, else
 *   the number and its role: "36th Fighter Squadron");
 * - a package by its number and mission: "Package 412 · OCA STRIKE";
 * - a battalion, brigade or task force by its number, role and word (Strings 614-616) with its main vehicle or ship:
 *   "500th Air Defense Battalion · SA-6 (2K12)".
 *
 * [short] is the name without the type (edit labels, a status line), [full] the name with it, [type] the type alone
 * (a table's Type column). Never throws; a unit the class table does not know is named by its kind and CampId.
 */
object UnitNames {
    private const val SQUADRON_WORD = 610
    private const val BRIGADE_WORD = 614
    private const val BATTALION_WORD = 615
    private const val TASKFORCE_WORD = 616

    /** The unit's role from its class ("Fighter", "Air Defense"); null for BMS's "Null" (a package) or unknown. */
    fun role(doc: CommandSave, u: CmdUnit): String? =
        doc.unitClass(u)?.trim()?.takeIf { it.isNotEmpty() && !it.equals("Null", ignoreCase = true) }

    /** The unit's main vehicle (its class's first vehicle: the aircraft, the lead ship, the battalion's main system). */
    fun vehicle(doc: CommandSave, u: CmdUnit): String? = when (u) {
        is CmdPackage, is CmdBrigade -> null // (a brigade's "vehicles" are battalions, a package has none)
        else -> runCatching { doc.aircraft(u) }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** "Falcon 3": the callsign word, a space, the flight's number. */
    fun callsign(doc: CommandSave, f: CmdFlight): String {
        val word = doc.string(CampaignArchive.CALLSIGN_STRINGS + f.callsignId)?.trim()?.ifEmpty { null } ?: "Callsign ${f.callsignId}"
        return if (f.callsignNum in 1..99) "$word ${f.callsignNum}" else word
    }

    /** A package's mission: its request's, else its first flight's. */
    fun packageMission(doc: CommandSave, p: CmdPackage): String? {
        fun m(n: Int) = doc.string(CampaignArchive.MISSION_STRINGS + n)?.trim()?.takeIf { it.isNotEmpty() && n > 0 }
        return m(p.request.mission) ?: doc.flightsOf(p).firstNotNullOfOrNull { m(it.mission) }
    }

    fun packageNumber(p: CmdPackage): Int = if (p.nameId > 0) p.nameId else p.campId

    private fun numbered(doc: CommandSave, n: Int): String? = if (n > 0) "$n" + CampaignArchive.ordinal(doc.names, n) else null

    private fun groundShort(doc: CommandSave, u: CmdUnit, word: Int, fallback: String): String {
        val r = role(doc, u)
        val n = numbered(doc, u.nameId)
        if (r == null && n == null) return "$fallback ${u.campId}"
        return listOfNotNull(n, r, doc.string(word)?.trim()?.ifEmpty { null } ?: fallback).joinToString(" ")
    }

    /** The name without the type: "Falcon 3", "156th KFS", "Package 412", "500th Air Defense Battalion". */
    fun short(doc: CommandSave, u: CmdUnit): String = runCatching {
        when (u) {
            is CmdFlight -> callsign(doc, u)
            is CmdPackage -> "Package ${packageNumber(u)}"
            is CmdSquadron -> doc.squadInfo(u)?.squadronName?.text?.trim()?.ifEmpty { null }
                ?: listOfNotNull(numbered(doc, u.nameId), role(doc, u), doc.string(SQUADRON_WORD)?.trim()?.ifEmpty { null } ?: "Squadron").joinToString(" ")
            is CmdBattalion -> groundShort(doc, u, BATTALION_WORD, "Battalion")
            is CmdBrigade -> groundShort(doc, u, BRIGADE_WORD, "Brigade")
            is CmdTaskForce -> groundShort(doc, u, TASKFORCE_WORD, "Task force")
        }
    }.getOrElse { "${u.kind.name.lowercase().replaceFirstChar { it.uppercase() }} ${u.campId}" }

    /** The type: "F-16CM-50 ×4" (a flight), "F-16PB-52 Fighter", "OCA STRIKE" (a package), "SA-6 (2K12) Air Defense". */
    fun type(doc: CommandSave, u: CmdUnit): String = runCatching {
        when (u) {
            is CmdFlight -> listOfNotNull(vehicle(doc, u), "×${u.aircraft}").joinToString(" ")
            is CmdPackage -> packageMission(doc, u) ?: "Package"
            else -> listOfNotNull(vehicle(doc, u), role(doc, u)).joinToString(" · ").ifEmpty { u.kind.name.lowercase() }
        }
    }.getOrElse { u.kind.name.lowercase() }

    /** The name with the type: "Falcon 3 · F-16CM-50 ×4", "156th KFS · F-16PB-52", "Package 412 · OCA STRIKE". */
    fun full(doc: CommandSave, u: CmdUnit): String = runCatching {
        val s = short(doc, u)
        val t = when (u) {
            is CmdFlight -> listOfNotNull(vehicle(doc, u), if (u.aircraft > 0) "×${u.aircraft}" else null).joinToString(" ").ifEmpty { null }
            is CmdPackage -> packageMission(doc, u)
            is CmdSquadron -> vehicle(doc, u) ?: role(doc, u)
            is CmdBrigade -> null // the role is in the name already
            else -> vehicle(doc, u)
        }
        if (t == null) s else "$s · $t"
    }.getOrElse { short(doc, u) }

    /**
     * The Units table's Name: the unit's type and role, never its callsign — "F-16C-30 IAF - Fighter" (a squadron or
     * a flight: its aircraft, then its class's role), "SA-3 - Air Defense" (a battalion), "Package - 412".
     */
    fun tableName(doc: CommandSave, u: CmdUnit): String = runCatching {
        when (u) {
            is CmdPackage -> "Package - ${packageNumber(u)}"
            else -> listOfNotNull(vehicle(doc, u), role(doc, u)).joinToString(" - ").ifEmpty { short(doc, u) }
        }
    }.getOrElse { short(doc, u) }

    /**
     * The Units table's Callsign: a flight's callsign ("Spade 1"), a squadron's name as BMS keeps it in the squadron
     * list, else its number ("110th"), a battalion's, brigade's or task force's number ("500th"); a package has none.
     */
    fun callsignText(doc: CommandSave, u: CmdUnit): String = runCatching {
        when (u) {
            is CmdFlight -> callsign(doc, u)
            is CmdPackage -> ""
            is CmdSquadron -> doc.squadInfo(u)?.squadronName?.text?.trim()?.ifEmpty { null } ?: numbered(doc, u.nameId).orEmpty()
            else -> numbered(doc, u.nameId).orEmpty()
        }
    }.getOrElse { "" }
}
