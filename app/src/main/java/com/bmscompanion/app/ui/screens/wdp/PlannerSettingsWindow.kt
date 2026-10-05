package com.bmscompanion.app.ui.screens.wdp

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bmscompanion.app.AppVersion
import com.bmscompanion.app.data.Platform
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.mission.CampRef
import com.bmscompanion.app.data.mission.MissionLink
import com.bmscompanion.app.ui.theme.Hud
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * **Planner settings**: WDP's Settings window (`fclsSettings`, Options → Settings), with what means something to this
 * Planner. Opened by the toolbar's Options → Settings… ([open]); every choice is taken at once and kept on this device, like the
 * Planner's other choices (the folders are the PC's, and say so).
 *
 * WDP's window, and where each of its settings went:
 * - **Falcon Location, Build, Tdf Location, DB Location** — shown: the BMS folder the PC reads, BMS's version, its
 *   theater list and the theater, and where the Planner's data comes from. The folder is chosen in Setup on the PC
 *   (the program that reads BMS), so **Change in Setup…** goes there where this is that PC; elsewhere a line says so.
 * - **Weather File (.twx) and (.fmap)** — shown: the files the DataCard's weather comes from now (Reload WX).
 * - **Datacard directory** — shown with the Planner's own folder ([dataCardsFolder]); its Browse is whatever sets
 *   that folder ([chooseDataCards], a hook the Planner's file part sets).
 * - **Show Tooltips** — [tooltips], every page and window here (WDP had them on the DTC page only), on by default.
 * - **Auto load last mission on startup** — [autoLoad]: the first time the Planner opens after the app starts, the
 *   flight picker opens on the save planned last with that flight and seat picked ([startup]), as WDP opened its last
 *   file and its flight selection. Off by default, as in WDP.
 * - **Reload WDP database** — **Reload from BMS** ([reload]): WDP read its own database folder again; here the data
 *   is the app's and BMS's, so it reads the open save's flight and the cartridge from BMS again.
 * - Left out, each for a reason the window gives at its foot: Screen size (the Planner fits any window), Use large
 *   resolution map (the maps are drawn at the screen's own resolution), Save main window location (the app keeps its
 *   own window), Reset Side (the Planner takes your side from the save and never asks), Saved Picture Size and its
 *   file naming (WDP saved pictures of the card beside a Backup DataCard for kneeboard tools; the Planner writes the
 *   cockpit's kneeboard itself with Upd Kneeboard).
 */
object PlannerSettings {
    private const val TOOLTIPS_KEY = "planner_tooltips"
    private const val AUTOLOAD_KEY = "planner_autoload"
    private const val LAST_KEY = "planner_last_flight"

    /** Show tooltips (on by default). */
    var tooltips: Boolean by mutableStateOf(runCatching { Repo.getInt(TOOLTIPS_KEY, 1) != 0 }.getOrDefault(true))
        private set

    /** Auto load last mission on startup (off by default, as WDP's). */
    var autoLoad: Boolean by mutableStateOf(runCatching { Repo.getInt(AUTOLOAD_KEY, 0) != 0 }.getOrDefault(false))
        private set

    fun chooseTooltips(on: Boolean) {
        tooltips = on
        if (!on) WdpTips.hide()
        runCatching { Repo.putInt(TOOLTIPS_KEY, if (on) 1 else 0) }
    }

    fun chooseAutoLoad(on: Boolean) {
        autoLoad = on
        runCatching { Repo.putInt(AUTOLOAD_KEY, if (on) 1 else 0) }
    }

    /** Opens the window (the toolbar's Options menu, the ⋮ menu's Options; anything else may call it). */
    fun open() = PlannerWindows.show(PlannerWindow.SETTINGS)

    /** Goes to Setup (set by the Planner's pane where the app can navigate; null elsewhere). */
    var onSetup: (() -> Unit)? = null

    /**
     * The PC's DataCards folder, as a path on that PC (null when the PC does not say). By default the PC's `@datacards`
     * place; the Planner's file part may replace it when it keeps the folder elsewhere.
     */
    var dataCardsFolder: suspend () -> String? = { runCatching { MissionLink.filesStat(WdpFiles.DATACARDS).value?.path }.getOrNull() }

    /**
     * WDP's Datacard directory Browse: chooses the folder the Planner keeps Backup DataCards, codewords and package
     * timing in, answering the new folder (null when nothing changed). **A hook**: null until the Planner's file part
     * sets it, and the window then has no Browse button.
     */
    var chooseDataCards: (suspend () -> String?)? = null

    // ---------------------------------------------------------------- the flight planned last

    /** The save and flight planned last on this device, and the seat. */
    class Last(val theater: String, val file: String, val flight: String, val seat: Int)

    val last: Last?
        get() = runCatching {
            val p = Repo.getString(LAST_KEY)?.split('\t').orEmpty()
            if (p.size < 3 || p[0].isBlank() || p[1].isBlank() || p[2].isBlank()) null
            else Last(p[0], p[1], p[2], p.getOrNull(3)?.toIntOrNull() ?: 0)
        }.getOrNull()

    /** Kept whenever a save's flight is planned ([PlannerMissionState.plan]). Names only, never a path. */
    fun rememberLast(ref: CampRef, seat: Int) {
        if (ref.theater.isBlank() || ref.file.isBlank() || ref.flight.isBlank()) return
        runCatching { Repo.putString(LAST_KEY, listOf(ref.theater, ref.file, ref.flight, seat.toString()).joinToString("\t")) }
    }

    private var started = false

    /**
     * Auto load last mission on startup, once per run of the app, the first time the Planner is shown: with the setting
     * on, no save's flight open, and no window up (the guide's first opening goes first), the flight picker opens on
     * the save planned last with its flight and seat picked. The pilot presses Plan this flight, which asks WDP's
     * Precision STPT question as a pick always does.
     */
    suspend fun startup() {
        if (started) return
        started = true
        if (!autoLoad || PlannerMissionState.fromSave) return
        val l = last ?: return
        delay(400)
        if (PlannerWindows.isOpen || PlannerMissionState.fromSave) return
        val r = CampRef(l.theater, l.file)
        FlightPicker.load(r)
        val rows = FlightPicker.ato?.packages?.flatMap { it.flights }.orEmpty()
        if (FlightPicker.ref == r && rows.any { it.id == l.flight }) {
            FlightPicker.selected = l.flight
            FlightPicker.seat = l.seat
            FlightPicker.loadFlight(l.flight)
        }
        if (!PlannerWindows.isOpen) FlightPicker.show(r)
    }

    /**
     * **Reload from BMS**: the open save's flight read from the save again (its route, targets and briefing, with the
     * seat and the Precision STPT answer kept), and the cartridge read again (which asks first over edits not saved).
     * What was done, in a sentence.
     */
    suspend fun reload(): String {
        val said = ArrayList<String>()
        val ref = PlannerMissionState.ref?.takeIf { PlannerMissionState.fromSave && PlannerMissionState.flight != null }
        if (ref != null) {
            val a = runCatching { MissionLink.campaignFlight(ref) }.getOrNull()
            val f = a?.value
            if (f != null) {
                PlannerMissionState.plan(
                    PlannerMissionState.theater ?: ref.theater, ref, f, PlannerMissionState.seat,
                    PlannerMissionState.file, PlannerMissionState.precision,
                )
                said += "${f.row.callsign} read again from ${ref.file}"
            } else said += "${ref.file} could not be read: ${a?.error ?: "the PC did not answer"}"
        }
        // Re-read DTC from BMS runs on its own and answers in its own message (or asks first over edits not saved)
        PlannerShell.reread()
        said += "your cartridge is being read again from BMS: its own message says how that went"
        return said.joinToString("; ").replaceFirstChar { it.uppercase() } + "."
    }
}

/** Starts what the Planner does by itself when it first shows ([PlannerSettings.startup]). */
@Composable
internal fun PlannerStartup() {
    LaunchedEffect(Unit) { PlannerSettings.startup() }
}

/** The window's content ([PlannerWindowHost]). */
@Composable
internal fun ColumnScope.PlannerSettingsWindow(onClose: () -> Unit) {
    val info by MissionLink.info.collectAsState()
    val bms = info?.bms
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var reloadNote by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var dataCards by remember { mutableStateOf<String?>(null) }
    var planner by remember { mutableStateOf<String?>(null) }
    // whether the DataCards folder is one the pilot chose (then Default puts back <Planner folder>\DataCards)
    var dataCardsChosen by remember { mutableStateOf(false) }
    LaunchedEffect(refresh, info?.bms?.baseDir) {
        dataCards = PlannerSettings.dataCardsFolder()
        planner = runCatching { MissionLink.filesStat("@planner").value?.path }.getOrNull()
        dataCardsChosen = runCatching { MissionLink.filesPlanner().value?.dataCardsSet == true }.getOrDefault(false)
    }
    // the PC window in This PC mode: the BMS folder is its own Setup's to change
    val local = plannerIsLocal(MissionLink.host) && Platform.installer != null

    Column(
        Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Heading("Falcon BMS")
        val base = bms?.baseDir?.trim()?.trimEnd('\\', '/')
        Setting("Falcon BMS folder", base ?: "Not found on the PC", "Settings/BmsFolder", mono = base != null) {
            val go = PlannerSettings.onSetup
            if (local && go != null) PlannerButton("Change in Setup…", probe = "Settings/ChangeBms", small = true) { onClose(); go() }
        }
        if (!local) Note("The Planner uses the Falcon BMS folder of the PC it is linked to. Change it there: BMS Companion → Setup → Falcon BMS folder.")
        Setting("Build", listOfNotNull(bms?.version, bms?.registryVersion?.takeIf { it != bms?.version }?.let { "registry $it" }).joinToString(" · ").ifEmpty { "Not known" }, "Settings/Build")
        Setting(
            "Theater list", if (base == null) "Not found" else base + "\\Data\\TerrData\\TheaterDefinition\\theater.lst", "Settings/Theaters", mono = base != null,
        )
        val planned = PlannerMissionState.theater?.takeIf { PlannerMissionState.fromSave }
        Setting(
            "Theater", listOfNotNull(bms?.theater?.trim()?.let { "BMS is on $it" }, planned?.let { "planning in $it" }).joinToString(" · ").ifEmpty { "Not known" },
            "Settings/Theater",
        )
        Setting("Planner data", "BMS Companion ${AppVersion.NAME}, made from Falcon BMS ${AppVersion.BMS}", "Settings/Data")

        Heading("Weather")
        val wx = WdpSession.dataCard.weatherSource
        val twx = wx?.substringBefore(" (map ")?.takeIf { it.endsWith(".twx", true) }
        val fmap = wx?.let { w -> if (w.contains(" (map ")) w.substringAfter(" (map ").substringBefore(")") else w.takeIf { it.endsWith(".fmap", true) } }
        Setting("Weather file (.twx)", twx ?: "None: the briefing's own weather", "Settings/Twx", mono = twx != null)
        Setting("Weather map (.fmap)", fmap ?: "None", "Settings/Fmap", mono = fmap != null)
        Note("Reload WX on the DataCard picks the file the card's weather comes from.")

        Heading("Planner folders")
        Setting("Planner folder", planner ?: "Not known", "Settings/PlannerFolder", mono = planner != null)
        Setting("DataCards folder", dataCards ?: "Not known", "Settings/DataCards", mono = dataCards != null) {
            val choose = PlannerSettings.chooseDataCards
            if (choose != null) PlannerButton("Browse…", probe = "Settings/BrowseDataCards", small = true) {
                scope.launch { choose(); refresh++ }
            }
            // back to <Planner folder>\DataCards (POST /api/files/planner with a blank folder)
            if (choose != null && dataCardsChosen) PlannerButton("Default", probe = "Settings/DefaultDataCards", small = true) {
                scope.launch {
                    val a = runCatching { MissionLink.filesSetDataCards(null) }.getOrNull()
                    if (a?.value == null || a.error != null) WdpDialogs.message("Datacard directory", "The DataCards folder was not changed: ${a?.error ?: "the PC could not be reached"}")
                    refresh++
                }
            }
        }

        Heading("Planner")
        Toggle(
            "Show tooltips", "Explain a control when the mouse rests on it, or on a long press by finger.",
            PlannerSettings.tooltips, "Settings/Tooltips",
        ) { PlannerSettings.chooseTooltips(it) }
        val last = PlannerSettings.last
        Toggle(
            "Auto load last mission on startup",
            "When the Planner first opens, open the save you planned last with its flight picked" +
                (last?.let { " (now: ${it.file}, ${it.theater})" } ?: " (none planned yet)") + ".",
            PlannerSettings.autoLoad, "Settings/AutoLoad",
        ) { PlannerSettings.chooseAutoLoad(it) }

        Heading("Reload")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PlannerButton(if (busy) "Reading…" else "Reload from BMS", probe = "Settings/Reload", enabled = !busy) {
                busy = true
                scope.launch {
                    reloadNote = runCatching { PlannerSettings.reload() }.getOrElse { "Not done: ${it.message}" }
                    busy = false
                    refresh++
                }
            }
            Text(
                reloadNote ?: "Reads the open save's flight and your cartridge from Falcon BMS again.",
                color = if (reloadNote != null) Hud.Text else Hud.TextDim, fontSize = fingerSp(12f), lineHeight = 16.sp,
                modifier = Modifier.weight(1f),
            )
        }

        Heading("Not needed here")
        Note(
            "WDP's Screen size, Use large resolution map, Save main window location, Reset Side and Saved Picture Size are " +
                "not in this Planner: it fits any window, draws its maps at the screen's own resolution, the app keeps its own " +
                "window, your side comes from the save, and Upd Kneeboard writes the cockpit's kneeboard itself.",
        )
    }
    // (no Close button: the × in the title bar closes it, and every change is already kept)
}

/** A section's title. */
@Composable
private fun Heading(text: String) {
    Text(
        text.uppercase(), color = Hud.TextFaint, fontSize = fingerSp(10f), fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 6.dp, start = 2.dp),
    )
}

/** A line of small print under a setting. */
@Composable
private fun Note(text: String) {
    Text(text, color = Hud.TextFaint, fontSize = fingerSp(11f), lineHeight = 14.sp, modifier = Modifier.padding(start = 2.dp))
}

/** The rule under a row of the window: rows sit flat on its ground, not on cards. */
private fun Modifier.ruled(): Modifier = drawBehind {
    drawLine(Hud.Outline.copy(alpha = 0.45f), androidx.compose.ui.geometry.Offset(0f, size.height - 0.5f), androidx.compose.ui.geometry.Offset(size.width, size.height - 0.5f), 1f)
}

/** One setting: its name, its value (a path in type that reads as one) and what can be done about it. */
@Composable
private fun Setting(name: String, value: String, probe: String, mono: Boolean = false, action: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().fingerHeight(40.dp).ruled()
            .padding(horizontal = 4.dp, vertical = 4.dp).plannerProbe(probe),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(name, color = Hud.TextDim, fontSize = fingerSp(12f), maxLines = 2, modifier = Modifier.widthIn(min = 120.dp, max = 180.dp))
        Text(
            value, color = Hud.Text, fontSize = fingerSp(if (mono) 11f else 12f), fontFamily = if (mono) FontFamily.Monospace else null,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).wdpTip("planner/$probe/Value", value, touch = true),
        )
        action?.invoke()
    }
}

/** A setting that is on or off: a switch drawn as the Planner's other windows draw theirs, with a line on what it does. */
@Composable
private fun Toggle(name: String, what: String, on: Boolean, probe: String, set: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().fingerHeight(44.dp).ruled()
            .plannerPress { set(!on) }.padding(horizontal = 4.dp, vertical = 5.dp).plannerProbe(probe),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(name, color = Hud.Text, fontSize = fingerSp(12.5f), fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(what, color = Hud.TextDim, fontSize = fingerSp(11f), lineHeight = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        // the switch
        Box(
            Modifier.size(width = 38.dp, height = 22.dp).clip(RoundedCornerShape(11.dp))
                .background(if (on) Hud.Amber else Hud.Surface3).border(1.dp, if (on) Hud.Amber else Hud.Outline, RoundedCornerShape(11.dp))
                .padding(3.dp),
            contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(Modifier.size(16.dp).clip(RoundedCornerShape(8.dp)).background(if (on) Hud.Bg else Hud.TextDim))
        }
        Spacer(Modifier.width(2.dp))
    }
}
