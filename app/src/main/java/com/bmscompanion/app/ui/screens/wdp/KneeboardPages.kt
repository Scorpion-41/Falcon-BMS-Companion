package com.bmscompanion.app.ui.screens.wdp

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bmscompanion.app.data.Platform
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.mission.KbKind
import com.bmscompanion.app.data.wdp.WdpForm
import kotlinx.coroutines.delay
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The pages Upd Kneeboard puts in the cockpit (R3-PLAN A15-A17), and how one is drawn and captured.
 *
 * **One page is one knee's half of one of BMS's page files.** The device running the Planner draws it here, at
 * [PAGE_W] x [PAGE_H] pixels (2:3, the shape the pad shows a half at), captures it and sends it to the PC as a PNG; the
 * PC stretches it into the file's 1024 x 2048 half and writes the file in the format it already has
 * (`desktop/.../bridge/KneeboardPrint.kt`). Drawing on the device is what makes the page the one the pilot sees: what
 * was typed on the card, the pictures the pages drew, all live on the device.
 *
 * **A page is laid out in WDP's own units.** Every page is 450 x 675 units — one half of the DataCard's 900 x 675 panel —
 * under a density that makes that exactly [PAGE_W] x [PAGE_H] pixels, whatever the device's own density: one unit is one
 * of WDP's pixels, a 1.dp line is the 1 px line WDP drew, and the page is the same picture from a phone, a browser or
 * the PC. Type is laid out at the page's size, never an enlarged screenshot.
 *
 * **It is captured on screen.** [KneeboardPageHost] lays the page out at full size, shows it scaled down in the Upd Kneeboard
 * window's preview, and records it into a graphics layer as it draws; [capturePng] turns that layer into a picture.
 * Compose on Android, the PC and the browser all capture a layer that way (the U10 spike in the round-3 notes).
 *
 * The kinds offered here are Leave as is, Picture (a file on the BMS PC, [KneeboardPictures]), Blank, Test page, DataCard
 * left/right and Coordination Card left/right; the
 * others (briefing, weather, target list, route map, attack profile, ground charts) come from [KneeboardExtraPages].
 */
object KneeboardPages {
    /** The page as drawn and sent: 1024 x 1536 pixels, 2:3. The PC holds the stretch into the 1:2 half (PAGE_ASPECT). */
    const val PAGE_W = 1024
    const val PAGE_H = 1536

    /** The page's layout units: half of WDP's 900 x 675 card, so one unit is one of WDP's pixels. */
    const val UNITS_W = 450
    const val UNITS_H = 675

    /** The density a page is laid out under: [UNITS_W] dp across is [PAGE_W] pixels. */
    val density: Density = Density(PAGE_W.toFloat() / UNITS_W, 1f)

    /** The DataCard page's form, which holds the card (`pnlPage_1`) and the Coordination Card (`pnlPage_2`). */
    const val CARD_FORM = "cntDataCard"

    val LEAVE = KbPageKind(KbKind.LEAVE, "Leave as it is", "As it is", draw = null)

    private val BASE: List<KbPageKind> = listOf(
        LEAVE,
        // WDP's "Selected Picture", second in its list: choosing it opens Browse picture… for that half
        KbPageKind(KbKind.PICTURE, "Picture… (Browse picture)", "Picture") { PicturePage(it) },
        KbPageKind(KbKind.BLANK, "Blank page", "Blank") { BlankPage(it) },
        KbPageKind(KbKind.TEST, "Test page (grid and circle)", "Test page") { TestPage(it) },
        KbPageKind(KbKind.DATACARD_LEFT, "DataCard, left half", "DataCard L") { CardHalf("pnlPage_1", WdpPage.DATACARD, left = true) },
        KbPageKind(KbKind.DATACARD_RIGHT, "DataCard, right half", "DataCard R") { CardHalf("pnlPage_1", WdpPage.DATACARD, left = false) },
        KbPageKind(KbKind.COORDINATION_LEFT, "Coordination Card, left half", "Coord. L") { CardHalf("pnlPage_2", WdpPage.COORDINATION, left = true) },
        KbPageKind(KbKind.COORDINATION_RIGHT, "Coordination Card, right half", "Coord. R") { CardHalf("pnlPage_2", WdpPage.COORDINATION, left = false) },
    )

    /** Every kind the window offers, in its order: this file's, then [KneeboardExtraPages]'s. */
    val kinds: List<KbPageKind> get() = BASE + KneeboardExtraPages.kinds.filter { k -> BASE.none { it.id == k.id } }

    fun of(id: String?): KbPageKind? = kinds.firstOrNull { it.id == id }

    /** The words for a kind, also for one this copy of the app does not draw (a newer PC's tag). */
    fun label(id: String?): String = of(id)?.label ?: id ?: LEAVE.label

    /** What a page file is called on the PC, for the answers before the PC has named it. */
    fun fileOf(n: Int) = "${7981 + n}.dds"

    /** "left knee" / "right knee" */
    fun knee(side: Char) = if (side == 'L') "left knee" else "right knee"

    /**
     * Makes sure what [kind] draws is loaded before it is captured: the card's grid and its layout are read from the
     * app's data once, then kept, so the first capture is not taken while the grid is still on its way.
     */
    suspend fun preload(kind: String) {
        // the other page kinds load their own data (maps, airfields, forms): asked directly, not left to warm()
        if (kind in KneeboardExtraPages.ids) { KneeboardExtraPages.preload(kind); return }
        if (kind !in CARD_KINDS) return
        Repo.wdpForm(CARD_FORM)
        val panel = if (kind == KbKind.DATACARD_LEFT || kind == KbKind.DATACARD_RIGHT) "pnlPage_1" else "pnlPage_2"
        listOf("png", "jpg", "bmp", "gif").firstNotNullOfOrNull { ext -> Repo.bitmap("data/wdp/img/$CARD_FORM.$panel.BackgroundImage.$ext") }
    }

    private val CARD_KINDS = setOf(KbKind.DATACARD_LEFT, KbKind.DATACARD_RIGHT, KbKind.COORDINATION_LEFT, KbKind.COORDINATION_RIGHT)

    /**
     * Waits until a page just put in [KneeboardPageHost] has been drawn with everything it loads: a few frames, a pause
     * for pictures that come in the frame after they load, and a few more frames.
     */
    suspend fun settle() {
        repeat(3) { withFrameNanos { } }
        delay(250)
        repeat(3) { withFrameNanos { } }
    }

    /** The page each [KneeboardPageHost] with a layer last composed, by its layer, as [drawnKey] names it. */
    private val drawn = HashMap<GraphicsLayer, String>()

    internal fun composed(layer: GraphicsLayer, key: String?) { if (key == null) drawn.remove(layer) else drawn[layer] = key }

    fun drawnKey(kind: String, n: Int, side: Char) = "$n$side:$kind"

    /**
     * Waits (a few seconds at most) until the page host has composed page [n] [side] of [kind]: a heavy page can take
     * longer than [settle] to be composed, and a capture before that took the page drawn before it (seen once: the
     * route map sent as the departure chart; the left half of a card sent as its right half too). Then three more
     * frames, which draw it. False when it never came: the caller sends nothing rather than the wrong page.
     */
    suspend fun awaitDrawn(layer: GraphicsLayer, kind: String, n: Int, side: Char): Boolean {
        val key = drawnKey(kind, n, side)
        repeat(160) {
            if (drawn[layer] == key) { repeat(3) { withFrameNanos { } }; return true }
            // the page to print was set from this coroutine: make sure the composition hears of it
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
            withFrameNanos { }
            delay(25)
        }
        return false
    }

    /**
     * The page recorded in [layer] as a PNG, or null with no encoder on this platform or when the capture failed. The
     * layer must have been drawn by a [KneeboardPageHost] since its page was last changed ([settle]).
     */
    suspend fun capturePng(layer: GraphicsLayer): ByteArray? {
        lastError = null
        val encode = Platform.encodePng ?: return null.also { lastError = "this device has no picture encoder" }
        val img = try { layer.toImageBitmap() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            lastError = "the page could not be captured (${e::class.simpleName}: ${e.message})"
            return null
        }
        if (img.width <= 0 || img.height <= 0) return null.also { lastError = "the page was captured empty (${img.width} x ${img.height})" }
        return encode(img) ?: null.also { lastError = "the page could not be encoded as PNG" }
    }

    /** Why the last [capturePng] gave nothing, or null. */
    var lastError: String? = null
        private set

    /** A PNG as the base64 text a `KbHalf` carries. */
    @OptIn(ExperimentalEncodingApi::class)
    fun base64(png: ByteArray): String = Base64.Default.encode(png)
}

/**
 * One kind of page: [id] is its `KbKind`, [label] the words in the picker, [short] the words in a cell. [draw] draws it
 * inside a 450 x 675 unit page ([KneeboardPages]); null for Leave as it is, which is never drawn or sent.
 */
class KbPageKind(
    val id: String,
    val label: String,
    val short: String,
    val draw: (@Composable (KbPageScope) -> Unit)?,
)

/** What a page is drawn for: page [n] (1-16) of [side] 'L' or 'R'. */
class KbPageScope(val n: Int, val side: Char)

/**
 * A page at its full size, [KneeboardPages.PAGE_W] x [KneeboardPages.PAGE_H] pixels, shown scaled to fit the box this
 * is given (centred, keeping its shape), and recorded into [layer] as it draws, for [KneeboardPages.capturePng]. The
 * page is white paper under whatever it draws.
 */
@Composable
fun KneeboardPageHost(kind: KbPageKind, n: Int, side: Char, modifier: Modifier = Modifier, layer: GraphicsLayer? = null) {
    if (layer != null) androidx.compose.runtime.DisposableEffect(layer) { onDispose { KneeboardPages.composed(layer, null) } }
    // a new host for every page: the same host handed the next half of the same card (2L, then 2R) kept composing the
    // one before inside its BoxWithConstraints, and the Print window then sent the left half twice
    androidx.compose.runtime.key(kind.id, n, side) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val w = KneeboardPages.PAGE_W
        val h = KneeboardPages.PAGE_H
        val bw = constraints.maxWidth.toFloat()
        val bh = if (constraints.hasBoundedHeight) constraints.maxHeight.toFloat() else bw * h / w
        val s = min(bw / w, bh / h).coerceAtLeast(0.01f)
        val shownW = (w * s).roundToInt().coerceAtLeast(1)
        val shownH = (h * s).roundToInt().coerceAtLeast(1)
        Box(
            Modifier.layout { measurable, _ ->
                // laid out at the page's own size, then drawn scaled down: the layout, the type and the capture are
                // all at full size, only the picture on screen is small
                val pl = measurable.measure(Constraints.fixed(w, h))
                layout(shownW, shownH) {
                    pl.placeWithLayer(0, 0) { scaleX = s; scaleY = s; transformOrigin = TransformOrigin(0f, 0f) }
                }
            },
        ) {
            CompositionLocalProvider(LocalDensity provides KneeboardPages.density) {
                Box(
                    Modifier.fillMaxSize()
                        .then(
                            if (layer == null) Modifier else Modifier.drawWithContent {
                                layer.record { this@drawWithContent.drawContent() }
                                drawLayer(layer)
                            },
                        )
                        .background(Color.White),
                ) {
                    // the page this host now holds, once its composition is applied (the frames after draw it)
                    if (layer != null) androidx.compose.runtime.SideEffect { KneeboardPages.composed(layer, KneeboardPages.drawnKey(kind.id, n, side)) }
                    kind.draw?.invoke(KbPageScope(n, side))
                }
            }
        }
    }
    }
}

// ---------------------------------------------------------------- the pages

private val PAPER_INK = Color(0xFF111111)
private val PAPER_FAINT = Color(0xFF8A8A8A)
private val PAPER_RULE = Color(0xFFCFCFCF)

/** The page's own words at its foot: which page and knee, and who made it. */
@Composable
private fun PageFoot(scope: KbPageScope, modifier: Modifier = Modifier) {
    Text(
        "Page ${scope.n} · ${KneeboardPages.knee(scope.side)} · BMS Companion",
        modifier, color = PAPER_FAINT, fontSize = 9.sp, textAlign = TextAlign.Center,
    )
}

@Composable
private fun BlankPage(scope: KbPageScope) {
    Box(Modifier.fillMaxSize().padding(8.dp)) {
        Text(
            "INTENTIONALLY LEFT BLANK", Modifier.align(Alignment.Center),
            color = PAPER_FAINT, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp,
        )
        PageFoot(scope, Modifier.align(Alignment.BottomCenter).fillMaxWidth())
    }
}

/**
 * A page to check the geometry with (R3-KNEEBOARD §10): a grid of squares, a circle, the four corners named, the page
 * and knee in large type. On the pad the squares are square and the circle round only if the page's shape is right.
 */
@Composable
private fun TestPage(scope: KbPageScope) {
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            val m = 3.5.dp.toPx()          // 8 px of white at the page's edge, for the lowest mip levels
            val step = 45.dp.toPx()
            var x = m
            while (x <= size.width - m + 0.5f) { drawLine(PAPER_RULE, Offset(x, m), Offset(x, size.height - m), 1.dp.toPx()); x += step }
            var y = m
            while (y <= size.height - m + 0.5f) { drawLine(PAPER_RULE, Offset(m, y), Offset(size.width - m, y), 1.dp.toPx()); y += step }
            drawRect(PAPER_INK, Offset(m, m), androidx.compose.ui.geometry.Size(size.width - 2 * m, size.height - 2 * m), style = Stroke(2.dp.toPx()))
            val c = Offset(size.width / 2f, size.height / 2f)
            drawCircle(Color(0xFFB00020), radius = 150.dp.toPx(), center = c, style = Stroke(3.dp.toPx()))
            drawLine(PAPER_INK, Offset(c.x - 160.dp.toPx(), c.y), Offset(c.x + 160.dp.toPx(), c.y), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
            drawLine(PAPER_INK, Offset(c.x, c.y - 160.dp.toPx()), Offset(c.x, c.y + 160.dp.toPx()), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
        }
        val corner = Modifier.padding(10.dp)
        Text("TOP LEFT", corner.align(Alignment.TopStart), color = PAPER_INK, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        Text("TOP RIGHT", corner.align(Alignment.TopEnd), color = PAPER_INK, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        Text("BOTTOM LEFT", corner.align(Alignment.BottomStart), color = PAPER_INK, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        Text("BOTTOM RIGHT", corner.align(Alignment.BottomEnd), color = PAPER_INK, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        Column(
            Modifier.align(Alignment.TopCenter).padding(top = 58.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("PAGE ${scope.n} ${scope.side}", color = PAPER_INK, fontSize = 40.sp, fontWeight = FontWeight.Bold)
            Text(KneeboardPages.knee(scope.side).uppercase(), color = PAPER_INK, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
        Column(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 50.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text("BMS Companion test page", color = PAPER_INK, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text("The grid is square and the circle round when the page is the right shape.", color = PAPER_FAINT, fontSize = 9.sp, textAlign = TextAlign.Center)
            Text("Squares of 45 × 45 units: 10 across, 15 down.", color = PAPER_FAINT, fontSize = 9.sp)
        }
    }
}

/**
 * One half of the DataCard (`pnlPage_1`) or the Coordination Card (`pnlPage_2`), as the pilot has it on the Planner's
 * page: the same values, the same pictures, drawn as paper ([WdpPanelView]). The card is 900 x 675, so a half is
 * exactly one page.
 */
@Composable
private fun CardHalf(panel: String, page: WdpPage, left: Boolean) {
    val form by produceState<WdpForm?>(null) { value = Repo.wdpForm(KneeboardPages.CARD_FORM) }
    val f = form ?: return
    val card = WdpSession.dataCard
    val values = card.values(page.hiddenHere())
    WdpPanelView(
        f, panel, values, Modifier.fillMaxSize(),
        crop = intArrayOf(if (left) 0 else 450, 0, 450, 675),
        // "Click picture to load new" speaks to the pilot at the screen, not on the knee
        hide = setOf("lblClickPic1"),
        content = card.controlContent(),
    )
}

/** The size a kind's name takes in a small preview, for the window. */
@Composable
internal fun KneeboardPagePlaceholder(text: String, modifier: Modifier = Modifier) {
    Box(modifier.background(Color(0xFFF4F4F4)), contentAlignment = Alignment.Center) {
        Row(Modifier.padding(6.dp)) { Text(text, color = PAPER_FAINT, fontSize = 11.sp, textAlign = TextAlign.Center) }
    }
}

