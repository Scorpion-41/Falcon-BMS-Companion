package com.bmscompanion.app.ui.screens.wdp

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.bmscompanion.app.data.mission.Briefing
import com.bmscompanion.app.data.wdp.DataCardPlan

/**
 * What the DataCard and the Performance page tell each other, as WDP's two controls do through its main form.
 *
 * In WDP both are fields of one window and write into each other directly. Here each page is its own wiring, so the
 * Planner joins them here, in both directions:
 *
 * - **Card → Performance** (`FillFlightplan`, `CreateAtis`, `btnToSpecSel_Click`): the flight plan's second row sets
 *   the cruise altitude; the take-off spec chosen on the card sets the pitch and the power; the card's take-off
 *   weather sets the wind, the temperature and the QNH; the card's departure runway sets the page's runway when both
 *   pages have the same field. Each is followed by the page's `ProgramFlow`, as in WDP.
 * - **Performance → Card** (`Weights`, `DoCalculations`, `SetFuel` → `FillFuel`): every recalculation puts the
 *   take-off figures on the card — gross weight (red when over the maximum), drag, rotation and refusal speeds
 *   (refusal red when it is not above rotation), the MIL power setting, the MIL climb schedule, the take-off spec,
 *   and the take-off and block fuel. A flight that is not an F-16 gets no speeds, as WDP clears them.
 *
 * **Fixed (D38):** WDP's card never shows the drag. Its only writer is `cntDataCard.FillOrdnance`
 * (`lblDrag.Text = cntPerformance.lblDrag_Val.Text`), and nothing in WDP 3.7.24 calls `FillOrdnance`
 * (`scratchpad/wdpsrc`: the method is defined at `cntDataCard.cs:39500` and referenced nowhere else), so the card's
 * Drag stays at "0" or blank whatever the loadout. Here it follows the page's drag like the gross weight does.
 *
 * **The flight's type and loadout** (`FillAutoFlight`, `SelectPilotSeat`): the Performance page takes them from the
 * mission's own flight — the printed briefing's, or, with a save's flight open (Open mission…), the briefing the PC
 * made of that flight, which names its aircraft and its ordnance (WDP then locks the type, `fclsMain.cs` l.19590; the
 * page keeps it open, so another F-16 can be planned against the mission). So a picked flight's type reaches the page with the mission itself;
 * the card never changes to another flight of the package (D86), and our own flight's callsign there opens this
 * page's Loadout window ([DataCardWiring.openLoadout]), whose OK writes the card's Config rows as WDP's does. The seat picked with the flight
 * is the card's seat ([WdpMission.seat]), and a seat picked on the card afterwards is the page's too, as WDP's
 * `SelectPilotSeat` → `SetLoadout` puts that aircraft's stores and its own fuel from the save
 * (`fuel_initial[SelPilotSeat]`) on the page, for a printed briefing as much as for a save's flight; the Loadout
 * window picks one as well.
 *
 * **The strike steerpoint** WDP hands to the attack pages (`CreateFlightplan` → `numWaypoint`, `STPTChange`) is
 * [WdpMission.defaultStpt]: each attack page sets its TGT STPT box to it once per mission or flight, and the card
 * names the first two strike steerpoints' targets ([WdpMission.cardTargets]). There is no target bar.
 */
object WdpHandOff {
    /**
     * Counts every hand-off into the card. The card's page draws from its plan's labels when it is composed; what the
     * hand-off writes there arrives after the card last changed (the Performance page is prepared after it), so the
     * Planner reads this count to be composed again. Without it the browser showed the card's first zeros.
     */
    var tick by androidx.compose.runtime.mutableIntStateOf(0)
        private set

    /** The take-off weather last handed to the page ([cardToPerformance]): wind, speed, temperature and QNH. */
    private var handed: String? = null
    /** The temperature the page had before any mission's weather reached it: WDP's Setup.ini `Temp`, its own default. */
    private var setupTemp: String? = null

    /** The page has been prepared (its engine tables are in): before that its figures are placeholders, not answers. */
    private fun ready(pp: com.bmscompanion.app.data.wdp.PerformancePlan) = pp.blnLoaded && pp.engines.isNotEmpty()

    /**
     * The card's hooks into the page ([DataCardPlan.pages]): the cruise altitude from the flight plan's second row, and
     * the seat picked on the card.
     */
    fun join(card: DataCardWiring, performance: PerformanceWiring) {
        // the page's own temperature, before any mission's weather is put on it
        if (setupTemp == null) setupTemp = performance.plan.txtTemp.text
        // Reload WX reads its file on the PC after the press: the page takes the file's take-off weather when it lands
        card.weatherChanged = { cardToPerformance(card, performance, card.cardBriefing) }
        // our own flight's callsign on the card opens the page's Loadout window, whose OK writes the card's Config rows
        // (WDP's rbnCallsignN_Click → fclsLoadout → DatacardLabels; D86)
        card.openLoadout = { performance.openLoadout() }
        performance.onLoadoutApplied = { stores -> card.loadoutRows(stores) }
        card.plan.pages = object : DataCardPlan.Pages {
            override fun cruiseAlt(text: String) {
                if (!cruiseAltInto(performance, text)) return
                performance.plan.programFlow()
                performanceToCard(performance, card)
            }

            /** `SelectPilotSeat`'s tail (`SetLoadout`, `ProgramFlow`): that aircraft's stores and fuel, and the figures back. */
            override fun pilotSeat(seat: Int) {
                performance.pilotSeat(seat)
                performanceToCard(performance, card)
            }
        }
    }

    /**
     * Everything the card hands the page when a mission is loaded or its weather is read again: the cruise altitude,
     * the take-off weather and the runway (WDP's `FillFlightplan` and `CreateAtis`), then the page's figures back.
     */
    fun cardToPerformance(card: DataCardWiring, performance: PerformanceWiring, briefing: Briefing?) {
        val pp = performance.plan
        val cp = card.plan
        if (!ready(pp)) return
        cruiseAltInto(performance, cp.text("lblAlt2"))
        // CreateAtis: the take-off weather as whole numbers into the page's boxes, the QNH through QnhHpa — the weather
        // the card itself reads (a weather file's, the save's own, or the printed briefing of the same flight with the
        // save's QNH: fileTakeOff; else the briefing's take-off column: cardBriefing)
        val w = card.fileTakeOff ?: CardWeather.column(card.cardBriefing ?: briefing, 0)
        // Weather that is new to the page (another mission, the save's file read again, Reload WX) sets every box, and
        // what it does not give goes back to the page's own — calm, the standard altimeter and the temperature the
        // page loaded with (Setup.ini's) — rather than staying the last weather's. The same weather again sets only
        // what it gives, and leaves the boxes it does not give as the pilot typed them.
        if (setupTemp == null) setupTemp = pp.txtTemp.text
        val key = listOf(w?.windDir, w?.windKts, w?.tempC, w?.qnhHpa).toString()
        val fresh = key != handed
        handed = key
        // a wind with no direction (a .twx whose heading BMS picks) gives no head or cross wind: planned as calm
        (w?.windDir?.toString() ?: if (fresh) "0" else null)?.let { pp.txtWindDir.set(it) }
        (w?.let { if (it.windDir == null) 0 else it.windKts }?.toString() ?: if (fresh) "0" else null)?.let { pp.txtWindSpd.set(it) }
        (w?.tempC?.toString() ?: if (fresh) setupTemp else null)?.let { pp.txtTemp.set(it) }
        (w?.qnhHpa?.let { DataCardPlanText.qnh(it) } ?: if (fresh) "1013.2" else null)?.let { pp.txtQNH_Hpa.set(it); pp.qnhHpa() }
        runwayInto(card, performance)
        pp.programFlow()
        performanceToCard(performance, card)
    }

    /**
     * After the pilot did something on the card: a take-off spec chosen there goes to the page (`btnToSpecSel_Click`),
     * a departure runway picked there becomes the page's (WDP's `CreateAtis` sets the page's runway), and the card
     * shows the page's figures again — a callsign pick clears them on the card, as WDP's `SelectCallsign` does.
     */
    fun afterCard(name: String, card: DataCardWiring, performance: PerformanceWiring, briefing: Briefing?) {
        val pp = performance.plan
        if (!ready(pp)) return
        var flow = false
        if (name == "btnToSpecSel") {
            val spec = card.plan.text("lblTOSpec")
            Regex("(\\d+)").find(spec)?.groupValues?.get(1)?.let { pp.cboPitch.selectItem(it) }
            pp.cboPower.select(if (spec.contains("MIL", ignoreCase = true)) 1 else 0)
            flow = true
        }
        if (name == "btnWeather") { cardToPerformance(card, performance, briefing); return }
        if (runwayInto(card, performance)) flow = true
        if (flow) pp.programFlow()
        performanceToCard(performance, card)
    }

    /**
     * The page's figures onto the card, as WDP's page writes them into `cntDataCard` on every `ProgramFlow`.
     */
    fun performanceToCard(performance: PerformanceWiring, card: DataCardWiring) {
        val pp = performance.plan
        val cp = card.plan
        if (!ready(pp)) return
        // Weights(): the gross weight, red when the jet is over its maximum
        cp.type("lblGrossWgt", pp.labels["lblGross_Val"].orEmpty())
        colour(cp, "lblGrossWgt", pp.blnOverweight)
        // D38: the drag, which WDP's card never receives
        cp.type("lblDrag", pp.labels["lblDrag_Val"].orEmpty())
        // SetFuel → FillFuel: the take-off and block fuel, right-aligned as WDP pads them
        cp.type("lblTOFuel", padded(pp.intTakeoffFuel))
        cp.type("lblIntFuel", padded(pp.intBlockFuel))
        // DoCalculations: nothing for a flight that is not an F-16
        if (pp.missionIsF16 == false) {
            for (n in listOf("lblTOSpec", "lblRotation", "lblRefusal", "lblMilP", "lblMilClimb")) cp.type(n, "")
            cp.milP = ""
            colour(cp, "lblRefusal", false)
            tick++
            return
        }
        cp.type("lblMilClimb", pp.labels["lblScheduleMil_Val"].orEmpty().replace(" ", ""))
        val pitch = pp.cboPitch.selectedItem?.trim()?.toIntOrNull() ?: 13
        cp.type("lblTOSpec", pitch.toString() + "°/ " + if (pp.cboPower.text == "Full AB") "AB" else "Mil")
        cp.type("lblRotation", pp.labels["lblRotate_Val"].orEmpty())
        cp.type("lblRefusal", pp.labels["lblRefusal_Val"].orEmpty())
        colour(cp, "lblRefusal", pp.blnRefusal)
        // the card prints its own copy of the MIL power setting when it fills the flight (`lblMilP = lblMilP_Val`)
        cp.milP = pp.labels["lblMilP_Val"].orEmpty()
        cp.type("lblMilP", cp.milP)
        tick++
    }

    /** Row 2's altitude as the page's cruise altitude; false when it is not a number (an unknown leg). */
    private fun cruiseAltInto(performance: PerformanceWiring, text: String): Boolean {
        val alt = text.trim().toIntOrNull() ?: return false
        performance.plan.txtCruiseAlt.set(alt.toString())
        return true
    }

    /** The card's departure runway as the page's, when both show the same field; true when it changed. */
    private fun runwayInto(card: DataCardWiring, performance: PerformanceWiring): Boolean {
        val pp = performance.plan
        val dep = card.plan.tblApt[0]
        val rwy = dep.rwy?.takeIf { it.isNotEmpty() } ?: return false
        if (pp.airport?.name == null || !pp.airport?.name.equals(dep.name, ignoreCase = true)) return false
        if (pp.cboRWY.selectedItem == rwy || rwy !in pp.cboRWY.items) return false
        pp.cboRWY.selectItem(rwy)
        return true
    }

    /** WDP's red for a figure that is out of limits; otherwise the designer's colour. */
    private fun colour(cp: DataCardPlan, name: String, red: Boolean) {
        if (red) cp.labels["$name.fore"] = "Red" else cp.labels.remove("$name.fore")
    }

    /** `FillFuel`: a fuel figure padded to five characters, as the card's fixed-width column prints it. */
    internal fun padded(n: Int): String = when {
        n < 1000 -> "   $n"
        n in 1000..9998 -> "  $n"
        n in 10000..99998 -> " $n"
        else -> n.toString()
    }
}

/** How the QNH the card knows is typed into the page's hPa box. */
private object DataCardPlanText {
    fun qnh(hpa: Double): String {
        val tenths = kotlin.math.round(hpa * 10).toLong()
        return (tenths / 10).toString() + "." + (tenths % 10).toString()
    }
}
