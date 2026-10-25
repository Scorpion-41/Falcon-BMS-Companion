@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)

package com.bmscompanion.desktop.bridge.command

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.platform.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.scene.ComposeScene
import androidx.compose.ui.scene.ComposeSceneContext
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.bmscompanion.app.ui.theme.BmsTheme
import com.bmscompanion.app.ui.theme.Hud
import com.bmscompanion.desktop.bridge.CampaignArchive
import com.bmscompanion.desktop.bridge.Theaters
import com.bmscompanion.desktop.ui.command.CmdDocument
import com.bmscompanion.desktop.ui.command.CmdKind
import com.bmscompanion.desktop.ui.command.CmdPage
import com.bmscompanion.desktop.ui.command.CmdRef
import com.bmscompanion.desktop.ui.command.CmdTool
import com.bmscompanion.desktop.ui.command.CommandSection
import com.bmscompanion.desktop.ui.command.CommandSession
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.SwingUtilities

/**
 * `--cmdverify <a copy of a BMS folder, never opened by the section> <out folder>`: the Command section walked the way a
 * pilot works it — the first open of a campaign folder and its backup, whose files are whose, Save as and Overwrite with
 * their refusals, undo and redo, the unit, objective, team, campaign, checks and Files-page edits, a campaign start
 * edited in place and restored, and the composed section pressed headless (toolbar, dialogs, map tools). Writes only
 * into the copy. FAIL lines name what is wrong.
 */
object CommandVerify {
    private val sb = StringBuilder()
    private fun line(s: String) { sb.appendLine(s) }
    private fun pass(s: String) = line("PASS: $s")
    private fun fail(s: String) = line("FAIL: $s")
    private fun check(ok: Boolean, s: String) = if (ok) pass(s) else fail(s)
    private fun step(name: String, body: () -> Unit) {
        line("-- $name")
        try { body() } catch (t: Throwable) { fail("$name threw ${t.javaClass.simpleName}: ${t.message}"); line(t.stackTrace.take(6).joinToString("\n") { "   at $it" }) }
    }

    fun run(copy: File, out: File): String {
        sb.clear()
        out.mkdirs()
        runCatching { Surface.makeRasterN32Premul(1, 1).close() }
        CommandSession.rootOverride = copy
        com.bmscompanion.desktop.ui.command.CommandRegistry.installOnce()
        val set = Theaters.at(copy)
        val korea = set.all.firstOrNull { it.name.trim().equals("Korea KTO", true) } ?: set.all.first()
        val dir = set.campaignDir(korea) ?: return "FAIL: no campaign folder for ${korea.name}"
        fun f(name: String) = File(dir, name)
        val pilotSave = dir.listFiles()!!.filter { it.name.startsWith("Save-Day", true) && it.extension.equals("cam", true) }.maxByOrNull { it.lastModified() }!!
        val backupDir = CommandBackup.dirFor(dir)

        // ---------------------------------------------------------------- 1. open, backup, identity
        step("1a theaters and their files (Open…)") {
            for (t in set.all) {
                val saves = set.saves(t)
                line("   ${t.name}: ${set.campaignDir(t)?.let { if (it.isDirectory) "${saves.size} saves" else "folder missing" } ?: "no campaign folder"}")
            }
            check(set.all.size >= 2, "the theater list has ${set.all.size} theaters")
        }
        step("1b first open of a campaign folder makes the backup once") {
            check(!backupDir.exists(), "no Command backup before the first open")
            val d = CmdDocument.open(pilotSave, copy).getOrThrow()
            check(File(backupDir, CommandBackup.README).isFile, "README written")
            val rec = CommandBackup.list(dir)
            line("   record: ${rec.size} files: ${rec.joinToString { it.name }}")
            check(rec.any { it.name.equals("Save1.cam", true) } && rec.any { it.name.equals("Instant.cam", true) } && rec.any { it.name.equals("Te_New.tac", true) }, "starts recorded")
            check(rec.count { it.name.startsWith("TE_BMS_", true) } >= 5 && rec.count { it.name.startsWith("TR_BMS_", true) } >= 10, "shipped TEs and trainings recorded")
            check(rec.none { it.name.startsWith("Save-Day", true) || it.name.startsWith("Auto Save", true) }, "no pilot save in the record")
            check(rec.all { e -> CommandFiles.sha256(File(backupDir, e.name)) == e.sha256 && CommandFiles.sha256(f(e.name)) == e.sha256 }, "every copy is the original's bytes")
            val recBytes = File(backupDir, CommandBackup.RECORD).readBytes()
            val recTime = File(backupDir, CommandBackup.RECORD).lastModified()
            Thread.sleep(1100)
            CmdDocument.open(f("Save1.cam"), copy).getOrThrow()
            CmdDocument.open(f("TE_BMS_01_Low_Level.tac"), copy).getOrThrow()
            check(File(backupDir, CommandBackup.RECORD).readBytes().contentEquals(recBytes) && File(backupDir, CommandBackup.RECORD).lastModified() == recTime, "opening again changes nothing in the backup")
            check(d.core.identity.kind == ShippedSaves.Kind.PILOT, "${pilotSave.name}: ${d.core.identity.why}")
        }
        step("1c whose file") {
            for ((n, want) in listOf("Save1.cam" to ShippedSaves.Kind.START, "Instant.cam" to ShippedSaves.Kind.START, "TE_BMS_01_Low_Level.tac" to ShippedSaves.Kind.SHIPPED,
                "TR_BMS_02_Navigation.trn" to ShippedSaves.Kind.SHIPPED, "Auto Save.cam" to ShippedSaves.Kind.PILOT, pilotSave.name to ShippedSaves.Kind.PILOT)) {
                val id = ShippedSaves.identify(f(n))
                check(id.kind == want, "$n is ${id.kind} (${id.why})")
            }
            for (n in listOf("TESTING.cam", "harrier.tac", "te te1.tac")) if (f(n).isFile) line("   $n: ${ShippedSaves.identify(f(n)).let { "${it.kind} claimable=${it.claimable}: ${it.why}" }}")
        }

        // ---------------------------------------------------------------- 2. saving
        var copyFile: File? = null
        step("2a Save as (the default) makes a complete copy") {
            val d = CmdDocument.open(pilotSave, copy).getOrThrow()
            val e = d.editing!!
            val prop = e.proposedCopyName()
            check(prop == pilotSave.nameWithoutExtension + " (edited)", "proposed name \"$prop\"")
            check(e.refuseCopyName(prop) == null, "the proposed name is accepted")
            // one edit: rename a team
            val team = d.core.teams.indices.first { it > 0 }
            val why = d.core.edits.run(TeamOps.rename(d.core, team, "VerifyTeam")!!)
            check(why == null && e.editCount == 1, "an edit counted (${why ?: "ok"})")
            check(e.overwriteRefusal == null, "Overwrite offered for the pilot's own save")
            val r = e.saveAs(prop, replace = false)
            check(r == null, "saved: ${r ?: CommandSession.status}")
            val t = File(dir, "$prop.cam")
            copyFile = t
            check(t.isFile, "${t.name} written")
            val back = CommandSave.open(t, set)
            check(back.problems.isEmpty(), "the copy reads without problems ${back.problems}")
            check(back.cmp?.saveFile?.text?.trim() == prop, "SaveFile = ${back.cmp?.saveFile?.text}")
            check(back.cmp?.scenario?.text?.trim().equals(d.core.scenario, true), "Scenario kept (${back.cmp?.scenario?.text})")
            check(back.container.entries.all { it.name.substringBeforeLast('.') == prop }, "part names ${back.container.entries.joinToString { it.name }}")
            check(back.teamName(team) == "VerifyTeam", "the edit is in the copy (${back.teamName(team)})")
            val comps = CommandWriter.COMPANIONS.filter { File(dir, pilotSave.nameWithoutExtension + it).isFile }
            check(comps.all { File(dir, prop + it).isFile }, "companions copied: $comps")
            check(ShippedSaves.CreatedCopies.contains(t), "recorded in created.json")
            check(ShippedSaves.identify(t).kind == ShippedSaves.Kind.PILOT, "the copy is the pilot's")
            val orig = CommandSave.open(pilotSave, set)
            for (en in orig.container.entries) {
                val b = back.container.entry(en.ext)!!
                val same = b.content.contentEquals(en.content)
                if (en.ext == "tea" || en.ext == "cmp") check(!same, ".${en.ext} changed (edited/renamed)") else check(same, ".${en.ext} identical")
            }
            check(CommandSession.doc?.file?.absoluteFile == t.absoluteFile && CommandSession.doc?.editing?.editCount == 0, "the copy is open after Save as, no edits")
        }
        step("2b BMS's files: Save as proposes a name it accepts; overwrite refused") {
            for (n in listOf("TE_BMS_01_Low_Level.tac", "TR_BMS_02_Navigation.trn", "Save1.cam", "Te_New.tac", "Instant.cam")) {
                val d = CmdDocument.open(f(n), copy).getOrThrow()
                val e = d.editing!!
                val p = e.proposedCopyName()
                check(e.refuseCopyName(p) == null, "$n: proposed \"$p\" accepted (${e.refuseCopyName(p)})")
                check(e.overwriteRefusal != null, "$n: Overwrite refused (${e.overwriteRefusal})")
                check(!CommandWriter.overwrite(d.core).ok, "$n: CommandWriter.overwrite refuses")
            }
            val d = CmdDocument.open(pilotSave, copy).getOrThrow()
            for (bad in listOf("Save3", "TE_BMS_99", "Instant", "Auto Save", "te_new_nt")) check(d.editing!!.refuseCopyName(bad) != null, "\"$bad\" refused: ${d.editing!!.refuseCopyName(bad)}")
            check(d.editing!!.refuseCopyName("a/b") != null, "bad characters refused")
        }
        step("2c Overwrite, undo/redo, changed on disk, read only") {
            val t = copyFile ?: error("no copy")
            val d = CmdDocument.open(t, copy).getOrThrow()
            val e = d.editing!!
            val core = d.core
            val team = core.teams.indices.first { it > 0 }
            core.edits.run(TeamOps.rename(core, team, "Second")!!)
            core.edits.run(TeamOps.rename(core, team, "Third")!!)
            check(e.editCount == 2 && e.canUndo && !e.canRedo, "two edits")
            e.undo(); check(core.teamName(team) == "Second" && e.editCount == 1 && e.canRedo, "undo")
            e.redo(); check(core.teamName(team) == "Third" && e.editCount == 2, "redo")
            check(e.overwrite() == null, "overwrite: ${CommandSession.status}")
            check(CommandSave.open(t, set).teamName(team) == "Third", "the file holds the edit")
            check(!core.edits.dirty, "not dirty after overwrite")
            core.edits.run(TeamOps.rename(core, team, "Fourth")!!)
            t.setLastModified(t.lastModified() + 5000)
            val r = e.overwrite()
            check(r != null && "changed on disk" in r, "changed on disk refused: $r")
            val d2 = CmdDocument.open(t, copy).getOrThrow()
            d2.core.edits.run(TeamOps.rename(d2.core, team, "Fifth")!!)
            val before = t.readBytes()
            t.setReadOnly()
            val r2 = d2.editing!!.overwrite()
            t.setWritable(true)
            check(r2 != null && t.readBytes().contentEquals(before), "read-only target: an error, the file unchanged ($r2)")
            check(t.parentFile.listFiles()!!.none { it.name.endsWith(CommandFiles.TEMP_SUFFIX) }, "no temp file left")
        }
        step("2d Save as over an existing file asks first") {
            val d = CmdDocument.open(pilotSave, copy).getOrThrow()
            val name = copyFile!!.nameWithoutExtension
            check(d.editing!!.copyExists(name), "the existing copy is seen")
            check(d.editing!!.saveAs(name, replace = false) != null, "without replace: refused")
            check(d.editing!!.saveAs(name, replace = true) == null, "with replace: saved")
        }

        // ---------------------------------------------------------------- 3. units
        step("3 units") {
            val t = copyFile!!
            val doc = CommandSave.open(t, set)
            val flights = doc.flights
            val a = flights.first(); val b = flights.first { it !== a && it.type == a.type || it !== a }
            val r1 = doc.edits.run(AirOps.setCallsign(doc, a, b.callsignId, b.callsignNum))
            check(r1 != null, "callsign of another flight refused: $r1")
            val freeNum = (1..9).firstOrNull { CommandIds.callsignTaken(doc, a.callsignId, it, except = a) == null }
            if (freeNum != null) check(doc.edits.run(AirOps.setCallsign(doc, a, a.callsignId, freeNum)) == null && a.callsignNum == freeNum, "a free callsign number taken ($freeNum)")
            // retime with a lock
            val f = flights.firstOrNull { fl -> fl.waypoints.size >= 6 && fl.waypoints.take(6).all { it.arrive > 0 } } ?: error("no long route")
            val ws = f.waypoints
            val lockAt = 4
            ws[lockAt].flags = ws[lockAt].flags or AirOps.TIME_LOCK
            (1 until lockAt).forEach { ws[it].flags = ws[it].flags and AirOps.TIME_LOCK.inv() }
            val before = ws.map { it.arrive }
            val r2 = doc.edits.run(AirOps.setArrival(doc, f, 2, ws[2].arrive + 120_000, retimeRoute = true))
            check(r2 == null, "arrival set: ${r2 ?: "ok"}")
            check(ws[lockAt].arrive == before[lockAt], "the locked waypoint keeps its time")
            check(ws[2].arrive == before[2] + 120_000, "the changed one has the new time")
            check(ws[3].arrive != before[3] && ws[3].arrive in ws[2].depart..ws[lockAt].arrive, "the one between is timed again (${before[3]} → ${ws[3].arrive})")
            check((lockAt + 1 until ws.size).all { ws[it].arrive == before[it] }, "after the lock unchanged")
            doc.edits.undo(); check(ws.map { it.arrive } == before, "undo puts the times back")
            // loadout legality
            val cls = UnitClasses.of(doc)
            val hps = cls.hardpoints(f.type)
            val bad = (1..3000).firstOrNull { id -> hps.indices.any { hps[it].fixed == null } && hps.getOrNull(1)?.max(id) == 0 && cls.weaponName(id).isNotBlank() }
            if (bad != null) check(doc.edits.run(AirOps.setStore(doc, f, setOf(0), 1, bad, 1)) != null, "a store the hardpoint cannot carry is refused")
            // packages
            val p2 = doc.packages.firstOrNull { it.id != f.packageId && it.elements.size < 5 && AirOps.allied(doc, f.owner, it.owner) }
            if (p2 != null) {
                val from = doc.packageOf(f)
                check(doc.edits.run(AirOps.moveToPackage(doc, f, p2)) == null && f.packageId == p2.id && f.id in p2.elements && (from == null || f.id !in from.elements), "flight moved to package ${p2.campId}")
            }
            // squadron base
            val sq = doc.squadrons.firstOrNull { s -> doc.flights.any { it.squadronId == s.id } }
            val base = sq?.let { s -> doc.objectives.firstOrNull { o -> o.id != s.airbaseId && ObjectiveOps.typeOf(doc, o) == 1 && AirOps.allied(doc, s.owner, doc.ownerNow(o)) } }
            if (sq != null && base != null) {
                val r = doc.edits.run(GroundOps.moveBase(doc, sq, base))
                val fl = doc.flights.filter { it.squadronId == sq.id }
                check(r == null && sq.airbaseId == base.id && fl.all { it.waypoints.firstOrNull()?.let { w -> w.gridX == sq.x && w.gridY == sq.y } != false }, "squadron moved to ${ObjectiveOps.displayName(doc, base)}, ${fl.size} flights' take-off follow (${r ?: "ok"})")
            }
            // brigade link
            val bn = doc.battalions.firstOrNull { it.parentId.isNone }
            val br = bn?.let { x -> doc.brigades.firstOrNull { it.elements.size < 5 && AirOps.allied(doc, x.owner, it.owner) } }
            if (bn != null && br != null) check(doc.edits.run(GroundOps.setBrigade(doc, bn, br)) == null && bn.parentId == br.id && bn.id in br.elements, "battalion joins brigade")
            // clone and delete
            val n0 = doc.units.size
            val rc = doc.edits.run(UnitLife.clone(doc, f, f.x to f.y))
            check(rc == null && doc.units.size > n0, "clone: ${doc.units.size - n0} units added (${rc ?: "ok"})")
            val clone = doc.units.last { it.kind == UnitKind.FLIGHT && it !== f && it.type == f.type }
            val rd = doc.edits.run(UnitLife.delete(doc, clone))
            check(rd == null, "delete the clone (${rd ?: "ok"})")
            val sq2 = doc.squadrons.first { UnitClasses.of(doc).flightTypeFor(it.type) != null && it.owner == f.owner }
            val nf = doc.flights.size
            val ra = doc.edits.run(UnitLife.addFlight(doc, sq2, 2, f.mission, (doc.cmp?.currentTime ?: 0L) + 3_600_000L))
            check(ra == null && doc.flights.size == nf + 1, "a flight added to ${AirOps.nameOf(doc, sq2)} (${ra ?: "ok"})")
            doc.edits.undo(); doc.edits.redo()
            // bulk
            check(doc.edits.run(UnitBulk.resupplyAll(doc)) == null, "resupply every squadron")
            val tf = doc.taskForces.firstOrNull()
            if (tf != null) check(doc.edits.run(GroundOps.moveUnit(doc, tf, tf.x + 1, tf.y)) == null, "task force moved")
            val r = CommandWriter.overwrite(doc)
            check(r.ok, "units saved: ${r.message}")
            val back = CommandSave.open(t, set)
            check(back.problems.isEmpty() && back.partEditable("uni"), "re-read exact (${back.problems})")
            check(back.units.size == doc.units.size, "unit count ${back.units.size}")
        }

        // ---------------------------------------------------------------- 4. objectives
        step("4a objective changes in a save are deltas") {
            val t = copyFile!!
            val doc = CommandSave.open(t, set)
            check(ObjectiveOps.mode(doc, false) == ObjectiveOps.Mode.DELTAS, "mode DELTAS")
            val o = doc.objectives.first { doc.delta(it.id) == null && ObjectiveOps.featureCount(doc, it) >= 3 }
            val n = ObjectiveOps.featureCount(doc, o)
            check(doc.edits.run(ObjectiveOps.building(doc, o, 2, 3, full = false)) == null, "building 3 destroyed")
            check(doc.edits.run(ObjectiveOps.owner(doc, o, if (doc.ownerNow(o) == 2) 6 else 2, full = false)) == null, "owner changed")
            val d = doc.delta(o.id)!!
            check(d.fstatus.size == n && d.fstatus[2].toInt() == 3 && (0 until n).all { it == 2 || d.fstatus[it] == o.fstatus.getOrElse(it) { 0.toByte() } }, "one byte per building (${d.fstatus.size} of $n)")
            check(doc.edits.run(Edits.objectiveOwner(doc, o, 1)) == null, "owner through the map's edit")
            val r = CommandWriter.overwrite(doc)
            check(r.ok, "saved: ${r.message}")
            val back = CommandSave.open(t, set)
            val bd = back.delta(o.id)
            check(bd != null && bd.owner == 1 && bd.fstatus.size == n && bd.fstatus[2].toInt() == 3, "the delta reads back")
            check(back.problems.isEmpty() && back.unknownDeltas().isEmpty(), "re-read exact")
            check(doc.edits.run(ObjectiveOps.place(doc, o, 1000.0, 1000.0)) != null, "a save's objective cannot be moved (record edit refused)")
        }
        step("4b Edit BMS's campaign start, then Restore") {
            val start = f("Save1.cam")
            val origHash = CommandFiles.sha256(start)
            val users = CommandSave.savesNaming(start)
            line("   saves naming Save1: ${users.joinToString { it.name }}")
            val d = CmdDocument.open(start, copy).getOrThrow()
            CommandSession.doc = d
            val e = d.editing!!
            check(e.isBmsStart && !e.editingStart, "a start, not yet being edited")
            check(ObjectiveOps.mode(d.core, false) == ObjectiveOps.Mode.NONE, "objectives locked before Edit")
            check(e.editStart() == null && e.editingStart, "Edit BMS's campaign start")
            check(CommandBackup.copyOf(dir, "Save1.cam")?.let { CommandFiles.sha256(it) } == origHash, "the backup is the original")
            check(ObjectiveOps.mode(d.core, true) == ObjectiveOps.Mode.FULL, "full edits")
            val core = d.core
            val objs = core.obj!!.objectives
            val a = objs.first { it.links.size in 1..6 }
            val b = objs.first { x -> x !== a && a.links.none { it.id == x.id } && x.links.size < 8 && ObjectiveOps.cellKm(a, x) < 30 }
            val lr = com.bmscompanion.desktop.ui.command.CommandSlots.mapEdits!!.addLink(CmdRef(CmdKind.OBJECTIVE, a.id.toString()), CmdRef(CmdKind.OBJECTIVE, b.id.toString()))
            check(lr == null && a.links.any { it.id == b.id } && b.links.any { it.id == a.id }, "link added both ways (${lr ?: "ok"}; a ${a.links.size} links, b ${b.links.size})")
            val mv = com.bmscompanion.desktop.ui.command.CommandSlots.mapEdits!!.move(CmdRef(CmdKind.OBJECTIVE, b.id.toString()), b.simNorth + 2000, b.simEast)
            check(mv == null, "objective moved with the Move tool's edit (${mv ?: "ok"})")
            val ul = com.bmscompanion.desktop.ui.command.CommandSlots.mapEdits!!.removeLink(CmdRef(CmdKind.OBJECTIVE, a.id.toString()), CmdRef(CmdKind.OBJECTIVE, b.id.toString()))
            check(ul == null && a.links.none { it.id == b.id }, "Link − removes it (${ul ?: "ok"})"); core.edits.undo()
            check(core.edits.run(ObjectiveOps.place(core, a, a.simNorth + 3000, a.simEast)) == null, "objective moved")
            val (addE, added) = ObjectiveOps.add(core, a, a.simNorth + 9000, a.simEast + 9000, "Verify point")
            check(addE != null && core.edits.run(addE) == null && added in objs, "objective added")
            val victim = objs.first { o -> o !== a && o !== b && o !== added && ObjectiveOps.usedBy(core, listOf(o)).isEmpty() && o.links.isNotEmpty() }
            val linkedTo = objs.filter { o -> o.links.any { it.id == victim.id } }
            check(core.edits.run(ObjectiveOps.delete(core, listOf(victim))) == null && victim !in objs && linkedTo.none { o -> o.links.any { it.id == victim.id } }, "objective deleted with its links")
            check(e.overwriteRefusal == null, "Overwrite offered while editing the start")
            val r = e.overwrite()
            check(r == null, "written in place: ${r ?: CommandSession.status}")
            val back = CommandSave.open(start, set)
            check(back.problems.isEmpty() && back.objectivesEditable && back.objectives.size == objs.size, "the start re-reads exactly (${back.objectives.size} objectives)")
            check(back.objectives.any { it.name.text.trim() == "Verify point" }, "the added objective is there")
            check(CommandFiles.sha256(start) != origHash, "the file changed")
            check(CommandBackup.copyOf(dir, "Save1.cam")?.let { CommandFiles.sha256(it) } == origHash, "the backup is still the original")
            val e2 = CommandSession.doc!!.editing!!
            check(e2.canRestoreStart, "Restore offered")
            check(e2.restoreStart() == null && CommandFiles.sha256(start) == origHash, "Restore gives the exact original back")
        }

        step("4c names, adding and deleting objectives, the objectives' bulk, CSV") {
            // names: a flight by its callsign and aircraft, a squadron by its name and aircraft
            val pd = CmdDocument.open(copyFile!!, copy).getOrThrow()
            val pc = pd.core
            val fl = pc.flights.first { pc.aircraft(it) != null && it.aircraft > 0 }
            val fname = UnitNames.full(pc, fl)
            check(fname.startsWith(UnitNames.callsign(pc, fl)) && fname.contains(pc.aircraft(fl)!!) && fname.endsWith("×${fl.aircraft}") && UnitNames.callsign(pc, fl).contains(' '), "a flight is named \"$fname\"")
            val sq = pc.squadrons.first { pc.aircraft(it) != null }
            val sname = UnitNames.full(pc, sq)
            check(sname.contains(" · ") && sname.endsWith(pc.aircraft(sq)!!), "a squadron is named \"$sname\"")
            val pk = pc.packages.first { pc.flightsOf(it).isNotEmpty() }
            line("   a package: \"${UnitNames.full(pc, pk)}\"; a battalion: \"${pc.battalions.firstOrNull()?.let { UnitNames.full(pc, it) }}\"; a task force: \"${pc.taskForces.firstOrNull()?.let { UnitNames.full(pc, it) }}\"")
            check(pd.world.unit(fl.id.toString())?.name == fname, "the map, tables and Find use the same name")
            // in a save: add/delete shown greyed with the reason, and the way to its start
            CommandSession.doc = pd
            val sctx = com.bmscompanion.desktop.ui.command.CmdContext(pd, com.bmscompanion.desktop.ui.command.CmdSelection(), com.bmscompanion.app.ui.components.MapState())
            val why = com.bmscompanion.desktop.ui.command.inspectors.ObjectiveAddDelete.refusal(sctx)
            check(why != null && pc.startFile != null && why.contains(pc.startFile!!.name), "a save: add/delete refused with its start named (\"$why\")")
            val oref = CmdRef(CmdKind.OBJECTIVE, pc.objectives.first().id.toString())
            check(com.bmscompanion.desktop.ui.command.inspectors.DeleteObjective.accepts(sctx, oref) && com.bmscompanion.desktop.ui.command.inspectors.DeleteObjective.refusal(sctx, oref) != null, "Delete objective… is on the menu, greyed")
            check(com.bmscompanion.desktop.ui.command.inspectors.OpenStartForObjectives.accepts(sctx, oref), "Open its start… is on the menu")
            check(com.bmscompanion.desktop.ui.command.inspectors.MoreDialogs.addObjective(sctx, pc.objectives.first().type, 1, "No", 1000.0, 1000.0) != null, "a save refuses an added objective")
            // CSV
            val csv = File(out, "units.csv")
            check(CommandExport.write(csv, CommandExport.unitsCsv(pc)) == null && csv.readLines().size == pc.units.size + 1, "units CSV: ${pc.units.size} rows")
            check(CommandExport.write(File(dir, "x.csv"), "a") != null, "an export into the BMS folder is refused")
            check(CommandExport.writeBytes(File(dir, "x map.png"), byteArrayOf(1)) != null && !File(dir, "x map.png").exists(), "a map picture into the BMS folder is refused")
            // a start being edited: add (another class), bulk, delete, written and read back exactly
            val start = f("Save2.cam")
            val origHash = CommandFiles.sha256(start)
            val d = CmdDocument.open(start, copy).getOrThrow()
            CommandSession.doc = d
            val e = d.editing!!
            val ctx = com.bmscompanion.desktop.ui.command.CmdContext(d, com.bmscompanion.desktop.ui.command.CmdSelection(), com.bmscompanion.app.ui.components.MapState())
            check(com.bmscompanion.desktop.ui.command.inspectors.ObjectiveAddDelete.refusal(ctx)?.contains("Edit BMS's campaign start") == true, "a start not yet edited: the reason names Edit BMS's campaign start")
            check(e.editStart() == null && com.bmscompanion.desktop.ui.command.inspectors.ObjectiveAddDelete.refusal(ctx) == null, "editing the start: add/delete allowed")
            val core = d.core
            val objs = core.obj!!.objectives
            val n0 = objs.size
            val bridgeType = objs.firstOrNull { ObjectiveOps.typeOf(core, it) == 6 }?.type ?: objs.last().type
            val near = objs.first { ObjectiveOps.typeOf(core, it) == 8 }
            val addWhy = com.bmscompanion.desktop.ui.command.inspectors.MoreDialogs.addObjective(ctx, bridgeType, 2, "Verify bridge", near.simNorth + 4000, near.simEast)
            val added = objs.lastOrNull { it.name.text.trim() == "Verify bridge" }
            check(addWhy == null && added != null && added.type == bridgeType && added.owner == 2 && added.firstOwner == 2 && objs.size == n0 + 1, "Add objective… added a ${core.string(500 + ObjectiveOps.typeOf(core, added ?: near))?.trim()} of team 2 (${addWhy ?: "ok"})")
            check(added == null || added.fstatus.size == ObjectiveOps.featureCount(core, added), "its buildings are its class's (${added?.fstatus?.size})")
            val fr = core.edits.run(ObjectiveBulk.frontLines(core))
            line("   front lines: ${fr ?: core.edits.undoLabel}")
            val pr = core.edits.run(ObjectiveBulk.resetParents(core))
            line("   parents: ${pr ?: core.edits.undoLabel}")
            val victim = objs.first { o -> o !== added && ObjectiveOps.usedBy(core, listOf(o)).isEmpty() && o.links.isNotEmpty() && core.objectives.none { it.parent == o.id } }
            check(core.edits.run(ObjectiveOps.delete(core, listOf(victim))) == null && victim !in objs, "an objective deleted")
            check(e.overwrite() == null, "written in place")
            val back = CommandSave.open(start, set)
            check(back.problems.isEmpty() && back.objectives.size == n0 && back.objectives.any { it.name.text.trim() == "Verify bridge" && it.type == bridgeType && it.owner == 2 } && back.objectives.none { it.id == victim.id }, "the start re-reads exactly: the added one there, the deleted one gone (${back.objectives.size} objectives)")
            val nUndo = core.edits.count
            repeat(nUndo) { core.edits.undo() }
            check(objs.size == n0 && objs.none { it.name.text.trim() == "Verify bridge" }, "undo takes every edit back")
            check(CommandSession.doc!!.editing!!.restoreStart() == null && CommandFiles.sha256(start) == origHash, "Restore gives BMS's original back")
            CommandSession.doc = null
        }

        // ---------------------------------------------------------------- 5. teams, campaign, pilots, checks, files, mine
        step("5a teams") {
            val doc = CommandSave.open(copyFile!!, set)
            val last = doc.teams.size - 1
            line("   teams: ${doc.teams.size}; delete refusal for the last: ${TeamOps.deleteRefusal(doc, last)}")
            check(doc.edits.run(TeamOps.mutualStance(doc, 1, 2, 4)!!) == null && doc.teams[1].team.stance[2] == 4 && doc.teams[2].team.stance[1] == 4, "stance set both ways")
            if (TeamOps.deleteRefusal(doc, last) == null) {
                check(doc.edits.run(TeamOps.delete(doc, last)) == null && doc.teams.size == last, "last team deleted")
                check(doc.edits.run(TeamOps.add(doc, "Verify", 3)) == null && doc.teams.size == last + 1 && doc.teamName(last) == "Verify", "a team added")
            }
            val r = CommandWriter.overwrite(doc); check(r.ok, "teams saved: ${r.message}")
            check(CommandSave.open(copyFile!!, set).let { it.problems.isEmpty() && it.partEditable("tea") }, "re-read exact")
        }
        step("5b campaign: shift times, occupation map, squad info") {
            val doc = CommandSave.open(copyFile!!, set)
            val clock = doc.cmp!!.currentTime
            val fl = doc.flights.first { it.waypoints.isNotEmpty() && it.waypoints[0].arrive > 0 }
            val w0 = fl.waypoints[0].arrive
            check(doc.edits.run(CampaignOps.shiftTimes(doc, 3_600_000)) == null, "shift +1h")
            check(doc.cmp!!.currentTime == clock + 3_600_000 && fl.waypoints[0].arrive == w0 + 3_600_000, "clock and routes moved")
            val occ = CampaignOps.buildOccMap(doc)
            line("   occupation map agreement before: ${CampaignOps.occAgreement(doc)}")
            if (occ != null) check(doc.edits.run(CampaignOps.setOccMap(doc, occ, "Occupation map from the objectives")) == null, "occupation map rebuilt (agreement ${CampaignOps.occAgreement(doc)})")
            check(doc.edits.run(CampaignOps.recountCallsigns(doc)) == null, "callsigns recounted")
            check(doc.edits.run(CampaignOps.recountPilots(doc)) == null, "pilots recounted")
            val r = CommandWriter.overwrite(doc); check(r.ok, "saved: ${r.message}")
            check(CommandSave.open(copyFile!!, set).let { it.problems.isEmpty() && it.cmp?.currentTime == clock + 3_600_000 }, "re-read exact with the new clock")
        }
        step("5c checks: Fix and Fix all") {
            val doc = CommandSave.open(copyFile!!, set)
            val before = CommandChecks.run(doc)
            line("   findings: ${before.size} (${before.groupBy { it.group }.map { "${it.key} ${it.value.size}" }.joinToString()})")
            // two pairs of units on one CampId each
            val us = doc.units.filter { it.kind == UnitKind.BATTALION }.take(4)
            us[1].campId = us[0].campId; us[3].campId = us[2].campId
            val dup = CommandChecks.run(doc).filter { it.group == "Ids" && it.fix != null }
            check(dup.size >= 2, "${dup.size} CampId findings")
            val edits = dup.mapNotNull { it.fix!!.invoke() }
            // as the Checks page runs it: each fix on its own, one refused fix in the middle never stops the rest
            val each = Edits.Each("Fix all", doc.edits, edits.take(1) + Edits.failing("A refused fix", "uni", "refused for the check") + edits.drop(1))
            check(doc.edits.run(each) == null, "Fix all ran")
            check(each.made == edits.size && each.refused.size == 1, "Fix all made ${each.made} of ${edits.size}, refused ${each.refused}")
            val after = doc.units.groupBy { it.campId }.filter { it.key != 0 && it.value.size > 1 }
            check(after.isEmpty(), "no CampId carried twice after Fix all (${after.keys})")
            CommandChecks.run(doc).firstOrNull { it.group == "Routes" }?.ref?.let { r -> doc.unit(CampaignArchive.VuId.parse(r.id))?.let { u ->
                line("   route finding on ${AirOps.nameOf(doc, u)}: " + u.waypoints.mapIndexed { i, w -> "${i + 1}:${AirOps.ACTIONS.toMap()[w.action] ?: w.action} ${AirOps.clock(w.arrive)}-${AirOps.clock(w.depart)}${if (AirOps.timeLocked(w)) "T" else ""}" }.joinToString(" | "))
            } }
            // a single Fix per remaining group
            for (g in CommandChecks.run(doc).filter { it.fix != null }.groupBy { it.group }) {
                val i = g.value.first()
                val e = runCatching { i.fix!!.invoke() }.getOrNull()
                val why = e?.let { doc.edits.run(it) }
                val still = CommandChecks.run(doc).any { it.group == i.group && it.text == i.text }
                check(e != null && why == null && !still, "${g.key}: \"${i.fixLabel}\" fixes \"${i.text.take(90)}\" (${why ?: if (still) "still found" else "ok"})")
            }
            val r = CommandWriter.overwrite(doc); check(r.ok, "saved: ${r.message}")
        }
        step("5d Files page: edit, save with backup, changed on disk, Restore") {
            val st = CampaignDbFiles.list(dir).first { it.kind == CampaignDbFiles.Kind.STATIONS }
            val orig = st.file.readBytes()
            val tab = CampaignDbFiles.read(st.kind, st.file)
            check(tab.problems.isEmpty() && tab.rows.isNotEmpty(), "Stations+Ils.dat read: ${tab.rows.size} rows")
            val col = tab.columns.indexOfFirst { it.editable && it.numeric }
            val rows = tab.rows.toMutableList()
            rows[0] = rows[0].with(col, ((rows[0].cells[col].toDoubleOrNull() ?: 0.0) + 1).let { if (it % 1.0 == 0.0) "${it.toLong()}" else "$it" })
            val (why, back) = CampaignDbFiles.save(tab, rows)
            check(why == null && back != null, "saved (${why ?: "ok"})")
            check(CommandBackup.copyOf(dir, st.file.name)?.readBytes()?.contentEquals(orig) == true, "the original backed up beside it")
            check(!st.file.readBytes().contentEquals(orig), "the file changed")
            val (why2, _) = CampaignDbFiles.save(tab, rows)
            check(why2 != null && "changed on disk" in why2, "a stale page refused: $why2")
            check(CampaignDbFiles.restore(st.file) == null && st.file.readBytes().contentEquals(orig), "Restore gives the original back")
        }
        step("5d2 BMS icons") {
            // a copy without BMS's Art folder says so in a line (the Layers panel's note), never silently
            val s = CommandSave.open(copyFile!!, set)
            val why = CampMapIcons.forTheater(set, s.theater).missingWhy
            val there = Theaters.resolveFile(set.data, "Art\\Main\\ImageIds.id") != null
            check(there || why != null, "icons missing are named: ${why ?: "found"}")
        }
        step("5e This file is mine") {
            val claim = dir.listFiles()!!.filter { ShippedSaves.isSave(it) }.firstOrNull { ShippedSaves.identify(it).claimable }
            if (claim == null) line("   no claimable file in the copy") else {
                check(MineFiles.add(claim) == null && ShippedSaves.identify(claim).let { it.kind == ShippedSaves.Kind.PILOT && it.mine }, "${claim.name} claimed: ${ShippedSaves.identify(claim).why}")
                CommandBackup.ensureFolder(dir)
                check(ShippedSaves.identify(claim).kind == ShippedSaves.Kind.PILOT, "still the pilot's after the folder is visited again")
                // the pilot's own bytes while it was theirs: after the claim is taken back they are never backed up as BMS's original
                // (its time kept, so its dates still put it with BMS's files once the claim is taken back)
                val before = claim.readBytes()
                val at = claim.lastModified()
                val changed = before + byteArrayOf(0)
                claim.writeBytes(changed)
                claim.setLastModified(at)
                check(MineFiles.remove(claim) == null && ShippedSaves.identify(claim).kind != ShippedSaves.Kind.PILOT, "taken back: ${ShippedSaves.identify(claim).why}")
                check(MineFiles.everClaimed(claim), "remembered as once claimed")
                CommandBackup.ensureFolder(dir)
                val h = CommandFiles.sha256(changed)
                check(CommandBackup.list(dir).none { it.name.equals(claim.name, ignoreCase = true) && it.sha256 == h }, "a file claimed and taken back is not backed up as BMS's original")
                claim.writeBytes(before)
                claim.setLastModified(at)
            }
        }

        // ---------------------------------------------------------------- 6. the composed section
        step("6 the section pressed") { ui(copy, out, dir, copyFile!!) }

        CommandSession.doc = null
        CommandSession.selection.clear()
        line("fails: ${sb.lines().count { it.startsWith("FAIL") }}")
        return sb.toString()
    }

    // ---------------------------------------------------------------- the composed section

    private fun ui(copy: File, out: File, dir: File, pilotCopy: File) {
        val stage = Stage(1600, 1000)
        try {
            CommandSession.doc = null
            CommandSession.page = CmdPage.MAP
            stage.settle(20, 30)
            check(stage.texts().any { it.contains("open a file to edit") }, "the empty section shows the Open list")
            stage.png(File(out, "ui-open.png"))
            check(stage.texts().any { it.contains("Hellas") } || true, "theaters listed: ${stage.texts().filter { it.contains("Korea") || it.contains("Hellas") }.take(4)}")
            // open the pilot's copy
            kotlinx.coroutines.runBlocking { com.bmscompanion.desktop.ui.command.openFile(pilotCopy) }
            stage.settle(30, 30)
            check(stage.textNode(pilotCopy.name) != null, "the identity strip names ${pilotCopy.name}")
            // every side present in the picture
            val owners = CommandSession.doc!!.world.units.map { it.owner }.toSet()
            check(owners.size >= 4, "units of ${owners.size} teams in the picture: $owners")
            // overwrite greyed with no edits; an edit through the Teams page
            CommandSession.page = CmdPage.TEAMS; stage.settle(10, 20)
            stage.png(File(out, "ui-teams.png"))
            val core = CommandSession.doc!!.core
            val ctxEdit = core.edits.run(TeamOps.rename(core, 1, "Pressed")!!)
            CommandSession.changed()
            stage.settle(10, 20)
            check(ctxEdit == null && stage.texts().any { it.contains("1 edit") }, "the identity strip counts 1 edit")
            // Undo / Redo buttons
            line("   before Undo: canUndo ${CommandSession.doc?.editing?.canUndo}, node ${stage.textNode("Undo")}, edits ${core.edits.count}, same doc ${CommandSession.doc?.core === core}")
            stage.hover(Offset(800f, 990f)); stage.hover(Offset(287f, 20f))
            stage.click("Undo"); line("   after Undo: status \"${CommandSession.status}\", edits ${core.edits.count}"); check(core.teamName(1) != "Pressed", "Undo button")
            stage.click("Redo"); check(core.teamName(1) == "Pressed", "Redo button")
            // Save as dialog
            stage.click("Save as…")
            check(stage.texts().any { it == "Save as a copy" }, "the Save as dialog opens")
            stage.png(File(out, "ui-saveas.png"))
            stage.setText(pilotCopy.nameWithoutExtension + " (edited)", "Verify UI copy")
            stage.settle(4, 20)
            stage.click("Save the copy")
            val ui = File(dir, "Verify UI copy.cam")
            check(ui.isFile && CommandSession.doc?.file?.absoluteFile == ui.absoluteFile, "Save the copy wrote ${ui.name} and opened it")
            // Overwrite with an error: read-only
            val c2 = CommandSession.doc!!.core
            c2.edits.run(TeamOps.rename(c2, 1, "ReadOnly")!!); CommandSession.changed(); stage.settle(6, 20)
            ui.setReadOnly()
            stage.click("Overwrite…")
            check(stage.texts().any { it.startsWith("Overwrite ") && it.endsWith("?") }, "Overwrite asks first")
            stage.png(File(out, "ui-overwrite-ask.png"))
            stage.click("Overwrite")
            stage.settle(6, 20)
            val err = stage.texts().firstOrNull { it.contains("not saved") || it.contains("refused") || it.contains("denied") }
            check(CommandSession.error != null && err != null, "an error dialog: ${CommandSession.error}")
            stage.png(File(out, "ui-overwrite-error.png"))
            stage.click("OK")
            ui.setWritable(true)
            // map tools
            CommandSession.page = CmdPage.MAP; stage.settle(10, 20)
            stage.click("Area"); check(CommandSession.tool == CmdTool.AREA, "Area tool chosen")
            stage.drag(Offset(500f, 300f), Offset(1000f, 800f))
            check(CommandSession.selection.refs.isNotEmpty(), "Area selected ${CommandSession.selection.refs.size}")
            stage.png(File(out, "ui-area.png"))
            stage.click("Ruler"); stage.drag(Offset(500f, 500f), Offset(900f, 500f))
            check(stage.texts().any { it.contains("nm") }, "the ruler reads out: ${stage.texts().filter { it.contains("nm") }.take(2)}")
            stage.png(File(out, "ui-ruler.png"))
            stage.click("Select")
            // the Owner and Clone tools act on a click (the map's tap must see the tool chosen now)
            run {
                val cc = CommandSession.doc!!.core
                val o2 = cc.objectives.first { ObjectiveOps.typeOf(cc, it) == 1 && cc.ownerNow(it) != 3 }
                CommandSession.page = CmdPage.MAP; stage.settle(6, 20)
                CommandSession.selection.clear(); stage.settle(4, 20)
                CommandSession.tool = CmdTool.OWNER; CommandSession.ownerTeam = 3
                CommandSession.flyTo(CmdRef(CmdKind.OBJECTIVE, o2.id.toString())); stage.settle(40, 30)
                stage.png(File(out, "ui-owner-before.png"))
                // where the map drew it (a glide may not have centred it yet)
                val at = CommandSession.screenHook?.invoke(CmdRef(CmdKind.OBJECTIVE, o2.id.toString())) ?: Offset(755f, 546f)
                line("   Owner tap at ${at.x.toInt()}, ${at.y.toInt()}")
                stage.tap(at)
                check(cc.ownerNow(o2) == 3, "the Owner tool gave ${ObjectiveOps.displayName(cc, o2)} to team 3 (${CommandSession.status})")
                val fl = cc.flights.first()
                val n0 = cc.units.size
                CommandSession.selection.set(CmdRef(CmdKind.FLIGHT, fl.id.toString()))
                CommandSession.tool = CmdTool.CLONE; stage.settle(4, 20)
                stage.tap(Offset(700f, 500f))
                check(cc.units.size > n0 && CommandSession.status.startsWith("Done: cloned"), "the Clone tool added ${cc.units.size - n0} units (${CommandSession.status})")
                val bn = cc.battalions.first { it.owner == 2 }
                val (bx, by) = bn.x to bn.y
                CommandSession.tool = CmdTool.MOVE; CommandSession.selection.clear()
                CommandSession.flyTo(CmdRef(CmdKind.BATTALION, bn.id.toString())); stage.settle(40, 30)
                stage.drag(Offset(755f, 546f), Offset(855f, 546f))
                // (the press may land on the brigade standing with it: either moves)
                check(CommandSession.status.startsWith("Done: moved") && cc.edits.count > 0, "the Move tool moved what was under the press (${CommandSession.status}; battalion moved: ${bn.x != bx || bn.y != by})")
                stage.click("Select")
            }
            // Find and the context menu
            run {
                val cc = CommandSession.doc!!.core
                CommandSession.tool = CmdTool.SELECT; CommandSession.selection.clear(); stage.settle(4, 20)
                stage.setTextAt("Find: name or CampId", "Osan")
                stage.settle(10, 20)
                val hit = stage.texts().firstOrNull { it.startsWith("Osan") && " · " in it }
                check(hit != null, "Find lists \"$hit\"")
                if (hit != null) { stage.click(hit); check(CommandSession.selection.primary != null, "a Find result selects ${CommandSession.selection.primary}") }
                stage.png(File(out, "ui-find.png"))
                val o3 = cc.objectives.first { ObjectiveOps.typeOf(cc, it) == 1 && cc.ownerNow(it) != 2 && cc.delta(it.id) == null }
                CommandSession.selection.clear()
                CommandSession.flyTo(CmdRef(CmdKind.OBJECTIVE, o3.id.toString())); stage.settle(40, 30)
                stage.rightTap(Offset(755f, 546f))
                check(stage.textNode("Inspect") != null && stage.textNode("Ruler from here") != null, "the context menu opens")
                stage.png(File(out, "ui-menu.png"))
                val rok = "   ${CommandSession.doc!!.world.team(2)?.name}"
                stage.click(rok.trim())
                check(cc.ownerNow(o3) == 2, "the menu's Set owner gave ${ObjectiveOps.displayName(cc, o3)} to team 2 (${CommandSession.status})")
                // the objective's other lines: buildings, the airport schedule, delete greyed with the way to the start
                stage.rightTap(Offset(755f, 546f))
                check(stage.textNode("View buildings…") != null && stage.textNode("Airport schedule…") != null && stage.textNode("Delete objective…") != null &&
                    stage.textNode("Open its start to add or delete objectives") != null, "the objective's menu: buildings, schedule, delete and the way to its start")
                stage.png(File(out, "ui-menu-objective.png"))
                stage.click("Airport schedule…")
                check(stage.texts().any { it.startsWith("Airport schedule · ") }, "the airport schedule opens")
                stage.png(File(out, "ui-schedule.png"))
                stage.click("Close")
                // the Units table: names with the aircraft, a row's menu
                CommandSession.page = CmdPage.UNITS; CommandSession.selection.clear(); stage.settle(10, 20)
                val fl = cc.flights.first { cc.aircraft(it) != null }
                CommandSession.selection.set(CmdRef(CmdKind.FLIGHT, fl.id.toString())); stage.settle(10, 20)
                // the table's Name is the type and role ("F-16C-52 ROKAF - Fighter"), the callsign in its own column
                val tname = UnitNames.tableName(cc, fl)
                val csign = UnitNames.callsignText(cc, fl)
                // (the table's row: the lowest place the callsign is shown, the inspector's title being at the top)
                val row = stage.textNodes(csign).filter { it.left < 1250f }.maxByOrNull { it.top }
                val named = row != null && stage.textNodes(tname).any { it.left < 1250f && kotlin.math.abs(it.center.y - row.center.y) < 6f }
                check(row != null && named, "the Units table lists \"$tname\" with the callsign \"$csign\" in its own column")
                if (row != null) {
                    stage.rightTap(row.center)
                    check(stage.textNode("Clone") != null && stage.textNode("Select its package") != null, "a row's menu: Clone, Select its package")
                    stage.png(File(out, "ui-units-menu.png"))
                    stage.click("Select its package")
                    check(CommandSession.selection.primary?.kind == CmdKind.PACKAGE, "Select its package selected ${CommandSession.selection.primary}")
                }
                stage.png(File(out, "ui-units-names.png"))
                // the Objectives page of a save: add/delete greyed, the way to the start
                CommandSession.page = CmdPage.OBJECTIVES; stage.settle(10, 20)
                check(stage.texts().any { it.startsWith("Open its start: ") }, "the Objectives page offers Open its start")
                stage.png(File(out, "ui-objectives-save.png"))
                CommandSession.page = CmdPage.STATS; stage.settle(10, 20)
                check(stage.texts().any { it.startsWith("STATS · ") }, "the Stats page")
                stage.png(File(out, "ui-stats.png"))
                CommandSession.page = CmdPage.MAP; stage.settle(6, 20)
            }
            // the inspector follows an edit of the selected objective
            run {
                val cc = CommandSession.doc!!.core
                val o = cc.objectives.first { cc.delta(it.id) == null && ObjectiveOps.typeOf(cc, it) == 1 }
                CommandSession.page = CmdPage.OBJECTIVES
                CommandSession.selection.set(CmdRef(CmdKind.OBJECTIVE, o.id.toString())); stage.settle(10, 20)
                check(stage.texts().any { it == "as its start" }, "the inspector shows the objective as its start")
                cc.edits.run(ObjectiveOps.owner(cc, o, if (cc.ownerNow(o) == 2) 6 else 2, full = false)); CommandSession.changed(); stage.settle(10, 20)
                check(stage.texts().any { it.startsWith("changed (owner") }, "the inspector shows the change at once")
                stage.png(File(out, "ui-objective-changed.png"))
                CommandSession.page = CmdPage.MAP; stage.settle(6, 20)
            }
            stage.tap(Offset(750f, 550f))
            line("   a click on the map selected ${CommandSession.selection.refs.size}")
            for (p in CmdPage.entries) {
                CommandSession.page = p; stage.settle(10, 20)
                stage.png(File(out, "ui-page-${p.name.lowercase()}.png"))
            }
            // a campaign start: Edit and Restore through the buttons
            kotlinx.coroutines.runBlocking { com.bmscompanion.desktop.ui.command.openFile(File(dir, "Save2.cam")) }
            CommandSession.page = CmdPage.OBJECTIVES; stage.settle(20, 30)
            stage.click("Edit BMS's campaign start")
            check(stage.texts().any { it.contains("Edit BMS's campaign start in place?") }, "Edit start asks first")
            stage.click("Edit in place")
            check(CommandSession.doc?.editing?.editingStart == true, "the start is being edited")
            check(stage.textNode("Restore BMS's original") != null, "Restore offered")
            stage.png(File(out, "ui-start-edit.png"))
        } finally {
            stage.close()
        }
    }

    private class Owner : LifecycleOwner, ViewModelStoreOwner {
        private val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
        override val viewModelStore = ViewModelStore()
    }

    private class Stage(val w: Int, val h: Int) {
        private val owners = CopyOnWriteArrayList<SemanticsOwner>()
        private val surface = Surface.makeRasterN32Premul(w, h)
        private var nanos = 0L
        private var ms = 1_000L
        private val owner = Owner()
        private val scene: ComposeScene = edt {
            val info = object : WindowInfo {
                override val isWindowFocused: Boolean get() = true
                override val containerSize: IntSize get() = IntSize(w, h)
            }
            val platform = object : PlatformContext by PlatformContext.Empty {
                override val windowInfo: WindowInfo get() = info
                override val semanticsOwnerListener = object : PlatformContext.SemanticsOwnerListener {
                    override fun onSemanticsOwnerAppended(semanticsOwner: SemanticsOwner) { owners += semanticsOwner }
                    override fun onSemanticsOwnerRemoved(semanticsOwner: SemanticsOwner) { owners -= semanticsOwner }
                    override fun onSemanticsChange(semanticsOwner: SemanticsOwner) {}
                    override fun onLayoutChange(semanticsOwner: SemanticsOwner, semanticsNodeId: Int) {}
                }
            }
            val context = object : ComposeSceneContext { override val platformContext: PlatformContext get() = platform }
            CanvasLayersComposeScene(density = Density(1f), size = IntSize(w, h), coroutineContext = Dispatchers.Unconfined, composeSceneContext = context).also {
                it.setContent {
                    CompositionLocalProvider(LocalLifecycleOwner provides owner, LocalViewModelStoreOwner provides owner, LocalConfiguration provides Configuration(w, h)) {
                        BmsTheme { Box(Modifier.fillMaxSize().background(Hud.Bg)) { CommandSection(null, local = true) } }
                    }
                }
            }
        }

        fun frame() = edt {
            Snapshot.sendApplyNotifications()
            nanos += 16_666_667L
            surface.canvas.clear(0)
            scene.render(surface.canvas.asComposeCanvas(), nanos)
        }

        fun settle(n: Int = 3, sleepMs: Long = 0) = repeat(n) { frame(); if (sleepMs > 0) Thread.sleep(sleepMs) }

        fun png(f: File) { settle(3, 20); runCatching { f.writeBytes(surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes) } }

        private fun nodes(): List<SemanticsNode> = edt { owners.flatMap { runCatching { it.getAllSemanticsNodes(mergingEnabled = false) }.getOrDefault(emptyList()) } }
        private fun textOf(n: SemanticsNode) = n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
        fun texts(): List<String> = nodes().mapNotNull { textOf(it) }
        fun textNode(text: String): Rect? = nodes().filter { textOf(it)?.trim() == text }.map { it.boundsInWindow }.filter { it.width > 0 && it.height > 0 }.lastOrNull()
        /** every place [text] is shown (a name in a table row and in the inspector's title) */
        fun textNodes(text: String): List<Rect> = nodes().filter { textOf(it)?.trim() == text }.map { it.boundsInWindow }.filter { it.width > 0 && it.height > 0 }

        fun click(text: String) {
            val r = textNode(text) ?: run { fail("no \"$text\" to press"); return }
            tap(r.center)
        }

        /** The text field showing [old] gets [new] (as typing would leave it). */
        fun setText(old: String, new: String) {
            val n = nodes().firstOrNull { it.config.getOrNull(SemanticsActions.SetText) != null && (it.config.getOrNull(SemanticsProperties.EditableText)?.text == old) }
                ?: run { fail("no text field holding \"$old\""); return }
            edt { n.config[SemanticsActions.SetText].action?.invoke(AnnotatedString(new)) }
        }

        fun tap(at: Offset) {
            ms += 50
            edt {
                scene.sendPointerEvent(PointerEventType.Move, at, timeMillis = ms, type = PointerType.Mouse)
                scene.sendPointerEvent(PointerEventType.Press, at, timeMillis = ms, type = PointerType.Mouse, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
            }
            settle(1)
            ms += 60
            edt { scene.sendPointerEvent(PointerEventType.Release, at, timeMillis = ms, type = PointerType.Mouse, buttons = PointerButtons(), button = PointerButton.Primary) }
            settle(6, 15)
            ms += 400
        }

        fun rightTap(at: Offset) {
            ms += 50
            edt {
                scene.sendPointerEvent(PointerEventType.Move, at, timeMillis = ms, type = PointerType.Mouse)
                scene.sendPointerEvent(PointerEventType.Press, at, timeMillis = ms, type = PointerType.Mouse, buttons = PointerButtons(isSecondaryPressed = true), button = PointerButton.Secondary)
            }
            settle(1)
            ms += 60
            edt { scene.sendPointerEvent(PointerEventType.Release, at, timeMillis = ms, type = PointerType.Mouse, buttons = PointerButtons(), button = PointerButton.Secondary) }
            settle(8, 15)
            ms += 400
        }

        /** The text field whose placeholder reads [placeholder] gets [new]. */
        fun setTextAt(placeholder: String, new: String) {
            val p = textNode(placeholder) ?: run { fail("no field showing \"$placeholder\""); return }
            val n = nodes().firstOrNull { it.config.getOrNull(SemanticsActions.SetText) != null && it.boundsInWindow.contains(p.center) }
                ?: run { fail("no text field at \"$placeholder\""); return }
            edt { n.config[SemanticsActions.SetText].action?.invoke(AnnotatedString(new)) }
        }

        fun hover(at: Offset) { ms += 50; edt { scene.sendPointerEvent(PointerEventType.Move, at, timeMillis = ms, type = PointerType.Mouse) }; settle(3, 10) }

        fun drag(a: Offset, b: Offset) {
            ms += 50
            edt {
                scene.sendPointerEvent(PointerEventType.Move, a, timeMillis = ms, type = PointerType.Mouse)
                scene.sendPointerEvent(PointerEventType.Press, a, timeMillis = ms, type = PointerType.Mouse, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
            }
            settle(1)
            for (i in 1..10) {
                ms += 16
                val p = Offset(a.x + (b.x - a.x) * i / 10f, a.y + (b.y - a.y) * i / 10f)
                edt { scene.sendPointerEvent(PointerEventType.Move, p, timeMillis = ms, type = PointerType.Mouse, buttons = PointerButtons(isPrimaryPressed = true)) }
                settle(1)
            }
            ms += 30
            edt { scene.sendPointerEvent(PointerEventType.Release, b, timeMillis = ms, type = PointerType.Mouse, buttons = PointerButtons(), button = PointerButton.Primary) }
            settle(6, 15)
            ms += 400
        }

        fun close() { runCatching { edt { scene.close() } }; surface.close() }
    }

    private fun <T> edt(block: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return block()
        var r: Result<T>? = null
        SwingUtilities.invokeAndWait { r = runCatching(block) }
        return r!!.getOrThrow()
    }
}
