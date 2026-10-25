package com.bmscompanion.desktop.ui.command

/**
 * Where the editor's pages, tables, inspectors and actions are put into the section's slots ([CommandSlots]). One line
 * each, in [install]; the shell calls it once, before the section is first drawn. Examples:
 *
 * ```
 * CommandSlots.inspectors += FlightInspector          // accepts CmdKind.FLIGHT
 * CommandSlots.tables[CmdPage.UNITS] = UnitsTable      // the Units page's drawer
 * CommandSlots.pages[CmdPage.TEAMS] = TeamsPage        // the Teams page's centre
 * CommandSlots.actions += AirportScheduleAction        // a line of the map's context menu
 * CommandSlots.mapEdits = CommandMapEdits              // Move, Clone, Link ±, Owner
 * CommandSlots.editing = { doc -> CommandEditingOf(doc) }   // undo/redo, Save as…, Overwrite…, BMS's start
 * ```
 */
object CommandRegistry {
    private var done = false

    fun installOnce() {
        if (done) return
        done = true
        runCatching { install() }
    }

    private fun install() {
        // (the editor's slots go here)
        com.bmscompanion.desktop.ui.command.inspectors.ObjectiveEditors.install() // objectives, teams, campaign, pilots, checks
        runCatching { com.bmscompanion.desktop.ui.command.inspectors.UnitEditors.install() } // units, ATO, unit map edits, bulk, unit checks
        CommandSlots.pages[CmdPage.FILES] = com.bmscompanion.desktop.ui.command.inspectors.FilesPage // the theater's campaign database files
        runCatching { com.bmscompanion.desktop.ui.command.inspectors.MoreActions.install() } // the menus' other lines per kind, their dialogs, one order
        CommandSlots.pages[CmdPage.STATS] = com.bmscompanion.desktop.ui.command.inspectors.StatsPage // per-team counts of air, ground and sea
    }
}
