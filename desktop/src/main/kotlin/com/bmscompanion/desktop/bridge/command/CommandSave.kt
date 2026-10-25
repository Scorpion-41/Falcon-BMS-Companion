package com.bmscompanion.desktop.bridge.command

import com.bmscompanion.desktop.bridge.CampaignArchive
import com.bmscompanion.desktop.bridge.Theaters
import java.io.File

/**
 * One campaign, TE or training save opened by the Command section: every part read whole into editable records
 * ([cmp], [uni], [obj], [obd], [tea], [evt], [pol], [plt], [pst]), the parts it does not model ([raw]: `.te`, `.wth`,
 * `.ver`) kept as bytes, and the container's own copy of every part as stored, so [CommandWriter] writes what nobody
 * changed back byte for byte.
 *
 * **Every side is visible**: this is the PC window's own editor (the app's no-cheat rule is for the mission pages).
 *
 * Edits go through [edits] ([CommandEdits]: undoable commands); the records are plain mutable objects the editors
 * may read freely. Objectives: a start carries its own ([obj], editable only in a start — the section's rule 3); a
 * played save takes them from the start its header names ([startObjectives], read only) and records changes in [obd].
 *
 * Opening never writes anything and never throws: what could not be read is in [problems], and a part that did not
 * walk exactly is kept as stored and refuses edits ([partEditable]).
 */
class CommandSave internal constructor(
    val file: File,
    val theaters: Theaters.TheaterSet?,
    val theater: Theaters.Theater?,
    val names: CampaignArchive.Names?,
    val container: SaveContainer.ReadResult,
    /** the `.ver` part (107-110 in 4.38.1) */
    val version: Int,
    val cmp: CmpHeader?,
    val uni: UnitPart?,
    val obj: ObjPart?,
    val obd: ObdPart?,
    val tea: TeaPart?,
    val evt: EvtPart?,
    val pol: PolPart?,
    val plt: PltPart?,
    val pst: PstPart?,
    /** the start the header's scenario names, when this save has no objectives of its own */
    val startFile: File?,
    val startObjectives: ObjPart?,
    val problems: List<String>,
) {
    /** Undo/redo and the edit count. */
    val edits = CommandEdits(this)

    /** The file's time and size when it was read (or last written here): Overwrite refuses a file changed since. */
    var diskModified: Long = runCatching { file.lastModified() }.getOrDefault(0L)
        internal set
    var diskLength: Long = runCatching { file.length() }.getOrDefault(0L)
        internal set

    /** Bumped by every edit, undo and redo (a UI recomposes on it). */
    @Volatile var revision = 0
        private set

    /** Whose file this is (BMS's start, BMS's or an add-on's mission, the pilot's), with the sentence saying why. */
    val identity: ShippedSaves.Identity by lazy { ShippedSaves.identify(file, this) }

    /** "campaign", "te" or "training", by the extension. */
    val missionKind: String get() = CampaignArchive.kindOfName(file.name)

    /** The extra class tables (objective classes, stores, weapon lists, parking points…), read on first use. */
    val tables: ClassTablesMore.Tables by lazy { ClassTablesMore.forTheater(theaters, theater) }

    // ---------------------------------------------------------------- what is held

    val units: List<CmdUnit> get() = uni?.units.orEmpty()
    val flights: List<CmdFlight> get() = units.filterIsInstance<CmdFlight>()
    val packages: List<CmdPackage> get() = units.filterIsInstance<CmdPackage>()
    val squadrons: List<CmdSquadron> get() = units.filterIsInstance<CmdSquadron>()
    val battalions: List<CmdBattalion> get() = units.filterIsInstance<CmdBattalion>()
    val brigades: List<CmdBrigade> get() = units.filterIsInstance<CmdBrigade>()
    val taskForces: List<CmdTaskForce> get() = units.filterIsInstance<CmdTaskForce>()
    val teams: List<TeamEntry> get() = tea?.teams.orEmpty()

    /** The start this save names (`.cmp Scenario`, "save1"): BMS takes the objectives from that file. */
    val scenario: String get() = cmp?.scenario?.text?.trim().orEmpty()

    /**
     * BMS reads **this file's own** `.obj`: only when `Scenario` names the file itself (BMS's starts do) or the start
     * it names is not in the folder. A Save-as copy of a start that keeps `Scenario = "save0"` flies Save0's objectives.
     */
    val objectivesFromOwn: Boolean get() = obj != null && (scenario.equals(file.nameWithoutExtension, ignoreCase = true) || startFile == null)

    /** The objectives this save flies: its own when BMS reads them ([objectivesFromOwn]), else its start's (read only). */
    val objectives: List<CmdObjective> get() = (if (objectivesFromOwn) obj else startObjectives ?: obj)?.objectives.orEmpty()

    /** The objectives may be edited as records (position, links, owner…): only in a start whose own BMS reads. */
    val objectivesEditable: Boolean get() = obj != null && obj.editable && !obj.headerOnly && objectivesFromOwn

    /** BMS ignores this save's `.obd` (and resets every delta) when `Scenario` is the file's own name, case and all. */
    val deltasIgnored: Boolean get() = scenario == file.nameWithoutExtension

    /**
     * Deltas naming an objective the start does not have. BMS stops reading deltas at the first such id, silently
     * dropping the rest; empty when the objectives are not known.
     */
    fun unknownDeltas(): List<CmdDelta> {
        val obd = obd ?: return emptyList()
        if (objectives.isEmpty()) return emptyList()
        val ids = objectives.mapTo(HashSet()) { it.id }
        return obd.deltas.filter { it.id !in ids }
    }

    /**
     * The other saves of this file's folder that name it as their start: editing a start's objectives in place (their
     * number or ids) breaks their deltas and units, so the page warns with this list first.
     */
    fun savesUsingThisStart(): List<File> = savesNaming(file)

    private var indexAt = -1
    private var unitIndex: Map<VuId, CmdUnit> = emptyMap()
    private var objIndex: Map<VuId, CmdObjective> = emptyMap()
    private fun index() {
        if (indexAt == revision) return
        unitIndex = units.associateBy { it.id }
        objIndex = objectives.associateBy { it.id }
        indexAt = revision
    }

    fun unit(id: VuId?): CmdUnit? = id?.takeUnless { it.isNone }?.let { index(); unitIndex[it] }
    fun objective(id: VuId?): CmdObjective? = id?.takeUnless { it.isNone }?.let { index(); objIndex[it] }
    fun delta(id: VuId?): CmdDelta? = id?.let { obd?.delta(it) }
    fun flightsOf(p: CmdPackage): List<CmdFlight> = p.elements.mapNotNull { unit(it) as? CmdFlight }
    fun packageOf(f: CmdFlight): CmdPackage? = unit(f.packageId) as? CmdPackage
    fun squadronOf(f: CmdFlight): CmdSquadron? = unit(f.squadronId) as? CmdSquadron
    fun battalionsOf(b: CmdBrigade): List<CmdBattalion> = b.elements.mapNotNull { unit(it) as? CmdBattalion }
    fun squadInfo(s: CmdSquadron): SquadInfo? = cmp?.squadInfoOf(s.id)

    /** The owner an objective has now: its delta's, else its start's. */
    fun ownerNow(o: CmdObjective): Int = delta(o.id)?.owner ?: o.owner

    // ---------------------------------------------------------------- names

    fun unitKind(type: Int): UnitKind? = names?.ct(type)?.let { kindOf(it) }
    fun unitClass(u: CmdUnit): String? = names?.unitClass(u.type)
    fun aircraft(u: CmdUnit): String? = names?.aircraft(u.type)
    fun weapon(id: Int): String? = names?.weapon(id)
    fun string(id: Int): String? = names?.strings?.get(id)
    fun teamName(n: Int): String = teams.getOrNull(n)?.team?.name?.text?.takeIf { it.isNotBlank() }
        ?: cmp?.teams?.getOrNull(n)?.name?.text?.takeIf { it.isNotBlank() } ?: "Team $n"
    fun callsign(f: CmdFlight): String =
        (string(CampaignArchive.CALLSIGN_STRINGS + f.callsignId) ?: "Callsign ${f.callsignId}") + (if (f.callsignNum in 1..99) "${f.callsignNum}" else "")
    fun pilotName(n: Int): String? = string(PILOT_STRINGS + n)

    /** Which part kinds walked exactly and may be edited (`cmp`, `uni`, `obj`, `obd`, `tea`, `evt`, `pol`, `plt`, `pst`). */
    fun partEditable(ext: String): Boolean = when (ext) {
        "cmp" -> cmp != null
        "uni" -> uni?.editable == true
        "obj" -> obj?.editable == true
        "obd" -> obd?.editable == true
        "tea" -> tea?.editable == true
        "evt" -> evt?.editable == true
        "pol" -> pol?.editable == true
        "plt" -> plt?.editable == true
        "pst" -> pst?.editable == true
        else -> false
    }

    /** The parts kept as bytes only (`.te`, `.wth`, `.ver`, and any not understood). */
    val raw: List<SaveContainer.Entry> get() = container.entries.filter { it.ext !in MODELLED }

    /** The TE part's text (the victory conditions), when the save has one. */
    val teText: String? get() = container.entry("te")?.stored?.let { b -> String(b, ANSI).trimEnd('\u0000').takeIf { it.isNotBlank() } }

    internal fun changed() { revision++ }

    companion object {
        const val PILOT_STRINGS = 2300
        val MODELLED = setOf("cmp", "uni", "obj", "obd", "tea", "evt", "pol", "plt", "pst")
        const val OLDEST = 107

        fun kindOf(ct: CampaignArchive.Ct): UnitKind? = when {
            ct.domain == 2 && ct.type == 1 -> UnitKind.FLIGHT
            ct.domain == 2 && ct.type == 2 -> UnitKind.PACKAGE
            ct.domain == 2 && ct.type == 3 -> UnitKind.SQUADRON
            ct.domain == 3 && ct.type == 1 -> UnitKind.BATTALION
            ct.domain == 3 && ct.type == 2 -> UnitKind.BRIGADE
            ct.domain == 4 && ct.type == 1 -> UnitKind.TASKFORCE
            else -> null
        }

        /**
         * Opens [file] (read whole, read only, no lock kept). [set] is the install's theater definitions (null:
         * found from the file's folder upwards); the theater is the one whose `campaigndir` holds the file. Never
         * throws.
         */
        fun open(file: File, set: Theaters.TheaterSet? = null): CommandSave {
            val problems = ArrayList<String>()
            val blob = try { file.readBytes() } catch (e: Throwable) {
                problems += "Could not read ${file.name}: ${e.message ?: e.javaClass.simpleName}."
                ByteArray(0)
            }
            return fromBytes(file, blob, set, problems)
        }

        /** [open] for bytes already in memory (the writer's verification reads its temp file this way). */
        fun fromBytes(file: File, blob: ByteArray, set: Theaters.TheaterSet?, problemsIn: List<String> = emptyList(), withStart: Boolean = true): CommandSave {
            val problems = ArrayList(problemsIn)
            val theaters = set ?: findTheaters(file)
            val theater = theaters?.owning(file.absoluteFile.parentFile ?: file)
            val names = theaters?.let { runCatching { CampaignArchive.names(it, theater) }.getOrNull() }
            val c = SaveContainer.read(blob)
            if (blob.isNotEmpty() && c.error != null) problems += "${file.name} is not a save Falcon BMS can read: ${c.error}"
            c.entries.forEach { e -> e.error?.let { problems += "${e.name}: $it." } }
            val version = c.entry("ver")?.stored?.let { String(it, ANSI).trim { ch -> ch <= ' ' || ch == '\u0000' }.toIntOrNull() } ?: 0
            if (c.error == null && blob.isNotEmpty()) {
                if (version == 0) problems += "${file.name} has no readable version part."
                else if (version < OLDEST) problems += "${file.name} is format $version, older than Falcon BMS 4.38's ($OLDEST-110): it can be looked at, not saved."
            }
            fun <T> part(ext: String, what: String, read: (SaveContainer.Entry) -> T?): T? {
                val e = c.entry(ext) ?: return null
                if (e.packed && e.unpacked == null) return null
                return try { read(e) } catch (t: Throwable) { problems += "The $what could not be read: ${t.message ?: t.javaClass.simpleName}."; null }
            }
            val cmp = part("cmp", "campaign header") { CmpHeader.read(it.content) }
            val kindOf: (Int) -> UnitKind? = { t -> names?.ct(t)?.let { kindOf(it) } }
            if (c.entry("uni") != null && (names == null || names.error != null)) problems += names?.error ?: "No class table was found for ${file.name}'s theater: its units cannot be read."
            val uni = if (names?.error == null && names != null) part("uni", "unit part") { UnitPart.read(it.content, it.records, version, kindOf) } else null
            val obj = part("obj", "objective part") { ObjPart.read(it.content, it.records) }
            val obd = part("obd", "objective delta part") { ObdPart.read(it.content, it.records) }
            val tea = part("tea", "team part") { TeaPart.read(it.content) }
            val evt = part("evt", "event part") { EvtPart.read(it.content) }
            val pol = part("pol", "primary objective part") { PolPart.read(it.content) }
            val plt = part("plt", "pilot part") { PltPart.read(it.content) }
            val pst = part("pst", "persistent object part") { PstPart.read(it.content) }
            uni?.stop?.let { problems += "The units could not all be read ($it): the unit part is kept as it is and cannot be edited." }
            obj?.stop?.let { problems += "The objectives could not all be read ($it)." }
            obd?.stop?.let { problems += "The objective deltas could not all be read ($it)." }
            tea?.stop?.let { problems += "The teams could not all be read ($it)." }
            for ((p, what) in listOf(evt?.stop to "events", pol?.stop to "primary objectives", plt?.stop to "pilots", pst?.stop to "persistent objects")) {
                if (p != null) problems += "The $what could not be read ($p)."
            }
            var startFile: File? = null
            var startObj: ObjPart? = null
            val scen = cmp?.scenario?.text?.trim().orEmpty()
            if (cmp != null && !scen.equals(file.nameWithoutExtension, ignoreCase = true)) startFile = startOf(file, scen)
            if (withStart && cmp != null && startFile != null) {
                startObj = startFile.let { sf ->
                    runCatching {
                        val sc = SaveContainer.read(sf.readBytes())
                        sc.entry("obj")?.takeIf { it.unpacked != null }?.let { ObjPart.read(it.unpacked!!, it.records) }
                    }.getOrNull()
                }
                if (startObj == null) problems += "The start this save names (\"$scen\", ${startFile.name}) has no readable objectives."
            }
            if (withStart && cmp != null && obj == null && startFile == null) problems += "The start this save was made from (\"$scen\") was not found beside it: its objectives are not shown."
            return CommandSave(file, theaters, theater, names, c, version, cmp, uni, obj, obd, tea, evt, pol, plt, pst, startFile, startObj, problems)
        }

        /**
         * The saves of [start]'s folder (other than [start]) whose header's `Scenario` names it — read only, header
         * part only. BMS opens the start by that name (case does not matter to Windows).
         */
        fun savesNaming(start: File): List<File> {
            val dir = start.absoluteFile.parentFile ?: return emptyList()
            val base = start.nameWithoutExtension
            return runCatching { dir.listFiles()?.toList() }.getOrNull().orEmpty()
                .filter { it.isFile && ShippedSaves.isSave(it) && !it.name.equals(start.name, ignoreCase = true) }
                .filter { f ->
                    runCatching {
                        val e = SaveContainer.read(f.readBytes()).entry("cmp")?.unpacked ?: return@runCatching false
                        CmpHeader.read(e).scenario.text.trim().equals(base, ignoreCase = true)
                    }.getOrDefault(false)
                }
                .sortedBy { it.name.lowercase() }
        }

        /** The start named [scenario] in [save]'s folder, the save's own extension first, matched without case. */
        fun startOf(save: File, scenario: String): File? {
            val s = scenario.trim().takeIf { it.isNotEmpty() } ?: return null
            val dir = save.absoluteFile.parentFile ?: return null
            val listing = runCatching { dir.listFiles()?.toList() }.getOrNull().orEmpty()
            val own = save.extension.lowercase()
            val order = if (own == "trn") listOf("tac", "trn", "cam") else (listOf(own) + listOf("cam", "tac", "trn")).distinct()
            for (ext in order) listing.firstOrNull { it.isFile && it.name.equals("$s.$ext", ignoreCase = true) }?.let { return it }
            return null
        }

        /** The install a save lies in: the folder above whose `Data` holds the theater list. */
        fun findTheaters(file: File): Theaters.TheaterSet? {
            var d: File? = file.absoluteFile.parentFile
            while (d != null) {
                if (File(d, "Data/TerrData/TheaterDefinition/theater.lst").isFile) return runCatching { Theaters.at(d) }.getOrNull()
                d = d.parentFile
            }
            return null
        }
    }
}
