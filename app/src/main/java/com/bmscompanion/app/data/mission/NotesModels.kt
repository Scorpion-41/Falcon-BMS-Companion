package com.bmscompanion.app.data.mission

import kotlinx.serialization.Serializable

// The Noteboard (1.4.1): the pilot's own page of notes, laser codes and a few figures, composed block by block and
// kept on the BMS PC (`GET /api/notes`, `POST /api/notes`; docs/PROTOCOL.md, "The Noteboard"), so every device, the
// Upd Kneeboard print and the VR Notes board show the same one. Every field has a default, so a PC a version ahead or
// behind never breaks decoding.

/**
 * One row of a [NoteBlock.LASER] or [NoteBlock.FIGURES] block: [label] ("TGP", "#2", "Joker") and its [value]
 * ("1688", "4,000"), both as the pilot typed them.
 */
@Serializable
data class NoteRow(val label: String = "", val value: String = "")

/**
 * One block of a noteboard. [type] is one of the constants below; [id] is unique within its board (the editor's key);
 * [title] the heading printed over it (empty: the type's own); [text] a [TEXT] block's words; [rows] a [LASER] or
 * [FIGURES] block's rows. The app-fed blocks ([COMMS], [STEERPOINTS]) hold nothing of their own: they are drawn from
 * the mission the Mission section shows when the page is drawn, and [max] caps how many rows they take (0: as many as
 * fit).
 */
@Serializable
data class NoteBlock(
    val id: String = "",
    val type: String = TEXT,
    val title: String = "",
    val text: String = "",
    val rows: List<NoteRow> = emptyList(),
    val max: Int = 0,
) {
    companion object {
        /** free notes, typed: a blank page of the pilot's own when it is the board's only block */
        const val TEXT = "text"
        /** laser codes: the own targeting pod's, the LST's, the wingmen's and a buddy's, each with its label */
        const val LASER = "laser"
        /** figures the pilot types: joker, bingo, push time, anything with a name and a value */
        const val FIGURES = "figures"
        /** the briefing's comm ladder (agency, callsign, UHF, VHF), drawn from the mission */
        const val COMMS = "comms"
        /** the flight plan's steerpoints (number, what, time, altitude), drawn from the mission */
        const val STEERPOINTS = "steerpoints"

        val TYPES = listOf(TEXT, LASER, FIGURES, COMMS, STEERPOINTS)

        /** The words for a type, in the editor's Add menu and over a block left untitled. */
        fun typeName(type: String): String = when (type) {
            TEXT -> "Notes"
            LASER -> "Laser codes"
            FIGURES -> "Figures"
            COMMS -> "Comm ladder"
            STEERPOINTS -> "Steerpoints"
            else -> type
        }
    }
}

/** One noteboard: one page of the kneeboard (a half page file), its [title] and its [blocks] top to bottom. */
@Serializable
data class Noteboard(val title: String = "", val blocks: List<NoteBlock> = emptyList())

/**
 * The noteboards of one mission. [key] names the mission they were written for (`NotesState.key` as the PC gave it);
 * [boards] are Noteboard 1 and Noteboard 2 (the two [KbKind.NOTES] kinds); [keep] carries them to the next mission (a
 * new mission with nothing written starts from the newest kept one); [modified] is the PC's clock when they were last
 * kept.
 */
@Serializable
data class NotesDoc(
    val key: String = "",
    val boards: List<Noteboard> = emptyList(),
    val keep: Boolean = false,
    val modified: Long = 0,
)

/**
 * `GET /api/notes` (and `POST`'s answer): the mission now ([key], and [mission] in words: "Jaguar2 · package 3712"),
 * the noteboards kept for it ([doc]; null when none were written for it and none are kept, so a device lays out the
 * default ones), [carried] when [doc] is an earlier mission's kept with [NotesDoc.keep], and [error] a sentence when
 * the PC could not keep a change (its settings folder not writable), the change then held in memory only.
 */
@Serializable
data class NotesState(
    val key: String = "",
    val mission: String? = null,
    val doc: NotesDoc? = null,
    val carried: Boolean = false,
    val error: String? = null,
)
