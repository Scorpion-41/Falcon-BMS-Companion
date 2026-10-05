package com.bmscompanion.desktop.bridge

import com.bmscompanion.app.data.wdp.Engine
import com.bmscompanion.app.data.wdp.Engines
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.math.abs
import kotlin.math.max

/**
 * `--wdppagetest engines <reference.tsv> <out.txt>` — the five engine classes, the port against the program.
 *
 * The reference is `wdpref page Engines`: every method the Performance page uses, called at random points —
 * mostly the range the page feeds, some on a breakpoint, some past both ends — with the program's answer. The port
 * answers the same call from its translated code (`EngineCode`). A number that differs only in its last bits (the
 * program ran on the x87 unit) is reported as "close", not as a match, and anything further as a mismatch.
 */
internal fun wdpEnginesTest(reference: File, out: File): String = buildString {
    appendLine("Weapon Delivery Planner engines — the port against the program")
    appendLine("reference: ${reference.path}")
    if (!reference.isFile) { appendLine(); appendLine("FAIL — no reference. Run `wdpref page Engines` from WDP's folder first."); return@buildString }
    val engines = try { runBlocking { Engines.load() } } catch (e: Exception) { appendLine("FAIL — ${e.message}"); return@buildString }
    val byClass = Engines.classes.entries.associate { (id, cls) -> cls to engines.getValue(id) }

    class Tally { var exact = 0; var close = 0; var bad = 0; val examples = ArrayList<String>() }
    val tally = LinkedHashMap<String, Tally>()
    var rows = 0
    for (line in reference.readLines()) {
        val f = line.split('\t')
        if (f.size < 4) continue
        val e = byClass[f[0]] ?: continue
        val method = f[1]
        val a = f[2].split(',')
        val want = f[3]
        rows++
        val got = try { call(e, method, a) } catch (x: Exception) { "THREW" }
        val t = tally.getOrPut("${f[0]}.$method") { Tally() }
        val w = want.toDoubleOrNull()
        val g = got.toDoubleOrNull()
        when {
            want == got || (w != null && g != null && w == g) -> t.exact++
            w != null && g != null && abs(w - g) <= 1e-9 * max(1.0, abs(w)) -> t.close++
            else -> { t.bad++; if (t.examples.size < 4) t.examples += "(${f[2]}) program $want, port $got" }
        }
    }
    var exact = 0; var close = 0; var bad = 0
    for ((name, t) in tally) {
        exact += t.exact; close += t.close; bad += t.bad
        appendLine((if (t.bad == 0) "ok    " else "FAIL  ") + name.padEnd(28) + "exact ${t.exact}, last-bit ${t.close}, differ ${t.bad}")
        for (x in t.examples) appendLine("        $x")
    }
    appendLine()
    appendLine("calls: $rows — exact $exact, differing only in the last bits $close, differing $bad")
    appendLine(if (bad == 0) "PASS" else "FAIL")
    out.writeText(toString())
}

/** One of the page's engine calls, by the program's method name, with the harness's arguments; the answer as the harness writes it. */
private fun call(e: Engine, method: String, a: List<String>): String {
    fun d(i: Int) = a[i].toDouble()
    fun i(i: Int) = a[i].toDouble().toInt()
    fun b(i: Int) = a[i].equals("True", true)
    fun num(v: Double) = v.toString().removeSuffix(".0")
    return when (method) {
        "TakeoffFactor" -> num(e.takeoffFactor(d(0), d(1), d(2)))
        "OptCruise" -> num(e.optCruise(d(0), d(1)))
        "GetOptMach" -> num(e.optMach(i(0)))
        "CruiseCeiling" -> e.cruiseCeiling(i(0), d(1)).toString()
        "ClimbScheduleMIL" -> e.climbScheduleMil(i(0))
        "ClimbScheduleAB" -> e.climbScheduleAb(i(0))
        "MilFuelIndex" -> num(e.milFuelIndex(i(0), i(1)))
        "MilClimbIndex" -> num(e.milClimbIndex(i(0), i(1)))
        "ABFuelIndex" -> num(e.abFuelIndex(i(0), i(1)))
        "ABClimbIndex" -> num(e.abClimbIndex(i(0), i(1)))
        "MilFuelUsed" -> e.milFuelUsed(d(0), i(1)).toString()
        "ABFuelUsed" -> e.abFuelUsed(d(0), i(1)).toString()
        "MilClimbDistance" -> num(e.milClimbDistance(d(0), i(1)))
        "ABClimbDistance" -> num(e.abClimbDistance(d(0), i(1)))
        "MilClimbTime" -> num(e.milClimbTime(d(0), i(1)))
        "ABClimbTime" -> num(e.abClimbTime(d(0), i(1)))
        "TakeOffSpeed" -> num(e.takeOffSpeed(i(0), i(1)))
        "RotationSpeed" -> num(e.rotationSpeed(i(0), b(1)))
        "AccRefusal" -> num(e.accRefusal(b(0), d(1), d(2)))
        "RwyRefusal" -> num(e.rwyRefusal(b(0), d(1), i(2)))
        "RefusalWindCorrection" -> num(e.refusalWindCorrection(d(0), d(1)))
        "RefusalSpeed" -> num(e.refusalSpeed(b(0), d(1), d(2), i(3), d(4)))
        else -> "no such method"
    }
}
