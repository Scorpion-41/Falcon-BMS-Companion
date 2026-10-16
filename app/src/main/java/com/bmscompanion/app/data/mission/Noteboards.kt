package com.bmscompanion.app.data.mission

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * The pilot's noteboards on this device (1.4.1): what the PC keeps for the mission now ([state], `GET /api/notes`), the
 * working copy the editor changes ([doc]) and its way back to the PC — every change is kept on the PC a moment after
 * the last key ([edit], [flush]), so the Upd Kneeboard print, the VR Notes board and every other device read the same.
 *
 * With nothing written for the mission (and nothing kept from an earlier one) the boards are [defaultDoc]: Noteboard 1
 * holds the laser codes — the cartridge's own TGP and LST codes filled in where it has them — and a block of free
 * notes; Noteboard 2 a block of free notes. Nothing is sent to the PC until the pilot changes something.
 */
object Noteboards {
    /** Noteboard 1 and Noteboard 2 ([KbKind.NOTES], [KbKind.NOTES_2]). */
    const val BOARDS = 2

    /** What the PC last answered; null before the first answer. */
    var state by mutableStateOf<NotesState?>(null)
        private set

    /** The working copy: what the editor shows and the pages draw. Null before the first answer. */
    var doc by mutableStateOf<NotesDoc?>(null)
        private set

    /** A sentence when the PC could not be read or could not keep a change; null otherwise. */
    var error by mutableStateOf<String?>(null)
        private set

    /** A change not yet kept on the PC. */
    var dirty by mutableStateOf(false)
        private set

    var saving by mutableStateOf(false)
        private set

    var loading by mutableStateOf(false)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var saveJob: Job? = null
    private var version = 0

    /** The boards the pages draw: the working copy, else the default ones for the mission the Mission section shows. */
    fun current(): NotesDoc = doc ?: defaultDoc(state?.key.orEmpty(), MissionLink.mission.value)

    /** Noteboard [i] (0 or 1) as the pages draw it. */
    fun board(i: Int): Noteboard = current().boards.getOrNull(i) ?: Noteboard()

    /**
     * Reads the mission's noteboards from the PC. A working copy with changes not yet kept for the same mission stays
     * as it is; one of another mission is first kept for the mission it was written for.
     */
    suspend fun load() {
        if (loading) return
        loading = true
        try {
            val a = MissionLink.notes()
            val s = a.value
            if (s == null) {
                error = a.error ?: "The BMS PC did not give the notes (an older BMS Companion keeps none)."
                return
            }
            if (dirty && doc?.key != s.key) flush()
            state = s
            error = s.error
            if (dirty) return
            doc = normalised(s.doc?.copy(key = s.key) ?: defaultDoc(s.key, MissionLink.mission.value))
        } finally {
            loading = false
        }
    }

    /** Changes the working copy; the PC keeps it a moment after the last change. */
    fun edit(change: (NotesDoc) -> NotesDoc) {
        doc = normalised(change(current()))
        dirty = true
        version++
        if (saveJob?.isActive != true) saveJob = scope.launch { delay(800); flush() }
    }

    /** Keeps every change not yet kept on the PC now (before a print draws the pages). */
    suspend fun flush() {
        var tries = 0
        while (dirty && tries++ < 4) if (!push()) return
    }

    private suspend fun push(): Boolean {
        val d = doc ?: return true
        val v = version
        saving = true
        val a = try { MissionLink.saveNotes(d) } finally { saving = false }
        val s = a.value
        if (s == null) {
            error = a.error ?: "The BMS PC did not keep the notes."
            return false
        }
        if (version == v) dirty = false
        // the answer is the mission now: a copy written for an earlier one is not taken in its place
        if (s.key == d.key) state = s
        error = s.error
        return true
    }

    // ---------------------------------------------------------------- the changes the editor makes

    fun updateBoard(i: Int, change: (Noteboard) -> Noteboard) = edit { d ->
        d.copy(boards = d.boards.mapIndexed { j, b -> if (j == i) change(b) else b })
    }

    fun updateBlock(i: Int, id: String, change: (NoteBlock) -> NoteBlock) = updateBoard(i) { b ->
        b.copy(blocks = b.blocks.map { if (it.id == id) change(it) else it })
    }

    fun addBlock(i: Int, type: String) = updateBoard(i) { b -> b.copy(blocks = b.blocks + newBlock(type, MissionLink.mission.value)) }

    fun removeBlock(i: Int, id: String) = updateBoard(i) { b -> b.copy(blocks = b.blocks.filter { it.id != id }) }

    /** Moves a block up ([by] -1) or down (+1). */
    fun moveBlock(i: Int, id: String, by: Int) = updateBoard(i) { b ->
        val at = b.blocks.indexOfFirst { it.id == id }
        val to = at + by
        if (at < 0 || to !in b.blocks.indices) b
        else b.copy(blocks = b.blocks.toMutableList().apply { add(to, removeAt(at)) })
    }

    fun setKeep(on: Boolean) = edit { it.copy(keep = on) }

    /** Board [i] back to its default blocks (Noteboard 1: laser codes and notes; Noteboard 2: notes). */
    fun clearBoard(i: Int) = updateBoard(i) { defaultDoc("", MissionLink.mission.value).boards.getOrNull(i) ?: Noteboard() }

    // ---------------------------------------------------------------- defaults

    /** A block of [type] as the Add menu makes it: laser codes and figures with their usual rows. */
    fun newBlock(type: String, m: MissionData?): NoteBlock = when (type) {
        NoteBlock.LASER -> NoteBlock(newId(), NoteBlock.LASER, rows = laserRows(m))
        NoteBlock.FIGURES -> NoteBlock(newId(), NoteBlock.FIGURES, rows = listOf(
            NoteRow("Joker", ""), NoteRow("Bingo", m?.dtc?.bingoLbs?.takeIf { it > 0 }?.toString().orEmpty()), NoteRow("Push", ""),
        ))
        NoteBlock.COMMS, NoteBlock.STEERPOINTS -> NoteBlock(newId(), type, max = 12)
        else -> NoteBlock(newId(), NoteBlock.TEXT)
    }

    /** The own codes from the cartridge where it has them (`[Laser]` LaserTGP, LaserLST), the others for the pilot to type. */
    fun laserRows(m: MissionData?): List<NoteRow> = listOf(
        NoteRow("TGP", m?.dtc?.laserTgp?.takeIf { it > 0 }?.toString().orEmpty()),
        NoteRow("LST", m?.dtc?.laserLst?.takeIf { it > 0 }?.toString().orEmpty()),
        NoteRow("#2", ""),
        NoteRow("Buddy", ""),
    )

    fun defaultDoc(key: String, m: MissionData?): NotesDoc = NotesDoc(
        key = key,
        boards = listOf(
            Noteboard("", listOf(NoteBlock("laser", NoteBlock.LASER, rows = laserRows(m)), NoteBlock("notes", NoteBlock.TEXT))),
            Noteboard("", listOf(NoteBlock("notes", NoteBlock.TEXT))),
        ),
    )

    /** Two boards, every block with an id of its own (a hand-edited file may lack them). */
    private fun normalised(d: NotesDoc): NotesDoc {
        val boards = (0 until BOARDS).map { i -> d.boards.getOrNull(i) ?: Noteboard() }.map { b ->
            val seen = HashSet<String>()
            b.copy(blocks = b.blocks.map { if (it.id.isBlank() || !seen.add(it.id)) it.copy(id = newId()).also { n -> seen += n.id } else it })
        }
        return if (boards == d.boards) d else d.copy(boards = boards)
    }

    private fun newId(): String = "b" + Random.nextInt(0, Int.MAX_VALUE).toString(36)

    /** For the checks: forgets everything held here. */
    fun forget() {
        saveJob?.cancel(); saveJob = null
        state = null; doc = null; error = null; dirty = false; saving = false; loading = false
    }
}
