package com.bmscompanion.app.ui.screens.wdp

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.bmscompanion.app.data.mission.CartridgeState
import com.bmscompanion.app.data.mission.LedgerMission
import com.bmscompanion.app.data.mission.MissionLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Which flight the Planner works on, for the PC's ledger and its new-mission reset (docs/DATA-STORES.md, "Starting the
 * next mission").
 *
 * The PC keeps a ledger of every key Save to DTC wrote and for which mission ([com.bmscompanion.app.data.mission.CartridgeLedger]);
 * every save says which mission it is for ([missionNow]: a save's flight, else the printed briefing's). Since 1.3.8
 * nothing is asked and nothing is drawn as "left from …": **Open mission… / Pick a flight** tells the PC which flight
 * was opened ([planned] → `POST /api/mission/opened`), and in WDP mode another flight is a new mission, for which the
 * PC clears by itself what the Planner saved for an earlier flight (every device shows a short note with Undo).
 */
object PlannerLeftovers {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** The cartridge as the PC last answered it (its text and ledger), whichever call it came with. */
    var cartridge: CartridgeState? by mutableStateOf(null)
    /** when the flight open now was opened (Open mission… / Pick a flight), for the ledger */
    private var openedAt = 0L

    /** Takes [st] as what the cartridge now holds, when it is one; returns it as it was. */
    fun saw(st: CartridgeState?): CartridgeState? {
        if (st != null && st.available && st.text != null) cartridge = st
        return st
    }

    /** The mission the Planner works on now: the save's flight it has open, else the printed briefing's flight. */
    fun missionNow(): LedgerMission? {
        val f = PlannerMissionState.flight
        if (PlannerMissionState.fromSave && f != null) {
            return LedgerMission.ofFlight(PlannerMissionState.theater, PlannerMissionState.ref?.file, f, PlannerMissionState.seat, openedAt)
        }
        val d = MissionLink.bmsFiles.value
        return LedgerMission.ofBriefing(d?.briefing, d?.briefingModified ?: 0L, MissionLink.info.value?.bms?.theater)
    }

    /**
     * The save's flight the Planner has open on this device, or null (no save open): what a switch of mode sends as
     * the current flight (`for=`), so the PC never resets that flight's own things.
     */
    fun openFlight(): LedgerMission? {
        val f = PlannerMissionState.flight ?: return null
        if (!PlannerMissionState.fromSave) return null
        return LedgerMission.ofFlight(PlannerMissionState.theater, PlannerMissionState.ref?.file, f, PlannerMissionState.seat, openedAt)
    }

    /**
     * After Open mission… or Pick a flight ([PlannerMissionState.plan]): the PC is told which flight was opened. In WDP
     * mode another flight than the last one is a new mission, and the PC clears by itself what the Planner saved for an
     * earlier flight; the DTC page then reads the cartridge again (asking first over edits not saved).
     */
    fun planned() {
        openedAt = java.util.Date().time
        val now = openFlight() ?: return
        scope.launch {
            val a = runCatching { MissionLink.missionOpened(now) }.getOrNull()
            if (a?.value?.reset?.let { it.kind == com.bmscompanion.app.data.mission.SwitchReset.MISSION && it.keys.isNotEmpty() && it.now?.sameFlight(now) == true } == true) {
                runCatching { WdpCartridge.reload?.invoke() }
            }
            runCatching { saw(MissionLink.cartridge(cartridge?.callsign)) }
        }
    }
}
