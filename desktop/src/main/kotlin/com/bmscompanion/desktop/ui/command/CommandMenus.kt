package com.bmscompanion.desktop.ui.command

import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bmscompanion.app.ui.theme.Hud

// A table row's menu (Units, Objectives …): the same lines the map's menu has for that objective or unit — inspect,
// show on the map, set owner, then every registered action (CommandSlots.actions) that accepts it and does not need
// the point clicked on the map. Run at the row's own place.

/** One line of a menu, greyed with [tip] as its reason when not [enabled]. */
@Composable
fun MenuLine(text: String, enabled: Boolean = true, tip: String? = null, lead: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    val row: @Composable () -> Unit = {
        DropdownMenuItem(
            text = {
                if (lead == null) Text(text, fontSize = 12.5.sp, color = if (enabled) Hud.Text else Hud.TextFaint)
                else androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    androidx.compose.foundation.layout.Spacer(Modifier.width(10.dp)); lead()
                    Text(text, fontSize = 12.5.sp, color = if (enabled) Hud.Text else Hud.TextFaint)
                }
            },
            onClick = onClick, enabled = enabled, modifier = Modifier.height(28.dp),
        )
    }
    if (tip == null) row() else Tip(tip) { row() }
}

@Composable
fun MenuHead(text: String) =
    Text(text, color = Hud.TextDim, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))

@Composable
fun MenuRule() = HorizontalDivider(color = Hud.Outline.copy(alpha = 0.5f))

/**
 * The lines of [ref]'s menu in a table; [refs] is what the line acts on where it takes several (the selection when
 * the row is in it). [close] closes the menu.
 */
@Composable
fun RefMenuItems(ctx: CmdContext, refs: List<CmdRef>, ref: CmdRef, close: () -> Unit) {
    val world = ctx.world
    MenuHead(if (refs.size > 1) "${world.nameOf(ref)} (+${refs.size - 1} selected)" else world.nameOf(ref))
    MenuLine("Inspect") { if (refs.size > 1) ctx.selection.setAll(refs) else ctx.selection.set(ref); close() }
    if (world.place(ref) != null) MenuLine("Show on the map") { ctx.focus(ref); ctx.open(CmdPage.MAP); close() }
    if (ref.kind != CmdKind.WAYPOINT && ref.kind != CmdKind.DIVISION && ref.kind != CmdKind.PACKAGE && ref.kind != CmdKind.TEAM) {
        MenuRule()
        Text("Set owner", color = Hud.TextFaint, fontSize = 10.5.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp))
        world.teams.filter { it.inUse }.forEach { tm ->
            MenuLine(tm.name, enabled = CommandSlots.mapEdits != null, lead = { TeamFlagLead(tm.name, tm.colour) }) {
                val edits = CommandSlots.mapEdits
                val why = edits?.setOwner(refs, tm.n) ?: if (edits == null) "Owners are not set here." else null
                if (why == null) CommandSession.done("Set owner of ${if (refs.size == 1) world.nameOf(ref) else "${refs.size} selected"}: ${tm.name}") else ctx.say(why)
                ctx.changed()
                close()
            }
        }
    }
    val extra = CommandSlots.actions.filter { !it.mapOnly && runCatching { it.accepts(ctx, ref) }.getOrDefault(false) }
    if (extra.isNotEmpty()) MenuRule()
    val (n, e) = world.place(ref) ?: (0.0 to 0.0)
    extra.forEach { a ->
        val why = runCatching { a.refusal(ctx, ref) }.getOrNull()
        MenuLine(a.label, enabled = why == null, tip = why) {
            runCatching { a.run(ctx, ref, n, e) }.onFailure { ctx.say(it.message ?: "That did not work.") }
            close()
        }
    }
}
