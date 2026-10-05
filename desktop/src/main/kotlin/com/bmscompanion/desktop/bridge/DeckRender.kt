package com.bmscompanion.desktop.bridge

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Density
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.airfield.AfDeckMark
import com.bmscompanion.app.data.airfield.Airfield
import com.bmscompanion.app.data.airfield.deckPosition
import com.bmscompanion.app.data.mission.Contact
import com.bmscompanion.app.data.mission.Live
import com.bmscompanion.app.ui.components.AirfieldChart
import com.bmscompanion.app.ui.components.ChartInks
import com.bmscompanion.app.ui.screens.TaxiLive
import com.bmscompanion.app.ui.screens.TaxiView
import com.bmscompanion.app.ui.theme.Hud
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * `--deckrender <out folder>` — every carrier deck the app charts, drawn headless by the real chart, as PNGs: the whole
 * deck by day and by night, three close-ups (the stern and landing area, the island, the bow), the Taxi page itself
 * at a phone's width, and a VR-board-sized page; with a report.
 *
 * The report is also the check on the jet's place on the deck. For each ship a jet is put on three of the ship's own
 * points — where BMS parks and launches — and the ship is sailed to an arbitrary position and heading; the jet's
 * position is turned into the world as the Tacview feed and shared memory would report it, and [deckPosition] has to
 * bring it back to the same deck point, to a foot, with its heading on the deck. Every ramp spot BMS has for the ship
 * is also tested against the drawn outline: a spot off the deck means the deck is in the wrong frame.
 *
 * One ship per drawn deck and name: the three Nimitz charts differ only in their number, so each is drawn once.
 */
object DeckRender {
    fun run(out: File): String = buildString {
        out.mkdirs()
        val index = runBlocking { Repo.index() }
        val seen = HashSet<String>()
        val ships = ArrayList<Airfield>()
        for (th in index.theaters) {
            val set = th.airfieldSet ?: continue
            val ids = runBlocking { Repo.airfieldIndex(set) }.keys
            for (id in ids) {
                val f = runBlocking { Repo.airfield(set, id.toInt()) } ?: continue
                val ship = f.ship ?: continue
                if (seen.add(ship.cls)) ships += f
            }
        }
        appendLine("Carrier decks, headless: ${ships.size} ships")
        appendLine()
        for (f in ships) {
            val ship = f.ship!!
            val slug = ship.cls.substringBefore(":").replace(", Nimitz class", "").replace(Regex(", .* class.*"), "").lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
            appendLine("${ship.cls}  [${f.name}]  ${ship.model}  ${ship.marks.size} marks: " +
                ship.marks.groupingBy { it.k }.eachCount().entries.joinToString(" ") { "${it.key}=${it.value}" })

            // the outline against BMS's own spots: all on the deck, or the deck is in the wrong frame
            val spots = f.routes.flatMap { r -> r.parking.mapNotNull { r.nodes.getOrNull(it.k) } }
            val off = spots.filter { !inside(ship.hull, it.e, it.n) }
            appendLine("  BMS ramp spots on the drawn deck: ${spots.size - off.size} of ${spots.size}" +
                if (off.isEmpty()) "" else "  off: " + off.joinToString(" ") { "(${it.e.toInt()},${it.n.toInt()})" })

            // the jet's place on the deck, through a ship sailing somewhere else at another heading
            val probes = (f.routes.flatMap { it.nodes }.filter { it.t == 2 }.take(2) + spots.take(1))
            for ((i, p) in probes.withIndex()) {
                val shipHdg = 37.0 + 101.0 * i
                val shipX = 812_345.0 - 20_000.0 * i
                val shipY = 402_100.0 + 7_000.0 * i
                val jetRel = 12.0 + 40.0 * i
                val h = shipHdg * PI / 180.0
                // the ship's frame back into the world: east and north of the ship
                val de = p.e * cos(h) + p.n * sin(h)
                val dn = -p.e * sin(h) + p.n * cos(h)
                val live = Live(x = shipX + dn, y = shipY + de, hdgTrue = (shipHdg + jetRel) % 360.0)
                val feed = listOf(
                    Contact(id = "s", kind = "ship", x = shipX, y = shipY, hdg = shipHdg, name = f.name),
                    Contact(id = "e", kind = "ship", x = shipX + 3000, y = shipY - 2500, hdg = shipHdg, name = "escort"),
                )
                val fix = deckPosition(f, live, feed)
                val err = fix?.let { hypot(it.e - p.e, it.n - p.n) }
                val herr = fix?.let { abs(((it.heading - jetRel) % 360.0 + 540.0) % 360.0 - 180.0) }
                appendLine("  jet on (${p.e.toInt()},${p.n.toInt()}) with the ship at ${shipHdg.toInt()}°: " +
                    if (fix == null) "FAIL, no fix" else "back at (${"%.1f".format(fix.e)},${"%.1f".format(fix.n)}) " +
                        "error ${"%.2f".format(err)} ft, heading on deck ${"%.1f".format(fix.heading)}° (error ${"%.2f".format(herr)}°) " +
                        if (err!! < 1.0 && herr!! < 0.1) "ok" else "FAIL")
            }
            // Under way: the feed is a second behind the jet, and the ship has sailed on at 25 kt in that second.
            // Without carrying the ship to the jet's moment the jet would come back 42 ft forward of its spot.
            probes.lastOrNull()?.let { p ->
                val shipHdg = 211.0
                val kts = 25.0
                val h = shipHdg * PI / 180.0
                val feedT = 1_000_000L
                val jetT = feedT + 1_000L
                val run = kts * 6076.12 / 3600.0 * (jetT - feedT) / 1000.0
                val nowX = 700_000.0 + run * cos(h)
                val nowY = 300_000.0 + run * sin(h)
                val de = p.e * cos(h) + p.n * sin(h)
                val dn = -p.e * sin(h) + p.n * cos(h)
                val live = Live(t = jetT, x = nowX + dn, y = nowY + de, hdgTrue = shipHdg)
                val feed = listOf(Contact(id = "s", kind = "ship", x = 700_000.0, y = 300_000.0, hdg = shipHdg, gsKts = kts, name = f.name))
                val fix = deckPosition(f, live, feed, feedT)
                val err = fix?.let { hypot(it.e - p.e, it.n - p.n) }
                appendLine("  under way at 25 kt, the feed a second behind: " + if (fix == null) "FAIL, no fix" else
                    "jet on (${p.e.toInt()},${p.n.toInt()}) back at (${"%.1f".format(fix.e)},${"%.1f".format(fix.n)}) error ${"%.2f".format(err)} ft " +
                        if (err!! < 1.0) "ok" else "FAIL")
            }
            val noFeed = deckPosition(f, Live(x = 812_345.0, y = 402_100.0, hdgTrue = 90.0), emptyList())
            appendLine("  with no Tacview feed: ${if (noFeed == null) "no jet on the deck (as meant)" else "FAIL: a position came back"}")

            // pictures
            val jet = probes.firstOrNull()?.let { Offset(it.e.toFloat(), it.n.toFloat()) }
            val island = ship.marks.firstOrNull { it.k == "island" }?.let(::middle)
            val stern = ship.hull.chunked(2).minByOrNull { it[1] }?.let { Offset(0f, it[1] + 170f) }
            val bow = ship.hull.chunked(2).maxByOrNull { it[1] }?.let { Offset(0f, it[1] - 150f) }
            for ((ink, inks) in listOf("day" to ChartInks.day, "night" to ChartInks.night)) {
                shot(out, "$slug-$ink-deck", 460, 1000, 1.5f) { AirfieldChart(f, null, inks, Modifier.fillMaxSize(), you = jet, youHeading = 12.0, interactive = false) }
                if (stern != null) shot(out, "$slug-$ink-stern", 460, 560, 1.5f) { AirfieldChart(f, null, inks, Modifier.fillMaxSize(), you = jet, youHeading = 12.0, interactive = false, follow = stern, followSpanFt = 380.0) }
                if (island != null) shot(out, "$slug-$ink-island", 460, 560, 1.5f) { AirfieldChart(f, null, inks, Modifier.fillMaxSize(), interactive = false, follow = island, followSpanFt = 260.0) }
                if (bow != null) shot(out, "$slug-$ink-bow", 460, 560, 1.5f) { AirfieldChart(f, null, inks, Modifier.fillMaxSize(), you = jet, youHeading = 12.0, interactive = false, follow = bow, followSpanFt = 380.0) }
            }
            // a kneeboard page: tall, turned to fit, printed small
            shot(out, "$slug-board", 360, 640, 1.25f) { AirfieldChart(f, null, ChartInks.night, Modifier.fillMaxSize(), you = jet, youHeading = 12.0, interactive = false, labelScale = 0.8f, fitRotation = true) }
            // the Taxi page as a phone shows it
            shot(out, "$slug-page", 400, 860, 2f) {
                Box(Modifier.fillMaxSize().background(Hud.Bg)) {
                    TaxiView(f, Modifier.fillMaxSize(), live = TaxiLive(you = jet, heading = 12.0, onGround = true))
                }
            }
            appendLine()
        }
    }

    private fun middle(m: AfDeckMark): Offset {
        val pts = m.p.chunked(2)
        return Offset(pts.map { it[0] }.average().toFloat(), pts.map { it[1] }.average().toFloat())
    }

    private fun inside(ring: List<Int>, e: Double, n: Double): Boolean {
        var c = false
        var j = ring.size - 2
        var i = 0
        while (i + 1 < ring.size) {
            val xi = ring[i].toDouble(); val yi = ring[i + 1].toDouble()
            val xj = ring[j].toDouble(); val yj = ring[j + 1].toDouble()
            if ((yi > n) != (yj > n) && e < (xj - xi) * (n - yi) / (yj - yi) + xi) c = !c
            j = i
            i += 2
        }
        return c
    }

    private fun StringBuilder.shot(out: File, name: String, wDp: Int, hDp: Int, density: Float, content: @androidx.compose.runtime.Composable () -> Unit) {
        val scene = ImageComposeScene((wDp * density).toInt(), (hDp * density).toInt(), Density(density)) {
            CompositionLocalProvider(LocalConfiguration provides Configuration(wDp, hDp)) { content() }
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
