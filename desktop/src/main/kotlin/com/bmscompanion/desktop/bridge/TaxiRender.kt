package com.bmscompanion.desktop.bridge

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.airfield.Airfield
import com.bmscompanion.app.ui.screens.TaxiLayout
import com.bmscompanion.app.ui.screens.TaxiLive
import com.bmscompanion.app.ui.screens.TaxiView
import com.bmscompanion.app.ui.screens.taxiCardsWidth
import com.bmscompanion.app.ui.screens.taxiChartHeight
import com.bmscompanion.app.ui.screens.taxiLayout
import com.bmscompanion.app.ui.theme.Hud
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/**
 * `--taxirender <out folder>` — the Taxi page drawn headless at the screen shapes pilots use, under the Mission
 * section's chrome (header, source bar, tab strip: [CHROME_DP]) and, from 600 dp, the navigation rail ([RAIL_DP]),
 * so the page gets the room it gets in the app. One airfield with the jet on a ramp spot (a clearance with steps)
 * and one carrier.
 *
 * The report says for each shape which layout the page chose and how much room the clearance and spot cards have:
 * the chart keeps every finger for itself, so the cards must always have a strip of their own (a pilot on a
 * 1280 x 800 tablet in landscape could not reach them at all up to 1.3.7). A shape where they get less than
 * [MIN_CARDS_DP] in their own direction is a FAIL.
 */
object TaxiRender {
    private const val CHROME_DP = 150
    private const val RAIL_DP = 80
    private const val PILLS_DP = 56
    private const val MIN_CARDS_DP = 150f

    private data class Shape(val name: String, val w: Int, val h: Int, val d: Float, val rail: Boolean = w >= 600, val chrome: Int = CHROME_DP)

    private val SHAPES = listOf(
        Shape("tablet-1280x800-landscape-mdpi", 1280, 800, 1f),
        Shape("tablet-1280x800px-landscape-1.33x", 960, 600, 1.333f),
        // 1280 x 800 px at 1.5x is 853 x 533 dp; Android takes the navigation bar off the side in landscape
        Shape("tablet-1280x800px-landscape-1.5x", 805, 509, 1.5f),
        Shape("tablet-1280x800px-landscape-2x", 592, 376, 2f, rail = false),
        Shape("tablet-800x1280-portrait", 800, 1256, 1f),
        Shape("phone-412x915-portrait", 412, 867, 2.625f),
        Shape("phone-915x412-landscape", 867, 388, 2.625f),
        // the PC window: no rail, its own header
        Shape("pc-1600x900", 1600, 900, 1f, rail = false, chrome = 130),
    )

    fun run(out: File): String = buildString {
        out.mkdirs()
        appendLine("Taxi page, headless: the layout and the cards' room at each shape")
        val index = runBlocking { Repo.index() }
        val field = findField(index.theaters, "Osan AB")
        val ship = findShip(index.theaters.mapNotNull { it.airfieldSet })
        if (field == null) { appendLine("FAIL: no Osan AB chart in the bundled data"); return@buildString }
        val route = field.routes.firstOrNull()
        val node = route?.parking?.getOrNull(5)?.let { route.nodes.getOrNull(it.k) }
        val jet = node?.let { Offset(it.e.toFloat(), it.n.toFloat()) }
        appendLine("field ${field.name}, jet on ${if (jet != null && route != null) "spot ${route.parking[5].n} of runway ${route.designator}" else "nothing"}; ship ${ship?.name ?: "none"}")
        appendLine()
        for (s in SHAPES) {
            // the room TaxiView's BoxWithConstraints gets: the pane less the page's padding (12 a side, 8 top and
            // bottom) and the pills row
            val paneW = s.w - (if (s.rail) RAIL_DP else 0)
            val paneH = s.h - s.chrome
            val boxW = paneW - 24f
            val boxH = paneH - 16f - PILLS_DP
            val layout = taxiLayout(boxW, boxH, false, s.w >= 840)
            val (chart, cards) = when (layout) {
                TaxiLayout.SIDE -> { val cw = taxiCardsWidth(boxW); "chart ${(boxW - cw - 12).toInt()} x ${boxH.toInt()}" to (cw to boxH) }
                TaxiLayout.STACK -> { val ch = taxiChartHeight(boxH); "chart ${boxW.toInt()} x ${ch.toInt()}" to (boxW to boxH - ch - 10) }
                TaxiLayout.DECK -> "deck" to (0f to 0f)
            }
            val ok = cards.first >= 240f && cards.second >= MIN_CARDS_DP
            appendLine("${if (ok) "ok  " else "FAIL"} ${s.name} (${s.w} x ${s.h} dp, page room ${boxW.toInt()} x ${boxH.toInt()}): " +
                "$layout, $chart, cards ${cards.first.toInt()} x ${cards.second.toInt()} dp scrolling on their own")
            shot(out, "taxi-${s.name}", s) { TaxiView(field, Modifier.fillMaxSize(), live = TaxiLive(you = jet, heading = 90.0, onGround = true)) }
            if (ship != null && (s.name.startsWith("phone-412") || s.name.contains("1.5x"))) {
                val deck = taxiLayout(boxW, boxH, true, s.w >= 840)
                appendLine("${if (deck == TaxiLayout.DECK) "ok  " else "FAIL"} ${s.name} carrier ${ship.name}: $deck")
                shot(out, "taxi-${s.name}-ship", s) { TaxiView(ship, Modifier.fillMaxSize()) }
            }
        }
        groundNumbers(out)
    }

    /**
     * BMS Ground's spot numbers after a landing (the research's worked example, read from the sim's code): landing on
     * Gunsan 36, Ground counts in runway 18's network, where the stand at e2028 n1549 is "04" and the one at e3928
     * n3737 is "28". Drawn once on the way in (the page's own pick of a spot is the first, 00).
     */
    private fun StringBuilder.groundNumbers(out: File) {
        val index = runBlocking { Repo.index() }
        val gunsan = findField(index.theaters, "Gunsan AB") ?: run { appendLine("FAIL: no Gunsan AB chart"); return }
        val net = com.bmscompanion.app.data.airfield.routeShown(gunsan, "36", outbound = false)
        appendLine()
        appendLine("Gunsan AB, landed on 36: Ground's numbers are runway ${net?.designator}'s network")
        for ((at, want) in listOf((2028.0 to 1549.0) to "04", (3928.0 to 3737.0) to "28")) {
            val got = net?.let { com.bmscompanion.app.data.airfield.TaxiNet(gunsan, it).nearestSpot(at.first, at.second) }
            val ok = got != null && got.second < 30 && got.first.label == want
            appendLine("${if (ok) "ok  " else "FAIL"} stand at e${at.first.toInt()} n${at.second.toInt()}: ${got?.first?.label ?: "none"} (BMS says $want)")
        }
        val s = SHAPES.first { it.name == "pc-1600x900" }
        shot(out, "taxi-gunsan-in-36", s) { TaxiView(gunsan, Modifier.fillMaxSize(), initialRunway = "36", initialOutbound = false) }
        shot(out, "taxi-gunsan-out-36", s) { TaxiView(gunsan, Modifier.fillMaxSize(), initialRunway = "36") }
    }

    /** The field by its airport name, in the first theater that charts it. */
    private fun findField(theaters: List<com.bmscompanion.app.data.Theater>, name: String): Airfield? {
        for (th in theaters) {
            val set = th.airfieldSet ?: continue
            val a = runBlocking { Repo.airportSet(th.airportSet).airports }.firstOrNull { it.name.equals(name, true) } ?: continue
            runBlocking { Repo.airfield(set, a.id) }?.let { return it }
        }
        return null
    }

    private fun findShip(sets: List<String>): Airfield? {
        for (set in sets) for (id in runBlocking { Repo.airfieldIndex(set) }.keys) {
            val f = runBlocking { Repo.airfield(set, id.toInt()) } ?: continue
            if (f.ship != null && f.ship!!.marks.isNotEmpty()) return f
        }
        return null
    }

    /** The page as the app shows it: chrome on top, a rail at the side from 600 dp, the Taxi page in the rest. */
    private fun StringBuilder.shot(out: File, name: String, s: Shape, content: @androidx.compose.runtime.Composable () -> Unit) {
        val scene = ImageComposeScene((s.w * s.d).toInt(), (s.h * s.d).toInt(), Density(s.d)) {
            CompositionLocalProvider(LocalConfiguration provides Configuration(s.w, s.h)) {
                Row(Modifier.fillMaxSize().background(Hud.Bg)) {
                    if (s.rail) Box(Modifier.width(RAIL_DP.dp).fillMaxHeight().background(Hud.Surface))
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        Box(Modifier.fillMaxWidth().height(s.chrome.dp).background(Hud.Surface.copy(alpha = 0.6f)))
                        Box(Modifier.weight(1f).fillMaxWidth()) { content() }
                    }
                }
            }
        }
        try {
            var t = 0L
            repeat(4) { scene.render(t); t += 50_000_000 }
            File(out, "$name.png").writeBytes(scene.render(t).encodeToData(EncodedImageFormat.PNG)!!.bytes)
        } catch (e: Exception) {
            appendLine("  FAIL $name: ${e::class.simpleName}: ${e.message}")
        } finally {
            scene.close()
        }
    }
}
