package com.bmscompanion.app.ui.screens.wdp

import com.bmscompanion.app.data.Airport
import com.bmscompanion.app.data.AirportSet
import com.bmscompanion.app.data.ChartRef
import com.bmscompanion.app.data.GeoLayers
import com.bmscompanion.app.data.RadioEntry
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.Theater
import com.bmscompanion.app.data.mission.Briefing
import com.bmscompanion.app.data.mission.PcWeather
import com.bmscompanion.app.data.wdp.DtcLoad
import com.bmscompanion.app.data.wdp.DtcModel
import com.bmscompanion.app.data.wdp.WdpControl
import com.bmscompanion.app.data.wdp.WdpForm
import com.bmscompanion.app.ui.screens.FT_PER_NM
import com.bmscompanion.app.ui.screens.mission.airbases
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * What the DataCard reads besides the briefing: the app's own airport database and charts for the mission's
 * theater, the theater's radio plan and towns, the pilot's cartridge as WDP reads it, and which of the card's three
 * pages each control sits on.
 *
 * WDP gets all of this from its own files — its airport database, the `Charts` folder beside it, the cartridge it
 * loaded on the DTC page. The app already has each of them, so the card takes them from there: the airports and
 * charts the Airfields section shows, the radio plan and towns the Mission pages use for tankers and AWACS, and the
 * cartridge BMS keeps, read with the DTC page's own reader ([DtcLoad.loadCallsign]) into a model of the card's own,
 * never written.
 *
 * Loaded once per mission by [DataCardWiring.prepare] (it suspends: assets and the cartridge come from disk or the
 * PC), then handed to the wiring, which stays synchronous.
 */
class DataCardSources(
    /** the theater whose airports these are; null when none could be told */
    val theater: Theater? = null,
    val airports: AirportSet? = null,
    /** the theater's instrument charts by airport id, as the Airfields section lists them */
    val charts: Map<String, List<ChartRef>> = emptyMap(),
    /** the cartridge as WDP reads it, or null when there is none to read */
    val cartridge: DtcModel? = null,
    /** control name → the page it is on: `pnlPage_1` (DataCard), `pnlPage_2` (Coordination Card), `pnlBrief` */
    val panels: Map<String, String> = emptyMap(),
    /** the theater's radio plan: a support flight's frequencies follow its callsign (`data/radio`) */
    val radio: List<RadioEntry> = emptyList(),
    /** the theater's towns, for the place a support station is named after ("21 nm southwest of Yongin-si") */
    val geo: GeoLayers? = null,
    /** the cartridge's own text, for what the DTC page's reader does not keep (`[LINK16]`) */
    val cartridgeText: String? = null,
    /** the airports (by id) BMS's data has a ground chart for: the card's chart buttons open that diagram */
    val grounds: Set<Int> = emptySet(),
    /** the app's Arsenal, for the names BMS's SMS page gives the stores ([smsName]) */
    val weapons: List<com.bmscompanion.app.data.Weapon> = emptyList(),
) {
    private val bySms: Map<String, String> by lazy {
        weapons.mapNotNull { w -> w.simName?.trim()?.takeIf { it.isNotEmpty() }?.let { smsKey(w.name) to it } }.toMap()
    }

    /**
     * A store as WDP's card names it in the Config rows: BMS's own SMS name (`Falcon4_SWD.xml` WpnName, the Arsenal's
     * `simName`: "AIM-120C AMRAAM" is "120C5", "Tank 370gal" "TK370", "AN/ALQ-184" "AL184"), which `fclsLoadout` writes
     * there; found by the whole name, else by its designation ("GBU-12"); null when the Arsenal does not know the store.
     */
    fun smsName(store: String): String? {
        bySms[smsKey(store)]?.let { return it }
        val d = smsKey(store.trim().substringBefore(' ')).takeIf { it.length >= 3 } ?: return null
        return bySms.entries.firstOrNull { it.key.startsWith(d) }?.value
    }

    private fun smsKey(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
    /**
     * The mission's departure, arrival and alternate fields, as every Mission page finds them ([airbases]): BMS names
     * a base by its first word ("USS" for every carrier), so a name that fits several bases is settled by what else
     * the briefing says about it — or left unknown rather than guessed.
     */
    fun bases(b: Briefing?, flight: com.bmscompanion.app.data.mission.CampFlight? = null): List<Airport?> {
        val set = airports ?: return listOf(null, null, null)
        val named = missionBases(b, flight)
        return listOf(named.departureIn(set), named.arrivalIn(set), named.alternateIn(set))
    }

    /** An airport's charts, in the Airfields section's order. */
    fun chartsOf(a: Airport?): List<ChartRef> = a?.let { charts[it.id.toString()] }.orEmpty()

    /** Whether the chart window has something to show for [a]: its ground chart (the airport diagram), or instrument charts. */
    fun hasCharts(a: Airport?): Boolean = a != null && (a.id in grounds || chartsOf(a).isNotEmpty())

    /**
     * D24: a field's TACAN, or — where the field has none of its own — the nearest TACAN beacon within 6 nm, marked
     * with a star as WDP 3.7.10 marks it (40 of Korea's 94 fields have no channel of their own).
     */
    fun tacanOf(a: Airport?): String {
        a ?: return ""
        a.tacan?.let { return it.channel.toString() + " " + it.band }
        if (a.x == 0.0 && a.y == 0.0) return ""
        val near = airports?.navaids.orEmpty().filter { it.tacan != null }
            .map { it to hypot(it.x - a.x, it.y - a.y) / FT_PER_NM }
            .filter { it.second <= 6.0 }.minByOrNull { it.second }?.first?.tacan
            ?: airports?.airports.orEmpty().filter { it !== a && it.tacan != null && (it.x != 0.0 || it.y != 0.0) }
                .map { it to hypot(it.x - a.x, it.y - a.y) / FT_PER_NM }
                .filter { it.second <= 6.0 }.minByOrNull { it.second }?.first?.tacan
        return near?.let { it.channel.toString() + " " + it.band + "*" } ?: ""
    }

    /**
     * A place a briefing names ("Larissa", "Yongin-si City"), in theater feet: an airfield first, then the theater's
     * towns — the same lookup the Mission map uses to draw a support station. Unknown: null, never a guess.
     */
    fun place(name: String): Pair<Double, Double>? {
        fun one(n0: String): Pair<Double, Double>? {
            val n = n0.trim().lowercase()
            if (n.length < 3) return null
            airports?.airports?.firstOrNull { it.name.lowercase().startsWith(n) || it.icao?.lowercase() == n }?.let { return it.x to it.y }
            val towns = geo?.places.orEmpty() + airports?.places.orEmpty()
            towns.firstOrNull { it.n.lowercase() == n }?.let { return it.x to it.y }
            return towns.firstOrNull { it.n.lowercase().startsWith(n) }?.let { it.x to it.y }
        }
        // a briefing writes a name the way a pilot says it ("Yongin-si City"), a map the way it is: try the first word too
        return one(name) ?: name.trim().substringBefore(' ').takeIf { it.length >= 3 }?.let { one(it) }
    }

    companion object {
        /** The card's three pages, by the panel that holds each. */
        val PAGES = listOf("pnlPage_1", "pnlPage_2", "pnlBrief")

        /**
         * Everything above for [mission]. The theater is the one BMS reports; while that is not known (BMS not
         * running, a device that has only the briefing) it is the theater the app is set to, and failing that any
         * theater whose airports include the briefing's departure field — an airport's frequencies and runways are
         * the same whichever add-on flies from it, so unlike a coordinate this cannot mislead.
         */
        suspend fun load(mission: WdpMission, cartridgeText: String?): DataCardSources {
            val named = missionBases(mission.briefing, mission.flight)
            val theaters = runCatching { Repo.index().theaters }.getOrNull().orEmpty()
            val candidates = buildList {
                mission.theater?.let { add(it) }
                theaters.firstOrNull { it.id == Repo.selectedTheater.value }?.let { add(it) }
                addAll(theaters.filter { it.primary })
                addAll(theaters)
            }.distinctBy { it.airportSet }
            var theater: Theater? = null
            var set: AirportSet? = null
            for (t in candidates) {
                if (t.airportSet.isEmpty()) continue
                val s = runCatching { Repo.airportSet(t.airportSet) }.getOrNull() ?: continue
                if (s.airports.isEmpty()) continue
                // the theater BMS reports is taken as it is; any other only if the briefing's airfield is in it
                if (t == mission.theater || named.departure == null || named.departureIn(s) != null) { theater = t; set = s; break }
            }
            val charts = theater?.let { runCatching { Repo.charts(it.airportSet) }.getOrNull() }.orEmpty()
            val model = cartridgeText?.takeIf { it.isNotBlank() }?.let { text ->
                runCatching { DtcModel().also { DtcLoad.loadCallsign(text, it) } }.getOrNull()
            }
            val form = runCatching { Repo.wdpForm("cntDataCard") }.getOrNull()
            val radio = theater?.radioSet?.takeIf { it.isNotBlank() }?.let { runCatching { Repo.radio(it) }.getOrNull() }.orEmpty()
            val geo = theater?.mapId?.takeIf { it.isNotBlank() }?.let { runCatching { Repo.geo(it) }.getOrNull() }
            // the fields BMS's data draws a ground chart for (the Taxi page's), by campaign id
            val grounds = theater?.airfieldSet?.let { a -> runCatching { Repo.airfieldIndex(a).keys.mapNotNull { it.toIntOrNull() }.toSet() }.getOrNull() }.orEmpty()
            val weapons = runCatching { Repo.weapons() }.getOrNull().orEmpty()
            return DataCardSources(theater, set, charts, model, panelsOf(form), radio, geo, cartridgeText, grounds, weapons)
        }

        private fun panelsOf(form: WdpForm?): Map<String, String> {
            val out = HashMap<String, String>()
            fun walk(c: WdpControl, page: String?) {
                val p = if (c.name in PAGES) c.name else page
                if (p != null) out[c.name] = p
                for (k in c.children) walk(k, p)
            }
            form?.roots?.forEach { walk(it, null) }
            return out
        }
    }
}

/**
 * The flight's Link 16 plan (E3): the four seats' STNs and the A/A TACAN of the seat this cartridge is for, from
 * FILE A — the briefing's "Link 16" table (the mission BMS just planned), else the cartridge's `[LINK16]`. BMS
 * fills it per seat with its "L16 PLAN" (User Manual 4.38.1 §5.1.8). Which of the two the jet loads when they
 * differ was NOT TESTED (it needs BMS running); the briefing is the current mission's.
 */
class Link16Plan(val stn: List<String?>, val tacan: List<String?>) {
    companion object {
        /** A STN that is a real address: five octal digits, not the empty 00000 or the table's "----". */
        private fun real(s: String?): String? = s?.trim()?.takeIf { Regex("^[0-7]{5}$").matches(it) && it != "00000" }

        private fun tacan(ch: String?, band: String?, seat: Int): List<String?> {
            val c = ch?.trim()?.toIntOrNull() ?: return emptyList()
            val b = band?.trim()?.uppercase()?.takeIf { it == "X" || it == "Y" } ?: return emptyList()
            if (c !in 1..126 || seat !in 0..3) return emptyList()
            return List(4) { if (it == seat) "$c$b" else null }
        }

        /** From the briefing's table, or null when it has none (or no seat of it has an address). */
        fun fromBriefing(b: Briefing?): Link16Plan? {
            val rows = b?.sections?.firstOrNull { it.title.equals("Link 16", true) }?.rows ?: return null
            var inA = false
            var stn: List<String?> = emptyList()
            var tcn: Pair<String?, String?>? = null
            var seat = 0
            for (r in rows) {
                val first = r.firstOrNull()?.trim() ?: continue
                if (first.startsWith("FILE", true)) { inA = first.contains(" A", true); continue }
                if (!inA) continue
                r.firstOrNull { it.startsWith("Number:", true) }?.let { n ->
                    seat = (n.substringAfter(':').trim().toIntOrNull()?.rem(10) ?: 1) - 1
                }
                if (first.equals("Flight", true)) {
                    stn = r.drop(1).take(4).map(::real)
                    val i = r.indexOfFirst { it.trim().equals("TACAN:", true) }
                    val v = r.getOrNull(i + 1)?.trim().orEmpty()
                    if (i >= 0) tcn = v.dropLast(1) to v.takeLast(1)
                }
            }
            if (stn.none { it != null }) return null
            return Link16Plan(stn, tcn?.let { tacan(it.first, it.second, seat) }.orEmpty())
        }

        /** From the cartridge's `[LINK16]` (`FILE_A_FLIGHT_1_STN` …), or null. */
        fun fromCartridge(text: String?): Link16Plan? {
            val t = text ?: return null
            // the section's lines, read as lines (no inline regex flags: the browser's regular expressions have none)
            val keys = HashMap<String, String>()
            var inside = false
            for (raw in t.lineSequence()) {
                val line = raw.trim()
                if (line.startsWith("[")) { inside = line.equals("[LINK16]", ignoreCase = true); continue }
                val eq = line.indexOf('=')
                if (inside && eq > 0) keys[line.substring(0, eq).trim()] = line.substring(eq + 1).trim()
            }
            if (keys.isEmpty()) return null
            fun key(k: String) = keys[k]
            val stn = (1..4).map { real(key("FILE_A_FLIGHT_${it}_STN")) }
            if (stn.none { it != null }) return null
            val seat = (key("FILE_A_CALLSIGN_NUMBER")?.toIntOrNull()?.rem(10) ?: 1) - 1
            return Link16Plan(stn, tacan(key("FILE_A_TACAN_CHANNEL"), key("FILE_A_TACAN_BAND"), seat))
        }
    }
}

/**
 * The briefing's weather, as the card's ATIS lines and the Briefing page's weather list.
 *
 * WDP builds both from the campaign's own weather (the `.fmap`, the weather tables) at the airfield and the time the
 * flight leaves. The app has the weather the briefing prints — the take-off, target-area and landing columns — which
 * is the same weather at the same places, so the ATIS is made from the take-off column: the runway into the wind,
 * the wind, and then either the military colour state or the civil visibility and cloud, as WDP's Mil/Civ button
 * chooses. BMS 4.38.1's briefing prints no dew point and no pressure: a row that carried them would be read (D25),
 * and without one the ATIS says so rather than inventing a figure — the dew point as METAR's missing group
 * (`23///`), the pressure as the standard one, marked `STD`.
 */
object CardWeather {
    class Column(
        val situation: String?, val windDir: Int?, val windKts: Int?, val visM: Int?, val tempC: Int?, val cloudFt: Int?,
        val dewC: Int? = null,
        /** the QNH in hPa, when the briefing prints one */
        val qnhHpa: Double? = null,
        /** a weather map's own cloud cover, as BMS's code (0 none, 1 FEW, 5 SCT, 9 BKN, 13 OVC); null for a briefing's column */
        val cover: Int? = null,
        /** a weather map's towering cumulus in this cell */
        val towering: Boolean = false,
    ) {
        /** The same weather with the QNH [hPa] (a printed briefing's column with the save's pressure, which it does not print). */
        fun withQnh(hPa: Double?) = Column(situation, windDir, windKts, visM, tempC, cloudFt, dewC, hPa, cover, towering)
    }

    /**
     * Whether two take-off weathers are the same weather as far as the card goes: the type, the wind's speed and the
     * temperature. A save's `.twx` holds no wind direction when BMS picks it (the briefing prints the one it picked),
     * so the direction is not compared.
     */
    fun sameWeather(a: Column, b: Column): Boolean =
        type(a.situation) == type(b.situation) && a.windKts == b.windKts && a.tempC == b.tempC

    /** BMS's four weathers by their code (1 sunny … 4 inclement), as the briefing's Situation row words them. */
    private val TYPE_WORDS = listOf("Fair", "Sunny", "Fair", "Poor", "Inclement")

    /**
     * One place's weather out of a weather file Reload WX read ([PcWeather]): on a map, the cell the point lies in
     * ([north] and [east] in theater feet, the theater [sizeFt] square split into the map's cells from its north-west
     * corner, as BMS lays its weather over the theater); from a `.twx` flown on BMS's four types, the table of the type
     * the campaign is in, at the time of day of [timeMs] (campaign milliseconds). Null when the file gives neither.
     *
     * WDP finds the cell with `GetCell`, which rounds the grid position to the nearest cell edge rather than taking
     * the cell the point is in, so it reads up to half a cell north-east of the field (and past the map's last row
     * on the southern edge); here it is the cell the field is in.
     */
    fun column(w: PcWeather, north: Double, east: Double, sizeFt: Double, timeMs: Long): Column? {
        val n = w.cols * w.rows
        if (w.cols > 0 && w.rows > 0 && sizeFt > 0 && w.type.size == n && w.visKm.size == n && w.windKt.size == n) {
            val col = kotlin.math.floor(east / sizeFt * w.cols).toInt().coerceIn(0, w.cols - 1)
            val row = (w.rows - 1 - kotlin.math.floor(north / sizeFt * w.rows).toInt()).coerceIn(0, w.rows - 1)
            val i = row * w.cols + col
            val visM = (w.visKm[i] * 1000f).roundToInt()
            val temp = w.tempC.getOrNull(i)?.roundToInt()
            return Column(
                situation = TYPE_WORDS.getOrElse(w.type[i]) { "Fair" },
                windDir = w.windDeg.getOrNull(i)?.roundToInt()?.mod(360)?.let { if (it == 0) 360 else it },
                windKts = w.windKt[i].roundToInt(),
                visM = visM,
                tempC = temp,
                cloudFt = w.cloudBaseFt.getOrNull(i)?.roundToInt(),
                dewC = temp?.let { dewPoint(it, visM) },
                qnhHpa = w.pressureMb.getOrNull(i)?.toDouble(),
                cover = w.cover.getOrNull(i),
                towering = (w.towering.getOrNull(i) ?: 0) != 0,
            )
        }
        val t = w.twx ?: return null
        val k = (t.condition - 1).coerceIn(0, 3)
        val row = t.types.getOrNull(k) ?: return null
        // WDP's TimeOfDayGeneral: night, dawn/dusk (the two hours after sunrise and before sunset), day — with the
        // campaign's sunrise and sunset (CampLib's TOD_SUNUP 300 and TOD_SUNDOWN 1260 minutes): night before 05:00 and
        // from 21:00, dawn 05:00-07:00, dusk 19:00-21:00. Only a file before BMS 4.38 has three values; version 8 one.
        val min = (timeMs / 60_000L).mod(24 * 60L).toInt()
        val tod = when { min < 5 * 60 || min >= 21 * 60 -> 0; min < 7 * 60 || min >= 19 * 60 -> 1; else -> 2 }
        val visM = (row.fogEndFt / 3.28084f).roundToInt()
        val temp = row.tempC.getOrNull(tod) ?: row.tempC.lastOrNull()
        return Column(
            situation = TYPE_WORDS[k + 1],
            windDir = if (t.windHeld) t.windDeg.mod(360).let { if (it == 0) 360 else it } else null,
            windKts = row.windKt.getOrNull(tod) ?: row.windKt.lastOrNull(),
            visM = visM,
            tempC = temp,
            cloudFt = row.cumulusFt,
            dewC = temp?.let { dewPoint(it, visM) },
            qnhHpa = (row.qnhMb.getOrNull(tod) ?: row.qnhMb.lastOrNull())?.toDouble(),
        )
    }

    /**
     * WDP's `GetDP`: the dew point from the temperature and the visibility, one degree of spread for every two
     * kilometres. WDP's steps leave out their own edges (a visibility of exactly 1,000 or 5,000 m fell through every
     * step and gave a dew point of 0 °C); here each step includes its lower edge.
     */
    fun dewPoint(tempC: Int, visM: Int): Int = tempC - when {
        visM < 1000 -> 0
        visM < 3000 -> 1
        visM < 5000 -> 2
        visM < 7000 -> 3
        visM < 9000 -> 4
        visM < 11000 -> 5
        visM < 12000 -> 6
        visM < 13000 -> 7
        visM < 14000 -> 8
        visM < 15000 -> 9
        else -> 10
    }

    /** The briefing's column [i] (0 take-off, 1 target area, 2 landing), or null when the briefing has no weather. */
    fun column(b: Briefing?, i: Int): Column? {
        val t = b?.weather ?: return null
        if (t.rows.isEmpty()) return null
        fun cell(word: String): String? = t.rows.firstOrNull { it.label.contains(word, true) }?.values?.getOrNull(i)?.trim()
        val wind = cell("Wind")
        val dir = wind?.let { Regex("(\\d+)\\s*deg").find(it)?.groupValues?.get(1)?.toIntOrNull() }
        val kts = wind?.let { Regex("(\\d+)\\s*kts", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)?.toIntOrNull() }
        val vis = cell("Visib")?.let { v ->
            Regex("(\\d+(?:\\.\\d+)?)\\s*km", RegexOption.IGNORE_CASE).find(v)?.groupValues?.get(1)?.toDoubleOrNull()?.let { (it * 1000).roundToInt() }
                ?: Regex("(\\d+)\\s*m\\b").find(v)?.groupValues?.get(1)?.toIntOrNull()
        }
        val temp = cell("Temp")?.let { Regex("(-?\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() }
        val cloud = cell("Cloud")?.let { Regex("([\\d,]+)\\s*ft").find(it)?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull() }
        val dew = cell("Dew")?.let { Regex("(-?\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() }
        // a pressure in inches (29.92) or hPa (1013), whichever the row prints
        val qnh = (cell("QNH") ?: cell("Pressure") ?: cell("Altim"))?.let { Regex("(\\d+(?:\\.\\d+)?)").find(it)?.groupValues?.get(1)?.toDoubleOrNull() }
            ?.let { v -> when { v in 25.0..35.0 -> v / 0.0295300; v in 850.0..1090.0 -> v; else -> null } }
        return Column(cell("Situation"), dir, kts, vis, temp, cloud, dew, qnh)
    }

    /** The QNH as the Coordination Card's Force QNH box prints it: inches to two decimals, or whole hPa (D25). */
    fun forceQnh(hPa: Double?, metric: Boolean): String {
        hPa ?: return ""
        if (metric) return hPa.roundToInt().toString()
        val hundredths = (hPa * 0.0295300 * 100.0).roundToInt()
        return (hundredths / 100).toString() + "." + (hundredths % 100).toString().padStart(2, '0')
    }

    /** BMS's weather type from the briefing's word: Sunny 0, Fair 1, Poor 2, Inclement 3. */
    private fun type(s: String?): Int = when {
        s == null -> 1
        s.contains("sun", true) -> 0
        s.contains("poor", true) -> 2
        s.contains("incl", true) -> 3
        else -> 1
    }

    /**
     * The two ATIS lines for the departure airfield: `ICAO letter time RWY TRL wind state` and the temperature with
     * the trend. [civil] is WDP's Civil ATIS (visibility and cloud) rather than the military colour state; [metric]
     * its M/SM button (metres, or statute miles). [depart] is the take-off in campaign milliseconds. [trl] is the
     * transition level the first line names: WDP's `CreateAtis` takes its airport table's TL (Korea's: 140 for every
     * field, BMS's KTO AIP 2.1.1: FL140) and falls back on 180.
     */
    fun atis(
        b: Briefing?, icao: String?, runway: String?, ils: Boolean, depart: Long, civil: Boolean, metric: Boolean, trl: Int = 180,
    ): Pair<String, String>? {
        val c = column(b, 0) ?: return null
        return atis(c, column(b, 2), icao, runway, ils, depart, civil, metric, trl)
    }

    /** The same two lines from any take-off [c] and landing [landing] weather: a briefing's columns, or a weather file's. */
    fun atis(
        c: Column, landing: Column?, icao: String?, runway: String?, ils: Boolean, depart: Long, civil: Boolean, metric: Boolean,
        trl: Int = 180,
    ): Pair<String, String> {
        // WDP issues the ATIS 31 minutes before the take-off, at :25 or :55, and letters it by the half hour
        val t = ((depart - 31 * 60_000L) / 60_000L).mod(24 * 60L).toInt()
        var h = t / 60
        val m: Int
        if (t % 60 < 25) { h = (h + 23) % 24; m = 55 } else m = 25
        val letter = 'A' + ((h * 2 + if (m == 55) 1 else 0) % 26)
        val time = two(h) + two(m) + "Z"
        // a ship's "runway" is its deck ("Deck 35"): named as it is, not as "RWYDeck 35"
        val rwy = runway?.takeIf { it.isNotBlank() }?.let {
            val name = if (Regex("^\\d{1,2}[LRC]?$").matches(it.trim())) "RWY" + it.trim() else it.trim().uppercase().replace(" ", "")
            (if (ils) "ILS " else "") + name + " "
        } ?: ""
        // a wind with no direction (a .twx whose heading BMS picks itself) is variable, not from the north
        val wind = if ((c.windKts ?: 0) >= 4) (c.windDir?.let { three(it) + "/" } ?: "VRB") + two(c.windKts ?: 0) + "KT " else "VRB03KT "
        val type = type(c.situation)
        val cloud = c.cloudFt ?: 5000
        val vis = c.visM ?: 10_000
        val weather = when (type) { 2 -> "-RA "; 3 -> if ((c.tempC ?: 10) <= 0) "SN " else "RA "; else -> "" }
        val line1 = StringBuilder(icao?.takeIf { it.isNotEmpty() }?.let { "$it " } ?: "")
            .append(letter).append(' ').append(time).append(' ').append(rwy).append("TRL").append(trl).append(' ').append(wind)
        if (civil) {
            val sky = sky(c, type, cloud)
            if (sky != "CAVOK ") line1.append(visibility(vis, metric))
            line1.append(weather).append(sky)
        } else {
            line1.append(colour(vis, cloud)).append(weather)
        }
        // the trend: what the landing column says, where it is not the take-off's weather
        val trend = if (landing == null || type(landing.situation) == type) "NOSIG"
            else "BECMG " + sky(landing, type(landing.situation), landing.cloudFt ?: cloud).trim()
        // D25: temperature and dew point as METAR writes them (M for minus, // for a figure not given), then the QNH
        fun deg(v: Int) = if (v < 0) "M" + two(-v) else two(v)
        val temps = c.tempC?.let { deg(it) + "/" + (c.dewC?.let(::deg) ?: "//") + " " } ?: ""
        val qnh = c.qnhHpa?.let { if (metric) "Q" + it.roundToInt() + " " else "A" + (it * 0.0295300 * 100.0).roundToInt() + " " }
            ?: if (metric) "Q1013 STD " else "A2992 STD "
        val line2 = temps + qnh + trend
        return line1.toString().trimEnd() to line2
    }

    /**
     * The military colour state (`MilColCode`): the worse of the visibility's and the cloud base's colours — BLU,
     * WHT, GRN, YLO, AMB, RED — on WDP's own thresholds.
     */
    fun colour(visM: Int, cloudFt: Int): String {
        val v = when { visM >= 8000 -> 6; visM >= 5000 -> 5; visM >= 3700 -> 4; visM >= 1600 -> 3; visM >= 800 -> 2; else -> 1 }
        val c = when { cloudFt >= 2500 -> 6; cloudFt >= 1500 -> 5; cloudFt >= 700 -> 4; cloudFt >= 300 -> 3; cloudFt >= 200 -> 2; else -> 1 }
        return when (minOf(v, c)) { 1 -> "RED "; 2 -> "AMB "; 3 -> "YLO "; 4 -> "GRN "; 5 -> "WHT "; else -> "BLU " }
    }

    /**
     * The sky of [c]: a weather map's own cover and base (`CreateCloud`: FEW, SCT, BKN, OVC by BMS's code, CB for
     * towering cumulus), else the type's (`CreateCloudOld`). On a map it is CAVOK only with no cloud below 5,000 ft, no
     * towering cumulus and 10 km or more; WDP's own test (`Height > 5000 && ~HasTowerCumulus != 0`) holds for 0 and
     * 1 alike, so its map ATIS said CAVOK under any cloud base above 5,000 ft, towering cumulus included.
     */
    private fun sky(c: Column, type: Int, height: Int): String {
        val cover = c.cover ?: return sky(type, height)
        val base = (minOf(height, 35_000).coerceAtLeast(0) / 100).toString().padStart(3, '0')
        if (height > 5000 && !c.towering && (c.visM ?: 0) >= 10_000) return "CAVOK "
        val word = when {
            cover <= 0 -> return if (c.towering) "SCT${base}CB " else "NSC "
            cover <= 1 -> "FEW"
            cover <= 5 -> "SCT"
            cover <= 9 -> "BKN"
            else -> "OVC"
        }
        return word + base + (if (c.towering) "CB " else " ")
    }

    /** `CreateCloudOld`: CAVOK for a clear day with a high base, FEW for fair, OVC for poor, OVC010 for inclement. */
    private fun sky(type: Int, height: Int): String {
        val base = (minOf(height, 35_000) / 100).toString().padStart(3, '0')
        return when (type) {
            0 -> if (height > 5000) "CAVOK " else "SKC "
            1 -> "FEW$base "
            2 -> "OVC$base "
            else -> "OVC010 "
        }
    }

    /**
     * Visibility in metres or statute miles, as the M/SM button says, after WDP's `Visibility` has rounded it: to
     * the 100 m (at least 100), whole kilometres from 3 km, and **9999 for ten kilometres or more**. D25: the port
     * printed the briefing's 115 km as `71SM`; WDP's `MeterToSm` table gives 9999 m as `7SM`.
     */
    fun visibility(m0: Int, metric: Boolean): String {
        var m = ((m0 / 100.0).roundToInt() * 100).coerceAtLeast(100)
        if (m >= 10_000) m = 9999 else if (m >= 3000) m = m / 1000 * 1000
        return if (metric) "$m " else meterToSm(m) + "SM "
    }

    /** WDP's `MeterToSm`: metres as the statute-mile figures a METAR uses (1,200–1,400 m is 7/8, where WDP's table repeated 5/8). */
    fun meterToSm(m: Int): String {
        val table = listOf(
            100 to "0", 200 to "1/16", 300 to "1/8", 400 to "3/16", 500 to "1/4", 600 to "5/16", 800 to "1/2", 1000 to "5/8",
            1200 to "3/4", 1400 to "7/8", 1600 to "1", 1800 to "1-1/8", 2000 to "1-1/4", 2200 to "1-3/8", 2400 to "1-1/2",
            2600 to "1-5/8", 2800 to "1-3/4", 3000 to "1-7/8", 3200 to "2", 3600 to "2-1/4", 4000 to "2-1/2", 4400 to "2-3/4",
            4800 to "3", 6000 to "4", 8000 to "5", 9000 to "6", 10000 to "7",
        )
        return table.firstOrNull { m < it.first }?.second ?: (m / 1600).toString()
    }

    /**
     * A METAR for one place from a weather file, as WDP's weather list prints one (`CreateMetarFmap`): station, time,
     * wind, then the visibility and sky (civil) or the colour state (military), the weather, temperature and dew
     * point, and the pressure — in the units the card's M/SM button chooses.
     */
    fun metar(c: Column, icao: String?, timeMs: Long, civil: Boolean, metric: Boolean): String {
        val t = ((timeMs - 31 * 60_000L) / 60_000L).let { if (it < 0) 0 else it }
        val day = (t / (24 * 60)).toInt() + 1
        val time = two(day) + two((t / 60 % 24).toInt()) + two((t % 60).toInt()) + "Z "
        val wind = if ((c.windKts ?: 0) >= 4) (c.windDir?.let { three(it) } ?: "VRB") + two(c.windKts ?: 0) + "KT " else "VRB03KT "
        val type = type(c.situation)
        val cloud = c.cloudFt ?: 5000
        val vis = c.visM ?: 10_000
        val weather = when (type) { 2 -> "-RA "; 3 -> if ((c.tempC ?: 10) <= 0) "SN " else "RA "; else -> "" }
        val body = if (civil) {
            val sky = sky(c, type, cloud)
            (if (sky != "CAVOK ") visibility(vis, metric) else "") + weather + sky
        } else colour(vis, cloud) + weather
        fun deg(v: Int) = if (v < 0) "M" + two(-v) else two(v)
        val temps = c.tempC?.let { deg(it) + "/" + (c.dewC?.let(::deg) ?: "//") + " " } ?: ""
        val qnh = c.qnhHpa?.let { if (metric) "Q" + it.roundToInt() else "A" + (it * 0.0295300 * 100.0).roundToInt() } ?: ""
        return (icao?.takeIf { it.isNotBlank() }?.let { "$it " } ?: "") + time + wind + body + temps + qnh
    }

    /**
     * The Briefing page's weather list from a weather file (WDP's `CreateWeatherList`): where it came from, then a
     * METAR for the target, the departure, the arrival and the alternate, the fields around the departure, and the
     * card's ATIS. [places] are (heading, station and name, weather); a place with no weather is left out.
     */
    fun fileList(file: String, places: List<Triple<String, String, Column?>>, around: List<Pair<String, Column>>, atis: Pair<String, String>?,
                 timeMs: Long, civil: Boolean, metric: Boolean): String {
        val out = ArrayList<String>()
        out += "WEATHER BRIEFING"
        out += "from $file"
        out += ""
        for ((head, name, c) in places) {
            if (c == null) continue
            out += head.uppercase() + (if (name.isNotBlank()) "  $name" else "")
            out += "  " + metar(c, name.substringBefore(' ').takeIf { it.length == 4 && it.all { ch -> ch.isLetterOrDigit() } }, timeMs, civil, metric)
            out += ""
        }
        if (around.isNotEmpty()) {
            out += "AROUND DEPARTURE"
            for ((name, c) in around) {
                out += "  $name"
                out += "  " + metar(c, name.substringBefore(' ').takeIf { it.length == 4 && it.all { ch -> ch.isLetterOrDigit() } }, timeMs, civil, metric)
            }
            out += ""
        }
        if (atis != null) {
            out += "ATIS"
            out += atis.first
            out += atis.second
        }
        return out.joinToString("\n")
    }

    /** The Briefing page's weather list: the briefing's table, one line a row, in columns, then the ATIS. */
    fun list(b: Briefing?, atis: Pair<String, String>?): String {
        val t = b?.weather ?: return ""
        if (t.rows.isEmpty()) return ""
        val cols = t.columns.ifEmpty { listOf("Take Off", "Target Area", "Landing") }
        fun row(label: String, cells: List<String>) = label.padEnd(13) + cells.joinToString("") { it.take(20).padEnd(21) }
        val out = ArrayList<String>()
        out += "WEATHER"
        out += ""
        out += row("", cols.map { it.trim().trimEnd(':') })
        for (r in t.rows) out += row(r.label.trim().trimEnd(':') + ":", r.values.map { it.trim() })
        if (atis != null) {
            out += ""
            out += "ATIS"
            out += atis.first
            out += atis.second
        }
        return out.joinToString("\n")
    }

    /** The runway end most nearly into [windDir] (true), among [ends] (designator, true heading): WDP's `PreferedRwy`. */
    fun intoWind(ends: List<Pair<String, Double>>, windDir: Int?): Int {
        if (ends.isEmpty() || windDir == null) return 0
        var best = 0
        var bestOff = Double.MAX_VALUE
        for ((i, e) in ends.withIndex()) {
            val off = abs(((e.second - windDir) % 360 + 540) % 360 - 180)
            if (off < bestOff) { bestOff = off; best = i }
        }
        return best
    }

    private fun two(v: Int) = v.toString().padStart(2, '0')
    private fun three(v: Int) = v.toString().padStart(3, '0')
}

/**
 * Named copies of the card that earlier 1.3.8 builds kept in the app's own settings.
 *
 * WDP saves a DataCard as a `.bdc` file under `DataCards\<mission>\<package>\<callsign>`, and the codewords and the
 * package timing as `Codewords.ini` and `PackageTiming.ini`, each picked in a Windows file dialog. The card's buttons
 * do that now, on the BMS PC ([WdpFiles], [CardFile]). Copies made before, each kept here under a name with its
 * boxes and their text, stay loadable: Cards Directory offers them, and so does a Load that picks no file. Nothing
 * new is saved here.
 */
object CardCopies {
    enum class Kind(val id: String, val label: String) {
        DATACARD("datacard", "DataCard"),
        CODEWORDS("codewords", "Codewords"),
        TIMING("timing", "Package Timing"),
    }

    /** How many copies of a kind are kept: the oldest goes when a new name would make one more. */
    const val KEEP = 12

    private fun namesKey(k: Kind) = "wdp_card_${k.id}_names"
    private fun key(k: Kind, name: String) = "wdp_card_${k.id}:" + name

    /** The names kept, newest first. */
    fun names(k: Kind): List<String> =
        runCatching { Repo.getString(namesKey(k)) }.getOrNull()?.split('\n')?.filter { it.isNotBlank() }.orEmpty()

    fun save(k: Kind, name: String, fields: Map<String, String>) {
        val body = fields.entries.joinToString("\n") { (n, v) -> n + "\t" + v.replace("\\", "\\\\").replace("\n", "\\n").replace("\t", "\\t") }
        Repo.putString(key(k, name), body)
        val all = listOf(name) + names(k).filter { it != name }
        for (old in all.drop(KEEP)) Repo.putString(key(k, old), null)
        Repo.putString(namesKey(k), all.take(KEEP).joinToString("\n"))
    }

    fun load(k: Kind, name: String): Map<String, String>? {
        val body = runCatching { Repo.getString(key(k, name)) }.getOrNull() ?: return null
        val out = LinkedHashMap<String, String>()
        for (line in body.split('\n')) {
            val tab = line.indexOf('\t')
            if (tab <= 0) continue
            out[line.substring(0, tab)] = unescape(line.substring(tab + 1))
        }
        return out
    }

    private fun unescape(s: String): String {
        val b = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) { 'n' -> b.append('\n'); 't' -> b.append('\t'); else -> b.append(s[i + 1]) }
                i += 2
            } else { b.append(c); i++ }
        }
        return b.toString()
    }
}

/**
 * The mission's departure, arrival and alternate fields as every Mission page finds them in the briefing ([airbases]),
 * and — for a save's flight (Open mission…), whose briefing has no comm ladder — the flight's own home, landing and
 * alternate fields wherever the briefing names none, as the Mission views do (`airbases(live, merged)`).
 */
internal fun missionBases(b: Briefing?, flight: com.bmscompanion.app.data.mission.CampFlight?): com.bmscompanion.app.ui.screens.mission.Airbases {
    val a = airbases(null, b)
    val f = flight ?: return a
    return a.copy(
        departure = a.departure ?: f.home?.name,
        arrival = a.arrival ?: f.landing?.name,
        alternate = a.alternate ?: f.alternate?.name,
    )
}
