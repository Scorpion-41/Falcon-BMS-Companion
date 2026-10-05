package com.bmscompanion.desktop.bridge

import java.io.File

/**
 * `--wdppagetest <page> <reference> <out.txt>` — one door for every ported Weapon Delivery Planner page.
 *
 * Each page's comparison lives in its own file (`WdpTossTest.kt`, `WdpPopupTest.kt`, …) and is reached by name
 * here, so porting a page means adding a file and not editing the dispatcher. A page that is not ported yet says
 * so rather than failing to build: the stubs below are what the port replaces, one at a time.
 *
 * The shape every page test keeps: the reference is written by `tools/wdpref page <Page>`, which drives the real
 * control by reflection over a grid of inputs and copies its labels out; the test drives the port through the
 * identical sequence and demands the same text. See `docs/WDP-PORT.md`.
 */
internal fun wdpPageTest(page: String, reference: File, out: File): String = when (page.lowercase()) {
    "toss" -> wdpTossTest(reference, out)
    "popup" -> wdpPopupTest(reference, out)
    "hadb" -> wdpHadbTest(reference, out)
    "performance" -> wdpPerformanceTest(reference, out)
    "dtc" -> wdpDtcTest(reference, out)
    "datacard" -> wdpDataCardTest(reference, out)
    "engines" -> wdpEnginesTest(reference, out)
    "coords" -> wdpCoordsTest(reference, out)
    else -> "FAIL — no such page test: $page (toss, popup, hadb, performance, dtc, datacard, engines)"
}
