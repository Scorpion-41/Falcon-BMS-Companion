package com.bmscompanion.desktop.ui.command

import com.bmscompanion.desktop.bridge.CampaignArchive
import com.bmscompanion.desktop.bridge.command.CmdObjective
import com.bmscompanion.desktop.bridge.command.CmdUnit
import com.bmscompanion.desktop.bridge.command.CommandBackup
import com.bmscompanion.desktop.bridge.command.CommandEdit
import com.bmscompanion.desktop.bridge.command.CommandWriter
import com.bmscompanion.desktop.bridge.command.Edits
import com.bmscompanion.desktop.bridge.command.ObjLink
import com.bmscompanion.desktop.bridge.command.ShippedSaves
import java.io.File
import kotlin.math.floor

// The shell's buttons on the data layer (bridge/command): undo and redo on the save's edit log, Save as… and
// Overwrite… through CommandWriter (a copy by default; overwrite only a pilot's own file and only after asking; BMS's
// campaign start in place only once its original is backed up), and the map's own edits (owner, move, links).

/** The editing half of [d] on its model's edit log and [CommandWriter]. */
class CoreEditing(private val d: CmdDocument) : CmdEditing {
    private val core get() = d.core
    private var startMode = false

    override val editCount: Int get() = core.edits.count
    override val canUndo: Boolean get() = core.edits.canUndo
    override val canRedo: Boolean get() = core.edits.canRedo
    override val undoName: String? get() = core.edits.undoLabel
    override val redoName: String? get() = core.edits.redoLabel
    override fun undo(): String? = if (core.edits.undo()) null else "Nothing to undo."
    override fun redo(): String? = if (core.edits.redo()) null else "That could not be done again."

    override fun proposedCopyName(): String = CommandWriter.proposedName(core).nameWithoutExtension

    private fun target(name: String) = File(d.file.absoluteFile.parentFile, "${name.trim()}.${d.file.extension}")

    override fun refuseCopyName(name: String): String? {
        val n = name.trim()
        if (n.isEmpty()) return "Give the copy a name."
        if (n.any { it in "\\/:*?\"<>|" }) return "A file name cannot hold \\ / : * ? \" < > |."
        if (n.length > 39) return "BMS keeps at most 39 characters of a save's name: shorten it."
        if (target(n).absoluteFile == d.file.absoluteFile) return "That is the open file's own name: Overwrite… writes it in place."
        return ShippedSaves.refuseName(target(n))
    }

    override fun copyExists(name: String): Boolean = CommandWriter.saveAsConflicts(core, target(name)).isNotEmpty()

    override fun saveAs(name: String, replace: Boolean): String? {
        val r = CommandWriter.saveAs(core, target(name), replace)
        if (!r.ok) return r.message
        CommandSession.status = r.message
        // the copy is the file worked on from now (its own, so Overwrite… may write it)
        r.file?.let { f -> CmdDocument.open(f, CommandSession.rootOverride).onSuccess { CommandSession.doc = it } }
        return null
    }

    override val overwriteRefusal: String?
        get() {
            val id = core.identity
            if (id.overwriteAllowed) return null
            if (id.start && startMode) return null
            return id.why + if (id.start) ". Edit BMS's campaign start (beside the file's name) writes it in place after a backup." else ". Save as… writes a copy."
        }

    override fun overwrite(): String? {
        val r = if (core.identity.start && startMode) CommandWriter.overwriteStart(core) else CommandWriter.overwrite(core)
        if (!r.ok) return r.message
        CommandSession.status = r.message
        return null
    }

    override val isBmsStart: Boolean get() = core.identity.start
    override val editingStart: Boolean get() = startMode
    override fun editStart(): String? {
        CommandBackup.backupOnce(d.file, "campaign start, before Edit BMS's campaign start")?.let { return "BMS's original could not be backed up, so the start stays read only: $it" }
        startMode = true
        return null
    }

    private fun folder() = d.file.absoluteFile.parentFile
    override val canRestoreStart: Boolean get() = core.identity.start && folder()?.let { CommandBackup.copyOf(it, d.file.name) } != null
    override fun restoreStart(): String? {
        val f = folder() ?: return "${d.file.name} has no folder."
        CommandBackup.restore(f, d.file.name)?.let { return it }
        CmdDocument.open(d.file, CommandSession.rootOverride).onSuccess { CommandSession.doc = it }
        return null
    }
}

/** The map's edits on the model: owners (objective record or delta, unit), moves, links (a start's records only). */
object CoreMapEdits : CmdMapEdits {
    private val doc get() = CommandSession.doc
    private const val CELL = CampaignArchive.FEET_PER_CELL

    private fun run(e: CommandEdit): String? {
        val d = doc ?: return "No file is open."
        return d.core.edits.run(e)
    }

    private fun obj(r: CmdRef): CmdObjective? = doc?.core?.objective(CampaignArchive.VuId.parse(r.id))
    private fun unit(r: CmdRef): CmdUnit? = doc?.core?.unit(CampaignArchive.VuId.parse(r.id))

    override fun setOwner(refs: List<CmdRef>, team: Int): String? {
        val d = doc ?: return "No file is open."
        val edits = refs.mapNotNull { r ->
            when (r.kind) {
                CmdKind.OBJECTIVE -> obj(r)?.let { Edits.objectiveOwner(d.core, it, team) }
                CmdKind.WAYPOINT, CmdKind.DIVISION, CmdKind.PACKAGE, CmdKind.TEAM, CmdKind.PILOT, CmdKind.CHECK, CmdKind.OTHER -> null
                else -> unit(r)?.let { u -> Edits.set("Owner of ${d.world.nameOf(r)}", "uni", { u.owner }, { u.owner = it }, team) }
            }
        }
        if (edits.isEmpty()) return "Nothing there has an owner to set."
        val name = d.world.team(team)?.name ?: "team $team"
        return run(if (edits.size == 1) edits[0] else Edits.group("Owner of ${edits.size} to $name", edits))
    }

    override fun move(ref: CmdRef, north: Double, east: Double): String? {
        val d = doc ?: return "No file is open."
        val cx = floor(east / CELL).toInt()
        val cy = floor(north / CELL).toInt()
        return when (ref.kind) {
            CmdKind.OBJECTIVE -> {
                val o = obj(ref) ?: return "That objective is not in the file."
                if (!d.core.objectivesEditable) return "Objectives move only in a campaign start (Edit BMS's campaign start); a save records owners and damage."
                val label = "Move ${d.world.nameOf(ref)}"
                run(
                    Edits.group(
                        label,
                        listOf(
                            Edits.set(label, "obj", { o.x }, { o.x = it }, cx), Edits.set(label, "obj", { o.y }, { o.y = it }, cy),
                            Edits.set(label, "obj", { o.simNorth }, { o.simNorth = it }, north), Edits.set(label, "obj", { o.simEast }, { o.simEast = it }, east),
                        ),
                    ),
                )
            }
            CmdKind.WAYPOINT -> {
                val u = unit(ref) ?: return "That unit is not in the file."
                val w = u.waypoints.getOrNull(ref.sub) ?: return "That waypoint is not there."
                val label = "Move waypoint ${ref.sub + 1} of ${d.world.nameOf(ref.owner(CmdKind.FLIGHT))}"
                run(Edits.group(label, listOf(Edits.set(label, "uni", { w.gridX }, { w.gridX = it }, cx), Edits.set(label, "uni", { w.gridY }, { w.gridY = it }, cy))))
            }
            CmdKind.DIVISION, CmdKind.PACKAGE, CmdKind.TEAM, CmdKind.PILOT, CmdKind.CHECK, CmdKind.OTHER -> "That cannot be moved on the map."
            else -> {
                val u = unit(ref) ?: return "That unit is not in the file."
                val label = "Move ${d.world.nameOf(ref)}"
                run(Edits.group(label, listOf(Edits.set(label, "uni", { u.x }, { u.x = it }, cx), Edits.set(label, "uni", { u.y }, { u.y = it }, cy))))
            }
        }
    }

    override fun clone(ref: CmdRef, north: Double, east: Double): String? = "Cloning comes with the unit editor, which is not in this build yet."

    override fun addLink(a: CmdRef, b: CmdRef): String? {
        val d = doc ?: return "No file is open."
        if (!d.core.objectivesEditable) return "Links are edited only in a campaign start (Edit BMS's campaign start)."
        val oa = obj(a) ?: return "Not an objective."
        val ob = obj(b) ?: return "Not an objective."
        if (oa.links.any { it.id == ob.id }) return "They are linked already."
        if (oa.links.size >= MAX_LINKS || ob.links.size >= MAX_LINKS) return "An objective has at most $MAX_LINKS links."
        val km = kotlin.math.hypot((oa.x - ob.x).toDouble(), (oa.y - ob.y).toDouble())
        val label = "Link ${d.world.nameOf(a)} and ${d.world.nameOf(b)}"
        return run(Edits.group(label, listOf(Edits.insert(label, "obj", oa.links, ObjLink(costs(km), ob.id)), Edits.insert(label, "obj", ob.links, ObjLink(costs(km), oa.id)))))
    }

    private const val MAX_LINKS = 8

    /**
     * A new link's cost for each movement type (none, foot, wheeled, tracked, low air, air, naval, rail) from its length
     * in grid kilometres, the terrain not taken into account; rail 255 (no way). The objectives editor may set them.
     */
    private fun costs(km: Double): IntArray {
        val per = doubleArrayOf(0.87, 1.58, 4.32, 2.94, 1.06, 1.06, 1.19)
        return IntArray(8) { i -> if (i == 7) 255 else (km * per[i]).toInt().coerceIn(0, 255) }
    }

    override fun removeLink(a: CmdRef, b: CmdRef): String? {
        val d = doc ?: return "No file is open."
        if (!d.core.objectivesEditable) return "Links are edited only in a campaign start (Edit BMS's campaign start)."
        val oa = obj(a) ?: return "Not an objective."
        val ob = obj(b) ?: return "Not an objective."
        val la = oa.links.firstOrNull { it.id == ob.id }
        val lb = ob.links.firstOrNull { it.id == oa.id }
        if (la == null && lb == null) return "They are not linked."
        val label = "Unlink ${d.world.nameOf(a)} and ${d.world.nameOf(b)}"
        return run(Edits.group(label, listOfNotNull(la?.let { Edits.remove(label, "obj", oa.links, it) }, lb?.let { Edits.remove(label, "obj", ob.links, it) })))
    }
}
