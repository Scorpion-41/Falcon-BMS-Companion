package com.bmscompanion.desktop.ui.command.inspectors

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bmscompanion.app.ui.screens.editor.Rule
import com.bmscompanion.app.ui.theme.Hud
import com.bmscompanion.desktop.bridge.CampaignArchive
import com.bmscompanion.desktop.bridge.command.CampaignOps
import com.bmscompanion.desktop.bridge.command.CmpHeader
import com.bmscompanion.desktop.bridge.command.Edits
import com.bmscompanion.desktop.bridge.command.ObjectiveOps
import com.bmscompanion.desktop.bridge.command.SquadInfo
import com.bmscompanion.desktop.ui.command.CmdContext
import com.bmscompanion.desktop.ui.command.CMD_BAR
import com.bmscompanion.desktop.ui.command.CmdHBar
import com.bmscompanion.desktop.ui.command.CmdVBar
import com.bmscompanion.desktop.ui.command.shiftWheel
import androidx.compose.foundation.layout.fillMaxHeight
import com.bmscompanion.desktop.ui.command.CmdGrid
import com.bmscompanion.desktop.ui.command.CmdKind
import com.bmscompanion.desktop.ui.command.CmdPage
import com.bmscompanion.desktop.ui.command.CmdPageView
import com.bmscompanion.desktop.ui.command.CmdRef
import com.bmscompanion.desktop.ui.command.CommandSession
import com.bmscompanion.desktop.ui.command.DeskButton
import com.bmscompanion.desktop.ui.command.FactRow
import com.bmscompanion.desktop.ui.command.GridCol
import com.bmscompanion.desktop.ui.command.MiniChip
import com.bmscompanion.desktop.ui.command.MiniWrap
import com.bmscompanion.desktop.ui.command.Strip
import com.bmscompanion.desktop.ui.command.teamInk

/**
 * The Campaign page: the header's fields (names, clock and dates, the campaign period, TE settings, ratios, the
 * bullseye), **Shift Times** of the whole file, the header's squadron list against the units, the occupation map
 * (shown, built from the objectives, painted), the event flags, the primary objective list (rebuilt from the priority
 * cities) and the victory conditions' text. Every change is an undoable edit of the open file.
 */
object CampaignPage : CmdPageView {
    override val page = CmdPage.CAMPAIGN

    internal enum class Tab(val label: String) { HEADER("Header and times"), SQUADRONS("Squadron list"), OCCUPATION("Occupation map"), EVENTS("Events"), POL("Primary objectives"), VICTORY("Victory conditions") }

    /** The sub-page shown (kept while the section is open; a render check sets it). */
    internal var tab by mutableStateOf(Tab.HEADER)

    @Composable
    override fun Content(ctx: CmdContext) {
        @Suppress("UNUSED_VARIABLE") val v = CommandSession.version
        Column(Modifier.fillMaxSize()) {
            EdDialogHost()
            Strip { Tab.entries.forEach { t -> MiniChip(t.label, tab == t) { tab = t } } }
            Rule(0.5f)
            val h = ctx.core.cmp
            if (h == null) { EdNote("The campaign header could not be read.", Hud.Amber); return@Column }
            when (tab) {
                Tab.HEADER -> Header(ctx, h)
                Tab.SQUADRONS -> Squadrons(ctx, h)
                Tab.OCCUPATION -> Occupation(ctx, h)
                Tab.EVENTS -> Events(ctx)
                Tab.POL -> Pol(ctx)
                Tab.VICTORY -> Victory(ctx)
            }
        }
    }

    private fun <T> cmp(label: String, get: () -> T, set: (T) -> Unit, v: T) = Edits.set(label, "cmp", get, set, v)

    @Composable
    private fun Header(ctx: CmdContext, h: CmpHeader) {
        val core = ctx.core
        val vs = rememberScrollState()
        val hs = rememberScrollState()
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().then(Modifier.shiftWheel(hs))) {
        Row(Modifier.fillMaxSize().padding(end = CMD_BAR, bottom = CMD_BAR).verticalScroll(vs).horizontalScroll(hs).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            EdColumn(340.dp) {
                EdHeading("Names")
                EdText("Shown as", h.uiName.text, tip = "The name BMS's screens give the campaign") { ctx.edit(cmp("Campaign name", { h.uiName }, { h.uiName = it }, h.uiName.copy().also { x -> x.text = it.take(39) })) }
                FactRow("Theater", h.theaterName.text)
                FactRow("Start file", h.scenario.text.ifBlank { "—" })
                FactRow("Saved as", h.saveFile.text)
                EdNote("BMS takes the objectives from the start file named here; Save as… sets \"Saved as\" to the copy's name.")
                EdHeading("Clock")
                ClockField(ctx, "Clock now", { h.currentTime }, { h.currentTime = it })
                ClockField(ctx, "Started", { h.startTime }, { h.startTime = it })
                ClockField(ctx, "Ends", { h.timeLimit }, { h.timeLimit = it }, tip = "The time limit (empty: none)")
                EdInt("Current day", h.currentDay, 0..255) { ctx.edit(cmp("Current day", { h.currentDay }, { h.currentDay = it }, it)) }
                EdInt("Day zero", h.dayZero, 0..255, tip = "The day of the year the campaign's day 1 is") { ctx.edit(cmp("Day zero", { h.dayZero }, { h.dayZero = it }, it)) }
                h.campPeriodStart?.let { s -> EdInt("Period from", s, 0..9999, tip = "The first year of the campaign's period (0 = any)") { ctx.edit(cmp("Campaign period start", { h.campPeriodStart }, { h.campPeriodStart = it }, it)) } }
                h.campPeriodEnd?.let { s -> EdInt("Period to", s, 0..9999, tip = "The last year (9999 = any)") { ctx.edit(cmp("Campaign period end", { h.campPeriodEnd }, { h.campPeriodEnd = it }, it)) } }
                FactRow("Last major event", if (h.lastMajorEvent > 0) CampaignArchive.clock(h.lastMajorEvent) else "—")
                FactRow("Last resupply", if (h.lastResupply > 0) CampaignArchive.clock(h.lastResupply) else "—")
                FactRow("Last repair", if (h.lastRepair > 0) CampaignArchive.clock(h.lastRepair) else "—")
                FactRow("Last reinforcement", if (h.lastReinforcement > 0) CampaignArchive.clock(h.lastReinforcement) else "—")
                EdHeading("Shift times")
                ShiftTimes(ctx)
            }
            EdColumn(320.dp) {
                EdHeading("Engagement (TE)")
                EdInt("TE type", h.teType, -1..255) { ctx.edit(cmp("TE type", { h.teType }, { h.teType = it }, it)) }
                EdInt("Victory points", h.teVictoryPoints, Int.MIN_VALUE..Int.MAX_VALUE) { ctx.edit(cmp("Victory points", { h.teVictoryPoints }, { h.teVictoryPoints = it }, it)) }
                EdText("TE flags", "0x" + h.teFlags.toString(16), mono = true) { s -> s.trim().removePrefix("0x").toLongOrNull(16)?.let { ctx.edit(cmp("TE flags", { h.teFlags }, { h.teFlags = it }, it.toInt())) } ?: run { CommandSession.status = "Flags are a hexadecimal number." } }
                FactRow("TE teams", "${h.teNumberTeams} · player's team ${h.teTeam}")
                Row(Modifier.padding(horizontal = 6.dp, vertical = 3.dp)) {
                    listOf("TEAM" to 120, "POINTS" to 60, "AIRCRAFT" to 64, "F-16S" to 52).forEach { (t, w) -> Text(t, color = Hud.TextFaint, fontSize = 9.5.sp, modifier = Modifier.width(w.dp)) }
                }
                for (n in 0 until 8) {
                    Row(Modifier.fillMaxWidth().height(23.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.width(120.dp), verticalAlignment = Alignment.CenterVertically) {
                            com.bmscompanion.desktop.ui.command.TeamFlagLead(core.teamName(n), core.teams.getOrNull(n)?.team?.teamColor ?: n, 10.dp)
                            Text("$n ${core.teamName(n)}", color = ctx.teamInkOf(n) ?: Hud.Text, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        ArrCell(ctx, "TE points of team $n", { h.teTeamPoints }, { h.teTeamPoints = it }, n, 60)
                        ArrCell(ctx, "TE aircraft of team $n", { h.teNumberAircraft }, { h.teNumberAircraft = it }, n, 64)
                        ArrCell(ctx, "TE F-16s of team $n", { h.teNumberF16s }, { h.teNumberF16s = it }, n, 52)
                    }
                    Rule(0.08f)
                }
                EdHeading("Situation")
                EdInt("Situation", h.situation, 0..255) { ctx.edit(cmp("Situation", { h.situation }, { h.situation = it }, it)) }
                EdInt("Brief", h.brief, -32768..32767) { ctx.edit(cmp("Brief", { h.brief }, { h.brief = it }, it)) }
                EdInt("Group", h.group, -32768..32767) { ctx.edit(cmp("Group", { h.group }, { h.group = it }, it)) }
                EdInt("End result", h.endgameResult, 0..255) { ctx.edit(cmp("End result", { h.endgameResult }, { h.endgameResult = it }, it)) }
                EdInt("Active team", h.activeTeam, 0..255) { ctx.edit(cmp("Active team", { h.activeTeam }, { h.activeTeam = it }, it)) }
            }
            EdColumn(300.dp) {
                EdHeading("Ratios")
                EdInt("Ground", h.groundRatio, -32768..32767) { ctx.edit(cmp("Ground ratio", { h.groundRatio }, { h.groundRatio = it }, it)) }
                EdInt("Air", h.airRatio, -32768..32767) { ctx.edit(cmp("Air ratio", { h.airRatio }, { h.airRatio = it }, it)) }
                EdInt("Air defence", h.airDefenseRatio, -32768..32767) { ctx.edit(cmp("Air defence ratio", { h.airDefenseRatio }, { h.airDefenseRatio = it }, it)) }
                EdInt("Naval", h.navalRatio, -32768..32767) { ctx.edit(cmp("Naval ratio", { h.navalRatio }, { h.navalRatio = it }, it)) }
                EdHeading("Enemy experience")
                EdInt("Air", h.enemyAirExp, 0..255) { ctx.edit(cmp("Enemy air experience", { h.enemyAirExp }, { h.enemyAirExp = it }, it)) }
                EdInt("Air defence", h.enemyADExp, 0..255) { ctx.edit(cmp("Enemy air defence experience", { h.enemyADExp }, { h.enemyADExp = it }, it)) }
                EdHeading("Bullseye")
                EdInt("Grid east", h.bullseyeX, 0..(h.theaterSizeX.coerceAtLeast(1) - 1), unit = "km") { ctx.edit(cmp("Bullseye east", { h.bullseyeX }, { h.bullseyeX = it }, it)) }
                EdInt("Grid north", h.bullseyeY, 0..(h.theaterSizeY.coerceAtLeast(1) - 1), unit = "km") { ctx.edit(cmp("Bullseye north", { h.bullseyeY }, { h.bullseyeY = it }, it)) }
                EdInt("Name", h.bullseyeName, 0..255, tip = "The bullseye's name number") { ctx.edit(cmp("Bullseye name", { h.bullseyeName }, { h.bullseyeName = it }, it)) }
                core.objectives.minByOrNull { (it.x - h.bullseyeX) * (it.x - h.bullseyeX) + (it.y - h.bullseyeY) * (it.y - h.bullseyeY) }?.let { o ->
                    FactRow("Nearest", ObjectiveOps.displayName(core, o))
                    EdButtons { DeskButton("At the selected objective", enabled = ctx.objective(ctx.selection.primary) != null) {
                        ctx.objective(ctx.selection.primary)?.let { s -> ctx.edit(Edits.group("Bullseye at ${ObjectiveOps.displayName(core, s)}", listOf(cmp("", { h.bullseyeX }, { h.bullseyeX = it }, s.x), cmp("", { h.bullseyeY }, { h.bullseyeY = it }, s.y)))) }
                    } }
                }
                EdHeading("Other")
                FactRow("Theater size", "${h.theaterSizeX} x ${h.theaterSizeY} km")
                FactRow("Player's squadron", core.unit(h.playerSquadronId)?.let { com.bmscompanion.desktop.bridge.command.CommandChecks.unitName(core, it) } ?: if (h.playerSquadronId.isNone) "—" else h.playerSquadronId.toString())
                FactRow("Time stamp", "${h.timeStamp}")
                FactRow("Last index", "${h.lastIndexNum}")
                if (h.hasCreation) FactRow("Created", "tempo ${h.tempo} · time ${h.creationTime} · number ${h.creationRand}", mono = true)
                FactRow("News", "${h.recentEvents.size} recent, ${h.priorityEvents.size} priority events")
            }
        }
            CmdVBar(androidx.compose.foundation.rememberScrollbarAdapter(vs), Modifier.align(Alignment.TopEnd).padding(bottom = CMD_BAR).fillMaxHeight())
            CmdHBar(hs, Modifier.align(Alignment.BottomStart).padding(end = CMD_BAR).fillMaxWidth())
        }
    }

    @Composable
    private fun ArrCell(ctx: CmdContext, label: String, get: () -> IntArray, set: (IntArray) -> Unit, i: Int, w: Int) {
        val a = get()
        EdField("${a.getOrElse(i) { 0 }}", i in a.indices, mono = true, width = (w - 4).dp, modifier = Modifier.padding(end = 4.dp)) { s ->
            s.trim().toIntOrNull()?.let { v -> ctx.edit(Edits.set(label, "cmp", get, set, a.copyOf().also { it[i] = v })) } ?: run { CommandSession.status = "\"$s\" is not a whole number." }
        }
    }

    @Composable
    private fun ClockField(ctx: CmdContext, label: String, get: () -> Long, set: (Long) -> Unit, tip: String? = null) {
        EdRow(label, tip) {
            EdField(get().let { if (it == 0L && label == "Ends") "" else CampaignArchive.clock(it) }, mono = true, width = 130.dp) { s ->
                val ms = if (s.isBlank()) 0L else CampaignOps.parseClock(s)
                if (ms == null) CommandSession.status = "Write a time as D2 04:30:00 (day, hours, minutes, seconds)."
                else ctx.edit(Edits.set(label, "cmp", get, set, ms))
            }
        }
    }

    @Composable
    private fun ShiftTimes(ctx: CmdContext) {
        var text by remember { mutableStateOf("+1h") }
        val ms = CampaignOps.parseShift(text)
        EdRow("Shift by", tip = "+2h, -30m, +1d, or +1d 02:30") {
            EdField(text, mono = true, width = 110.dp) { text = it }
            Spacer(Modifier.width(8.dp))
            DeskButton("Shift", enabled = ms != null && ms != 0L, tip = "Every time of the file moves together: the clock, every route, the teams' actions, the packages' times") {
                ms?.let { d ->
                    EdDialogs.ask = EdAsk(
                        "Shift every time by ${CampaignOps.shiftText(d)}?",
                        "The clock and every time of ${ctx.doc.file.name} move by ${CampaignOps.shiftText(d)}: every unit's route, the flights' and packages' times and the teams' actions. " +
                            "Units stay where they are, so they are where the new clock expects them. Undo takes it back.",
                        "Shift", false,
                    ) { ctx.core.edits.run(CampaignOps.shiftTimes(ctx.core, d)) }
                }
            }
        }
        EdNote(if (ms == null) "\"$text\" does not read as a shift." else "Clock now ${CampaignArchive.clock(ctx.core.cmp?.currentTime ?: 0)} → ${CampaignArchive.clock((ctx.core.cmp?.currentTime ?: 0) + ms)}")
    }

    @Composable
    private fun Squadrons(ctx: CmdContext, h: CmpHeader) {
        val core = ctx.core
        val orphans = remember(CommandSession.version) { CampaignOps.orphanSquadInfo(core).toSet() }
        val unlisted = remember(CommandSession.version) { CampaignOps.unlistedSquadrons(core) }
        var picked by remember { mutableStateOf<Set<Int>>(emptySet()) }
        Column(Modifier.fillMaxSize()) {
            Strip {
                Text("${h.squadInfo.size} squadrons listed in the header · ${orphans.size} with no unit · ${unlisted.size} squadron${if (unlisted.size == 1) "" else "s"} not listed", color = if (orphans.isNotEmpty() || unlisted.isNotEmpty()) Hud.Amber else Hud.TextDim, fontSize = 11.5.sp, modifier = Modifier.weight(1f))
                DeskButton("Delete ${picked.size}", enabled = picked.isNotEmpty(), danger = true) {
                    val l = picked.mapNotNull { h.squadInfo.getOrNull(it) }; picked = emptySet()
                    ctx.edit(CampaignOps.removeSquadInfo(core, l, "Remove ${l.size} squadron list entries"))
                }
                DeskButton("Remove ${orphans.size} with no unit", enabled = orphans.isNotEmpty()) { picked = emptySet(); ctx.edit(CampaignOps.removeSquadInfo(core, orphans.toList(), "Remove ${orphans.size} squadron list entries with no unit")) }
                DeskButton("List ${unlisted.size} missing", enabled = unlisted.isNotEmpty() && h.squadInfo.isNotEmpty()) { ctx.edit(CampaignOps.addSquadInfo(core, unlisted, "List ${unlisted.size} squadrons in the header")) }
            }
            CmdGrid(
                rows = h.squadInfo.withIndex().toList(),
                cols = listOf(
                    GridCol<IndexedValue<SquadInfo>>("Nr", 44.dp, numeric = true, sort = { it.index }) { "${it.index + 1}" },
                    GridCol("Squadron", 200.dp, ink = { if (it.value in orphans) Hud.Red else null }) { it.value.squadronName.text.trim() },
                    GridCol("Airbase", 170.dp) { it.value.airbaseName.text.trim() },
                    GridCol("Team", 100.dp, ink = { s -> core.unit(s.value.id)?.let { ctx.teamInkOf(it.owner) } }, lead = { s -> core.unit(s.value.id)?.let { ctx.teamFlag(it.owner) } }) { s -> core.unit(s.value.id)?.let { core.teamName(it.owner) } ?: "no unit" },
                    GridCol("Class", 150.dp) { s -> core.unit(s.value.id)?.let { core.unitClass(it) }.orEmpty() },
                    GridCol("CampId", 64.dp, numeric = true) { "${it.value.campId}" },
                    GridCol("Strength", 70.dp, numeric = true) { "${it.value.currentStrength}" },
                    GridCol("Specialty", 70.dp, numeric = true) { "${it.value.specialty}" },
                    GridCol("North", 90.dp, numeric = true) { "%.0f".format(it.value.x) },
                    GridCol("East", 90.dp, numeric = true) { "%.0f".format(it.value.y) },
                    GridCol("Paint", 50.dp, numeric = true) { "${it.value.texSet}" },
                ),
                key = { System.identityHashCode(it.value) },
                selected = { it.index in picked },
                onClick = { s, add ->
                    picked = if (add) (if (s.index in picked) picked - s.index else picked + s.index) else setOf(s.index)
                    if (!add) core.unit(s.value.id)?.let { ctx.selection.set(CmdRef(CmdKind.SQUADRON, it.id.toString())) }
                },
            )
        }
    }

    @Composable
    private fun Occupation(ctx: CmdContext, h: CmpHeader) {
        val core = ctx.core
        val m = CampaignOps.occMap(core)
        if (m == null) { EdNote("This file has no occupation map."); return }
        var team by remember { mutableIntStateOf(ctx.world.teams.firstOrNull { it.inUse && it.n > 0 }?.n ?: 1) }
        var brush by remember { mutableIntStateOf(1) }
        var work by remember(CommandSession.version) { mutableStateOf<ByteArray?>(null) }
        var tick by remember { mutableIntStateOf(0) }
        val (ok, n) = remember(CommandSession.version) { CampaignOps.occAgreement(core) }
        val colours = remember(ctx.world) { (0 until 16).map { t -> ctx.world.team(t)?.let { teamInk(it.colour) } ?: Color.Transparent } }
        Row(Modifier.fillMaxSize().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.width(300.dp).verticalScroll(rememberScrollState())) {
                EdHeading("Occupation map")
                EdNote("Who holds each ${m.cellKm} km square, as BMS's campaign map shows it (${m.width} x ${m.height} squares). It agrees with $ok of $n objectives' owners.")
                EdHeading("Paint")
                Column(Modifier.padding(horizontal = 6.dp)) {
                    MiniWrap { ctx.world.teams.forEach { t -> MiniChip("${t.n} ${t.name}", team == t.n, teamInk(t.colour), lead = { com.bmscompanion.desktop.ui.command.TeamFlagLead(t.name, t.colour) }) { team = t.n } } }
                    MiniWrap { listOf(0 to "1 square", 1 to "3 x 3", 3 to "7 x 7", 6 to "13 x 13").forEach { (b, w) -> MiniChip(w, brush == b) { brush = b } } }
                }
                EdNote("Drag on the map to paint the chosen team; each stroke is one edit (Undo takes it back).")
                EdButtons {
                    DeskButton("Build from the objectives", tip = "Every square the owner of its nearest objective (within 40 km)") {
                        CampaignOps.buildOccMap(core)?.let { ctx.edit(CampaignOps.setOccMap(core, it, "Occupation map from the objectives")) } ?: ctx.say("There are no objectives to build it from.")
                    }
                }
            }
            val map = work ?: h.campMap
            @Suppress("UNUSED_VARIABLE") val t = tick
            Canvas(
                Modifier.size(620.dp).border(1.dp, Hud.Outline).background(Color.Black.copy(alpha = 0.25f))
                    .pointerInput(m, team, brush) {
                        fun paint(p: Offset) {
                            val w = work ?: h.campMap.copyOf().also { work = it }
                            val cx = (p.x / size.width * m.width).toInt()
                            val cy = m.height - 1 - (p.y / size.height * m.height).toInt()
                            for (dx in -brush..brush) for (dy in -brush..brush) m.set(w, cx + dx, cy + dy, team)
                            tick++
                        }
                        detectDragGestures(
                            onDragStart = { paint(it) },
                            onDrag = { ch, _ -> ch.consume(); paint(ch.position) },
                            onDragEnd = { work?.let { w -> work = null; ctx.edit(CampaignOps.setOccMap(core, w, "Paint the occupation map: ${core.teamName(team)}")) } },
                        )
                    }
                    .pointerInput(m, team, brush) {
                        detectTapGestures { p ->
                            val w = h.campMap.copyOf()
                            val cx = (p.x / size.width * m.width).toInt()
                            val cy = m.height - 1 - (p.y / size.height * m.height).toInt()
                            for (dx in -brush..brush) for (dy in -brush..brush) m.set(w, cx + dx, cy + dy, team)
                            ctx.edit(CampaignOps.setOccMap(core, w, "Paint the occupation map: ${core.teamName(team)}"))
                        }
                    },
            ) {
                val cw = size.width / m.width
                val ch = size.height / m.height
                for (cy in 0 until m.height) for (cx in 0 until m.width) {
                    val v = m.get(map, cx, cy)
                    if (v <= 0) continue
                    drawRect(colours[v].copy(alpha = 0.75f), Offset(cx * cw, (m.height - 1 - cy) * ch), Size(cw + 0.5f, ch + 0.5f))
                }
                // the objectives on top, as dots of their owners
                val k = m.cellKm.toFloat()
                for (o in core.objectives) {
                    val x = o.x / k * cw; val y = (m.height - o.y / k) * ch
                    drawCircle(Color.Black.copy(alpha = 0.6f), 1.6f, Offset(x, y))
                }
            }
        }
    }

    @Composable
    private fun Events(ctx: CmdContext) {
        val core = ctx.core
        val evt = core.evt
        if (evt == null) { EdNote("This file has no event part."); return }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp)) {
            EdHeading("Campaign events (${evt.events.size})")
            EdNote("BMS's scripted campaign events: whether each may fire only once and whether it has fired.")
            Row(Modifier.padding(horizontal = 6.dp)) {
                listOf("NR" to 50, "EVENT" to 80, "ONE TIME ONLY" to 130, "HAS FIRED" to 110, "FLAGS" to 60).forEach { (t, w) -> Text(t, color = Hud.TextFaint, fontSize = 9.5.sp, modifier = Modifier.width(w.dp)) }
            }
            evt.events.forEachIndexed { i, e ->
                Row(Modifier.height(23.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}", color = Hud.TextFaint, fontSize = 11.sp, modifier = Modifier.width(50.dp))
                    Text("${e.id}", color = Hud.Text, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(80.dp))
                    Column(Modifier.width(130.dp)) { com.bmscompanion.desktop.ui.command.CheckLine("", e.flags and 1 != 0, enabled = evt.editable) { ctx.edit(CampaignOps.eventFlag(core, e, 1, it)) } }
                    Column(Modifier.width(110.dp)) { com.bmscompanion.desktop.ui.command.CheckLine("", e.flags and 8 != 0, enabled = evt.editable) { ctx.edit(CampaignOps.eventFlag(core, e, 8, it)) } }
                    Text("0x" + e.flags.toString(16), color = Hud.TextDim, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(60.dp))
                }
                Rule(0.08f)
            }
        }
    }

    @Composable
    private fun Pol(ctx: CmdContext) {
        val core = ctx.core
        val pol = core.pol
        if (pol == null) { EdNote("This file has no primary objective part."); return }
        var minPrio by remember { mutableIntStateOf(80) }
        Column(Modifier.fillMaxSize()) {
            Strip {
                Text("${pol.entries.size} primary objectives · the teams the list keeps a priority for: ${(0 until 8).filter { pol.teamMask and (1 shl it) != 0 }.joinToString()}", color = Hud.TextDim, fontSize = 11.5.sp, modifier = Modifier.weight(1f))
                Text("cities of priority ≥", color = Hud.TextDim, fontSize = 11.5.sp)
                EdField("$minPrio", mono = true, width = 50.dp) { s -> s.trim().toIntOrNull()?.let { minPrio = it.coerceIn(0, 255) } }
                DeskButton("Rebuild the list", enabled = core.objectives.isNotEmpty(), tip = "Every city of that priority or more, most important first; each team's priority left to BMS") {
                    EdDialogs.ask = EdAsk("Rebuild the primary objective list?", "The list is made again from the cities of priority $minPrio and more (${core.objectives.count { ObjectiveOps.typeOf(core, it) == 8 && it.priority >= minPrio }} of them); the per-team priorities set by hand are dropped.", "Rebuild", true) {
                        core.edits.run(CampaignOps.rebuildPol(core, minPrio))
                    }
                }
            }
            CmdGrid(
                rows = pol.entries.withIndex().toList(),
                cols = listOf(
                    GridCol<IndexedValue<com.bmscompanion.desktop.bridge.command.PolEntry>>("Nr", 44.dp, numeric = true, sort = { it.index }) { "${it.index + 1}" },
                    GridCol("Objective", 220.dp) { e -> core.objective(e.value.id)?.let { ObjectiveOps.displayName(core, it) } ?: "${e.value.id} (missing)" },
                    GridCol("CampId", 64.dp, numeric = true) { e -> core.objective(e.value.id)?.campId?.toString().orEmpty() },
                    GridCol("Priority", 64.dp, numeric = true) { e -> core.objective(e.value.id)?.priority?.toString().orEmpty() },
                    GridCol("Held by", 110.dp, ink = { e -> core.objective(e.value.id)?.let { ctx.teamInkOf(core.ownerNow(it)) } }, lead = { e -> core.objective(e.value.id)?.let { ctx.teamFlag(core.ownerNow(it)) } }) { e -> core.objective(e.value.id)?.let { core.teamName(core.ownerNow(it)) }.orEmpty() },
                    GridCol("Team priorities", 260.dp) { e -> e.value.priority.joinToString(" ") { if (it < 0) "·" else "$it" } },
                ),
                key = { System.identityHashCode(it.value) },
                selected = { e -> ctx.selection.primary?.id == e.value.id.toString() },
                onClick = { e, _ -> if (core.objective(e.value.id) != null) ctx.selection.set(CmdRef(CmdKind.OBJECTIVE, e.value.id.toString())) },
            )
        }
    }

    @Composable
    private fun Victory(ctx: CmdContext) {
        val text = ctx.core.teText
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
            EdHeading("Victory conditions")
            if (text == null) EdNote("This file has no victory conditions (a campaign has none; a TE keeps them in its .te part).")
            else Text(text.replace('\u000B', '\n'), color = Hud.Text, fontSize = 12.sp, fontFamily = FontFamily.Monospace, lineHeight = 16.sp, modifier = Modifier.padding(6.dp))
            EdNote("Shown as BMS keeps it; the TE's victory conditions are edited in BMS's own TE screens.")
        }
    }
}
