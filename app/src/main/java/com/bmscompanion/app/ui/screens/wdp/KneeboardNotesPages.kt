package com.bmscompanion.app.ui.screens.wdp

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.bmscompanion.app.data.mission.KbKind
import com.bmscompanion.app.data.mission.MissionLink
import com.bmscompanion.app.data.mission.Noteboards
import com.bmscompanion.app.ui.screens.mission.NoteInks
import com.bmscompanion.app.ui.screens.mission.NoteboardSheet

/**
 * The Noteboard pages of Upd Kneeboard (1.4.1): Noteboard 1 and Noteboard 2 ([KbKind.NOTES], [KbKind.NOTES_2]), the
 * pilot's own notes, laser codes, figures and a few of the mission's tables, composed in the editor
 * ([NoteboardEditor]) and kept on the PC (`/api/notes`, [Noteboards]). A page is drawn as paper from what the device
 * holds, every change kept on the PC first ([preload]), so the page printed is the page the VR Notes board shows.
 */
object KneeboardNotes {
    val kinds: List<KbPageKind> = listOf(
        KbPageKind(KbKind.NOTES, "Noteboard 1: your notes, laser codes, figures", "Notes 1") { NotesPage(it, 0, KbKind.NOTES) },
        KbPageKind(KbKind.NOTES_2, "Noteboard 2: a second page of notes", "Notes 2") { NotesPage(it, 1, KbKind.NOTES_2) },
    )

    val ids: Set<String> = setOf(KbKind.NOTES, KbKind.NOTES_2)

    /** The noteboard a kind prints: 0 for Noteboard 1, 1 for Noteboard 2. */
    fun boardOf(kind: String): Int = if (kind == KbKind.NOTES_2) 1 else 0

    fun kindOf(board: Int): String = if (board == 1) KbKind.NOTES_2 else KbKind.NOTES

    /**
     * The editor while it is open in the Print window, in place of the page table ([NoteboardEditor]): the noteboard,
     * and the half page its preview stands for. Null when it is closed.
     */
    var editing by mutableStateOf<Editing?>(null)

    class Editing(val board: Int, val n: Int, val side: Char)

    /** Before a print: every change kept on the PC, and the boards read (once) when this device has none yet. */
    suspend fun preload() {
        Noteboards.flush()
        if (Noteboards.doc == null) Noteboards.load()
    }
}

/** One noteboard as a half page of the cockpit kneeboard ([KbPageScope] page 0: the editor's preview, no page number). */
@Composable
private fun NotesPage(scope: KbPageScope, board: Int, kind: String) {
    val mission by MissionLink.mission.collectAsState()
    if (Noteboards.doc == null) {
        LaunchedEffect(Unit) { Noteboards.load() }
        // held from the capture while the boards are on their way (a PC that does not answer leaves the default ones)
        if (Noteboards.loading || Noteboards.state == null && Noteboards.error == null) KneeboardPages.PageLoading()
    }
    val foot = if (scope.n in 1..16) "Page ${scope.n} · ${KneeboardPages.knee(scope.side)} · BMS Companion" else "Noteboard ${board + 1} · BMS Companion"
    NoteboardSheet(
        Noteboards.board(board), board, mission, NoteInks.PAPER, foot, Modifier.fillMaxSize(),
        report = { said, rows -> KneeboardExtraPages.drawn[kind] = said; KneeboardExtraPages.rows[kind] = rows },
    )
}
