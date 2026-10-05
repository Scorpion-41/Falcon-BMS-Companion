package com.bmscompanion.app.ui.screens.wdp

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.mission.KbFileResult
import com.bmscompanion.app.data.mission.MissionMode
import com.bmscompanion.app.data.mission.MissionSourceInfo
import com.bmscompanion.app.ui.theme.Hud

/**
 * **Steps**: the evening from Falcon BMS to the cockpit in ten short lines, beside the Planner's page (a panel on the
 * right where the Planner is 840 dp or wider and wider than tall, toggled by the toolbar's **Steps** and remembered on
 * the device; a sheet from the same button on a tablet held upright or a phone).
 *
 * - Each line says where it happens, **in BMS** or in the **Planner**. The step the pilot is on is lit, worked out from
 *   what the app can know ([Facts]): a save's flight open in the Planner, edits not saved, Save to DTC done for this
 *   flight, the Mission section populated from it, Upd Kneeboard done for it. What happens in
 *   BMS the app cannot see, so those lines are tick boxes the pilot ticks, kept for this mission only ([ticks]: a
 *   different flight opened in the Planner starts them again; **Clear ticks** does it by hand).
 * - **One place per action**: a Planner line is not a second button. Where the toolbar shows that action, pressing the
 *   line lights the toolbar's button ([flash]) and the current step's button carries an amber ring while the steps
 *   are shown; only where the toolbar has no room for it (a phone, Upd Kneeboard on a tablet) the line does it in one
 *   press ([onToolbar]).
 * - **Full guide** opens the guide's start page, which has the same ten steps with the why of each.
 */
object PlannerSteps {
    /** the device's settings: the panel shown (1) or not (0); the ticks and what was done, for one mission */
    const val OPEN_KEY = "planner_steps_open"
    const val STATE_KEY = "planner_steps_state"

    enum class Where(val label: String) { BMS("in BMS"), PLANNER("Planner") }

    /**
     * One line. [toolbar] is the toolbar button's probe name that does a Planner step ([run]); [note] a second line in
     * small type.
     */
    class Step(
        val n: Int,
        val where: Where,
        val text: String,
        val note: String? = null,
        val toolbar: String? = null,
        val run: (() -> Unit)? = null,
    )

    val STEPS: List<Step> = listOf(
        Step(1, Where.BMS, "Open the campaign or TE, pick your flight and seat, set the loadout."),
        Step(2, Where.BMS, "Open the DTC, set what you want, SAVE. Then save the campaign or TE."),
        Step(3, Where.BMS, "PRINT the briefing."),
        Step(4, Where.PLANNER, "Open mission… → pick the save, your flight and seat.", toolbar = "Shell/OpenMission", run = { PlannerShell.openMission() }),
        Step(5, Where.PLANNER, "Plan: DataCard, DTC, attack pages, Map."),
        Step(6, Where.PLANNER, "Save to DTC.", toolbar = "Shell/SaveToDtc", run = { PlannerShell.save() }),
        Step(7, Where.BMS, "Open the DTC again → LOAD → SAVE.", note = "Without LOAD, FLY saves BMS's old copy over the Planner's."),
        Step(8, Where.PLANNER, "Populate from Planner (Mission section, VR boards).", toolbar = "Shell/Populate", run = { PlannerShell.populate() }),
        Step(9, Where.PLANNER, "Upd Kneeboard (cockpit pages).", toolbar = "Shell/Print", run = { PlannerShell.print() }),
        Step(10, Where.BMS, "FLY. In the cockpit: DTE page → LOAD."),
    )

    /** the line before step 1 */
    const val WEATHER = "Optional, before 1: weather of your own? Pick it and save it in BMS first."

    /** What the app knows of the evening, for working out the step: see [done]. */
    data class Facts(
        val opened: Boolean = false,
        val unsaved: Int = 0,
        val saved: Boolean = false,
        val populated: Boolean = false,
        val stale: Boolean = false,
        val printed: Boolean = false,
        val ticks: Set<Int> = emptySet(),
        /** when BMS last printed a briefing (ms), 0 for never */
        val printedBriefing: Long = 0,
    )

    /**
     * Whether step [n] is done. In BMS: ticked — and steps 1-3 also once a save's flight is open in the Planner, which
     * needs them. In the Planner: 4 a save's flight is open; 5 there are edits, or they are saved; 6 saved for this
     * flight with nothing waiting; 8 the Mission section shows this flight; 9 Upd Kneeboard wrote
     * pages for this flight.
     */
    fun done(n: Int, f: Facts): Boolean = when (n) {
        1, 2, 3 -> n in f.ticks || f.opened
        4 -> f.opened
        5 -> f.opened && (f.unsaved > 0 || f.saved)
        6 -> f.opened && f.saved && f.unsaved == 0
        8 -> f.opened && f.populated
        9 -> f.opened && f.printed
        else -> n in f.ticks
    }

    /** The step the pilot is on: the first not done, or null when all ten are. */
    fun current(f: Facts): Int? = STEPS.firstOrNull { !done(it.n, f) }?.n

    /** Whether the toolbar at [layout] shows the button [probe] (else a Planner line does it in one press). */
    internal fun onToolbar(probe: String?, layout: ShellLayout): Boolean = when (probe) {
        null -> false
        "Shell/SaveToDtc" -> true
        "Shell/OpenMission", "Shell/Populate" -> layout != ShellLayout.COMPACT
        // a mouse's toolbar holds every action at any width but a phone's (shellFit); a finger's only from 840 dp
        "Shell/Print" -> if (WdpTouch.device) layout == ShellLayout.FULL || layout == ShellLayout.ICONS else layout != ShellLayout.COMPACT
        else -> false
    }

    // ---------------------------------------------------------------- state

    private var loaded = false

    /** the panel beside the page (840 dp and wider), remembered on the device */
    var open: Boolean by mutableStateOf(false)
        private set

    /** the sheet over the Planner (a tablet held upright, a phone), not remembered */
    var sheet: Boolean by mutableStateOf(false)

    /** a toolbar button lit for a moment by a press on its line ([Step.toolbar]) */
    var flash: String? by mutableStateOf(null)
        private set
    internal var flashes: Int by mutableStateOf(0)
        private set

    /** the BMS steps ticked, for [key]'s mission */
    val ticks = mutableStateListOf<Int>()
    /** the mission the ticks and [savedKey]/[printedKey] belong to: "theater|file|callsign", "" before one is opened */
    var key: String by mutableStateOf("")
        private set
    /** the mission Save to DTC last saved for, and the one Upd Kneeboard last wrote pages for */
    var savedKey: String? by mutableStateOf(null)
        private set
    var printedKey: String? by mutableStateOf(null)
        private set

    /** set by the Planner as it lays out: the steps are a panel at this width (else a sheet) */
    internal var side = true

    // the Upd Kneeboard answer seen last: a print is counted when a new one comes back
    private var seenResults: Any? = null
    private var seenAny = false

    fun load() {
        if (loaded) return
        loaded = true
        runCatching { open = Repo.getInt(OPEN_KEY, 0) != 0 }
        runCatching {
            val lines = Repo.getString(STATE_KEY)?.split('\n') ?: return@runCatching
            key = lines.getOrNull(0).orEmpty()
            ticks.clear(); ticks += lines.getOrNull(1).orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() }
            savedKey = lines.getOrNull(2)?.ifEmpty { null }
            printedKey = lines.getOrNull(3)?.ifEmpty { null }
        }
    }

    private fun persist() {
        runCatching { Repo.putString(STATE_KEY, listOf(key, ticks.joinToString(","), savedKey.orEmpty(), printedKey.orEmpty()).joinToString("\n")) }
    }

    /** The panel shown or hidden (the toolbar's Steps where the steps are a panel). */
    fun choosePanel(on: Boolean) {
        load()
        open = on
        runCatching { Repo.putInt(OPEN_KEY, if (on) 1 else 0) }
    }

    /** The toolbar's **Steps**: the panel where there is room, else the sheet. */
    fun toggle() { if (side) choosePanel(!open) else sheet = !sheet }

    /** Shows the steps (the identity strip with nothing open). */
    fun show() { if (side) choosePanel(true) else sheet = true }

    /** The mission the Planner plans now ([missionKey]): a different one than the ticks were made for starts them again. */
    fun follow(now: String) {
        load()
        if (now.isEmpty() || now == key) return
        if (key.isNotEmpty()) { ticks.clear(); savedKey = null; printedKey = null }
        // before any mission was opened, the ticks of steps 1-3 were made for this one: they stay with it
        key = now
        persist()
    }

    fun tick(n: Int) {
        load()
        if (n in ticks) ticks.remove(n) else ticks += n
        persist()
    }

    /** **Clear ticks**: this mission's ticks and what was done for it, as if it were opened afresh. */
    fun clear() {
        load()
        ticks.clear(); savedKey = null; printedKey = null
        persist()
    }

    /** Save to DTC wrote the cartridge (any of its buttons): done for the mission open now. */
    fun saved(now: String = missionKey()) {
        load()
        if (now.isEmpty()) return
        savedKey = now
        persist()
    }

    /** Upd Kneeboard's answer: a print that wrote a page is done for the mission open now. */
    fun printResults(results: List<KbFileResult>, of: String?, now: String = missionKey()) {
        load()
        val first = !seenAny
        seenAny = true
        if (results === seenResults) return
        seenResults = results
        // the answer already there when the Planner first showed is an earlier one's
        if (first) return
        if (of == "Print" && now.isNotEmpty() && results.any { it.status == KbFileResult.WRITTEN }) {
            printedKey = now
            persist()
        }
    }

    /** Lights toolbar button [probe] for a moment (WdpPlanner puts it out). */
    fun flashOn(probe: String) { flash = probe; flashes++ }
    fun flashOff() { flash = null }

    /** The mission the Planner plans now, as [key] names one: a save's flight, or "" for the printed briefing / nothing. */
    fun missionKey(): String {
        if (!PlannerMissionState.fromSave) return ""
        val f = PlannerMissionState.flight ?: return ""
        val r = PlannerMissionState.ref
        return listOf(r?.theater.orEmpty().trim(), r?.file.orEmpty().lowercase(), f.row.callsign).joinToString("|")
    }

    /** What the app knows now ([Facts]), with [unsaved] the toolbar's count and [info] what the Mission section shows. */
    fun facts(unsaved: Int, info: MissionSourceInfo?, printedBriefing: Long): Facts {
        val now = missionKey()
        val opened = now.isNotEmpty()
        val p = info?.populated?.takeIf { info.wdp }
        val f = PlannerMissionState.flight
        val ours = p != null && opened && p.save.equals(PlannerMissionState.ref?.file.orEmpty(), ignoreCase = true) &&
            (p.callsign.isNullOrBlank() || f == null || p.callsign.equals(f.row.callsign, ignoreCase = true))
        return Facts(
            opened = opened,
            unsaved = unsaved,
            saved = opened && savedKey == now,
            populated = ours,
            stale = ours && p?.stale == true,
            printed = opened && printedKey == now,
            ticks = if (key == now || key.isEmpty() || now.isEmpty()) ticks.toSet() else emptySet(),
            printedBriefing = printedBriefing,
        )
    }

    /** The probe names of the toolbar buttons to ring now: the current step's while the steps show, and one flashed. */
    fun ringed(f: Facts, showing: Boolean): Set<String> = buildSet {
        if (showing) current(f)?.let { n -> STEPS.firstOrNull { it.n == n }?.toolbar?.let(::add) }
        flash?.let(::add)
    }
}

/**
 * The steps, as the panel beside the page ([sheet] false) or as the sheet over it, which has its own ×. [layout] says
 * which Planner lines are shortcuts ([PlannerSteps.onToolbar]).
 */
@Composable
internal fun PlannerStepsView(facts: PlannerSteps.Facts, layout: ShellLayout, sheet: Boolean, modifier: Modifier = Modifier) {
    val finger = WdpTouch.device
    val cur = PlannerSteps.current(facts)
    Column(
        modifier.background(Hud.Surface).then(if (sheet) Modifier else Modifier.border(1.dp, Hud.Outline, RoundedCornerShape(8.dp)))
            .plannerProbe(if (sheet) "Steps/Sheet" else "Steps/Panel"),
    ) {
        // the title, and on a sheet its ×
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Steps", color = Hud.Text, fontSize = if (finger) 14.5.sp else 13.5.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(8.dp))
            Text(
                when (cur) { null -> "all done"; else -> "you are on $cur of ${PlannerSteps.STEPS.size}" },
                color = Hud.TextFaint, fontSize = if (finger) 12.sp else 11.sp, maxLines = 1, modifier = Modifier.weight(1f),
            )
            if (sheet) Box(
                Modifier.size(if (finger) 44.dp else 32.dp).clip(RoundedCornerShape(6.dp)).clickable { PlannerSteps.sheet = false }.plannerProbe("Steps/Close"),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Default.Close, "Close", tint = Hud.TextDim, modifier = Modifier.size(18.dp)) }
        }
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 4.dp)) {
            Text(
                PlannerSteps.WEATHER, color = Hud.TextFaint, fontSize = if (finger) 11.5.sp else 11.sp, lineHeight = 14.sp,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp).plannerProbe("Steps/Weather"),
            )
            for (s in PlannerSteps.STEPS) StepLine(s, facts, cur == s.n, layout, sheet)
            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                StepLink("Full guide", "Steps/FullGuide") { if (sheet) PlannerSteps.sheet = false; PlannerGuide.open("start") }
                StepLink("Clear ticks", "Steps/ClearTicks") { PlannerSteps.clear() }
            }
        }
    }
}

@Composable
private fun StepLine(s: PlannerSteps.Step, f: PlannerSteps.Facts, current: Boolean, layout: ShellLayout, sheet: Boolean) {
    val finger = WdpTouch.device
    val done = PlannerSteps.done(s.n, f)
    val bms = s.where == PlannerSteps.Where.BMS
    // a Planner line does its action only where the toolbar has no room for it; elsewhere it lights the toolbar's button
    val shortcut = !bms && s.run != null && !PlannerSteps.onToolbar(s.toolbar, layout)
    val press: (() -> Unit)? = when {
        bms -> { { PlannerSteps.tick(s.n) } }
        shortcut -> { { if (sheet) PlannerSteps.sheet = false; s.run?.invoke() } }
        s.toolbar != null -> { { PlannerSteps.flashOn(s.toolbar) } }
        else -> null
    }
    val shape = RoundedCornerShape(6.dp)
    Row(
        Modifier.fillMaxWidth().padding(vertical = 1.dp).clip(shape)
            .background(if (current) Hud.Amber.copy(alpha = 0.14f) else androidx.compose.ui.graphics.Color.Transparent)
            .then(if (press != null) Modifier.clickable(remember { MutableInteractionSource() }, indication = null) { press() } else Modifier)
            .heightIn(min = if (finger) 40.dp else 0.dp)
            .padding(horizontal = 6.dp, vertical = if (finger) 5.dp else 3.dp)
            .plannerProbe("Steps/Step/${s.n}"),
        verticalAlignment = Alignment.Top,
    ) {
        // the number, or a tick once done
        Box(
            Modifier.padding(top = 1.dp).size(if (finger) 22.dp else 19.dp).clip(CircleShape)
                .background(when { current -> Hud.Amber; done -> Hud.Green.copy(alpha = 0.22f); else -> Hud.Surface3 }),
            contentAlignment = Alignment.Center,
        ) {
            if (done && !current) Icon(Icons.Default.Check, "done", tint = Hud.Green, modifier = Modifier.size(13.dp))
            else Text(s.n.toString(), color = if (current) Hud.Bg else Hud.TextDim, fontSize = if (finger) 11.5.sp else 10.5.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            // where the step is done ("IN BMS", "PLANNER") leads the line in small capitals, not a line of its own
            Text(
                androidx.compose.ui.text.buildAnnotatedString {
                    pushStyle(androidx.compose.ui.text.SpanStyle(
                        color = if (bms) Hud.Blue else Hud.Amber.copy(alpha = 0.85f), fontSize = if (finger) 10.sp else 9.sp,
                        fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp,
                    ))
                    append(s.where.label.uppercase())
                    pop()
                    append("  ")
                    append(s.text)
                },
                color = if (done && !current) Hud.TextDim else Hud.Text, fontSize = if (finger) 13.sp else 12.sp,
                lineHeight = if (finger) 17.sp else 15.sp, fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
            )
            // what the app can say beside a step: the edits waiting, when BMS printed (never "changed since": not wanted)
            val extra = when (s.n) {
                3 -> if (f.printedBriefing > 0) "Last PRINT: ${hhmm(f.printedBriefing)}" else null
                6 -> if (f.unsaved > 0) "${f.unsaved} not saved" else null
                else -> null
            }
            extra?.let { Text(it, color = Hud.Amber, fontSize = if (finger) 11.5.sp else 11.sp, maxLines = 1) }
            s.note?.let { Text(it, color = Hud.TextFaint, fontSize = if (finger) 11.5.sp else 11.sp, lineHeight = if (finger) 14.sp else 14.sp) }
        }
        when {
            bms -> Icon(
                if (s.n in f.ticks) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                if (s.n in f.ticks) "ticked" else "not ticked",
                tint = if (s.n in f.ticks) Hud.Green else Hud.TextFaint,
                modifier = Modifier.padding(start = 4.dp).size(if (finger) 22.dp else 18.dp).plannerProbe("Steps/Tick/${s.n}"),
            )
            shortcut -> Icon(Icons.Default.ChevronRight, "Do it", tint = Hud.Amber, modifier = Modifier.padding(start = 4.dp).size(18.dp))
        }
    }
}

@Composable
private fun StepLink(text: String, probe: String, onClick: () -> Unit) {
    Box(
        Modifier.then(if (WdpTouch.device) Modifier.heightIn(min = 44.dp) else Modifier).clip(RoundedCornerShape(4.dp)).clickable(onClick = onClick)
            .padding(horizontal = 3.dp, vertical = 2.dp).plannerProbe(probe),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = Hud.Amber, fontSize = if (WdpTouch.device) 13.sp else 11.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) }
}

/** The sheet over the Planner under its toolbar (a tablet held upright, a phone): the steps, and the page behind dimmed. */
@Composable
internal fun PlannerStepsSheet(facts: PlannerSteps.Facts, layout: ShellLayout, top: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier.fillMaxSize().padding(top = top).background(androidx.compose.ui.graphics.Color(0x66000000))
            .clickable(remember { MutableInteractionSource() }, indication = null) { PlannerSteps.sheet = false },
    ) {
        // the sheet itself takes its presses (a press on it is not a press outside)
        Box(
            Modifier.align(Alignment.TopEnd).fillMaxHeight().widthIn(max = 440.dp).fillMaxWidth()
                .clickable(remember { MutableInteractionSource() }, indication = null) {},
        ) {
            PlannerStepsView(facts, layout, sheet = true, modifier = Modifier.fillMaxSize())
        }
    }
}
