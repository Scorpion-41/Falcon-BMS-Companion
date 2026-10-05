package com.bmscompanion.desktop.bridge

import com.bmscompanion.app.data.wdp.Ballistics
import java.io.File

/**
 * `--wdpporttest <reference.csv> <out.txt>` — the port against the program it was ported from.
 *
 * The reference file is written by the small C# harness in `tools/wdpref`, which loads the real Weapon Delivery
 * Planner and asks it for the answers. Nothing here reads WDP's source: this compares numbers, which is the only
 * test that can actually fail when a port drifts.
 *
 * A port checked by reading is a port checked by the person least able to see their own mistake. This one is 720
 * releases across three drag categories, eight dive angles, five speeds and six release heights, and it demands
 * the range to the foot and the time of fall to the tenth — the precision the original prints.
 */
internal fun wdpPortTest(reference: File, out: File): String = buildString {
    appendLine("Weapon Delivery Planner port — checked against the program itself")
    appendLine("reference: ${reference.path}")
    if (!reference.isFile) {
        appendLine()
        appendLine("FAIL — no reference file. Build tools/wdpref and run it against WeaponDeliveryPlanner.exe first.")
        return@buildString
    }

    var rows = 0
    var bad = 0
    val worst = StringBuilder()
    for (line in reference.readLines().drop(1)) {
        val f = line.split(',')
        if (f.size < 7 || f[0] != "bombrange") continue
        val drag = f[1]
        val dive = f[2].toIntOrNull() ?: continue
        val tas = f[3].toIntOrNull() ?: continue
        val height = f[4].toIntOrNull() ?: continue
        val wantRange = f[5].toDoubleOrNull()?.toInt() ?: continue
        val wantTof = f[6].toDoubleOrNull() ?: continue

        rows++
        val got = Ballistics.bombRange(dive, tas, height, drag)
        val rangeOk = got.rangeFt == wantRange
        val tofOk = kotlin.math.abs(got.timeOfFallSec - wantTof) < 0.0001
        if (!rangeOk || !tofOk) {
            bad++
            if (bad <= 12) {
                worst.appendLine(
                    "     $drag dive $dive° ${tas}kt ${height}ft: " +
                        "range ${got.rangeFt} vs $wantRange, fall ${got.timeOfFallSec} vs $wantTof",
                )
            }
        }
    }

    appendLine("releases compared: $rows")
    if (bad == 0) {
        appendLine("ok   every release agrees, to the foot and the tenth of a second")
    } else {
        appendLine("FAIL $bad of $rows disagree")
        append(worst)
    }

    // a couple of releases printed in full, so the report says something a pilot could sanity-check by eye
    appendLine()
    appendLine("for the record:")
    for (d in listOf(Ballistics.Drag.LOW, Ballistics.Drag.HIGH)) {
        val s = Ballistics.bombRange(30, 450, 5000, d)
        appendLine("     ${d.label}-drag, 30° dive, 450 kt, 5,000 ft: ${s.rangeFt} ft, ${s.timeOfFallSec} s")
    }

    appendLine()
    appendLine(
        if (bad == 0) "PASS — the port answers exactly as Weapon Delivery Planner does"
        else "FAIL — the port and Weapon Delivery Planner disagree",
    )
}
