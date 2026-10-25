package com.bmscompanion.desktop.ui.command

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.ui.components.MapState
import java.io.File

// The Command section's state and the slots the editor pages fill (see CommandSlots). PC only: everything here runs
// in the PC's own window, in-process, on this PC's files.

/** The pages of the section, in the strip's order. */
enum class CmdPage(val label: String) {
    MAP("Map"), UNITS("Units"), ATO("ATO"), OBJECTIVES("Objectives"), TEAMS("Teams"),
    CAMPAIGN("Campaign"), PILOTS("Pilots"), STATS("Stats"), CHECKS("Checks"), FILES("Files"),
}

/** What a [CmdRef] points at. */
enum class CmdKind(val label: String) {
    OBJECTIVE("Objective"), SQUADRON("Squadron"), FLIGHT("Flight"), PACKAGE("Package"), BATTALION("Battalion"),
    BRIGADE("Brigade"), DIVISION("Division"), TASKFORCE("Task force"), TEAM("Team"), WAYPOINT("Waypoint"),
    PILOT("Pilot"), CHECK("Check"), OTHER("Item"),
}

/**
 * One thing the section can select. [id] is the entity's own key: "num/creator" (`CampaignArchive.VuId.toString()`)
 * for a unit or an objective, "<owner>:<division>" for a division, the team number for a team. [sub] is a part of it
 * (a waypoint's index, a pilot's slot), -1 for the whole.
 */
data class CmdRef(val kind: CmdKind, val id: String, val sub: Int = -1) {
    /** the unit or objective a waypoint belongs to, as its own ref */
    fun owner(kind: CmdKind): CmdRef = CmdRef(kind, id)
}

/** The map's tools: what a left press on the map does (keyboard: the letter). */
enum class CmdTool(val label: String, val key: Char, val hint: String) {
    SELECT("Select", 'S', "Click selects (Shift adds); drag pans"),
    AREA("Area", 'A', "Drag a box: everything shown inside it is selected"),
    MOVE("Move", 'M', "Drag the selected unit or objective to its new place"),
    CLONE("Clone", 'C', "Click where a copy of the selected unit goes"),
    LINK_ADD("Link +", 'L', "Click two objectives to link them (campaign starts only)"),
    LINK_REMOVE("Link −", 'K', "Click two linked objectives to unlink them (campaign starts only)"),
    OWNER("Owner", 'O', "Click an objective or a unit to give it the owner chosen beside the tools"),
    RULER("Ruler", 'R', "Drag from one point to another: track and distance"),
}

/** The selection, shared by the map, the tables and the inspector. [primary] is the one the inspector shows first. */
class CmdSelection {
    var refs by mutableStateOf<List<CmdRef>>(emptyList())
        private set
    /** what the pointer is over on the map (or a table row), for the hover box and the route on hover */
    var hover by mutableStateOf<CmdRef?>(null)
    val primary: CmdRef? get() = refs.firstOrNull()

    fun set(r: CmdRef?) { refs = listOfNotNull(r) }
    fun setAll(rs: List<CmdRef>) { refs = rs.distinct() }
    fun toggle(r: CmdRef) { refs = if (r in refs) refs - r else refs + r }
    fun clear() { refs = emptyList() }
    operator fun contains(r: CmdRef) = r in refs
}

/**
 * The editing half of an open file, provided by the data layer (bridge/command): every change is an undoable command,
 * counted; saving follows the section's saving rules (a copy by default, overwrite after asking, BMS's own files never
 * through Save, a campaign start edited in place only after its one-time backup). Every call answers a reason in a
 * sentence instead of throwing; null = done.
 */
interface CmdEditing {
    val editCount: Int
    val canUndo: Boolean
    val canRedo: Boolean
    /** what an undo would take back ("Owner of Osan AB"), for the button's tip; null when nothing */
    val undoName: String? get() = null
    val redoName: String? get() = null
    fun undo(): String?
    fun redo(): String?
    /** the name a copy is offered under ("<name> (edited)") */
    fun proposedCopyName(): String
    /** why [name] cannot be the copy's name (looks like BMS's own, bad characters), null when it can */
    fun refuseCopyName(name: String): String? = null
    /** the copy would replace an existing file */
    fun copyExists(name: String): Boolean = false
    fun saveAs(name: String, replace: Boolean): String?
    /** why the open file cannot be overwritten (BMS's own), null when it can */
    val overwriteRefusal: String?
    fun overwrite(): String?
    /** the open file is one of BMS's campaign starts that may be edited in place after its backup */
    val isBmsStart: Boolean get() = false
    /** the start is being edited in place (its backup taken) */
    val editingStart: Boolean get() = false
    fun editStart(): String? = "Not available."
    /** BMS's original of the start is kept beside it and can be put back */
    val canRestoreStart: Boolean get() = false
    fun restoreStart(): String? = "Not available."
}

/**
 * The map's edits, provided by the editor (bridge/command): the tools Move, Clone, Link ± and Owner, and the owner of
 * an area. Each answers null when done, else the reason (shown in the status line). Until one is installed the tools
 * that change the file are shown but say so.
 */
interface CmdMapEdits {
    fun move(ref: CmdRef, north: Double, east: Double): String?
    fun clone(ref: CmdRef, north: Double, east: Double): String?
    fun addLink(a: CmdRef, b: CmdRef): String?
    fun removeLink(a: CmdRef, b: CmdRef): String?
    fun setOwner(refs: List<CmdRef>, team: Int): String?
}

/** What a page, a table, an inspector or a context action is given: the open file, the selection, the map. */
class CmdContext(
    val doc: CmdDocument,
    val selection: CmdSelection,
    val map: MapState,
) {
    val world: CmdWorld get() = doc.world
    /** select [ref] and show it on the map */
    fun focus(ref: CmdRef) { selection.set(ref); CommandSession.flyTo(ref) }
    /** turn to [page] (the selection is kept) */
    fun open(page: CmdPage) { CommandSession.page = page }
    /** a line in the status bar (an edit's reason, a count) */
    fun say(text: String) { CommandSession.status = text }
    /** a changed file: the map and the tables read the document again */
    fun changed() { CommandSession.changed() }

    /** the data layer's model of the open file (bridge/command): what every edit changes */
    val core: com.bmscompanion.desktop.bridge.command.CommandSave get() = doc.core

    /**
     * Runs [edit] on the open file's edit log (undoable, counted): true when done; else its reason is in the status
     * line. The map, the tables and the inspector are drawn again either way.
     */
    fun run(edit: com.bmscompanion.desktop.bridge.command.CommandEdit): Boolean {
        val why = doc.core.edits.run(edit)
        if (why != null) say(why) else CommandSession.done(edit.label)
        changed()
        return why == null
    }
}

/** An inspector: the right-hand panel for the refs it [accepts] (the first registered that accepts wins). */
interface CmdInspector {
    fun accepts(ref: CmdRef): Boolean
    @Composable fun Content(ctx: CmdContext, refs: List<CmdRef>)
}

/** A page's table, shown in the bottom drawer while its page is open. */
interface CmdTable {
    val page: CmdPage
    @Composable fun Content(ctx: CmdContext)
}

/** A page whose centre is its own rather than the map ([replacesMap] true: Teams, Campaign, Pilots, Checks, Files). */
interface CmdPageView {
    val page: CmdPage
    val replacesMap: Boolean get() = true
    @Composable fun Content(ctx: CmdContext)
}

/** One line of the map's context menu for the kinds it [accepts]. */
interface CmdAction {
    val label: String
    fun accepts(ctx: CmdContext, ref: CmdRef?): Boolean
    /** greyed with this reason; null = enabled */
    fun refusal(ctx: CmdContext, ref: CmdRef?): String? = null
    /** [north]/[east]: where the menu was opened (a table row's menu: the row's own place) */
    fun run(ctx: CmdContext, ref: CmdRef?, north: Double, east: Double)
    /** only on the map's menu (it needs the point clicked), never on a table row's */
    val mapOnly: Boolean get() = false
}

/**
 * The slots the editor fills. Register in [CommandRegistry.install] (one line each); the shell draws the first
 * inspector that accepts the selection, the table of the open page in the drawer, the page view in the centre, and
 * every accepting action under the built-in ones in the context menu. A page with nothing registered shows a built-in
 * read-only view (CommandBuiltIns) or a sentence.
 */
object CommandSlots {
    val inspectors = ArrayList<CmdInspector>()
    val tables = LinkedHashMap<CmdPage, CmdTable>()
    val pages = LinkedHashMap<CmdPage, CmdPageView>()
    val actions = ArrayList<CmdAction>()
    /** the map's edits (owner, move, links on the model: [CoreMapEdits]); an editor may put its own in */
    var mapEdits: CmdMapEdits? = CoreMapEdits
    /** makes the editing half of an open file instead of [CoreEditing] (null: CoreEditing) */
    var editing: ((CmdDocument) -> CmdEditing?)? = null
    /** drawn over the workspace while a file is open (an editor's dialogs: add a unit, the bulk operations) */
    val overlays = ArrayList<@Composable (CmdContext) -> Unit>()
}

/** The session of the PC window: the open file, the page, the tool, the selection, the dialogs. */
object CommandSession {
    var doc by mutableStateOf<CmdDocument?>(null)
    var page by mutableStateOf(CmdPage.MAP)
    var tool by mutableStateOf(CmdTool.SELECT)
    val selection = CmdSelection()
    /** the team the Owner tool and an area's owner give */
    var ownerTeam by mutableIntStateOf(1)
    /**
     * The status line under the map: a passing message (what was just done, why something was refused), shown for a
     * few seconds and then cleared ([statusAt] = when it was set, so the same words said twice show again).
     */
    var status: String
        get() = statusText
        set(v) { statusText = v; statusAt = System.nanoTime() }
    private var statusText by mutableStateOf("")
    var statusAt by mutableStateOf(0L)
        private set

    /** An edit made: "Done: <what>" in the status line (the edit's own name, as Undo would name it). */
    fun done(what: String) { status = doneLine(what) }

    /** "Fix 3 findings: References" → "Done: fixed 3 findings: References"; any other name → "Done: <name>". */
    fun doneLine(what: String): String {
        val w = what.trim().trimEnd('.')
        val first = w.substringBefore(' ')
        val past = when (first.lowercase()) {
            "fix" -> "fixed"; "add" -> "added"; "delete" -> "deleted"; "remove" -> "removed"; "move" -> "moved"; "clone" -> "cloned"
            "link" -> "linked"; "unlink" -> "unlinked"; "clear" -> "cleared"; "rename" -> "renamed"; "shift" -> "shifted"; "resupply" -> "resupplied"
            "repair" -> "repaired"; "destroy" -> "destroyed"; "build" -> "built"; "recalculate" -> "recalculated"; "set" -> "set"; "reset" -> "reset"
            else -> null
        }
        return if (past != null && w.length > first.length) "Done: $past${w.substring(first.length)}." else "Done: $w."
    }
    /** an error to show in a dialog (a save that failed …) */
    var error by mutableStateOf<String?>(null)
    /** the Open window is up */
    var openWindow by mutableStateOf(false)
    /** bumped at every change of the document, so the map and the tables read it again */
    var version by mutableIntStateOf(0)
        private set
    /** set by the map while it is shown */
    internal var flyHook: ((CmdRef) -> Unit)? = null
    /** where the map drew [CmdRef] in the window on its last frame (for the headless checks), null when not drawn */
    internal var screenHook: ((CmdRef) -> androidx.compose.ui.geometry.Offset?)? = null
    /** opening a file: its name */
    var opening by mutableStateOf<String?>(null)
    /** a check's BMS folder (read only); the program reads the install it found */
    @Volatile var rootOverride: File? = null

    fun flyTo(ref: CmdRef) { flyHook?.invoke(ref) }

    fun changed() {
        doc?.refresh()
        version++
    }

    /** The files opened lately, newest first (kept in the PC's prefs). */
    val recent: List<String> get() = Repo.getString(RECENT)?.split('\n')?.filter { it.isNotBlank() }.orEmpty()

    internal fun remember(file: File) {
        val list = (listOf(file.absolutePath) + recent.filter { !it.equals(file.absolutePath, ignoreCase = true) }).take(10)
        Repo.putString(RECENT, list.joinToString("\n"))
    }

    private const val RECENT = "cmd_recent"
}
