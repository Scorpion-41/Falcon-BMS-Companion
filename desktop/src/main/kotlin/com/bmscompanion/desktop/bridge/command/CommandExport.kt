package com.bmscompanion.desktop.bridge.command

import com.bmscompanion.desktop.bridge.Bridge
import com.bmscompanion.desktop.bridge.CampaignArchive
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The Command section's exports: the open file's units and objectives as CSV (comma separated, quoted where needed,
 * UTF-8 with a BOM so a spreadsheet reads the names), for a designer's own lists, and the map's picture (PNG). Written where the pilot picks,
 * never into the Falcon BMS folder; through a temp file and a move; never throws (the reason comes back).
 */
object CommandExport {
    private fun q(s: String): String = if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
    private fun line(vararg cells: Any?): String = cells.joinToString(",") { q(it?.toString().orEmpty()) }

    fun unitsCsv(doc: CommandSave): String = buildString {
        appendLine(line("Kind", "Name", "Short name", "Type", "Role", "Team", "CampId", "Id", "Mission", "Strength", "Parent", "Base or objective", "Grid X", "Grid Y", "Waypoints", "Unit flags"))
        for (u in doc.units) {
            val mission = when (u) {
                is CmdFlight -> doc.string(CampaignArchive.MISSION_STRINGS + u.mission)?.trim()
                is CmdPackage -> UnitNames.packageMission(doc, u)
                else -> null
            }
            val strength = when (u) {
                is CmdFlight -> u.aircraft
                is CmdPackage -> u.elements.size
                is CmdBrigade -> u.elements.size
                else -> CampaignOps.rosterTotal(u.roster)
            }
            val parent = when (u) {
                is CmdFlight -> doc.packageOf(u)?.let { UnitNames.short(doc, it) }
                is CmdBattalion -> (doc.unit(u.parentId))?.let { UnitNames.short(doc, it) }
                else -> null
            }
            val base = when (u) {
                is CmdSquadron -> doc.objective(u.airbaseId)?.let { ObjectiveOps.displayName(doc, it) } ?: doc.unit(u.airbaseId)?.let { UnitNames.short(doc, it) }
                is CmdFlight -> doc.squadronOf(u)?.let { UnitNames.short(doc, it) }
                is CmdGroundUnit -> doc.objective(u.aobj)?.let { ObjectiveOps.displayName(doc, it) }
                else -> null
            }
            appendLine(line(u.kind.name.lowercase(), UnitNames.full(doc, u), UnitNames.short(doc, u), UnitNames.type(doc, u), UnitNames.role(doc, u),
                doc.teamName(u.owner), u.campId, u.id, mission, strength, parent, base, u.x, u.y, u.waypoints.size, "0x" + u.unitFlags.toString(16)))
        }
    }

    fun objectivesCsv(doc: CommandSave): String = buildString {
        appendLine(line("Name", "Type", "CampId", "Id", "Owner", "First owner", "Priority", "Supply", "Fuel", "Losses", "Buildings damaged", "Links", "Grid X", "Grid Y", "North ft", "East ft", "Flags", "Changed in this save"))
        for (o in doc.objectives) {
            val d = doc.delta(o.id)
            val states = ObjectiveOps.statesNow(doc, o)
            appendLine(line(ObjectiveOps.displayName(doc, o), doc.string(500 + ObjectiveOps.typeOf(doc, o))?.trim(), o.campId, o.id,
                doc.teamName(doc.ownerNow(o)), doc.teamName(o.firstOwner), o.priority, d?.supply ?: o.supply, d?.fuel ?: o.fuel, d?.losses ?: o.losses,
                ObjectiveOps.FeatureStates.damaged(states, states.size), o.links.size, o.x, o.y,
                if (o.simNorth != 0.0) "%.0f".format(o.simNorth) else "%.0f".format((o.y + 0.5) * CampaignArchive.FEET_PER_CELL),
                if (o.simEast != 0.0) "%.0f".format(o.simEast) else "%.0f".format((o.x + 0.5) * CampaignArchive.FEET_PER_CELL),
                "0x" + (o.objFlags.toLong() and 0xFFFFFFFFL).toString(16), if (d != null) "yes" else ""))
        }
    }

    /** Writes [text] to [target] (never inside the Falcon BMS folder); null when done, else the reason. */
    fun write(target: File, text: String): String? =
        writeBytes(target, byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + text.replace("\n", "\r\n").toByteArray(Charsets.UTF_8))

    /** Writes [bytes] (a CSV, the map's picture) to [target], never inside the Falcon BMS folder; null when done, else the reason. */
    fun writeBytes(target: File, bytes: ByteArray): String? = try {
        val t = target.absoluteFile
        val bms = Bridge.install.baseDir?.let { File(it).canonicalFile }
        // (the install this program reads, or any folder laid out as one: a copy too)
        val inBms = (bms != null && t.canonicalPath.startsWith(bms.path + File.separator, ignoreCase = true)) ||
            generateSequence(t.canonicalFile.parentFile) { it.parentFile }.any { File(it, "Data/TerrData/TheaterDefinition/theater.lst").isFile || File(it, "Falcon BMS.exe").isFile }
        if (inBms) "Exports are not written into the Falcon BMS folder: pick another folder."
        else {
            val dir = t.parentFile ?: return "No folder to write in."
            val tmp = File(dir, t.name + ".bmsc-new")
            tmp.writeBytes(bytes)
            Files.move(tmp.toPath(), t.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            null
        }
    } catch (e: Throwable) {
        "${target.name} could not be written: ${e.message ?: e.javaClass.simpleName}"
    }
}
