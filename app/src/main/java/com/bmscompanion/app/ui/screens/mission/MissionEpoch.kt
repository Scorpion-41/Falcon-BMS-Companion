package com.bmscompanion.app.ui.screens.mission

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.mission.BridgeInfo
import com.bmscompanion.app.data.mission.LedgerMission
import com.bmscompanion.app.data.mission.MissionData
import com.bmscompanion.app.data.mission.MissionLink
import com.bmscompanion.app.data.mission.MissionMode
import com.bmscompanion.app.ui.screens.wdp.PlannerLeftovers
import com.bmscompanion.app.ui.screens.wdp.WdpMapView
import com.bmscompanion.app.ui.screens.wdp.WdpSession
import kotlinx.serialization.Serializable

/**
 * What this device starts afresh at a **new mission** or a **switch of mode** (docs/DATA-STORES.md, "Starting the next
 * mission"), so nothing of the last mission lingers on screen or in the attack pages: the PC clears the cartridge, and
 * each device clears its own state of that mission.
 *
 * The mission is the mode and the flight: in EZBoards mode the printed briefing's, in WDP mode the flight the Planner
 * here has open, else the populated one. It is kept in the device's preferences (`mission_epoch`), so a mission that
 * changed while the app was closed is a new one when it starts. A flight not yet known (nothing printed, nothing open)
 * changes nothing; the same flight printed again is the same mission.
 *
 * Reset ([reset]): the maps' view of the mission — the Mission map's selection and AWACS tools, its framing (it flies to
 * the new route), the Planner Map page's selection, move, measure, HSD centre, package flights, Viewing and cached
 * picture ([WdpMapView.resetForMission]) — and the attack: Pop-up, HADB and TOSS back to WDP's defaults with their
 * typed ELEVs and target ground gone and TGT STPT on the new mission's first strike steerpoint, the DataCard's delivery
 * block back to "None" with no profile chosen, the attack page last worked on forgotten, and the DTC page's delivery
 * data (`[NAV OFFSETS]`: Modesel, VIP/VRP, aim points, and the attack pages' hand-over) dropped, as the PC clears it
 * from the cartridge ([WdpSession.resetAttack], `DtcWiring.clearDeliveryForMission`).
 * Not reset: what is the pilot's and not the mission's — which layers are on, the map style, units, Mil/Civ, metric.
 */
object MissionEpoch {
    /** How many new missions this run has seen: what the maps' per-mission state is keyed on. */
    var n by mutableIntStateOf(0)
        private set

    private const val KEY = "mission_epoch"

    @Serializable
    private data class Seen(val mode: String = MissionMode.EZBOARDS, val flight: LedgerMission = LedgerMission())

    /** The mission as this device sees it now, or null before the PC has answered. */
    private fun now(info: BridgeInfo?, mission: MissionData?): Seen? {
        val src = info?.mission ?: return null
        val theater = info.bms.theater
        val flight = if (src.wdp) {
            runCatching { PlannerLeftovers.openFlight() }.getOrNull()
                ?: LedgerMission.ofPopulated(src.populated)
        } else {
            LedgerMission.ofBriefing(mission?.takeIf { it.mode != MissionMode.WDP }?.briefing, mission?.briefingModified ?: 0L, theater)
        }
        return Seen(src.mode, flight ?: LedgerMission())
    }

    private fun load(): Seen? = runCatching { Repo.getString(KEY)?.let { Repo.json.decodeFromString(Seen.serializer(), it) } }.getOrNull()
    private fun store(s: Seen) { runCatching { Repo.putString(KEY, Repo.json.encodeToString(Seen.serializer(), s)) } }

    /**
     * The mission last seen (mode and flight, as kept in `mission_epoch`) as text, for state a device keeps in its
     * preferences with the mission it belongs to (AttackFocus); null before this device has seen one.
     */
    internal fun seenText(): String? = load()?.let { runCatching { Repo.json.encodeToString(Seen.serializer(), it) }.getOrNull() }

    /** Whether [text] (an earlier [seenText]) is the mission last seen: the same mode and the same flight. */
    internal fun isSeen(text: String?): Boolean {
        val a = text?.let { runCatching { Repo.json.decodeFromString(Seen.serializer(), it) }.getOrNull() } ?: return false
        val b = load() ?: return false
        return a.mode == b.mode && a.flight.sameFlight(b.flight)
    }

    /**
     * Looks at the mission now ([info], [mission]); when it is another mode or another flight than the last one seen,
     * [reset]. A flight that becomes known where none was is taken quietly.
     */
    fun observe(info: BridgeInfo?, mission: MissionData?) {
        val cur = now(info, mission) ?: return
        val prev = load()
        if (prev == null) { store(cur); return }
        val changed = prev.mode != cur.mode || !prev.flight.sameFlight(cur.flight)
        if (changed) {
            store(cur)
            reset()
            // into EZBoards mode the Planner is greyed out: the Mission section opens on the Briefing instead
            if (prev.mode != cur.mode && cur.mode == MissionMode.EZBOARDS) MissionTabRequest.open(MissionTab.BRIEF)
        } else if (prev.flight.callsign.isBlank() && cur.flight.callsign.isNotBlank()) store(cur)
    }

    /** Starts this device's view of the mission afresh (see the class). Never throws. */
    fun reset() {
        n++
        runCatching { WdpMapView.resetForMission() }
        runCatching { WdpSession.resetAttack() }
    }

    // ---- the maps' framing: a map flies to the new mission's route once after a reset

    private val framed = HashMap<Any, Int>()

    /** [state] was framed on the mission of [n] already (before the first reset: whether it was ever placed). */
    fun framed(state: Any, initialized: Boolean): Boolean = (framed[state] ?: 0) >= n && (n > 0 || initialized)

    fun markFramed(state: Any) { framed[state] = n }
}

/**
 * A tab the Mission section should show next time it is drawn ([MissionTabContent] takes it): the Briefing after a
 * switch to EZBoards mode, whether the switch was made here, on another device, or while the section was not open.
 */
object MissionTabRequest {
    var pending: MissionTab? by mutableStateOf(null)
        private set

    fun open(tab: MissionTab) {
        pending = tab
        // the section may not be on screen: it then opens on this tab from the preferences
        runCatching { saveMissionTab(tab) }
    }

    /** The pending tab, once; null when there is none. */
    fun take(): MissionTab? = pending.also { pending = null }
}

/** Watches the mission for [MissionEpoch], wherever the app is drawn (AppRoot). */
@Composable
fun MissionEpochWatcher() {
    val info by MissionLink.info.collectAsState()
    val mission by MissionLink.mission.collectAsState()
    val planner = runCatching { PlannerLeftovers.openFlight() }.getOrNull()
    LaunchedEffect(info?.mission?.mode, info?.mission?.populated?.at, mission?.briefingModified, mission?.mode, planner) {
        MissionEpoch.observe(info, mission)
    }
}
