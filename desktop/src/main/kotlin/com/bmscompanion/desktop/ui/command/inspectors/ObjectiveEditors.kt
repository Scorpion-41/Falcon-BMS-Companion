package com.bmscompanion.desktop.ui.command.inspectors

import com.bmscompanion.desktop.bridge.command.ObjectiveOps
import com.bmscompanion.desktop.ui.command.CmdKind
import com.bmscompanion.desktop.ui.command.CmdMapEdits
import com.bmscompanion.desktop.ui.command.CmdPage
import com.bmscompanion.desktop.ui.command.CmdRef
import com.bmscompanion.desktop.ui.command.CommandSession
import com.bmscompanion.desktop.ui.command.CommandSlots
import com.bmscompanion.desktop.ui.command.tables.ObjectivesTable

/**
 * The objective, team, campaign, pilot and checks editors put into the section's slots (one call from
 * `CommandRegistry.install`): the Objectives page's table and inspector, the Teams, Campaign, Pilots and Checks
 * pages, two lines of the map's menu, and a guard round the map's edits so an objective is changed only where BMS
 * reads the change (a save's deltas, or a start being edited).
 */
object ObjectiveEditors {
    fun install() {
        CommandSlots.inspectors += ObjectiveInspector
        CommandSlots.tables[CmdPage.OBJECTIVES] = ObjectivesTable
        CommandSlots.pages[CmdPage.TEAMS] = TeamsPage
        CommandSlots.pages[CmdPage.CAMPAIGN] = CampaignPage
        CommandSlots.pages[CmdPage.PILOTS] = PilotsPage
        CommandSlots.pages[CmdPage.CHECKS] = ChecksPage
        CommandSlots.actions += AddObjectiveHere
        CommandSlots.actions += RepairObjective
        CommandSlots.mapEdits?.let { inner -> CommandSlots.mapEdits = GuardedMapEdits(inner) }
    }
}

/**
 * The map's edits with the objectives' rule (the section's rule 3): an objective's owner changes in a save's deltas
 * or in a start being edited; its place and links only in a start being edited. Everything else goes to [inner].
 */
class GuardedMapEdits(private val inner: CmdMapEdits) : CmdMapEdits {
    private fun mode(): Pair<ObjectiveOps.Mode, String>? {
        val d = CommandSession.doc ?: return null
        val start = d.editing?.editingStart == true
        return ObjectiveOps.mode(d.core, start) to ObjectiveOps.modeWhy(d.core, start)
    }

    override fun move(ref: CmdRef, north: Double, east: Double): String? {
        if (ref.kind == CmdKind.OBJECTIVE) mode()?.let { (m, why) -> if (m != ObjectiveOps.Mode.FULL) return if (m == ObjectiveOps.Mode.NONE) why else "An objective moves only in a campaign start being edited; a save records owners and damage." }
        return inner.move(ref, north, east)
    }

    override fun clone(ref: CmdRef, north: Double, east: Double): String? = inner.clone(ref, north, east)

    override fun addLink(a: CmdRef, b: CmdRef): String? {
        mode()?.let { (m, why) -> if (m != ObjectiveOps.Mode.FULL) return if (m == ObjectiveOps.Mode.NONE) why else "Links are edited only in a campaign start (Edit BMS's campaign start)." }
        // a link costed by the theater's own links (ObjectiveOps.costModel)
        val core = CommandSession.doc?.core ?: return inner.addLink(a, b)
        val oa = core.objective(com.bmscompanion.desktop.bridge.CampaignArchive.VuId.parse(a.id)) ?: return "Not an objective."
        val ob = core.objective(com.bmscompanion.desktop.bridge.CampaignArchive.VuId.parse(b.id)) ?: return "Not an objective."
        if (oa.links.size >= ObjectiveOps.MAX_LINKS || ob.links.size >= ObjectiveOps.MAX_LINKS) return "An objective has at most ${ObjectiveOps.MAX_LINKS} links."
        val e = ObjectiveOps.link(core, oa, ob) ?: return "They are linked already."
        return core.edits.run(e)
    }

    override fun removeLink(a: CmdRef, b: CmdRef): String? {
        mode()?.let { (m, why) -> if (m != ObjectiveOps.Mode.FULL) return if (m == ObjectiveOps.Mode.NONE) why else "Links are edited only in a campaign start (Edit BMS's campaign start)." }
        return inner.removeLink(a, b)
    }

    override fun setOwner(refs: List<CmdRef>, team: Int): String? {
        val m = mode()
        if (m != null && m.first == ObjectiveOps.Mode.NONE && refs.any { it.kind == CmdKind.OBJECTIVE }) {
            val rest = refs.filter { it.kind != CmdKind.OBJECTIVE }
            if (rest.isEmpty()) return m.second
            return inner.setOwner(rest, team)
        }
        return inner.setOwner(refs, team)
    }
}
