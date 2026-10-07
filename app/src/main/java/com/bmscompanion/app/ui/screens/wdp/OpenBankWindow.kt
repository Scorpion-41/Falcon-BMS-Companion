package com.bmscompanion.app.ui.screens.wdp

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bmscompanion.app.data.Airport
import com.bmscompanion.app.data.wdp.DataCardPlan
import com.bmscompanion.app.data.wdp.DtcFromMission
import com.bmscompanion.app.data.wdp.DtcFromMission.Place
import com.bmscompanion.app.data.wdp.DtcFromMission.Pt
import com.bmscompanion.app.data.wdp.DtcStpt
import kotlin.math.roundToLong

/**
 * The Map page's **Add to Open bank…** (1.3.9): one point of the map into the DTC page's Open 1 (STPT 81-89) or
 * Open 2 (STPT 90-99) tab, with the steerpoint type the tabs' Change window offers (WDP's `fclsChangeSTPT.cboAction`:
 * Precision and `ActionString` 0-26, [DtcActions.names]). An alternate is a field as **Land**; a target or a threat
 * goes in as Precision, anything else as Nav. The slot starts on the first free one from 81 (the slots list what each
 * holds), and the point goes in through [DtcWiring.placeSteerpoint] — the DTC page's own edit: it shows on the Open
 * tab and on the map with its number, counts in Save to DTC (`target_80…98` with the type's code), and an occupied
 * slot is replaced only after WDP's question. A field's name (with its ICAO) and elevation go with it; any other
 * point takes the ground under it ([groundAt]), else 0 ft and the answer says so.
 *
 * Plain state ([slot], [action], [choice], [apply]) so the checks work it as a pilot does; [open] draws it as a
 * [WdpPanelWindow] in Windows' greys, as WDP's own windows are.
 */
internal class OpenBankChooser(
    private val dtc: DtcWiring,
    /** what can go in: a field near a right-click, then the point itself; the first is the default */
    val choices: List<Choice>,
    private val theater: String?,
    private val where: (Pt) -> String,
    /** the slot to start on (a steerpoint of the bank changing its type); else the first free from 81 */
    start: Int? = null,
    /** what was done: the slot and the answer, once the point is in */
    private val placed: (Int, String) -> Unit,
) {
    /** One thing the chooser can put in: [action] its default type, [elevFt] null = the ground under it. */
    data class Choice(val label: String, val name: String, val at: Pt, val elevFt: Double?, val action: Int, val group: String, val field: Boolean = false)

    /** A slot of the bank as the cartridge on the page holds it: [holds] its name and type, null when empty. */
    data class Slot(val n: Int, val holds: String?, val at: Pt?)

    var choice by mutableIntStateOf(0)
        private set
    var action by mutableIntStateOf(choices.firstOrNull()?.action ?: 0)
    var slot by mutableIntStateOf(start?.takeIf { it in BANK } ?: firstFree() ?: BANK.first)
    /** the types beyond Precision, Nav and Land are listed */
    var more by mutableStateOf(action !in MAIN)

    private var window: WdpPanelWindow? = null

    fun pick(i: Int) {
        if (i !in choices.indices) return
        choice = i
        action = choices[i].action
        if (action !in MAIN) more = true
    }

    private fun stpt(n: Int): DtcStpt? = dtc.model?.let { m -> if (n <= 89) m.open.getOrNull(n - 81) else m.hpn.getOrNull(n - 90) }

    fun slots(): List<Slot> = BANK.map { n ->
        val s = stpt(n)
        if (s == null || DtcFromMission.stptEmpty(s)) Slot(n, null, null)
        else {
            val name = s.target?.trim()?.takeIf { it.isNotEmpty() && !it.equals("Not set", true) } ?: "a point"
            Slot(n, "$name · ${DataCardPlan.actionString(s.action)}", Pt(s.falconY.toDouble(), s.falconX.toDouble()))
        }
    }

    fun firstFree(): Int? = BANK.firstOrNull { n -> stpt(n)?.let(DtcFromMission::stptEmpty) == true }

    /** The elevation that goes in: the field's, else the ground under the point (null while unknown). */
    fun elevation(c: Choice = choices[choice]): Double? = c.elevFt ?: groundAt(theater, c.at)?.toDouble()

    /** The button's words: "Add as STPT 81", or "Replace STPT 82…" over a point. */
    fun buttonText(): String {
        val s = slots().firstOrNull { it.n == slot }
        val c = choices.getOrNull(choice)
        return when {
            s?.holds == null -> "Add as STPT $slot"
            c != null && s.at != null && s.at.dist(c.at) < 50.0 -> "Set STPT $slot"
            else -> "Replace STPT $slot…"
        }
    }

    /**
     * The point into [slot] as [action]: the DTC page's call, which asks before replacing a point (and only then
     * closes the chooser); the same point at the same place (its type changed) is not asked about. Returns what was
     * done, or null while the question is up.
     */
    fun apply(): String? {
        val c = choices.getOrNull(choice) ?: return null
        val place = Place(c.name, c.at, elevation(c), c.group)
        val n = slot
        val same = slots().firstOrNull { it.n == n }?.at?.let { it.dist(c.at) < 50.0 } == true
        val r = dtc.placeSteerpoint(n, place, ask = !same, done = { msg -> close(); placed(n, msg) }, action = action)
        if (dtc.asked(r)) return null
        close()
        placed(n, r)
        return r
    }

    fun open() {
        current = this
        choices.forEach { askGround(theater, it.at) }
        val w = WdpPanelWindow("Add to Open bank — " + (choices.firstOrNull()?.name ?: ""), maxW = 460f, maxH = 660f, onDismiss = { if (current === this) current = null }) {
            OpenBankView(this)
        }
        window = w
        WdpDialogs.show(w)
    }

    fun close() {
        window?.let { WdpDialogs.close(it) }
        window = null
        if (current === this) current = null
    }

    /** Reads the DTC page's edit count, so a view of the slots is drawn again after each edit. */
    internal fun dtcVersion(): Any? = dtc.freeSlots()

    val isOpen: Boolean get() = window.let { w -> w != null && WdpDialogs.stack.any { it === w } }

    /** For the view: the position and elevation lines of the choice. */
    internal fun facts(): String {
        val c = choices.getOrNull(choice) ?: return ""
        val e = elevation(c)
        val elev = when {
            c.elevFt != null -> "field elevation ${thousands(c.elevFt.toLong())} ft"
            e != null -> "ground ${thousands(e.toLong())} ft"
            !WdpGround.known(theater, (c.at.north / 100.0).roundToLong() * 100.0, (c.at.east / 100.0).roundToLong() * 100.0) ->"ground: asking the PC…"
            else -> "elevation not known: 0 ft"
        }
        return where(c.at) + "  ·  " + elev
    }

    companion object {
        const val LABEL = "Add to Open bank…"
        val BANK = 81..99
        /** the types offered first: Precision, Nav, Land */
        val MAIN = listOf(-1, 0, 7)
        const val PRECISION = -1
        const val NAV = 0
        const val LAND = 7

        /** every type the Open tabs' Change window offers, in its (sorted) order, without its blank */
        val TYPES: List<Int> get() = DtcActions.names.filter { it.isNotEmpty() }.map { DtcActions.toInt(it) }

        /** the chooser on screen, for the checks */
        internal var current: OpenBankChooser? = null

        /** A field: its name with its ICAO, its elevation, Land. */
        fun field(a: Airport, role: String? = null): Choice {
            val icao = a.icao?.trim()?.takeIf { it.isNotEmpty() && !a.name.contains(it, ignoreCase = true) }
            val name = a.name + (icao?.let { " ($it)" } ?: "")
            return Choice(name + (role?.let { " · $it" } ?: ""), name, Pt(a.x, a.y), a.elevationFt?.toDouble() ?: 0.0, LAND, "Airbases", field = true)
        }

        fun point(name: String, at: Pt, action: Int, group: String, label: String = name, elevFt: Double? = null): Choice =
            Choice(label, name, at, elevFt, action, group)
    }
}

// Windows' own greys and blue, as WDP's windows are drawn
private val INK = Color(0xFF1B1B1B)
private val DIM = Color(0xFF5F6368)
private val FAINT = Color(0xFF9AA0A6)
private val EDGE = Color(0xFFADADAD)
private val BLUE = Color(0xFF0078D7)
private val PICKED_BG = Color(0xFFCCE4F7)
private val BUTTON_BG = Color(0xFFE1E1E1)
private val HEAD_BG = Color(0xFFF0F0F0)

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun OpenBankView(c: OpenBankChooser) {
    val finger = WdpTouch.device
    val row = if (finger) 44.dp else 24.dp
    val type = if (finger) 14.sp else 12.5.sp
    @Suppress("UNUSED_VARIABLE") val v = WdpGround.version
    // the DTC page's edits redraw the slots (read through its version)
    @Suppress("UNUSED_VARIABLE") val w = c.dtcVersion()
    Column(Modifier.fillMaxSize().background(Color.White).padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      // everything but the button scrolls as one (a phone, the types listed): the slots never shrink to nothing
      Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // what goes in
        if (c.choices.size > 1) {
            Caption("Point")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                c.choices.forEachIndexed { i, ch -> Chip(ch.label, i == c.choice, "OpenBankWhat/$i") { c.pick(i) } }
            }
        } else Text(c.choices.firstOrNull()?.name.orEmpty(), color = INK, fontSize = type, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(c.facts(), color = DIM, fontSize = if (finger) 13.sp else 11.5.sp, maxLines = 2)
        // the steerpoint's type, as the Open tabs' Change window offers it
        Caption("Steerpoint type")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (a in OpenBankChooser.MAIN) Chip(DataCardPlan.actionString(a), c.action == a, "OpenBankType/${DataCardPlan.actionString(a)}") { c.action = a }
            if (!c.more) Chip("More types…", false, "OpenBankMore") { c.more = true }
        }
        if (c.more) FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (a in OpenBankChooser.TYPES.filter { it !in OpenBankChooser.MAIN }) Chip(DataCardPlan.actionString(a), c.action == a, "OpenBankType/${DataCardPlan.actionString(a)}", small = true) { c.action = a }
        }
        // the slots, as the two tabs hold them
        Caption("Slot")
        val slots = c.slots()
        Column(Modifier.fillMaxWidth().border(1.dp, EDGE)) {
            for ((head, range) in listOf("Open 1 · STPT 81-89" to 81..89, "Open 2 · STPT 90-99" to 90..99)) {
                Box(Modifier.fillMaxWidth().background(HEAD_BG).padding(horizontal = 8.dp, vertical = 3.dp)) {
                    Text(head, color = DIM, fontSize = if (finger) 12.5.sp else 11.sp, fontWeight = FontWeight.Bold)
                }
                for (s in slots.filter { it.n in range }) {
                    val on = s.n == c.slot
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = row).background(if (on) PICKED_BG else Color.White).plannerPress { c.slot = s.n }
                            .mapControl("OpenBankSlot/${s.n}").padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(if (finger) 16.dp else 12.dp).clip(CircleShape).border(1.dp, if (on) BLUE else EDGE, CircleShape), contentAlignment = Alignment.Center) {
                            if (on) Box(Modifier.size(if (finger) 8.dp else 6.dp).clip(CircleShape).background(BLUE))
                        }
                        Spacer(Modifier.width(8.dp))
                        Text("STPT ${s.n}", color = INK, fontSize = type, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(if (finger) 76.dp else 62.dp))
                        Text(s.holds ?: "empty", color = if (s.holds == null) FAINT else INK, fontSize = type, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
      }
        Text("Goes on the DTC page's Open ${if (c.slot <= 89) "1" else "2"} tab and on this map; Save to DTC writes it.", color = DIM, fontSize = if (finger) 12.5.sp else 11.sp, maxLines = 2)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Box(
                Modifier.heightIn(min = if (finger) 44.dp else 28.dp).clip(RoundedCornerShape(3.dp)).background(BLUE).plannerPress { runCatching { c.apply() } }
                    .mapControl("OpenBankAdd").padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) { Text(c.buttonText(), color = Color.White, fontSize = type, fontWeight = FontWeight.SemiBold, maxLines = 1) }
        }
    }
}

@Composable
private fun Caption(text: String) = Text(text, color = DIM, fontSize = if (WdpTouch.device) 12.5.sp else 11.sp, fontWeight = FontWeight.Bold)

@Composable
private fun Chip(text: String, on: Boolean, probe: String, small: Boolean = false, onClick: () -> Unit) {
    val finger = WdpTouch.device
    val shape = RoundedCornerShape(3.dp)
    Box(
        Modifier.height(if (finger) 40.dp else if (small) 22.dp else 26.dp).clip(shape).background(if (on) BLUE else BUTTON_BG).border(1.dp, if (on) BLUE else EDGE, shape)
            .clickable(onClick = onClick).mapControl(probe).padding(horizontal = if (small) 7.dp else 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (on) Color.White else INK, fontSize = if (finger) 13.5.sp else if (small) 11.sp else 12.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
    }
}
