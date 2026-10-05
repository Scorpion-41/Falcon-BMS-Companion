package com.bmscompanion.app.data.mission

/**
 * Reading Falcon BMS's radio subtitles: one line of its debug log into a call — who speaks, to whom, which group it
 * belongs to ([RadioCategory]) and, for a controller's call, the runway, the taxiway letters and the parking spot.
 *
 * BMS assembles every call out of fragments (`Data\Sounds\CommFile.xml` → `EvalFile.xml` → `F4Talk.csv`, one text per
 * voice) and prints the result in its log as `[hh:mm:ss.mmm] <thread> Subtitle: <text>`. The shapes below are read
 * from those files, not guessed (call numbers are `CommFile.xml`'s; docs/PARKING.md has the taxi-back ones):
 *
 * - almost every call starts with whom it is for and then who speaks: "Jaguar 2-1 magic 5 picture is …" (calls 138,
 *   375, 524), "magic 5 Jaguar 2-1 request picture" (140); a callsign is a word and a number, "2" for a flight and
 *   "2-1" for one aircraft of it;
 * - a controller names itself by its field: "Jaguar 2-1 Gunsan Tower wind 3-6-0 at 5 knots runway 3-6 cleared for
 *   takeoff" (39), "… Gunsan Approach continue inbound for runway 3-6" (287); the controllers' voices put "Good
 *   morning" in front of some (38, 286, 387);
 * - Ground's own calls name only the aircraft: "Jaguar 2-1 taxi Alpha Charlie and hold short runway 3-6" (284), "Good
 *   morning Jaguar 2-1 you are number 1 for departure taxi Alpha Charlie and hold short runway 3-6" (387), "Jaguar 2-1
 *   Taxi back Alpha and hold short runway 3-6" (504), "Jaguar 2-1 Taxi back to the ramp Alpha Charlie park 0 0 4"
 *   (517 — the controllers' voices have no text for "park", so the subtitle may go straight to the digits),
 *   "Jaguar 2-1 Taxi to the ramp welcome back" (391, no spot given);
 * - digits are said one by one ("runway 3-6", "park 0 0 4", "bullseye 1 3 8"), and an AWACS picture's groups are
 *   printed as lines of their own at the same moment with no callsign ("Southeast group bullseye …"), which
 *   [RadioThread] joins to their call.
 *
 * Pure Kotlin: the PC parses (it knows the mission), and the devices use [designatorIn] and the wording.
 */
object RadioCalls {
    /** `[04:12:30.535] 27312 Subtitle: …` */
    private val LOG_LINE = Regex("""^\[(\d{1,2}):(\d{2}):(\d{2})\.(\d{1,3})\]\s+\S+\s+Subtitle:\s?(.*)$""")
    private val NUMBER = Regex("""^\d(?:-\d)?$""")
    private val FLIGHT = Regex("""^\s*([A-Za-z][A-Za-z' ]*?)\s*(\d)(?:\s*-\s*(\d))?\b""")
    private val SPACES = Regex("""\s+""")
    private val SCRUBBED = Regex("""scrubbed|cancelled|aborted|mission""")
    private val WORD = Regex("""^[A-Za-z][A-Za-z']*$""")
    private val RUNWAY = Regex("""\brunway\s+(\d)\s*-?\s*(\d)\b(?:\s+(left|right|cent(?:er|re)))?""", RegexOption.IGNORE_CASE)
    private val ATC_WORDS = Regex(
        """\b(hold short|cleared for|for departure|to land\b|contact (approach|tower|ground|departure)|request taxi|""" +
            """wind check|go around|missed approach|expedite departure|orbit for spacing|qnh|taxi|runway \d|""" +
            """position and hold|inbound for|vectors (to|for)|landing check|traffic now|give way)""",
        RegexOption.IGNORE_CASE,
    )
    private val STATION_SUFFIX = setOf("approach", "tower", "ground", "departure", "arrival", "clearance", "delivery")
    private val GREETINGS = listOf("good morning", "good afternoon", "good evening")
    /** words that come before a number in a call without being a callsign */
    private val NOT_CALLSIGNS = setOf(
        "bullseye", "group", "groups", "angels", "angel", "heading", "runway", "park", "braa", "number", "wind", "at",
        "is", "to", "for", "channel", "tacan", "and", "track", "contact", "switch", "picture", "bearing", "minutes",
        "minute", "miles", "mile", "knots", "on", "of", "in", "the", "you", "are", "maintain", "climb", "descend",
        "fly", "turn", "depart", "qnh", "alpha", "bravo", "charlie", "delta", "echo", "foxtrot", "golf", "hotel", "india",
        "juliet", "juliett", "kilo", "lima", "mike", "november", "oscar", "papa", "quebec", "romeo", "sierra", "tango",
        "uniform", "victor", "whiskey", "xray", "x-ray", "yankee", "zulu", "single", "north", "south", "east", "west",
        "northeast", "northwest", "southeast", "southwest", "hot", "cold", "flight", "flights", "way", "give",
    )
    private val PHONETIC = mapOf(
        "alpha" to "A", "alfa" to "A", "bravo" to "B", "charlie" to "C", "delta" to "D", "echo" to "E", "foxtrot" to "F",
        "golf" to "G", "hotel" to "H", "india" to "I", "juliet" to "J", "juliett" to "J", "kilo" to "K", "lima" to "L",
        "mike" to "M", "november" to "N", "oscar" to "O", "papa" to "P", "quebec" to "Q", "romeo" to "R", "sierra" to "S",
        "tango" to "T", "uniform" to "U", "victor" to "V", "whiskey" to "W", "x-ray" to "X", "xray" to "X", "yankee" to "Y",
        "zulu" to "Z",
    )

    /** The AWACS and tanker callsigns BMS voices (`F4Talk.csv`: Chalice, Sentry, Dragnet, magic; Texaco, Copper), and the usual others. */
    val DEFAULT_AWACS = setOf("magic", "chalice", "sentry", "dragnet", "darkstar", "wizard", "overlord", "focus", "disco")
    val DEFAULT_TANKERS = setOf("texaco", "copper", "arco", "shell", "exxon", "mobil", "bloodhound")

    /** One subtitle line of the log: its stamp to the millisecond (to join lines printed together), the sim time, the text. */
    data class LogLine(val stamp: String, val time: String, val text: String)

    fun logLine(raw: String): LogLine? {
        val m = LOG_LINE.find(raw.trimEnd('\r', '\n')) ?: return null
        val g = m.groupValues
        val time = g[1].padStart(2, '0') + ":" + g[2] + ":" + g[3]
        return LogLine("$time.${g[4]}", time, g[5].trim())
    }

    /** A party a call names: a callsign ("Jaguar 2-1"), a controller ("Gunsan Tower") or "All flights". */
    data class Party(val text: String, val name: String, val flight: Int? = null, val element: Int? = null, val station: Boolean = false) {
        /** the field a controller belongs to ("Gunsan" of "Gunsan Tower") */
        val field: String? get() = if (!station) null else text.split(' ').dropLastWhile { it.lowercase() in STATION_SUFFIX }.joinToString(" ").ifEmpty { text }
    }

    /** A call taken apart. [body] is what follows the parties. */
    data class Parsed(val to: Party?, val from: Party?, val body: String, val atc: RadioAtc?, val startsCall: Boolean)

    private fun clean(t: String) = t.trim(',', '.', ';', ':', '!', '?')

    /** "Jaguar2", "Jaguar 2", "Jaguar2-1" → (jaguar, 2, 1); null for anything else. */
    fun flightOf(s: String?): Triple<String, Int, Int?>? {
        val m = FLIGHT.find(s ?: return null) ?: return null
        return Triple(m.groupValues[1].trim().lowercase(), m.groupValues[2].toInt(), m.groupValues[3].toIntOrNull())
    }

    private fun partyAt(tok: List<String>, i: Int, ctx: RadioContext, first: Boolean): Pair<Party, Int>? {
        if (i >= tok.size) return null
        val w0 = clean(tok[i])
        // "All flights Gunsan Tower be advised …" (506-508)
        if (w0.equals("all", true) && tok.getOrNull(i + 1)?.let(::clean)?.equals("flights", true) == true) return Party("All flights", "all flights") to 2
        // a callsign: a word and a number
        val n = tok.getOrNull(i + 1)?.let(::clean)
        if (WORD.matches(w0) && n != null && NUMBER.matches(n)) {
            val lw = w0.lowercase()
            val known = lw in ctx.callsigns || lw in ctx.awacs || lw in ctx.tankers || lw == ctx.ownName || lw in ctx.flights
            if (known || (lw !in NOT_CALLSIGNS && w0[0].isUpperCase())) {
                val parts = n.split('-')
                return Party("$w0 $n", lw, parts[0].toIntOrNull(), parts.getOrNull(1)?.toIntOrNull()) to 2
            }
        }
        // a controller: up to three words of a field's name and Approach, Tower, Ground …
        for (len in 1..3) {
            val suffix = tok.getOrNull(i + len)?.let(::clean) ?: break
            val words = (i until i + len).map { clean(tok[it]) }
            if (words.any { w -> w.isEmpty() || w.any { it.isDigit() } }) break
            if (suffix.lowercase() in STATION_SUFFIX) {
                if (words.first().lowercase() in NOT_CALLSIGNS) break
                return Party((words + suffix).joinToString(" "), (words + suffix).joinToString(" ").lowercase(), station = true) to len + 1
            }
        }
        // a controller named by its field alone (CommFile's E87 entries 94 on: "Jaguar 2-1 Gunsan taxi clear of the runway")
        if (!first) for (f in ctx.fields) {
            val fw = f.split(' ')
            if (i + fw.size <= tok.size && fw.indices.all { clean(tok[i + it]).equals(fw[it], true) }) {
                val said = (i until i + fw.size).joinToString(" ") { clean(tok[it]) }
                return Party(said, said.lowercase(), station = true) to fw.size
            }
        }
        return null
    }

    /** Takes a subtitle apart. Never throws. */
    fun parse(text: String, ctx: RadioContext): Parsed {
        val tok = text.trim().split(SPACES).filter { it.isNotEmpty() }
        var i = 0
        var greeted = false
        val lower = text.trim().lowercase()
        GREETINGS.firstOrNull { lower.startsWith(it) }?.let { i = 2; greeted = true }
        val a = partyAt(tok, i, ctx, first = true)
        if (a != null) i += a.second
        val b = if (a != null) partyAt(tok, i, ctx, first = false) else null
        if (b != null) i += b.second
        val body = tok.drop(i).joinToString(" ")
        val station = listOfNotNull(a?.first, b?.first).firstOrNull { it.station }
        val atc = atcOf(body, station)
        val (to, from) = when {
            a != null && b != null -> a.first to b.first
            // one party: told something by a controller, or speaking
            a != null && (atc != null || greeted) -> a.first to null
            a != null -> null to a.first
            else -> null to null
        }
        return Parsed(to, from, body, atc, startsCall = greeted || a != null)
    }

    /** The runway a call names, as the charts name it: "runway 0-9 left" → "09L"; null when it names none. */
    fun runwayIn(text: String): String? {
        val m = RUNWAY.find(text) ?: return null
        val side = when (m.groupValues[3].lowercase()) { "left" -> "L"; "right" -> "R"; "" -> ""; else -> "C" }
        return m.groupValues[1] + m.groupValues[2] + side
    }

    /** The field's own designator for a runway a call names ("36" → "36", "09L" → "09L"); null when it has none, or two. */
    fun designatorIn(spoken: String?, designators: List<String>): String? {
        if (spoken.isNullOrBlank()) return null
        designators.firstOrNull { it.equals(spoken, true) }?.let { return it }
        val num = spoken.takeWhile { it.isDigit() }.toIntOrNull() ?: return null
        val side = spoken.dropWhile { it.isDigit() }.uppercase()
        val same = designators.filter { d -> d.takeWhile { it.isDigit() }.toIntOrNull() == num && (side.isEmpty() || d.dropWhile { it.isDigit() }.uppercase() == side) }
        return same.singleOrNull()
    }

    private fun lettersAfter(words: List<String>, from: Int): Pair<List<String>, Int> {
        val out = ArrayList<String>()
        var k = from
        while (k < words.size) {
            val l = PHONETIC[words[k].lowercase()] ?: break
            out += l; k++
        }
        return out to k
    }

    /** What a controller's call says, or null when the body is not a controller's. */
    fun atcOf(body: String, station: Party?): RadioAtc? {
        val b = body.lowercase().replace(",", " ").replace(SPACES, " ").trim()
        val words = b.split(' ').filter { it.isNotEmpty() }
        val rwy = runwayIn(b)
        fun idx(phrase: String): Int {
            val p = phrase.split(' ')
            for (k in 0..words.size - p.size) if (p.indices.all { words[k + it] == p[it] }) return k + p.size
            return -1
        }
        val field = station?.field
        val st = station?.text
        // Ground's way back to the ramp: a spot (517) or none (391); "your mission has been scrubbed, taxi back to the ramp" is Tower's (308)
        val ramp = idx("to the ramp")
        if (ramp >= 0 && !SCRUBBED.containsMatchIn(b)) {
            val (letters, after) = lettersAfter(words, ramp)
            val rest = words.drop(after).let { if (it.firstOrNull() == "park") it.drop(1) else it }
            val digits = rest.takeWhile { it.all(Char::isDigit) && it.isNotEmpty() }
            val spot = if (digits.isNotEmpty() && digits.size <= 3 && digits.joinToString("").length <= 3) digits.joinToString("").toIntOrNull() else null
            return if (spot != null) RadioAtc(RadioTaxi.PARK, st, field, null, spot, letters)
            else RadioAtc(RadioTaxi.NOSPOT, st, field, null, null, letters)
        }
        val back = idx("taxi back")
        if (back >= 0 && b.contains("hold short")) return RadioAtc(RadioTaxi.BACK, st, field, rwy, null, lettersAfter(words, back).first)
        if (b.contains("taxi clear of the runway")) return RadioAtc(RadioTaxi.VACATE, st, field, null)
        if (b.contains("position and hold")) return RadioAtc(RadioTaxi.LINEUP, st, field, rwy)
        if (b.contains("cleared for takeoff") || (rwy != null && b.contains("for takeoff"))) return RadioAtc(RadioTaxi.TAKEOFF, st, field, rwy)
        val taxi = words.indexOf("taxi")
        if (taxi >= 0 && (b.contains("hold short") || b.contains("for departure")) && !b.contains("request taxi")) {
            return RadioAtc(RadioTaxi.OUT, st, field, rwy, null, lettersAfter(words, taxi + 1).first)
        }
        if (b.contains("cleared for landing") || (rwy != null && (b.contains("for landing") || b.contains("inbound for") || b.contains("vectors")))) {
            return RadioAtc(RadioTaxi.LANDING, st, field, rwy)
        }
        if (station != null || ATC_WORDS.containsMatchIn(b)) return RadioAtc("atc", st, field, rwy)
        return null
    }

    /** Whether [p] is the pilot's own flight (any of its aircraft). */
    fun isOwn(p: Party?, ctx: RadioContext): Boolean =
        p != null && !p.station && ctx.ownName != null && p.name == ctx.ownName && (ctx.ownFlight == null || p.flight == ctx.ownFlight)

    /** Which group a call goes under: a controller's, then AWACS, a tanker, the pilot's own flight, another flight, the rest. */
    fun categoryOf(p: Parsed, ctx: RadioContext): String {
        val parties = listOfNotNull(p.to, p.from)
        return when {
            p.atc != null || parties.any { it.station } -> RadioCategory.ATC
            parties.any { it.name in ctx.awacs } -> RadioCategory.AWACS
            parties.any { it.name in ctx.tankers } -> RadioCategory.TANKER
            parties.any { isOwn(it, ctx) } -> RadioCategory.MINE
            parties.any { !it.station && it.flight != null } -> RadioCategory.FLIGHTS
            else -> RadioCategory.OTHER
        }
    }

    /**
     * The Taxi page's cue in a controller's call, when it is to the pilot's own flight: the runway to taxi to, or the
     * way back in. A taxi-out clearance goes to the flight (any of its aircraft); the way back is per aircraft, so it
     * must be the pilot's own seat (or the lead, when the seat is not known). [landed] is the runway the last landing
     * clearance to the flight named, which the way back is counted from (the taxi-in network).
     */
    fun taxiCue(seq: Long, at: Long, time: String, text: String, p: Parsed, ctx: RadioContext, landed: String?): RadioTaxi? {
        val atc = p.atc ?: return null
        val kind = atc.kind
        if (kind == "atc" || kind == RadioTaxi.LANDING) return null
        val to = p.to ?: return null
        if (!isOwn(to, ctx)) return null
        val perAircraft = kind == RadioTaxi.PARK || kind == RadioTaxi.NOSPOT || kind == RadioTaxi.BACK || kind == RadioTaxi.VACATE
        if (perAircraft && to.element != null && to.element != (ctx.ownSeat ?: 1)) return null
        val runway = if (perAircraft) landed else atc.runway
        return RadioTaxi(seq, at, time, kind, runway, atc.spot, atc.letters, atc.field, text)
    }

    /** What a cue does to a taxi chart: the way out or in, the runway (null: the network the jet stands in decides), the spot. */
    data class TaxiApply(val outbound: Boolean, val runway: String?, val spot: Int?)

    /**
     * The Taxi page's and the live taxi board's decision for a cue on a field whose runways are [designators]: taxi out
     * to the runway named (null when the field has no such runway — the page may be on another field); the way in on
     * the runway landed on when a landing clearance named it, with the spot Ground gave ("park 0 0 4" → 4).
     */
    fun applyTo(c: RadioTaxi, designators: List<String>): TaxiApply? {
        val rw = designatorIn(c.runway, designators)
        if (c.outbound) return rw?.let { TaxiApply(true, it, null) }
        return TaxiApply(false, rw, c.spot.takeIf { c.kind == RadioTaxi.PARK })
    }

    /** "Ground: park 04 via A C (landed 36)" — one line for the Taxi page. */
    fun cueLine(c: RadioTaxi): String {
        val via = c.letters.takeIf { it.isNotEmpty() }?.joinToString(" ", prefix = " via ")
        return when (c.kind) {
            RadioTaxi.OUT -> "Ground: taxi to runway ${c.runway ?: "?"}" + (via ?: "")
            RadioTaxi.LINEUP -> "Tower: line up on runway ${c.runway ?: "?"}"
            RadioTaxi.TAKEOFF -> "Tower: cleared for takeoff, runway ${c.runway ?: "?"}"
            RadioTaxi.VACATE -> "Tower: taxi clear of the runway" + (c.runway?.let { " (landed $it)" } ?: "")
            RadioTaxi.BACK -> "Ground: taxi back" + (via ?: "") + ", hold short"
            RadioTaxi.PARK -> "Ground: park ${c.spot?.toString()?.padStart(2, '0') ?: "?"}" + (via ?: "") + (c.runway?.let { " (landed $it)" } ?: "")
            RadioTaxi.NOSPOT -> "Ground gave no spot (\"welcome back\"): call taxi back from the EOR / de-arm pad to be given one"
            else -> c.text
        }
    }
}

/** What the PC knows of the mission when it files a call ([RadioCalls]). Names in lower case. */
data class RadioContext(
    /** the pilot's own flight, "jaguar" and 2 */
    val ownName: String? = null,
    val ownFlight: Int? = null,
    /** the pilot's seat in it (1-4), when known */
    val ownSeat: Int? = null,
    val awacs: Set<String> = RadioCalls.DEFAULT_AWACS,
    val tankers: Set<String> = RadioCalls.DEFAULT_TANKERS,
    /** the package's other flights */
    val flights: Set<String> = emptySet(),
    /** every word BMS voices as a callsign (`F4Talk.csv`) */
    val callsigns: Set<String> = emptySet(),
    /** the fields BMS names its controllers by, as said ("Gunsan") */
    val fields: List<String> = emptyList(),
) {
    val own: String? get() = ownName?.let { n -> n.replaceFirstChar { it.uppercaseChar() } + (ownFlight?.let { " $it" } ?: "") }
}

/**
 * Joins subtitle lines into calls: a line printed at the same millisecond as the call before it, naming no one, is that
 * call's next line (an AWACS picture's groups). Kept by the PC per session; [add] answers the call it made or extended.
 */
class RadioThread(private val cap: Int = 2000) {
    val messages = ArrayDeque<RadioMessage>()
    var seq = 0L; private set
    private var lastStamp: String? = null

    /** Adds one subtitle line; answers the message it became, and whether it is new (false: joined to the last). */
    fun add(line: RadioCalls.LogLine, ctx: RadioContext, file: (Long, RadioCalls.Parsed) -> RadioMessage): Pair<RadioMessage, Boolean> {
        val p = RadioCalls.parse(line.text, ctx)
        val last = messages.lastOrNull()
        if (last != null && line.stamp == lastStamp && !p.startsCall) {
            val joined = last.copy(text = last.text + "\n" + line.text)
            messages[messages.size - 1] = joined
            return joined to false
        }
        lastStamp = line.stamp
        val m = file(++seq, p)
        messages.addLast(m)
        while (messages.size > cap) messages.removeFirst()
        return m to true
    }

    fun clear() { messages.clear(); seq = 0; lastStamp = null }
}
