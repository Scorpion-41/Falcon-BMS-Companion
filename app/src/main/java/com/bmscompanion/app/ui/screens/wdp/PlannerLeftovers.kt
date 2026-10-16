package com.bmscompanion.app.ui.screens.wdp

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.bmscompanion.app.data.mission.CartridgeState
import com.bmscompanion.app.data.mission.LedgerMission
import com.bmscompanion.app.data.mission.MissionLink
import com.bmscompanion.app.data.mission.PlannerPcSettings
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
     * earlier flight; the DTC page then reads the cartridge again (asking first over edits not saved). [clean]: another
     * flight than the one planned before — with **Start each opened mission with clean lines, PPTs and Open 1/2 steerpoints** on, the PC
     * cleans the cartridge and the campaign's mission file of lines and PPTs (1.3.9, in either mode), and the DTC page
     * reads it again the same way; the maps go on suggesting the mission's threats and tracks. Since 1.4.1 the PC
     * cleans only when the pilot chose so: while the choice is not made the pilot is asked first ([choose]).
     */
    fun planned(clean: Boolean = false) {
        openedAt = java.util.Date().time
        val now = openFlight() ?: return
        val campaign = PlannerMissionState.kind == com.bmscompanion.app.data.mission.CampKind.CAMPAIGN
        scope.launch {
            // the first time it would clean, the pilot is asked: Clear them / Keep them (1.4.1, choose)
            val go = clean && runCatching { choose(campaign) }.getOrDefault(true)
            val a = runCatching { MissionLink.missionOpened(now, go) }.getOrNull()
            if (a?.value?.reset?.let { it.kind == com.bmscompanion.app.data.mission.SwitchReset.MISSION && it.keys.isNotEmpty() && it.now?.sameFlight(now) == true } == true) {
                runCatching { WdpCartridge.reload?.invoke() }
            }
            runCatching { saw(MissionLink.cartridge(cartridge?.callsign)) }
        }
    }

    // ------------------------------------------------- the opened mission's leftovers: asked once (1.4.1)

    /** The question's title, and its two answers (quoted by the Guide; `GuideOutcome` NAMES). */
    const val ASK_TITLE = "Lines and PPTs from earlier missions"
    const val CLEAR = "Clear them"
    const val KEEP = "Keep them"

    /**
     * Headless checks: the answer taken instead of the question (`SelfTest` sets [PlannerPcSettings.CLEAN] for every
     * developer check, so none stops on it; a check of the question sets it as it needs). Null = ask.
     */
    var testAnswer: String? = null

    /** How many times the question was due (asked, or answered by [testAnswer]): for the checks. */
    var questionsDue = 0
        private set

    /** The question on screen now, answered by everyone waiting on it. */
    private var asking: kotlinx.coroutines.CompletableDeferred<String?>? = null

    /**
     * Whether Open mission… / Pick a flight of another flight sends `clean=1`: the PC cleans only when its choice is
     * `clean` (`BridgeSettings.OpenedLeftovers`), so the device's part is to settle a choice not made yet. In a
     * campaign ([campaign]; a TE or a training is never cleaned) with the PC saying [PlannerPcSettings.ASK], the pilot is
     * asked ([ask]); the answer is written to the PC, where it is the setting for every device, and this open goes
     * that way. Closed with × or Escape: nothing is cleaned this time and the question comes again next time. An
     * older PC (no `openedLeftovers`) or none reached: as before, the PC decides.
     */
    internal suspend fun choose(campaign: Boolean): Boolean {
        if (!campaign) return true
        val s = MissionLink.plannerSettings().value ?: return true
        if (s.openedLeftovers != PlannerPcSettings.ASK) return true
        questionsDue++
        val choice = testAnswer ?: ask() ?: return false
        val set = runCatching { MissionLink.setOpenedLeftovers(choice) }.getOrNull()?.value
        set?.let { PlannerSettings.took(it) }
        return choice == PlannerPcSettings.CLEAN
    }

    /** Puts the question up in the Planner's message-box look and waits for the answer: [PlannerPcSettings.CLEAN], [PlannerPcSettings.KEEP], or null for a close. */
    private suspend fun ask(): String? {
        asking?.let { return it.await() }
        val d = kotlinx.coroutines.CompletableDeferred<String?>()
        asking = d
        WdpDialogs.stack += questionBox { d.complete(it) }
        return try { d.await() } finally { asking = null }
    }

    /** The question as the Planner's message box: [done] hears [PlannerPcSettings.CLEAN], [PlannerPcSettings.KEEP], or null for × / Escape. */
    internal fun questionBox(done: (String?) -> Unit): WdpMessage = WdpMessage(
        ASK_TITLE, question(), listOf(CLEAR, KEEP), default = 1,
        onAnswer = { a -> done(if (a == CLEAR) PlannerPcSettings.CLEAN else PlannerPcSettings.KEEP) },
        onClose = { done(null) },
    )

    /** The question's words: what goes, what never does, why, and where the choice can be changed later. */
    fun question(): String {
        val cs = cartridge?.callsign?.trim()?.takeIf { it.isNotEmpty() }
        val save = PlannerMissionState.ref?.file?.substringBeforeLast('.')?.trim()?.takeIf { it.isNotEmpty() }
        return "This campaign mission's DTC may still hold HSD lines, PPTs (the preplanned threat steerpoints) and " +
            "Open 1/Open 2 steerpoints (STPT 81-99) from earlier missions: BMS's DTC LOAD brings them back into each new one.\n\n" +
            "$CLEAR: they are cleared from your callsign file (${cs?.let { "$it.ini" } ?: "User\\Config\\<callsign>.ini"}) and from " +
            "the campaign's mission file (${save?.let { "$it.ini" } ?: "<save>.ini"}), whoever made them, each time another mission is opened. " +
            "Never cleared: the route, Recon and precision targets, weapon targets, what you saved in the Planner for this flight, " +
            "and anything of a TE or a training.\n\n" +
            "$KEEP: they are left as they are, now and at every new mission, until you clear them yourself.\n\n" +
            "Your choice becomes the default. You can change it later in Planner → Options → Settings…"
    }
}
