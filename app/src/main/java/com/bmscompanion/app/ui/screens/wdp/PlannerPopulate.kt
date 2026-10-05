package com.bmscompanion.app.ui.screens.wdp

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.bmscompanion.app.data.mission.CampRef
import com.bmscompanion.app.data.mission.MissionLink
import com.bmscompanion.app.data.mission.MissionMode
import com.bmscompanion.app.data.mission.MissionSourceInfo
import com.bmscompanion.app.data.mission.PcAnswer
import com.bmscompanion.app.data.mission.PopulateSend
import com.bmscompanion.app.data.mission.Populated
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * **Populate from Planner**: how the Mission section is filled in WDP mode, and only when the pilot presses it.
 *
 * It sends the PC which flight the Planner has open (the save, the flight and the seat), the attack an attack page
 * worked out and whose cartridge it is; the PC reads the save, the cartridge **as saved**, the save's tanker and
 * AWACS tracks and the save's own weather file (`<save>.twx`, as the card reads it — not a file Reload WX picked, nor
 * the printed briefing's forecast), and keeps that snapshot until the next press (`POST /api/mission/populate`,
 * docs/PROTOCOL.md "Mission source"). Every Mission view on every device then shows it.
 *
 * One object, so the four places that offer the button — the Planner's toolbar, the Kneeboards page beside the greyed
 * GENERATE NOW, the Mission section's empty state and the "changed since" hint — take the same flight, ask the same
 * question over edits not saved yet, and show the same answer.
 */
object PlannerPopulate {
    /** A press is on its way to the PC. */
    var busy: Boolean by mutableStateOf(false)
        private set

    /** What the last press answered, for whichever button is on screen to show; null before any. */
    var result: Result? by mutableStateOf(null)
        internal set

    /**
     * Where a Planner-side press shows the Mission section afterwards ("Show the Map"): set by the Planner's pane,
     * which can reach the Mission tabs. Null leaves the answer without that button.
     */
    var onShowMission: (() -> Unit)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * What a press would take. [again] is true when the Planner on this device has no save's flight open and the PC's
     * snapshot names one: the press then takes that flight again, with the cartridge as it is saved now (the snapshot's
     * attack is laid out again from the cartridge). [label] names it for a button: "Cyborg6 · Lead · Auto Save.cam".
     */
    data class Target(val ref: CampRef, val seat: Int, val callsign: String?, val again: Boolean) {
        val label: String get() = listOfNotNull(callsign?.takeIf { it.isNotBlank() }, SEAT_NAMES.getOrElse(seat) { "Lead" }, ref.file.takeIf { it.isNotBlank() }).joinToString(" · ")
    }

    /**
     * What a press answered: [ok] with the sentence to show, or the PC's own refusal. [before] is the snapshot's time
     * (`Populated.at`) as the answer left it, so a view can drop a refusal once anything has been populated since.
     */
    data class Result(val ok: Boolean, val text: String, val seq: Long, val before: Long = 0)

    /** The flight open in the Planner on this device, as a press would send it; null while it plans the printed briefing. */
    fun openFlight(): Target? {
        val s = PlannerMissionState
        val ref = s.ref ?: return null
        val f = s.flight ?: return null
        if (!s.fromSave) return null
        return Target(ref, s.seat.coerceIn(0, 3), f.row.callsign, again = false)
    }

    /** The flight the PC's snapshot was made of, for a press that takes it again; null before the first Populate. */
    fun snapshotFlight(info: MissionSourceInfo?): Target? {
        val p: Populated = info?.populated ?: return null
        if (p.save.isBlank() || p.flight.isBlank()) return null
        return Target(p.ref, p.seat.coerceIn(0, 3), p.callsign, again = true)
    }

    /** [openFlight], else [snapshotFlight], else null (then the button opens the Planner at Open mission…). */
    fun target(info: MissionSourceInfo? = MissionLink.info.value?.mission): Target? = openFlight() ?: snapshotFlight(info)

    /**
     * The edits in the Planner that are not saved to the DTC yet: the DTC page's changed settings and the card's
     * entries, as the toolbar's Save to DTC counts them. 0 where the Planner has not been opened on this device.
     */
    fun unsaved(): Int = if (WdpSession.started) runCatching { PlannerShell.unsaved() }.getOrDefault(0) else 0

    /** The question a press asks over [n] unsaved edits, and its three answers. */
    fun unsavedQuestion(n: Int): String =
        (if (n == 1) "1 edit in the Planner is" else "$n edits in the Planner are") + " not saved to the DTC. Populate from " +
            "Planner takes your cartridge as it is saved on the PC, so the Mission section would not show " + (if (n == 1) "it" else "them") + "."
    const val SAVE_AND_POPULATE = "Save to DTC and populate"
    const val WITHOUT = "Populate without them"
    const val CANCEL = "Cancel"

    /** Sends [t]; the answer is also left in [result]. Never throws. */
    suspend fun populate(t: Target): Result {
        if (busy) return Result(false, "Populate from Planner is already on its way to the PC.", now(), snapshotAt())
        busy = true
        try {
            // the attack and the cartridge's owner are this device's Planner's, when it is the flight it has open
            val mine = !t.again && WdpSession.started
            val attack = if (mine) runCatching { WdpAttackOverlay.toSend() }.getOrNull() else null
            val callsign = if (WdpSession.started) runCatching { WdpSession.dtc.callsign }.getOrNull()?.takeIf { it.isNotBlank() } else null
            val a: PcAnswer<MissionSourceInfo> = runCatching {
                MissionLink.populateFromPlanner(PopulateSend(ref = t.ref, seat = t.seat, attack = attack, callsign = callsign))
            }.getOrElse { PcAnswer.unreachable("Could not reach the PC: ${it.message ?: it::class.simpleName}") }
            val p = a.value?.populated
            val said = a.error
            val r = when {
                said != null -> Result(false, said, now(), snapshotAt())
                p == null -> Result(false, "The PC did not say what it populated. Try again.", now(), snapshotAt())
                else -> Result(true, doneText(p), now(), p.at)
            }
            result = r
            return r
        } finally {
            busy = false
        }
    }

    /** [populate] from a button: the answer lands in [result]. */
    fun launch(t: Target, then: ((Result) -> Unit)? = null) {
        scope.launch { val r = populate(t); then?.invoke(r) }
    }

    /**
     * "Save to DTC and populate": the Planner's own Save to DTC (with the question it asks on the way when a save
     * would zero placed points), and once it has written the cartridge, the press. A save
     * that fails, or a question answered Cancel, populates nothing. The Planner must be on screen: its questions and
     * its answer are drawn there.
     */
    fun saveThenPopulate(t: Target, then: ((Result) -> Unit)? = null) {
        WdpSession.dtc.afterSave = { launch(t, then) }
        PlannerShell.save()
    }

    /** The sentence after a Populate: which flight the Mission section now shows, from which cartridge, and the PC's notes. */
    fun doneText(p: Populated): String {
        val seat = SEAT_NAMES.getOrElse(p.seat) { "Lead" }
        val who = p.callsign?.takeIf { it.isNotBlank() }?.let { "$it ($seat)" } ?: "the $seat's flight"
        val cart = p.cartridge?.let { "your cartridge as saved ($it)" } ?: "no saved cartridge"
        return "The Mission section now shows $who from ${p.save.ifBlank { "the save" }}, with $cart" +
            (if (p.briefingFrom == "printed") ", BMS's printed briefing" else "") +
            (p.weatherFile?.let { ", the weather saved in $it" } ?: "") +
            (if (p.attack) " and the attack" else "") + ", on every device." +
            (if (p.notes.isNotEmpty()) "\n\n" + p.notes.joinToString("\n") else "")
    }

    /**
     * [result] while it still says how things stand: nothing has been populated since it (on this device or another),
     * so "the Mission section now shows …" or the refusal is still true.
     */
    fun current(info: MissionSourceInfo?): Result? = result?.takeIf { it.before == (info?.populated?.at ?: 0L) }

    /** One line for a button's foot: "Populated 22:51" or the refusal. */
    fun resultLine(r: Result): String = r.text.substringBefore("\n\n")

    /** Whether the Mission section is in WDP mode, as the PC last said. */
    val wdp: Boolean get() = MissionLink.info.value?.mission?.mode == MissionMode.WDP

    private fun snapshotAt(): Long = MissionLink.info.value?.mission?.populated?.at ?: 0L

    private var presses = 0L
    /** a number that grows with every answer, so a view can tell a new one from the one it showed (no clock needed) */
    private fun now(): Long = ++presses
}
