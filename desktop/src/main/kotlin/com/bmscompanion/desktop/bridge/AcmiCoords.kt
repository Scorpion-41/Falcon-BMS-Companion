package com.bmscompanion.desktop.bridge

import com.bmscompanion.app.data.DataIndex
import com.bmscompanion.app.data.wdp.PopupCoords
import com.bmscompanion.app.data.wdp.WdpCoords
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Locale
import java.util.zip.ZipFile
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot

/**
 * `--acmicoords <recording> out.txt [theater id]` — the latitude and longitude the Planner prints, against the ones
 * Falcon BMS itself writes into an ACMI recording (docs/WDP-PORT.md, D26).
 *
 * BMS 4.38.1 records every object as `id,T=lon|lat|alt|U|V` or `…|roll|pitch|yaw|U|V|heading`, U east and V north in
 * metres (the sim's feet ÷ 3.28084), so one line holds both a position in feet and BMS's own figure for it. The check
 * reads up to five complete records per object (20,000 positions), prints each through [WdpCoords.coordData] for
 * every theater of the app's data (or the one named) and says how far the print lies from BMS's figure; the
 * projection string of `Theater.txt` is measured beside it. The theater the recording was flown on is the one whose
 * prints all lie within 2 m — which is how a recording of a theater not yet checked (the Falklands) settles which
 * projection it needs. Reads the recording only (a `.zip.acmi` as BMS writes it, or its text); never throws.
 */
internal object AcmiCoords {
    private const val FT_PER_M = 3.28084
    private const val CAP = 20000

    fun run(recording: File, theaterId: String?): String = buildString {
        appendLine("ACMI coordinates — the Planner's latitude and longitude against BMS's own, from ${recording.name}")
        try {
            val index = WdpCoords::class.java.classLoader.getResourceAsStream("data/index.json")?.use { s ->
                Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true; explicitNulls = false }
                    .decodeFromString(DataIndex.serializer(), s.readBytes().decodeToString())
            } ?: run { appendLine("FAIL — the app's data/index.json is not on the classpath"); return@buildString }
            val pts = read(recording)
            appendLine("${pts.size} positions")
            if (pts.isEmpty()) { appendLine("FAIL — no complete position in the recording"); return@buildString }
            val theaters = index.theaters.filter { theaterId == null || it.id == theaterId }
            if (theaters.isEmpty()) { appendLine("FAIL — no theater '$theaterId' in the app's data"); return@buildString }
            val fits = ArrayList<String>()
            for (t in theaters) {
                val now = WdpCoords.coordData(t)
                val string = t.projection?.let { p ->
                    WdpCoords.appMeta(p)?.let { PopupCoords.CoordData(0.0, 0.0, p.sizeKm * 1000.0 * p.ftPerM, p.sizeKm * 1000.0 * p.ftPerM, true, it) }
                }
                val a = stats(now, pts)
                val b = stats(string, pts)
                val how = when { now == null -> "no projection"; now.tm.appProjection -> "the projection string"; else -> "BMS's grid" }
                appendLine("${t.name} ($how): now ${a.text()}; the projection string ${b.text()}")
                if (a.n == pts.size && a.within2 == a.n) fits += t.name
            }
            appendLine()
            appendLine(
                if (fits.isNotEmpty()) "PASS — every position within 2 m of BMS's own on: ${fits.joinToString()}"
                else "FAIL — no theater of the app's data prints this recording's positions within 2 m of BMS's own",
            )
        } catch (e: Throwable) {
            appendLine("FAIL — ${e::class.simpleName}: ${e.message}")
        }
    }

    /** (north ft, east ft, lat, lon) */
    private fun read(f: File): List<DoubleArray> {
        val out = ArrayList<DoubleArray>()
        val per = HashMap<String, Int>()
        fun line(l: String): Boolean {
            if (out.size >= CAP) return false
            val c = l.indexOf(",T=")
            if (c <= 0 || l.startsWith("0,") || l.startsWith("#")) return true
            val id = l.substring(0, c)
            val k = per[id] ?: 0
            if (k >= 5) return true
            val t = l.substring(c + 3).substringBefore(',').split('|')
            val v = when (t.size) { 5 -> listOf(t[0], t[1], t[3], t[4]); 9 -> listOf(t[0], t[1], t[6], t[7]); else -> return true }
                .map { it.toDoubleOrNull() ?: return true }
            per[id] = k + 1
            out += doubleArrayOf(v[3] * FT_PER_M, v[2] * FT_PER_M, v[1], v[0])
            return true
        }
        val zipped = f.inputStream().use { s -> val b = ByteArray(2); s.read(b) == 2 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte() }
        if (zipped) ZipFile(f).use { z ->
            val e = z.entries().asSequence().firstOrNull { !it.isDirectory } ?: return out
            z.getInputStream(e).bufferedReader().useLines { ls -> for (l in ls) if (!line(l)) break }
        } else f.bufferedReader().useLines { ls -> for (l in ls) if (!line(l)) break }
        return out
    }

    private class Stats(val n: Int, val mean: Double, val max: Double, val within2: Int) {
        fun text(): String = if (n == 0) "prints nothing"
            else String.format(Locale.ROOT, "%d printed, mean %.1f m, max %.1f m, %d within 2 m", n, mean, max, within2)
    }

    private fun stats(c: PopupCoords.CoordData?, pts: List<DoubleArray>): Stats {
        c ?: return Stats(0, 0.0, 0.0, 0)
        var n = 0; var sum = 0.0; var max = 0.0; var w = 0
        for (p in pts) {
            val s = try { PopupCoords.feetToCoordsBoth(c, p[0], p[1]) } catch (e: Exception) { continue }
            if (s == PopupCoords.ZERO) continue
            val la = degrees(PopupCoords.getNorthDeg(s), c) ?: continue
            val lo = degrees(PopupCoords.getEastDeg(s), c) ?: continue
            val d = hypot((la - p[2]) * 60 * 1852.0, (lo - p[3]) * 60 * 1852.0 * cos(p[2] * PI / 180))
            n++; sum += d; if (d > max) max = d; if (d <= 2.0) w++
        }
        return Stats(n, if (n > 0) sum / n else 0.0, max, w)
    }

    /** A printed half back to degrees: sign and magnitude on the projection string, WDP's floor on the grid. */
    private fun degrees(s: String, c: PopupCoords.CoordData): Double? =
        if (c.tm.appProjection) PopupCoords.labelToDegrees(s)
        else {
            val d = s.substringBefore(',').toIntOrNull()
            val m = s.substringAfter(',').toDoubleOrNull()
            if (d == null || m == null) null else d + m / 60.0
        }
}
