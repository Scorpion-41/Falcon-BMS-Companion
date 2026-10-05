package com.bmscompanion.app.ui.screens.wdp

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.Weapon
import com.bmscompanion.app.data.wdp.PerformanceLoadout
import com.bmscompanion.app.data.wdp.WdpValues

/**
 * The Performance page's **Loadout** window (`fclsLoadout`): the flight's stores, hardpoint by hardpoint, and what they
 * weigh, drag and carry in fuel. The weights, drag and fuel are WDP's `WeightsDragsFuel` ([PerformanceLoadout.totals]),
 * and OK hands them to the page as WDP's window does; the way the stores are chosen is the app's own.
 *
 * WDP's window is a picture of the jet with each hardpoint's store written under it, and a grid of every store with a
 * column per hardpoint, where a cell is tapped once per store added. Pilots found it unclear: a row had to be tapped
 * twice, a single store could not be taken off, and a refusal came up as a message box that hid the window. Here the
 * window keeps WDP's frame — the aircraft and its seats on the left, the picture of the jet at the top, the weights on
 * the right, Clear All where it was (WDP's Ok and Cancel give way to the picker's Cancel and Apply, [REMOVED]) — and draws the rest itself:
 *
 * - **a card under each hardpoint** of the picture (anchored on WDP's own hardpoint number, `lblHpt*`), with the store
 *   hanging there, how many of how many it takes, **−** and **+** to take one off or put one on, and **✕** to empty
 *   the station. A tap on the card picks the station (a second tap lets it go);
 * - **the store picker** where WDP's grid was ([Picker]): a search box and the kinds the jet carries; every store it
 *   can carry, **grouped by kind** under a heading (air-to-air, air-to-ground missiles, guided bombs, bombs, rockets,
 *   tanks, ECM, pods), each one row with its weight, drag index and how many a station takes, in as few columns as
 *   hold them. **Pick a store, then a station** (the stations that take it light up green on the jet, the others step
 *   back) — or **a station, then a store** (the list shows only what that station takes): either hangs a full load,
 *   and **Mirror** (on by default, as BMS's own loadout screen works in pairs) does the same across the jet. A row's
 *   **+** adds one wherever it fits. The line over the list says what is picked: the station with **−**, **+** and
 *   **Clear station**, or the store and the stations that take it, or what happened ("No hardpoint left for another
 *   AIM-9X Sidewinder", in red, the window staying open). At its foot the totals, always in sight, with **Cancel** and
 *   **Apply** (WDP's Cancel and OK);
 * - **by finger, or where the window is drawn too small** (a phone, a tablet), the panel is one large **Choose
 *   stores** button and the picker opens over the window at the device's own sizes ([WdpPanelWindow]), with the jet's
 *   stations as a strip of finger-sized buttons at its top;
 * - the stores of each kind and their count, where WDP's AA/AG/ECM/Fuel lines were.
 *
 * **OK asks WDP's question when a mission is loaded** (`fclsLoadout.CloseForm`, WDP 3.7.24): "Loadout will not be
 * changed for your mission, only on the datacard. Do you want to continue?" with Yes, No and Cancel, in WDP's own
 * words and title. **Yes** hands the stores to the page (and so to the DataCard's take-off figures); **No** and
 * **Cancel** (and the box's ×, and Escape) leave the window open with the stores as they are, so nothing reaches the
 * page and nothing is lost — WDP closes its window whatever the answer (its OK button carries
 * `DialogResult.OK`, `fclsLoadout.cs` l.1812) and drops the changes with it. With no mission loaded there is no
 * question, as in WDP. The box is drawn over the window, which stays on the screen under it. Cancel and the window's ×
 * put the loadout back as it was when the window opened.
 */
internal class PerfLoadoutWindow(
    private val l: PerformanceLoadout,
    private val type: String,
    private val empty: Int,
    private val intFuel: Int,
    private val maxWeight: Int,
    startSeat: Int,
    private val missionLoaded: Boolean,
    /** the aircraft is an F-16, which is what WDP's picture shows; another's stations are drawn without it */
    private val f16: Boolean,
    /** another aircraft's picture in the app's aircraft reference (`img/tacref`), drawn where WDP's window shows its F-16 */
    private val aircraftPic: String? = null,
    private val onOk: (Int) -> Unit,
) : WdpWiring, WdpControlContent {
    /** bumped on every change: the window and its cards read it, so a change draws them again */
    private var rev by mutableIntStateOf(0)
    private val v = LinkedHashMap<String, String>()
    private var dialog: WdpDialog? = null

    private var seat by mutableIntStateOf(startSeat.coerceIn(0, 3))
    /** the hardpoint picked on the jet (the app's number: 10 is 5L, 11 is 5R), or null */
    private var station by mutableStateOf<Int?>(null)
    /**
     * The store picked in the list with no station picked: the stations that take it are lit on the jet, and a tap on
     * one hangs a full load of it there (null for none).
     */
    private var armed by mutableStateOf<String?>(null)
    private var filter by mutableStateOf(Filter.ALL)
    /** the search box's text: the list shows the stores whose name has it (case, spaces and dashes aside) */
    private var query by mutableStateOf("")
    /** the store picker drawn at the device's own sizes, over the window ([WdpPanelWindow]), while it is open */
    private var picker: WdpPanelWindow? = null
    /** on by default for the F-16, whose hardpoints pair as the picture shows; another aircraft's may not */
    private var both by mutableStateOf(f16)
    /** the window is drawn too small to work the store list in place (a phone, a tablet by finger): the picker opens over it */
    private var compact by mutableStateOf(false)
    private var status by mutableStateOf("")
    private var statusKind by mutableIntStateOf(HINT)
    /** the flight's loadout as it was when the window opened, for Cancel and the × */
    private val before = l.snapshot()

    /**
     * The WDP number label each hardpoint's card hangs from: the F-16's own (5L and 5R either side of 5); another
     * aircraft's stations in their number order across the picture, highest on the left as the F-16's are, spread over
     * the eleven places where it has fewer — not on the F-16 places that happen to share their numbers.
     */
    private val anchorOf: Map<Int, String> = run {
        val ns = (1..11).filter { l.has(it) }
        if (f16) ns.associateWith { anchor(it) }
        else {
            val desc = ns.sortedDescending()
            val n = desc.size
            desc.mapIndexed { i, hp ->
                hp to ANCHORS[if (n <= 1) ANCHORS.size / 2 else ((i * (ANCHORS.size - 1)).toFloat() / (n - 1) + 0.5f).toInt()]
            }.toMap()
        }
    }

    /** The hardpoint whose card hangs from WDP's number label [name], or null where none does. */
    private fun hpAt(name: String): Int? = anchorOf.entries.firstOrNull { it.value == name }?.key

    /** The kinds the store list is grouped by, in its order, each with its tag and colour. */
    enum class Group(val label: String, val tag: String, val color: Color) {
        AA("Air-to-air missiles", "A-A", Color(0xFF2F6FB5)),
        AGM("Air-to-ground missiles", "AGM", Color(0xFFB4621A)),
        GUIDED("Guided bombs", "GBU", Color(0xFF9C4F2E)),
        BOMBS("Bombs", "BOMB", Color(0xFF8A6A3A)),
        ROCKETS("Rockets", "RKT", Color(0xFFB0453A)),
        TANKS("Fuel tanks", "FUEL", Color(0xFF3C8D4A)),
        ECM("ECM", "ECM", Color(0xFF7A4DA8)),
        PODS("Pods and other", "POD", Color(0xFF66707C)),
    }

    /** The list's choices: everything, one kind, or what is on the aircraft now. */
    enum class Filter(val label: String, val group: Group? = null) {
        ALL("All"), AA("Air-to-air", Group.AA), AGM("AG missiles", Group.AGM), GUIDED("Guided bombs", Group.GUIDED),
        BOMBS("Bombs", Group.BOMBS), ROCKETS("Rockets", Group.ROCKETS), TANKS("Tanks", Group.TANKS), ECM("ECM", Group.ECM),
        PODS("Pods", Group.PODS), ON("On the jet"),
    }

    init { fill() }

    fun open() {
        val d = WdpDialog("fclsLoadout", "Loadout — $type", this, hidden = REMOVED, onDismiss = { l.restore(before) })
        dialog = d
        WdpDialogs.show(d)
    }

    private fun close() { closePicker(); dialog?.let { WdpDialogs.close(it) } }

    /** The store picker at the device's own sizes, over the window: where the window is too small to work by finger. */
    private fun openPicker() {
        if (picker != null) return
        val w = WdpPanelWindow("Choose stores — $type", maxW = 1100f, maxH = 900f, onDismiss = { picker = null }) {
            val density = LocalDensity.current
            val finger = WdpTouch.device
            Picker(Units(density.density), if (finger) Dims.FINGER else Dims.MOUSE, overlay = true, modifier = Modifier.fillMaxSize())
        }
        picker = w
        WdpDialogs.show(w)
    }

    private fun closePicker() { picker?.let { WdpDialogs.close(it) }; picker = null }

    // ================================================================ WDP's own controls

    override fun values(hidden: List<String>): WdpValues {
        @Suppress("UNUSED_VARIABLE") val r = rev
        val m = LinkedHashMap<String, String>()
        for (h in hidden) m[h] = "hidden"
        m.putAll(v)
        return WdpValues(m)
    }

    override fun onValue(name: String, value: String) {}

    override fun onClick(name: String) = guard {
        when {
            // the picker over the window goes first, so WDP's question is asked over the window itself
            name == "btnOK" -> { closePicker(); if (missionLoaded) askMission() else { close(); onOk(seat) } }
            name == "btnCancel" -> { l.restore(before); close() }
            name == "btnClear" -> {
                l.clear()
                say(if (l.aircraftCount > 1) "Every aircraft of the flight is empty now." else "The jet is empty now.", DONE)
            }
            name in SEATS -> {
                val i = SEATS.indexOf(name)
                if (i < l.aircraftCount && i != seat) {
                    seat = i
                    say("${SEAT_NAMES[i]}'s stores. What you change here is for this aircraft only; \"Copy to flight\" gives them to all.", HINT)
                }
            }
            // the grid's rows are the tiles in their order (a headless check reaches them by WDP's grid name)
            name.startsWith("dgvLoadOut:row:") || name.startsWith("dgvLoadOut:open:") ->
                shown().getOrNull(name.substringAfterLast(':').toIntOrNull() ?: -1)?.let { tapStore(it.first.key) }
            else -> hpAt(name)?.let { tapStation(it) }
        }
    }

    /**
     * WDP's question before a mission's loadout is changed (`CloseForm`): only Yes hands the stores over and closes the
     * window; No, Cancel and the box's × keep the window open on the stores as they are.
     */
    private fun askMission() {
        WdpDialogs.message("Question", MISSION_QUESTION, listOf("Yes", "No", "Cancel")) { answer ->
            if (answer == "Yes") { close(); onOk(seat) }
        }
    }

    /** What WDP's controls show: the aircraft, the seats, the weights; the labels and grid the window draws itself are hidden. */
    private fun fill() {
        v["lblAC"] = type
        SEATS.forEachIndexed { i, r ->
            v[r] = if (i == seat) "checked" else "unchecked"
            v["$r.enabled"] = (i < l.aircraftCount).toString()
        }
        for (n in DRAWN_HERE) v[n] = "hidden"
        // a hardpoint's number is where its card hangs: blank (the card draws it), or hidden where the jet has none
        for (a in ANCHORS) v[a] = if (hpAt(a) != null) "" else "hidden"
        v["lblAA"] = ""
        // another aircraft: the app's own picture of it over WDP's F-16 (drawn in picLoadout's box), else no picture
        if (!f16 && aircraftPic == null) v["picLoadout"] = "hidden"
        v["dgvLoadOut.columns"] = ""
        // the weights, WDP's FillLabels
        val tot = l.totals(seat)
        val fuel = intFuel + tot.extFuel
        val gross = empty + tot.loadWeight + fuel
        v["lblEmptyW"] = empty.toString()
        v["lblMaxW"] = maxWeight.toString()
        v["lblFuelIntW"] = intFuel.toString()
        v["lblFuelExtW"] = tot.extFuel.toString()
        v["lblFuelW"] = fuel.toString()
        v["lblLoadW"] = tot.loadWeight.toString()
        v["lblGrossW"] = gross.toString()
        v["lblGrossW.fore"] = if (gross > maxWeight) "Red" else "Black"
        v["lblDragVal"] = tot.totalDrag.toString()
        v["lblFuelUsedW"] = "0"
        // what the window draws, as values too, so the headless checks can read it
        v["station"] = station?.let { hpLabel(it) } ?: ""
        v["filter"] = filter.label
        v["armed"] = armed.orEmpty()
        v["query"] = query
        v["picker"] = (picker != null).toString()
        v["both"] = both.toString()
        v["status"] = status
        v["stores"] = l.seats[seat].entries.sortedBy { PerformanceLoadout.ORDER.indexOf(it.key) }
            .joinToString(" ") { (hp, ld) -> hpLabel(hp) + ":" + ld.count + "x" + ld.key }
        rev++
    }

    /** Never throws: whatever goes wrong is said on the window's own line, which stays open. */
    private fun guard(block: () -> Unit) {
        try { block() } catch (e: Exception) {
            say("That did not work: " + (e.message ?: e::class.simpleName ?: "an error") + ".", BAD)
        }
        fill()
    }

    private fun say(text: String, kind: Int) { status = text; statusKind = kind }

    // ================================================================ what a tap does

    private fun name(key: String?) = key?.let { l.weapons[it]?.name } ?: key ?: ""

    /** A hardpoint's name: the F-16's own marking (5L, 5R for the chin stations), BMS's number on another aircraft. */
    private fun hpLabel(hp: Int): String = if (f16) PerformanceLoadout.hpName(hp) else hp.toString()

    /**
     * The hardpoint across the jet: the F-16's pairs (1-9, 2-8, 3-7, 4-6, 5L-5R); on another aircraft the station
     * the same way in from the other end of its numbering, where it has one.
     */
    private fun acrossOf(hp: Int): Int? {
        if (f16) return PerformanceLoadout.mirror(hp)
        val ns = l.stations.map { it.n }.filter { it in 1..11 }
        if (ns.isEmpty()) return null
        return (ns.min() + ns.max() - hp).takeIf { it != hp && l.has(it) }
    }

    /**
     * A station tapped (its card on the jet, or its button in the picker): with a store picked in the list and none on
     * the jet, a full load of that store hangs there (and across the jet, Mirror on); otherwise the station is picked,
     * or let go when it was.
     */
    private fun tapStation(hp: Int) {
        val a = armed
        if (station == null && a != null) { hang(hp, a); return }
        if (station == hp) station = null else { station = hp; armed = null }
        say("", HINT)
    }

    /**
     * A store tapped in the list: with a station picked, a full load of it there; with none, the store is picked (or
     * let go when it was), and the stations that take it light up on the jet for a tap.
     */
    fun tapStore(key: String) = guard {
        val hp = station
        if (hp != null) { hang(hp, key); return@guard }
        if (armed == key) { armed = null; say("", HINT); return@guard }
        armed = key
        val st = fitsOn(key)
        if (st.isEmpty()) say("No station of this jet takes the ${name(key)}.", BAD)
        else say("", HINT)
    }

    /** A full load of [key] on [hp], and on the hardpoint across the jet with Mirror on. */
    private fun hang(hp: Int, key: String) {
        val w = name(key)
        val m = l.max(hp, key)
        if (m <= 0) { say("Station ${hpLabel(hp)} cannot carry the $w.", BAD); return }
        l.set(seat, hp, key, m)
        var said = "Station ${hpLabel(hp)}: $m × $w"
        val across = acrossOf(hp)?.takeIf { both && l.has(it) }
        if (across != null) {
            val m2 = l.max(across, key)
            said += if (m2 > 0) { l.set(seat, across, key, m2); ", and $m2 on ${hpLabel(across)}" }
            else " (station ${hpLabel(across)} cannot carry it)"
        }
        say("$said.", DONE)
    }

    /** A store's **+** in the list: one more of it wherever it fits (on a station that has it first), mirrored. */
    fun addOne(key: String) = guard {
        val w = name(key)
        val at = l.addOneAt(seat, key)
        if (at == null) { say("No hardpoint left for another $w.", BAD); return@guard }
        var said = "One more $w, on station ${hpLabel(at)}"
        val across = acrossOf(at)?.takeIf { both && l.has(it) }
        if (across != null) {
            val here = l.seats[seat][at]?.count ?: 1
            val there = l.seats[seat][across]
            if ((there == null || there.key == key) && l.max(across, key) > 0 && (there?.count ?: 0) < here) {
                l.set(seat, across, key, here)
                said += " and ${hpLabel(across)}"
            }
        }
        say("$said.", DONE)
    }

    /** The stations that take [key], in the order the jet shows them (left to right across the picture). */
    private fun fitsOn(key: String): List<Int> = visualOrder().filter { l.max(it, key) > 0 }

    /** The hardpoints left to right as the picture shows them. */
    private fun visualOrder(): List<Int> = anchorOf.entries.sortedBy { ANCHORS.indexOf(it.value) }.map { it.key }

    /** The most of [key] any one station takes: what a rack holds of it. */
    private fun perStation(key: String): Int = (1..11).maxOf { l.max(it, key) }

    /** A store's weight and drag in the mission's theater, as the totals count them. */
    private fun lbs(w: Weapon): Double? = l.figures[w.key]?.weightLbs ?: w.weightLbs
    private fun dragOf(w: Weapon): Double? = l.figures[w.key]?.drag ?: w.drag

    /** −/+ on a station's card: one store off or on, the same across the jet when Both sides is on. */
    fun stepStation(hp: Int, delta: Int) = guard {
        val cur = l.seats[seat][hp] ?: return@guard
        val w = name(cur.key)
        if (!l.step(seat, hp, delta)) {
            say("Station ${hpLabel(hp)} takes at most ${l.max(hp, cur.key)} × $w.", BAD)
            return@guard
        }
        val now = l.seats[seat][hp]?.count ?: 0
        var said = if (now == 0) "Station ${hpLabel(hp)} is empty now" else "Station ${hpLabel(hp)}: $now × $w"
        val across = acrossOf(hp)?.takeIf { both }
        val there = across?.let { l.seats[seat][it] }
        if (across != null && there != null && there.key == cur.key) {
            l.set(seat, across, cur.key, now)
            said += ", and on ${hpLabel(across)}"
        }
        say("$said.", DONE)
    }

    /** ✕ on a station's card: the station emptied, and the one across the jet when it carries the same. */
    fun clearStation(hp: Int) = guard {
        val cur = l.seats[seat][hp] ?: return@guard
        l.clearStation(seat, hp)
        var said = "Station ${hpLabel(hp)} emptied"
        val across = acrossOf(hp)?.takeIf { both }
        if (across != null && l.seats[seat][across]?.key == cur.key) {
            l.clearStation(seat, across)
            said += ", and ${hpLabel(across)}"
        }
        say("$said.", DONE)
    }

    fun pickFilter(f: Filter) = guard { filter = f }

    fun toggleBoth() = guard {
        both = !both
        say(if (both) "Mirror: what you hang or take off goes on the hardpoint across the jet too." else "Mirror off: each station on its own.", HINT)
    }

    fun search(text: String) { query = text; fill() }

    /** The picked station's −/+ and Clear station, from the line over the list. */
    private fun stepPicked(delta: Int) { station?.let { stepStation(it, delta) } }

    private fun clearPicked() { station?.let { clearStation(it) } }

    fun copyToFlight() = guard {
        l.copyToFlight(seat)
        say("${SEAT_NAMES[seat]}'s stores are on all ${l.aircraftCount} aircraft of the flight now.", DONE)
    }

    /**
     * What the station picked takes (each with how many), else every store the jet carries (each with the most one
     * station takes); narrowed by the list's choice and the search box, grouped by kind, by name within a kind.
     */
    private fun base(): List<Pair<Weapon, Int>> {
        val hp = station
        return if (hp != null) l.fits(hp) else l.stores.map { w -> w to perStation(w.key) }
    }

    private fun shown(): List<Pair<Weapon, Int>> {
        val onJet = l.seats[seat].values.map { it.key }.toSet()
        val q = squash(query)
        return base().filter { (w, _) ->
            (when (filter) {
                Filter.ALL -> true
                Filter.ON -> w.key in onJet
                else -> groupOf(w) == filter.group
            }) && (q.isEmpty() || squash(w.name).contains(q))
        }.sortedWith(compareBy({ groupOf(it.first).ordinal }, { it.first.name.lowercase() }))
    }

    // ================================================================ what the window draws itself

    override fun controlContent(): Map<String, @Composable () -> Unit> = buildMap {
        for ((hp, a) in anchorOf) put(a) { StationCard(hp) }
        if (!f16 && aircraftPic != null) put("picLoadout") { AircraftPicture(aircraftPic) }
        put("lblAA") { Summary() }
        put("dgvLoadOut") { Stores() }
    }

    /** A hardpoint's card, hung from WDP's hardpoint number under the picture (a 20 × 13 label: the card is drawn past it). */
    @Composable
    private fun StationCard(hp: Int) {
        @Suppress("UNUSED_VARIABLE") val r = rev
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val k = constraints.maxWidth / 20f
            val u = Units(k)
            val load = l.seats[seat][hp]
            val w = load?.let { l.weapons[it.key] }
            val picked = station == hp
            val a = armed
            val fits = station == null && a != null && l.max(hp, a) > 0
            // with a store picked in the list, the stations that cannot take it step back
            val faded = station == null && a != null && !fits
            val shape = RoundedCornerShape(u.dp(3f))
            Box(Modifier.wrapContentSize(Alignment.TopCenter, unbounded = true)) {
                Column(
                    Modifier.requiredSize(u.dp(CARD_W), u.dp(CARD_H)).alpha(if (faded) 0.45f else 1f).clip(shape)
                        .background(if (picked) PICKED_BG else if (fits) GREEN_BG else Color.White)
                        .border(u.dp(if (picked || fits) 2f else 1f), if (picked) BLUE else if (fits) GREEN_INK else EDGE, shape)
                        // too small for a finger here (compact): the picker opens on this station at the device's sizes
                        .clickable {
                            guard {
                                if (compact && !(station == null && armed != null)) { station = hp; say("", HINT); openPicker() }
                                else tapStation(hp)
                            }
                        }
                        .plannerProbe("Loadout/Station/${hpLabel(hp)}")
                        .wdpTip("planner/Loadout/Station/$hp", stationTip(hp), touch = true),
                ) {
                    Box(
                        Modifier.fillMaxWidth().height(u.dp(16f)).background(if (picked) BLUE else if (fits) GREEN_BG else HEAD_BG),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(hpLabel(hp), color = if (picked) Color.White else INK, fontSize = u.sp(10.5f), fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                    Box(Modifier.fillMaxWidth().height(u.dp(56f)).padding(horizontal = u.dp(2f)), contentAlignment = Alignment.Center) {
                        if (w != null) FitText(w.name, u.px(11f), u.px(6.5f), INK, maxLines = 3, align = TextAlign.Center, bold = true)
                        else Text(if (picked) "pick a store below" else "empty", color = FAINT, fontSize = u.sp(9f), fontStyle = FontStyle.Italic, textAlign = TextAlign.Center)
                    }
                    Box(Modifier.fillMaxWidth().height(u.dp(13f)).padding(horizontal = u.dp(2f)), contentAlignment = Alignment.Center) {
                        val rack = load?.let { l.rack(hp, it.key) }?.takeIf { readableRack(it) }
                        if (rack != null) FitText(rack, u.px(8.5f), u.px(6f), FAINT, align = TextAlign.Center)
                    }
                    if (load != null) {
                        Row(Modifier.fillMaxWidth().height(u.dp(21f)).padding(horizontal = u.dp(2f)), verticalAlignment = Alignment.CenterVertically) {
                            SmallButton("−", u, "Loadout/Minus/${hpLabel(hp)}") { stepStation(hp, -1) }
                            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                FitText("${load.count}/${l.max(hp, load.key)}", u.px(11f), u.px(7f), INK, align = TextAlign.Center, bold = true)
                            }
                            SmallButton("+", u, "Loadout/Plus/${hpLabel(hp)}") { stepStation(hp, 1) }
                        }
                        Box(
                            Modifier.fillMaxWidth().height(u.dp(19f)).padding(u.dp(2f)).clip(RoundedCornerShape(u.dp(2f))).background(CLEAR_BG)
                                .clickable { clearStation(hp) }.plannerProbe("Loadout/Clear/${hpLabel(hp)}"),
                            contentAlignment = Alignment.Center,
                        ) { Text("✕ empty", color = RED, fontSize = u.sp(9f), maxLines = 1) }
                    }
                }
            }
        }
    }

    /** The stores of each kind and their count, where WDP's AA/AG/ECM/Fuel lines were (hung from `lblAA`, 228 wide). */
    @Composable
    private fun Summary() {
        @Suppress("UNUSED_VARIABLE") val r = rev
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val u = Units(constraints.maxWidth / 228f)
            val kinds = listOf(
                "A-A" to listOf(PerformanceLoadout.AA), "A-G" to listOf(PerformanceLoadout.AG),
                "Pods" to listOf(PerformanceLoadout.ECM, PerformanceLoadout.OTHER), "Fuel" to listOf(PerformanceLoadout.TANK),
            )
            val t = l.seats[seat]
            Box(Modifier.wrapContentSize(Alignment.TopStart, unbounded = true)) {
                Column(Modifier.requiredSize(u.dp(228f), u.dp(108f)), verticalArrangement = Arrangement.spacedBy(u.dp(2f))) {
                    for ((label, cats) in kinds) {
                        val text = t.values.groupBy { it.key }.mapNotNull { (key, loads) ->
                            l.weapons[key]?.takeIf { PerformanceLoadout.category(it) in cats }?.let { "${loads.sumOf { it.count }} ${it.name}" }
                        }.joinToString(", ").ifEmpty { "—" }
                        Row(Modifier.fillMaxWidth().height(u.dp(20f)), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.width(u.dp(34f)).height(u.dp(15f)).clip(RoundedCornerShape(u.dp(2f))).background(kindColor(cats[0])),
                                contentAlignment = Alignment.Center,
                            ) { Text(label, color = Color.White, fontSize = u.sp(8.5f), fontWeight = FontWeight.Bold, maxLines = 1) }
                            Spacer(Modifier.width(u.dp(5f)))
                            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
                                FitText(text, u.px(11f), u.px(7f), if (text == "—") FAINT else INK, maxLines = 2)
                            }
                        }
                    }
                    val n = l.count(seat)
                    FitText(
                        if (n == 0) "No stores on this aircraft" else "$n store${if (n == 1) "" else "s"} on ${t.size} station${if (t.size == 1) "" else "s"}",
                        u.px(11f), u.px(7f), INK, bold = true, modifier = Modifier.fillMaxWidth().height(u.dp(16f)),
                    )
                }
            }
        }
    }

    /**
     * The stores to pick from, where WDP's grid was (1060 × 405 designer pixels). Where the window is drawn large enough
     * to work it (a PC's mouse) the picker is drawn here, in the window's scale; where it is not — a phone, or a tablet
     * by finger — this is one large **Choose stores** button and the totals, and the picker opens over the window at
     * the device's own sizes ([openPicker]).
     */
    @Composable
    private fun Stores() {
        @Suppress("UNUSED_VARIABLE") val r = rev
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val u = Units(constraints.maxWidth / 1060f)
            val dpPer = u.k / LocalDensity.current.density
            val small = if (WdpTouch.device) dpPer < 1.6f else dpPer < 0.6f
            // never written while composing (a write there draws again, and again): after the frame
            androidx.compose.runtime.SideEffect { if (small != compact) compact = small }
            // the panel takes every press on it, so a tap between the rows never reaches WDP's grid drawn underneath
            val frame = Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { } }.background(Color.White).border(u.dp(1f), EDGE)
            if (!small) Picker(u, Dims.PANEL, overlay = false, modifier = frame.padding(u.dp(6f)))
            else Column(frame.padding(u.dp(8f)), verticalArrangement = Arrangement.spacedBy(u.dp(8f))) {
                Box(
                    Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(u.dp(8f))).background(BLUE)
                        .clickable { guard { openPicker() } }.plannerProbe("Loadout/Choose"),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Choose stores", color = Color.White, fontSize = u.sp(40f), fontWeight = FontWeight.Bold, maxLines = 1)
                        Text(
                            "Tap here, or a station on the jet, for the list of stores at a finger's size",
                            color = Color.White.copy(alpha = 0.85f), fontSize = u.sp(20f), maxLines = 1,
                        )
                    }
                }
                Totals(u, Dims.PANEL, buttons = true, modifier = Modifier.fillMaxWidth().height(u.dp(60f)), big = true)
            }
        }
    }

    /** Sizes of the picker, in its units: designer pixels in the window, dp over it ([openPicker]). */
    private enum class Dims(
        val row: Float, val head: Float, val font: Float, val small: Float, val bar: Float, val minCol: Float, val tag: Float,
    ) {
        PANEL(row = 16f, head = 14f, font = 10.5f, small = 9f, bar = 20f, minCol = 190f, tag = 26f),
        MOUSE(row = 24f, head = 18f, font = 13f, small = 11.5f, bar = 28f, minCol = 260f, tag = 34f),
        FINGER(row = 44f, head = 26f, font = 15f, small = 12.5f, bar = 40f, minCol = 300f, tag = 44f),
    }

    /**
     * The store picker: (over the window, the jet's stations as a strip of buttons;) a search box and the kinds;
     * one line that says what is picked and what to do — the picked station's − / + and Clear station, or the store
     * picked and which stations take it — beside Mirror and Copy to flight; the stores grouped by kind, each with its
     * weight, drag and how many a station takes, and a + that adds one wherever it fits; and the totals with Cancel and
     * Apply. Never a store the jet cannot carry.
     */
    @Composable
    private fun Picker(u: Units, d: Dims, overlay: Boolean, modifier: Modifier) {
        @Suppress("UNUSED_VARIABLE") val r = rev
        Column(modifier, verticalArrangement = Arrangement.spacedBy(u.dp(if (d == Dims.PANEL) 3f else 6f))) {
            if (overlay) Strip(u, d)
            // the search box and the kinds this jet carries
            Row(Modifier.fillMaxWidth().height(u.dp(d.bar)), verticalAlignment = Alignment.CenterVertically) {
                SearchBox(u, d, Modifier.width(u.dp(if (d == Dims.PANEL) 150f else 200f)).fillMaxHeight())
                Spacer(Modifier.width(u.dp(6f)))
                val present = base().map { groupOf(it.first) }.toSet()
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                    for (f in Filter.entries) {
                        if (f.group != null && f.group !in present && filter != f) continue
                        Chip(f.label, filter == f, u, d, "Loadout/Filter/${f.name}") { pickFilter(f) }
                        Spacer(Modifier.width(u.dp(4f)))
                    }
                }
            }
            // what is picked and what to do, with Mirror and Copy to flight at the end; on a narrow screen those two
            // get a line of their own, so the line that says what to do has the width
            @Composable
            fun toggles() {
                Chip((if (both) "✓ " else "") + "Mirror", both, u, d, "Loadout/Both") { toggleBoth() }
                if (l.aircraftCount > 1) {
                    Spacer(Modifier.width(u.dp(4f)))
                    Chip("Copy to flight", false, u, d, "Loadout/Copy") { copyToFlight() }
                }
            }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val narrow = constraints.maxWidth / u.k < 640f
                Column(verticalArrangement = Arrangement.spacedBy(u.dp(6f))) {
                    if (narrow) Row(Modifier.fillMaxWidth().height(u.dp(d.bar)), verticalAlignment = Alignment.CenterVertically) {
                        Text(SEAT_SHORT[seat], color = MUTED, fontSize = u.sp(d.small), maxLines = 1, modifier = Modifier.weight(1f))
                        toggles()
                    }
                    Row(Modifier.fillMaxWidth().height(u.dp(d.bar)), verticalAlignment = Alignment.CenterVertically) {
                        Context(u, d, Modifier.weight(1f).fillMaxHeight())
                        if (!narrow) { Spacer(Modifier.width(u.dp(6f))); toggles() }
                    }
                }
            }
            StoreList(shown(), u, d, Modifier.fillMaxWidth().weight(1f))
            Totals(u, d, buttons = true, modifier = Modifier.fillMaxWidth().height(u.dp(d.bar + 6f)))
        }
    }

    /** The search box: a store whose name has the text (case, spaces and dashes aside); ✕ empties it. */
    @Composable
    private fun SearchBox(u: Units, d: Dims, modifier: Modifier) {
        val shape = RoundedCornerShape(u.dp(4f))
        Row(
            modifier.clip(shape).background(Color.White).border(u.dp(1f), if (query.isEmpty()) EDGE else BLUE, shape).padding(horizontal = u.dp(6f)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) Text("Search stores", color = FAINT, fontSize = u.sp(d.font), maxLines = 1)
                androidx.compose.foundation.text.BasicTextField(
                    value = query, onValueChange = { search(it) }, singleLine = true,
                    textStyle = TextStyle(color = INK, fontSize = u.sp(d.font)),
                    modifier = Modifier.fillMaxWidth().plannerProbe("Loadout/Search"),
                )
            }
            if (query.isNotEmpty()) Text(
                "✕", color = MUTED, fontSize = u.sp(d.font),
                modifier = Modifier.clickable { search("") }.padding(start = u.dp(4f)).plannerProbe("Loadout/SearchClear"),
            )
        }
    }

    /**
     * The line over the list: the picked station with − / + and Clear station; or the picked store, its figures and the
     * stations that take it; or, with neither, what happened last or what to do.
     */
    @Composable
    private fun Context(u: Units, d: Dims, modifier: Modifier) {
        @Suppress("UNUSED_VARIABLE") val rv = rev   // drawn again on every change: what it shows is not state itself
        val hp = station
        val a = armed
        val news = status.isNotEmpty()
        val ink = when (if (news) statusKind else HINT) { BAD -> RED; DONE -> GREEN_INK; else -> MUTED }
        Row(modifier, verticalAlignment = Alignment.CenterVertically) {
            if (hp != null) {
                val load = l.seats[seat][hp]
                Text(
                    "Station ${hpLabel(hp)}" + (load?.let { ": ${it.count} × ${name(it.key)}" } ?: ": empty"),
                    color = INK, fontSize = u.sp(d.font), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(u.dp(6f)))
                if (load != null) {
                    Button("−", u, d, "Loadout/Sel/Minus") { stepPicked(-1) }
                    Spacer(Modifier.width(u.dp(3f)))
                    Button("+", u, d, "Loadout/Sel/Plus") { stepPicked(1) }
                    Spacer(Modifier.width(u.dp(6f)))
                    Button("Clear station", u, d, "Loadout/Sel/Clear", danger = true) { clearPicked() }
                    Spacer(Modifier.width(u.dp(3f)))
                }
                Button("Done", u, d, "Loadout/Sel/Done") { guard { station = null; say("", HINT) } }
                Spacer(Modifier.width(u.dp(8f)))
            } else if (a != null) {
                val w = l.weapons[a]
                Text(name(a), color = BLUE, fontSize = u.sp(d.font), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(u.dp(6f)))
                Button("Let go", u, d, "Loadout/Sel/Drop") { guard { armed = null; say("", HINT) } }
                Spacer(Modifier.width(u.dp(8f)))
                if (!news) {
                    val st = fitsOn(a)
                    val facts = listOfNotNull(w?.let { lbs(it) }?.let { "${it.toInt()} lb" }, w?.let { dragOf(it) }?.let { "DI ${fmt(it)}" }).joinToString(" · ")
                    FitText(
                        (if (facts.isEmpty()) "" else "$facts — ") + "tap a lit station: " + st.joinToString(", ") { "${hpLabel(it)} (${l.max(it, a)})" },
                        u.px(d.font), u.px(d.small * 0.8f), GREEN_INK, modifier = Modifier.weight(1f).fillMaxHeight().plannerProbe("Loadout/Status"),
                    )
                    return@Row
                }
            }
            FitText(
                if (news) status else hint(), u.px(d.font), u.px(d.small * 0.8f), ink, bold = news && statusKind == BAD,
                modifier = Modifier.weight(1f).fillMaxHeight().plannerProbe("Loadout/Status"),
            )
        }
    }

    private fun hint(): String {
        val hp = station
        return if (hp != null) "Tap a store to hang a full load of it here" + (acrossOf(hp)?.takeIf { both && l.has(it) }?.let { " and on ${hpLabel(it)}" } ?: "") + "."
        else "Pick a store, then a lit station on the jet — or a station, then a store. + adds one where it fits." +
            (if (missionLoaded) " Apply changes the card and the figures, not BMS's own loadout." else "")
    }

    /** The totals of the aircraft shown (always in sight), and Cancel and Apply (WDP's Cancel and OK). */
    @Composable
    private fun Totals(u: Units, d: Dims, buttons: Boolean, modifier: Modifier, big: Boolean = false) {
        @Suppress("UNUSED_VARIABLE") val rv = rev   // drawn again on every change: what it shows is not state itself
        val tot = l.totals(seat)
        val fuel = intFuel + tot.extFuel
        val gross = empty + tot.loadWeight + fuel
        val font = if (big) 18f else d.font
        Row(
            modifier.clip(RoundedCornerShape(u.dp(4f))).background(FACTS_BG).padding(horizontal = u.dp(6f)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
                FitText(
                    SEAT_SHORT[seat] + " · stores ${num(tot.loadWeight)} lb · drag ${tot.totalDrag} · fuel ${num(fuel)} lb · gross ${num(gross)} / ${num(maxWeight)} lb" +
                        (if (gross > maxWeight) " — over the maximum" else ""),
                    u.px(font), u.px(d.small * 0.8f), if (gross > maxWeight) RED else INK, bold = true,
                    // by finger the totals may take two lines rather than lose their end
                    maxLines = if (d == Dims.FINGER) 2 else 1,
                    modifier = Modifier.fillMaxWidth().fillMaxHeight().plannerProbe("Loadout/Totals"),
                )
            }
            if (buttons) {
                Spacer(Modifier.width(u.dp(6f)))
                Button("Cancel", u, d, "Loadout/Cancel", big = big) { onClick("btnCancel") }
                Spacer(Modifier.width(u.dp(4f)))
                Button("Apply", u, d, "Loadout/Apply", primary = true, big = big) { onClick("btnOK") }
            }
        }
    }

    /** One line of the list: a kind's heading, or a store with how many one station takes of it. */
    private sealed class Line {
        class Head(val group: Group, val count: Int) : Line()
        class Item(val w: Weapon, val max: Int) : Line()
    }

    /**
     * The stores grouped by kind under a heading each, in as few columns as hold them all without a scroll bar (the
     * window: never one row wider than reads well; over the window: one column on a phone), scrolling only where even
     * the most columns do not hold them.
     */
    @Composable
    private fun StoreList(list: List<Pair<Weapon, Int>>, u: Units, d: Dims, modifier: Modifier) {
        @Suppress("UNUSED_VARIABLE") val rv = rev   // drawn again on every change: what it shows is not state itself
        BoxWithConstraints(modifier) {
            if (list.isEmpty()) {
                Text(
                    if (l.stores.isEmpty()) "The $type has no hardpoints in Falcon BMS's aircraft data: it carries no stores."
                    else if (query.isNotEmpty()) "No store here has \"$query\" in its name."
                    else if (filter == Filter.ON) "Nothing on this aircraft yet." else "No store of this kind fits " + (station?.let { "station ${hpLabel(it)}" } ?: "this jet") + ".",
                    color = FAINT, fontSize = u.sp(d.font), fontStyle = FontStyle.Italic, modifier = Modifier.padding(u.dp(4f)),
                )
                return@BoxWithConstraints
            }
            val lines = ArrayList<Line>()
            for ((g, items) in list.groupBy { groupOf(it.first) }) {
                lines += Line.Head(g, items.size)
                items.forEach { lines += Line.Item(it.first, it.second) }
            }
            fun h(line: Line) = if (line is Line.Head) d.head else d.row
            val wU = constraints.maxWidth / u.k
            val hU = constraints.maxHeight / u.k
            val total = lines.sumOf { h(it).toDouble() }.toFloat()
            val maxCols = (wU / d.minCol).toInt().coerceIn(1, 6)
            // the window's list is never stretched to one line across 1,000 pixels: at least two columns there
            val minCols = if (d == Dims.PANEL) minOf(2, maxCols) else 1
            var cols = minCols
            while (cols < maxCols && total / cols > hU - d.row) cols++
            // lines into columns, each about total / cols tall; a heading never ends a column
            val cap = total / cols
            val columns = ArrayList<MutableList<Line>>().apply { add(ArrayList()) }
            var used = 0f
            for ((i, line) in lines.withIndex()) {
                val last = columns.last()
                val next = lines.getOrNull(i + 1)
                val need = h(line) + if (line is Line.Head && next != null) h(next) else 0f
                if (last.isNotEmpty() && used + need > cap + 0.5f && columns.size < cols) { columns.add(ArrayList()); used = 0f }
                columns.last().add(line)
                used += h(line)
            }
            val onJet = l.seats[seat].values.groupBy { it.key }.mapValues { e -> e.value.sumOf { it.count } }
            val here = station?.let { l.seats[seat][it]?.key }
            Row(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(u.dp(6f))) {
                for (c in 0 until cols) Column(Modifier.weight(1f)) {
                    for (line in columns.getOrNull(c).orEmpty()) when (line) {
                        is Line.Head -> Heading(line, u, d)
                        is Line.Item -> StoreRow(line.w, line.max, onJet[line.w.key] ?: 0, line.w.key == here, u, d)
                    }
                }
            }
        }
    }

    @Composable
    private fun Heading(h: Line.Head, u: Units, d: Dims) {
        @Suppress("UNUSED_VARIABLE") val rv = rev   // drawn again on every change: what it shows is not state itself
        Row(
            Modifier.fillMaxWidth().height(u.dp(d.head)).drawBehind {
                drawLine(h.group.color.copy(alpha = 0.55f), androidx.compose.ui.geometry.Offset(0f, size.height - 1f),
                    androidx.compose.ui.geometry.Offset(size.width, size.height - 1f), maxOf(1f, u.px(1f)))
            },
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(h.group.label.uppercase(), color = h.group.color, fontSize = u.sp(d.small), fontWeight = FontWeight.Bold, maxLines = 1)
            Spacer(Modifier.width(u.dp(4f)))
            Text("${h.count}", color = FAINT, fontSize = u.sp(d.small), maxLines = 1)
        }
    }

    /**
     * A store: its kind's tag, its name (bold when on the aircraft), how many are on it, its weight and drag, and how
     * many a station takes; a tap picks it (or, a station picked, hangs it there), and **+** adds one where it fits.
     */
    @Composable
    private fun StoreRow(w: Weapon, max: Int, onJet: Int, isHere: Boolean, u: Units, d: Dims) {
        @Suppress("UNUSED_VARIABLE") val rv = rev   // drawn again on every change: what it shows is not state itself
        val picked = armed == w.key
        val g = groupOf(w)
        val shape = RoundedCornerShape(u.dp(3f))
        Row(
            Modifier.fillMaxWidth().height(u.dp(d.row)).padding(vertical = u.dp(if (d == Dims.PANEL) 0.5f else 2f)).clip(shape)
                .background(if (picked || isHere) PICKED_BG else Color.Transparent)
                .border(u.dp(1f), if (picked || isHere) BLUE else Color.Transparent, shape)
                .clickable { tapStore(w.key) }
                .plannerProbe("Loadout/Store/${w.key}")
                .wdpTip("planner/Loadout/Store/${w.key}", storeTip(w), touch = true),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.width(u.dp(d.tag)).fillMaxHeight().padding(u.dp(1f)).clip(RoundedCornerShape(u.dp(2f))).background(g.color),
                contentAlignment = Alignment.Center,
            ) { Text(g.tag, color = Color.White, fontSize = u.sp(d.small * 0.85f), fontWeight = FontWeight.Bold, maxLines = 1) }
            Spacer(Modifier.width(u.dp(4f)))
            Text(
                w.name, color = INK, fontSize = u.sp(d.font), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                fontWeight = if (onJet > 0 || picked) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(u.dp(4f)))
            if (onJet > 0) Text("$onJet on ", color = BLUE, fontSize = u.sp(d.small), fontWeight = FontWeight.Bold, maxLines = 1)
            val facts = listOfNotNull(lbs(w)?.let { "${it.toInt()} lb" }, dragOf(w)?.let { "DI ${fmt(it)}" }, "×$max").joinToString(" · ")
            Text(facts, color = MUTED, fontSize = u.sp(d.small), maxLines = 1, softWrap = false)
            if (station == null) {
                Spacer(Modifier.width(u.dp(4f)))
                Box(
                    Modifier.size(u.dp(d.row - 2f)).clip(RoundedCornerShape(u.dp(3f))).background(BUTTON_BG).border(u.dp(1f), EDGE, RoundedCornerShape(u.dp(3f)))
                        .clickable { addOne(w.key) }.plannerProbe("Loadout/Add/${w.key}"),
                    contentAlignment = Alignment.Center,
                ) { Text("+", color = INK, fontSize = u.sp(d.font), fontWeight = FontWeight.Bold, maxLines = 1) }
            }
        }
    }

    /**
     * The jet's stations as a strip of buttons, left to right as the picture has them (over the window, where the
     * cards on the picture are too small for a finger): the store and the count on each; the picked station blue, the
     * stations that take the picked store lit green and the others stepped back.
     */
    @Composable
    private fun Strip(u: Units, d: Dims) {
        @Suppress("UNUSED_VARIABLE") val rv = rev   // drawn again on every change: what it shows is not state itself
        val a = armed
        Row(Modifier.fillMaxWidth().height(u.dp(if (d == Dims.FINGER) 62f else 50f)), horizontalArrangement = Arrangement.spacedBy(u.dp(2f))) {
            for (hp in visualOrder()) {
                val load = l.seats[seat][hp]
                val picked = station == hp
                val fits = station == null && a != null && l.max(hp, a) > 0
                val faded = station == null && a != null && !fits
                val shape = RoundedCornerShape(u.dp(4f))
                Column(
                    Modifier.weight(1f).fillMaxHeight().alpha(if (faded) 0.4f else 1f).clip(shape)
                        .background(if (picked) PICKED_BG else if (fits) GREEN_BG else TILE_BG)
                        .border(u.dp(if (picked || fits) 2f else 1f), if (picked) BLUE else if (fits) GREEN_INK else TILE_EDGE, shape)
                        .clickable { guard { tapStation(hp) } }.plannerProbe("Loadout/Strip/${hpLabel(hp)}"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(hpLabel(hp), color = if (picked) BLUE else INK, fontSize = u.sp(d.small), fontWeight = FontWeight.Bold, maxLines = 1)
                    Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal = u.dp(1f)), contentAlignment = Alignment.Center) {
                        if (load != null) FitText(
                            LoadoutWindow.shortName(name(load.key)) + "\n" + load.count + "/" + l.max(hp, load.key),
                            u.px(d.small), u.px(7f), INK, maxLines = 2, align = TextAlign.Center,
                        ) else Text("—", color = FAINT, fontSize = u.sp(d.small))
                    }
                }
            }
        }
    }

    /** A station card's tooltip (WdpTips): what hangs there, on what, and what the card's buttons do (WDP's WPN ID tip). */
    private fun stationTip(hp: Int): String {
        val load = l.seats[seat][hp]
        val w = load?.let { l.weapons[it.key] } ?: return "Station ${hpLabel(hp)}: empty. Tap it to pick it, then a store in the list to hang a full load of it here (or pick the store first, then this station)."
        val rack = l.rack(hp, w.key)?.let { " on a $it" }.orEmpty()
        return "Station ${hpLabel(hp)}: ${load.count} × ${w.name}$rack (${kindWords(w)}). Tap to pick this station; − and + take one off or put one on, ✕ empty clears it."
    }

    /** A store's tooltip (WdpTips): what it is, its figures, where it goes, and what a tap does. */
    private fun storeTip(w: Weapon): String =
        "${w.name}, ${kindWords(w)}: ${facts(w)}. ${where(w)}. Tap: with a station picked, a full load of it there; with none, " +
            "the store is picked and the stations that take it light up. + adds one wherever it fits."

    private fun facts(w: Weapon): String = listOfNotNull(
        lbs(w)?.let { "${it.toInt()} lb each" },
        dragOf(w)?.let { "drag index ${fmt(it)} each" },
        w.strength?.takeIf { w.category == "FUEL_TANK" && it > 0 }?.let { "${it.toInt()} lb of fuel" },
        w.rangeKm?.takeIf { it > 0 }?.let { "reach ${(it / 1.852).toInt()} nm" },
        w.guidance.takeIf { it.isNotEmpty() }?.joinToString("/")?.let { "guidance $it" },
    ).joinToString(" · ")

    private fun where(w: Weapon): String {
        val st = (1..11).filter { l.max(it, w.key) > 0 }.sortedBy { PerformanceLoadout.ORDER.indexOf(it) }
        val on = l.seats[seat].filter { it.value.key == w.key }
        val fitsTxt = if (st.isEmpty()) "No station of this jet takes it" else "Goes on " + st.sortedWith(compareBy { if (f16 && it >= 10) 5.5 else it.toDouble() })
            .joinToString(", ") { "${hpLabel(it)} (${l.max(it, w.key)})" }
        val onTxt = if (on.isEmpty()) "" else " · on this aircraft: " + on.entries.joinToString(", ") { "${it.value.count} on ${hpLabel(it.key)}" }
        return fitsTxt + onTxt
    }

    /** Another aircraft as the app's aircraft reference pictures it, over WDP's F-16 (picLoadout's box, 650 × 250). */
    @Composable
    private fun AircraftPicture(pic: String) {
        BoxWithConstraints(Modifier.fillMaxSize().background(Color.White)) {
            val u = Units(constraints.maxWidth / 650f)
            val img = photo(pic, 1)
            if (img != null) Image(img, type, Modifier.fillMaxSize().padding(u.dp(6f)), contentScale = ContentScale.Fit)
            Text(
                type, color = MUTED, fontSize = u.sp(9f), maxLines = 1,
                modifier = Modifier.align(Alignment.BottomEnd).padding(horizontal = u.dp(6f), vertical = u.dp(3f)),
            )
        }
    }

    // ================================================================ small pieces

    /** A picture from the app's reference (`img/tacref`); null while it loads, or where the app has none. */
    @Composable
    private fun photo(pic: String?, sample: Int): ImageBitmap? {
        val path = pic?.trim()?.takeIf { it.isNotEmpty() && !it.equals("NO PICTURE", ignoreCase = true) }?.let { Repo.tacrefImagePath(it) }
        val bmp by produceState<android.graphics.Bitmap?>(null, path, sample) { value = path?.let { Repo.bitmap(it, sample) } }
        val b = bmp
        return remember(b) { b?.asImageBitmap() }
    }

    /** Pixels per designer pixel, and the sizes the window's own drawing is given in designer pixels. */
    private class Units(val k: Float) {
        @Composable fun dp(designer: Float): Dp = with(LocalDensity.current) { (designer * k).toDp() }
        @Composable fun sp(designer: Float): TextUnit = with(LocalDensity.current) { (designer * k).toSp() }
        fun px(designer: Float): Float = designer * k
    }

    @Composable
    private fun SmallButton(text: String, u: Units, probe: String, onClick: () -> Unit) {
        Box(
            Modifier.size(u.dp(17f), u.dp(17f)).clip(RoundedCornerShape(u.dp(2f))).background(BUTTON_BG).border(u.dp(1f), EDGE, RoundedCornerShape(u.dp(2f)))
                .clickable { onClick() }.plannerProbe(probe),
            contentAlignment = Alignment.Center,
        ) { Text(text, color = INK, fontSize = u.sp(12f), fontWeight = FontWeight.Bold, maxLines = 1) }
    }

    @Composable
    private fun Chip(text: String, on: Boolean, u: Units, d: Dims, probe: String, onClick: () -> Unit) {
        val shape = RoundedCornerShape(u.dp(d.bar / 2f))
        Box(
            Modifier.height(u.dp(d.bar)).clip(shape).background(if (on) BLUE else Color.White).border(u.dp(1f), if (on) BLUE else EDGE, shape)
                .clickable { onClick() }.plannerProbe(probe).padding(horizontal = u.dp(d.bar * 0.45f)),
            contentAlignment = Alignment.Center,
        ) { Text(text, color = if (on) Color.White else INK, fontSize = u.sp(d.font), maxLines = 1) }
    }

    /** A flat button of the picker: Cancel, Apply (filled), Clear station (red), −, +. */
    @Composable
    private fun Button(
        text: String, u: Units, d: Dims, probe: String,
        primary: Boolean = false, danger: Boolean = false, big: Boolean = false, onClick: () -> Unit,
    ) {
        val shape = RoundedCornerShape(u.dp(4f))
        val h = if (big) 44f else d.bar
        Box(
            Modifier.height(u.dp(h)).widthIn(min = u.dp(h * if (text.length <= 1) 1f else 2.2f)).clip(shape)
                .background(if (primary) BLUE else if (danger) CLEAR_BG else BUTTON_BG)
                .border(u.dp(1f), if (primary) BLUE else if (danger) RED.copy(alpha = 0.4f) else EDGE, shape)
                .clickable { onClick() }.plannerProbe(probe).padding(horizontal = u.dp(h * 0.35f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text, color = if (primary) Color.White else if (danger) RED else INK, fontSize = u.sp(if (big) 18f else d.font),
                fontWeight = if (primary || text.length <= 1) FontWeight.Bold else FontWeight.Normal, maxLines = 1,
            )
        }
    }

    companion object {
        private const val HINT = 0
        private const val DONE = 1
        private const val BAD = 2

        /** `fclsLoadout.CloseForm`'s question, word for word (WDP's "\r\n" is the box's line break). */
        const val MISSION_QUESTION = "Loadout will not be changed for your mission, only on the datacard.\nDo you want to continue?"

        /** a card's size in designer pixels: the hardpoints under WDP's picture are 62 to 79 apart */
        private const val CARD_W = 58f
        private const val CARD_H = 134f

        val SEATS = listOf("rbnLead", "rbnWingman", "rbnElemLead", "rbnElemWingman")

        /**
         * WDP's Ok and Cancel at the window's top right, taken off it: the picker's bar at the foot (and the large
         * Choose stores panel's) has **Cancel** and **Apply**, which do what they did, and the Planner has one place per
         * action. The window's hidden list ([WdpDialog.hidden]), so `--wdpclicktest` checks they never show.
         */
        val REMOVED = listOf("btnOK", "btnCancel")
        private val SEAT_NAMES = listOf("The lead", "The wingman", "The element lead", "The element wingman")
        private val SEAT_SHORT = listOf("#1 Lead", "#2 Wingman", "#3 Element lead", "#4 Element wingman")

        /** A store's kind in the list (the app's weapon categories, finer than WDP's three). */
        fun groupOf(w: Weapon): Group = when (w.category) {
            "AAM_IR", "AAM_RADAR" -> Group.AA
            "AGM", "ARM", "ANTI_SHIP" -> Group.AGM
            "BOMB_LGB", "BOMB_GUIDED" -> Group.GUIDED
            "BOMB_GP", "BOMB_CLUSTER", "BOMB_INCENDIARY", "BOMB_NUCLEAR", "BOMB_SPECIAL" -> Group.BOMBS
            "ROCKETS" -> Group.ROCKETS
            "FUEL_TANK" -> Group.TANKS
            "ECM_POD", "CM_POD" -> Group.ECM
            else -> Group.PODS
        }

        /** A name as the search box matches it: lower case, letters and digits only ("gbu12" finds "GBU-12 Paveway II"). */
        private fun squash(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

        /** 36473 as "36,473". */
        private fun num(n: Int): String {
            val s = kotlin.math.abs(n).toString()
            val out = StringBuilder()
            for ((i, ch) in s.withIndex()) { if (i > 0 && (s.length - i) % 3 == 0) out.append(','); out.append(ch) }
            return (if (n < 0) "-" else "") + out
        }

        /** WDP's labels and grid that the window draws itself (its store names, counts, pylon and rack marks, the lists). */
        private val DRAWN_HERE = listOf("cboType", "lblLoadout", "chbTgp", "chbHTS", "lblAG", "lblECM", "lblFLL") +
            (1..11).flatMap { listOf("lblWpn$it", "lblAmount$it", "lblPylon$it", "lblRack$it") }

        /** The label an F-16 hardpoint's card hangs from: WDP's number for it under the picture. */
        fun anchor(hp: Int): String = when (hp) { 10 -> "lblHpt5L"; 11 -> "lblHpt5R"; else -> "lblHpt$hp" }

        /** WDP's hardpoint numbers under the picture, left to right (fclsLoadout's designer: 321 … 1017). */
        private val ANCHORS = listOf("lblHpt9", "lblHpt8", "lblHpt7", "lblHpt6", "lblHpt5L", "lblHpt5", "lblHpt5R", "lblHpt4", "lblHpt3", "lblHpt2", "lblHpt1")

        /**
         * A rack a pilot knows by name (TER, MER, SUU20, APU470 …) is written on the card; BMS's model names for a
         * pylon ("f16-center-tank", "LAU-7-aim-gap") say nothing to a pilot and are left off, and so is "TANK".
         */
        private fun readableRack(r: String): Boolean = r.length <= 10 && r.none { it.isLowerCase() || it == '_' } && r != "TANK"

        private fun kindColor(cat: Int) = when (cat) {
            PerformanceLoadout.AA -> Color(0xFF2F6FB5)
            PerformanceLoadout.AG -> Color(0xFFB4621A)
            PerformanceLoadout.TANK -> Color(0xFF3C8D4A)
            PerformanceLoadout.ECM -> Color(0xFF7A4DA8)
            else -> Color(0xFF66707C)
        }

        private fun kindWords(w: Weapon): String = when (w.category) {
            "AAM_RADAR" -> "air-to-air missile, radar"
            "AAM_IR" -> "air-to-air missile, infrared"
            "AGM" -> "air-to-ground missile"
            "ARM" -> "anti-radiation missile"
            "ANTI_SHIP" -> "anti-ship missile"
            "ROCKETS" -> "rockets"
            "BOMB_LGB" -> "laser-guided bomb"
            "BOMB_GUIDED" -> "guided bomb"
            "BOMB_GP" -> "general-purpose bomb"
            "BOMB_CLUSTER" -> "cluster bomb"
            "BOMB_INCENDIARY" -> "incendiary bomb"
            "BOMB_NUCLEAR" -> "nuclear bomb"
            "BOMB_SPECIAL" -> "special bomb"
            "FUEL_TANK" -> "external fuel tank"
            "ECM_POD" -> "ECM pod"
            "TARGETING_POD" -> "targeting pod"
            "RECON_POD" -> "reconnaissance pod"
            "AVIONICS_POD" -> "avionics pod"
            "CM_POD" -> "countermeasures pod"
            else -> w.category.lowercase().replace('_', ' ')
        }

        private fun fmt(d: Double): String = if (d == kotlin.math.floor(d)) d.toInt().toString() else ((kotlin.math.round(d * 10)) / 10.0).toString()

        private val INK = Color(0xFF1B1B1B)
        private val MUTED = Color(0xFF5A6068)
        private val FAINT = Color(0xFF8A8F96)
        private val BLUE = Color(0xFF0078D7)
        private val PICKED_BG = Color(0xFFDCEBFA)
        private val FOCUS_BG = Color(0xFFF0F6FD)
        private val GREEN_BG = Color(0xFFD9F2DC)
        private val GREEN_INK = Color(0xFF1E7B34)
        private val RED = Color(0xFFC42B1C)
        private val CLEAR_BG = Color(0xFFFBE9E7)
        private val HEAD_BG = Color(0xFFE6E8EB)
        private val EDGE = Color(0xFFB4B9BF)
        private val TILE_BG = Color(0xFFF7F8FA)
        private val TILE_EDGE = Color(0xFFD9DDE2)
        private val BUTTON_BG = Color(0xFFEDEFF2)
        private val FACTS_BG = Color(0xFFF1F3F6)
    }
}

/**
 * Text at the largest size up to [maxPx] at which it fits its box in [maxLines] lines, down to [minPx] (then cut
 * with an ellipsis): a store's name on a card, a line of news that is longer than usual.
 */
@Composable
private fun FitText(
    text: String,
    maxPx: Float,
    minPx: Float,
    color: Color,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
    align: TextAlign = TextAlign.Start,
    bold: Boolean = false,
) {
    BoxWithConstraints(modifier, contentAlignment = when (align) { TextAlign.Center -> Alignment.Center; TextAlign.End -> Alignment.CenterEnd; else -> Alignment.CenterStart }) {
        val measurer = rememberTextMeasurer()
        val density = LocalDensity.current
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        val weight = if (bold) FontWeight.Bold else FontWeight.Normal
        val px = remember(text, w, h, maxPx, minPx, maxLines, bold) {
            // wrapped text must break between words, not inside one ("Sidewinde / r"): the longest word fits a line
            // (a line may break after a slash, as "AN/ | ALQ-184" does)
            val words = text.split(' ', '/').filter { it.isNotEmpty() }
            fun wordsFit(size: Float): Boolean = maxLines <= 1 || words.all { word ->
                measurer.measure(word, TextStyle(fontSize = with(density) { size.toSp() }, fontWeight = weight), maxLines = 1, softWrap = false).size.width <= w
            }
            var f = maxPx
            while (f > minPx) {
                val r = measurer.measure(
                    text, TextStyle(fontSize = with(density) { f.toSp() }, fontWeight = weight),
                    maxLines = maxLines, softWrap = maxLines > 1,
                    constraints = Constraints(maxWidth = w.coerceAtLeast(1)),
                )
                if (!r.hasVisualOverflow && (h == Constraints.Infinity || r.size.height <= h) && wordsFit(f)) break
                f -= maxOf(0.5f, maxPx / 24f)
            }
            maxOf(f, minPx)
        }
        Text(
            text, color = color, fontSize = with(density) { px.toSp() }, fontWeight = weight, maxLines = maxLines,
            softWrap = maxLines > 1, overflow = TextOverflow.Ellipsis, textAlign = align, lineHeight = with(density) { (px * 1.12f).toSp() },
        )
    }
}
