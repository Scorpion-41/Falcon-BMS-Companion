package com.bmscompanion.desktop.ui.command.inspectors

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bmscompanion.app.ui.screens.editor.Rule
import com.bmscompanion.app.ui.theme.Hud
import com.bmscompanion.desktop.bridge.CampaignArchive
import com.bmscompanion.desktop.bridge.command.CmdObjective
import com.bmscompanion.desktop.bridge.command.CommandEdit
import com.bmscompanion.desktop.bridge.command.CommandSave
import com.bmscompanion.desktop.bridge.command.Edits
import com.bmscompanion.desktop.bridge.command.ObjLink
import com.bmscompanion.desktop.bridge.command.ObjectiveOps
import com.bmscompanion.desktop.bridge.command.ObjectiveOps.FeatureStates
import com.bmscompanion.desktop.ui.command.CmdAction
import com.bmscompanion.desktop.ui.command.CmdContext
import com.bmscompanion.desktop.ui.command.CmdInspector
import com.bmscompanion.desktop.ui.command.CmdKind
import com.bmscompanion.desktop.ui.command.CmdRef
import com.bmscompanion.desktop.ui.command.CommandSession
import com.bmscompanion.desktop.ui.command.DeskButton
import com.bmscompanion.desktop.ui.command.FactRow
import com.bmscompanion.desktop.ui.command.MiniChip
import com.bmscompanion.desktop.ui.command.teamInk
import kotlin.math.roundToInt

// The Objectives page's inspector: one objective edited in place, or what several selected have in common.
// In a played save only what BMS records (owner, supply, fuel, losses, buildings: the objective deltas); in a campaign
// start under "Edit BMS's campaign start" (or the pilot's own copy reading its own objectives) the whole record:
// name, class, owners, priority, place, flags, parent, links, radar sectors and buildings. ObjectiveOps does the edits.

internal fun CmdContext.objective(r: CmdRef?): CmdObjective? =
    r?.takeIf { it.kind == CmdKind.OBJECTIVE }?.let { core.objective(CampaignArchive.VuId.parse(it.id)) }

internal fun CmdContext.editingStart(): Boolean = doc.editing?.editingStart == true
internal fun CmdContext.objMode(): ObjectiveOps.Mode = ObjectiveOps.mode(core, editingStart())

internal fun CmdContext.teamChoices(): List<Pair<Int, String>> =
    world.teams.map { it.n to "${it.n} ${it.name}" + if (it.inUse) "" else " (none)" }

internal fun CmdContext.teamInkOf(n: Int): Color? = world.team(n)?.let { teamInk(it.colour) }

/** The objective classes of the theater's class table (domain 3, class 4): type number → "Airbase · Osan AB". */
internal fun objectiveClasses(core: CommandSave): List<Pair<Int, String>> {
    val names = core.names ?: return emptyList()
    val ocdName = core.tables.ocd
    val out = ArrayList<Pair<Int, String>>()
    for (i in 0 until names.ctCount + 64) {
        val ct = names.ct(i + 100) ?: continue
        if (ct.domain != 3 || ct.cls != 4) continue
        val typeName = names.strings[500 + ct.type]?.trim().orEmpty()
        val n = ocdName[ct.entityIdx]?.name?.takeIf { it.isNotBlank() }
        out += (i + 100) to listOfNotNull(typeName.ifEmpty { "Type ${ct.type}" }, n).joinToString(" · ")
    }
    return out.sortedBy { it.second.lowercase() }
}

/** The buildings of [o]'s class (FED): index, name, value (importance). */
internal fun buildingsOf(core: CommandSave, o: CmdObjective): List<Triple<Int, String, Int>> {
    val ocd = ObjectiveOps.ocdOf(core, o)
    val fcd = core.tables.table("FCD")
    return core.tables.features(ocd).mapIndexed { i, f ->
        val ct = core.names?.ct(f.ctIdx + 100)
        val name = ct?.let { fcd[it.entityIdx]?.name }?.takeIf { it.isNotBlank() } ?: "Building ${i + 1}"
        Triple(i, name, f.value)
    }
}

object ObjectiveInspector : CmdInspector {
    override fun accepts(ref: CmdRef): Boolean = ref.kind == CmdKind.OBJECTIVE

    @Composable
    override fun Content(ctx: CmdContext, refs: List<CmdRef>) {
        @Suppress("UNUSED_VARIABLE") val v = CommandSession.version
        val objs = refs.mapNotNull { ctx.objective(it) }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            EdDialogHost()
            if (objs.isEmpty()) { EdNote("That objective is not in the file."); return@Column }
            if (objs.size > 1) Several(ctx, objs) else One(ctx, objs[0])
        }
    }
}

@Composable
private fun ModeLine(ctx: CmdContext) {
    val mode = ctx.objMode()
    EdNote(ObjectiveOps.modeWhy(ctx.core, ctx.editingStart()), if (mode == ObjectiveOps.Mode.NONE) Hud.Amber else Hud.TextFaint)
}

@Composable
private fun One(ctx: CmdContext, o: CmdObjective) {
    val core = ctx.core
    val mode = ctx.objMode()
    val full = mode == ObjectiveOps.Mode.FULL
    val can = mode != ObjectiveOps.Mode.NONE
    val name = ObjectiveOps.displayName(core, o)
    val delta = core.delta(o.id)
    androidx.compose.foundation.layout.Row(Modifier.padding(start = 8.dp, top = 6.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        ctx.teamFlag(core.ownerNow(o))?.invoke()
        Text(name, color = Hud.Text, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
    }
    val typeName = core.string(500 + ObjectiveOps.typeOf(core, o))?.trim().orEmpty()
    Text("${typeName.uppercase()} · CAMPID ${o.campId} · ID ${o.id}", color = Hud.TextFaint, fontSize = 10.sp, letterSpacing = 0.5.sp, modifier = Modifier.padding(horizontal = 8.dp))
    ModeLine(ctx)
    Rule()

    EdHeading("Who holds it")
    EdChoice("Owner", core.ownerNow(o), ctx.teamChoices(), can, ink = { ctx.teamInkOf(it) }, lead = { ctx.teamFlag(it) }) { ctx.edit(ObjectiveOps.owner(core, o, it, full)) }
    if (full) EdChoice("First owner", o.firstOwner, ctx.teamChoices(), true, tip = "Who held it when the campaign began", ink = { ctx.teamInkOf(it) }, lead = { ctx.teamFlag(it) }) { t ->
        ctx.edit(ObjectiveOps.field("First owner of $name", { o.firstOwner }, { o.firstOwner = it }, t))
    } else FactRow("First owner", ctx.world.team(o.firstOwner)?.name ?: "${o.firstOwner}", lead = ctx.teamFlag(o.firstOwner))
    if (mode == ObjectiveOps.Mode.DELTAS) FactRow("In this save", if (delta == null) "as its start" else "changed (owner, supply or buildings)", ink = if (delta == null) Hud.TextDim else Hud.Amber)

    EdHeading("Supply")
    val sup = delta?.supply ?: o.supply
    val fuel = delta?.fuel ?: o.fuel
    val loss = delta?.losses ?: o.losses
    EdInt("Supply", if (full) o.supply else sup, 0..255, can) { ctx.edit(ObjectiveOps.supply(core, o, it, full)) }
    EdInt("Fuel", if (full) o.fuel else fuel, 0..255, can) { ctx.edit(ObjectiveOps.fuel(core, o, it, full)) }
    EdInt("Losses", if (full) o.losses else loss, 0..255, can) { ctx.edit(ObjectiveOps.losses(core, o, it, full)) }

    if (full) {
        EdHeading("Record")
        EdText("Name", o.name.text) { ctx.edit(ObjectiveOps.rename(core, o, it)) }
        val classes = remember(core) { objectiveClasses(core) }
        EdChoice("Class", o.type, classes, tip = "The objective's class: what it is and which buildings it has") { t ->
            ctx.edit(ObjectiveOps.changeClass(core, o, t))
        }
        EdInt("Priority", o.priority, 0..255, tip = "How much the campaign values it (cities of 80 and more make the primary objective list)") { p ->
            ctx.edit(ObjectiveOps.field("Priority of $name", { o.priority }, { o.priority = it }, p))
        }
        EdText("Parent (CampId)", core.objective(o.parent)?.campId?.toString().orEmpty(), tip = "The objective this one belongs to (a town's city), by its CampId; empty for none") { s ->
            val p = if (s.isBlank()) CampaignArchive.VuId.NONE else core.objectives.firstOrNull { it.campId.toString() == s.trim() }?.id
            if (p == null) CommandSession.status = "No objective has CampId $s." else ctx.edit(ObjectiveOps.field("Parent of $name", { o.parent }, { o.parent = it }, p))
        }
        val n = if (o.simNorth.isFinite() && o.simNorth != 0.0) o.simNorth else (o.y + 0.5) * CampaignArchive.FEET_PER_CELL
        val e = if (o.simEast.isFinite() && o.simEast != 0.0) o.simEast else (o.x + 0.5) * CampaignArchive.FEET_PER_CELL
        EdNum("North", n, 0, unit = "ft") { ctx.edit(ObjectiveOps.place(core, o, it, e)) }
        EdNum("East", e, 0, unit = "ft") { ctx.edit(ObjectiveOps.place(core, o, n, it)) }
        FactRow("Grid cell", "${o.x}, ${o.y}", mono = true)
        EdNum("Elevation", 0.0 - o.simZ + 0.0, 0, unit = "ft", tip = "Kept negated in the record, as BMS keeps heights") { z -> ctx.edit(ObjectiveOps.field("Elevation of $name", { o.simZ }, { o.simZ = it }, -z)) }
        EdNum("Heading", Math.toDegrees(o.heading.toDouble()).let { if (it < 0) it + 360 else it }, 1, unit = "°") { h ->
            ctx.edit(ObjectiveOps.field("Heading of $name", { o.heading }, { o.heading = it }, Math.toRadians(((h % 360) + 360) % 360).toFloat()))
        }

        EdHeading("Flags")
        ObjectiveOps.FLAGS.forEach { (bit, words) -> EdCheck(words, o.objFlags and bit != 0) { ctx.edit(ObjectiveOps.setFlag(core, o, bit, it)) } }
        FactRow("Raw", "0x" + (o.objFlags.toLong() and 0xFFFFFFFFL).toString(16), mono = true)

        Links(ctx, o)
        Radar(ctx, o)
    } else {
        FactRow("Priority", "${o.priority}")
        FactRow("Position", "N ${((o.y + 0.5) * CampaignArchive.FEET_PER_CELL).roundToInt()}  E ${((o.x + 0.5) * CampaignArchive.FEET_PER_CELL).roundToInt()} ft", mono = true)
        FactRow("Links", "${o.links.size}")
    }
    Buildings(ctx, o, full, can)
    if (mode == ObjectiveOps.Mode.DELTAS && delta != null) EdButtons {
        DeskButton("Undo this save's changes", danger = true, tip = "Remove the objective's delta: it is as its start again") {
            ctx.edit(ObjectiveOps.removeDeltas(core, listOf(delta), "Remove the changes of $name"))
        }
    }
    if (full) EdButtons {
        DeskButton("Add a copy beside it", tip = "A new objective of the same class 1 km east, with a new id and CampId") { askAdd(ctx, o, o.simNorth.takeIf { it != 0.0 } ?: ((o.y + 0.5) * CampaignArchive.FEET_PER_CELL), (o.simEast.takeIf { it != 0.0 } ?: ((o.x + 0.5) * CampaignArchive.FEET_PER_CELL)) + 3280.84) }
        DeskButton("Add objective…", tip = "A new objective of any class: its class, owner, name and place") { MoreDialogs.req = MoreDialogs.AddObjective(o.simNorth.takeIf { it != 0.0 } ?: ((o.y + 0.5) * CampaignArchive.FEET_PER_CELL), (o.simEast.takeIf { it != 0.0 } ?: ((o.x + 0.5) * CampaignArchive.FEET_PER_CELL)) + 3280.84, o) }
        DeskButton("Delete", danger = true) { askDelete(ctx, listOf(o)) }
    } else AddDeleteBlock(ctx)
}

@Composable
private fun Several(ctx: CmdContext, objs: List<CmdObjective>) {
    val core = ctx.core
    val mode = ctx.objMode()
    val full = mode == ObjectiveOps.Mode.FULL
    val can = mode != ObjectiveOps.Mode.NONE
    Text("${objs.size} objectives", color = Hud.Text, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(8.dp))
    ModeLine(ctx)
    Rule()
    val owners = objs.map { core.ownerNow(it) }.distinct()
    EdHeading("Owner of all")
    Column(Modifier.padding(horizontal = 6.dp)) {
        com.bmscompanion.desktop.ui.command.MiniWrap {
            ctx.world.teams.filter { it.inUse }.forEach { t ->
                MiniChip(t.name, owners.size == 1 && owners[0] == t.n, teamInk(t.colour), can, lead = { com.bmscompanion.desktop.ui.command.TeamFlagLead(t.name, t.colour) }) {
                    ctx.edit(Edits.group("Owner of ${objs.size} objectives: ${t.name}", objs.map { ObjectiveOps.owner(core, it, t.n, full) }))
                }
            }
        }
    }
    EdHeading("Supply of all")
    fun same(f: (CmdObjective) -> Int) = objs.map(f).distinct().singleOrNull()
    val sup = same { core.delta(it.id)?.supply ?: it.supply }
    val fu = same { core.delta(it.id)?.fuel ?: it.fuel }
    EdRow("Supply") { EdField(sup?.toString() ?: "mixed", can, mono = true, width = 90.dp, align = TextAlign.End) { s -> s.trim().toIntOrNull()?.let { v -> ctx.edit(Edits.group("Supply of ${objs.size} objectives", objs.map { ObjectiveOps.supply(core, it, v, full) })) } } }
    EdRow("Fuel") { EdField(fu?.toString() ?: "mixed", can, mono = true, width = 90.dp, align = TextAlign.End) { s -> s.trim().toIntOrNull()?.let { v -> ctx.edit(Edits.group("Fuel of ${objs.size} objectives", objs.map { ObjectiveOps.fuel(core, it, v, full) })) } } }
    EdButtons {
        DeskButton("Repair all", enabled = can, tip = "Every building of the selected objectives intact") { ctx.edit(ObjectiveOps.repair(core, objs, full)) }
    }
    if (full) {
        val pr = same { it.priority }
        EdRow("Priority") { EdField(pr?.toString() ?: "mixed", true, mono = true, width = 90.dp, align = TextAlign.End) { s -> s.trim().toIntOrNull()?.let { v -> ctx.edit(Edits.group("Priority of ${objs.size} objectives", objs.map { o -> ObjectiveOps.field("Priority", { o.priority }, { o.priority = it }, v.coerceIn(0, 255)) })) } } }
        EdHeading("Links and radar")
        EdButtons {
            DeskButton("Auto-link", tip = "Link each to its nearest neighbours as the theater's network is laid") { ctx.edit(ObjectiveOps.autoLink(core, objs)) }
            DeskButton("Recalculate costs", tip = "Every link's costs from its length, by the theater's own links") { ctx.edit(ObjectiveOps.recost(core, objs)) }
            DeskButton("Remove links", danger = true) { ctx.edit(ObjectiveOps.removeLinks(core, objs)) }
        }
        EdButtons {
            DeskButton("Radar from terrain", tip = "The eight sectors of every selected radar from the ground round it") { radarAll(ctx, objs.filter { it.hasRadar != 0 || it.objFlags and 0x10000 != 0 }) }
            DeskButton("Radar default", tip = "Every sector open (${ObjectiveOps.RADAR_DEFAULT})") {
                ctx.edit(Edits.group("Radar default for ${objs.size}", objs.filter { it.hasRadar != 0 }.map { ObjectiveOps.setRadar(core, it, FloatArray(8) { ObjectiveOps.RADAR_DEFAULT }, "Radar default") }))
            }
        }
        EdButtons { DeskButton("Delete ${objs.size} objectives", danger = true) { askDelete(ctx, objs) } }
    } else AddDeleteBlock(ctx)
}

@Composable
internal fun Buildings(ctx: CmdContext, o: CmdObjective, full: Boolean, can: Boolean) {
    val core = ctx.core
    val list = remember(core, o.type) { buildingsOf(core, o) }
    val states = ObjectiveOps.statesNow(core, o)
    val count = if (list.isNotEmpty()) list.size else states.size
    val damaged = FeatureStates.damaged(states, count)
    EdHeading("Buildings ($count${if (damaged > 0) ", $damaged damaged" else ""})") {
        if (count > 0) Text("Repair all", color = if (can) Hud.Amber else Hud.TextFaint, fontSize = 11.sp, modifier = Modifier.clickable(enabled = can) { ctx.edit(ObjectiveOps.repair(core, listOf(o), full)) })
    }
    if (count == 0) { EdNote("Its class has no buildings."); return }
    val rows = if (list.isNotEmpty()) list else (0 until count).map { Triple(it, "Building ${it + 1}", 0) }
    var showAll by remember(o) { mutableStateOf(false) }
    val shown = if (showAll) rows else rows.filter { it.third > 0 || FeatureStates.get(states, it.first, count) != 0 }.ifEmpty { rows.take(12) }
    for ((i, nm, value) in shown) {
        val s = FeatureStates.get(states, i, count)
        Row(Modifier.fillMaxWidth().height(24.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${i + 1}", color = Hud.TextFaint, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(22.dp))
            Text(nm + if (value > 0) "  ·$value" else "", color = Hud.Text, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            listOf(0 to "OK", 2 to "Dmg", 3 to "Dest").forEach { (st, w) ->
                Text(
                    w, Modifier.padding(start = 3.dp).clickable(enabled = can && st != s) { ctx.edit(ObjectiveOps.building(core, o, i, st, full)) }.padding(horizontal = 4.dp, vertical = 2.dp),
                    color = when { st == s && st == 3 -> Hud.Red; st == s && st == 2 -> Hud.Amber; st == s -> Hud.Green; else -> Hud.TextFaint },
                    fontSize = 10.5.sp, fontWeight = if (st == s) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
        Rule(0.1f)
    }
    if (shown.size < rows.size || showAll) Text(if (showAll) "Fewer" else "All ${rows.size} (also those of no value)", color = Hud.Amber, fontSize = 11.sp, modifier = Modifier.padding(6.dp).clickable { showAll = !showAll })
}

private val MOVES = listOf("None", "Foot", "Wheeled", "Tracked", "Low air", "Air", "Naval", "Rail")

@Composable
private fun Links(ctx: CmdContext, o: CmdObjective) {
    val core = ctx.core
    val name = ObjectiveOps.displayName(core, o)
    EdHeading("Links (${o.links.size} of ${ObjectiveOps.MAX_LINKS})") {
        Text("Auto", color = Hud.Amber, fontSize = 11.sp, modifier = Modifier.clickable { ctx.edit(ObjectiveOps.autoLink(core, listOf(o))) }.padding(horizontal = 4.dp))
        Text("Costs", color = Hud.Amber, fontSize = 11.sp, modifier = Modifier.clickable { ctx.edit(ObjectiveOps.recost(core, listOf(o))) }.padding(horizontal = 4.dp))
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp)) {
        Text("TO", color = Hud.TextFaint, fontSize = 9.5.sp, modifier = Modifier.weight(1f))
        Text("KM", color = Hud.TextFaint, fontSize = 9.5.sp, modifier = Modifier.width(34.dp), textAlign = TextAlign.End)
        MOVES.forEach { Text(it.take(2).uppercase(), color = Hud.TextFaint, fontSize = 9.5.sp, modifier = Modifier.width(22.dp), textAlign = TextAlign.End) }
        Text("", modifier = Modifier.width(18.dp))
    }
    for (l in o.links.toList()) {
        val b = core.objective(l.id)
        Row(Modifier.fillMaxWidth().height(23.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(b?.let { ObjectiveOps.displayName(core, it) } ?: "${l.id} (missing)", color = if (b == null) Hud.Red else Hud.Text, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).clickable(enabled = b != null) { b?.let { ctx.focus(CmdRef(CmdKind.OBJECTIVE, it.id.toString())) } })
            Text(b?.let { "%.0f".format(ObjectiveOps.cellKm(o, it)) } ?: "", color = Hud.TextDim, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(34.dp), textAlign = TextAlign.End)
            for (i in 0 until 8) {
                val c = l.costs.getOrElse(i) { 255 }
                CostCell(c) { nv -> ctx.edit(linkCost(core, o, l, i, nv)) }
            }
            Text("✕", color = Hud.Red, fontSize = 11.sp, modifier = Modifier.width(18.dp).clickable {
                ctx.edit(if (b != null) ObjectiveOps.unlink(core, o, b) else Edits.remove("Remove $name's link to ${l.id}", "obj", o.links, l))
            }, textAlign = TextAlign.End)
        }
        Rule(0.1f)
    }
    EdRow("Link to CampId", tip = "Type the CampId of the objective to link (both ways); the map's Link + tool clicks two objectives") {
        EdField("", mono = true, width = 90.dp) { s ->
            val b = core.objectives.firstOrNull { it.campId.toString() == s.trim() }
            when {
                b == null -> CommandSession.status = "No objective has CampId $s."
                o.links.size >= ObjectiveOps.MAX_LINKS -> CommandSession.status = "$name has ${ObjectiveOps.MAX_LINKS} links already."
                else -> ctx.edit(ObjectiveOps.link(core, o, b))
            }
        }
    }
}

private fun linkCost(core: CommandSave, o: CmdObjective, l: ObjLink, i: Int, v: Int): CommandEdit =
    Edits.set("${MOVES[i]} cost of a link of ${ObjectiveOps.displayName(core, o)}", "obj", { l.costs }, { l.costs = it }, l.costs.copyOf().also { it[i] = v.coerceIn(0, 255) })

@Composable
private fun CostCell(c: Int, onCommit: (Int) -> Unit) {
    EdField(if (c >= 255) "–" else "$c", mono = true, width = 22.dp, align = TextAlign.End) { s ->
        val t = s.trim()
        val v = if (t == "–" || t == "-" || t.isEmpty()) 255 else t.toIntOrNull()
        if (v == null || v !in 0..255) CommandSession.status = "A cost goes from 0 to 254; – (or 255) closes the link to that movement." else onCommit(v)
    }
}

@Composable
private fun Radar(ctx: CmdContext, o: CmdObjective) {
    val core = ctx.core
    val name = ObjectiveOps.displayName(core, o)
    EdHeading("Radar sectors") {
        Text("Terrain", color = Hud.Amber, fontSize = 11.sp, modifier = Modifier.clickable { radarAll(ctx, listOf(o)) }.padding(horizontal = 4.dp))
        Text("Default", color = Hud.Amber, fontSize = 11.sp, modifier = Modifier.clickable {
            ctx.edit(ObjectiveOps.setRadar(core, o, FloatArray(8) { ObjectiveOps.RADAR_DEFAULT }, "Radar default for $name"))
        }.padding(horizontal = 4.dp))
    }
    EdCheck("Has radar data", o.hasRadar != 0) { on ->
        ctx.edit(ObjectiveOps.setRadar(core, o, if (on) FloatArray(8) { i -> o.detectRatio(i).takeIf { it.isFinite() && it > 0f } ?: ObjectiveOps.RADAR_DEFAULT } else null, "Radar data of $name ${if (on) "on" else "off"}"))
    }
    if (o.hasRadar == 0) { EdNote("No radar data: BMS's radar here sees as over open ground."); return }
    for (i in 0 until 8) {
        val (a, b) = ObjectiveOps.sectorBearings(i)
        EdRow("%03.0f°–%03.0f°".format(a, b), tip = "The slope of the ground the radar cannot see under in this sector (rise over distance)") {
            EdField("%.4f".format(o.detectRatio(i)), mono = true, width = 90.dp, align = TextAlign.End) { s ->
                val v = s.trim().toFloatOrNull()
                if (v == null || !v.isFinite() || v < 0f) CommandSession.status = "\"$s\" is not a ratio." else {
                    val bits = o.detectRatioBits.copyOf().also { it[i] = v.toRawBits() }
                    ctx.edit(ObjectiveOps.field("Radar sector ${i + 1} of $name", { o.detectRatioBits }, { o.detectRatioBits = it }, bits))
                }
            }
        }
    }
}

/** Radar sectors from the terrain for [objs] (read from the height map on a background thread). */
internal fun radarAll(ctx: CmdContext, objs: List<CmdObjective>) {
    if (objs.isEmpty()) { ctx.say("None of those has a radar."); return }
    ctx.say("Reading the ground round ${objs.size} radar${if (objs.size == 1) "" else "s"}…")
    Thread {
        val edits = ArrayList<CommandEdit>()
        var why: String? = null
        for (o in objs) {
            val (r, w) = ObjectiveOps.radarFromTerrain(ctx.core, o)
            if (r != null) edits += ObjectiveOps.setRadar(ctx.core, o, r, "Radar from terrain") else if (why == null) why = w
        }
        javax.swing.SwingUtilities.invokeLater {
            if (edits.isEmpty()) ctx.say(why ?: "Nothing was read.")
            else ctx.edit(Edits.group(if (objs.size == 1) "Radar of ${ObjectiveOps.displayName(ctx.core, objs[0])} from terrain" else "Radar of ${edits.size} objectives from terrain", edits))
        }
    }.apply { isDaemon = true }.start()
}

/** The saves of the start's folder that would lose their objective changes and units' places, as a sentence. */
internal fun savesLine(ctx: CmdContext): String {
    val saves = ctx.core.savesUsingThisStart()
    return if (saves.isEmpty()) "No other save in this folder names ${ctx.doc.file.nameWithoutExtension} as its start."
    else "${saves.size} save${if (saves.size == 1) "" else "s"} in this folder name${if (saves.size == 1) "s" else ""} ${ctx.doc.file.nameWithoutExtension} as ${if (saves.size == 1) "its" else "their"} start and take objectives from it: " +
        saves.take(12).joinToString { it.name } + (if (saves.size > 12) " … (${saves.size - 12} more)" else "") +
        ". Their objective changes are kept by id: a deleted objective's changes make BMS stop reading the rest, and units ordered to it lose their way."
}

internal fun askAdd(ctx: CmdContext, template: CmdObjective, north: Double, east: Double) {
    EdDialogs.ask = EdAsk(
        "Add an objective to ${ctx.doc.file.name}?",
        "A new objective of the same class as ${ObjectiveOps.displayName(ctx.core, template)}, with a new id and CampId, is added to this start. " + savesLine(ctx),
        "Add it", danger = false,
    ) {
        val (e, o) = ObjectiveOps.add(ctx.core, template, north, east, ObjectiveOps.displayName(ctx.core, template) + " 2")
        if (e == null || o == null) "This file has no objectives of its own." else {
            val why = ctx.core.edits.run(e)
            if (why == null) { ctx.selection.set(CmdRef(CmdKind.OBJECTIVE, o.id.toString())); CommandSession.done(e.label) }
            why
        }
    }
}

internal fun askDelete(ctx: CmdContext, objs: List<CmdObjective>) {
    val used = ObjectiveOps.usedBy(ctx.core, objs)
    EdDialogs.ask = EdAsk(
        "Delete ${if (objs.size == 1) ObjectiveOps.displayName(ctx.core, objs[0]) else "${objs.size} objectives"}?",
        "Deleted from this start with every link to ${if (objs.size == 1) "it" else "them"}. " + savesLine(ctx) +
            (if (used.isNotEmpty()) "\n\nStill named by ${used.size} unit${if (used.size == 1) "" else "s"} of this file: ${used.take(8).joinToString()}${if (used.size > 8) " …" else ""} (the Checks page lists them afterwards)." else ""),
        "Delete", danger = true,
    ) {
        val why = ctx.core.edits.run(ObjectiveOps.delete(ctx.core, objs))
        if (why == null) ctx.selection.clear()
        why
    }
}

/**
 * "Add an objective here…" on the map's menu (empty ground, or an objective whose class it starts with): a dialog for
 * its class, owner, name and place. Shown in a save too, greyed with the reason (a save takes its objectives from its
 * start; "Open its start…" is the line under it).
 */
object AddObjectiveHere : CmdAction {
    override val label = "Add an objective here…"
    override val mapOnly = true
    override fun accepts(ctx: CmdContext, ref: CmdRef?): Boolean = ref == null || ref.kind == CmdKind.OBJECTIVE
    override fun refusal(ctx: CmdContext, ref: CmdRef?): String? = ObjectiveAddDelete.refusal(ctx)
    override fun run(ctx: CmdContext, ref: CmdRef?, north: Double, east: Double) {
        MoreDialogs.req = MoreDialogs.AddObjective(north, east, ctx.objective(ref) ?: ctx.objective(ctx.selection.primary))
    }
}

/** "Repair" on the map's menu for an objective (its delta in a save). */
object RepairObjective : CmdAction {
    override val label = "Repair every building"
    override fun accepts(ctx: CmdContext, ref: CmdRef?): Boolean = ref?.kind == CmdKind.OBJECTIVE
    override fun refusal(ctx: CmdContext, ref: CmdRef?): String? = if (ctx.objMode() == ObjectiveOps.Mode.NONE) ObjectiveOps.modeWhy(ctx.core, ctx.editingStart()) else null
    override fun run(ctx: CmdContext, ref: CmdRef?, north: Double, east: Double) {
        val objs = (if (ref != null && ref in ctx.selection) ctx.selection.refs else listOfNotNull(ref)).mapNotNull { ctx.objective(it) }
        ctx.edit(ObjectiveOps.repair(ctx.core, objs, ctx.objMode() == ObjectiveOps.Mode.FULL))
    }
}
