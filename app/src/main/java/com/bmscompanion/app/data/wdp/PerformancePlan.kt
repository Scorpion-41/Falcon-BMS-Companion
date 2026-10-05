package com.bmscompanion.app.data.wdp

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Weapon Delivery Planner's Performance page — Falcas's `cntPerformance` — as a state machine.
 *
 * The page is two things. The left three quarters take the jet (type, loadout, fuel), the airfield (runway, wind,
 * temperature, altimeter) and a cruise altitude, and answer the take-off (factor, rotate, lift-off, refusal), the
 * MIL and MAX AB climbs (schedule, distance, fuel, time) and the cruise (optimum Mach and altitude, the ceilings),
 * from the engine charts ([Engine]). The right quarter is a turn calculator: a CAS, an altitude, a G and a
 * temperature give TAS, Mach, density altitude and a turn radius.
 *
 * The port keeps WDP's method names and, above all, its **events**: a WinForms text box runs its `TextChanged` on
 * every change, including the ones a handler makes itself, so `WindDir()` writing a corrected direction back into
 * its box re-enters `ProgramFlow()` exactly as it does in the program.
 *
 * **WDP's slips on this page are fixed** (the Planner is WDP for Falcon BMS 4.38.1); each is named where it is
 * fixed, and `docs/WDP-PORT.md` lists them with their evidence:
 * - pressure altitude with the right sign and size ([Atmosphere.pressureAltitudeFt], D28);
 * - the turn calculator's Actual Temp is used — WDP wrote the standard temperature back over any pick ([temperature],
 *   D29); a Temp C that is not a number is refused rather than becoming 20 °C ([temp], D29);
 * - the cruise altitude compared with the service ceiling as a number, not as text, where "9000" sorted after
 *   "36680" and became it ([txtCruiseAltTextChanged], D30); the third runway's metres from its own width
 *   ([runwayChange], D30);
 * - the field's ISA deviation reaches the climb and fuel charts, which WDP always handed 0; the GE-100's AB climb
 *   schedule for drag 101–200 and the refusal chart's 12,000–15,000 ft cell as their neighbours show they were meant
 *   ([doCalculations], D31);
 * - a mission flight of another aircraft shows that aircraft's own weights and no figures, where WDP kept the last
 *   F-16's ([otherAircraft]).
 *
 * **The standard day is WDP's own, 2 °C per 1,000 ft** (the ISA deviation, the turn calculator's standard temperature),
 * because it is Falcon BMS's (User Manual 4.38.1 §4.1; [Atmosphere]). The port once used the ICAO 1.98 here (D32,
 * withdrawn), which put the ISA deviation a degree off WDP's on some days and the turn calculator's figures off at
 * 26,000 ft and above.
 *
 * [wdpSlips] runs the page as the program answered, slips included. Only the comparison check builds one that way,
 * to show that the fixes are the only differences from the program; the page never does.
 *
 * What WDP reads from its own databases arrives from the app: the aircraft's weights ([aircraftData], from Falcon
 * BMS's aircraft data, as WDP reads them from BMS's `VehicleDataEntries`), the airfield ([database]), and the
 * mission's loadout ([applyLoadout], which is what WDP's Loadout window hands back when it closes).
 */
class PerformancePlan(
    /** WDP's slips kept, for `--wdppagetest performance` only (see the class notes); false everywhere else */
    val wdpSlips: Boolean = false,
) {

    /**
     * The aircraft's weights as BMS's vehicle data holds them: WDP's `EmptyWt`, `FuelWt`, `MaxWt`; and the conformal
     * tanks it can carry ([cft]), null for a jet that has none.
     */
    data class AcData(val empty: Int, val fuel: Int, val max: Int, val cft: Cft? = null)

    /**
     * An F-16's conformal fuel tanks as Falcon BMS's flight model has them (`Sim/Acdata/<jet>.txtpb`: `has_cft`,
     * `cft_empty_weight`, `cft_fuel`, `cft_drag`): what the pair weighs empty, the fuel it holds, the drag it adds.
     * 1,700 lb, 3,060 lb and 20 on every F-16 that has them.
     */
    data class Cft(val empty: Int, val fuel: Int, val drag: Int)

    /** One of an airfield's runways, as WDP's airport table holds it. Width 0 where it is not known. */
    data class Rwy(val nr: String, val toda: Int, val lda: Int, val width: Int, val ils: Double = 0.0)

    /** The airfield WDP's `Database()` fills the page from. */
    data class Apt(
        val name: String,
        val icao: String = "",
        val iata: String = "",
        /** null where the database has no elevation: WDP reads a non-number as 0 */
        val elevation: Int? = null,
        val runways: List<Rwy> = emptyList(),
        /** whether there is a chart to show, which is what enables the Charts button */
        val hasChart: Boolean = false,
    )

    /** A WinForms combo box, enough of one: its items, the selected index, its text. */
    inner class Combo(items: List<String>, private val onChange: () -> Unit) {
        var items: List<String> = items
            private set
        var index = -1
            private set
        var text = ""
        val selectedItem: String? get() = items.getOrNull(index)

        fun setItems(list: List<String>) { items = list; index = -1; text = "" }

        /** `SelectedIndex = i`: the event only when it changes. */
        fun select(i: Int) {
            val j = if (i in items.indices) i else -1
            if (j == index) return
            index = j
            text = items.getOrNull(j) ?: ""
            onChange()
        }

        /** `SelectedItem = s`: an item that is not in the list changes nothing. */
        fun selectItem(s: String?) { val i = items.indexOf(s); if (i >= 0) select(i) }

        /** `Text = s`: the matching item is selected (and its event runs), else only the text changes. */
        fun changeText(s: String) {
            text = s
            if (selectedItem == null || !s.equals(selectedItem, ignoreCase = true)) {
                val i = items.indexOfFirst { it.equals(s, ignoreCase = true) }
                if (i >= 0 && i != index) { index = i; text = items[i]; onChange() }
            }
        }
    }

    /** A WinForms text box: `TextChanged` runs on every change of its text, and only then. */
    inner class Box(initial: String, private val onChange: () -> Unit) {
        var text: String = initial
            private set

        fun set(s: String) { if (s == text) return; text = s; onChange() }
    }

    // ---------------------------------------------------------------- what WDP's fields hold

    var blnLoaded = false
    /** Falcon BMS rather than Allied Force: the type list and every label follow it. The app is always BMS. */
    var blnVersion = true

    var intEmpty = 0
    var intIntFuel = 0
    var intExtFuel = 0
    var intBlockFuel = 0
    var intTakeoffFuel = 0
    var intLoadout = 0
    var intDrag = 0
    var intGrossWt = 0
    var intMax = 0
    var dblTOFactor = 0.0
    var currentEngine = 0
    var intEngDry = 0
    var intEngMax = 0
    var intElv = 0
    var intTODA = 0
    var intRwyHed = 0
    var intHWC = 0
    var intCWC = 0
    var dblTempC = 0.0
    var dblTempF = 0.0
    var dblQNH_Hpa = 0.0
    var dblQNH_In = 0.0
    var blnOverweight = false
    var blnRefusal = false
    /** false while a flight of another aircraft is on the page and the app has no weights for it ([otherAircraft]) */
    private var weightsKnown = true

    /**
     * The jet's conformal tanks ([AcData.cft]) and whether it carries them ([cftOn]); [baseEmpty] and [baseFuel] are
     * its weights without them. **Fixed:** WDP folds the tanks into its own figures for the types named CFT (21,200 lb
     * empty, 10,219 lb of fuel, no drag); the page takes Falcon BMS's: the jet's own weights plus the tanks' 1,700 lb
     * and 3,060 lb, and their drag of 20. The fuel is what BMS itself loads — a save's spare slots of an F-16C B52+ HAF
     * start with 10,222 lb (7,162 + 3,060), an F-16I-52+'s with 8,980 (5,920 + 3,060) — where WDP's is 3 lb short.
     */
    private var cft: Cft? = null
    var cftOn = false
        private set
    private var baseEmpty = 0
    private var baseFuel = 0

    /**
     * A save's flight on its own jet: the fuel it has burnt so far (the save's `fuel_burnt`), which WDP's `SetFuel`
     * takes off the block fuel, and whether its take-off is behind the save's clock with the flight under way, for
     * which WDP prints "----" as the take-off fuel. 0 and false otherwise.
     */
    var fuelBurnt = 0
    var departed = false

    /** The drag the conformal tanks add while the jet carries them, else 0. */
    val cftDrag: Int get() = if (cftOn) cft?.drag ?: 0 else 0

    /**
     * The mission's own fuel for the jet ([fuel], the save's `fuel_initial`) says whether it carries its conformal
     * tanks: BMS loads their fuel with the rest, so a figure above the jet's own tanks is theirs. The empty weight
     * follows; the drag is [cftDrag]. Nothing changes for a jet without them.
     */
    fun cftFromFuel(fuel: Int) {
        val c = cft ?: return
        cftOn = fuel > baseFuel
        intEmpty = baseEmpty + if (cftOn) c.empty else 0
        labels["lblEmpty_Val"] = intEmpty.toString()
    }

    // the turn calculator
    private var intCAS = 0
    private var intAltitude = 0
    private var intPullingGs = 0
    private var intTemperature = 0
    private var intTempISA = 0
    private var intActualTemp = 0
    private var intISAdev = 0
    private var intDensityAlt = 0
    private var intTAS = 0
    private var dblMach = 0.0

    /** The DataCard's weather strings WDP shares with this page: [1] wind direction, [2] wind speed, [3] temperature. */
    val strWeather = arrayOf("", "", "", "")

    /** The engines, from [Engines]; the calculations do nothing until they are here. */
    var engines: Map<Int, Engine> = emptyMap()

    /** The aircraft's weights by type name, or null when the app's aircraft data has no such type. */
    var aircraftData: (String) -> AcData? = { null }

    /**
     * The mission's flight, when a mission is loaded: its aircraft type as the briefing names it, and whether it
     * is an F-16. WDP computes nothing for a flight of anything else (`DoCalculations` returns at once).
     */
    var missionAircraft: String? = null
    var missionIsF16: Boolean? = null

    /**
     * Other names Falcon BMS gives the mission's jet — its data file names ("F-16C-52M 393 HAF" for the "F-16C 393
     * HAF") — which [typeItem] tries after the briefing's own name.
     */
    var missionAliases: List<String> = emptyList()

    /** The type list's item for the mission's jet ([typeItem] of [missionAircraft] and [missionAliases]), or null. */
    fun missionItem(): String? = missionAircraft?.let { typeItem(it, *missionAliases.toTypedArray()) }

    /** Called when the page wants the mission's loadout applied again (WDP's `TypeChange` re-running the Loadout window). */
    var onMissionLoadout: (() -> Unit)? = null

    /** The airfield on the page. */
    var airport: Apt? = null
        private set

    // ---------------------------------------------------------------- the controls

    val labels = LinkedHashMap<String, String>()
    /** fore colours WDP sets at runtime, by control: "#FF0000", "White", "Gray", … */
    val fore = LinkedHashMap<String, String>()
    var btnChartEnabled = false
    var cboTypeEnabled = true
    var btnLoadoutEnabled = true

    val txtTemp: Box = Box("15") { txtTempTextChanged() }
    val txtWindDir: Box = Box("0") { programFlow() }
    val txtWindSpd: Box = Box("0") { programFlow() }
    val txtCruiseAlt: Box = Box("25000") { txtCruiseAltTextChanged() }
    val txtQNH_Hpa: Box = Box("1013.2") {}
    val txtQNH_In: Box = Box("29.92") {}
    var numTaxiFuel = 0
        private set
    /** radCruiseAlt, radOptCruiseAlt, radCruiseCeiling, radServiceCeiling: the one that is checked */
    var radio = "radCruiseAlt"
        private set

    val cboType: Combo = Combo(emptyList()) { typeChange() }
    val cboPitch: Combo = Combo(listOf("8", "9", "10", "11", "12", "13")) { programFlow() }
    val cboPower: Combo = Combo(listOf("Full AB", "MIL")) { programFlow() }
    val cboRWY: Combo = Combo(emptyList()) { runwayChange() }
    val cboCAS: Combo = Combo((200..500 step 10).map { it.toString() }) { intCAS = selInt(cboCASItem()); intAltitude = selInt(cboAltitudeItem()); programflowTurn() }
    val cboAltitude: Combo = Combo((36000 downTo 1000 step 1000).map { it.toString() }) { intAltitude = selInt(cboAltitudeItem()); intCAS = selInt(cboCASItem()); programflowTurn() }
    val cboGs: Combo = Combo((2..10).map { it.toString() }) { intPullingGs = selInt(cboGsItem()); programflowTurn() }
    /**
     * The air's temperature at the turn's altitude. It opens on the standard temperature and follows the altitude;
     * a pick is kept, as a distance from standard, so a warm day stays as warm when the altitude changes.
     *
     * **Fixed (D29).** In WDP a pick never stuck: the handler runs `ProgramflowTurn`, whose first step
     * (`Temperature`) writes the standard temperature back into this very list, which selects it — so the figures
     * were always at ISA whatever was picked, as `wdpref page Performance` shows the real control doing.
     */
    val cboActualTemp: Combo = Combo((-70..30).map { it.toString() }) {
        intTemperature = selInt(cboActualTempItem())
        if (wdpSlips) programflowTurn()
        else if (!writingTemp) { pickedDev = intTemperature - intTempISA; programflowTurn() }
    }

    /** The picked temperature's distance from standard; null until a pick, while the list follows the standard day */
    private var pickedDev: Int? = null
    /** true while [temperature] itself moves the list, so its own change is not taken for a pick */
    private var writingTemp = false

    private fun cboCASItem(): String? = cboCAS.selectedItem
    private fun cboAltitudeItem(): String? = cboAltitude.selectedItem
    private fun cboGsItem(): String? = cboGs.selectedItem
    private fun cboActualTempItem(): String? = cboActualTemp.selectedItem

    /** `Conversions.ToInteger(SelectedItem)`: nothing selected is 0. */
    private fun selInt(s: String?): Int = if (s == null) 0 else DataCardNet.toInteger(s)

    init {
        // the designer's own text
        cboCAS.text = "400"; cboAltitude.text = "15000"; cboActualTemp.text = "15"; cboGs.text = "4"
        for ((k, v) in DESIGNER) labels[k] = v
    }

    // ---------------------------------------------------------------- Load and Setup

    /**
     * `cntPerformance_Load`, then what `fclsMain` runs once a theater is loaded. [ini] is Setup.ini's
     * `[Performance]` section as the app keeps it (empty the first time, when WDP's own fallbacks apply).
     */
    fun load(ini: Map<String, String>) {
        blnLoaded = false
        fillCombobox()
        setup(ini)
        intIntFuel = 7162
        setTaxiFuelValue(200)
        cboCAS.select(3)
        cboAltitude.select(21)
        cboGs.select(2)
        intCAS = selInt(cboCAS.selectedItem)
        intAltitude = selInt(cboAltitude.selectedItem)
        intPullingGs = selInt(cboGs.selectedItem)
        dblQNH_Hpa = 1013.2
        dblQNH_In = 29.92
        txtQNH_Hpa.set(DataCardNet.str(dblQNH_Hpa))
        txtQNH_In.set(DataCardNet.str(dblQNH_In))
        blnLoaded = true
    }

    /** `Setup()`: Setup.ini's `[Performance]`, each value checked, WDP's fallback where it is not a number. */
    private fun setup(ini: Map<String, String>) {
        val temp = ini["Temp"] ?: ""
        var type = ini["Type"] ?: ""
        val pitch = ini["Pitch"] ?: ""
        val power = ini["Power"] ?: ""
        val taxi = ini["TaxiFuel"] ?: ""
        val cruise = ini["CruiseAlt"] ?: ""
        txtTemp.set(if (DataCardNet.isNumeric(temp)) temp else "25")
        if (DataCardNet.isNumeric(type)) {
            if (DataCardNet.toDouble(type) == -1.0) type = "1"
            val n = DataCardNet.toDouble(type)
            if (cboType.items.size.toDouble() >= n && n >= 0 && n < cboType.items.size) cboType.select(DataCardNet.toInteger(type))
            else cboType.select(if (cboType.items.size > 1) 1 else 0)
        } else cboType.select(if (cboType.items.size > 1) 1 else 0)
        if (DataCardNet.isNumeric(pitch)) {
            if (DataCardNet.toDouble(pitch) < 8.0 || DataCardNet.toDouble(pitch) > 13.0) cboPitch.select(5) else cboPitch.selectItem(pitch)
        } else cboPitch.select(5)
        if (DataCardNet.isNumeric(power)) {
            if (DataCardNet.toDouble(power) < 0.0 || DataCardNet.toDouble(power) > 1.0) cboPower.select(0) else cboPower.select(DataCardNet.toInteger(power))
        } else cboPower.select(0)
        if (DataCardNet.isNumeric(taxi) && DataCardNet.toDouble(taxi) in 0.0..1000.0) setTaxiFuelValue(DataCardNet.toInteger(taxi))
        else setTaxiFuelValue(200)
        txtCruiseAlt.set(if (DataCardNet.isNumeric(cruise)) cruise else "25000")
    }

    /** What `fclsMain` writes to Setup.ini's `[Performance]` when it closes. */
    fun iniValues(): Map<String, String> = linkedMapOf(
        "Apt" to (labels["lblAPT_VAL"] ?: ""),
        "Rwy" to "",
        "Temp" to txtTemp.text,
        "Type" to cboType.index.toString(),
        "Pitch" to (cboPitch.selectedItem ?: ""),
        "Power" to cboPower.index.toString(),
        "TaxiFuel" to numTaxiFuel.toString(),
        "CruiseAlt" to txtCruiseAlt.text,
    )

    /** `FillCombobox()`: the F-16s WDP plans for, the Falcon BMS list. */
    private fun fillCombobox() {
        cboType.setItems(if (blnVersion) TYPES_BMS else TYPES_AF)
        cboType.selectItem("F-16C-52")
    }

    // ---------------------------------------------------------------- the handlers

    private fun txtTempTextChanged() {
        if (DataCardNet.isNumeric(txtTemp.text)) dblTempC = DataCardNet.toDouble(txtTemp.text)
        programFlow()
    }

    /**
     * Leaving the Temp C box (or Enter in it): text that is still not a number gives way to the last temperature
     * that was one, which is what the figures have been using meanwhile ([temp]).
     */
    fun tempLeave() {
        if (!wdpSlips && !DataCardNet.isNumeric(txtTemp.text)) txtTemp.set(DataCardNet.str(dblTempC))
    }

    /**
     * A cruise altitude above the service ceiling becomes the ceiling. **Fixed (D30):** WDP compared the two as
     * text, so "9000" sorted after "36680" and was replaced by it while being typed; here they are numbers.
     */
    private fun txtCruiseAltTextChanged() {
        val sc = labels["lblServiceCeiling_Val"] ?: ""
        if (wdpSlips) { if (txtCruiseAlt.text > sc && sc != "") txtCruiseAlt.set(sc); return }
        if (!DataCardNet.isNumeric(txtCruiseAlt.text) || !DataCardNet.isNumeric(sc)) return
        if (DataCardNet.toDouble(txtCruiseAlt.text) > DataCardNet.toDouble(sc)) txtCruiseAlt.set(sc)
    }

    /** Leaving the cruise altitude box (or Enter in it). */
    fun cruiseAltLeave() = programFlow()

    /** Leaving the hPa box (or Enter in it). */
    fun qnhHpaLeave() { qnhHpa(); programFlow() }

    /** Leaving the inches box (or Enter in it). */
    fun qnhInLeave() { qnhInch(); programFlow() }

    /** A cruise radio button: the old one's CheckedChanged runs, then the new one's; both run `ProgramFlow`. */
    fun check(radioName: String) {
        if (radioName == radio) return
        radio = radioName
        programFlow()
        programFlow()
    }

    /** The taxi fuel up/down: 0 to 1,000 lb; `ValueChanged` runs only on a change. */
    fun setTaxiFuel(v: Int) = setTaxiFuelValue(v.coerceIn(0, 1000))

    private fun setTaxiFuelValue(v: Int) {
        if (v == numTaxiFuel) return
        numTaxiFuel = v
        setFuel()
        programFlow()
    }

    // ---------------------------------------------------------------- the turn calculator

    fun programflowTurn() {
        if (!blnLoaded) return
        temperature()
        densityAlt()
        internMach()
        turnRad()
    }

    /**
     * `Temperature()`: the standard temperature at the altitude — WDP's 2 °C a thousand feet, the altitude rounded to
     * the thousand, which is BMS's own lapse ([Atmosphere.LAPSE_C_PER_1000FT]) — and the Actual Temp list moved to it,
     * or, once the pilot has picked one, to the same distance from standard (D29).
     */
    private fun temperature() {
        val altDiffTemp = DataCardNet.roundInt(intAltitude / 1000.0)
        intTempISA = 15 - altDiffTemp * 2
        labels["lblTempStandard"] = intTempISA.toString()
        if (wdpSlips) {
            cboActualTemp.changeText(intTempISA.toString())
            return
        }
        val want = (intTempISA + (pickedDev ?: 0)).coerceIn(-70, 30)
        writingTemp = true
        try { cboActualTemp.changeText(want.toString()) } finally { writingTemp = false }
    }

    private fun densityAlt() {
        intActualTemp = selInt(cboActualTemp.selectedItem)
        intISAdev = intActualTemp - intTempISA
        labels["lblISAdevVal"] = intISAdev.toString()
        intDensityAlt = intAltitude + intISAdev * 120
        labels["lblDensityAlt"] = intDensityAlt.toString()
    }

    private fun internMach() {
        val p0 = 29.92126
        val cs0 = 38.967854 * sqrt(288.15)
        val cs = 38.967854 * sqrt(intActualTemp.toDouble() + 273.15)
        val p = p0 * (1.0 - 6.875585599999999E-06 * intAltitude).pow(5.2558797)
        val dp = p0 * ((1.0 + 0.2 * (intCAS / cs0).pow(2.0)).pow(3.5) - 1.0)
        dblMach = (5.0 * ((dp / p + 1.0).pow(0.2857142857142857) - 1.0)).pow(0.5)
        intTAS = DataCardNet.roundInt(dblMach * cs)
        labels["lblTAS"] = intTAS.toString()
        labels["lblMach"] = DataCardNet.str(DataCardNet.round(dblMach, 2))
    }

    private fun turnRad() {
        if (intPullingGs > 0) {
            val r = DataCardNet.roundInt((intTAS * 1.69).pow(2.0) / (intPullingGs * 32.2))
            labels["lblTurnRadius"] = r.toString()
        } else if (intPullingGs == 0) labels["lblTurnRadius"] = "Error"
    }

    // ---------------------------------------------------------------- the airfield

    /** `Database(AptId, Rwy)`: the airfield's name, codes, elevation and runways; [rwy] "0" for the first. */
    fun database(apt: Apt?, rwy: String = "0") {
        airport = apt
        if (apt == null) return
        labels["lblAPT_VAL"] = apt.name
        labels["lblIcao"] = apt.icao
        labels["lblIata"] = apt.iata
        intElv = apt.elevation ?: 0
        labels["lblELV_Val"] = intElv.toString()
        fillRWY(rwy)
    }

    private fun rwyAt(i: Int): Rwy? = airport?.runways?.getOrNull(i)

    private fun fillRWY(rwy: String) {
        if (!blnLoaded) return
        val items = ArrayList<String>()
        // the first two rows are always there (WDP puts "No RWYs" in an empty one); the last two only when named
        for (i in 0..3) {
            val nr = rwyAt(i)?.nr.orEmpty()
            if (nr != "") items += nr else if (i < 2) items += "No RWYs"
        }
        cboRWY.setItems(items)
        if (items.isNotEmpty()) { cboRWY.select(0); fore["lblRWY"] = "White" } else fore["lblRWY"] = "Silver"
        if (rwy == "0") cboRWY.select(0)
        else {
            var n = 0
            do {
                cboRWY.select(n)
                if (rwy == cboRWY.selectedItem) break
                n++
            } while (n <= 3)
        }
        btnChartEnabled = airport?.hasChart == true
        programFlow()
    }

    private fun runwayChange() {
        if (!blnLoaded) return
        fun f(i: Int, v: (Rwy) -> Int): Int = rwyAt(i)?.let(v) ?: 0
        fun both(ft: Int, mFrom: Int) = "$ft f / " + DataCardNet.str(DataCardNet.round(mFrom * FT_TO_METERS_F.toDouble(), 0)) + " m"
        // Fixed (D30): WDP worked the third row's width in metres out from the fourth row's width
        fun widthRow(i: Int) = if (wdpSlips && i == 2) 3 else i
        when (cboRWY.index) {
            in 0..3 -> {
                val i = cboRWY.index
                labels["lblTODA_Val"] = both(f(i) { it.toda }, f(i) { it.toda })
                labels["lblLDA_Val"] = both(f(i) { it.lda }, f(i) { it.lda })
                labels["lblWidth_Val"] = both(f(i) { it.width }, f(widthRow(i)) { it.width })
                intTODA = f(i) { it.toda }
            }
            else -> { labels["lblTODA_Val"] = ""; labels["lblLDA_Val"] = ""; labels["lblWidth_Val"] = "" }
        }
        fore["lblTODA"] = if (labels["lblTODA_Val"] == "") "Gray" else "White"
        fore["lblLDA"] = if (labels["lblLDA_Val"] == "") "Gray" else "White"
        fore["lblWidth"] = if (labels["lblWidth_Val"] == "") "Gray" else "White"
        var text = cboRWY.selectedItem ?: ""
        intRwyHed = when (text.length) {
            2 -> if (DataCardNet.isNumeric(text)) DataCardNet.roundInt(DataCardNet.toDouble(text) * 10.0) else 0
            3 -> { text = text.substring(0, 2); if (DataCardNet.isNumeric(text)) DataCardNet.roundInt(DataCardNet.toDouble(text) * 10.0) else 0 }
            else -> 0
        }
        programFlow()
    }

    // ---------------------------------------------------------------- the jet

    /** `TypeChange()`: a new type clears the loadout; the mission's own type gets the mission's loadout back. */
    fun typeChange() {
        if (!blnLoaded) return
        aircraft(cboType.text)
        blnOverweight = false
        intLoadout = 0
        // WDP's clean jet has no drag of its own here; a jet carrying conformal tanks has theirs
        intDrag = cftDrag
        intExtFuel = 0
        if (missionItem() == cboType.text) onMissionLoadout?.invoke()
        setFuel()
        fillLoadoutDrag()
        programFlow()
    }

    /**
     * `clsAircraftData.Aircraft(Type)`: the engine by type, then the weights. The weights are BMS's own where the
     * app's aircraft data has the type (WDP reads them from BMS's vehicle table), else WDP's per-type figures.
     */
    private fun aircraft(type: String) {
        if (type == "") return
        var empty = 19900; var fuel = 7162; var max = 48000
        when (type) {
            "F-16A-15", "F-16A-15I", "F-16A-15-IAF", "F-16A-15 IAF" -> setEngine(200)
            "F-16A-MLU" -> setEngine(220)
            "F-16AM-BE", "F-16AM BAF", "F-16AM-JO", "F-16AM RJAF" -> { setEngine(220); empty = 17700; max = 37500 }
            "F-16AM-DK", "F-16AM DAF", "F-16AM RDAF" -> { setEngine(200); empty = 17610; max = 37500 }
            "F-16AM-NL", "F-16AM RNlAF", "F-16AM-NO", "F-16AM RNoAF" -> { setEngine(220); empty = 17610; max = 37500 }
            "F-16B-15", "F-16B-15-IAF", "F-16B-15 IAF" -> { setEngine(200); empty = 17600; fuel = 5898; max = 37500 }
            "F-16C-25", "F-16C-32", "F-16C-32-AGRS", "F-16C-32 AGRS", "F-16C-32E", "F-16C-32-EAF", "F-16C-32 EAF",
            "F-16C-32 ROK", "F-16C-42", "F-16C-42E", "F-16C-42 CCIP", "F-16CM-42", "KF-16C-32", "KF-16C ROK" -> setEngine(220)
            "F-16C-30", "F-16C-30-AGRS", "F-16C-30 AGRS", "F-16C-30I", "F-16C-30-IAF", "F-16C-30 IAF", "F-16C-40",
            "F-16C-40I", "F-16C-40 CCIP", "F-16C-40-EAF", "F-16C-40 EAF", "F-16C-40-IAF", "F-16C-40 IAF", "F-16CM-40" -> setEngine(100)
            "F-16C-50", "F-16C-50 CCIP", "F-16CM-50" -> setEngine(129)
            // Fixed: WDP lists these two but its table has no case for them, so the engine stayed whatever the last jet's was
            // (a block 42 is an F100-PW-220, as the list's F-16C-42 and F-16CM-42 are)
            "F-16C-42-EAF", "F-16C-42 EAF" -> if (!wdpSlips) setEngine(220)
            "F-16C-52", "F-16C-52 CCIP", "F-16C-52+", "F-16C-52+-EAF", "F-16C-52+ EAF", "F-16CM-52", "F-16I-52+",
            "F-16I-52+ RSAF", "KF-16C-52", "F-16C-AGRS", "F-16I" -> setEngine(229)
            "F-16C-52+CFT", "F-16C-52+ CFT" -> { setEngine(229); empty = 21200; fuel = 10219; max = 52000 }
            "F-16C-TB", "F-16CM-TB", "F-16CM TB" -> { setEngine(229); empty = 19500 }
            "F-16D-30", "F-16D-30-IAF", "F-16D-30 IAF" -> { setEngine(100); empty = 21234; fuel = 5898 }
            "F-16D-40", "F-16DM-40" -> { setEngine(100); empty = 20600; fuel = 5898 }
            "F-16D-40I", "F-16D-40-IAF", "F-16D-40 IAF" -> { setEngine(100); empty = 21826; fuel = 5898 }
            "F-16D-52", "F-16DM-52" -> { setEngine(229); empty = 20300; fuel = 5898 }
            "F-16I-52+-CFT", "F-16I-52+ CFT", "F-16I-CFT" -> { setEngine(229); empty = 22900; fuel = 8847; max = 52000 }
        }
        val known = aircraftData(type)
        weightsKnown = true
        // a type WDP names CFT carries its conformal tanks: BMS's own figures for them on BMS's own jet (see [cft])
        cft = if (wdpSlips) null else known?.cft
        cftOn = cft != null && type.contains("CFT", ignoreCase = true)
        baseEmpty = known?.empty ?: empty
        baseFuel = known?.fuel ?: fuel
        intEmpty = baseEmpty + if (cftOn) cft!!.empty else 0
        intIntFuel = baseFuel + if (cftOn) cft!!.fuel else 0
        intMax = known?.max ?: max
        labels["lblDry_Val"] = "$intEngDry lbs"
        labels["lblMaxPower_Val"] = "$intEngMax lbs"
        labels["lblEmpty_Val"] = intEmpty.toString()
        labels["lblMax_Val"] = intMax.toString()
        setFuel()
    }

    private fun setEngine(id: Int) {
        currentEngine = id
        labels["lblPowerPlant_val"] = when (id) { 100 -> "F110-GE-100"; 129 -> "F110-GE-129"; 200 -> "F100-PW-200"; 220 -> "F100-PW-220"; else -> "F100-PW-229" }
        when (id) {
            100 -> { intEngDry = 14670; intEngMax = 28948 }
            129 -> { intEngDry = 17155; intEngMax = 28948 }
            200 -> { intEngDry = 14670; intEngMax = 23830 }
            220 -> { intEngDry = 14590; intEngMax = 23770 }
            229 -> { intEngDry = 17000; intEngMax = 28500 }
        }
        labels["lblMilP_Val"] = when (id) { 100 -> "103 %"; 129 -> "108 %"; 229 -> "97 %"; else -> "96 %" }
    }

    /**
     * A mission flight of another aircraft than the F-16 ([missionIsF16] false). The engine charts are the F-16's, so
     * the page works nothing out for it, as `DoCalculations` returns at once in WDP. **Fixed:** WDP left the type list,
     * the engine and the weights on the last F-16 it had, and its figures standing, so the page and the DataCard's
     * gross weight and fuel were an F-16's. Here Type shows the flight's own aircraft by name (the list itself stays
     * the F-16s WDP plans for), no engine, that aircraft's own weights from Falcon BMS's data ([data]; blank where the
     * app has none), and its loadout back through [onMissionLoadout]; the figures are blank ([doCalculations]).
     */
    fun otherAircraft(name: String, data: AcData?) {
        if (!blnLoaded) return
        cboType.text = name
        currentEngine = 0
        intEngDry = 0
        intEngMax = 0
        for (k in ENGINE_LABELS) labels[k] = ""
        weightsKnown = data != null
        cft = null
        cftOn = false
        intEmpty = data?.empty ?: 0
        intIntFuel = data?.fuel ?: 0
        intMax = data?.max ?: 0
        labels["lblEmpty_Val"] = data?.empty?.toString() ?: ""
        labels["lblMax_Val"] = data?.max?.toString() ?: ""
        blnOverweight = false
        intLoadout = 0
        intDrag = 0
        intExtFuel = 0
        onMissionLoadout?.invoke()
        setFuel()
        fillLoadoutDrag()
        programFlow()
    }

    /** What the Loadout window hands back when it closes (`fclsLoadout.CloseForm`). */
    fun applyLoadout(loadWeight: Int, totalDrag: Int, intFuel: Int, extFuel: Int) {
        intLoadout = loadWeight
        intDrag = totalDrag
        intIntFuel = intFuel
        intExtFuel = extFuel
        setFuel()
        fillLoadoutDrag()
    }

    fun fillLoadoutDrag() {
        labels["lblLoadout_val"] = intLoadout.toString()
        labels["lblDrag_Val"] = intDrag.toString()
    }

    fun setFuel() {
        if (!blnLoaded) return
        intBlockFuel = intIntFuel + intExtFuel - fuelBurnt
        intTakeoffFuel = intBlockFuel - numTaxiFuel
        labels["lblBlockFuel_Val"] = intBlockFuel.toString()
        labels["lblFuel_val"] = if (departed) "----" else intTakeoffFuel.toString()
    }

    fun weights() {
        if (!blnLoaded) return
        if (!weightsKnown) {
            // another aircraft the app has no weights for: no gross weight rather than one without its empty weight
            intGrossWt = 0
            blnOverweight = false
            fore["lblGross_Val"] = "White"
            labels["lblLoadout_val"] = intLoadout.toString()
            labels["lblGross_Val"] = ""
            return
        }
        intGrossWt = intEmpty + intTakeoffFuel + intLoadout
        blnOverweight = intGrossWt > intMax
        fore["lblGross_Val"] = if (blnOverweight) "Red" else "White"
        labels["lblLoadout_val"] = intLoadout.toString()
        labels["lblGross_Val"] = intGrossWt.toString()
    }

    // ---------------------------------------------------------------- the weather

    fun windDir() {
        if (!blnLoaded) return
        strWeather[1] = txtWindDir.text
        var text = strWeather[1]
        if (text == "") text = "0"
        var num: Int
        if (DataCardNet.isNumeric(text)) num = DataCardNet.toInteger(text)
        else { num = 0; text = "0"; txtWindDir.set("0") }
        if (num < 0) { num = 0; text = "0"; txtWindDir.set("0") }
        if (num > 359) { num = 359; text = "359"; txtWindDir.set("359") }
        strWeather[1] = text
        components()
    }

    fun windSpd() {
        if (!blnLoaded) return
        strWeather[2] = txtWindSpd.text
        val text = strWeather[2].ifEmpty { "0" }
        var num: Int
        if (DataCardNet.isNumeric(text)) num = DataCardNet.toInteger(text)
        else { num = 0; txtWindSpd.set("0") }
        if (num < 0) { num = 0; txtWindSpd.set("0") }
        if (num > 200) { num = 200; txtWindSpd.set("200") }
        strWeather[2] = num.toString()
        components()
    }

    /** The head and cross wind on the runway in use: `C.DTR` is a float and the angle is multiplied as one. */
    private fun components() {
        val num2 = DataCardNet.roundInt(abs(intRwyHed.toDouble() - DataCardNet.toDouble(strWeather[1])))
        val num3 = (DTR_F * num2.toFloat()).toDouble()
        val spd = if (DataCardNet.isNumeric(strWeather[2])) DataCardNet.toInteger(strWeather[2]) else 0
        intHWC = DataCardNet.roundInt(cos(num3) * spd)
        intCWC = DataCardNet.roundInt(sin(num3) * spd)
        labels["lblHWC"] = "HWC: $intHWC"
        labels["lblCWC"] = "CWC: ${abs(intCWC)}"
    }

    /**
     * The field's temperature. **Fixed (D29):** WDP put "20" in the box the moment it held anything but a number —
     * a lone "-" on the way to "-5" included — and planned the day at 20 °C. Text that is not a number is refused:
     * the figures stay on the last temperature that was one, and leaving the box puts it back ([tempLeave]).
     */
    fun temp() {
        if (!blnLoaded) return
        if (DataCardNet.isNumeric(txtTemp.text)) {
            dblTempC = DataCardNet.toDouble(txtTemp.text)
            dblTempF = Atmosphere.celsiusToFahrenheit(dblTempC)
            labels["lblTempF"] = "F: " + DataCardNet.str(DataCardNet.round(dblTempF, 0))
        } else if (wdpSlips) txtTemp.set("20")
        strWeather[3] = DataCardNet.str(dblTempC)
    }

    /**
     * A new mission's weather before it gives its own: calm, and the standard altimeter the page loads with ([load]).
     * WDP's `CreateAtis` writes every flight's own wind and QNH into these boxes when the flight is picked; a mission
     * that brings none (a save's flight with no printed briefing of its own) must not fly on the last mission's. The
     * temperature is left as it is: WDP keeps it between runs (Setup.ini's `Temp`).
     */
    fun calmStandardDay() {
        txtWindDir.set("0")
        txtWindSpd.set("0")
        dblQNH_Hpa = 1013.2
        dblQNH_In = 29.92
        txtQNH_Hpa.set(DataCardNet.str(dblQNH_Hpa))
        txtQNH_In.set(DataCardNet.str(dblQNH_In))
        programFlow()
    }

    fun qnhHpa() {
        if (DataCardNet.isNumeric(txtQNH_Hpa.text)) dblQNH_Hpa = DataCardNet.toDouble(txtQNH_Hpa.text)
        else { dblQNH_Hpa = 1013.0; txtQNH_Hpa.set(DataCardNet.str(dblQNH_Hpa)) }
        if (dblQNH_Hpa < 914.0) { dblQNH_Hpa = 914.0; txtQNH_Hpa.set(DataCardNet.str(dblQNH_Hpa)) }
        if (dblQNH_Hpa > 1050.0) { dblQNH_Hpa = 1050.0; txtQNH_Hpa.set(DataCardNet.str(dblQNH_Hpa)) }
        dblQNH_In = Atmosphere.hpaToInches(DataCardNet.roundInt(dblQNH_Hpa))
        txtQNH_In.set(DataCardNet.fmt(dblQNH_In, "#.#0"))
    }

    fun qnhInch() {
        if (DataCardNet.isNumeric(txtQNH_In.text)) dblQNH_In = DataCardNet.toDouble(txtQNH_In.text)
        else { dblQNH_In = 29.92; txtQNH_In.set(DataCardNet.str(dblQNH_In)) }
        if (dblQNH_In < 27.0) { dblQNH_In = 27.0; txtQNH_In.set(DataCardNet.str(dblQNH_In)) }
        if (dblQNH_In > 31.0) { dblQNH_In = 31.0; txtQNH_In.set(DataCardNet.str(dblQNH_In)) }
        dblQNH_Hpa = Atmosphere.inchesToHpa(dblQNH_In)
        txtQNH_Hpa.set(DataCardNet.fmt(dblQNH_Hpa, "#.0"))
    }

    // ---------------------------------------------------------------- ProgramFlow

    fun programFlow() {
        if (!blnLoaded) return
        labels["lblISA_Dev"] = "ISA dev: " + isaDev()
        weights()
        temp()
        windDir()
        windSpd()
        doCalculations()
    }

    private fun doCalculations() {
        if (!blnLoaded) return
        var milFactor = 1.0
        val runwayLength = intTODA - 150
        blnRefusal = false
        // a mission flight that is not an F-16 gets no figures at all — and, fixed, none of the last jet's either
        if (missionIsF16 == false) {
            if (!wdpSlips) {
                for (k in FIGURES) labels[k] = ""
                fore["lblRefusal_Val"] = "White"
            }
            return
        }
        val pitch = cboPitch.selectedItem?.takeIf { DataCardNet.isNumeric(it) }?.let { DataCardNet.toInteger(it) } ?: 13
        var ab = false
        if (cboPower.text == "Full AB") {
            when (currentEngine) {
                100, 129, 200, 220 -> milFactor = 0.550000011920929
                229 -> milFactor = 0.5099999904632568
            }
            ab = true
        }
        var cruise: Int
        if (DataCardNet.isNumeric(txtCruiseAlt.text)) cruise = DataCardNet.toInteger(txtCruiseAlt.text)
        else { cruise = 25000; txtCruiseAlt.set(cruise.toString()) }
        // D28: the pressure altitude with its right sign and size
        val pressAlt = if (wdpSlips) Atmosphere.wdpPressureAltitudeFt(intElv, dblQNH_In) else Atmosphere.pressureAltitudeFt(intElv, dblQNH_In)
        // D31: the charts' own correction for a day off standard, which WDP always handed 0 (its ISA_Dev is never set)
        val isa = if (wdpSlips) 0 else isaDev()
        var liftOff = 0; var rotate = 0; var refusal = 0
        var distMil = 0.0; var timeMil = 0.0; var distMax = 0.0; var timeMax = 0.0; var fuelMil = 0; var fuelMax = 0
        val e = engines[currentEngine]
        if (e != null) {
            dblTOFactor = e.takeoffFactor(pressAlt.toDouble(), decimalOf(dblTempC), milFactor)
            liftOff = DataCardNet.roundInt(e.takeOffSpeed(intGrossWt, pitch))
            rotate = DataCardNet.roundInt(e.rotationSpeed(liftOff, ab))
            refusal = DataCardNet.roundInt(refusalSpeed(e, ab, runwayLength))
            labels["lblScheduleMax_Val"] = climbScheduleAb(e)
            labels["lblScheduleMil_Val"] = e.climbScheduleMil(intDrag)
            labels["lblCruiseCeiling_Val"] = e.cruiseCeiling(intGrossWt, intDrag.toDouble()).toString()
            labels["lblOptCruiseAlt_val"] = DataCardNet.str(e.optCruise(intDrag.toDouble(), intGrossWt.toDouble()))
            val margin = when (currentEngine) { 200 -> 600.0; 220 -> 700.0; 229 -> 670.0; else -> 650.0 }
            labels["lblServiceCeiling_Val"] = DataCardNet.str(DataCardNet.toDouble(labels["lblCruiseCeiling_Val"]) + margin)
            val service = labels["lblServiceCeiling_Val"]!!.let { if (DataCardNet.isNumeric(it)) DataCardNet.toInteger(it) else 45000 }
            if (cruise > service) { cruise = service; txtCruiseAlt.set(cruise.toString()) }
            labels["lblOptMach_Val"] = DataCardNet.fmt(e.optMach(intDrag), "#0.00")
            val alt = when (radio) {
                "radOptCruiseAlt" -> DataCardNet.toInteger(labels["lblOptCruiseAlt_val"])
                "radCruiseCeiling" -> DataCardNet.toInteger(labels["lblCruiseCeiling_Val"])
                "radServiceCeiling" -> DataCardNet.toInteger(labels["lblServiceCeiling_Val"])
                else -> cruise
            }
            // from the field's elevation to the altitude: each figure at the altitude less the same at the field
            val g = intGrossWt
            val d = intDrag
            val ci = e.milClimbIndex(g, alt)
            val d7 = e.milClimbDistance(ci, d, isa)
            val d8 = e.milClimbTime(ci, d, isa)
            val ci2 = e.abClimbIndex(g, alt)
            val d9 = e.abClimbDistance(ci2, d, isa)
            val d10 = e.abClimbTime(ci2, d, isa)
            val fu = e.milFuelUsed(e.milFuelIndex(g, alt), d, isa)
            val fu2 = e.abFuelUsed(e.abFuelIndex(g, alt), d, isa)
            val ciE = e.milClimbIndex(g, intElv)
            distMil = d7 - e.milClimbDistance(ciE, d, isa)
            timeMil = d8 - e.milClimbTime(ciE, d, isa)
            val ci2E = e.abClimbIndex(g, intElv)
            distMax = d9 - e.abClimbDistance(ci2E, d, isa)
            timeMax = d10 - e.abClimbTime(ci2E, d, isa)
            fuelMil = fu - e.milFuelUsed(e.milFuelIndex(g, intElv), d, isa)
            fuelMax = fu2 - e.abFuelUsed(e.abFuelIndex(g, intElv), d, isa)
        }
        labels["lblDistMil_Val"] = DataCardNet.fmt(distMil, "#0.0")
        labels["lblFuelMil_Val"] = fuelMil.toString()
        labels["lblTimeMil_Val"] = minSec(timeMil)
        labels["lblDistMax_Val"] = DataCardNet.fmt(distMax, "#0.0")
        labels["lblFuelMax_Val"] = fuelMax.toString()
        labels["lblTimeMax_Val"] = minSec(timeMax)
        labels["lblFactor_Val"] = DataCardNet.str(DataCardNet.round(dblTOFactor, 3))
        labels["lblLiftOff_Val"] = liftOff.toString()
        labels["lblRotate_Val"] = rotate.toString()
        labels["lblRefusal_Val"] = refusal.toString()
        blnRefusal = rotate >= refusal
        fore["lblRefusal_Val"] = if (blnRefusal) "Red" else "White"
    }

    /** The field's deviation from a standard day, whole degrees: WDP's `GetISADev`, BMS's 2 °C per 1,000 ft. */
    private fun isaDev(): Int = Atmosphere.isaDeviation(intElv, dblTempC)

    /**
     * The refusal speed. **Fixed (D31):** every engine's `RwyRefusal` takes the upper line of its 12,000–15,000 ft
     * cell from the wrong end (`array10[1] - array9[0]`, where every other cell of the chart has `[1] - [1]`), so a
     * runway of that length read a line that is not on the chart, and the refusal speed jumped at both ends of the
     * cell. The chart is linear in the runway's length across a cell, so the intended answer is the blend of the
     * cell's two edges, which the program reads correctly (exact unless an edge is at the chart's 200 kt cap); the
     * headwind correction is then applied to it, as `RefusalSpeed` applies it.
     */
    private fun refusalSpeed(e: Engine, ab: Boolean, runwayLength: Int): Double {
        val gross = intGrossWt.toDouble()
        val hwc = intHWC.toDouble()
        if (wdpSlips || runwayLength <= 12000 || runwayLength >= 15000) return e.refusalSpeed(ab, dblTOFactor, gross, runwayLength, hwc)
        val t = (runwayLength - 12000) / 3000.0
        val lo = e.refusalSpeed(ab, dblTOFactor, gross, 12000, 0.0)
        val hi = e.refusalSpeed(ab, dblTOFactor, gross, 15000, 0.0)
        return e.refusalWindCorrection(lo + (hi - lo) * t, hwc)
    }

    /**
     * The MAX AB climb schedule. **Fixed (D31):** the GE-100's chart tests `> 150 && <= 150` and `> 200 && <= 200`,
     * which nothing passes, so any drag from 101 to 200 fell through to the heaviest schedule (480 / 0.85). The
     * GE-129's chart — the same schedules, the same strings — has `> 100 && <= 150` → 565 / 0.90 and
     * `> 150 && <= 200` → 550 / 0.90, and the GE-100's own dead branches hold those two strings.
     */
    private fun climbScheduleAb(e: Engine): String {
        if (!wdpSlips && e.id == 100 && intDrag in 101..200) return if (intDrag <= 150) "565 / 0.90" else "550 / 0.90"
        return e.climbScheduleAb(intDrag)
    }

    /** "3 min 12 sec" from minutes: the whole minutes and the rest times sixty, each rounded to even. */
    private fun minSec(minutes: Double): String {
        val frac = minutes % 1.0
        return "${DataCardNet.roundInt(minutes - frac)} min ${DataCardNet.roundInt(frac * 60.0)} sec"
    }

    /** `new decimal(double)`: fifteen significant digits, which is what the take-off chart compares. */
    private fun decimalOf(v: Double): Double = DataCardNet.toDouble(DataCardNet.str(v))

    /** The briefing's aircraft and a type in the list are the same jet: case, spaces and hyphens aside. */
    fun sameType(a: String?, b: String?): Boolean = a != null && b != null && norm(a) == norm(b)

    /**
     * The item of the type list that is the briefing's jet ([name], then its other names [aliases]), found in this
     * order: the same name; the same model letter, block and "+" — BMS names a theater's own F-16s its own way
     * ("F-16C B52+ HAF" in Hellas, which is the list's "F-16C-52+", with the F100-PW-229), and a G or J after the
     * letter is the M ("F-16CJ-50" is the list's "F-16CM-50", "F-16DG-40 IAF" its "F-16DM-40"); the same block
     * without a "+" the list does not have ("F-16C 50+ THK", "F-16D-52+ RSAF"); the same name with the model's M
     * aside ("F-16A RDAF" is the list's "F-16AM RDAF"); and last the list's first jet of the same model ("F-16AM
     * BAC"). Without this the page ran such a flight as whatever jet the list last had, locked, with no stores on
     * it — 15 of the 68 F-16 names in the app's aircraft data found nothing before. Null for a name with no F-16
     * model in it at all.
     */
    fun typeItem(name: String, vararg aliases: String): String? {
        val names = listOf(name) + aliases
        val items = cboType.items
        for (n in names) items.firstOrNull { sameType(it, n) }?.let { return it }
        fun key(s: String): String? = BLOCK.find(s)?.let { m -> m.groupValues[1].uppercase().take(1) + m.groupValues[2] + m.groupValues[3] }
        for (n in names) {
            val k = key(n) ?: continue
            items.firstOrNull { key(it) == k }?.let { return it }
            if (k.endsWith("+")) items.firstOrNull { key(it) == k.dropLast(1) }?.let { return it }
        }
        fun bare(s: String) = MODEL_M.replace(norm(s), "$1")
        for (n in names) { val b = bare(n); items.firstOrNull { bare(it) == b }?.let { return it } }
        fun model(s: String): String? = MODEL.find(s)?.let { it.groupValues[1].uppercase() + it.groupValues[2].uppercase() }
        val m = names.firstNotNullOfOrNull { model(it) } ?: return null
        return items.firstOrNull { model(it) == m } ?: items.firstOrNull { model(it)?.take(1) == m.take(1) }
    }

    companion object {
        const val DTR_F = 0.01745329f
        const val FT_TO_METERS_F = 0.30488f

        fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() || it == '+' }

        /** The engine's labels, blank for another aircraft ([otherAircraft]). */
        private val ENGINE_LABELS = listOf("lblPowerPlant_val", "lblDry_Val", "lblMaxPower_Val", "lblMilP_Val")

        /** Everything `DoCalculations` works out, blank for another aircraft. */
        private val FIGURES = listOf(
            "lblScheduleMax_Val", "lblScheduleMil_Val", "lblCruiseCeiling_Val", "lblOptCruiseAlt_val", "lblServiceCeiling_Val",
            "lblOptMach_Val", "lblDistMil_Val", "lblFuelMil_Val", "lblTimeMil_Val", "lblDistMax_Val", "lblFuelMax_Val",
            "lblTimeMax_Val", "lblFactor_Val", "lblLiftOff_Val", "lblRotate_Val", "lblRefusal_Val",
        )

        /** "F-16C B52+ HAF", "F-16CM-40", "F-16CJ-50", "F-16D-40I": the model letter, the block and a "+" after it */
        private val BLOCK = Regex("""F-16\s*([A-D]|I)[MGJ]?[\s-]*B?(\d{2})(\+?)""", RegexOption.IGNORE_CASE)

        /** The model letter and an M after it: "F-16AM BAC" is A and M */
        private val MODEL = Regex("""F-16\s*([A-D]|I)(M?)""", RegexOption.IGNORE_CASE)

        /** A normalised name's model M ("f16amrdaf"), taken out by [typeItem] to compare the rest */
        private val MODEL_M = Regex("""^(k?f16[a-di])m""")

        /** A jet's model letter and an M after it ("F-16AM BAF" is "AM"); null for a name with no F-16 model. */
        fun modelKey(s: String): String? = MODEL.find(s)?.let { it.groupValues[1].uppercase() + it.groupValues[2].uppercase() }

        /**
         * A jet's model letter, block and "+" as [typeItem] compares them ("F-16C B52+ HAF" and "F-16CM-52+" are both
         * "C52+"); null for a name without a block.
         */
        fun blockKey(s: String): String? = BLOCK.find(s)?.let { m -> m.groupValues[1].uppercase().take(1) + m.groupValues[2] + m.groupValues[3] }

        /** The type list WDP shows under Falcon BMS. */
        val TYPES_BMS = listOf(
            "F-16A-15", "F-16A-15 IAF", "F-16A-15-IAF", "F-16AM-BE", "F-16AM BAF", "F-16AM-DK", "F-16AM RDAF", "F-16AM-JO",
            "F-16AM RJAF", "F-16AM-NL", "F-16AM RNlAF", "F-16AM-NO", "F-16AM RNoAF", "F-16B-15", "F-16B-15-IAF", "F-16B-15 IAF",
            "F-16C-25", "F-16C-30", "F-16C-30 AGRS", "F-16C-30-AGRS", "F-16C-30I", "F-16C-30 IAF", "F-16C-30-IAF", "F-16C-32",
            "F-16C-32 AGRS", "F-16C-32-AGRS", "F-16C-32E", "F-16C-32 EAF", "F-16C-32-EAF", "F-16CM-40", "F-16C-40 EAF",
            "F-16C-40-EAF", "F-16C-40I", "F-16C-40-IAF", "F-16C-40 IAF", "F-16CM-42", "F-16C-42-EAF", "F-16C-42 EAF", "F-16CM-50",
            "F-16CM-52", "F-16C-52+", "F-16C-52+CFT", "F-16C-52+-EAF", "F-16CM-TB", "F-16CM TB", "F-16D-30-IAF", "F-16D-30 IAF",
            "F-16DM-40", "F-16D-40I", "F-16D-40 IAF", "F-16D-40-IAF", "F-16DM-52", "F-16I-52+", "F-16I-52+ CFT", "F-16I-52+-CFT",
            "F-16I-52+ RSAF", "F-16I", "F-16I-CFT", "KF-16C-32", "KF-16C-52",
        )

        /** The Allied Force list, for completeness. */
        val TYPES_AF = listOf(
            "F-16A-MLU", "F-16C-30", "F-16C-32", "F-16C-40", "F-16C-40 CCIP", "F-16D-40", "F-16C-42", "F-16C-42 CCIP",
            "F-16C-50", "F-16C-50 CCIP", "F-16C-52", "F-16C-52 CCIP", "F-16D-52",
        )

        /** What the designer shows in the labels the program fills, before it fills them. */
        private val DESIGNER = mapOf(
            "lblTempStandard" to "15", "lblTAS" to "100", "lblDensityAlt" to "100", "lblMach" to "0.82",
            "lblTurnRadius" to "100", "lblISAdevVal" to "15",
        )
    }
}
