package com.bmscompanion.desktop.ui.command.inspectors

import androidx.compose.runtime.Composable
import com.bmscompanion.desktop.bridge.command.MineFiles
import com.bmscompanion.desktop.ui.command.CmdDocument
import com.bmscompanion.desktop.ui.command.CommandSession
import com.bmscompanion.desktop.ui.command.Confirm
import com.bmscompanion.desktop.ui.command.DeskButton

/**
 * "This file is mine" in the identity strip: for a file taken for one of BMS's own **by its dates only** (a pilot's
 * save copied into the campaign folder with the install's files), the pilot says it is theirs — recorded once in
 * `User\BMS Companion Command\mine.json` ([MineFiles]); from then on it is overwritten like their own (after asking).
 * "Not mine after all" takes it back. Never offered for a start (by name or objectives) or a TE_BMS_/TR_BMS_ mission.
 */
@Composable
internal fun MineButtons(doc: CmdDocument, onConfirm: (Confirm) -> Unit) {
    val id = doc.core.identity
    fun reopen(done: String) {
        val e = doc.editing
        if (e == null || e.editCount == 0) {
            CmdDocument.open(doc.file, CommandSession.rootOverride).onSuccess { CommandSession.doc = it }
            CommandSession.status = done
        } else CommandSession.status = "$done It takes effect when the file is opened again (your edits are kept until then)."
    }
    if (id.claimable) DeskButton("This file is mine", tip = "Its dates put it with Falcon BMS's own files. If it is your own save, say so: it can then be overwritten (after asking).") {
        onConfirm(
            Confirm(
                "Is ${doc.file.name} yours?",
                "${doc.file.name} was taken for one of Falcon BMS's or a theater's own missions only because of its dates. If it is your own save, " +
                    "it is recorded as yours (in User\\BMS Companion Command\\mine.json) and Overwrite… may write it from then on. This changes nothing in the file itself.",
                "It is mine", danger = false,
            ) { MineFiles.add(doc.file) ?: null.also { reopen("${doc.file.name} is recorded as yours.") } },
        )
    }
    if (id.mine) DeskButton("Not mine after all", tip = "Take back that this file is yours: its dates decide again") {
        onConfirm(
            Confirm(
                "Take ${doc.file.name} back?", "${doc.file.name} is no longer recorded as yours; its dates decide again whether it is one of Falcon BMS's own files.",
                "Take it back", danger = false,
            ) { MineFiles.remove(doc.file) ?: null.also { reopen("${doc.file.name} is no longer recorded as yours.") } },
        )
    }
}
