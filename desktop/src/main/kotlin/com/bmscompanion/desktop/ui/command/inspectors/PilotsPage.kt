package com.bmscompanion.desktop.ui.command.inspectors

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bmscompanion.app.ui.theme.Hud
import com.bmscompanion.desktop.bridge.CampaignArchive
import com.bmscompanion.desktop.bridge.command.CampaignOps
import com.bmscompanion.desktop.bridge.command.CommandIds
import com.bmscompanion.desktop.bridge.command.CommandSave
import com.bmscompanion.desktop.bridge.command.Edits
import com.bmscompanion.desktop.bridge.command.PilotUsage
import com.bmscompanion.desktop.ui.command.CmdContext
import com.bmscompanion.desktop.ui.command.CmdGrid
import com.bmscompanion.desktop.ui.command.CmdKind
import com.bmscompanion.desktop.ui.command.CmdPage
import com.bmscompanion.desktop.ui.command.CmdPageView
import com.bmscompanion.desktop.ui.command.CmdRef
import com.bmscompanion.desktop.ui.command.CommandSession
import com.bmscompanion.desktop.ui.command.DeskButton
import com.bmscompanion.desktop.ui.command.GridCol
import com.bmscompanion.desktop.ui.command.Strip

/**
 * The Pilots page: the file's pilot names (Strings 2300 +) with how many squadron places use each, their voice and
 * photo, and the callsigns (Strings 2000 +) with the numbers 1-8 in use — what the file says against what its flights
 * fly — with the usage counted again from the squadrons and the flights, or cleared.
 */
object PilotsPage : CmdPageView {
    override val page = CmdPage.PILOTS

    private class CallRow(val i: Int, val name: String, val bits: Int, val flights: List<String>, val want: Int)

    @Composable
    override fun Content(ctx: CmdContext) {
        @Suppress("UNUSED_VARIABLE") val v = CommandSession.version
        val core = ctx.core
        val plt = core.plt
        Column(Modifier.fillMaxSize()) {
            EdDialogHost()
            if (plt == null) { EdNote("This file has no pilot part.", Hud.Amber); return@Column }
            val can = plt.editable
            val fromSquadrons = remember(CommandSession.version) { CampaignOps.pilotUsageFromSquadrons(core) }
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Strip {
                        Text("${plt.pilots.size} pilot names · ${fromSquadrons.count { it > 0 }} in squadrons", color = Hud.TextDim, fontSize = 11.5.sp, modifier = Modifier.weight(1f))
                        DeskButton("Count from the squadrons", enabled = can, tip = "Each name's usage = the squadron places that carry it") { ctx.edit(CampaignOps.recountPilots(core)) }
                        DeskButton("Clear usage", enabled = can, danger = true, tip = "Every name's usage 0 (BMS hands names out again)") {
                            ctx.edit(Edits.group("Clear every pilot's usage", plt.pilots.filter { it.usage != 0 }.map { p -> Edits.set("", "plt", { p.usage }, { p.usage = it }, 0) }))
                        }
                    }
                    // the six columns (470 dp) fit the half a 1600 px window leaves them, Photo included
                    CmdGrid(
                        rows = plt.pilots.withIndex().toList(),
                        cols = listOf(
                            GridCol<IndexedValue<PilotUsage>>("Nr", 44.dp, numeric = true, sort = { it.index }) { "${it.index}" },
                            GridCol("Name", 160.dp) { p -> core.pilotName(p.index)?.trim().orEmpty() },
                            GridCol("Usage", 56.dp, numeric = true, sort = { it.value.usage }) { "${it.value.usage}" },
                            GridCol("In squadrons", 104.dp, numeric = true, sort = { fromSquadrons.getOrElse(it.index) { 0 } }, ink = { p -> if (fromSquadrons.getOrElse(p.index) { 0 } != p.value.usage) Hud.Amber else null }) { "${fromSquadrons.getOrElse(it.index) { 0 }}" },
                            GridCol("Voice", 50.dp, numeric = true) { "${it.value.voice}" },
                            GridCol("Photo", 56.dp, numeric = true) { "${it.value.photo}" },
                        ),
                        key = { it.index },
                        selected = { false },
                        onClick = { _, _ -> },
                    )
                }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    val rows = remember(CommandSession.version) { callRows(core) }
                    var picked by remember { mutableIntStateOf(-1) }
                    Strip {
                        Text("${rows.size} callsigns · ${rows.count { it.bits != it.want }} differ from the flights", color = Hud.TextDim, fontSize = 11.5.sp, modifier = Modifier.weight(1f))
                        DeskButton("Count from the flights", enabled = can, tip = "Each callsign's numbers in use = the flights flying it") { ctx.edit(CampaignOps.recountCallsigns(core)) }
                        DeskButton("Clear", enabled = can && picked >= 0, danger = true, tip = "The chosen callsign's numbers all free") { ctx.edit(CampaignOps.clearCallsign(core, picked)) }
                    }
                    CmdGrid(
                        rows = rows,
                        cols = listOf(
                            GridCol<CallRow>("Nr", 54.dp, numeric = true, sort = { it.i }) { "${it.i}" },
                            GridCol("Callsign", 130.dp) { it.name },
                            GridCol("In use (file)", 110.dp, ink = { if (it.bits != it.want) Hud.Amber else null }) { r -> numbers(r.bits) },
                            GridCol("Flown", 110.dp) { r -> numbers(r.want) },
                            GridCol("Flights", 260.dp) { r -> r.flights.joinToString() },
                        ),
                        key = { it.i },
                        selected = { it.i == picked },
                        onClick = { r, _ ->
                            picked = r.i
                            core.flights.firstOrNull { it.callsignId == r.i }?.let { ctx.selection.set(CmdRef(CmdKind.FLIGHT, it.id.toString())) }
                        },
                    )
                }
            }
        }
    }

    private fun numbers(bits: Int): String = (1..8).filter { bits and (1 shl (it - 1)) != 0 }.joinToString(" ").ifEmpty { "—" }

    private fun callRows(core: CommandSave): List<CallRow> {
        val plt = core.plt ?: return emptyList()
        val want = CommandIds.callsignUsage(core)
        val byCall = core.flights.groupBy { it.callsignId }
        return plt.callsigns.indices.map { i ->
            CallRow(i, core.string(CampaignArchive.CALLSIGN_STRINGS + i)?.trim()?.ifEmpty { null } ?: "Callsign $i", plt.callsigns[i] and 0xFF,
                byCall[i].orEmpty().map { core.callsign(it) }, want.getOrElse(i) { 0 })
        }.filter { it.bits != 0 || it.want != 0 || it.flights.isNotEmpty() || it.name != "Callsign ${it.i}" }
    }
}
