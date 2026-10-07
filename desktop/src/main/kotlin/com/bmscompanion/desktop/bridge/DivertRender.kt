package com.bmscompanion.desktop.bridge

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.rememberNavController
import com.bmscompanion.app.data.Airport
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.Theater
import com.bmscompanion.app.ui.screens.AirportDetail
import com.bmscompanion.app.ui.screens.DivertBlock
import com.bmscompanion.app.ui.screens.airportKind
import com.bmscompanion.app.ui.screens.divertCoords
import com.bmscompanion.app.ui.screens.wdp.WdpProbe
import com.bmscompanion.app.ui.theme.BmsTheme
import com.bmscompanion.app.ui.theme.Hud
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.math.cos

/**
 * `--divertrender <out folder>` — the airfield page's Info box, coordinates and radios (DivertBlock.kt; "the block" below).
 *
 * 1. Its coordinates against BMS's own: every airbase the KTO AIP prints a "BMS coord" for (AD 3.1.1-3.1.2), the
 *    block's DED figure beside the AIP's, the difference in thousandths of a minute and in metres (a FAIL over 2 m,
 *    which is more than the print's own rounding).
 * 2. The page drawn headless — Jungwon (KTO), a carrier and a Falklands field, and at phone and tablet only Aviano
 *    (Balkans), Andravida (Hellas), Ramat David (Israel) and HMS Hermes (a Falklands ship typed "Airbase"), and Aqaba
 *    (Israel, a VHF-only tower) at all three; every theater's every field is `--divertcheck` (DivertCheck.kt) — at a phone (400 x 800 dp), a tablet
 *    (1280 x 800, the rail beside it) and a PC window (1600 x 1000). The block is one compact **Info** box under the
 *    ground chart, in the chart's column, so it is measured alone at that column's width and must stay compact (a FAIL
 *    over [MAX_ONE_COL_DP] with its tables on one column, [MAX_DP] on two or three); and in the drawn page (the parts'
 *    places through `airfieldProbe`) the box must start under the chart, within its column and at most [MAX_GAP_DP]
 *    below it.
 */
object DivertRender {
    private const val RAIL_DP = 80
    /** The tallest the Info box may be with its tables on one column (a phone), and on two or three (dp). */
    private const val MAX_ONE_COL_DP = 330
    private const val MAX_DP = 230
    private const val MAX_GAP_DP = 24

    /** KTO AIP 4.38, AD 3.1.1-3.1.2, "BMS Lat/Lon coord", as printed (the same list WdpCoordsTest measures). */
    private val KTO_AIP = listOf(
        Triple("Jungwon AB", "N 37°01.699'", "E 127°53.116'"), Triple("Osan AB", "N 37°04.862'", "E 127°01.386'"),
        Triple("Gunsan AB", "N 35°54.297'", "E 126°37.064'"), Triple("Cheongju Intl Airport", "N 36°42.857'", "E 127°29.493'"),
        Triple("Gangneung AB", "N 37°45.275'", "E 128°56.576'"), Triple("Gwangju AB", "N 35°07.916'", "E 126°48.349'"),
        Triple("Incheon Intl Airport", "N 37°28.430'", "E 126°27.083'"), Triple("Pohang AB", "N 35°59.123'", "E 129°25.200'"),
        Triple("Sacheon AB", "N 35°04.997'", "E 128°03.978'"), Triple("Seoul AB", "N 37°26.922'", "E 127°06.512'"),
        Triple("Suwon AB", "N 37°13.880'", "E 127°00.727'"), Triple("Yangyang Intl Airport", "N 38°03.859'", "E 128°40.045'"),
    )

    private data class Shape(val name: String, val w: Int, val h: Int, val d: Float, val rail: Boolean)

    private val SHAPES = listOf(
        Shape("phone-400x800", 400, 800, 2.625f, rail = false),
        Shape("tablet-1280x800", 1280, 800, 1f, rail = true),
        Shape("pc-1600x1000", 1600, 1000, 1f, rail = false),
    )

    fun run(out: File): String = buildString {
        out.mkdirs()
        org.jetbrains.skia.Surface.makeRasterN32Premul(1, 1).close()
        appendLine("The Info box (Reference → Airfields → a field): coordinates against BMS's KTO AIP, and the page at three sizes")
        appendLine()
        val kto = runBlocking { Repo.theater("korea-kto") }
        if (kto == null) { appendLine("FAIL no korea-kto theater"); return@buildString }
        val ktoSet = runBlocking { Repo.airportSet(kto.airportSet) }.airports
        appendLine("1. The DED figures against the KTO AIP's \"BMS coord\":")
        for ((name, lat, lng) in KTO_AIP) {
            val a = ktoSet.firstOrNull { it.name == name }
            val c = a?.let { divertCoords(kto, it) }
            if (c == null) { appendLine("FAIL $name: ${if (a == null) "not in the airport data" else "no coordinates"}"); continue }
            val dLat = thousandths(c.lat) - thousandths(lat)
            val dLng = thousandths(c.lng) - thousandths(lng)
            val latDeg = thousandths(lat) / 60000.0
            val m = kotlin.math.hypot(dLat * 1.852, dLng * 1.852 * cos(Math.toRadians(latDeg)))
            val ok = m <= 2.0
            appendLine("${if (ok) "ok  " else "FAIL"} ${name.padEnd(22)} ours ${c.lat} ${c.lng} (keys ${c.latKeys} / ${c.lngKeys}) · AIP $lat $lng · Δ ${dLat}/${dLng} thousandths of a minute = ${"%.1f".format(m)} m")
        }
        appendLine()
        appendLine("2. The page: the block's size in the chart's column, and its place under the chart:")
        val jungwon = ktoSet.firstOrNull { it.name == "Jungwon AB" }
        val index = runBlocking { Repo.index() }
        val ship = findCarrier(index.theaters)
        val falk = index.theaters.firstOrNull { it.id == "falklands" }?.let { th ->
            runBlocking { Repo.airportSet(th.airportSet) }.airports.firstOrNull { it.name.contains("Pleasant", true) }?.let { th to it }
        }
        // the other theaters, phone and tablet: Balkans, Hellas, Israel (one of its VHF-only towers too), a Falklands ship
        fun field(thId: String, name: String): Pair<Theater, Airport>? = index.theaters.firstOrNull { it.id == thId }?.let { th ->
            runBlocking { Repo.airportSet(th.airportSet) }.airports.firstOrNull { it.name == name }?.let { th to it }
        }
        val others = listOfNotNull(
            field("balkans", "Aviano Airbase")?.let { "balkans" to it }, field("hellas", "Andravida Airbase")?.let { "hellas" to it },
            field("israel", "Ramat David Airbase")?.let { "israel" to it }, field("israel", "Aqaba INT Airport")?.let { "israel-vhf" to it },
            field("falklands", "HMS HERMES")?.let { "falklands-ship" to it },
        )
        val cases = listOfNotNull(jungwon?.let { "jungwon" to (kto to it) }, ship?.let { "carrier" to it }, falk?.let { "falklands" to it }) + others
        for ((tag, pair) in cases) {
            val (th, a) = pair
            val c = divertCoords(th, a)
            appendLine("     $tag: ${a.name} (${th.name}) — ${c?.let { "${it.lat} ${it.lng}${if (it.checked) "" else ", projection string"}" } ?: "no coordinates (moves with the ship)"}")
            for (s in if (others.any { it.first == tag } && tag != "israel-vhf") SHAPES.take(2) else SHAPES) {
                val paneW = s.w - if (s.rail) RAIL_DP else 0
                // the chart's column: AdaptiveSplit's left half (16 dp apart, at most 1400 wide) from 760 dp, else the pane
                val inner = (paneW - 32).coerceAtMost(1400)
                val colW = if (inner >= 760) (inner - 16) / 2 else inner
                val blockH = measure(th, a, colW, s.d)
                // the box's own rule (DivertBlock: 200-dp columns 10 apart inside 12-dp sides, at most three)
                val cols = ((colW - 24 + 10) / 210).coerceIn(1, 3)
                val max = if (cols == 1) MAX_ONE_COL_DP else MAX_DP
                appendLine(
                    "${if (blockH <= max) "ok  " else "FAIL"} $tag ${s.name}: Info box ${blockH} dp tall in a ${colW}-dp column, " +
                        "$cols column${if (cols == 1) "" else "s"} (at most $max)",
                )
                WdpProbe.on = true
                WdpProbe.rects.keys.removeAll { it.startsWith("airfield/") }
                shot(out, "divert-$tag-${s.name}", s) { AirportDetail(rememberNavController(), th.id, a.id, onBack = {}) }
                val chart = WdpProbe.rects["airfield/chart"]
                val box = WdpProbe.rects["airfield/info"]
                WdpProbe.on = false
                fun dp(v: Float) = (v / s.d).toInt()
                when {
                    box == null -> appendLine("FAIL $tag ${s.name}: the Info box was not drawn on the page")
                    chart == null -> appendLine("info $tag ${s.name}: no ground chart drawn; the Info box starts at ${dp(box.top)} dp")
                    else -> {
                        val gap = dp(box.top - chart.bottom)
                        val inColumn = box.left >= chart.left - 1 && box.right <= chart.right + 1
                        val ok = gap in 0..MAX_GAP_DP && inColumn
                        appendLine(
                            "${if (ok) "ok  " else "FAIL"} $tag ${s.name}: chart ends at ${dp(chart.bottom)} dp, Info box ${dp(box.top)}-${dp(box.bottom)} " +
                                "(gap $gap dp, ${if (inColumn) "in the chart's column" else "outside the chart's column"})",
                        )
                    }
                }
            }
        }
        listFolds(out)
    }

    /**
     * 3. The Airfields page on a wide screen: the list beside the first field, then a field picked from the list — the
     * list folds away and the field takes the width (its back arrow brings the list back). Pictures only.
     */
    private fun StringBuilder.listFolds(out: File) {
        appendLine()
        appendLine("3. The Airfields page, wide: the list, then a field picked from it (the list folds away)")
        for (s in SHAPES.filter { it.w >= 1000 }) {
            val scene = ImageComposeScene((s.w * s.d).toInt(), (s.h * s.d).toInt(), Density(s.d)) {
                CompositionLocalProvider(LocalConfiguration provides Configuration(s.w, s.h)) {
                    BmsTheme {
                        Row(Modifier.fillMaxSize().background(Hud.Bg)) {
                            if (s.rail) Box(Modifier.width(RAIL_DP.dp).fillMaxHeight().background(Hud.Surface))
                            Box(Modifier.weight(1f).fillMaxHeight()) { com.bmscompanion.app.ui.screens.AirfieldsPage(rememberNavController()) }
                        }
                    }
                }
            }
            try {
                var t = 0L
                fun settle() = repeat(30) { scene.render(t); t += 50_000_000; Thread.sleep(30) }
                settle()
                File(out, "airfields-list-${s.name}.png").writeBytes(scene.render(t).encodeToData(EncodedImageFormat.PNG)!!.bytes)
                // the third row of the list: under the tabs, the search, the kind chips and the count
                val at = androidx.compose.ui.geometry.Offset(((if (s.rail) RAIL_DP else 0) + 200) * s.d, 330 * s.d)
                scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Press, at)
                scene.render(t); t += 50_000_000
                scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Release, at)
                settle()
                File(out, "airfields-picked-${s.name}.png").writeBytes(scene.render(t).encodeToData(EncodedImageFormat.PNG)!!.bytes)
                appendLine("     pictures airfields-list-${s.name}.png, airfields-picked-${s.name}.png")
            } catch (e: Throwable) {
                appendLine("FAIL airfields page ${s.name}: ${e::class.simpleName}: ${e.message}")
            } finally {
                scene.close()
            }
        }
    }

    /** "N 37°01.699'" → thousandths of a minute from the equator / meridian, signed by the hemisphere. */
    private fun thousandths(s: String): Long {
        val hemi = s[0]
        val deg = s.substring(2).substringBefore('°').toLong()
        val min = s.substringAfter('°').removeSuffix("'").replace(".", "").toLong()
        val v = deg * 60000 + min
        return if (hemi == 'S' || hemi == 'W') -v else v
    }

    private fun findCarrier(theaters: List<Theater>): Pair<Theater, Airport>? {
        for (th in theaters) {
            val a = runBlocking { Repo.airportSet(th.airportSet) }.airports.firstOrNull { airportKind(it) == "Carrier" && it.freqs != null } ?: continue
            return th to a
        }
        return null
    }

    /** The block alone at [widthDp], as the chart's column lays it out: its height in dp. */
    private fun measure(th: Theater, a: Airport, widthDp: Int, d: Float): Int {
        var h = 0
        val scene = ImageComposeScene((widthDp * d).toInt(), (2000 * d).toInt(), Density(d)) {
            BmsTheme {
                Column(Modifier.width(widthDp.dp)) {
                    Box(Modifier.fillMaxWidth().onGloballyPositioned { h = it.size.height }) { DivertBlock(th, a) }
                }
            }
        }
        try { var t = 0L; repeat(4) { scene.render(t); t += 50_000_000 } } finally { scene.close() }
        return (h / d).toInt()
    }

    private fun StringBuilder.shot(out: File, name: String, s: Shape, content: @Composable () -> Unit) {
        val scene = ImageComposeScene((s.w * s.d).toInt(), (s.h * s.d).toInt(), Density(s.d)) {
            CompositionLocalProvider(LocalConfiguration provides Configuration(s.w, s.h)) {
                BmsTheme {
                    Row(Modifier.fillMaxSize().background(Hud.Bg)) {
                        if (s.rail) Box(Modifier.width(RAIL_DP.dp).fillMaxHeight().background(Hud.Surface))
                        Box(Modifier.weight(1f).fillMaxHeight()) { content() }
                    }
                }
            }
        }
        try {
            var t = 0L
            repeat(40) { scene.render(t); t += 50_000_000; Thread.sleep(30) }
            File(out, "$name.png").writeBytes(scene.render(t).encodeToData(EncodedImageFormat.PNG)!!.bytes)
            appendLine("     picture $name.png")
        } catch (e: Throwable) {
            appendLine("FAIL picture $name: ${e::class.simpleName}: ${e.message}")
        } finally {
            scene.close()
        }
    }
}
