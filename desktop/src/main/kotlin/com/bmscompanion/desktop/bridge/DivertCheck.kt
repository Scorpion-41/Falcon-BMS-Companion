package com.bmscompanion.desktop.bridge

import com.bmscompanion.app.data.Airport
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.Theater
import com.bmscompanion.app.data.wdp.WdpCoords
import com.bmscompanion.app.ui.screens.DedCoords
import com.bmscompanion.app.ui.screens.airportKind
import com.bmscompanion.app.ui.screens.divertCoords
import com.bmscompanion.app.ui.screens.divertIsShip
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import java.io.File
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * `--divertcheck out.txt <a BMS folder> [airports.csv]` — the airfield page's Info box (DivertBlock.kt) for every
 * theater of the app's data and every field in it. Reads the BMS folder only (its documents), never writes it.
 *
 * 1. **Every field**: coordinates given or not, and why not (a ship, no projection, a conversion that failed); the
 *    figures a divert reads (Approach, TACAN, Tower, Ground, ILS, ATIS, elevation) counted where missing; any
 *    exception. A FAIL where a theater with BMS's grid (`WdpCoords.bmsGrid`) leaves a field that is not a ship
 *    without coordinates, where a figure lies outside the theater's square (from its centre, 0.75 of its side) or in
 *    the wrong hemisphere,
 *    where the DED keys are not 7 + 8 digits, or where the ship rule (`divertIsShip`) and the ground chart
 *    (`Airfield.ship`) disagree.
 * 2. **BMS's own figures**: every AIP or navaid list in the folder's `Docs` and the add-ons' `Docs` (a PDF named
 *    "…AIP…" or "…Navaid…") is read with PDFBox, and every latitude and longitude it prints ("N 41° 32.720'" then
 *    "E 015° 42.874'") is matched to the nearest field of each theater whose centre lies within 600 km of the
 *    document's: within 2 m is BMS's figure to the print's rounding, 2 m-2 km is listed, beyond 2 km is a field the
 *    theater does not have.
 * 3. **The real world** (with `airports.csv`, OurAirports' list: the extractor's `cache/airports.csv`): every field's
 *    distance from the nearest airfield the list has (a FAIL when more than a tenth have none within 5 km), and every
 *    field whose ICAO the list knows, its distance from that one — a sanity check (BMS places its fields
 *    near, not on, the real ones), which is what catches a hemisphere or a theater laid the wrong way round where BMS
 *    prints no figure of its own (the Falklands).
 */
object DivertCheck {
    private const val EXACT_M = 2.0
    private const val NEAR_M = 2000.0

    private data class DocPoint(val label: String, val lat: Double, val lon: Double)
    private data class Field(val a: Airport, val lat: Double, val lon: Double, val c: DedCoords)

    fun run(bms: File, airportsCsv: File?): String = buildString {
        appendLine("Divert block for every theater: coordinates, radios, BMS's own figures (${bms.name}), the real world")
        appendLine()
        val index = runBlocking { Repo.index() }
        val fieldsBy = LinkedHashMap<String, List<Field>>()
        appendLine("1. Every field of every theater")
        appendLine("   theater | fields | coords | none: ship / no projection / failed | projection | missing: APP TCN TWR(any) GND ILS ATIS ELEV | VHF-only tower | ship rule")
        for (th in index.theaters) {
            val set = try { runBlocking { Repo.airportSet(th.airportSet) }.airports } catch (e: Throwable) {
                appendLine("FAIL ${th.name}: airport set ${th.airportSet}: ${e::class.simpleName}: ${e.message}"); continue
            }
            if (set.isEmpty()) { appendLine("FAIL ${th.name}: no airports in ${th.airportSet}"); continue }
            val grid = th.projection?.let { WdpCoords.bmsGrid(it) } != null
            val cd = WdpCoords.coordData(th)
            val how = when { cd == null -> "none"; grid -> "BMS grid"; else -> "projection string" }
            var ships = 0; var noProj = 0; var failed = 0
            var app = 0; var tcn = 0; var twr = 0; var gnd = 0; var ils = 0; var atis = 0; var elev = 0; var vhfOnly = 0
            val fails = ArrayList<String>()
            val fields = ArrayList<Field>()
            val ctrLat = th.projection?.centerLat
            val ctrLon = th.projection?.centerLon
            for (a in set) {
                try {
                    if (a.name.isBlank()) fails += "field ${a.id} has no name"
                    val ship = divertIsShip(a)
                    val c = divertCoords(th, a)
                    when {
                        ship -> ships++
                        cd == null -> noProj++
                        c == null -> { failed++; if (grid) fails += "${a.name}: no coordinates on a theater with BMS's grid" }
                    }
                    if (ship && c != null) fails += "${a.name}: a ship given coordinates"
                    if (c != null) {
                        if (!Regex("""^[NS] \d{7}$""").matches(c.latKeys) || !Regex("""^[EW] \d{8}$""").matches(c.lngKeys)) fails += "${a.name}: keys ${c.latKeys} / ${c.lngKeys}"
                        val lat = degrees(c.lat); val lon = degrees(c.lng)
                        if (lat == null || lon == null) fails += "${a.name}: unreadable ${c.lat} ${c.lng}"
                        else {
                            fields += Field(a, lat, lon, c)
                            if (ctrLat != null && ctrLon != null) {
                                // the theater square's half diagonal is 0.71 of its side: 800 km on 1,024 km, 1,450 on 2,048
                                val d = metres(lat, lon, ctrLat, ctrLon)
                                if (d > (th.projection?.sizeKm ?: 1024.0) * 750.0) fails +="${a.name}: ${c.lat} ${c.lng} lies ${(d / 1000).toInt()} km from the theater's centre"
                                if ((lat < 0) != (ctrLat < 0) || (lon < 0) != (ctrLon < 0)) fails += "${a.name}: ${c.lat} ${c.lng} in the other hemisphere from the centre ($ctrLat, $ctrLon)"
                            }
                        }
                    }
                    val f = a.freqs
                    if (f?.approachUhf.isNullOrBlank()) app++
                    if (a.tacan == null) tcn++
                    if (f?.towerUhf.isNullOrBlank() && f?.towerVhf.isNullOrBlank()) twr++
                    if (f?.towerUhf.isNullOrBlank() && !f?.towerVhf.isNullOrBlank()) vhfOnly++
                    if (f?.groundUhf.isNullOrBlank()) gnd++
                    if (a.runways.none { r -> r.ends.any { it.ils != null } }) ils++
                    if (f?.atisVhf.isNullOrBlank()) atis++
                    if (!ship && a.elevationFt == null) elev++
                } catch (e: Throwable) {
                    fails += "${a.name}: ${e::class.simpleName}: ${e.message}"
                }
            }
            // the ship rule against the ground chart: a field whose chart is a deck is a ship, and only those
            val afSet = th.airfieldSet
            var charts = 0; var chartShips = 0
            if (afSet != null) {
                val idx = try { runBlocking { Repo.airfieldIndex(afSet) } } catch (_: Throwable) { emptyMap() }
                for (a in set) {
                    if (idx[a.id.toString()] == null) continue
                    val af = try { runBlocking { Repo.airfield(afSet, a.id) } } catch (_: Throwable) { null } ?: continue
                    charts++
                    if (af.ship != null) chartShips++
                    if ((af.ship != null) != divertIsShip(a)) fails +="${a.name}: chart says ${if (af.ship != null) "a ship" else "a field"}, the block ${if (divertIsShip(a)) "a ship" else "a field"} (kind ${airportKind(a)})"
                }
            }
            fieldsBy[th.id] = fields
            appendLine(
                "${if (fails.isEmpty()) "ok  " else "FAIL"} ${th.name.padEnd(22)} | ${set.size.toString().padStart(3)} | ${fields.size.toString().padStart(3)} | " +
                    "$ships / $noProj / $failed | $how | $app $tcn $twr $gnd $ils $atis $elev | $vhfOnly | ship rule held on $charts charts ($chartShips decks)",
            )
            fails.take(25).forEach { appendLine("       $it") }
            if (fails.size > 25) appendLine("       … ${fails.size - 25} more")
        }

        appendLine()
        appendLine("2. BMS's own figures (AIPs and navaid lists in the BMS folder's documents)")
        val docs = findDocs(bms)
        if (docs.isEmpty()) appendLine("info no AIP or navaid list found under ${bms.name}\\Docs or the add-ons' Docs")
        for (doc in docs) {
            val pts = try { readPoints(doc) } catch (e: Throwable) { appendLine("FAIL ${doc.name}: ${e::class.simpleName}: ${e.message}"); continue }
            val rel = doc.relativeToOrNull(bms)?.path ?: doc.name
            if (pts.isEmpty()) { appendLine("info $rel: no latitude and longitude printed"); continue }
            val mLat = pts.map { it.lat }.sorted()[pts.size / 2]
            val mLon = pts.map { it.lon }.sorted()[pts.size / 2]
            appendLine("   $rel: ${pts.size} positions, median N $mLat E $mLon")
            for (th in index.theaters) {
                val p = th.projection ?: continue
                val cl = p.centerLat ?: continue
                val cn = p.centerLon ?: continue
                if (metres(mLat, mLon, cl, cn) > 600_000) continue
                val fields = fieldsBy[th.id].orEmpty()
                var exact = 0; var near = 0; var none = 0; var sum = 0.0; var max = 0.0
                val off = ArrayList<String>()
                val gone = ArrayList<String>()
                val seen = HashSet<Int>()
                for (q in pts) {
                    val best = fields.minByOrNull { metres(q.lat, q.lon, it.lat, it.lon) }
                    val d = best?.let { metres(q.lat, q.lon, it.lat, it.lon) } ?: Double.MAX_VALUE
                    when {
                        d <= EXACT_M -> exact++
                        d <= NEAR_M -> { near++; off += "${best!!.a.name}: ours ${best.c.lat} ${best.c.lng}, print ${fmt(q.lat, true)} ${fmt(q.lon, false)} (${q.label}) = ${"%.0f".format(d)} m" }
                        else -> { none++; gone += "${fmt(q.lat, true)} ${fmt(q.lon, false)} (${q.label})" + (best?.let { ", nearest ${it.a.name} ${"%.1f".format(d / 1000)} km" } ?: "") }
                    }
                    if (d <= NEAR_M) { sum += d; if (d > max) max = d; seen += best!!.a.id }
                }
                val n = exact + near
                appendLine(
                    "${if (near == 0) "ok  " else "info"}   ${th.name.padEnd(22)} ${seen.size} fields checked: $exact of $n positions within $EXACT_M m" +
                        (if (n > 0) ", mean ${"%.1f".format(sum / n)} m, max ${"%.1f".format(max)} m" else "") + "; $none not a field of this theater",
                )
                off.forEach { appendLine("         $it") }
                // what the theater lacks, listed once per airport set (the Korea family shares two)
                if (th.id == index.theaters.firstOrNull { t -> t.airportSet == th.airportSet }?.id) gone.take(10).forEach { appendLine("         not a field: $it") }
            }
        }

        appendLine()
        appendLine("3. The real world (OurAirports), by ICAO: a sanity check, not BMS's figure")
        val csv = airportsCsv?.takeIf { it.isFile }?.let { readCsv(it) }
        val real = csv?.first
        if (csv == null || real == null) appendLine("info no airports.csv given")
        else for (th in index.theaters) {
            val fields = fieldsBy[th.id].orEmpty()
            // every field against the nearest airfield the list has, whatever its name: the one figure for theaters
            // whose data carries no ICAO (Hellas, Israel), and what a hemisphere or an axis turned the wrong way fails
            val all = csv.second
            val nearest = fields.map { f ->
                val lo = all.binarySearchBy(f.lat - 0.2) { it.first }.let { if (it < 0) -it - 1 else it }
                var best = Double.MAX_VALUE
                var i = lo
                while (i < all.size && all[i].first <= f.lat + 0.2) { val d = metres(f.lat, f.lon, all[i].first, all[i].second); if (d < best) best = d; i++ }
                f to best
            }
            if (nearest.isNotEmpty()) {
                val s = nearest.map { it.second }.sorted()
                val lost = nearest.filter { it.second > 5000 }
                appendLine(
                    "${if (lost.size * 10 <= nearest.size) "ok  " else "FAIL"} ${th.name.padEnd(22)} nearest published airfield to each of ${nearest.size}: median ${"%.0f".format(s[s.size / 2])} m, " +
                        "90% within ${"%.0f".format(s[(s.size * 9 / 10).coerceAtMost(s.size - 1)])} m; ${lost.size} with none within 5 km",
                )
            }
            val ds = fields.mapNotNull { f -> f.a.icao?.let { real[it] }?.let { (la, lo) -> Triple(f, metres(f.lat, f.lon, la, lo), la to lo) } }
            if (ds.isEmpty()) { appendLine("info ${th.name}: no field with a known ICAO"); continue }
            val sorted = ds.map { it.second }.sorted()
            val far = ds.filter { it.second > 5000 }
            appendLine(
                "${if (far.isEmpty()) "ok  " else "info"} ${th.name.padEnd(22)} ${ds.size} fields: median ${"%.0f".format(sorted[sorted.size / 2])} m, " +
                    "90% within ${"%.0f".format(sorted[(sorted.size * 9 / 10).coerceAtMost(sorted.size - 1)])} m, max ${"%.0f".format(sorted.last())} m",
            )
            far.sortedByDescending { it.second }.take(8).forEach { (f, d, ll) ->
                appendLine("       ${f.a.name} (${f.a.icao}): ours ${f.c.lat} ${f.c.lng}, published ${fmt(ll.first, true)} ${fmt(ll.second, false)} = ${"%.1f".format(d / 1000)} km")
            }
        }
    }

    /** "N 37°01.699'" → signed degrees. */
    private fun degrees(s: String): Double? {
        val m = Regex("""^([NSEW]) (\d{1,3})°(\d{2}\.\d{3})'$""").find(s) ?: return null
        val v = m.groupValues[2].toDouble() + m.groupValues[3].toDouble() / 60.0
        return if (m.groupValues[1] == "S" || m.groupValues[1] == "W") -v else v
    }

    private fun fmt(v: Double, lat: Boolean): String {
        val h = if (lat) (if (v < 0) "S" else "N") else (if (v < 0) "W" else "E")
        val a = kotlin.math.abs(v)
        val d = a.toInt()
        return "$h ${if (lat) "%02d".format(d) else "%03d".format(d)}°${"%06.3f".format((a - d) * 60.0)}'"
    }

    private fun metres(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371008.8
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
        val dp = p2 - p1; val dl = Math.toRadians(lon2 - lon1)
        val h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * r * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    private fun findDocs(bms: File): List<File> {
        val roots = ArrayList<File>()
        bms.listFiles()?.firstOrNull { it.isDirectory && it.name.equals("Docs", true) }?.let { roots += it }
        bms.listFiles()?.firstOrNull { it.isDirectory && it.name.equals("Data", true) }?.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("Add-On", true) }
            ?.forEach { addon -> addon.listFiles()?.filter { it.isDirectory && it.name.equals("Docs", true) }?.forEach { roots += it } }
        val seen = HashSet<String>()
        return roots.flatMap { r ->
            r.walkTopDown().maxDepth(4).filter { f ->
                f.isFile && f.extension.equals("pdf", true) && Regex("AIP|Navaid", RegexOption.IGNORE_CASE).containsMatchIn(f.nameWithoutExtension)
            }.toList()
        }.filter { seen.add(it.canonicalPath.lowercase()) }.sortedBy { it.path.lowercase() }
    }

    private val LAT = Regex("""([NS])\s?(\d{1,2})\s?[°º�]\s?(\d{1,2}[.,]\d{1,4})\s?['’`]?""")
    private val LON = Regex("""([EW])\s?(\d{1,3})\s?[°º�]\s?(\d{1,2}[.,]\d{1,4})\s?['’`]?""")

    /** Every printed latitude, paired with the first longitude after it and before the next latitude. */
    private fun readPoints(pdf: File): List<DocPoint> {
        val text = Loader.loadPDF(pdf).use { PDFTextStripper().getText(it) }
        val lats = LAT.findAll(text).toList()
        val out = ArrayList<DocPoint>()
        for ((i, m) in lats.withIndex()) {
            val end = lats.getOrNull(i + 1)?.range?.first ?: text.length
            val lon = LON.find(text, m.range.last + 1)?.takeIf { it.range.first < end && it.range.first - m.range.last < 400 } ?: continue
            fun v(g: MatchResult): Double {
                val x = g.groupValues[2].toDouble() + g.groupValues[3].replace(',', '.').toDouble() / 60.0
                return if (g.groupValues[1] == "S" || g.groupValues[1] == "W") -x else x
            }
            val label = text.substring((m.range.first - 60).coerceAtLeast(0), m.range.first).replace(Regex("\\s+"), " ").trim().takeLast(40)
            out += DocPoint(label, v(m), v(lon))
        }
        return out
    }

    /**
     * OurAirports' airports.csv: ICAO (or GPS code / ident, four letters) → latitude, longitude; and every airfield
     * (not a heliport, seaplane base or balloon port) as (latitude, longitude), sorted by latitude.
     */
    private fun readCsv(f: File): Pair<Map<String, Pair<Double, Double>>, List<Pair<Double, Double>>> {
        val lines = f.readLines()
        val head = lines.first().split(',').map { it.trim('"') }
        fun col(n: String) = head.indexOf(n)
        val cell = Regex("(\"([^\"]|\"\")*\"|[^,]*)(,|$)")
        val out = HashMap<String, Pair<Double, Double>>()
        val all = ArrayList<Pair<Double, Double>>()
        for (line in lines.drop(1)) {
            val c = cell.findAll(line).map { it.groupValues[1].trim('"') }.toList()
            val la = c.getOrNull(col("latitude_deg"))?.toDoubleOrNull() ?: continue
            val lo = c.getOrNull(col("longitude_deg"))?.toDoubleOrNull() ?: continue
            if (c.getOrNull(col("type")) !in setOf("heliport", "seaplane_base", "balloonport")) all += la to lo
            for (k in listOf("icao_code", "gps_code", "ident")) {
                val id = c.getOrNull(col(k)) ?: continue
                if (id.length == 4 && id !in out) out[id] = la to lo
            }
        }
        return out to all.sortedBy { it.first }
    }
}
