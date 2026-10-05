package com.bmscompanion.desktop.bridge

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.platform.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.bmscompanion.app.data.mission.Live
import com.bmscompanion.app.data.mission.MfdKey
import com.bmscompanion.app.data.mission.RttArea
import com.bmscompanion.app.data.mission.RttConfig
import com.bmscompanion.app.data.mission.RttFrame
import com.bmscompanion.app.data.mission.RttPhase
import com.bmscompanion.app.data.mission.RttState
import com.bmscompanion.app.ui.components.LocalRttSource
import com.bmscompanion.app.ui.screens.mission.MfdCardBody
import com.bmscompanion.app.ui.screens.mission.MfdPanel
import com.bmscompanion.app.ui.screens.mission.MfdPrefs
import com.bmscompanion.app.ui.screens.mission.MfdArrange
import com.bmscompanion.app.ui.screens.mission.LocalMfdRoom
import com.bmscompanion.app.ui.screens.mission.MFD_CAPTION
import com.bmscompanion.app.ui.screens.mission.MFD_CARD_CHROME
import com.bmscompanion.app.ui.screens.mission.MFD_GAP
import com.bmscompanion.app.ui.screens.mission.mfdFit
import com.bmscompanion.app.ui.screens.mission.mfdFullFit
import com.bmscompanion.app.ui.screens.mission.MFD_FULL_PAD
import com.bmscompanion.app.ui.screens.mission.glassNote
import com.bmscompanion.app.ui.theme.BmsTheme
import com.bmscompanion.app.ui.theme.Hud
import com.bmscompanion.app.ui.theme.HudSkin
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import java.io.File

/**
 * `--mfdrender <folder>` — the MFDs card drawn headless in every state the glass explains, at a phone's, a tablet's
 * and a PC's width, plus the kneeboard's printed bezel.
 *
 * The states are handed to the card as data ([MfdCardBody]), the way the PC would answer them, so nothing here needs
 * Falcon BMS or a link. The live state gets pictures of its own through [LocalRttSource]: the same radar page and pod
 * video `--mfdbench` publishes, so the drawing and the cost are checked against the same thing.
 */
object MfdRender {
    private val left = listOf(
        MfdKey("CRM"), MfdKey("RWS"), MfdKey("NORM"), MfdKey("OVRD"), MfdKey("CNTL"),
        MfdKey("", ""), MfdKey("FZ"), MfdKey("SP"), MfdKey("STP"), MfdKey("", ""),
        MfdKey("DCLT"), MfdKey("SMS"), MfdKey("TGP"), MfdKey("FCR", inverted = true), MfdKey("SWAP"),
        MfdKey("4B"), MfdKey("C"), MfdKey("40"), MfdKey("A"), MfdKey("", ""),
    )
    private val right = listOf(
        MfdKey("STBY"), MfdKey("A-G"), MfdKey("WIDE", inverted = true), MfdKey("OVRD"), MfdKey("CNTL"),
        MfdKey("TV"), MfdKey("FLIR", "WHOT"), MfdKey("", ""), MfdKey("LSR", "ARM"), MfdKey("CZ"),
        MfdKey("DCLT"), MfdKey("SMS"), MfdKey("TGP", inverted = true), MfdKey("FCR"), MfdKey("SWAP"),
        MfdKey("", ""), MfdKey("", ""), MfdKey("SP"), MfdKey("TMS"), MfdKey("FOV"),
    )
    private val live = Live(flying = true, mfdLeft = left, mfdRight = right)
    private val mfds = listOf(RttArea("mfdleft", "Left MFD", 600, 600), RttArea("mfdright", "Right MFD", 600, 600))

    private fun rtt(phase: String, config: RttConfig? = null, available: Boolean = false, running: Boolean = true, cockpit: Boolean = true) =
        RttState(available = available, phase = phase, bmsRunning = running, inCockpit = cockpit, config = config, areas = if (available) mfds else emptyList())

    private class Case(val name: String, val l: Live?, val linked: Boolean, val live: Boolean, val rtt: RttState?)

    private val cases = listOf(
        Case("unlinked", null, false, true, null),
        Case("nobms", null, true, true, rtt(RttPhase.NO_BMS, running = false, cockpit = false)),
        Case("no3d", null, true, true, rtt(RttPhase.NO_3D, cockpit = false)),
        Case("exportoff-launcher", live, true, true, rtt(RttPhase.EXPORT_OFF, RttConfig(on = false, launcher = true, fps = 30))),
        Case("exportoff-switch", live, true, true, rtt(RttPhase.EXPORT_OFF, RttConfig(on = false, launcher = false, fps = 30))),
        Case("restart", live, true, true, rtt(RttPhase.RESTART, RttConfig(on = true, launcher = true))),
        Case("dark", live.copy(mfdLeft = emptyList(), mfdRight = emptyList()), true, true, rtt(RttPhase.DARK, available = true)),
        Case("live", live, true, true, rtt(RttPhase.LIVE, RttConfig(on = true, launcher = true), available = true)),
        Case("liveoff", live, true, false, null),
    )

    /** The bench's pictures as frames, one new one each time a view asks. */
    private fun source(black: Boolean): suspend (String, Int, Long) -> RttFrame? {
        var n = 0L
        return { display, _, _ ->
            asked++
            val img = when {
                black -> java.awt.image.BufferedImage(600, 600, java.awt.image.BufferedImage.TYPE_INT_RGB)   // an unpowered display
                display == "mfdleft" -> MfdBench.radarPage(3, 600)
                else -> MfdBench.podVideo(3, 600)
            }
            val w = img.width
            val bgra = ByteArray(w * img.height * 4)
            val px = IntArray(w)
            for (y in 0 until img.height) {
                img.getRGB(0, y, w, 1, px, 0, w)
                for (x in 0 until w) {
                    val p = px[x]; val o = (y * w + x) * 4
                    bgra[o] = (p and 0xFF).toByte(); bgra[o + 1] = (p shr 8 and 0xFF).toByte(); bgra[o + 2] = (p shr 16 and 0xFF).toByte(); bgra[o + 3] = -1
                }
            }
            val sk = Image.makeRaster(ImageInfo(w, img.height, ColorType.BGRA_8888, ColorAlphaType.OPAQUE), bgra, w * 4)
            RttFrame(++n, android.graphics.Bitmap(sk.toComposeImageBitmap()))
        }
    }

    fun run(out: File): String = buildString {
        out.mkdirs()
        outDir = out
        val wasLive = MfdPrefs.live
        val widths = listOf(Triple("phone", 390, 2.5f), Triple("tablet", 820, 2f), Triple("pc", 1280, 1f))
        try {
            for (c in cases) for ((wName, wDp, density) in widths) {
                val hDp = when (wName) { "phone" -> 1060; "tablet" -> 700; else -> 640 }
                MfdPrefs.live = c.live
                shot("mfd-${c.name}-$wName", wDp, hDp, density, black = c.name == "dark") {
                    MfdCardBody(c.l, c.linked, c.live, c.rtt, onSetup = {})
                }
            }
            // The card on a dashboard page, sized to the room the page gives it (LocalMfdRoom): each shot is the page
            // area below the dashboard's header at a device's size, and the bezels must take the most of it.
            val liveCase = cases.first { it.name == "live" }
            MfdPrefs.live = true
            val arrangeWas = MfdPrefs.arrange
            try {
                val rooms = listOf(
                    listOf("tablet-landscape", 1138, 560, 2.25f), listOf("tablet-portrait", 711, 980, 2.25f),
                    listOf("phone", 412, 700, 2.625f), listOf("phone-fullscreen", 412, 860, 2.625f), listOf("pc", 1600, 780, 1f),
                )
                for (r in rooms) {
                    val (nm, w, h, dens) = r
                    w as Int; h as Int; dens as Float
                    for (a in if (nm == "tablet-portrait" || nm == "phone") MfdArrange.entries else listOf(MfdArrange.AUTO)) {
                        MfdPrefs.arrange = a
                        // the card's own width: the shot's 12 dp padding and the card's 16 dp, each side
                        val fit = mfdFit((w - 56).dp, (h - 24).dp - MFD_CARD_CHROME, a, w >= 600)
                        val each = if (fit.stacked) fit.side * 2 + MFD_GAP + MFD_CAPTION * 2 else fit.side + MFD_CAPTION
                        appendLine("     $nm ${w}x$h ${a.name.lowercase()}: ${if (fit.stacked) "stacked" else "side by side"}, squares of ${fit.side.value.toInt()} dp, bezels ${each.value.toInt()} dp tall in ${h - 24 - MFD_CARD_CHROME.value.toInt()} dp")
                        shot("mfd-room-live-$nm-${a.name.lowercase()}", w, h, dens) {
                            CompositionLocalProvider(LocalMfdRoom provides (h - 24).dp) {
                                MfdCardBody(liveCase.l, true, true, liveCase.rtt, onSetup = {})
                            }
                        }
                    }
                }
                // The full page (MfdFull) against the dashboard card it replaces, at the sizes pilots run it at. The
                // card's figure is the page's own arithmetic with the chrome measured on a tablet (Mission header,
                // source line and tab strip 136 dp; the dashboard's header 80 dp, 128 on a phone where the page
                // buttons take a line; the rail 81 dp, or a phone's 80 dp bottom bar); the full page's is the box
                // the window gives it, which the shot draws.
                MfdPrefs.arrange = MfdArrange.AUTO
                val pages = listOf(
                    listOf("tablet-landscape", 1138, 711, 2.25f), listOf("tablet-portrait", 711, 1138, 2.25f),
                    listOf("phone", 412, 915, 2.625f), listOf("pc-1600x900", 1600, 900, 1f), listOf("pc-1920x1080", 1920, 1080, 1f),
                )
                for (p in pages) {
                    val (nm, w, h, dens) = p
                    w as Int; h as Int; dens as Float
                    val phone = w < 600
                    val paneW = w - (if (phone) 0 else 81)
                    val paneH = h - 136 - (if (phone) 80 else 0)
                    val room = (paneH - (if (phone) 128 else 80) - 16).dp
                    val before = mfdFit((paneW - 24 - 32).dp, room - MFD_CARD_CHROME, MfdArrange.AUTO, !phone)
                    val inner = (w.dp - MFD_FULL_PAD * 2)
                    val after = mfdFullFit(inner, h.dp - MFD_FULL_PAD * 2, MfdArrange.AUTO, !phone)
                    appendLine(
                        "     full page $nm ${w}x$h: card ${before.side.value.toInt()} dp ${if (before.stacked) "stacked" else "side by side"}" +
                            " -> full page ${after.fit.side.value.toInt()} dp ${if (after.fit.stacked) "stacked" else "side by side"}," +
                            " strip ${if (after.stripBeside) "beside" else "above"}",
                    )
                    shot("mfd-full-live-$nm", w, h, dens, pad = 0) {
                        MfdCardBody(liveCase.l, true, true, liveCase.rtt, onSetup = {}, full = true, onExit = {})
                    }
                }
            } finally {
                MfdPrefs.arrange = arrangeWas
            }
            // the kneeboard's printed bezel: legends, and a note, as a VR board draws them
            HudSkin.board = true
            try {
                shot("mfd-board-legends", 420, 470, 2f) {
                    MfdPanel(left, "LEFT MFD", Modifier.padding(8.dp))
                }
                shot("mfd-board-note", 420, 470, 2f) {
                    MfdPanel(emptyList(), "LEFT MFD", Modifier.padding(8.dp), note = glassNote(true, rtt(RttPhase.NO_3D, cockpit = false)))
                }
            } finally {
                HudSkin.board = false
            }
        } finally {
            MfdPrefs.live = wasLive
        }
    }

    private fun StringBuilder.shot(name: String, wDp: Int, hDp: Int, density: Float, black: Boolean = false, pad: Int = 12, content: @androidx.compose.runtime.Composable () -> Unit) {
        val src = source(black)
        asked = 0
        val scene = ImageComposeScene((wDp * density).toInt(), (hDp * density).toInt(), Density(density)) {
            CompositionLocalProvider(LocalConfiguration provides Configuration(wDp, hDp), LocalRttSource provides src) {
                BmsTheme {
                    Box(Modifier.fillMaxSize().background(Hud.Bg).padding(pad.dp)) { content() }
                }
            }
        }
        try {
            var t = 0L
            repeat(25) { scene.render(t); t += 50_000_000; Thread.sleep(30) }   // the live picture arrives asynchronously
            // How many pictures the card asked for is the proof of the Live switch: none at all when it is off (and
            // none in a state with no picture to show), some when it is on.
            val wanted = name.contains("-live-") || name.contains("-dark-")
            val verdict = if ((asked > 0) == wanted) "ok  " else "FAIL"
            appendLine("$verdict $name (${wDp} dp at $density), $asked pictures asked for")
            File(outDir, "$name.png").writeBytes(scene.render(t).encodeToData(EncodedImageFormat.PNG)!!.bytes)
        } catch (e: Exception) {
            appendLine("FAIL $name: ${e::class.simpleName}: ${e.message}")
        } finally {
            scene.close()
        }
    }

    private lateinit var outDir: File

    /** Pictures asked of [source] during one shot. */
    @Volatile private var asked = 0
}
