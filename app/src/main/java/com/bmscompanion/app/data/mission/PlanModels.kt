package com.bmscompanion.app.data.mission

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// The Planner's plan as the rest of the app sees it: BMS's own mission file beside the save (MissionData.route), and
// what the pilot sent with Send to Mission (MissionData.plan; GET/POST /api/plan…). docs/PROTOCOL.md, "Planner
// integration". Every field has a default, so a PC a version ahead or behind never breaks decoding.

/**
 * Falcon BMS's mission file: `<campaign folder>/<SaveFile>.ini`, the DTC file BMS writes beside a save.
 *
 * BMS keeps the flight's route there (not in the pilot's cartridge, whose route slots are empty in 4.38.1), so it is
 * the only place the app finds steerpoint positions before 3D. In `MissionData.route` it is only ever the file BMS
 * wrote for the briefed flight: the PC serves it there only when its route matches the printed briefing row for row
 * and the save's own waypoints to 2 ft. In a plan sent from the files on the PC ([PlanOverlay.route]) it is whatever
 * the file held at that moment.
 *
 * [file] and [save] are names, never paths. [steerpoints] are `target_n` as BMS wrote them (n = index + 1, the action
 * as written); [ppts], [lines] and [weaponTargets] as in [Dtc]. [bullseyeX]/[bullseyeY] are the bullseye from the
 * save's own header, in theater feet, **x north and y east** as everywhere in this protocol (the header itself stores
 * east first, in cells); null when the header could not be read.
 */
@Serializable
data class MissionRoute(
    val file: String = "",
    val save: String = "",
    /** [CampKind.CAMPAIGN], [CampKind.TE] or [CampKind.TRAINING] */
    val kind: String = "",
    /** the mission file's file time */
    val modified: Long = 0,
    val steerpoints: List<DtcPoint> = emptyList(),
    val ppts: List<DtcPpt> = emptyList(),
    val lines: List<DtcPoint> = emptyList(),
    val weaponTargets: List<DtcPoint> = emptyList(),
    val bullseyeX: Double? = null,
    val bullseyeY: Double? = null,
    /**
     * True when this is not the mission file but **the save's own flight plan** of the printed flight: in
     * `MissionData.route` only when the mission file is not believed and the newest save provably holds the printed
     * flight (callsign, package number and flight number, and the steerpoint table's words and times). [steerpoints]
     * are then the flight's waypoints, each at its cell's middle (n = waypoint number, the action as the save has it),
     * [file] is empty, and there are no PPTs, lines or weapon targets. A device marks such points as the save's
     * ([PlanItemSource.SAVE]), as WDP mode marks the populated flight's. Older devices ignore the field.
     */
    val fromSave: Boolean = false,
)

/**
 * What the pilot sent with **Send to Mission**, as the PC keeps it and every device merges it into its views.
 *
 * Nothing reaches the Mission section from the Planner without that button, and nothing of it is written into Falcon
 * BMS. [id] is when it was sent (ms since 1970) and doubles as its revision; 0 means there is no plan (`GET
 * /api/plan` answers one with [canUndo] when a cleared plan can be brought back).
 *
 * [source] says what was sent ([PlanSource]); [from] which kind of device sent it ("PC", "Android", "Browser" —
 * never a name). The mission it was made for is stamped on it: [theater] (the app's theater id), [callsign] and
 * [packageId] of the flight, [briefing] (the printed briefing's `generated` stamp, when there was one), and, when a
 * save was open in the Planner, [ref] and [seat] (0-3) with [flight] attached by the PC.
 *
 * [state] is [PlanState.APPLIED] (the devices merge it) or [PlanState.PARKED] (set aside because the printed briefing
 * is now for another flight; the devices merge nothing and [note] says why). [dtc] is the plan's cartridge, parsed
 * exactly like `MissionData.dtc`; [route] the mission file as snapshotted (source "files"); [attack] the attack the
 * attack page worked out. [notInJet] names what the jet will not have until the pilot saves and loads the DTC
 * ("STPT 5", "PPT 57", "LINE 2", "UHF 3", "OA1 on 6").
 */
@Serializable
data class PlanOverlay(
    val id: Long = 0,
    val source: String = PlanSource.PLANNER,
    val from: String? = null,
    val theater: String = "",
    val flight: CampFlight? = null,
    val ref: CampRef? = null,
    val seat: Int? = null,
    val callsign: String? = null,
    val packageId: String? = null,
    val briefing: String? = null,
    val state: String = PlanState.APPLIED,
    val note: String? = null,
    val dtc: Dtc = Dtc(),
    val route: MissionRoute? = null,
    val attack: AttackOverlay? = null,
    val notInJet: List<String> = emptyList(),
    val canUndo: Boolean = false,
) {
    /** there is a plan (a cleared or never-sent one has [id] 0) */
    val present: Boolean get() = id != 0L
    val applied: Boolean get() = present && state == PlanState.APPLIED
}

/** [PlanOverlay.source]: what the plan was made from. */
object PlanSource {
    /** the Planner's own cartridge as it stood (saved or not) and its attack */
    const val PLANNER = "planner"
    /** a snapshot of the files on the PC: the pilot's cartridge, the mission file and the save's bullseye (WDP or BMS) */
    const val FILES = "files"
    /**
     * WDP mode's snapshot (`POST /api/mission/populate`, 1.3.8): the Planner's flight of a save, its seat and attack.
     * Its `dtc` is empty — the cartridge as saved is `MissionData.dtc` — so nothing is marked as the plan's.
     */
    const val POPULATED = "populated"
}

/** [PlanOverlay.state]. */
object PlanState {
    const val APPLIED = "applied"
    const val PARKED = "parked"
}

/**
 * The body of `POST /api/plan`: the Planner's cartridge text (what a Save to DTC would write, saved or not; null to
 * let the PC take the cartridge on disk), the attack it worked out, which kind of device sends it, and — when a save
 * is open in the Planner — which flight and seat, so the PC attaches the flight itself.
 */
@Serializable
data class PlanSend(
    val cartridge: String? = null,
    val attack: AttackOverlay? = null,
    val from: String? = null,
    val ref: CampRef? = null,
    val seat: Int? = null,
)

/**
 * The cartridge's `[NAV OFFSETS]`: the attack's reference points as the jet gets them. [mode] is "vip", "vrp" or
 * "none". [oa] holds the offset aimpoints, OA1 and OA2 of each steerpoint that has them.
 */
@Serializable
data class NavOffsets(
    val mode: String = "none",
    val vip: NavOffset? = null,
    val vipPup: NavOffset? = null,
    val vrp: NavOffset? = null,
    val vrpPup: NavOffset? = null,
    val oa: List<NavOffset> = emptyList(),
)

/**
 * One reference point: [key] as the file names it ("VIP", "VIPPUP", "VRP", "VRPPUP", "OA1-6", "OA2-6"), the
 * steerpoint it is measured from, a true bearing in degrees, the range in feet and the elevation in feet.
 */
@Serializable
data class NavOffset(
    val key: String = "",
    val stpt: Int = 0,
    val bearing: Double = 0.0,
    val rangeFt: Double = 0.0,
    val elevFt: Double = 0.0,
)

/**
 * The cartridge's `[COMMS]`: the UHF and VHF presets tuned after a DTC load ([comm1], [comm2]), and the TACAN and ILS
 * WDP writes there. [tacan] reads as [Live.tacan] does ("94X", "12Y A/A"), [ils] is MHz as text ("109.30"), [ilsCrs]
 * degrees. Whether BMS 4.38.1 reads the TACAN and ILS keys is not established; the app marks them as the plan's.
 */
@Serializable
data class DtcComm(
    val comm1: Int? = null,
    val comm2: Int? = null,
    val tacan: String? = null,
    val ils: String? = null,
    val ilsCrs: Int? = null,
)

/**
 * What a Save to DTC did to a Tactical Engagement's own mission file (`<campaign folder>/<TE>.ini`), which BMS loads
 * over the cartridge in a TE. [written] false with a [reason] when it was not (a campaign save, a renamed TE, a TE
 * with no mission file, a read-only file) — the cartridge itself may still have been saved.
 */
@Serializable
data class MissionIniResult(
    val file: String = "",
    val written: Boolean = false,
    val reason: String? = null,
)

/**
 * What the PC answered to one of the Planner's calls (campaign files, Send to Mission, Upd Kneeboard): the
 * [value], or the sentence it gave instead.
 *
 * Those routes refuse in words — `{"error":"<sentence>"}` with HTTP 400, 409 or 501 — and the page has to show the
 * words, so the three `MissionLink` copies hand back this rather than a bare null. [status] is the HTTP status, 0 when
 * the PC could not be reached at all (then [error] says why). A refusal that also carries the state (a body with more
 * than `error`) gives both.
 */
data class PcAnswer<out T>(
    val value: T? = null,
    val error: String? = null,
    val status: Int = 0,
) {
    val ok: Boolean get() = value != null && error == null
    /** the PC answered, whatever it said */
    val reached: Boolean get() = status != 0

    companion object {
        /** A PC from before the route existed answers 404. */
        const val OLD_PC = "BMS Companion on the PC is an older version that cannot do this yet. Update it on the PC."

        private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }

        /**
         * Turns one answer into a [PcAnswer]. [decode] reads the value; a 2xx answer is the value, anything else is the
         * `error` sentence (and the value too, when the body holds more than the sentence). A body that is nothing but
         * `{"error":…}` is never a value, whatever the status: every model here has defaults, so it would otherwise
         * read as an empty one.
         */
        fun <T> read(status: Int, text: String, decode: (String) -> T): PcAnswer<T> {
            val obj = runCatching { lenient.parseToJsonElement(text) as? JsonObject }.getOrNull()
            val said = (obj?.get("error") as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
            val bare = obj != null && obj.keys.all { it == "error" }
            if (status in 200..299 && !(bare && said != null)) {
                return runCatching { PcAnswer<T>(value = decode(text), status = status) }
                    .getOrElse { PcAnswer(error = said ?: "The PC's answer could not be read.", status = status) }
            }
            val value = if (!bare) runCatching { decode(text) }.getOrNull() else null
            val error = when {
                status == 404 && (said == null || said == "not found") -> OLD_PC
                said != null -> said
                else -> "The PC answered HTTP $status."
            }
            return PcAnswer(value = value, error = error, status = status)
        }

        /** The PC could not be reached: [why] is the link's own reason. */
        fun <T> unreachable(why: String): PcAnswer<T> = PcAnswer(error = why, status = 0)
    }
}
