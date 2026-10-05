package com.bmscompanion.app.ui.screens.wdp

import com.bmscompanion.app.data.mission.ownFlight
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.bmscompanion.app.data.Aircraft
import com.bmscompanion.app.data.Airport
import com.bmscompanion.app.data.AirportSet
import com.bmscompanion.app.data.ChartRef
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.Theater
import com.bmscompanion.app.data.Weapon
import com.bmscompanion.app.data.airfield.Airfield
import com.bmscompanion.app.data.mission.Briefing
import com.bmscompanion.app.data.wdp.Engines
import com.bmscompanion.app.data.wdp.PerformanceLoadout
import com.bmscompanion.app.data.wdp.PerformancePlan
import com.bmscompanion.app.data.wdp.WdpValues
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The Performance page's behaviour, joined to its layout (`cntPerformance.json`).
 *
 * [PerformancePlan] is Falcas's `cntPerformance` and knows nothing about controls; this hands its labels, boxes,
 * lists and colours to the renderer and turns a pick, a keystroke, a focus change or a press into the handler WDP
 * runs for it. The figures come from the engine code ([Engines]) — WDP's own methods, run as it runs them.
 *
 * What WDP reads from its databases and the campaign, the page takes from the app:
 * - **the airfield**: the mission's departure field, matched as the Briefing page matches it (`departureIn`, which
 *   knows a carrier's home plate from its first word), from the app's airport data for the theater (name, ICAO,
 *   elevation — the ATC file's, else the ground under the runways in BMS's height map —, each runway end with its
 *   length) and BMS's own runway rectangles for the width; the page opens on
 *   the runway most nearly into the briefing's take-off wind, as WDP's DataCard picks it (`PreferedRwy`). Select APT
 *   is WDP's own window over the theater's fields; Charts shows the field as the app's Taxi page draws it (airport
 *   diagram, a parking chart per runway end) and then its instrument charts from the app's Charts;
 * - **the jet**: the briefing's aircraft is picked in Type — which, unlike WDP's, stays open, so any F-16 of WDP's
 *   list can be planned against the mission with the stores carried across, and the mission's jet picked back; the
 *   weights are BMS's own aircraft data of the page's theater (its variant of the jet, with the conformal tanks of a
 *   jet that carries them); the loadout is the briefing's ordnance table, hung on the hardpoints and
 *   totalled in the Loadout window (**Set**, [PerfLoadoutWindow]), where the stores are changed station by station —
 *   each loaded hardpoint with its pylon and rack, whose weight and drag are Falcon BMS 4.38.1's own, and each store
 *   weighing and dragging what the theater's own weapon table says ([PerformanceLoadout]);
 * - **the weather**: the briefing's take-off wind and temperature, typed into the page's own boxes.
 *
 * Setup.ini's `[Performance]` is kept in the app's preferences (read when the page loads, written on every change,
 * as `fclsMain` writes it on exit), so the pitch, power, taxi fuel and cruise altitude a pilot set stay set.
 */
class PerformanceWiring(
    /**
     * where the page loads what it fetches on a pick (another airfield's runway widths). Only the Planner's own page
     * ([WdpSession.performance]) has one, and that is also what makes it hand its figures to the Planner's DataCard
     * by itself ([onFigures]); the headless checks' pages have none.
     */
    private val scope: CoroutineScope? = null,
) : WdpWiring {
    private var version by mutableIntStateOf(0)
    val plan = PerformancePlan()

    private var aircraftList: List<Aircraft> = emptyList()
    private var weapons: Map<String, Weapon> = emptyMap()
    /** Falcon BMS's pylons and racks, the weight and drag each loaded hardpoint adds (`data/wdp/racks.json`) */
    private var racks: PerformanceLoadout.Racks? = null
    private var theater: Theater? = null
    private var airports: AirportSet? = null
    /** BMS's own airfield data for the fields looked at so far: the runway widths and the charts come from it */
    private var fields: Map<Int, Airfield> = emptyMap()
    /** the theater's instrument charts by airport id, as the Airfields section lists them, and the set they are of */
    private var charts: Map<String, List<ChartRef>> = emptyMap()
    private var chartsSet: String? = null
    private var loaded = false
    private var mission: WdpMission? = null
    private var appliedKey: String? = null
    private var savedIni: String? = null

    /** The briefing's loadout for the flight, and the aircraft whose stores the page shows (WDP's pilot seat). */
    private var loadout: PerformanceLoadout? = null
    private var seat = 0

    /**
     * The field the page showed last time (Setup.ini's `Apt`, which WDP writes on exit and never reads back): where the
     * page opens before any mission, rather than on the theater's first field in the alphabet.
     */
    private var rememberedApt: String? = null

    /**
     * Called whenever the page's figures may have changed — a press or an entry on the page, and also what its own
     * windows hand back (the Loadout window's OK, Select APT), which the Planner's page events do not see. The Planner
     * joins it to the DataCard ([WdpHandOff]), so the card's take-off figures are right even when the card is printed
     * or sent without being opened first.
     */
    var onFigures: (() -> Unit)? = null

    /**
     * Told the stores the Loadout window's OK hands over (name and count, in the window's hardpoint order), for the
     * DataCard's Config rows (`fclsLoadout.DatacardLabels`; [WdpHandOff.join]).
     */
    var onLoadoutApplied: ((List<Pair<String, Int>>) -> Unit)? = null

    init {
        val ini = readIni()
        rememberedApt = ini["Apt"]?.trim()?.takeIf { it.isNotEmpty() }
        plan.load(ini)
        plan.onMissionLoadout = { applyMissionLoadout() }
        saveIni()
    }

    // ================================================================ data

    /**
     * Everything the page reads besides the mission: the engines, BMS's aircraft and weapon data, and the airfields
     * and charts of the mission's theater. Then the mission itself, as `fclsMain` runs the page once a theater is in.
     */
    suspend fun prepare(mission: WdpMission?) {
        try {
            if (plan.engines.isEmpty()) plan.engines = Engines.load()
            if (aircraftList.isEmpty()) aircraftList = Repo.aircraft()
            if (weapons.isEmpty()) weapons = Repo.weaponMap()
            if (racks == null) racks = PerformanceLoadout.loadRacks()
            // the list's item for the briefing's jet weighs what the briefing's jet does in BMS's data
            plan.aircraftData = { type -> acData(plan.missionAircraft?.takeIf { plan.missionItem() == type } ?: type) }
            // the airfields once, and again only when BMS moves to another theater
            if (airports == null || (mission?.theater != null && mission.theater.airportSet != theater?.airportSet)) loadAirports(mission)
            // the theater's instrument charts, as the Airfields section lists them: the Charts window shows them after
            // the field's own diagram and parking charts
            theater?.airportSet?.takeIf { it != chartsSet }?.let { set ->
                charts = runCatching { Repo.charts(set) }.getOrNull().orEmpty()
                chartsSet = set
            }
            // the field the page will open on, read before the page is filled so its runway widths are there
            val t = theater
            val a = fieldFor(mission?.briefing, mission?.flight)
            if (t != null && a != null && a.id !in fields) airfield(t, a)?.let { fields = fields + (a.id to it) }
            loaded = true
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            // the Planner moved on to a newer mission (the theater arrived, a target was picked) and prepares again for
            // that one: not a failure to report — the browser showed "The coroutine scope left the composition" here
            throw e
        } catch (e: Exception) {
            WdpDialogs.message("Performance", "The performance data could not be read: " + (e.message ?: e::class.simpleName))
        }
        if (mission != null) onMission(mission) else if (appliedKey == null) { appliedKey = ""; start(null) }
        version++
    }

    /** The theater's airfields: the one BMS reports, else the one the app is set to, else any holding the departure. */
    private suspend fun loadAirports(mission: WdpMission?) {
        val bases = missionBases(mission?.briefing, mission?.flight)
        val dep = bases.departure
        val theaters = runCatching { Repo.index().theaters }.getOrNull().orEmpty()
        val candidates = buildList {
            mission?.theater?.let { add(it) }
            theaters.firstOrNull { it.id == Repo.selectedTheater.value }?.let { add(it) }
            addAll(theaters.filter { it.primary })
            addAll(theaters)
        }.distinctBy { it.airportSet }
        for (t in candidates) {
            if (t.airportSet.isEmpty()) continue
            val s = runCatching { Repo.airportSet(t.airportSet) }.getOrNull() ?: continue
            if (s.airports.isEmpty()) continue
            if (t == mission?.theater || dep == null || bases.departureIn(s) != null) {
                theater = t; airports = s
                return
            }
        }
    }

    /**
     * The mission's departure field, or the first field of the theater before any mission. Matched as the Briefing
     * page matches it (`departureIn`): the briefing's name with its comms rows as clues, so a carrier BMS names only
     * by its first word ("USS") is found where it is the one, and nothing is guessed where it is not.
     */
    private fun fieldFor(b: Briefing?, flight: com.bmscompanion.app.data.mission.CampFlight? = mission?.flight): Airport? =
        missionBases(b, flight).departureIn(airports) ?: if (b == null) noMissionField() else null

    /** Before any mission: the field the page showed last time ([rememberedApt]) when this theater has it, else its first. */
    private fun noMissionField(): Airport? {
        val list = airports?.airports.orEmpty().filter { it.runways.isNotEmpty() }
        return list.firstOrNull { it.name == rememberedApt } ?: list.firstOrNull()
    }

    /** BMS's own data for one field (runway rectangles, taxiways, ramp); null where the theater has none. */
    private suspend fun airfield(t: Theater, a: Airport): Airfield? {
        val set = t.airfieldSet ?: return null
        return runCatching { Repo.airfield(set, a.id) }.getOrNull()
    }

    /**
     * BMS's weights for a type, from the app's aircraft data: the mission theater's own variant ([variantFor]), and
     * the conformal tanks it can carry (`racks.json`, [PerformanceLoadout.Racks.cft]).
     */
    private fun acData(type: String): PerformancePlan.AcData? {
        val a = aircraftFor(type) ?: return null
        val v = variantFor(a) ?: return null
        val s = v.spec
        val empty = s.emptyWeightLbs ?: return null
        val fuel = s.internalFuelLbs ?: return null
        val max = s.maxWeightLbs ?: return null
        return PerformancePlan.AcData(empty.toInt(), fuel.toInt(), max.toInt(), racks?.cft(a.key, theatersFor(v)))
    }

    /**
     * The variant of [a] Falcon BMS flies in the page's theater — the mission's, else the one the app is set to —
     * as WDP reads the running theater's own vehicle table; else the variant flown in the most theaters.
     */
    private fun variantFor(a: Aircraft): com.bmscompanion.app.data.AircraftVariant? {
        val id = theater?.id
        return a.variants.firstOrNull { id != null && id in it.theaters } ?: a.variants.firstOrNull()
    }

    /** The theaters to read [v]'s theater data from: the page's own first, then the variant's. */
    private fun theatersFor(v: com.bmscompanion.app.data.AircraftVariant?): List<String> =
        (listOfNotNull(theater?.id) + v?.theaters.orEmpty()).distinct()

    private fun aircraftFor(type: String): Aircraft? {
        val n = PerformancePlan.norm(type)
        return aircraftList.firstOrNull { PerformancePlan.norm(it.name) == n }
            ?: aircraftList.firstOrNull { a -> a.variants.any { v -> v.spec.datFile?.let { PerformancePlan.norm(it) } == n } }
    }

    /**
     * The app's aircraft for an item of the type list, for its hardpoints: the same name; else the one whose name
     * begins with the item's ("F-16C-52+" is the app's "F-16C-52+ EAF"); else the first F-16 of the same model and
     * block ("F-16C-52+CFT" → "F-16C-52+ CFT"); else the first of the same model. The list names WDP's jets its own way, and a jet picked there with no
     * hardpoints could not be loaded at all.
     */
    private fun aircraftForItem(item: String): Aircraft? {
        aircraftFor(item)?.let { return it }
        val n = PerformancePlan.norm(item)
        val f16 = aircraftList.filter { it.name.contains("F-16", ignoreCase = true) && it.variants.any { v -> v.stations.isNotEmpty() } }
        f16.firstOrNull { PerformancePlan.norm(it.name).startsWith(n) }?.let { return it }
        PerformancePlan.blockKey(item)?.let { key ->
            f16.firstOrNull { a -> PerformancePlan.blockKey(a.name) == key || a.variants.any { v -> v.spec.datFile?.let { PerformancePlan.blockKey(it) } == key } }
                ?.let { return it }
        }
        // last, a jet of the same model ("F-16AM BAF", which the app names "F-16AM BAC"): its hardpoints are the model's
        val model = PerformancePlan.modelKey(item) ?: return null
        return f16.firstOrNull { PerformancePlan.modelKey(it.name) == model }
    }

    /** One of the app's airfields as WDP's airport table holds it: each runway end a row, with its length and width. */
    private fun apt(a: Airport): PerformancePlan.Apt {
        // each end's designator and its strip's width, from BMS's own runway rectangles
        val w = fields[a.id]?.runways.orEmpty().flatMap { r -> r.ends.map { it.designator to r.widthFt } }.toMap()
        val rows = a.runways.flatMap { r ->
            r.ends.map { e ->
                PerformancePlan.Rwy(e.designator, r.lengthFt ?: 0, r.lengthFt ?: 0, w[e.designator] ?: 0, e.ils?.toDoubleOrNull() ?: 0.0)
            }
        }.take(4)
        return PerformancePlan.Apt(
            name = a.name,
            icao = a.icao.orEmpty(),
            iata = "",
            elevation = a.elevationFt,
            runways = rows,
            // WDP enables Charts when it has the field's ground chart; the app draws one wherever BMS's data has the field,
            // and shows the field's instrument charts from its own Charts after it
            hasChart = fields[a.id] != null || charts[a.id.toString()].orEmpty().isNotEmpty(),
        )
    }

    // ================================================================ the mission

    override fun onMission(mission: WdpMission) {
        try {
            this.mission = mission
            val b = mission.briefing
            // which mission or flight (a save's flight is told by its save as well as its callsign), which printing of
            // the briefing, and whether the data and the theater's airfields were in when it was put on the page
            val key = buildString {
                append(mission.key).append('|')
                append(b?.generated).append('|').append(b?.overview?.flight).append('|').append(loaded).append('|').append(theater?.airportSet)
            }
            if (key != appliedKey) {
                appliedKey = key
                appliedSeat = null
                // a new mission starts on its own seat (the lead for a printed briefing), not on the one the Loadout
                // window last showed for another flight
                seat = mission.seat.coerceIn(0, 3)
                // the last mission's burnt fuel is not this one's (the new mission's own is put on with its loadout)
                plan.fuelBurnt = 0
                plan.departed = false
                start(b)
            } else if (mission.flight == null || mission.seat == appliedSeat) return
            // a save's flight picked with its seat (Open mission…): that seat's loadout onto the page, once per pick
            if (mission.flight != null) { appliedSeat = mission.seat; seat = mission.seat.coerceIn(0, 3); applyMissionLoadout(); plan.programFlow() }
        } catch (e: Exception) {
            WdpDialogs.message("Performance", "The mission could not be put on the page: " + (e.message ?: e::class.simpleName))
        }
        changed()
    }

    /** What `fclsMain` runs once the campaign is in: the field, the turn table, the type, and the figures. */
    private fun start(b: Briefing?) {
        val p = plan
        // the flight: its aircraft, picked in the list, and its loadout
        val own = b?.overview?.flight
        val flight = b?.`package`.orEmpty().let { pk -> b?.ownFlight(own) ?: pk.firstOrNull() }
        val type = flight?.aircraft?.trim()?.takeIf { it.isNotEmpty() }
        p.missionAircraft = type
        p.missionIsF16 = type?.contains("F-16", ignoreCase = true)
        missionOther = type?.takeIf { p.missionIsF16 == false }
        // BMS's data file names for the jet, which name the block where the briefing's name does not ("F-16C 393 HAF")
        p.missionAliases = type?.let { t -> aircraftFor(t)?.variants?.mapNotNull { it.spec.datFile }?.distinct() }.orEmpty()
        loadout = buildLoadout(type, b)
        // the weather at take-off, into the page's own boxes — calm and a standard altimeter first, so a mission that
        // brings no weather (a save's flight without a printed briefing of its own) does not take off in the last
        // mission's wind: the user's page kept "1 / 15" from a briefing four days older, a 15 kt crosswind on 09L
        if (b != null) p.calmStandardDay()
        weather(b)
        // the departure field, or the first field of the theater before any mission
        val field = fieldFor(b)
        if (field != null) p.database(apt(field), intoWind(field))
        p.programflowTurn()
        val item = p.missionItem()
        // back from another aircraft's flight, whose name Type showed: the list's own pick first
        (p.cboType.selectedItem ?: "").let { if (p.cboType.text != it) p.cboType.text = it }
        when {
            // a flight of another aircraft: its name, its own weights and stores, and no F-16 figures
            type != null && p.missionIsF16 == false -> p.otherAircraft(type, acData(type))
            item != null && item != p.cboType.text -> p.cboType.selectItem(item)
            else -> p.typeChange()
        }
        // WDP locks Type once a mission is in; here it stays open, so another F-16 can be planned against the same
        // mission (its stores carried across, [pickType]) and the mission's own jet picked back
        p.cboTypeEnabled = true
        p.programFlow()
    }

    /**
     * The mission's own aircraft when it is not an F-16 ("F-15C"): WDP's list holds only F-16s, so the page puts that
     * jet at the top of the list it shows, to be picked back after planning an F-16 against the mission.
     */
    private fun otherItem(): String? = missionOther

    /** The mission's own aircraft when it is not an F-16, as [start] found it (null for an F-16 flight or no mission). */
    private var missionOther: String? = null

    /** Whether the page shows the mission's own non-F-16 jet (Type shows its name) rather than an F-16 of the list. */
    private fun onOtherAircraft(): Boolean = missionOther != null && plan.cboType.text == missionOther

    /**
     * A pick in Type. An F-16 of the list gets WDP's `TypeChange` — its engine and weights — and the stores on the page
     * carried onto its hardpoints (WDP's TypeChange empties the loadout, and the pilot had to load the jet again to
     * compare two blocks); the mission's own jet gets the mission's loadout back, as TypeChange gives it. Picking an
     * F-16 while the mission's jet is another aircraft plans that F-16, with its figures; picking the mission's jet
     * again blanks them, as for any flight of another aircraft.
     */
    private fun pickType(value: String) {
        val p = plan
        if (value == p.cboType.text) return
        val other = otherItem()
        if (other != null && value == other) {
            p.missionIsF16 = false
            loadout = buildLoadout(other, mission?.briefing)
            p.otherAircraft(other, acData(other))
            return
        }
        val i = p.cboType.items.indexOf(value)
        if (i < 0) return
        if (other != null) p.missionIsF16 = true
        loadout = if (other == null && value == p.missionItem()) buildLoadout(p.missionAircraft, mission?.briefing) else carried(loadout, value)
        // the list's own index may already be this item while Type showed the other aircraft's name: TypeChange all the same
        if (p.cboType.index == i) { p.cboType.text = value; p.typeChange() } else p.cboType.select(i)
        applyMissionLoadout()
        p.programFlow()
    }

    /**
     * The page's stores onto [type]'s hardpoints; the mission's own loadout for it where the page had none. Null where
     * the app has no hardpoints for [type] (the Loadout window then says so) — never the last aircraft's stores.
     */
    private fun carried(from: PerformanceLoadout?, type: String): PerformanceLoadout? {
        val to = buildLoadout(type, null) ?: return null
        if (from == null) return buildLoadout(type, mission?.briefing) ?: to
        from.carryTo(to)
        return to
    }

    /**
     * The runway the page opens on: of the rows it lists, the end most nearly into the wind in the page's own boxes
     * (the briefing's take-off wind once a mission is in), as WDP's DataCard picks its ATIS runway (`PreferedRwy`,
     * the app's [CardWeather.intoWind]) and hands it to this page. "0" — the first row — when there is no wind.
     */
    private fun intoWind(a: Airport): String {
        val rows = apt(a).runways.map { it.nr }
        val ends = a.runways.flatMap { it.ends }.filter { it.designator in rows }
        val wind = plan.txtWindDir.text.trim().toIntOrNull()
        if (ends.isEmpty() || wind == null || (plan.txtWindSpd.text.trim().toIntOrNull() ?: 0) == 0) return "0"
        return ends[CardWeather.intoWind(ends.map { it.designator to it.headingTrue }, wind)].designator
    }

    private fun weather(b: Briefing?) {
        val rows = b?.weather?.rows.orEmpty()
        fun takeOff(label: String) = rows.firstOrNull { it.label.startsWith(label, true) }?.values?.firstOrNull()
        // "1deg@ 15kts."
        takeOff("Wind")?.let { w ->
            val m = Regex("(\\d+)\\s*deg\\s*@\\s*(\\d+)").find(w)
            if (m != null) { plan.txtWindDir.set(m.groupValues[1]); plan.txtWindSpd.set(m.groupValues[2]) }
        }
        // "23deg C."
        takeOff("Temp")?.let { t -> Regex("(-?\\d+)").find(t)?.let { plan.txtTemp.set(it.groupValues[1]) } }
    }

    private fun buildLoadout(type: String?, b: Briefing?): PerformanceLoadout? {
        val a = type?.let { aircraftFor(it) ?: aircraftForItem(it) } ?: aircraftForItem(plan.cboType.text) ?: return null
        val variant = variantFor(a)
        // the pylons, racks and stores of the page's theater, as BMS flies it there (WDP reads the running theater's)
        val where = theatersFor(variant)
        val l = PerformanceLoadout(
            variant?.stations.orEmpty(), weapons, racks?.forVariant(a.key, where).orEmpty(), racks?.storeFigures(where).orEmpty(),
        )
        val own = b?.overview?.flight
        val ord = b?.ordnance.orEmpty().let { o -> o.firstOrNull { it.flight.equals(own, true) } ?: o.firstOrNull() }
        if (ord != null) l.fromBriefing(ord.aircraft.map { ac -> ac.stores.map { it.qty to it.name } })
        return l
    }

    /** The seat the Performance page last took from the mission (a save's flight picked with a seat), or null. */
    private var appliedSeat: Int? = null

    /**
     * WDP's `SelectPilotSeat` from outside the page: the loadout of seat [seat] (0-3: lead, wing, element lead, element
     * wing) onto the page and the figures worked out again, as the Loadout window's OK does.
     */
    fun pilotSeat(seat: Int) {
        this.seat = seat.coerceIn(0, 3)
        applyMissionLoadout()
        plan.programFlow()
        changed()
    }

    /**
     * The mission's loadout onto the page, as WDP's Loadout window does when it closes itself for a new flight, with
     * the seat's own internal fuel where the save gives it ([missionFuel]).
     */
    private fun applyMissionLoadout() {
        missionBurn()
        val fuel = missionFuel()
        val l = loadout
        // the save's fuel says whether the jet carries its conformal tanks (their fuel is loaded with the rest)
        val cftBefore = plan.cftDrag
        if (fuel != null) plan.cftFromFuel(fuel)
        if (l == null) {
            // no hardpoints known for the jet: its stores stay as they are, its fuel is still the save's
            if (fuel != null) plan.applyLoadout(plan.intLoadout, plan.intDrag - cftBefore + plan.cftDrag, fuel, plan.intExtFuel) else plan.setFuel()
            return
        }
        l.airframeDrag = plan.cftDrag
        val t = l.totals(seat.coerceIn(0, 3))
        plan.applyLoadout(t.loadWeight, t.totalDrag, fuel ?: plan.intIntFuel, t.extFuel)
    }

    /**
     * The internal fuel the save gives this seat's jet (the flight's `fuel_initial`), which WDP's `SetFuel` puts on the
     * page for a flight of a save (`cntPerformance.cs` l.4983-4995: `intIntFuel = fuel_initial[SelPilotSeat]`), where
     * the page otherwise has the type's full tanks. The save carries each aircraft's starting fuel (the loadout screen's
     * fuel slider, User Manual 4.38.1 p.151), so a jet sent with part of its fuel starts the take-off with that. Null for a
     * printed briefing (it does not say), and for another F-16 picked in Type: WDP gave it the mission jet's fuel too,
     * whatever its own tanks hold (a CFT jet's 10,219 lb read as 7,162), and here it keeps its own.
     */
    private fun missionFuel(): Int? {
        val f = mission?.flight ?: return null
        if (!ownJet()) return null
        return (f.fuelLb.getOrNull(seat.coerceIn(0, 3)) ?: f.fuelLb.firstOrNull())?.takeIf { it > 0 }
    }

    /** Whether Type shows the mission's own jet (WDP: `intAC == GetFlightVehId(SelFlightNr)`). */
    private fun ownJet(): Boolean {
        val other = missionOther
        return if (other != null) plan.cboType.text == other else plan.missionItem()?.let { it == plan.cboType.text } == true
    }

    /**
     * The rest of WDP's `SetFuel` for a save's flight (`cntPerformance.cs` l.5000-5017): the fuel the flight has burnt
     * (`fuel_burnt`) off the block fuel of its own jet, and "----" for the take-off fuel once the save's clock is past
     * the flight's take-off and it is under way (`current_wp > 0`), as the DataCard prints it. Nothing for a printed
     * briefing, another jet picked in Type, or no mission.
     */
    private fun missionBurn() {
        val f = mission?.flight
        plan.fuelBurnt = if (f != null && ownJet()) (f.fuelBurnt ?: 0).coerceAtLeast(0) else 0
        val clock = f?.clock
        val dep = f?.route?.firstOrNull()?.departMs
        plan.departed = clock != null && dep != null && clock > dep && (f.currentWp ?: 0) > 0
    }

    // ================================================================ values

    override fun values(hidden: List<String>): WdpValues {
        @Suppress("UNUSED_VARIABLE") val v = version   // read so a change re-composes the page
        val p = plan
        val m = LinkedHashMap<String, String>()
        for (h in hidden) m[h] = "hidden"
        m.putAll(p.labels)
        for ((k, c) in p.fore) m["$k.fore"] = c
        m["txtTemp"] = p.txtTemp.text
        m["txtWindDir"] = p.txtWindDir.text
        m["txtWindSpd"] = p.txtWindSpd.text
        m["txtCruiseAlt"] = p.txtCruiseAlt.text
        m["txtQNH_Hpa"] = p.txtQNH_Hpa.text
        m["txtQNH_In"] = p.txtQNH_In.text
        m["numTaxiFuel"] = p.numTaxiFuel.toString()
        // the designer's Increment (100 lb a click)
        m["numTaxiFuel.increment"] = "100"
        for (r in RADIOS) m[r] = if (p.radio == r) "checked" else "unchecked"
        fun combo(name: String, c: PerformancePlan.Combo) { m[name] = c.text; m["$name.items"] = c.items.joinToString("\n") }
        combo("cboType", p.cboType)
        combo("cboPitch", p.cboPitch)
        combo("cboPower", p.cboPower)
        combo("cboRWY", p.cboRWY)
        combo("cboCAS", p.cboCAS)
        combo("cboAltitude", p.cboAltitude)
        combo("cboGs", p.cboGs)
        combo("cboActualTemp", p.cboActualTemp)
        // every F-16 WDP plans for, and first the mission's own jet where that is another aircraft
        otherItem()?.let { m["cboType.items"] = (listOf(it) + p.cboType.items).joinToString("\n") }
        m["cboType.enabled"] = "true"
        m["btnChart.enabled"] = p.btnChartEnabled.toString()
        m["btnLoadout.enabled"] = p.btnLoadoutEnabled.toString()
        // the designer sets this one from the form's resources, which the layout does not carry
        m["lblClimbExplanation"] = CLIMB_EXPLANATION
        return WdpValues(m)
    }

    // ================================================================ input

    private fun handleValue(name: String, value: String) {
        val p = plan
        when (name) {
            "txtTemp" -> p.txtTemp.set(value)
            "txtWindDir" -> p.txtWindDir.set(value)
            "txtWindSpd" -> p.txtWindSpd.set(value)
            "txtCruiseAlt" -> p.txtCruiseAlt.set(value)
            "txtQNH_Hpa" -> p.txtQNH_Hpa.set(value)
            "txtQNH_In" -> p.txtQNH_In.set(value)
            "txtCruiseAlt.leave" -> p.cruiseAltLeave()
            "txtQNH_Hpa.leave" -> p.qnhHpaLeave()
            "txtQNH_In.leave" -> p.qnhInLeave()
            "txtTemp.leave" -> p.tempLeave()
            "numTaxiFuel" -> {
                // the arrows step by the box's increment of 100 lb ("numTaxiFuel.increment", which the renderer's
                // arrows use); a typed number is taken as it is, 1101 as much as 1200
                val n = value.trim().toDoubleOrNull()?.toInt() ?: return
                p.setTaxiFuel(n)
            }
            "cboType" -> pickType(value)
            "cboPitch" -> p.cboPitch.selectItem(value)
            "cboPower" -> p.cboPower.selectItem(value)
            "cboRWY" -> p.cboRWY.selectItem(value)
            "cboCAS" -> p.cboCAS.selectItem(value)
            "cboAltitude" -> p.cboAltitude.selectItem(value)
            "cboGs" -> p.cboGs.selectItem(value)
            "cboActualTemp" -> p.cboActualTemp.selectItem(value)
            else -> return
        }
        changed()
    }

    private fun handleClick(name: String) {
        when (name) {
            in RADIOS -> plan.check(name)
            "btnSelect" -> selectApt()
            "btnChart" -> if (plan.btnChartEnabled) openCharts()
            "btnLoadout" -> if (plan.btnLoadoutEnabled) openLoadout()
            else -> return
        }
        changed()
    }

    private fun changed() {
        saveIni()
        version++
        // the DataCard's take-off figures follow at once — also after the page's own windows (the Loadout window's OK,
        // Select APT), which the Planner's page events do not see, so a card printed or sent straight from this page
        // carries this loadout: through the hook where one is set, else, on the Planner's own page (the one given a
        // scope; the headless checks' pages have none), straight through the hand-off
        val hook = onFigures
        if (hook != null) hook() else if (scope != null) WdpHandOff.performanceToCard(this, WdpSession.dataCard)
    }

    // ================================================================ the child windows

    /** `btnSelect_Click` → `fclsSelAPT`; OK runs `Database(apt, "0")` for the field picked. */
    private fun selectApt() {
        val list = airports?.airports.orEmpty().filter { it.runways.isNotEmpty() }
        if (list.isEmpty()) {
            WdpDialogs.message("Select APT", "No airfields are loaded yet. They come with the theater Falcon BMS is running; " +
                "press PRINT on the briefing screen, or pick a theater in the app, and try again.")
            return
        }
        SelAptWindow(list, plan.airport?.name) { a ->
            if (a != null) {
                plan.database(apt(a), intoWind(a))
                changed()
                // the runway widths and the charts come from the field's own data, which is read when it is picked
                val t = theater
                if (t != null && scope != null && a.id !in fields) scope.launch {
                    val f = airfield(t, a)
                    if (f != null) {
                        fields = fields + (a.id to f)
                        if (plan.airport?.name == a.name) { val rwy = plan.cboRWY.selectedItem ?: intoWind(a); plan.database(apt(a), rwy); changed() }
                    }
                }
            }
        }.open()
    }

    /**
     * `btnChart_Click` → `fclsChart`: the field as the app draws it on its Taxi page — the airport diagram, a parking
     * chart for each runway end (BMS numbers the ramp for each end) — and then the field's instrument charts from the
     * app's Charts, the same window the DataCard's chart buttons open ([ChartWindow]). WDP's showed its own pictures of
     * the ground and parking charts.
     */
    private fun openCharts() {
        val a = airports?.airports?.firstOrNull { it.name == plan.airport?.name }
        if (a == null) { WdpDialogs.message("Charts", "Pick the airfield first (Select APT), then its charts open here."); return }
        ChartWindow(a, charts[a.id.toString()].orEmpty(), theater?.airfieldSet).open()
    }

    /**
     * `btnLoadout_Click` → `fclsLoadout` (the app's own way of choosing the stores); its OK hands the load, drag and fuel
     * back to the page. The DataCard opens it too, from our own flight's callsign in its package rows (D86).
     */
    internal fun openLoadout() {
        val l = loadout ?: buildLoadout(plan.cboType.text, mission?.briefing)
        if (l == null) {
            // only a jet Falcon BMS added after the app's copy of its aircraft data was made
            WdpDialogs.message("Loadout", "The ${plan.cboType.text} is newer than the app's copy of Falcon BMS's aircraft data, so its hardpoints are not known here.")
            return
        }
        loadout = l
        val f16 = !onOtherAircraft() && plan.cboType.text.contains("F-16", ignoreCase = true)
        // another aircraft is shown as the app's aircraft reference pictures it, where WDP's window has its F-16
        val pic = if (f16) null else aircraftFor(plan.cboType.text)?.pic
        // WDP asks its question whenever a mission is open (`fclsMain.blnMissionLoaded`): a printed briefing or a save's flight
        val missionOpen = mission?.briefing != null || mission?.flight != null
        // the jet's conformal tanks, when it carries them, are part of its drag in the window as on the page
        l.airframeDrag = plan.cftDrag
        PerfLoadoutWindow(l, plan.cboType.text, plan.intEmpty, plan.intIntFuel, plan.intMax, seat, missionOpen, f16, pic) { newSeat ->
            seat = newSeat
            val t = l.totals(seat)
            // the seat's own fuel from the save, as WDP's window takes fuel_initial[PilotSeat]
            plan.applyLoadout(t.loadWeight, t.totalDrag, missionFuel() ?: plan.intIntFuel, t.extFuel)
            plan.programFlow()
            // DatacardLabels: the card's Config rows from these stores, each store once with its count
            onLoadoutApplied?.invoke(
                l.seats[seat].entries.sortedBy { PerformanceLoadout.ORDER.indexOf(it.key) }
                    .groupBy({ it.value.key }, { it.value.count })
                    .mapNotNull { (key, counts) -> l.weapons[key]?.let { it.name to counts.sum() } },
            )
            changed()
        }.open()
    }

    // ---------------------------------------------------------------- Setup.ini's [Performance], in the app's preferences

    private fun readIni(): Map<String, String> = runCatching {
        val s = Repo.getString(INI_KEY) ?: return@runCatching emptyMap<String, String>()
        s.split('\n').filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
    }.getOrDefault(emptyMap())

    private fun saveIni() {
        val s = plan.iniValues().entries.joinToString("\n") { "${it.key}=${it.value}" }
        if (s == savedIni) return
        savedIni = s
        runCatching { Repo.putString(INI_KEY, s) }
    }

    // The plan throws only where WDP would stop with its unhandled-exception box (a checked conversion of a
    // nonsense number); the page answers with WDP's box and carries on from where the handler got to.
    override fun onValue(name: String, value: String) = guard { handleValue(name, value) }
    override fun onClick(name: String) = guard { handleClick(name) }

    private fun guard(block: () -> Unit) {
        try { block() } catch (e: Exception) {
            WdpDialogs.message("Performance", "This did not work: " + (e.message ?: e::class.simpleName ?: "an error") + ".")
            version++
        }
    }

    companion object {
        /** Where Setup.ini's [Performance] is kept in the app's preferences (the headless checks save and restore it). */
        const val INI_KEY = "wdp_performance_ini"
        private val RADIOS = listOf("radCruiseAlt", "radOptCruiseAlt", "radCruiseCeiling", "radServiceCeiling")

        /** WDP's own words under Climb Explanation, from the form's resources (`cntPerformance.resx`). */
        private const val CLIMB_EXPLANATION = "A constant throttle position (MIL or MAX AB) from brake release to MIL or MAX AB climb speed is used. " +
            "After takeoff, a constant pitch attitude of 12 degrees is held until 2500 feet AGL. A level acceleration to climb speed is " +
            "then made. In some cases, climb airspeed will be reached prior to gaining 2500 feet AGL. This technique was developed for " +
            "performance calculations only and not as an operational procedure."
    }

    // ================================================================ windows

    /** A child window's small state machine, as a page's: values by control name, a guarded handler for each event. */
    private abstract class Window(val form: String, val title: String) : WdpWiring {
        private var version by mutableIntStateOf(0)
        protected val v = LinkedHashMap<String, String>()
        private var dialog: WdpDialog? = null

        override fun values(hidden: List<String>): WdpValues {
            @Suppress("UNUSED_VARIABLE") val r = version
            val m = LinkedHashMap<String, String>()
            for (h in hidden) m[h] = "hidden"
            m.putAll(v)
            return WdpValues(m)
        }

        override fun onValue(name: String, value: String) = guard { value(name, value) }
        override fun onClick(name: String) = guard { click(name) }

        protected open fun value(name: String, value: String) { if (!name.endsWith(".leave")) v[name] = value }
        protected abstract fun click(name: String)

        private fun guard(block: () -> Unit) {
            try { block() } catch (e: Exception) {
                WdpDialogs.message(title, "This did not work: " + (e.message ?: e::class.simpleName ?: "an error") + ".")
            }
            version++
        }

        fun open() { val d = WdpDialog(form, title, this, onDismiss = { dismissed() }); dialog = d; WdpDialogs.show(d) }
        fun close() { dialog?.let { WdpDialogs.close(it) } }

        /** The window's own ×, which WDP treats as its Cancel. */
        protected open fun dismissed() {}
    }

    /**
     * `fclsSelAPT`. WDP's two lists are the country and its airports; the app's airfield data has no countries, so
     * the first list sorts the theater's fields by kind (airbases, airstrips …) and the second lists them by name.
     */
    private class SelAptWindow(private val list: List<Airport>, current: String?, private val onSelect: (Airport?) -> Unit) :
        Window("fclsSelAPT", "Select an airport") {
        private val kinds = listOf(ALL) + list.map { it.type }.distinct().sorted()

        init {
            // the first list holds kinds of field, not countries: its caption says so
            v["lblCountry"] = "Type :"
            v["cboCountry.items"] = kinds.joinToString("\n")
            v["cboCountry"] = ALL
            fill(ALL)
            list.firstOrNull { it.name == current }?.let { v["cboAirports"] = it.name }
        }

        private fun fill(kind: String) {
            val names = list.filter { kind == ALL || it.type == kind }.map { it.name }.sorted()
            v["cboAirports.items"] = names.joinToString("\n")
            v["cboAirports"] = names.firstOrNull() ?: ""
        }

        override fun value(name: String, value: String) {
            when (name) {
                // picking the kind already chosen changes nothing, as a WinForms list fires no change for it
                "cboCountry" -> if (value != v[name]) { v[name] = value; fill(value) }
                "cboAirports" -> v[name] = value
                else -> super.value(name, value)
            }
        }

        override fun click(name: String) {
            when (name) {
                "btnSelect" -> { close(); onSelect(list.firstOrNull { it.name == v["cboAirports"] }) }
                "btnCancel" -> close()
            }
        }

        companion object { const val ALL = "All airfields" }
    }
}
