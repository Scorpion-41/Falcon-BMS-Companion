package com.bmscompanion.app.ui.screens.wdp

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import com.bmscompanion.app.data.mission.CampFile
import com.bmscompanion.app.data.mission.CampFlight
import com.bmscompanion.app.data.mission.CampKind
import com.bmscompanion.app.data.mission.CampRef
import com.bmscompanion.app.data.mission.MissionData
import com.bmscompanion.app.data.mission.MissionLink

/**
 * What the Planner is planning from (R3-PLAN A1): the printed briefing and the cartridge, as by default, or one flight
 * of a save opened with **Open mission…** and picked in the flight picker. Only one source is active at a time; the
 * identity strip names it and **Back to BMS briefing** ([backToBriefing]) returns to the default. Compose state, so
 * every page follows a change.
 *
 * The pages read it through `plannerMission` (WdpScreen.kt): with a save's flight they are given the [Briefing] the PC
 * made of it ([CampFlight.briefing]), its waypoints ([CampFlight.route]) and the [seat], and the Planner works in the
 * save's own theater ([theater]), which may not be the one Falcon BMS is set to. Whether the pilot's cartridge places
 * the steerpoints is WDP's own question, asked as the flight is planned ([precision], [asksPrecision]). Kept for as
 * long as the app runs, like the pages ([WdpSession]); a lost link to the PC does not clear it.
 */
object PlannerMissionState {
    /** [BRIEFING] (the default) or [SAVE] */
    var source: String by mutableStateOf(BRIEFING)
    /**
     * The save's theater as its definition names it ("Korea KTO", "Hellas WCP"; the app's theater of the same name);
     * null = the one BMS is set to, i.e. the printed briefing's.
     */
    var theater: String? by mutableStateOf(null)
    /** the save and the flight in it, as the PC names them: what Populate from Planner and a TE's Save to DTC send */
    var ref: CampRef? by mutableStateOf(null)
    /** the save's kind: `CampKind.CAMPAIGN`, `TE` or `TRAINING` */
    var kind: String? by mutableStateOf(null)
    /**
     * The seat in the flight, 0-3 (lead, wing, element lead, element wing): whose designated targets the DataCard names.
     * The flight picker sets it; a check may set it directly.
     */
    var seat: Int by mutableIntStateOf(0)
    /** the flight as the PC read it from the save, with its route and a Briefing made from it */
    var flight: CampFlight? by mutableStateOf(null)
    /**
     * The pilot's answer to WDP's question "Did you save Precision STPT in the DTC for THIS flight in BMS?", asked when
     * a save's flight is planned ([FlightPicker.plan]; [asksPrecision] says when). True (Yes): each steerpoint at the
     * cartridge's position, and at the mission file's where the cartridge's slot is empty. False (No, the default
     * answer, and the answer when nothing is asked): every steerpoint at the mission file's position, none of the
     * cartridge's. Null: not asked (the printed briefing, or a check that sets this state itself). The pages get it as
     * [WdpMission.precision].
     */
    var precision: Boolean? by mutableStateOf(null)

    /** the save as the Open mission list described it (its times, campaign clock and name), when it was opened from there */
    var file: CampFile? by mutableStateOf(null)
    /**
     * The sentences the PC gave about the save and this flight (R3-CAM §4.5): another theater, older than the printed
     * briefing, another flight than the briefed one, already flown, a mission file for another flight, more waypoints
     * than the cartridge holds. The identity strip shows them as notice chips.
     */
    var notes: List<String> by mutableStateOf(emptyList())
    /** Counts every change of source, flight or seat made here, so a view can tell "a new pick" from "the same one". */
    var changes: Int by mutableIntStateOf(0)

    const val BRIEFING = "briefing"
    const val SAVE = "save"

    /** The seats of a flight, in the words the flight picker and the identity strip use (WDP's `SelPilotSeat`). */
    val SEATS = listOf("Lead", "Wing", "Element lead", "Element wing")

    val fromSave: Boolean get() = source == SAVE

    /**
     * Plans [flight] of the save [ref] from [seat] (0-3). [file] is the row the Open mission list showed, when there was
     * one; [theater] the save's theater-definition name; [precision] the answer to the Precision STPT question. All of
     * it changes at once, so the pages see one new mission.
     */
    fun plan(theater: String, ref: CampRef, flight: CampFlight, seat: Int, file: CampFile? = null, precision: Boolean? = null) {
        Snapshot.withMutableSnapshot {
            this.theater = theater.trim()
            this.ref = ref
            this.flight = flight
            this.seat = seat.coerceIn(0, (flight.row.count - 1).coerceIn(0, 3))
            this.file = file
            this.precision = precision
            this.kind = file?.kind?.takeIf { it.isNotEmpty() } ?: kindOf(ref.file)
            this.notes = flight.notes
            this.source = SAVE
            changes++
        }
        // what Settings → Auto load last mission on startup opens again
        PlannerSettings.rememberLast(ref, this.seat)
        // what the Planner saved into the cartridge for another flight and is still there: asked about once
        PlannerLeftovers.planned()
    }

    /** **Back to BMS briefing**: the Planner plans the printed briefing and the cartridge again. */
    fun backToBriefing() {
        Snapshot.withMutableSnapshot {
            source = BRIEFING
            theater = null
            ref = null
            flight = null
            kind = null
            seat = 0
            file = null
            precision = null
            notes = emptyList()
            changes++
        }
    }

    /**
     * Whether planning [flight] of a save of [kind] asks the Precision STPT question, as WDP's `SelectNewFlight` does
     * (`cntDataCard.cs` l.40486-40497): a campaign (`.cam`) always; a TE or a training mission only when its mission file
     * (`<save>.ini`) is beside it — or when the PC does not say (an older PC). What is not asked is planned as WDP's No:
     * from the mission file.
     */
    fun asksPrecision(kind: String?, flight: CampFlight): Boolean {
        if (kind == CampKind.CAMPAIGN) return true
        val ini = flight.missionIni ?: return true
        return ini.matches || ini.modified > 0L || ini.reason?.startsWith("There is no mission file") != true
    }

    /**
     * WDP's question word for word (`cntDataCard.SelectNewFlight`): a campaign's with "DEFAULT ANSWER SHOULD BE: NO!", a
     * TE's with "If you are not sure, choose NO."; [callsign] is the flight's ("Satan7"), [path] the save's full path.
     */
    fun precisionQuestion(campaign: Boolean, callsign: String, path: String): String =
        "Did you save Precision STPT in the DTC for THIS flight in BMS?\n(flight callsign: $callsign)\n\n" +
            (if (campaign) "DEFAULT ANSWER SHOULD BE: NO!\n\nIf you answer YES, Precision STPT data from the DTC is used.\n"
            else "If you are not sure, choose NO.\n\nIf you answer YES, the STPT data from the DTC is used.\n") +
            "If you answer NO, the STPT data from the mission file (.tac/.cam) is used.\n\n" + path

    /** The question's title and answers, as WDP's `MessageBox` (Yes, No), No the default. */
    const val PRECISION_TITLE = "Question"
    const val YES = "Yes"
    const val NO = "No"

    /**
     * Whether the pilot's cartridge places steerpoints in a save of theater [saveTheater]: only when that is the theater
     * Falcon BMS is set to ([bmsTheater], the registry's), since the cartridge is made for BMS's mission there. A save
     * of another theater (Hellas WCP while BMS is on Korea KTO) would otherwise be planned against Korea's recon targets
     * at Korea's feet, which in Hellas land in the sea. True when either is unknown.
     */
    fun cartridgeApplies(saveTheater: String?, bmsTheater: String? = MissionLink.info.value?.bms?.theater): Boolean =
        saveTheater.isNullOrBlank() || bmsTheater.isNullOrBlank() || same(saveTheater, bmsTheater)

    /**
     * [data] as a save's flight is planned from it ([saveTheater]: the save's theater, by default the one planned now).
     * In a save of another theater than BMS's ([cartridgeApplies] false) the cartridge's positions — steerpoints, the
     * second target bank and weapon targets — are left out of the mission; its presets, codes, programs and everything
     * else stay. Anything else is [data] as it is.
     */
    fun missionData(
        data: MissionData?,
        saveTheater: String? = theater.takeIf { fromSave },
        bmsTheater: String? = MissionLink.info.value?.bms?.theater,
    ): MissionData? {
        val d = data?.dtc ?: return data
        if (cartridgeApplies(saveTheater, bmsTheater)) return data
        return data.copy(dtc = d.copy(steerpoints = emptyList(), open = emptyList(), weaponTargets = emptyList()))
    }

    /** Two theater names are the same theater: letters and digits only, any case (BMS pads some names with spaces). */
    private fun same(a: String, b: String): Boolean {
        fun key(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
        return key(a) == key(b)
    }

    /** "Cyborg6, Lead: Auto Save.cam (Korea KTO)", or null while the briefing is the source. */
    val label: String?
        get() {
            val f = flight ?: return null
            if (!fromSave) return null
            val r = ref
            return "${f.row.callsign}, ${SEATS.getOrElse(seat) { "Lead" }}" + (r?.let { ": ${it.file} (${it.theater})" } ?: "")
        }

    /** The kind of save a file name says (`.cam` campaign, `.tac` TE, `.trn` training). */
    fun kindOf(fileName: String): String? = when (fileName.substringAfterLast('.', "").lowercase()) {
        "cam" -> CampKind.CAMPAIGN
        "tac" -> CampKind.TE
        "trn" -> CampKind.TRAINING
        else -> null
    }
}
