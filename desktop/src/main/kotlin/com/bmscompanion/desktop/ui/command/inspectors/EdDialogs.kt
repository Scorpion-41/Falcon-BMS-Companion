package com.bmscompanion.desktop.ui.command.inspectors

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bmscompanion.app.ui.theme.Hud
import com.bmscompanion.desktop.ui.command.CommandSession
import com.bmscompanion.desktop.ui.command.DeskButton

/** A question the objective, team and campaign editors ask before an edit that matters (a delete, a change of count). */
internal class EdAsk(val title: String, val text: String, val yes: String, val danger: Boolean, val run: () -> String?)

internal object EdDialogs {
    var ask by mutableStateOf<EdAsk?>(null)
    val hosts = mutableStateListOf<Any>()
}

/** Shows [EdDialogs.ask]; several editors on screen each host it, the first composed draws it. */
@Composable
internal fun EdDialogHost() {
    val key = remember { Any() }
    DisposableEffect(key) { EdDialogs.hosts += key; onDispose { EdDialogs.hosts.remove(key) } }
    if (EdDialogs.hosts.firstOrNull() !== key) return
    val a = EdDialogs.ask ?: return
    AlertDialog(
        onDismissRequest = { EdDialogs.ask = null },
        confirmButton = {
            DeskButton(a.yes, strong = true, danger = a.danger) {
                EdDialogs.ask = null
                val why = runCatching { a.run() }.getOrElse { it.message ?: "That did not work." }
                if (why != null) CommandSession.error = why
                CommandSession.changed()
            }
        },
        dismissButton = { DeskButton("Cancel") { EdDialogs.ask = null } },
        title = { Text(a.title, color = Hud.Text, fontSize = 15.sp) },
        text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) { Text(a.text, color = Hud.TextDim, fontSize = 13.sp, lineHeight = 18.sp) } },
        containerColor = Hud.Surface,
    )
}
