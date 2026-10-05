package com.bmscompanion.desktop.bridge

import java.io.File

/**
 * `--planneroutcome <part|all> <outDir> [more…]`: what the Planner's own windows and shell do, checked in-process with
 * pictures, one part per batch. This file only routes: each part is its own object in its own file, owned by the batch
 * that builds that part — `shell` (ShellOutcome, U2), `browser` (BrowserOutcome, U3), `print` (PrintOutcome, K2), `printpages` (PrintPagesOutcome, K3), `guide` (GuideOutcome, G1) and `tanker` (TankerOutcome: the DataCard's tanker rows chosen by press, mouse and finger).
 *
 * Each part's report goes to `<outDir>/planneroutcome-<part>.txt`, and all of them to `<outDir>/planneroutcome.txt`. A
 * part that throws is reported as a FAIL line, and the next part still runs.
 */
object PlannerOutcome {
    // `send` (Send to Mission, S1) was retired in 1.3.8 with the window: WDP mode's Populate from Planner replaced it
    val PARTS = listOf("shell", "browser", "print", "printpages", "guide")
    /** parts run only by name (their arguments differ from the others', so `all` leaves them out): `tanker` (TankerOutcome) */
    val OWN = listOf("tanker")

    fun run(part: String, outDir: File, more: List<String>): String {
        val parts = if (part == "all") PARTS else listOf(part)
        if (parts.any { it !in PARTS + OWN }) return "FAIL: unknown part '$part' — one of ${(PARTS + OWN).joinToString()} or all\n"
        outDir.mkdirs()
        return buildString {
            for (p in parts) {
                val report = try {
                    when (p) {
                        "shell" -> ShellOutcome.run(outDir, more)
                        "browser" -> BrowserOutcome.run(outDir, more)
                        "print" -> PrintOutcome.run(outDir, more)
                        "printpages" -> PrintPagesOutcome.run(outDir, more)
                        "tanker" -> TankerOutcome.run(outDir, more)
                        else -> GuideOutcome.run(outDir, more)
                    }
                } catch (e: Throwable) {
                    "FAIL: the $p part threw ${e::class.java.simpleName}: ${e.message}\n" + e.stackTrace.take(12).joinToString("\n") { "    at $it" } + "\n"
                }
                runCatching { File(outDir, "planneroutcome-$p.txt").writeText(report) }
                appendLine("=== $p")
                append(report)
                if (!report.endsWith("\n")) appendLine()
            }
        }
    }
}
