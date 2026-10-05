package com.bmscompanion.app.data.wdp

import com.bmscompanion.app.data.Repo

/**
 * The F-16's five engines as Weapon Delivery Planner knows them — Falcas's `clsGE100`, `clsGE129`, `clsPW200`,
 * `clsPW220` and `clsPW229` — with every method the Performance page calls, under the program's own names.
 *
 * The methods are the program's, run from their translated code ([EngineCode]); this only gives them their
 * types. Arguments go in as the page passes them (a gross weight in pounds, a pressure altitude in feet, a
 * temperature in °C); each method rounds or scales them itself, as it does in the program.
 *
 * Worth knowing, because they are kept: the GE-100's AB climb schedule answers the heaviest schedule for any drag
 * between 101 and 200 (its two middle tests can never be true); every engine's `RwyRefusal` takes the upper line of
 * the 12,000–15,000 ft cell from the wrong end; the PW-229's schedules test `> 35` where `> 350` was meant. The
 * page always passes an ISA deviation of 0 to the climb and fuel methods (the field is never assigned).
 */
class Engine internal constructor(
    /** 100, 129, 200, 220 or 229: WDP's `CurrentEngine`. */
    val id: Int,
    private val code: EngineCode,
) {
    val cls: String = Engines.classes.getValue(id)

    private fun n(method: String, vararg a: Double) = code.call(cls, method, *a)
    private fun b(v: Boolean) = if (v) 1.0 else 0.0

    fun takeoffFactor(pressureAlt: Double, tempC: Double, milFactor: Double) = n("TakeoffFactor", pressureAlt, tempC, milFactor)
    fun optCruise(dragIndex: Double, grossWeight: Double) = n("OptCruise", dragIndex, grossWeight)
    fun optMach(dragIndex: Int) = n("GetOptMach", dragIndex.toDouble())
    fun cruiseCeiling(grossWeight: Int, dragIndex: Double) = n("CruiseCeiling", grossWeight.toDouble(), dragIndex).toInt()
    fun climbScheduleMil(dragIndex: Int) = code.callText(cls, "ClimbScheduleMIL", dragIndex.toDouble())
    fun climbScheduleAb(dragIndex: Int) = code.callText(cls, "ClimbScheduleAB", dragIndex.toDouble())

    fun milFuelIndex(grossWeight: Int, cruiseAlt: Int) = n("MilFuelIndex", grossWeight.toDouble(), cruiseAlt.toDouble())
    fun milClimbIndex(grossWeight: Int, cruiseAlt: Int) = n("MilClimbIndex", grossWeight.toDouble(), cruiseAlt.toDouble())
    fun abFuelIndex(grossWeight: Int, cruiseAlt: Int) = n("ABFuelIndex", grossWeight.toDouble(), cruiseAlt.toDouble())
    fun abClimbIndex(grossWeight: Int, cruiseAlt: Int) = n("ABClimbIndex", grossWeight.toDouble(), cruiseAlt.toDouble())

    fun milFuelUsed(fuelIndex: Double, dragIndex: Int, isaDev: Int = 0) = n("MilFuelUsed", fuelIndex, dragIndex.toDouble(), isaDev.toDouble()).toInt()
    fun abFuelUsed(fuelIndex: Double, dragIndex: Int, isaDev: Int = 0) = n("ABFuelUsed", fuelIndex, dragIndex.toDouble(), isaDev.toDouble()).toInt()
    fun milClimbDistance(climbIndex: Double, dragIndex: Int, isaDev: Int = 0) = n("MilClimbDistance", climbIndex, dragIndex.toDouble(), isaDev.toDouble())
    fun abClimbDistance(climbIndex: Double, dragIndex: Int, isaDev: Int = 0) = n("ABClimbDistance", climbIndex, dragIndex.toDouble(), isaDev.toDouble())
    fun milClimbTime(climbIndex: Double, dragIndex: Int, isaDev: Int = 0) = n("MilClimbTime", climbIndex, dragIndex.toDouble(), isaDev.toDouble())
    fun abClimbTime(climbIndex: Double, dragIndex: Int, isaDev: Int = 0) = n("ABClimbTime", climbIndex, dragIndex.toDouble(), isaDev.toDouble())

    fun takeOffSpeed(grossWt: Int, pitch: Int) = n("TakeOffSpeed", grossWt.toDouble(), pitch.toDouble())
    fun rotationSpeed(takeoffSpeed: Int, maxAb: Boolean) = n("RotationSpeed", takeoffSpeed.toDouble(), b(maxAb))
    fun refusalSpeed(ab: Boolean, toFactor: Double, grossWt: Double, runwayLength: Int, hwc: Double) =
        n("RefusalSpeed", b(ab), toFactor, grossWt, runwayLength.toDouble(), hwc)

    // the refusal speed's three parts, for the checks
    fun accRefusal(ab: Boolean, toFactor: Double, grossWt: Double) = n("AccRefusal", b(ab), toFactor, grossWt)
    fun rwyRefusal(ab: Boolean, acc: Double, runwayLength: Int) = n("RwyRefusal", b(ab), acc, runwayLength.toDouble())
    fun refusalWindCorrection(refusalSpeed: Double, hwc: Double) = n("RefusalWindCorrection", refusalSpeed, hwc)
}

/**
 * The five engines, from `data/wdp/engines/engines.wdpc` (`Repo.text`, which all three platforms provide), read
 * once. A method is compiled the first time it is called, so only the engine of the jet being planned costs
 * anything.
 */
object Engines {
    /** WDP's `CurrentEngine` numbers and the class each one is. */
    val classes: Map<Int, String> = linkedMapOf(100 to "clsGE100", 129 to "clsGE129", 200 to "clsPW200", 220 to "clsPW220", 229 to "clsPW229")

    const val ASSET = "data/wdp/engines/engines.wdpc"

    private var cache: Map<Int, Engine>? = null

    /** Every engine; throws with the reason when the code cannot be read. */
    suspend fun load(): Map<Int, Engine> {
        cache?.let { return it }
        val text = Repo.text(ASSET) ?: throw IllegalStateException("the engine code ($ASSET) is missing")
        return of(text).also { cache = it }
    }

    /** From the code's text in hand. */
    fun of(text: String): Map<Int, Engine> {
        val code = EngineCode.parse(text) ?: throw IllegalStateException("the engine code cannot be read")
        return classes.mapValues { (id, _) -> Engine(id, code) }
    }
}
