package com.bmscompanion.desktop.bridge

import com.bmscompanion.app.data.Airport
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.mission.Contact
import com.bmscompanion.app.data.mission.Live
import com.bmscompanion.app.data.mission.Voice
import com.bmscompanion.app.ui.screens.mission.afloat
import com.bmscompanion.app.ui.screens.mission.airbases
import com.bmscompanion.app.ui.screens.mission.matchAirport
import kotlinx.coroutines.runBlocking

/**
 * `--carriertest out.txt`: does every base of every theater come back as itself from what Falcon BMS says about it?
 *
 * BMS never names a base in full where the app reads it. The VoiceHelpers string in shared memory and the comm
 * ladder's DEP_AB / ARR_AB / ALT_AB tokens carry only the **first word** of the objective's name ("Osan AB (RKSO)"
 * → "Osan", "Nea Anchialos Airbase" → "Nea" in a real printed briefing, "Pyeongtaek AAF (RKSG)" → "Pyeongtaek" in
 * FlightData.h's own example), so all three US carriers of Korea arrive as "USS". And for a ship the ladder
 * (`Data/Campaign/Comms.b`, `#IF_DEP_CARRIER`) calls its rows plain "ATIS", "Tower", "Ground" and puts the name
 * in the notes of the Ground row (Approach for arrival and alternate).
 *
 * For each base this builds what BMS would give: the first-word token in VoiceHelpers, a comm ladder in the carrier
 * or the airfield form with the base's own frequencies (run through the real briefing parser), its TACAN in the DED,
 * and a Tacview ship under the jet named by its hull number. Each is asked on its own and together. The full name,
 * and every combination that includes the ladder, must come back as the base itself; nothing may come back as
 * another base, except an airfield's bare first word, which keeps the first match as it always did (counted). A
 * ship's bare first word must come back as nothing when it fits several ships.
 */
object CarrierTest {
    private val FAR_FLIGHT = "Other9"

    fun run(): String = buildString {
        val index = runBlocking { Repo.index() }
        var wrong = 0
        var none = 0
        var guessed = 0
        var noEvidence = 0
        var total = 0
        for ((setId, theaters) in index.theaters.groupBy { it.airportSet }) {
            val set = runBlocking { Repo.airportSet(setId) }
            appendLine("=== $setId (${theaters.joinToString { it.name }}), ${set.airports.size} bases")
            for (a in set.airports) {
                total++
                val token = a.name.trim().split(' ').first()
                val ship = afloat(a)
                val brief = BriefingParser.parse(ladder(a, token, "Test1"))
                val voice = Voice(flight = "Test1", seats = "PXXX", departure = token, arrival = token, alternate = token)
                val tacan = a.tacan?.label
                // the ship the jet stands on, as Tacview names it: its hull number, nothing else
                val hull = Regex("[0-9]+").find("${a.name} ${a.fullName}")?.value
                val deck = hull?.let { listOf(Contact(id = "s", kind = "ship", x = 5000.0, y = 5000.0, name = "Hull $it")) }
                val onDeck = Live(x = 5100.0, y = 5000.0, voice = voice)
                // another base the same word fits, to print a stale briefing for
                val other = set.airports.firstOrNull { it.id != a.id && it.name.trim().split(' ').first() == token && it.freqs != null }
                val cases = listOf(
                    "full name" to matchAirport(set, a.name),
                    "token" to matchAirport(set, token),
                    "dep" to airbases(Live(voice = voice, tacanUfc = tacan), brief).departureIn(set),
                    "arr" to airbases(Live(voice = voice, tacanUfc = tacan), brief).arrivalIn(set),
                    "alt" to airbases(Live(voice = voice, tacanUfc = tacan), brief).alternateIn(set),
                    "briefing only" to airbases(null, brief).departureIn(set),
                    "voice+TACAN" to airbases(Live(voice = voice, tacanUfc = tacan), null).departureIn(set),
                    "voice+deck" to if (deck != null) airbases(onDeck, null, deck).departureIn(set) else null,
                    "voice+stale briefing" to if (other != null) airbases(Live(voice = voice), BriefingParser.parse(ladder(other, token, FAR_FLIGHT))).departureIn(set) else null,
                )
                // a base with no station entry has no frequencies and no TACAN: BMS prints nothing that tells it apart
                val evidence = a.freqs != null || a.tacan != null
                val mustBeItself = if (evidence) listOf("full name", "dep", "arr", "alt", "briefing only") else listOf("full name")
                if (!evidence) noEvidence++
                val bare = cases.first { it.first == "token" }.second
                // an airfield's first word alone keeps the first match, as it always did: counted, not failed
                val bad = cases.filter { (k, r) -> r != null && r.id != a.id && !(!ship && k !in mustBeItself && r.id == bare?.id) }
                val missing = cases.filter { (k, r) -> r == null && k in mustBeItself }
                val guess = !ship && bare != null && bare.id != a.id
                if (bad.isNotEmpty()) wrong++
                if (missing.isNotEmpty()) none++
                if (guess) guessed++
                if (bad.isNotEmpty() || missing.isNotEmpty() || ship || guess) {
                    appendLine("%s %-26s token %-11s %s%s%s".format(
                        if (ship) "SHIP " else "field", a.name.take(26), "'$token'",
                        cases.joinToString(" | ") { (k, r) -> "$k → ${r?.let { "${it.name.take(22)} #${it.id}" } ?: "—"}" },
                        if (bad.isNotEmpty()) "   WRONG: " + bad.joinToString { it.first } else "",
                        if (missing.isNotEmpty()) "   NONE: " + missing.joinToString { it.first } else "",
                    ))
                }
            }
        }
        appendLine()
        appendLine("$total bases: $wrong answered with another base, $none left unanswered where they must not be;")
        appendLine("$guessed airfields whose first word alone fits another field too (they keep the first match, as before)")
        appendLine("$noEvidence bases with no frequencies and no TACAN, which only their full name or a hull number can name")
    }

    /** The comm ladder BMS prints (Comms.b), in the carrier form for a ship and the airfield form otherwise. */
    private fun ladder(a: Airport, token: String, flight: String): String = buildString {
        val f = a.freqs
        fun mhz(v: String?) = v?.let { "$it MHz [2]" } ?: "--"
        fun row(agency: String, callsign: String, uhf: String?, vhf: String?, notes: String) =
            appendLine("\t$agency:\t$callsign\t${mhz(uhf)}\t${mhz(vhf)}\t$notes")
        appendLine("Mission Overview:")
        appendLine("\t$flight (CAP) ")
        appendLine("Comm Ladder:")
        appendLine(" ")
        appendLine("\tAgency:\tCallsign:\tUhf [Chnl]:\tVhf [Chnl]:\tNotes:")
        appendLine(" ")
        row("Intra-Flight", flight, "345.950", "52.450", "Flight Management Comms")
        appendLine(" ")
        for ((p, role) in listOf("Dep" to "Departure", "Arr" to "Recovery", "Alt" to "Alternate")) {
            if (afloat(a)) {
                row("$p Atis", "ATIS", null, f?.atisVhf, "$role Carrier:")
                if (p == "Dep") {
                    row("$p Ground", "Ground", f?.groundUhf, null, token)
                    row("$p Tower", "Tower", f?.towerUhf, f?.towerVhf, "")
                    row("$p Departure", "Tower", f?.approachUhf, null, "")
                } else {
                    row("$p Approach", "Approach", f?.approachUhf, null, token)
                    row("$p Tower", "Tower", f?.towerUhf, f?.towerVhf, "")
                    row("$p Lso", "Paddles", f?.lsoUhf, null, "Landing Signal Officer")
                    row("$p Ground", "Ground", f?.groundUhf, null, " ")
                }
            } else {
                row("$p Atis", "$token ATIS", null, f?.atisVhf, "$role Airbase")
                if (p == "Dep") {
                    row("$p Ground", "$token Ground", f?.groundUhf, null, "$role Airbase")
                    row("$p Tower", "$token Tower", f?.towerUhf, f?.towerVhf, "$role Airbase")
                    row("$p Departure", "$token Departure", f?.approachUhf, null, "$role Airbase")
                } else {
                    row("$p Approach", "$token Approach", f?.approachUhf, null, "$role Airbase")
                    row("$p Tower", "$token Tower", f?.towerUhf, f?.towerVhf, "$role Airbase")
                    row("$p Ground", "$token Ground", f?.groundUhf, null, "$role Airbase")
                }
            }
            appendLine(" ")
        }
        appendLine("Iff")
    }
}
