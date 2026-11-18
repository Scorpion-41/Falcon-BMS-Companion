package com.bmscompanion.app.data

/**
 * A theater's name **as the app shows it**, where BMS's own is not one a pilot would write: Add-On Mideast128's theater
 * definition calls it "Mideast4_38", and the app says "MidEast" (1.4.4, the owner's choice). Display only: everything
 * that matches a theater — the PC's theater definitions, the Planner's `plannerTheater`, the campaign files, a
 * mission's theater, prefs keyed by theater — keeps BMS's own name or the app's id. Matched without case on BMS's
 * name ("Mideast4_38") and on the app's id ("mideast4-38"); any other name is shown as it is.
 */
object TheaterLabels {
    private val LABELS: Map<String, String> = mapOf(
        "mideast4_38" to "MidEast",
        "mideast4-38" to "MidEast",
    )

    /** [name] as the app shows it; null stays null. */
    fun ui(name: String?): String? = name?.let { n -> LABELS[n.trim().lowercase()] ?: n }

    /** The same, for a name that is never null. */
    fun of(name: String): String = LABELS[name.trim().lowercase()] ?: name
}
