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
)

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
}
