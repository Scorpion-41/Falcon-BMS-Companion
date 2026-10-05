package com.bmscompanion.desktop.bridge

import com.bmscompanion.app.data.DataIndex
import com.bmscompanion.app.data.Theater
import com.bmscompanion.app.data.TheaterProjection
import com.bmscompanion.app.data.WdpTerrain
import com.bmscompanion.app.data.wdp.PopupCoords
import com.bmscompanion.app.data.wdp.PopupNet
import com.bmscompanion.app.data.wdp.PopupPlan
import com.bmscompanion.app.data.wdp.TossPlan
import com.bmscompanion.app.data.wdp.WdpCoords
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.asinh
import kotlin.math.atan2
import kotlin.math.atanh
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * `--wdppagetest coords <coords.tsv> <out.txt>` — latitude and longitude as the Planner prints them, against **the
 * latitude and longitude Falcon BMS itself gives** (D26).
 *
 * The verdict is BMS's own figure. BMS 4.38.1 writes each object's latitude and longitude beside its position in feet
 * in its ACMI recordings, and prints a "BMS coord" for every airbase in its AIPs; a sample of both is carried here
 * ([ACMI], [KTO_AIP], [BALKANS_AIP]). Up to 1.3.8's fix the Planner printed with the projection string of
 * `Theater.txt`, taken for "BMS's own"; it lies 140-220 m from those figures on average (up to 290 m), and Weapon Delivery
 * Planner's own grid lies within a metre of them. So:
 *
 * - **every theater of the app's data** is handed BMS's grid — WDP's arithmetic over the size, centre and heightmap
 *   length of the terrain BMS flies it on ([WdpCoords.bmsGrid]) — which on every theater WDP finds a terrain for is
 *   exactly what WDP prints; the one exception is the Falklands (2,048 km: no BMS figure checked), which keeps the
 *   projection string;
 * - **a grid and a scatter of points per theater** print, within the print's own rounding, an independent copy of
 *   that projection — `tools/extractor/src/projection.mjs`'s `makeProjector`, Krüger's series term by term, set up
 *   as the grid (3.28084 ft/m, the centre's forward projection, one heightmap sample north) or as the projection
 *   string — inverted numerically here, so a mistake in the port's GeographicLib series, its parameters, its sign
 *   handling or its rounding cannot agree with itself; the labels read back land within a foot or three;
 * - **BMS's own figures**: every theater on a terrain BMS's recordings or AIPs cover prints them within the print's
 *   rounding (a thousandth of a minute, 1.85 m of latitude), and the projection string is measured beside it;
 * - **real airfields** (a few per terrain) print within a few miles of their published positions — a check on signs
 *   and axes, which would put them hundreds of miles out;
 * - **WDP's reference** (`tools/wdpref page Coords`): its own arithmetic bit for bit (the grid the pages now print
 *   with), and every point WDP printed against what the Planner prints now — identical, except where the list of
 *   expected differences says (`wdp/expected-diffs/coords.txt`: the Falklands, Korea TvT's labels); the TOSS page's
 *   coordinate labels too (`<ref>.toss.tsv`).
 */
internal fun wdpCoordsTest(reference: File, out: File): String = buildString {
    appendLine("Weapon Delivery Planner coordinates — the Planner against the latitude and longitude BMS itself gives (its ACMI, its AIPs), and against WDP")
    appendLine("reference: ${reference.path}")
    val index = WdpCoords::class.java.classLoader.getResourceAsStream("data/index.json")?.use { s ->
        Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true; explicitNulls = false }
            .decodeFromString(DataIndex.serializer(), s.readBytes().decodeToString())
    }
    if (index == null) { appendLine("FAIL — the app's data/index.json is not on the classpath"); return@buildString }
    val byName = index.theaters.associateBy { it.name.trim() }
    val allow = AttackAllowList.load("coords")
    appendLine("expected differences from WDP: ${allow.describe()}")

    var bad = 0

    // ---- 1. the projection each theater's pages get
    appendLine()
    appendLine("1. What each theater's pages get (WdpCoords.coordData), against the theater's own record:")
    val app = LinkedHashMap<String, Pair<PopupCoords.CoordData, MapProjector>>()     // theater id -> the port's, the independent copy
    for (t in index.theaters) {
        val p = t.projection
        if (p == null) { bad++; appendLine("FAIL ${t.name}: the app's data gives it no projection (plannercheck.mjs should have caught this)"); continue }
        val c = WdpCoords.coordData(t)
        if (c == null) { bad++; appendLine("FAIL ${t.name}: refused its projection '${p.proj}'"); continue }
        val tm = c.tm
        val diffs = ArrayList<String>()
        fun eq(name: String, want: Double, got: Double) { if (want != got) diffs += "$name: want $want, got $got" }
        val grid = p.sizeKm == WdpCoords.GRID_KM
        val map: MapProjector
        val note: String
        if (grid) {
            // BMS's grid: WDP's arithmetic over what the theater's own terrain says
            if (tm.appProjection) diffs += "a 1,024 km theater is not given BMS's grid"
            if (p.heightmapBytes == null) diffs += "its terrain record has no heightmap length"
            val want = WdpCoords.meta(WdpTerrain(heightmapBytes = p.heightmapBytes, theaterTxt = true, sizeKm = p.sizeKm, centerLat = p.centerLat, centerLon = p.centerLon))
            if (tm != want) diffs += "not WDP's arithmetic over its terrain: $tm vs $want"
            eq("width", WdpCoords.CAMP_1024_FT, c.campW); eq("height", WdpCoords.CAMP_1024_FT, c.campH)
            // where WDP reads a terrain too, the pages print exactly WDP's own
            val w = WdpCoords.wdpCoordData(t)
            note = when {
                t.wdpTerrain == null -> " (WDP found no terrain; this is the one BMS flies it on)"
                w == c -> " (= WDP's own)"
                else -> { diffs += "WDP reads another terrain: ${t.wdpTerrain} vs the terrain BMS flies it on"; "" }
            }
            map = MapProjector.grid(p, tm.meterRes.toDouble())
        } else {
            // the projection string, where BMS's grid is not established (the Falklands)
            eq("meridian", p.lon0, tm.meridian); eq("offsetX", -p.x0, tm.offsetX); eq("offsetY", -p.y0, tm.offsetY)
            eq("ft/m", p.ftPerM, tm.ftPerM); eq("k0", p.k0, tm.k0); eq("lat0", p.lat0, tm.lat0)
            eq("width", p.sizeKm * 1000.0 * p.ftPerM, c.campW); eq("height", p.sizeKm * 1000.0 * p.ftPerM, c.campH)
            if (!c.enableNewTerrain || !tm.appProjection) diffs += "not the projection string"
            note = " (the projection string: BMS's grid is only established for 1,024 km)"
            map = MapProjector(p)
        }
        app[t.id] = c to map
        // Theater.txt's centre prints where the projection puts it: the middle of the square on the projection string
        // (to the rounding of its y_0, a few metres), 512 km east and 512 km less a heightmap sample north on the grid
        val (cN, cE) = if (grid) map.forward(p.centerLat ?: 0.0, p.centerLon ?: 0.0) else c.campW / 2.0 to c.campW / 2.0
        val centre = PopupCoords.feetToCoordsBoth(c, cN, cE)
        val cl = PopupCoords.labelToDegrees(PopupCoords.getNorthDeg(centre))
        val cn = PopupCoords.labelToDegrees(PopupCoords.getEastDeg(centre))
        if (p.centerLat != null && p.centerLon != null) {
            if (cl == null || cn == null || abs(cl - p.centerLat!!) * 60.0 > 0.02 || abs(cn - p.centerLon!!) * 60.0 > 0.02)
                diffs += "centre (N ${"%.0f".format(cN)} E ${"%.0f".format(cE)} ft) prints $centre, Theater.txt says ${p.centerLat}, ${p.centerLon}"
        }
        val how = if (grid) "BMS's grid" else "projection string ${p.proj}"
        if (diffs.isEmpty()) appendLine("ok   ${t.name}: $how; Theater.txt's centre prints $centre (${p.centerLat}, ${p.centerLon})$note")
        else { bad++; appendLine("FAIL ${t.name}: " + diffs.joinToString("; ")) }
    }

    // ---- 2. points, against the independent copy
    appendLine()
    appendLine("2. Points, against an independent copy of each projection (projection.mjs's series, inverted here):")
    // theaters on one terrain print the same: one run each, named after all of them
    val groups = index.theaters.filter { it.id in app }.groupBy { terrainKey(it) }
    for ((_, ts) in groups) {
        val (c, map) = app.getValue(ts[0].id)
        val grid = !c.tm.appProjection
        val size = c.campW
        val pts = ArrayList<Pair<Double, Double>>()
        val n = 60
        for (i in 0..n) for (j in 0..n) pts += size * i / n to size * j / n
        val rnd = java.util.Random(0x5EED + ts[0].id.hashCode().toLong())
        repeat(3000) { pts += rnd.nextDouble() * size to rnd.nextDouble() * size }
        var rows = 0; var exact = 0; var edge = 0; var wrong = 0
        var backMax = 0.0; var simMax = 0.0; var roundMax = 0.0
        val ex = StringBuilder()
        // the grid carries the feet as floats and its arithmetic in floats, as WDP does (a tenth of a metre at most)
        val edgeTol = if (grid) GRID_EDGE else EDGE
        for ((fn0, fe0) in pts) {
            if (fn0 == 0.0 && fe0 == 0.0) continue     // "no point" to every page, as in WDP
            rows++
            val got = try { PopupCoords.feetToCoordsBoth(c, fn0, fe0) } catch (e: Exception) { "ERR:" + e::class.simpleName }
            val fn = if (grid) fn0.toFloat().toDouble() else fn0
            val fe = if (grid) fe0.toFloat().toDouble() else fe0
            val (lat, lon) = map.inverse(fn, fe)
            val (wantN, edgeN) = expectLabel(lat, 2)
            val (wantE, edgeE) = expectLabel(lon, 3)
            val want = "$wantN/$wantE"
            if (got == want) exact++
            else if (min2(edgeN, edgeE) < edgeTol) edge++          // on a thousandth of a minute's rounding edge
            else { wrong++; if (ex.length < 3000) ex.appendLine("     N $fn0 E $fe0: port '$got', the independent copy '$want'") }
            if (got.startsWith("ERR")) continue
            // the label read back: ConvertLatLonToFeet ("east,north", seven digits) against the independent forward of
            // the printed degrees; and the printed degrees themselves within half a thousandth of a minute
            val pl = PopupCoords.labelToDegrees(PopupCoords.getNorthDeg(got)) ?: continue
            val pn = PopupCoords.labelToDegrees(PopupCoords.getEastDeg(got)) ?: continue
            val (mn, me) = map.forward(pl, pn)
            roundMax = max(roundMax, hypot(mn - fn0, me - fe0))
            val back = PopupCoords.convertLatLonToFeet(c, got).split(',').map { it.trim().toDouble() }
            backMax = max(backMax, hypot(back[1] - mn, back[0] - me))
            val (se, sn) = PopupCoords.convertLatLonToSimXY(c.tm, lat.toFloat(), lon.toFloat())
            val (fn2, fe2) = map.forward(lat.toFloat().toDouble(), lon.toFloat().toDouble())
            simMax = max(simMax, hypot(sn - fn2, se - fe2))
        }
        val names = ts.joinToString { it.name }
        // WDP's read-back takes the degrees as floats (0.4 m at 38°) and its feet as floats (a quarter of a foot)
        val backTol = if (grid) 3.0 else 1.5
        val ok = wrong == 0 && backMax <= backTol && simMax <= backTol && roundMax <= 6.0
        if (!ok) bad++
        appendLine((if (ok) "ok   " else "FAIL ") + "$names (${if (grid) "BMS's grid" else "projection string"}): $rows points, $exact printed exactly as the independent copy rounds" +
            (if (edge > 0) ", $edge on a rounding edge (within $edgeTol of a thousandth of a minute)" else "") +
            (if (wrong > 0) ", $wrong WRONG" else "") +
            String.format(Locale.ROOT, "; read back within %.2f ft (ConvertLatLonToFeet), %.2f ft (ConvertLatLonToSimXY from floats); the printed figures within %.2f ft of the point", backMax, simMax, roundMax))
        append(ex)
    }

    // ---- 3. BMS's own figures
    appendLine()
    appendLine("3. BMS's own latitude and longitude (its ACMI recordings, its AIPs' \"BMS coord\"), and the projection string beside it:")
    for ((_, ts) in groups) {
        val t0 = ts[0]
        val (c, _) = app.getValue(t0.id)
        val key = terrainKey(t0)
        val names = ts.joinToString { it.name }
        val string = t0.projection?.let { p -> WdpCoords.appMeta(p)?.let { PopupCoords.CoordData(0.0, 0.0, p.sizeKm * 1000.0 * p.ftPerM, p.sizeKm * 1000.0 * p.ftPerM, true, it) } }
        val parts = ArrayList<String>()
        var failed = false
        fun measure(what: String, pts: List<Triple<Double, Double, Pair<Double, Double>>>, tol: Double) {
            if (pts.isEmpty()) return
            var worst = 0.0; var sum = 0.0; var sWorst = 0.0; var sSum = 0.0
            for ((fn, fe, ll) in pts) {
                val d = metresFrom(c, fn, fe, ll)
                worst = max(worst, d); sum += d
                string?.let { s -> metresFrom(s, fn, fe, ll).let { sWorst = max(sWorst, it); sSum += it } }
            }
            if (worst > tol) failed = true
            parts += String.format(Locale.ROOT, "%d %s within %.1f m (mean %.1f m; the projection string %.0f m, at most %.0f m)", pts.size, what, worst, sum / pts.size, sSum / pts.size, sWorst)
        }
        val acmi = ACMI.filter { a -> index.theaters.firstOrNull { it.id == a.theater }?.let { terrainKey(it) } == key }
            .map { a -> Triple(a.v.toDouble() * 3.28084, a.u.toDouble() * 3.28084, a.lat.toDouble() to a.lon.toDouble()) }
        measure("positions of BMS's recordings", acmi, ACMI_M)
        for ((srcId, what, list) in listOf(Triple("korea-kto", "KTO AIP airbases", KTO_AIP), Triple("balkans", "Balkans AIP airports", BALKANS_AIP))) {
            val src = index.theaters.firstOrNull { it.id == srcId } ?: continue
            if (terrainKey(src) != key) continue
            val airports = loadAirports(src.airportSet)
            val pts = ArrayList<Triple<Double, Double, Pair<Double, Double>>>()
            for ((name, lat, lon) in list) {
                val a = airports.firstOrNull { it.first == name }
                if (a == null) { failed = true; parts += "no airport '$name' in ${src.airportSet}"; continue }
                pts += Triple(a.second, a.third, lat to lon)
            }
            measure(what, pts, AIP_M)
        }
        when {
            parts.isEmpty() && c.tm.appProjection -> appendLine("info $names: no BMS figure to check against (no recording, no AIP with BMS coordinates): the projection string, not verified")
            parts.isEmpty() -> appendLine("info $names: BMS's grid, but no BMS figure of this terrain to check it against")
            failed -> { bad++; appendLine("FAIL $names: " + parts.joinToString("; ")) }
            else -> appendLine("ok   $names: " + parts.joinToString("; "))
        }
    }

    // ---- 4. real airfields
    appendLine()
    appendLine("4. Real airfields (the app's airport data), printed, against their published positions:")
    for ((idOrName, spec) in AIRFIELDS) {
        val t = index.theaters.firstOrNull { it.id == idOrName } ?: run { bad++; appendLine("FAIL no theater $idOrName"); null } ?: continue
        val (c, _) = app[t.id] ?: continue
        val airports = loadAirports(t.airportSet)
        for ((name, lat, lon) in spec) {
            val a = airports.firstOrNull { it.first.contains(name, ignoreCase = true) }
            if (a == null) { bad++; appendLine("FAIL ${t.name}: no airport named like '$name' in ${t.airportSet}"); continue }
            val printed = PopupCoords.feetToCoordsBoth(c, a.second, a.third)
            val pl = PopupCoords.labelToDegrees(PopupCoords.getNorthDeg(printed))!!
            val pn = PopupCoords.labelToDegrees(PopupCoords.getEastDeg(printed))!!
            val nm = greatCircleNm(pl, pn, lat, lon)
            val line = String.format(Locale.ROOT, "%s, %s: prints %s, %.2f nm from %.4f, %.4f", t.name, a.first, printed, nm, lat, lon)
            if (nm <= AIRFIELD_NM) appendLine("ok   $line") else { bad++; appendLine("FAIL $line") }
        }
    }

    // ---- 5. WDP's reference
    appendLine()
    appendLine("5. Weapon Delivery Planner's reference: its own arithmetic bit for bit, and each of its prints against the Planner's now (D26):")
    if (!reference.isFile) {
        appendLine("info no reference (tools/wdpref page Coords writes it); WDP's arithmetic not replayed")
    } else {
        bad += wdpSection(reference, byName, app, allow)
    }
    append(allow.report())

    appendLine()
    appendLine(if (bad == 0) "PASS — every theater prints the latitude and longitude BMS itself gives (the Falklands, with no BMS figure to check, its projection string); WDP differs only as expected-diffs/coords.txt says"
        else "FAIL — $bad check(s) disagree")
}

/** Theaters flown on one terrain print alike: its size, centre and heightmap length. */
private fun terrainKey(t: Theater): String = t.projection?.let { "${it.sizeKm}|${it.centerLat}|${it.centerLon}|${it.heightmapBytes}|${it.proj}" } ?: "-"

/** Metres from the lat/lon [ll] to what [c] prints for ([north], [east]). */
private fun metresFrom(c: PopupCoords.CoordData, north: Double, east: Double, ll: Pair<Double, Double>): Double {
    val s = PopupCoords.feetToCoordsBoth(c, north, east)
    val la = PopupCoords.labelToDegrees(PopupCoords.getNorthDeg(s)) ?: return Double.POSITIVE_INFINITY
    val lo = PopupCoords.labelToDegrees(PopupCoords.getEastDeg(s)) ?: return Double.POSITIVE_INFINITY
    return greatCircleNm(la, lo, ll.first, ll.second) * 1852.0
}

/** How far a published airfield may be from its print before the parameters are wrong rather than BMS's placing, nm. */
private const val AIRFIELD_NM = 3.0

/** How close to a rounding edge, in thousandths of a minute, two correct projections may round apart. */
private const val EDGE = 1e-3

/** The same for the grid, whose feet and arithmetic are floats as in WDP (a tenth of a metre, 0.05 of a thousandth). */
private const val GRID_EDGE = 0.1

/**
 * How far a print may lie from BMS's own figure, metres: half a thousandth of a minute each way (0.93 m of latitude,
 * less of longitude), with a recording's centimetres ([ACMI_M]), or with the AIP's own thousandth of a minute and the
 * airport's position rounded to the foot ([AIP_M]).
 */
private const val ACMI_M = 1.5
private const val AIP_M = 2.2

/** One object as BMS 4.38.1 recorded it: U (east) and V (north) in metres (sim feet / 3.28084), its latitude and longitude. */
private class AcmiPoint(val theater: String, val u: String, val v: String, val lat: String, val lon: String, val what: String)

/**
 * Positions from BMS's own ACMI recordings, eight a terrain spread across it (the bullseye first), exactly as the
 * files write them: `T=lon|lat|alt|…|U|V`.
 */
private val ACMI = listOf(
    AcmiPoint("korea-kto", "310418.62", "650329.50", "39.722960", "125.148048", "2026-09-20 Bullseye"),
    AcmiPoint("korea-kto", "429253.60", "242304.71", "36.065728", "126.581146", "2026-09-24 FixedWing"),
    AcmiPoint("korea-kto", "596333.02", "508380.46", "38.463673", "128.466649", "2026-09-20 Vehicle"),
    AcmiPoint("korea-kto", "365700.45", "457656.22", "37.998702", "125.833754", "2026-09-20 Vehicle"),
    AcmiPoint("korea-kto", "555354.41", "348408.65", "37.024789", "127.987415", "2026-09-24 FixedWing"),
    AcmiPoint("korea-kto", "470415.94", "568562.92", "39.009023", "127.019709", "2026-09-20 FixedWing"),
    AcmiPoint("korea-kto", "485822.44", "445796.71", "37.903242", "127.202232", "2026-09-20 Static"),
    AcmiPoint("korea-kto", "361405.25", "333412.61", "36.878593", "125.810221", "2026-09-24 FixedWing"),
    AcmiPoint("hellas-wcp", "47487.55", "895265.25", "41.319585", "19.450990", "2026-09-23 Bullseye"),
    AcmiPoint("hellas-wcp", "282928.58", "420504.18", "37.147503", "22.420718", "2026-09-22 FixedWing"),
    AcmiPoint("hellas-wcp", "240920.82", "695450.95", "39.610538", "21.842441", "2026-09-23 FixedWing"),
    AcmiPoint("hellas-wcp", "121096.90", "530101.47", "38.078836", "20.543973", "2026-09-22 FixedWing"),
    AcmiPoint("hellas-wcp", "299445.14", "854818.23", "41.061474", "22.470402", "2026-09-23 FixedWing"),
    AcmiPoint("hellas-wcp", "76507.94", "704893.21", "39.627594", "19.926857", "2026-09-23 Static"),
    AcmiPoint("hellas-wcp", "113265.62", "388075.43", "36.799200", "20.531648", "2026-09-22 FixedWing"),
    AcmiPoint("hellas-wcp", "165147.98", "815169.70", "40.659121", "20.897395", "2026-09-22 Vehicle"),
    AcmiPoint("israel", "587346.00", "820284.94", "34.778166", "35.823468", "2026-09-24 Bullseye"),
    AcmiPoint("israel", "512365.68", "419390.06", "31.164717", "35.003837", "2026-09-24 FixedWing"),
    AcmiPoint("israel", "645341.39", "592368.06", "32.717232", "36.422813", "2026-09-24 Vehicle"),
    AcmiPoint("israel", "501607.96", "564104.49", "32.470293", "34.889411", "2026-09-24 Static"),
    AcmiPoint("israel", "390397.69", "464378.28", "31.564211", "33.718667", "2026-09-20 Bullseye"),
    AcmiPoint("israel", "567758.24", "665917.29", "33.387294", "35.599511", "2026-09-24 Vehicle"),
    AcmiPoint("israel", "617917.92", "514869.58", "32.021211", "36.121581", "2026-09-24 Vehicle"),
    AcmiPoint("israel", "480047.43", "485897.19", "31.764336", "34.662579", "2026-09-24 Static"),
)

/** "BMS Lat/Lon coord" of BMS's KTO AIP (Docs/03 KTO Charts/KTO_AIP.pdf, AD 3.1.1-3.1.2), degrees and minutes as printed. */
private val KTO_AIP: List<Triple<String, Double, Double>> = listOf(
    Triple("Cheongju Intl Airport", 36 + 42.857 / 60, 127 + 29.493 / 60), Triple("Gangneung AB", 37 + 45.275 / 60, 128 + 56.576 / 60),
    Triple("Gunsan AB", 35 + 54.297 / 60, 126 + 37.064 / 60), Triple("Gwangju AB", 35 + 7.916 / 60, 126 + 48.349 / 60),
    Triple("Incheon Intl Airport", 37 + 28.430 / 60, 126 + 27.083 / 60), Triple("Jungwon AB", 37 + 1.699 / 60, 127 + 53.116 / 60),
    Triple("Osan AB", 37 + 4.862 / 60, 127 + 1.386 / 60), Triple("Pohang AB", 35 + 59.123 / 60, 129 + 25.200 / 60),
    Triple("Sacheon AB", 35 + 4.997 / 60, 128 + 3.978 / 60), Triple("Seoul AB", 37 + 26.922 / 60, 127 + 6.512 / 60),
    Triple("Suwon AB", 37 + 13.880 / 60, 127 + 0.727 / 60), Triple("Yangyang Intl Airport", 38 + 3.859 / 60, 128 + 40.045 / 60),
)

/** "BMS GPS COORD" of the Balkans' own AIP (Add-On Balkans/Docs/Balkans Navaids List.pdf), as printed. */
private val BALKANS_AIP: List<Triple<String, Double, Double>> = listOf(
    Triple("Aviano Airbase", 46 + 2.074 / 60, 12 + 35.422 / 60), Triple("Amendola Airbase", 41 + 32.720 / 60, 15 + 42.874 / 60),
    Triple("Sarajevo Airport", 43 + 49.595 / 60, 18 + 20.233 / 60), Triple("Tirana Airport", 41 + 24.811 / 60, 19 + 43.168 / 60),
    Triple("Pristina Airport", 42 + 34.523 / 60, 21 + 1.615 / 60), Triple("Nikola Tesla Airport", 44 + 49.451 / 60, 20 + 18.459 / 60),
    Triple("Pescara Airport", 42 + 25.930 / 60, 14 + 10.770 / 60), Triple("Villafranca Airport", 45 + 23.691 / 60, 10 + 53.204 / 60),
    Triple("Skopski Petrovac", 41 + 57.714 / 60, 21 + 37.247 / 60), Triple("Kerkira Airport", 39 + 35.982 / 60, 19 + 55.134 / 60),
)

/**
 * A few airfields per terrain with their published positions (AIP / public aerodrome data, to four decimals).
 * The test's tolerance is a few miles because BMS's terrain places a field near, not on, the real one; a wrong sign
 * or swapped axis puts it hundreds of miles out.
 */
private val AIRFIELDS: List<Pair<String, List<Triple<String, Double, Double>>>> = listOf(
    "korea-kto" to listOf(Triple("Osan", 37.0906, 127.0297), Triple("Gunsan", 35.9038, 126.6158)),
    "korea-tvt" to listOf(Triple("Osan", 37.0906, 127.0297)),
    "balkans" to listOf(Triple("Aviano", 46.0319, 12.5965)),
    "hellas" to listOf(Triple("Larissa", 39.6503, 22.4656), Triple("Souda", 35.5317, 24.1497)),
    "israel" to listOf(Triple("Ramat David", 32.6651, 35.1795), Triple("Nevatim", 31.2083, 35.0123)),
    "falklands" to listOf(Triple("Mount Pleasant", -51.8228, -58.4472)),
)

/** (name, north ft, east ft) of every airport in a theater's set. */
private fun loadAirports(set: String): List<Triple<String, Double, Double>> {
    val text = WdpCoords::class.java.classLoader.getResourceAsStream("data/airports/$set.json")?.use { it.readBytes().decodeToString() }
        ?: return emptyList()
    val root = Json.parseToJsonElement(text).jsonObject
    return root["airports"]?.jsonArray?.mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        val name = o["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
        val x = o["x"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return@mapNotNull null
        val y = o["y"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return@mapNotNull null
        Triple(name, x, y)
    } ?: emptyList()
}

private fun greatCircleNm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = PI / 180
    val a = sin((lat2 - lat1) * r / 2).let { it * it } + cos(lat1 * r) * cos(lat2 * r) * sin((lon2 - lon1) * r / 2).let { it * it }
    return 2 * asin(sqrt(a)) * 6371008.8 / 1852.0
}

private fun min2(a: Double, b: Double) = if (a < b) a else b

/**
 * One half of a label as it should read, worked out here from scratch: the magnitude's whole degrees, its minutes to
 * the nearest thousandth (60.000 carried), "-" for south and west; and how close the minutes were to a rounding edge,
 * in thousandths. (The grid prints whole degrees by floor, which for the northern and eastern figures it is handed
 * is the same.)
 */
private fun expectLabel(v: Double, degDigits: Int): Pair<String, Double> {
    val a = abs(v)
    var deg = floor(a).toInt()
    val m = (a - deg) * 60000.0
    var r = Math.rint(m)
    val edge = abs(m - floor(m) - 0.5)
    if (r >= 60000.0) { r -= 60000.0; deg++ }
    val sign = if (v < 0 && (deg > 0 || r > 0)) "-" else ""
    return sign + deg.toString().padStart(degDigits, '0') + "," + String.format(Locale.ROOT, "%06.3f", r / 1000.0) to edge
}

/**
 * `tools/extractor/src/projection.mjs`'s `makeProjector`, line for line: latitude and longitude to theater feet
 * (x north, y east) with Krüger's series written out term by term — the other way from the port's GeographicLib
 * (Clenshaw's sums over the reverse series), so the two only agree if both are right. [inverse] is Newton's method
 * on it. [grid] sets it up as BMS's grid instead of a projection string.
 */
private class MapProjector(private val t: TheaterProjection) {
    companion object {
        /**
         * BMS's grid for the terrain [p], built here from its definition rather than WDP's code: metres are feet ÷
         * 3.28084 about the centre's meridian at UTM's scale, the false easting half the theater, and the false
         * northing half the theater less the centre's own northing less one heightmap sample ([cellM] metres).
         */
        fun grid(p: TheaterProjection, cellM: Double): MapProjector {
            val half = p.sizeKm * 500.0
            val about = TheaterProjection(lon0 = p.centerLon ?: 0.0, k0 = PopupCoords.UTM_K0, x0 = 0.0, y0 = 0.0, ftPerM = 1.0, centerLat = p.centerLat, centerLon = p.centerLon)
            val centreNorthing = MapProjector(about).forward(p.centerLat ?: 0.0, p.centerLon ?: 0.0).first
            return MapProjector(about.copy(x0 = half, y0 = half - cellM - centreNorthing, ftPerM = 3.28084))
        }
    }

    private val a = 6378137.0
    private val f = 1 / 298.257223563
    private val n = f / (2 - f)
    private fun p(e: Int): Double { var r = 1.0; repeat(e) { r *= n }; return r }
    private val aRect = a / (1 + n) * (1 + p(2) / 4 + p(4) / 64 + p(6) / 256)
    private val alpha = doubleArrayOf(
        n / 2 - 2 * p(2) / 3 + 5 * p(3) / 16 + 41 * p(4) / 180 - 127 * p(5) / 288 + 7891 * p(6) / 37800,
        13 * p(2) / 48 - 3 * p(3) / 5 + 557 * p(4) / 1440 + 281 * p(5) / 630 - 1983433 * p(6) / 1935360,
        61 * p(3) / 240 - 103 * p(4) / 140 + 15061 * p(5) / 26880 + 167603 * p(6) / 181440,
        49561 * p(4) / 161280 - 179 * p(5) / 168 + 6601661 * p(6) / 7257600,
        34729 * p(5) / 80640 - 3418889 * p(6) / 1995840,
        212378941 * p(6) / 319334400,
    )
    private val e = sqrt(f * (2 - f))

    /** (north ft, east ft). */
    fun forward(lat: Double, lon: Double): Pair<Double, Double> {
        val phi = lat * PI / 180
        val lam = lon * PI / 180 - t.lon0 * PI / 180
        val tau = tan(phi)
        val sigma = sinh(e * atanh(e * tau / sqrt(1 + tau * tau)))
        val tauP = tau * sqrt(1 + sigma * sigma) - sigma * sqrt(1 + tau * tau)
        val xiP = atan2(tauP, cos(lam))
        val etaP = asinh(sin(lam) / sqrt(tauP * tauP + cos(lam) * cos(lam)))
        var xi = xiP
        var eta = etaP
        for (j in 1..6) {
            xi += alpha[j - 1] * sin(2 * j * xiP) * cosh(2 * j * etaP)
            eta += alpha[j - 1] * cos(2 * j * xiP) * sinh(2 * j * etaP)
        }
        val easting = t.k0 * aRect * eta + t.x0
        val northing = t.k0 * aRect * xi + t.y0
        return northing * t.ftPerM to easting * t.ftPerM
    }

    /** (lat, lon) of theater feet, to a ten-millionth of a foot. */
    fun inverse(north: Double, east: Double): Pair<Double, Double> {
        var lat = t.centerLat ?: 0.0
        var lon = t.centerLon ?: t.lon0
        repeat(40) {
            val (fn, fe) = forward(lat, lon)
            val dn = north - fn
            val de = east - fe
            if (abs(dn) < 1e-7 && abs(de) < 1e-7) return lat to lon
            val h = 1e-6
            val (n1, e1) = forward(lat + h, lon)
            val (n2, e2) = forward(lat, lon + h)
            val a11 = (n1 - fn) / h; val a12 = (n2 - fn) / h
            val a21 = (e1 - fe) / h; val a22 = (e2 - fe) / h
            val det = a11 * a22 - a12 * a21
            lat += (a22 * dn - a12 * de) / det
            lon += (a11 * de - a21 * dn) / det
        }
        return lat to lon
    }
}

/**
 * Section 5: WDP's reference. Returns the number of failed checks.
 *
 * (a) WDP's own projection and conversions, bit for bit — the grid the pages print with, and the path the other
 * pages' reference tests replay; (b) GeographicLib's forward and reverse, which both paths share; (c) every point WDP
 * printed for a real theater, against what the Planner prints now: the same wherever the Planner is handed WDP's grid,
 * different only as expected-diffs/coords.txt says (the Falklands), and how far WDP's print lay from the independent
 * copy of what the Planner prints; (d) the TOSS page's coordinate labels on WDP's own rows.
 */
private fun StringBuilder.wdpSection(
    reference: File,
    byName: Map<String, Theater>,
    app: Map<String, Pair<PopupCoords.CoordData, MapProjector>>,
    allow: AttackAllowList,
): Int {
    var bad = 0
    val wdpByKey = HashMap<String, PopupCoords.CoordData>()
    val appByKey = HashMap<String, Pair<PopupCoords.CoordData, MapProjector>>()
    val nameByKey = HashMap<String, String>()
    val lines = reference.readLines().filter { it.isNotEmpty() }
    lines.firstOrNull { it.startsWith("info\t") }?.let { appendLine("     WDP's GeographicLib: ${it.split('\t').last()}") }

    appendLine("  a. WDP's projection (fclsMain.InitNewTerrain), field by field:")
    for (line in lines.filter { it.startsWith("theater\t") }) {
        val f = line.split('\t')
        val key = f[1]
        val names = f[2]
        val raw = WdpTerrain(
            heightmapBytes = f[4].toLong().takeIf { it >= 0 },
            theaterTxt = f[3] == "1",
            sizeKm = f[5].takeIf { it != "-" }?.trim()?.toDouble(),
            centerLat = f[6].takeIf { it != "-" }?.trim()?.toDouble(),
            centerLon = f[7].takeIf { it != "-" }?.trim()?.toDouble(),
        )
        val c: PopupCoords.CoordData?
        val label: String
        if (names == "-") {
            label = "$key (synthetic)"
            c = WdpCoords.wdpCoordData(Theater(id = key, name = key, wdpTerrain = raw.takeIf { it.heightmapBytes != null || it.theaterTxt }))
        } else {
            val found = names.split('|').map { it to byName[it.trim()] }
            label = names.split('|').first() + (if (names.contains('|')) " (+${names.count { it == '|' }} on the same terrain)" else "")
            nameByKey[key] = label
            val missing = found.filter { it.second == null }.map { it.first }
            val wrong = found.mapNotNull { (n, t) -> t?.takeIf { it.wdpTerrain != raw.takeIf { r -> r.heightmapBytes != null || r.theaterTxt } }?.let { "$n ${it.wdpTerrain}" } }
            if (missing.isNotEmpty() || wrong.isNotEmpty()) {
                bad++
                appendLine("FAIL $label: the app's theater list " +
                    (if (missing.isNotEmpty()) "has no ${missing.joinToString()}" else "") +
                    (if (wrong.isNotEmpty()) " disagrees with the files: ${wrong.joinToString()} vs $raw" else ""))
            }
            val first = found.firstNotNullOfOrNull { it.second }
            c = WdpCoords.wdpCoordData(first)
            first?.let { th -> app[th.id]?.let { appByKey[key] = it } }
        }
        if (c == null) { appendLine("FAIL $label: no coordinate data"); bad++; continue }
        wdpByKey[key] = c
        val m = f[9].split(',')
        val tm = c.tm
        val diffs = ArrayList<String>()
        val near = ArrayList<String>()
        fun d(name: String, want: String, got: Double) { if (want != hexD(got)) diffs += "$name WDP ${fromHexD(want)} port $got" }
        fun s(name: String, want: String, got: Float) { if (want != hexF(got)) diffs += "$name WDP ${fromHexF(want)} port $got" }
        // the offsets are GeographicLib's forward projection of the centre, whose sines and hyperbolic functions
        // are Microsoft's C runtime's in WDP and the platform's here: a unit or two in the last place is libm
        fun o(name: String, want: String, got: Double) {
            val u = ulps(fromHexD(want), got)
            if (u > ULP_SLACK) diffs += "$name WDP ${fromHexD(want)} port $got" else if (u > 0) near += "$name $u ulp"
        }
        d("Meridian", m[0], tm.meridian); o("offsetX", m[1], tm.offsetX); o("offsetY", m[2], tm.offsetY)
        if (m[5] != tm.theaterSizeInMeters.toString()) diffs += "theaterSizeInMeters WDP ${m[5]} port ${tm.theaterSizeInMeters}"
        s("HEIGHTMAP_SIZE", m[6], tm.heightmapSize); s("METER_RES", m[7], tm.meterRes); s("FT_TO_GRID", m[8], tm.ftToGrid)
        s("GRID_TO_FT", m[9], tm.gridToFt); s("GRID_OFFSET", m[10], tm.gridOffset)
        if (diffs.isEmpty()) appendLine("ok   $label: samples ${f[8]}" + (if (near.isEmpty()) "" else " (${near.joinToString()} from WDP's)"))
        else { bad++; appendLine("FAIL $label: " + diffs.joinToString("; ")) }
    }

    appendLine("  a. WDP's points under WDP's projection (FeetToCoordsBoth, GetNorthDeg/GetEastDeg, ConvertLatLonToFeet and its two steps):")
    val perKey = LinkedHashMap<String, IntArray>()
    val badCols = LinkedHashMap<String, Int>()
    val examples = StringBuilder()
    // c. the same points as the Planner prints them now
    class D26(var rows: Int = 0, var same: Int = 0, var allowed: Int = 0, var failed: Int = 0, val metres: ArrayList<Double> = ArrayList(), var grid: Boolean = true)
    val d26 = LinkedHashMap<String, D26>()
    val d26Examples = StringBuilder()
    for (line in lines) {
        val f = line.split('\t')
        when (f[0]) {
            "c" -> {
                val c = wdpByKey[f[1]] ?: continue
                val fn = fromHexD(f[2])
                val fe = fromHexD(f[3])
                val res = tryStr { PopupCoords.feetToCoordsBoth(c, fn, fe) }
                var n = ""; var e = ""; var simXY = ""; var decs = ""; var sim = ""
                if (!res.startsWith("ERR")) {
                    n = tryStr { PopupCoords.getNorthDeg(res) }
                    e = tryStr { PopupCoords.getEastDeg(res) }
                    simXY = tryStr { PopupCoords.convertLatLonToFeet(c, res) }
                    decs = tryStr {
                        val (la, lo) = PopupCoords.coordinatesToDec(n, e)
                        sim = tryStr { PopupCoords.convertLatLonToSimXY(c.tm, la.toFloat(), lo.toFloat()).let { hexF(it.first) + "," + hexF(it.second) } }
                        hexD(la) + "," + hexD(lo)
                    }
                }
                val got = listOf(res, n, e, simXY, decs, sim)
                val want = f.subList(4, 10)
                val counts = perKey.getOrPut(f[1]) { IntArray(2) }
                counts[0]++
                val cols = listOf("coords", "north", "east", "simXY", "degrees", "simX,simY")
                var rowBad = false
                for (i in cols.indices) if (got[i] != want[i]) {
                    rowBad = true
                    badCols[cols[i]] = (badCols[cols[i]] ?: 0) + 1
                    if (examples.length < 3000) examples.appendLine("     ${f[1]} N $fn E $fe ${cols[i]}: port '${got[i]}' vs WDP '${want[i]}'")
                }
                if (rowBad) counts[1]++

                // c. what the Planner prints now for a real theater's point, against WDP's print
                val (ac, map) = appByKey[f[1]] ?: continue
                if (fn == 0.0 && fe == 0.0) continue
                if (fn < 0 || fe < 0 || fn > ac.campW || fe > ac.campW) continue       // WDP's own probes outside the theater
                val dd = d26.getOrPut(f[1]) { D26() }
                dd.rows++
                dd.grid = !ac.tm.appProjection
                // where WDP's print sends the jet (read as the jet reads a typed coordinate), from the point as the
                // Planner's projection has it
                val wl = PopupCoords.labelToDegrees(want[1]); val wn = PopupCoords.labelToDegrees(want[2])
                if (wl != null && wn != null && want[0] != PopupCoords.ZERO) {
                    val (mn, me) = map.forward(wl, wn)
                    dd.metres += hypot(mn - fn, me - fe) / 3.28084
                }
                val now = tryStr { PopupCoords.feetToCoordsBoth(ac, fn, fe) }
                if (now == want[0]) { dd.same++; continue }
                val ctx = mapOf("theater" to (nameByKey[f[1]] ?: f[1]))
                if (allow.match("coords", want[0], now, ctx) != null) dd.allowed++
                else { dd.failed++; if (d26Examples.length < 2000) d26Examples.appendLine("     ${f[1]} N $fn E $fe: now '$now', WDP '${want[0]}' — no expected-diffs line allows it") }
            }
            "back" -> {
                val c = wdpByKey[f[1]] ?: continue
                val s = f[2].removePrefix("[").removeSuffix("]")
                val got = tryStr { PopupCoords.convertLatLonToFeet(c, s) }
                val counts = perKey.getOrPut(f[1]) { IntArray(2) }
                counts[0]++
                if (got != f[3]) {
                    counts[1]++
                    badCols["label strings"] = (badCols["label strings"] ?: 0) + 1
                    if (examples.length < 3000) examples.appendLine("     ${f[1]} ConvertLatLonToFeet('$s'): port '$got' vs WDP '${f[3]}'")
                }
            }
        }
    }
    for ((k, v) in perKey) {
        val name = nameByKey[k] ?: "$k (synthetic)"
        if (v[1] == 0) appendLine("ok   $name: ${v[0]} points, every string and every bit identical")
        else { bad++; appendLine("FAIL $name: ${v[1]} of ${v[0]} points differ") }
    }
    if (badCols.isNotEmpty()) { appendLine("     by column: " + badCols.entries.joinToString { "${it.key}×${it.value}" }); append(examples) }

    // ---- b. GeographicLib, and the label splitters (shared by both paths)
    appendLine("  b. GeographicLib's TransverseMercator, bit for bit, and GetNorthDeg/GetEastDeg of odd strings:")
    // compared to the bit and shown as information: GeographicLib's sines, cosines and hyperbolic functions are
    // Microsoft's C runtime's inside WDP and the platform's here, and neither promises correct rounding. A call that
    // fails on one side and not on the other would be the port's fault, and fails.
    val geo = LinkedHashMap<String, IntArray>()             // samples, identical, within slack, max ulps, errors
    val geoExamples = StringBuilder()
    var deg = 0; var degBad = 0
    fun geoSample(what: String, args: String, got: String, want: String) {
        val g = geo.getOrPut(what) { IntArray(5) }
        g[0]++
        if (got == want) { g[1]++; return }
        if (got.startsWith("ERR") != want.startsWith("ERR")) g[4]++
        val u = if (got.startsWith("ERR") || want.startsWith("ERR")) Long.MAX_VALUE
        else got.split(',').zip(want.split(',')).maxOf { (a, b) -> ulps(fromHexD(a), fromHexD(b)) }
        if (u <= ULP_SLACK) g[2]++
        if (u != Long.MAX_VALUE) g[3] = maxOf(g[3], minOf(u, Int.MAX_VALUE.toLong()).toInt())
        if (u > ULP_SLACK && geoExamples.length < 1500) geoExamples.appendLine("     $what($args): port ${show(got)} vs WDP ${show(want)}")
    }
    for (line in lines) {
        val f = line.split('\t')
        when (f[0]) {
            "fwd" -> geoSample("Forward", "${fromHexD(f[1])}, ${fromHexD(f[2])}, ${fromHexD(f[3])}",
                tryStr { PopupCoords.transverseMercatorForward(fromHexD(f[1]), fromHexD(f[2]), fromHexD(f[3])).let { hexD(it.first) + "," + hexD(it.second) } }, f[4])
            "rev" -> geoSample("Reverse", "${fromHexD(f[1])}, ${fromHexD(f[2])}, ${fromHexD(f[3])}",
                tryStr { PopupCoords.transverseMercatorReverse(fromHexD(f[1]), fromHexD(f[2]), fromHexD(f[3])).let { hexD(it.first) + "," + hexD(it.second) } }, f[4])
            "deg" -> {
                deg++
                val s = if (f[1] == "<null>") null else f[1].removePrefix("[").removeSuffix("]")
                val n = "[" + tryStr { PopupCoords.getNorthDeg(s) } + "]"
                val e = "[" + tryStr { PopupCoords.getEastDeg(s) } + "]"
                if (n != f[2] || e != f[3]) { degBad++; geoExamples.appendLine("     GetNorthDeg/GetEastDeg(${f[1]}): port $n $e vs WDP ${f[2]} ${f[3]}") }
            }
        }
    }
    for ((what, g) in geo) {
        val off = g[0] - g[1] - g[2]
        if (g[4] > 0) { bad++; appendLine("FAIL $what: ${g[4]} of ${g[0]} fail on one side only") }
        appendLine("info $what: ${g[1]} of ${g[0]} identical to the bit, ${g[2]} within $ULP_SLACK units in the last place, $off further (at most ${g[3]} units)")
    }
    if (degBad == 0) appendLine("ok   GetNorthDeg/GetEastDeg: $deg samples identical") else { bad++; appendLine("FAIL GetNorthDeg/GetEastDeg: $degBad of $deg differ") }
    append(geoExamples)

    // ---- c. D26, per theater
    appendLine("  c. WDP's prints against the Planner's now (D26), and how far WDP's print lay from the point as the Planner's projection has it:")
    for ((k, dd) in d26) {
        val m = dd.metres.sorted()
        val med = if (m.isEmpty()) Double.NaN else m[m.size / 2]
        val mx = m.lastOrNull() ?: Double.NaN
        val line = "${nameByKey[k] ?: k} (${if (dd.grid) "BMS's grid" else "the projection string"}): ${dd.rows} points inside the theater, ${dd.same} print as WDP did" +
            (if (dd.allowed > 0) ", ${dd.allowed} differ as expected-diffs/coords.txt allows" else "") +
            (if (m.isEmpty()) "; WDP printed no position for any of them"
            else String.format(Locale.ROOT, "; WDP's print lay %.1f m from the point at the median, %.1f m at most (%.2f nm)", med, mx, mx / 1852.0))
        if (dd.failed == 0) appendLine("ok   $line") else { bad++; appendLine("FAIL $line, ${dd.failed} differ otherwise") }
    }
    append(d26Examples)

    // ---- d. TOSS, VIP mode
    appendLine("  d. The TOSS page's coordinate labels (Get_Coords) on WDP's rows: WDP's where it is handed WDP's grid, and the independent copy's rounding:")
    val toss = File(reference.path + ".toss.tsv")
    if (!toss.isFile) {
        bad++; appendLine("FAIL no ${toss.name} beside the reference")
        return bad
    }
    val tl = toss.readLines().filter { it.isNotEmpty() }
    val head = tl[0].split('\t')
    val idx = head.withIndex().associate { it.value to it.index }
    var rows = 0; var rowsBad = 0; var allowed = 0; var same = 0
    val tossExamples = StringBuilder()
    for (line in tl.drop(1)) {
        val f = line.split('\t')
        fun col(n: String) = f[idx.getValue(n)]
        val theater = byName[col("theater").trim()] ?: continue
        val (ac, map) = app[theater.id] ?: continue
        val grid = !ac.tm.appProjection
        rows++
        val p = TossPlan()
        p.ref = false
        p.load()
        p.objC = WdpCoords.coordData(theater)
        p.waypoint = col("wp").toInt()
        fun fl(n: String) = fromHexF(col(n)).toDouble()
        p.setTargets(Triple(fl("tgtN"), fl("tgtE"), fl("tgtZ")), Triple(fl("ipN"), fl("ipE"), fl("ipZ")))
        p.ingressCas = 450; p.changeIngressSpeed()
        p.ingressHeight = col("ingrHeight").toInt() * 100; p.changeIngressHeight()
        p.pullingGs = col("g").toInt(); p.changePullingG()
        p.turnDirection = col("turn"); p.changeTurn()
        p.oa2ToPupNm = 3; p.changeOa2ToPup()
        p.releaseAngleDeg = col("relAngle").toInt(); p.changeRelAngle()
        p.releaseCas = col("relCas").toInt(); p.changeRelSpd()
        p.releaseHeight = col("relHeight").toInt() * 100; p.changeRelHeight()
        p.angleOff = col("angleOff").toInt(); p.changeAngleOff()
        p.attackHeadingDeg = col("heading").toInt(); p.changeHeading()
        p.chooseRef(col("vip") == "1" && p.doVip)
        var rowBad = false
        val ctx = mapOf("theater" to col("theater").trim())
        for ((lbl, pt) in listOf("lblTGT_N" to (fl("tgtN") to fl("tgtE")), "lblTGT_E" to (fl("tgtN") to fl("tgtE")),
            "lblIP_N" to (fl("ipN") to fl("ipE")), "lblIP_E" to (fl("ipN") to fl("ipE")))) {
            val wantShown = col("$lbl.visible") == "1"
            val gotShown = p.coordShown[lbl] ?: true
            val got = p.labels[lbl] ?: ""
            if (wantShown != gotShown) {
                // a theater WDP had no terrain for (Korea TvT) now shows its coordinates
                if (allow.match("$lbl.visible", wantShown.toString(), gotShown.toString(), ctx) != null) allowed++
                else { rowBad = true; if (tossExamples.length < 3000) tossExamples.appendLine("     ${col("theater")} $lbl shown: port $gotShown, WDP $wantShown") }
            }
            if (!gotShown) continue
            // the label is the independent copy's rounding of the point, whatever WDP printed
            val (lat, lon) = if (grid) map.inverse(pt.first.toFloat().toDouble(), pt.second.toFloat().toDouble()) else map.inverse(pt.first, pt.second)
            val (want, edge) = if (lbl.endsWith("_N")) expectLabel(lat, 2) else expectLabel(lon, 3)
            if (got != want && edge >= (if (grid) GRID_EDGE else EDGE)) { rowBad = true; if (tossExamples.length < 3000) tossExamples.appendLine("     ${col("theater")} $lbl: port '$got', the independent copy '$want'") }
            if (wantShown && got == col(lbl)) same++
            if (wantShown && got != col(lbl)) {
                if (allow.match(lbl, col(lbl), got, ctx) != null) allowed++
                else { rowBad = true; if (tossExamples.length < 3000) tossExamples.appendLine("     ${col("theater")} $lbl: port '$got', WDP '${col(lbl)}' — no expected-diffs line allows it") }
            }
        }
        if (rowBad) rowsBad++
    }
    if (rows == 0) { bad++; appendLine("FAIL no TOSS rows whose theater the app knows") }
    else if (rowsBad == 0) appendLine("ok   $rows rows: every target and IP label is its projection's rounding of the point; $same labels as WDP printed them, $allowed differ as expected-diffs/coords.txt allows")
    else { bad++; appendLine("FAIL $rowsBad of $rows rows"); append(tossExamples) }
    return bad
}

/** How far GeographicLib's answers may sit from WDP's, in units in the last place, before it is the port's fault. */
private const val ULP_SLACK = 4L

/** The distance between two doubles in units in the last place (NaN only matches NaN). */
private fun ulps(a: Double, b: Double): Long {
    if (a.isNaN() || b.isNaN()) return if (a.isNaN() && b.isNaN()) 0 else Long.MAX_VALUE
    fun ordered(d: Double): Long { val r = d.toRawBits(); return if (r < 0) Long.MIN_VALUE - r else r }
    val x = ordered(a); val y = ordered(b)
    return if ((x >= 0) == (y >= 0)) kotlin.math.abs(x - y) else kotlin.math.abs(x) + kotlin.math.abs(y)
}

private fun hexD(d: Double): String = String.format("%016X", d.toRawBits())
private fun hexF(f: Float): String = String.format("%08X", f.toRawBits())
private fun fromHexD(s: String): Double = Double.fromBits(java.lang.Long.parseUnsignedLong(s, 16))
private fun fromHexF(s: String): Float = Float.fromBits(java.lang.Long.parseLong(s, 16).toInt())

/** "X,Y" in hex as the two numbers, for a message. */
private fun show(s: String): String = if (s.startsWith("ERR")) s else s.split(',').joinToString(",") { fromHexD(it).toString() }

private fun coordsErr(e: Throwable): String = when (e) {
    is PopupPlan.PopupOverflow -> "ERR:OverflowException"
    is PopupNet.PopupCast -> "ERR:InvalidCastException"
    is PopupNet.PopupArgument, is PopupCoords.VbArgument -> "ERR:ArgumentException"
    else -> "ERR:" + e::class.simpleName
}

private fun tryStr(f: () -> String): String = try { f() } catch (e: Exception) { coordsErr(e) }
