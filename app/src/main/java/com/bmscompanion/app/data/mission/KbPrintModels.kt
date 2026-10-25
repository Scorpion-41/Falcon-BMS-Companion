package com.bmscompanion.app.data.mission

import kotlinx.serialization.Serializable

// Upd Kneeboard: the Planner's pages written into Falcon BMS's own 3D-cockpit kneeboard textures
// (GET /api/kbprint/state, /api/kbprint/thumb; POST /api/kbprint/file, /api/kbprint/shipped; docs/PROTOCOL.md,
// "Planner integration"). Every field has a default, so a PC a version ahead or behind never breaks decoding.
//
// The F-16's pages are the sixteen files 7982.dds-7997.dds in the theater's `KoreaObj` folder: the left half of each
// file is the left knee's page n, the right half the right knee's. The device running the Planner draws each half at
// 1024x1536 and sends it as a PNG; the PC scales it into the file's half and writes the file in the format it already
// has. A Picture half is sent as its file's path instead, and the PC draws it from the file ([KbHalf.picture]). The pages are written in place with no backup (they are made again by EZBoards, BMS's PRINT or this Print),
// through a temporary file and an atomic move, and a page file that does not exist is never created.

/**
 * The kneeboard pages of the theater Falcon BMS is set to, for the Print window.
 *
 * [folder] is the page folder relative to the BMS folder ("Data\\Add-On Hellas\\Terrdata\\objects\\KoreaObj"), from the
 * theater definition's `3ddatadir`; [theater] the theater-definition name it was worked out for. [twin] is true when
 * a `KoreaObj_HiRes` twin exists, which is written as well. [bmsIn3d] is true while the pilot is in the cockpit:
 * BMS reads the pages on entering it, so a print shows on the next entry. [shipped] is true when BMS's own shipped
 * pages are there to put back (`POST /api/kbprint/shipped`). [ezConfig] names the EZBoards config the claimed pages
 * were read from, null when EZBoards is not set up. [error] is a sentence when the folder could not be found or read.
 * [mode] is the Mission section's source on the PC ([MissionMode.EZBOARDS] or [MissionMode.WDP]; null from a PC
 * before 1.3.8): in WDP mode EZBoards does not run at PRINT, so its claims do not keep the Planner off its pages.
 *
 * For the Kneeboards page's **In the cockpit** view (1.4.1, [KbCockpit]): [since] is when the mission now planned
 * began, on the PC's clock as the page files' times are — EZBoards mode: the first PRINT of the printed flight; WDP
 * mode: the earliest of the Planner's first open of its flight, the Populate of that flight and a PRINT of that same
 * flight — so a half written at or after it is this mission's, one written before is from an earlier mission; 0 when
 * no mission is known. [sinceFrom] says which it was ([SINCE_PRINTED], [SINCE_OPENED], [SINCE_POPULATED]) and
 * [mission] names the flight ("Viper1, package 1234"), both null with it.
 */
@Serializable
data class KbPrintState(
    val folder: String? = null,
    val theater: String? = null,
    val twin: Boolean = false,
    val bmsIn3d: Boolean = false,
    val pages: List<KbSlot> = emptyList(),
    val shipped: Boolean = false,
    val ezConfig: String? = null,
    val error: String? = null,
    val mode: String? = null,
    val since: Long = 0,
    val sinceFrom: String? = null,
    val mission: String? = null,
) {
    companion object {
        const val SINCE_PRINTED = "printed"
        const val SINCE_OPENED = "opened"
        const val SINCE_POPULATED = "populated"
    }
}

/**
 * One page file, as it is now.
 *
 * [n] is the page (1-16), [file] its name ("7983.dds"). [format] is "DXT1", "DXT5" or "BGRA" for a file this program
 * can write, anything else for one it refuses (and then never touches); [mips] its mip count, [size] its width in
 * pixels (the files are square). [left] and [right] say who made each half now ([KbOwner]); [ezLeft]/[ezRight] mark a
 * half EZBoards writes at every PRINT (its `SET KNEEBOARD[F16_<n><L|R>]` lines). [missing] is true when the theater has
 * no such page file, which is never created.
 */
@Serializable
data class KbSlot(
    val n: Int = 0,
    val file: String = "",
    val format: String = "",
    val mips: Int = 0,
    val size: Int = 0,
    val left: String = KbOwner.OTHER,
    val right: String = KbOwner.OTHER,
    val ezLeft: Boolean = false,
    val ezRight: Boolean = false,
    /** file time */
    val modified: Long = 0,
    val missing: Boolean = false,
    /** what BMS Companion printed on the left half ([KbKind]), read from the file's own header; null when it did not */
    val leftKind: String? = null,
    /** the same for the right half */
    val rightKind: String? = null,
    /** why BMS Companion will not print into this file (a format it does not write, a file cut short), or null */
    val problem: String? = null,
    /**
     * the same page's `KoreaObj_HiRes` twin as it is now (its own owners, format and time), when the theater has one:
     * BMS shows the twin in the cockpit (`g_bHiResTextures`, on by default), so the In the cockpit view reads this
     * one ([KbCockpit.shown]) and asks for its pictures with `twin=1`; null when there is no twin (1.4.1)
     */
    val hiRes: KbSlot? = null,
)

/** Who made a half page, as [KbSlot.left] and [KbSlot.right] say, worked out from the file alone. */
object KbOwner {
    /** Falcon BMS's own page, as shipped */
    const val BMS = "bms"
    const val EZBOARDS = "ezboards"
    /** UOAF's html_brief exporter */
    const val HTMLBRIEF = "htmlbrief"
    /** Falcas's Weapon Delivery Planner */
    const val WDP = "wdp"
    /** this program's Upd Kneeboard */
    const val COMPANION = "companion"
    const val OTHER = "other"
    const val MISSING = "missing"
}

/**
 * One half page to print: [kind] is what the page is ([KbKind]), [label] the words the window showed for it, and
 * [png] the page itself, a 1024x1536 PNG as base64. A half the pilot left as it is is not sent at all.
 *
 * A [KbKind.PICTURE] half is sent as [picture] instead, the picture file's path on the BMS PC, and no [png]: the PC
 * reads the file and draws it into the half from the file's own pixels, as WDP does (a device's 1024x1536 capture,
 * stretched 4/3 into the file's 1024x2048 half, would lose a quarter of a full-size picture's rows).
 */
@Serializable
data class KbHalf(
    val kind: String = "",
    val label: String = "",
    val png: String = "",
    val picture: String = "",
)

/**
 * The body of `POST /api/kbprint/file`: one page file, [n] 1-16, with what to print on each half. A null half is
 * left exactly as it is (its blocks are copied, never re-encoded).
 */
@Serializable
data class KbFileSend(
    val n: Int = 0,
    val left: KbHalf? = null,
    val right: KbHalf? = null,
)

/**
 * What happened to one file: [status] is [KbFileResult.WRITTEN], [KbFileResult.UNCHANGED] or [KbFileResult.REFUSED],
 * and [reason] the sentence when it was refused (read-only, in use, no such page, a format this program does not
 * write, disk full).
 *
 * [leftRefused] and [rightRefused] say why that half was left as it is while the rest of the file was dealt with: a
 * [KbKind.PICTURE] half whose file is gone or cannot be read by print time (never a wrong or an empty half). When every
 * half sent was refused so, [status] is REFUSED and [reason] repeats them.
 */
@Serializable
data class KbFileResult(
    val file: String = "",
    val status: String = "",
    val reason: String? = null,
    val leftRefused: String? = null,
    val rightRefused: String? = null,
) {
    companion object {
        const val WRITTEN = "written"
        const val UNCHANGED = "unchanged"
        const val REFUSED = "refused"
    }
}

/** The page kinds the Print window offers, as [KbHalf.kind] names them. */
object KbKind {
    /** the default for every page: not printed, the file's half stays as it is */
    const val LEAVE = "leave"
    const val BLANK = "blank"
    const val TEST = "test"
    const val DATACARD_LEFT = "datacard-l"
    const val DATACARD_RIGHT = "datacard-r"
    const val COORDINATION_LEFT = "coordination-l"
    const val COORDINATION_RIGHT = "coordination-r"
    const val BRIEFING = "briefing"
    const val WEATHER = "weather"
    const val TARGETS_LEFT = "targets-l"
    const val TARGETS_RIGHT = "targets-r"
    const val ROUTE_MAP = "routemap"
    const val ATTACK = "attack"
    const val DEPARTURE = "departure"
    const val ARRIVAL = "arrival"
    const val ALTERNATE = "alternate"
    /**
     * a picture file on the BMS PC (WDP's "Selected Picture", Browse Picture), stretched to fill the half as WDP does;
     * sent as [KbHalf.picture] and drawn by the PC from the file
     */
    const val PICTURE = "picture"
    /** the DataCard's Airport Schedule (`fclsAptSchedule`): the departures from the flight's field (1.4.0) */
    const val SCHEDULE = "schedule"
    /** the route map in dark inks over the dark map style, for a kneeboard in the sun's glare (1.4.0) */
    const val ROUTE_MAP_DARK = "routemap-dark"
    /**
     * the mission's target pictures (the Briefing's Target pictures card): up to four targets, each in its two views,
     * chosen per half in the Print window (1.4.0)
     */
    const val TARGET_PICTURES = "targetpics"
    /**
     * the pilot's Noteboard 1 (1.4.1, [NotesDoc]): free notes, laser codes, figures and a few of the mission's tables,
     * composed in the Print window or on the Kneeboards page and kept on the PC (`/api/notes`)
     */
    const val NOTES = "notes"
    /** Noteboard 2, so both knees can carry a page of notes */
    const val NOTES_2 = "notes-2"
    /**
     * the mission's weather over the route as Mission → Weather → Mission weather shows it (its fill, overlays, level and
     * map), with the key and the figures at the take-off, target and landing (1.4.2)
     */
    const val WEATHER_MAP = "wxmap"
}

/**
 * The Kneeboards page's **In the cockpit** view (1.4.1): what Falcon BMS will show on the kneeboard in 3D, read from
 * [KbPrintState] — pure, so every device judges a page the same way.
 */
object KbCockpit {
    /** The file BMS shows for page [s]: its `KoreaObj_HiRes` twin when there is one, else the page file itself. */
    fun shown(s: KbSlot): KbSlot = s.hiRes ?: s

    /** The pages BMS has files for, in BMS's order (page 1 first). */
    fun pages(state: KbPrintState?): List<KbSlot> = state?.pages.orEmpty().filter { !shown(it).missing }.sortedBy { it.n }

    /** Who made half [side] (`L`/`R`) of page [s] as BMS shows it, and what BMS Companion printed there. */
    fun owner(s: KbSlot, side: Char): String = shown(s).let { if (side == 'L') it.left else it.right }
    fun kind(s: KbSlot, side: Char): String? = shown(s).let { if (side == 'L') it.leftKind else it.rightKind }

    /** A half of Falcon BMS's own page as it ships (a checklist page, nothing of this mission's). */
    fun original(s: KbSlot, side: Char) = owner(s, side) == KbOwner.BMS

    /** A page whose two halves are both BMS's own: folded away with the others like it. */
    fun allOriginal(s: KbSlot) = original(s, 'L') && original(s, 'R')

    /** The file BMS shows was written for the mission now planned ([KbPrintState.since]); false when none is known. */
    fun current(state: KbPrintState?, s: KbSlot): Boolean = state != null && state.since > 0 && shown(s).modified >= state.since

    /** The file was written before the mission now planned began: what it holds is an earlier mission's. */
    fun earlier(state: KbPrintState?, s: KbSlot): Boolean = state != null && state.since > 0 && shown(s).modified in 1 until state.since

    /** The halves in cockpit order — page 1 left knee, page 1 right knee, page 2 … — leaving out [skipOriginal] ones. */
    fun halves(state: KbPrintState?, skipOriginal: Boolean = false): List<Pair<Int, Char>> =
        pages(state).filter { !skipOriginal || !allOriginal(it) }.flatMap { listOf(it.n to 'L', it.n to 'R') }

    /** Where a picture of half [side] of page [s] as BMS shows it comes from, [w] px wide (at most 1024: the file's half). */
    fun picturePath(s: KbSlot, side: Char, w: Int): String =
        "/api/kbprint/thumb?n=${s.n}&side=$side&w=$w" + (if (s.hiRes != null) "&twin=1" else "") + "&t=${shown(s).modified}"
}
