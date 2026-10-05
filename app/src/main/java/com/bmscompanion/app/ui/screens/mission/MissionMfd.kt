package com.bmscompanion.app.ui.screens.mission

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.mission.LinkState
import com.bmscompanion.app.data.mission.Live
import com.bmscompanion.app.data.mission.MfdKey
import com.bmscompanion.app.data.mission.MfdRockerKey
import com.bmscompanion.app.data.mission.MissionLink
import com.bmscompanion.app.data.mission.RttPhase
import com.bmscompanion.app.data.mission.RttState
import com.bmscompanion.app.ui.components.RttView
import com.bmscompanion.app.ui.components.SectionCard
import com.bmscompanion.app.ui.components.isMedium
import com.bmscompanion.app.ui.components.rememberRttState
import com.bmscompanion.app.ui.theme.Hud
import com.bmscompanion.app.ui.theme.Mono
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/*
 * The two multi-function displays: a replica of the F-16's MFD bezel with its twenty option-select buttons and four
 * corner rockers, and inside it either Falcon BMS's own picture or the button legends on dark glass.
 *
 * What the real unit looks like, from the Dash-34 (TO 1F-16CMAM-34-1-1 §2.1.6), the display makers' brochures and
 * museum photographs: a near-square, dark grey matte bezel about 5¼ inches across round a 4 x 4 inch display; five
 * square push buttons along each edge, raised, with ribs between them so a gloved finger finds the gaps; a rocker
 * at each corner — GAIN top left, SYM top right, BRT bottom left, CON bottom right — pressed on its upper half to
 * increase and its lower half to decrease; the glass recessed in the bezel with an anti-glare finish. And the part
 * that matters most for drawing it: **the legends are not printed on the bezel.** The display draws them itself,
 * beside each button, in its own green — so when BMS's picture is on the glass the legends are already in it and
 * nothing is drawn over them, and when there is no picture they are drawn on the dark glass exactly where the
 * display would put them.
 */

/**
 * The pilot's two choices about the displays, remembered on this device.
 *
 * [live] is the performance switch: off, the card draws the bezels with their legends and reads no picture at all —
 * nothing is asked of the PC and nothing crosses the network. [fps] is how often a picture is asked for when on.
 */
object MfdPrefs {
    private const val LIVE_KEY = "mfd_live"
    private const val FPS_KEY = "mfd_fps"

    /** The rates offered. BMS's own export runs at up to 30 (`g_nRTTExport_FPS`), and never above half the sim's rate. */
    val rates = listOf(5, 10, 15, 30)

    private var liveI by mutableStateOf(Repo.getInt(LIVE_KEY, 1))
    private var fpsI by mutableStateOf(Repo.getInt(FPS_KEY, 10))

    var live: Boolean
        get() = liveI == 1
        set(v) { liveI = if (v) 1 else 0; Repo.putInt(LIVE_KEY, liveI) }

    var fps: Int
        get() = fpsI.takeIf { it in rates } ?: 10
        set(v) { fpsI = v; Repo.putInt(FPS_KEY, v) }

    private const val ARRANGE_KEY = "mfd_arrange"
    private var arrangeI by mutableStateOf(Repo.getInt(ARRANGE_KEY, 0))

    /** Side by side, one above the other, or whichever gives each display the larger square (the default). */
    var arrange: MfdArrange
        get() = MfdArrange.entries.getOrElse(arrangeI) { MfdArrange.AUTO }
        set(v) { arrangeI = v.ordinal; Repo.putInt(ARRANGE_KEY, v.ordinal) }
}

/** How the two MFDs sit on the card. */
enum class MfdArrange(val label: String) { AUTO("Auto"), SIDE("Side by side"), STACK("Stacked") }

/**
 * The MFDs as the whole page: the Mission header, the source line, the tab strip, the dashboard's own header, the
 * card's frame and its notes and the app's navigation rail all step aside, and the two bezels take the window.
 *
 * On a dashboard page the card gets what the chrome above it leaves, and on a tablet held sideways that chrome is a
 * third of the height — the bezels came out 375 dp where the screen has room for 555. One tap on the card's header
 * (the full-screen icon beside its chips) turns this on, the button in its corner turns it off, and so does Escape on the PC.
 *
 * Not remembered between launches, for the reason [DashFocus] is not: a page with no chrome is not a page anyone
 * should open the app to without having asked for it. It turns itself off when the page leaves the screen.
 */
object MfdFull {
    var on by mutableStateOf(false)
}

/**
 * The height a dashboard page gives a card on screen, below its own header: the MFDs card sizes its bezels to it, so
 * a page holding the MFDs is two displays as large as the window allows rather than two capped squares and a band of
 * nothing. Null anywhere else (a VR board, a check), where the old capped sizes stand.
 */
val LocalMfdRoom = compositionLocalOf<Dp?> { null }

/** The gap between the two bezels, and the name line under each. */
internal val MFD_GAP = 10.dp
internal val MFD_CAPTION = 22.dp

/** What the card spends around the bezels before the first line under them: padding, the title row and its spacer. */
internal val MFD_CARD_CHROME = 72.dp

/** One arrangement: whether the bezels are one above the other, and the side of each square. */
data class MfdFit(val stacked: Boolean, val side: Dp)

/**
 * The largest bezels that fit in [width] x [height] (each with its name line under it), side by side or stacked.
 *
 * [MfdArrange.AUTO] takes whichever gives each display the larger square: a landscape tablet side by side, a portrait
 * one stacked, a phone side by side until the page goes full screen. Without a [height] (nothing says how much of the
 * window the card has) the bezels are capped at [MFD_MAX] as before, side by side from a medium width.
 */
fun mfdFit(width: Dp, height: Dp?, arrange: MfdArrange, medium: Boolean, caption: Dp = MFD_CAPTION): MfdFit {
    if (height == null) {
        val stacked = when (arrange) { MfdArrange.SIDE -> false; MfdArrange.STACK -> true; MfdArrange.AUTO -> !medium }
        val s = if (stacked) width else (width - MFD_GAP) / 2
        return MfdFit(stacked, s.coerceAtMost(MFD_MAX).coerceAtLeast(MFD_MIN))
    }
    val side = min((width - MFD_GAP) / 2, height - caption)
    val stack = min(width, (height - MFD_GAP) / 2 - caption)
    val stacked = when (arrange) { MfdArrange.SIDE -> false; MfdArrange.STACK -> true; MfdArrange.AUTO -> stack > side }
    return MfdFit(stacked, (if (stacked) stack else side).coerceAtLeast(MFD_MIN))
}

/** The smallest bezel drawn: below this the buttons are not worth having. */
internal val MFD_MIN = 120.dp

// ---------------------------------------------------------------- what the glass says when there is no picture

/** What a note on the glass offers to do. */
enum class GlassAction { SETUP, SWITCH_ON }

/** A note drawn on the glass in the display's own green, in place of a picture that cannot be had. */
data class GlassNote(val title: String, val body: String, val actions: List<GlassAction> = emptyList())

/**
 * The one thing the glass should say right now, or null when there is a picture to show (or nothing to say yet).
 *
 * One state at a time, in the order a pilot meets them: a device that cannot reach the PC, a PC without BMS
 * running, BMS in the menus, then the export itself. The PC works the state out ([RttState.phase]); this only
 * words it. A PC older than this app has no phase, and its own reason is shown instead.
 */
fun glassNote(linked: Boolean, rtt: RttState?, canSwitch: Boolean = true): GlassNote? {
    if (!linked) return GlassNote(
        "NOT LINKED",
        "This device is not linked to the PC that runs Falcon BMS. Link it in Setup.",
        listOf(GlassAction.SETUP),
    )
    if (rtt == null) return null
    return when (rtt.phase) {
        null -> if (rtt.available) null else GlassNote("NO PICTURE", rtt.reason ?: "Falcon BMS is not exporting its displays.", listOf(GlassAction.SETUP))
        RttPhase.LIVE -> null
        RttPhase.NO_BMS -> GlassNote("START FALCON BMS", "The displays appear here once you are in the cockpit.")
        RttPhase.NO_3D -> GlassNote("ENTER THE COCKPIT", "Falcon BMS is running. Enter 3D and the displays appear here.")
        RttPhase.EXPORT_OFF -> {
            val cfg = rtt.config
            // The Launcher writes its own Export RTT Textures line at the foot of the config every launch, and the
            // last line wins: when it has one, it is the only switch that counts, and a line written above it by
            // this program would change nothing. The one-tap switch is offered only where it would work.
            if (cfg != null && !cfg.launcher && canSwitch) GlassNote(
                "DISPLAY EXPORT OFF",
                "Falcon BMS is not copying its displays out. Switch it on here, then restart BMS once.",
                listOf(GlassAction.SWITCH_ON, GlassAction.SETUP),
            ) else GlassNote(
                "DISPLAY EXPORT OFF",
                "In the Falcon BMS Launcher set Export RTT Textures to Enable, then launch BMS again.",
                listOf(GlassAction.SETUP),
            )
        }
        RttPhase.RESTART -> GlassNote("RESTART FALCON BMS", "The export is on now, but this BMS was started before it was. Quit and launch again.")
        RttPhase.DARK -> GlassNote("DISPLAYS DARK", "The MFDs are not powered yet: they come on with the MFD switch on the AVIONICS POWER panel.")
        RttPhase.NO_MFDS -> GlassNote("NO MFD PICTURE", "This aircraft publishes no MFD pictures in Falcon BMS's export.")
        RttPhase.PITCH -> GlassNote("CANNOT READ THE PICTURE", rtt.reason ?: "The export's layout is not one this version knows.")
        else -> GlassNote("NO PICTURE", rtt.reason ?: "Falcon BMS is not exporting its displays.", listOf(GlassAction.SETUP))
    }
}

// ---------------------------------------------------------------- the bezel

/**
 * How wide a bezel is ever drawn.
 *
 * A real MFD is about five inches across and this is a replica of one, not a poster of one: past this the square
 * makes the whole panel taller than a phone or a dashboard column, and the top and bottom rows of buttons stop being
 * reachable at the same time.
 */
internal val MFD_MAX = 430.dp

/**
 * Where everything on the bezel is, as fractions of its width. One place, so the drawing and the hit testing
 * cannot disagree about where a button is.
 */
private object Bz {
    const val GLASS = 0.70f            // the 4-inch glass in a 5¼-inch bezel is 0.76; a little less leaves room to tap
    const val MARGIN = (1f - GLASS) / 2
    const val LANE = 0.078f            // the buttons' centre line, from the outer edge
    const val CAP_LONG = 0.084f        // a cap along the edge it sits on
    const val CAP_SHORT = 0.066f       // and across it
    const val CAP_SIDE = 0.074f        // a side cap along its edge: the side buttons are closer together
    const val RIB_LONG = 0.050f
    const val RIB_SHORT = 0.013f
    const val ROCKER_W = 0.046f
    const val ROCKER_H = 0.098f
    const val FACE_INSET = 0.012f

    /** The centre of a top or bottom OSB along its edge, as a fraction of the glass from its start. */
    fun along(i: Int) = 0.1f + 0.2f * i

    /**
     * The same for a side OSB. The side buttons sit closer together, nearer the middle, as on the real bezel: the
     * corner legends of a side and of the top or bottom row would otherwise be drawn on top of each other.
     */
    fun alongSide(i: Int) = 0.16f + 0.17f * i

    /** OSB 1-5 run left to right along the top, 6-10 down the right, 11-15 right to left along the bottom, 16-20 up the left. */
    fun osbCentre(n: Int, s: Float): Offset {
        val g0 = MARGIN * s
        val g = GLASS * s
        val i = (n - 1) % 5
        return when ((n - 1) / 5) {
            0 -> Offset(g0 + g * along(i), LANE * s)
            1 -> Offset(s - LANE * s, g0 + g * alongSide(i))
            2 -> Offset(g0 + g * along(4 - i), s - LANE * s)
            else -> Offset(LANE * s, g0 + g * alongSide(4 - i))
        }
    }

    /** Whether OSB [n] lies along the top or bottom (a cap wider than tall) rather than a side. */
    fun horizontal(n: Int) = (n - 1) / 5 % 2 == 0

    /** The four rockers, clockwise from the top left, with what the real bezel prints beside them. */
    val ROCKERS = listOf("GAIN", "SYM", "CON", "BRT")

    fun rockerCentre(k: Int, s: Float): Offset {
        val x = 0.052f * s
        val y = 0.086f * s
        return when (k) {
            0 -> Offset(x, y)
            1 -> Offset(s - x, y)
            2 -> Offset(s - x, s - y)
            else -> Offset(x, s - y)
        }
    }
}

/** A control on the bezel: an OSB (1-20) or one half of a rocker. */
private sealed interface Ctl {
    data class Osb(val n: Int) : Ctl
    data class Rocker(val k: Int, val up: Boolean) : Ctl
}

/** Which control a press at [p] lands on. The whole margin is live, so a thumb that is nearly there still counts. */
private fun hit(p: Offset, s: Float): Ctl? {
    val m = Bz.MARGIN * s
    val inTop = p.y < m
    val inBottom = p.y > s - m
    val inLeft = p.x < m
    val inRight = p.x > s - m
    if ((inTop || inBottom) && (inLeft || inRight)) {
        val k = when {
            inTop && inLeft -> 0
            inTop -> 1
            inRight -> 2
            else -> 3
        }
        return Ctl.Rocker(k, p.y < Bz.rockerCentre(k, s).y)
    }
    if (!(inTop || inBottom || inLeft || inRight)) return null
    val half = Bz.GLASS * s * 0.1f
    val halfSide = Bz.GLASS * s * 0.085f
    for (n in 1..20) {
        val c = Bz.osbCentre(n, s)
        val onEdge = when ((n - 1) / 5) { 0 -> inTop; 1 -> inRight; 2 -> inBottom; else -> inLeft }
        if (!onEdge) continue
        val d = if (Bz.horizontal(n)) abs(p.x - c.x) else abs(p.y - c.y)
        if (d <= if (Bz.horizontal(n)) half else halfSide) return Ctl.Osb(n)
    }
    return null
}

/** The inks of a bezel: a dark grey panel in the app, a printed outline on a kneeboard. */
private class BezelInks(
    val paper: Boolean,
    val flange: Color, val faceTop: Color, val faceBottom: Color,
    val capTop: Color, val capBottom: Color, val capDownTop: Color, val capDownBottom: Color,
    val line: Color, val print: Color, val glass: Color, val legend: Color, val legendDim: Color,
)

@Composable
private fun bezelInks(): BezelInks = if (Hud.onPaper) BezelInks(
    paper = true,
    flange = Hud.Surface, faceTop = Hud.Surface, faceBottom = Hud.Surface,
    capTop = Hud.Surface, capBottom = Hud.Surface, capDownTop = Hud.Outline, capDownBottom = Hud.Outline,
    line = Hud.Text, print = Hud.Text, glass = Hud.Surface, legend = Hud.Text, legendDim = Hud.TextDim,
) else BezelInks(
    paper = false,
    // A matte dark grey rather than black: in photographs the bezel reads as a grey panel round a black glass,
    // and that difference is what makes the glass look like glass.
    flange = Color(0xFF141618), faceTop = Color(0xFF34373A), faceBottom = Color(0xFF26292C),
    capTop = Color(0xFF4C5055), capBottom = Color(0xFF2E3135), capDownTop = Color(0xFF222528), capDownBottom = Color(0xFF2C2F32),
    line = Color(0xFF0B0C0D), print = Color(0xFFE4E2DA), glass = Color(0xFF050907),
    // The display's own green, a little softer than pure green so a page of it does not glare on a dark tablet.
    legend = Color(0xFF45F06E), legendDim = Color(0xFF1F6B35),
)

/**
 * One MFD: the bezel with its twenty buttons and four rockers, and the glass inside it.
 *
 * The parts come from three different places and none stands in for the others. The **legends** are what BMS
 * publishes in shared memory, which costs nothing and works on a BMS that exports nothing else. The **glass** is the
 * display itself, out of the render-to-texture export (see `RttTextures` on the PC), which has to be switched on.
 * The **buttons** are ours: a press on one is sent back to the cockpit as the key Falcon BMS has bound to it (see
 * `BmsKeys`), and the corner rockers the same way — BRT and GAIN, which have keys in BMS; SYM and CON, which BMS
 * does not implement, are drawn faded because they are on the bezel and say so when pressed or under the mouse, as
 * does a BRT or GAIN half whose callback the pilot's key file leaves unbound ([rockers]).
 *
 * The bezel is drawn whatever is running: an empty one still says where the buttons are, and a pilot setting a
 * tablet up on the ground needs to see the thing they are about to fly with. When there is no picture, [note] says
 * why on the glass and [onAction] carries out what it offers.
 */
@Composable
fun MfdPanel(
    keys: List<MfdKey>,
    name: String,
    modifier: Modifier = Modifier,
    /** the display's id in the export (`mfdleft`, `mfdright`), or null when there is no picture to be had */
    rtt: String? = null,
    /** `L` or `R`: which display a press should work in the cockpit. Null leaves the buttons dead. */
    side: Char? = null,
    onPressed: ((String) -> Unit)? = null,
    note: GlassNote? = null,
    onAction: ((GlassAction) -> Unit)? = null,
    fps: Int = 10,
    /** a faint line at the foot of the glass, for a state that is not worth a note (the live picture switched off) */
    footnote: String? = null,
    /** the name line under the bezel; the full page leaves it out, since which is left and which right is plain */
    showName: Boolean = true,
    /** what the rockers are bound to (`/api/mfd/keys`); null when unknown, and then only SYM and CON are dimmed */
    rockers: List<MfdRockerKey>? = null,
) {
    val ink = bezelInks()
    val scope = rememberCoroutineScope()
    // twenty entries whatever BMS is doing: a bezel with gaps in it is not a bezel
    val k = remember(keys) { List(20) { keys.getOrElse(it) { MfdKey() } } }
    var down by remember { mutableStateOf<Ctl?>(null) }
    // The rocker half under the mouse, for the tooltip a dead half carries. No light on the glass for an OSB press:
    // the pilots found the green flash beside the button a distraction, and the cap sinking says it was pressed.
    var hover by remember { mutableStateOf<Ctl.Rocker?>(null) }
    // a half is dead when BMS has no callback for it (SYM, CON) or the pilot's key file leaves its callback unbound
    val dead: (Ctl.Rocker) -> String? = dead@{ c ->
        val label = Bz.ROCKERS[c.k]
        val dir = if (c.up) "up" else "down"
        if (label == "SYM" || label == "CON") return@dead "$label: Falcon BMS has no callback for it (not implemented in BMS)."
        val r = rockers?.firstOrNull { it.side == side?.toString() && it.which == label.lowercase() && it.up == c.up } ?: return@dead null
        if (r.key.isBlank()) "$label $dir is not bound in your key file: bind ${r.callback.ifBlank { "it" }} to a key in Falcon BMS." else null
    }
    val handler: (Ctl) -> Unit = handler@{ c ->
        val s = side ?: return@handler
        when (c) {
            is Ctl.Osb -> scope.launch { onPressed?.invoke(MissionLink.pressOsb(s, c.n)) }
            is Ctl.Rocker -> {
                val label = Bz.ROCKERS[c.k]
                val why = dead(c)
                if (why != null) onPressed?.invoke(why)
                else scope.launch { onPressed?.invoke(MissionLink.pressRocker(s, label.lowercase(), c.up)) }
            }
        }
    }
    // read through state so the pointer handler, which is set up once, always calls the current one
    val press by rememberUpdatedState(handler)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current

    BoxWithConstraints(modifier) {
        val caption = if (showName) MFD_CAPTION else 0.dp
        val side0: Dp = if (constraints.hasBoundedHeight) min(maxWidth, maxHeight - caption) else maxWidth
        val sz = side0.coerceAtLeast(MFD_MIN)
        Column(Modifier.width(sz).align(Alignment.TopCenter)) {
            Box(Modifier.size(sz)) {
                // the bezel, its buttons and the empty glass
                Canvas(
                    Modifier.fillMaxSize().pointerInput(side) {
                        if (side == null || ink.paper) return@pointerInput
                        awaitPointerEventScope {
                            while (true) {
                                val e = awaitPointerEvent(PointerEventPass.Initial)
                                when (e.type) {
                                    PointerEventType.Press -> {
                                        val c = e.changes.firstOrNull()?.position?.let { hit(it, size.width.toFloat()) }
                                        down = c
                                        if (c != null) press(c)
                                    }
                                    PointerEventType.Release -> down = null
                                    PointerEventType.Exit -> { down = null; hover = null }
                                    PointerEventType.Move -> {
                                        val c = e.changes.firstOrNull()?.position?.let { hit(it, size.width.toFloat()) }
                                        hover = c as? Ctl.Rocker
                                    }
                                    else -> Unit
                                }
                            }
                        }
                    },
                ) {
                    val s = size.width
                    drawBezel(ink, s, down) { k, up -> dead(Ctl.Rocker(k, up)) != null }
                }

                val g0 = sz * Bz.MARGIN
                val g = sz * Bz.GLASS
                if (rtt != null) {
                    // The display itself, square because an MFD is: the radar's range rings stay round.
                    RttView(rtt, Modifier.offset(g0, g0).size(g), fps = fps)
                }
                // what the display itself would draw on the glass when there is no picture: the legends
                Canvas(Modifier.fillMaxSize()) {
                    val s = size.width
                    if (rtt == null) drawLegends(k, ink, s, measurer, density.fontScale)
                    drawSheen(ink, s)
                    drawRockerLabels(ink, s, measurer, density.fontScale)
                }
                // a dead rocker half says why under the mouse, beside its rocker and toward the glass
                val tip = hover?.let { h -> dead(h)?.let { h to it } }
                if (tip != null && !ink.paper) {
                    val (h, text) = tip
                    val left = h.k == 0 || h.k == 3
                    val top = h.k == 0 || h.k == 1
                    Text(
                        text,
                        Modifier.align(
                            when {
                                top && left -> Alignment.TopStart
                                top -> Alignment.TopEnd
                                left -> Alignment.BottomStart
                                else -> Alignment.BottomEnd
                            },
                        ).padding(horizontal = sz * 0.11f, vertical = sz * 0.03f).widthIn(max = sz * 0.6f)
                            .clip(RoundedCornerShape(4.dp)).background(Color(0xF01B1E21))
                            .border(1.dp, Color(0xFF3E4246), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                        color = Color(0xFFE4E2DA), fontSize = 11.sp, lineHeight = 14.sp,
                    )
                }
                if (note != null) {
                    GlassNoteView(note, ink, onAction, Modifier.offset(g0 + g * 0.17f, g0 + g * 0.15f).size(g * 0.66f, g * 0.70f), g)
                } else if (footnote != null) {
                    Text(
                        footnote, Modifier.offset(g0, g0 + g * 0.80f).width(g), color = ink.legendDim,
                        fontFamily = Mono, fontSize = with(density) { (g * 0.040f).toSp() }, textAlign = TextAlign.Center, maxLines = 1,
                    )
                }
            }
            if (showName) Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                // its own line height: the theme's 24 sp would not fit the MFD_CAPTION the card sizes the bezel with
                Text(name, fontFamily = Mono, fontSize = 10.sp, lineHeight = 13.sp, color = Hud.TextDim, maxLines = 1)
            }
        }
    }
}

/** The note on the glass: a title, a sentence, and the boxed choices a display would offer beside its buttons. */
@Composable
private fun GlassNoteView(note: GlassNote, ink: BezelInks, onAction: ((GlassAction) -> Unit)?, modifier: Modifier, glass: Dp) {
    val density = LocalDensity.current
    val title = with(density) { (glass * 0.058f).toSp() }
    val body = with(density) { (glass * 0.046f).toSp() }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(note.title, color = ink.legend, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = title, textAlign = TextAlign.Center, lineHeight = title * 1.15f)
        Spacer(Modifier.height(glass * 0.025f))
        Text(note.body, color = ink.legend, fontFamily = Mono, fontSize = body, textAlign = TextAlign.Center, lineHeight = body * 1.25f)
        if (onAction != null && note.actions.isNotEmpty()) {
            Spacer(Modifier.height(glass * 0.035f))
            // One above the other: side by side, SWITCH ON and SETUP GUIDE fill the note's width almost exactly, and
            // at a phone's rounding the second lost its last word ("SETUP" alone, `--mfdrender`).
            Column(verticalArrangement = Arrangement.spacedBy(glass * 0.022f), horizontalAlignment = Alignment.CenterHorizontally) {
                for (a in note.actions) {
                    Text(
                        when (a) { GlassAction.SETUP -> "SETUP GUIDE"; GlassAction.SWITCH_ON -> "SWITCH ON" },
                        Modifier.clip(RoundedCornerShape(2.dp)).border(1.dp, ink.legend, RoundedCornerShape(2.dp))
                            .clickable { onAction(a) }.padding(horizontal = glass * 0.02f, vertical = glass * 0.012f),
                        color = ink.legend, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = body, maxLines = 1, softWrap = false,
                    )
                }
            }
        }
    }
}

/** The bezel: flange, face, the recess round the glass, the ribs, the twenty caps and the four rockers. */
private fun DrawScope.drawBezel(ink: BezelInks, s: Float, down: Ctl?, dead: (Int, Boolean) -> Boolean = { _, _ -> false }) {
    val r = CornerRadius(s * 0.045f)
    // the flange the unit is mounted by, and the face standing proud of it
    drawRoundRect(ink.flange, size = Size(s, s), cornerRadius = r)
    val fi = s * Bz.FACE_INSET
    val face = Size(s - 2 * fi, s - 2 * fi)
    val faceR = CornerRadius(s * 0.036f)
    if (ink.paper) {
        drawRoundRect(ink.line, Offset(fi, fi), face, faceR, style = Stroke(s * 0.004f))
        drawRoundRect(ink.line, size = Size(s, s), cornerRadius = r, style = Stroke(s * 0.003f))
    } else {
        drawRoundRect(Brush.verticalGradient(listOf(ink.faceTop, ink.faceBottom), fi, s - fi), Offset(fi, fi), face, faceR)
        // light falls from above: a lit top edge, a shadowed bottom one
        drawRoundRect(Color.White.copy(alpha = 0.10f), Offset(fi, fi), face, faceR, style = Stroke(s * 0.004f))
        drawLine(Color.Black.copy(alpha = 0.45f), Offset(fi + faceR.x, s - fi), Offset(s - fi - faceR.x, s - fi), s * 0.005f)
        // a fine texture: the paint on these is a crackle finish, which reads as a faint grain at this size
        var y = fi + s * 0.01f
        while (y < s - fi) { drawLine(Color.White.copy(alpha = 0.012f), Offset(fi, y), Offset(s - fi, y), 1f); y += s * 0.009f }
    }
    // four mounting screws at the corners of the flange
    for ((x, y) in listOf(0.024f to 0.024f, 0.976f to 0.024f, 0.976f to 0.976f, 0.024f to 0.976f)) drawScrew(ink, Offset(x * s, y * s), s * 0.0085f)

    // the recess the glass sits in: shadowed where the light is blocked, lit on the far lip
    val g0 = Bz.MARGIN * s
    val g = Bz.GLASS * s
    val lip = s * 0.016f
    if (ink.paper) {
        drawRect(ink.line, Offset(g0 - lip, g0 - lip), Size(g + 2 * lip, g + 2 * lip), style = Stroke(s * 0.003f))
        drawRect(ink.line, Offset(g0, g0), Size(g, g), style = Stroke(s * 0.003f))
    } else {
        drawRoundRect(Brush.linearGradient(listOf(Color(0xFF08090A), Color(0xFF3D4145)), Offset(g0 - lip, g0 - lip), Offset(g0 + g + lip, g0 + g + lip)),
            Offset(g0 - lip, g0 - lip), Size(g + 2 * lip, g + 2 * lip), CornerRadius(s * 0.012f))
        drawRect(ink.glass, Offset(g0, g0), Size(g, g))
        // a faint lift in the middle of the glass, as an unlit panel has under cockpit light
        drawRect(Brush.radialGradient(listOf(Color(0xFF0C1410), ink.glass), Offset(g0 + g / 2, g0 + g / 2), g * 0.7f), Offset(g0, g0), Size(g, g))
    }

    // ribs between the caps, so a finger finds the gaps without looking
    for (n in 1..20) {
        if ((n - 1) % 5 == 4) continue
        val a = Bz.osbCentre(n, s)
        val b = Bz.osbCentre(n + 1, s)
        val mid = Offset((a.x + b.x) / 2, (a.y + b.y) / 2)
        val hz = Bz.horizontal(n)
        val w = (if (hz) Bz.RIB_SHORT else Bz.RIB_LONG) * s
        val h = (if (hz) Bz.RIB_LONG else Bz.RIB_SHORT) * s
        val tl = Offset(mid.x - w / 2, mid.y - h / 2)
        if (ink.paper) drawRect(ink.line.copy(alpha = 0.5f), tl, Size(w, h), style = Stroke(1f))
        else {
            drawRoundRect(Color(0xFF3E4246), tl, Size(w, h), CornerRadius(w.coerceAtMost(h) / 2))
            drawRoundRect(Color.Black.copy(alpha = 0.35f), tl + Offset(0f, s * 0.004f), Size(w, h), CornerRadius(w.coerceAtMost(h) / 2), style = Stroke(1f))
        }
    }
    for (n in 1..20) drawCap(ink, Bz.osbCentre(n, s), Bz.horizontal(n), s, down == Ctl.Osb(n))
    for (kk in 0..3) drawRocker(ink, kk, s, (down as? Ctl.Rocker)?.takeIf { it.k == kk }, dead(kk, true), dead(kk, false))
}

private fun DrawScope.drawScrew(ink: BezelInks, c: Offset, rad: Float) {
    if (ink.paper) {
        drawCircle(ink.line, rad, c, style = Stroke(1f))
    } else {
        drawCircle(Brush.radialGradient(listOf(Color(0xFF5A5F64), Color(0xFF1E2023)), c - Offset(rad * 0.3f, rad * 0.3f), rad * 1.4f), rad, c)
        drawCircle(Color.Black.copy(alpha = 0.5f), rad, c, style = Stroke(rad * 0.18f))
    }
    // a cross-point head
    val d = rad * 0.55f
    val col = if (ink.paper) ink.line else Color.Black.copy(alpha = 0.7f)
    drawLine(col, c - Offset(d, 0f), c + Offset(d, 0f), rad * 0.22f)
    drawLine(col, c - Offset(0f, d), c + Offset(0f, d), rad * 0.22f)
}

/**
 * One option-select button, drawn as the moulded cap it is.
 *
 * There is no lighting model here and it does not need one: a cap reads as raised from three facts — a shadow
 * under it, a lighter top edge where the light falls and a darker lower one — and as pressed when the shadow goes,
 * the cap sinks a little and the edges swap. The press is drawn from the pointer directly, because the only
 * feedback that matters is the one that happens before the finger lifts: the cockpit's own answer takes a round
 * trip to the PC and back.
 */
private fun DrawScope.drawCap(ink: BezelInks, c: Offset, horizontal: Boolean, s: Float, pressed: Boolean) {
    val w = (if (horizontal) Bz.CAP_LONG else Bz.CAP_SHORT) * s
    val h = (if (horizontal) Bz.CAP_SHORT else Bz.CAP_SIDE) * s
    val sink = if (pressed) s * 0.004f else 0f
    val tl = Offset(c.x - w / 2, c.y - h / 2 + sink)
    val rr = CornerRadius(s * 0.009f)
    if (ink.paper) {
        if (pressed) drawRoundRect(ink.capDownTop, tl, Size(w, h), rr)
        drawRoundRect(ink.line, tl, Size(w, h), rr, style = Stroke(s * 0.0035f))
        return
    }
    if (!pressed) drawRoundRect(Color.Black.copy(alpha = 0.55f), tl + Offset(0f, s * 0.007f), Size(w, h), rr)
    drawRoundRect(
        Brush.verticalGradient(if (pressed) listOf(ink.capDownTop, ink.capDownBottom) else listOf(ink.capTop, ink.capBottom), tl.y, tl.y + h),
        tl, Size(w, h), rr,
    )
    // the square face of the cap, a touch inside its edge, the way a moulded key has a flat top and a chamfer
    val inset = s * 0.007f
    drawRoundRect(
        if (pressed) Color.Black.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.05f),
        tl + Offset(inset, inset), Size(w - 2 * inset, h - 2 * inset), CornerRadius(s * 0.005f),
    )
    val edge = s * 0.0035f
    drawLine(if (pressed) Color.Black.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.22f), tl + Offset(rr.x, edge / 2), tl + Offset(w - rr.x, edge / 2), edge)
    drawLine(if (pressed) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.55f), tl + Offset(rr.x, h - edge / 2), tl + Offset(w - rr.x, h - edge / 2), edge)
}

/**
 * A corner rocker: a short upright bar, pressed at the top to increase and at the bottom to decrease, and its name.
 *
 * Each half shows its own press the same way, by a shade laid over that half alone. Up to the 1.3.8 test build the
 * press was a three-stop gradient whose lower stop changed by two shades of grey, so the down half looked dead while
 * it was sending its key. A half that does nothing ([deadUp]/[deadDown]: SYM, CON, or a callback the key file leaves
 * unbound) is drawn faded, its arrow too.
 */
private fun DrawScope.drawRocker(ink: BezelInks, k: Int, s: Float, down: Ctl.Rocker?, deadUp: Boolean = false, deadDown: Boolean = false) {
    val c = Bz.rockerCentre(k, s)
    val w = Bz.ROCKER_W * s
    val h = Bz.ROCKER_H * s
    val tl = Offset(c.x - w / 2, c.y - h / 2)
    val rr = CornerRadius(w * 0.35f)
    fun half(up: Boolean, block: DrawScope.() -> Unit) =
        clipRect(tl.x - 2f, if (up) tl.y - 2f else c.y, tl.x + w + 2f, if (up) c.y else tl.y + h + 2f, block = block)
    if (ink.paper) {
        if (down != null) half(down.up) { drawRoundRect(ink.capDownTop, tl, Size(w, h), rr) }
        drawRoundRect(ink.line, tl, Size(w, h), rr, style = Stroke(s * 0.0035f))
        drawLine(ink.line, Offset(tl.x, c.y), Offset(tl.x + w, c.y), 1f)
    } else {
        drawRoundRect(Color.Black.copy(alpha = 0.55f), tl + Offset(0f, s * 0.006f), Size(w, h), rr)
        drawRoundRect(Brush.verticalGradient(listOf(ink.capTop, ink.capBottom, ink.capBottom), tl.y, tl.y + h), tl, Size(w, h), rr)
        // the rocker tips toward the half that is pressed: that half sinks into shadow
        if (down != null) half(down.up) {
            drawRoundRect(Brush.verticalGradient(listOf(ink.capDownTop, ink.capDownBottom), tl.y, tl.y + h), tl, Size(w, h), rr)
            drawRoundRect(Color.Black.copy(alpha = 0.40f), tl, Size(w, h), rr)
        }
        drawLine(Color.Black.copy(alpha = 0.6f), Offset(tl.x + w * 0.12f, c.y), Offset(tl.x + w * 0.88f, c.y), s * 0.003f)
        if (down?.up != true) drawLine(Color.White.copy(alpha = 0.18f), tl + Offset(rr.x, 1f), tl + Offset(w - rr.x, 1f), s * 0.003f)
    }
    // up and down marks on the two halves
    val a = w * 0.22f
    val mark = if (ink.paper) ink.print else ink.print.copy(alpha = 0.85f)
    fun tri(cy: Float, up: Boolean) {
        val p = Path()
        if (up) { p.moveTo(c.x, cy - a * 0.6f); p.lineTo(c.x + a, cy + a * 0.6f); p.lineTo(c.x - a, cy + a * 0.6f) }
        else { p.moveTo(c.x, cy + a * 0.6f); p.lineTo(c.x + a, cy - a * 0.6f); p.lineTo(c.x - a, cy - a * 0.6f) }
        p.close()
        val dead = if (up) deadUp else deadDown
        drawPath(p, if (dead) mark.copy(alpha = mark.alpha * 0.3f) else mark)
    }
    tri(c.y - h * 0.25f, true)
    tri(c.y + h * 0.25f, false)
    // a dead half is faded over, so it reads as a part of the bezel rather than a control
    if (!ink.paper) {
        if (deadUp) half(true) { drawRoundRect(Color(0xFF26292C).copy(alpha = 0.55f), tl, Size(w, h), rr) }
        if (deadDown) half(false) { drawRoundRect(Color(0xFF26292C).copy(alpha = 0.55f), tl, Size(w, h), rr) }
    }
}

/** GAIN, SYM, BRT and CON, printed on the bezel beside their rockers, toward the glass. */
private fun DrawScope.drawRockerLabels(ink: BezelInks, s: Float, measurer: androidx.compose.ui.text.TextMeasurer, fontScale: Float) {
    val style = TextStyle(fontSize = (s * 0.026f / density / fontScale).sp, fontWeight = FontWeight.Bold, color = ink.print, letterSpacing = 0.02.sp)
    for (k in 0..3) {
        val c = Bz.rockerCentre(k, s)
        val t = measurer.measure(Bz.ROCKERS[k], style)
        val gap = s * 0.010f + Bz.ROCKER_W * s / 2
        val x = if (k == 0 || k == 3) c.x + gap else c.x - gap - t.size.width
        drawText(t, topLeft = Offset(x, c.y - t.size.height / 2f))
    }
}

/** The legends BMS publishes, drawn where the display would draw them: along each edge of the glass, beside its button. */
private fun DrawScope.drawLegends(keys: List<MfdKey>, ink: BezelInks, s: Float, measurer: androidx.compose.ui.text.TextMeasurer, fontScale: Float) {
    val g0 = Bz.MARGIN * s
    val g = Bz.GLASS * s
    val pad = g * 0.018f
    // about 22 characters across the glass, as on the real page; divided by the font scale so a pilot's larger
    // system text does not push the legends into each other
    val style = TextStyle(fontFamily = Mono, fontSize = (g * 0.046f / density / fontScale).sp, fontWeight = FontWeight.SemiBold)
    for (n in 1..20) {
        val key = keys[n - 1]
        if (key.blank) continue
        val lines = listOf(key.a, key.b).filter { it.isNotBlank() }
        val laid = lines.map { measurer.measure(it, style.copy(color = if (key.inverted && !ink.paper) ink.glass else ink.legend)) }
        val w = laid.maxOf { it.size.width }.toFloat()
        val h = laid.sumOf { it.size.height }.toFloat()
        val c = Bz.osbCentre(n, s)
        val tl = when ((n - 1) / 5) {
            0 -> Offset(c.x - w / 2, g0 + pad)
            1 -> Offset(g0 + g - pad - w, c.y - h / 2)
            2 -> Offset(c.x - w / 2, g0 + g - pad - h)
            else -> Offset(g0 + pad, c.y - h / 2)
        }
        // A highlighted legend is shown the way the display shows it: reverse video, a green box with the text cut
        // out of it. On paper a box round it says the same thing.
        if (key.inverted) {
            val box = Rect(tl.x - pad * 0.4f, tl.y - pad * 0.2f, tl.x + w + pad * 0.4f, tl.y + h + pad * 0.2f)
            if (ink.paper) drawRect(ink.line, box.topLeft, box.size, style = Stroke(1.2f))
            else drawRect(ink.legend, box.topLeft, box.size)
        }
        var y = tl.y
        for (t in laid) {
            val x = when ((n - 1) / 5) {
                1 -> tl.x + (w - t.size.width)
                3 -> tl.x
                else -> tl.x + (w - t.size.width) / 2
            }
            drawText(t, topLeft = Offset(x, y))
            y += t.size.height
        }
    }
}

/** The anti-glare glass over whatever is on it: a faint sheen across the top corner, never enough to hide the picture. */
private fun DrawScope.drawSheen(ink: BezelInks, s: Float) {
    if (ink.paper) return
    val g0 = Bz.MARGIN * s
    val g = Bz.GLASS * s
    drawRect(
        Brush.linearGradient(listOf(Color.White.copy(alpha = 0.055f), Color.Transparent), Offset(g0, g0), Offset(g0 + g * 0.55f, g0 + g * 0.55f)),
        Offset(g0, g0), Size(g, g),
    )
}

// ---------------------------------------------------------------- the card

/**
 * Both MFDs on one card: the bezels always, the glass when Falcon BMS is exporting its displays.
 *
 * The glass does the explaining: each state a pilot can be in — this device not linked, BMS not running, BMS in the
 * menus, the export switched off, the displays unpowered — is said once, on the glass, with the one thing to do
 * about it. Below the bezels are the switch that turns the live picture off and what it costs, because the export
 * costs the sim a little every frame and some PCs have none to spare.
 */
@Composable
fun MfdCard(l: Live?, width: DashWidth, onSetup: (() -> Unit)? = null, onFull: (() -> Unit)? = null) {
    val live = MfdPrefs.live
    val link by MissionLink.state.collectAsState()
    val linked = link is LinkState.Online
    val rtt = rememberRttState(enabled = live && linked)
    MfdCardBody(l, linked, live, rtt, onSetup, onFull = onFull)
}

/**
 * The MFDs filling whatever box they are given ([MfdFull]): no card, no name lines, no notes under them — only the two
 * bezels, as large as the box allows, and a thin strip with the way back and the two chips, put along whichever edge
 * the bezels leave free (above them on a landscape window, beside them when stacked on a portrait one).
 */
@Composable
fun MfdFullPage(l: Live?, onSetup: (() -> Unit)?, onExit: () -> Unit) {
    val live = MfdPrefs.live
    val link by MissionLink.state.collectAsState()
    val linked = link is LinkState.Online
    val rtt = rememberRttState(enabled = live && linked)
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { MfdFull.on = false } }
    MfdCardBody(l, linked, live, rtt, onSetup, full = true, onExit = onExit)
}

/** The strip of controls on the full page: as a row above the bezels, or as a column beside them. */
internal val MFD_FULL_BAR = 40.dp
internal val MFD_FULL_SIDE = 132.dp
internal val MFD_FULL_PAD = 6.dp

/** The full page's bezels in a [width] x [height] box (inside its padding), and whether the strip goes beside them. */
data class MfdFullFit(val fit: MfdFit, val stripBeside: Boolean)

/**
 * Where the full page's strip goes: wherever the bezels, fitted to the whole box, leave room for it — above them when
 * there is a strip's height to spare, beside them when there is only width — so on most windows it costs the
 * displays nothing at all. Only when neither is free does it take its height from them.
 */
fun mfdFullFit(width: Dp, height: Dp, arrange: MfdArrange, medium: Boolean): MfdFullFit {
    val f0 = mfdFit(width, height, arrange, medium, caption = 0.dp)
    val spareW = width - (if (f0.stacked) f0.side else f0.side * 2 + MFD_GAP)
    val spareH = height - (if (f0.stacked) f0.side * 2 + MFD_GAP else f0.side)
    return if (spareH < MFD_FULL_BAR && spareW >= MFD_FULL_SIDE) MfdFullFit(mfdFit(width - MFD_FULL_SIDE, height, arrange, medium, caption = 0.dp), true)
    else MfdFullFit(mfdFit(width, height - MFD_FULL_BAR, arrange, medium, caption = 0.dp), false)
}

/** The card with its inputs spelled out, so `--mfdrender` can draw every state of it without a PC to ask. */
@Composable
fun MfdCardBody(
    l: Live?, linked: Boolean, live: Boolean, rtt: RttState?, onSetup: (() -> Unit)?,
    /** the whole page rather than a card ([MfdFullPage]) */
    full: Boolean = false,
    /** shown as the card's full-page button when set */
    onFull: (() -> Unit)? = null,
    /** the full page's way back */
    onExit: (() -> Unit)? = null,
) {
    var said by remember { mutableStateOf<String?>(null) }
    var asking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // what the rockers are bound to in the pilot's key file, asked again now and then (a pilot may rebind in BMS)
    val bindings by androidx.compose.runtime.produceState<com.bmscompanion.app.data.mission.MfdBindings?>(null, linked) {
        if (!linked) { value = null; return@produceState }
        while (true) {
            MissionLink.mfdBindings()?.let { value = it }
            delay(20_000)
        }
    }
    val rockers = bindings?.rockers?.takeIf { it.isNotEmpty() }
    // the last press's answer, else why no press can reach BMS at all (BMS elevated, the PC program not: UIPI)
    val saidNow = said ?: bindings?.blocked?.takeIf { it.isNotBlank() }
    // A dark display is still read: the note goes over its black picture, and the picture is what tells the PC the
    // moment the pilot switches the MFDs on.
    val picture = live && linked && rtt != null && (rtt.phase == RttPhase.LIVE || rtt.phase == RttPhase.DARK || (rtt.phase == null && rtt.available))
    val left = if (picture && rtt?.has("mfdleft") == true) "mfdleft" else null
    val right = if (picture && rtt?.has("mfdright") == true) "mfdright" else null
    val note = when {
        !linked -> glassNote(false, null)
        !live -> null
        else -> glassNote(true, rtt)
    }
    val onAction: (GlassAction) -> Unit = { a ->
        when (a) {
            GlassAction.SETUP -> onSetup?.invoke()
            GlassAction.SWITCH_ON -> if (!asking) {
                asking = true
                scope.launch {
                    val s = MissionLink.setRtt(true)
                    asking = false
                    said = when {
                        s == null -> "The PC did not answer."
                        s.error != null -> s.error
                        else -> "Switched on in your Config profile. Restart Falcon BMS once for it to take effect."
                    }
                }
            }
        }
    }

    if (full) {
        val press: (String) -> Unit = { said = it }
        val foot = if (!live && linked) "LIVE PICTURE OFF" else null
        @Composable
        fun panel(keys: List<MfdKey>, id: String?, s: Char, m: Modifier) = MfdPanel(
            keys, "", m, rtt = id, side = s, onPressed = press,
            note = if (id == null || rtt?.phase == RttPhase.DARK) note else null,
            onAction = onAction, fps = MfdPrefs.fps, footnote = foot, showName = false, rockers = rockers,
        )
        @Composable
        fun bezels(m: Modifier) = BoxWithConstraints(m, contentAlignment = Alignment.Center) {
            val fit = mfdFit(maxWidth, maxHeight, MfdPrefs.arrange, isMedium(), caption = 0.dp)
            val each = Modifier.size(fit.side)
            if (fit.stacked) Column(verticalArrangement = Arrangement.spacedBy(MFD_GAP)) {
                panel(l?.mfdLeft.orEmpty(), left, 'L', each)
                panel(l?.mfdRight.orEmpty(), right, 'R', each)
            } else Row(horizontalArrangement = Arrangement.spacedBy(MFD_GAP)) {
                panel(l?.mfdLeft.orEmpty(), left, 'L', each)
                panel(l?.mfdRight.orEmpty(), right, 'R', each)
            }
        }
        BoxWithConstraints(Modifier.fillMaxSize().padding(MFD_FULL_PAD)) {
            if (mfdFullFit(maxWidth, maxHeight, MfdPrefs.arrange, isMedium()).stripBeside) Row(Modifier.fillMaxSize()) {
                bezels(Modifier.weight(1f).fillMaxHeight())
                Column(Modifier.width(MFD_FULL_SIDE).fillMaxHeight().padding(start = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FullExit(onExit)
                    ArrangeChip(MfdPrefs.arrange) { MfdPrefs.arrange = it }
                    LiveChip(live) { MfdPrefs.live = it }
                    saidNow?.let { Text(it, color = Hud.TextDim, fontSize = 11.sp, lineHeight = 14.sp) }
                }
            } else Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().height(MFD_FULL_BAR), verticalAlignment = Alignment.CenterVertically) {
                    FullExit(onExit)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        saidNow ?: "", Modifier.weight(1f), color = Hud.TextDim, fontSize = 11.sp, lineHeight = 14.sp,
                        maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                    ArrangeChip(MfdPrefs.arrange) { MfdPrefs.arrange = it }
                    Spacer(Modifier.width(6.dp))
                    LiveChip(live) { MfdPrefs.live = it }
                }
                bezels(Modifier.weight(1f).fillMaxWidth())
            }
        }
        return
    }

    SectionCard("MFDs", accent = Hud.Green, trailing = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onFull != null) {
                Box(
                    Modifier.size(26.dp).clip(RoundedCornerShape(8.dp)).background(Hud.Surface2)
                        .border(1.dp, Hud.Outline, RoundedCornerShape(8.dp)).clickable { onFull() },
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.material3.Icon(
                        Icons.Default.Fullscreen, "MFDs full page",
                        tint = Hud.TextDim, modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(Modifier.width(6.dp))
            }
            ArrangeChip(MfdPrefs.arrange) { MfdPrefs.arrange = it }
            Spacer(Modifier.width(6.dp))
            LiveChip(live) { MfdPrefs.live = it }
        }
    }) {
        val leftKeys = l?.mfdLeft.orEmpty()
        val rightKeys = l?.mfdRight.orEmpty()
        val press: (String) -> Unit = { said = it }
        val foot = if (!live && linked) "LIVE PICTURE OFF" else null
        @Composable
        fun panel(keys: List<MfdKey>, nm: String, id: String?, s: Char, m: Modifier) = MfdPanel(
            keys, nm, m, rtt = id, side = s, onPressed = press,
            // the note goes on the glass that has nothing to show; a cockpit with one MFD picture keeps it
            note = if (id == null || rtt?.phase == RttPhase.DARK) note else null,
            onAction = onAction, fps = MfdPrefs.fps, footnote = foot, rockers = rockers,
        )
        // As large as the page allows: on a dashboard the card knows how much of the window it has below the
        // page's header (LocalMfdRoom), and both bezels are sized to fit it whole — so the top row of buttons and
        // the bottom are always on screen together, which is the one thing a bezel has to let you do — side by side
        // or one above the other, whichever gives each display the larger square (or as the pilot set it).
        val room = LocalMfdRoom.current
        val medium = isMedium()
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val fit = mfdFit(maxWidth, room?.let { it - MFD_CARD_CHROME }, MfdPrefs.arrange, medium)
            val each = Modifier.size(fit.side, fit.side + MFD_CAPTION)
            if (fit.stacked) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(MFD_GAP), horizontalAlignment = Alignment.CenterHorizontally) {
                    panel(leftKeys, "LEFT MFD", left, 'L', each)
                    panel(rightKeys, "RIGHT MFD", right, 'R', each)
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(MFD_GAP, Alignment.CenterHorizontally)) {
                    panel(leftKeys, "LEFT MFD", left, 'L', each)
                    panel(rightKeys, "RIGHT MFD", right, 'R', each)
                }
            }
        }
        saidNow?.let { Text(it, color = Hud.TextDim, fontSize = 11.5.sp, modifier = Modifier.padding(top = 6.dp)) }
        Spacer(Modifier.height(8.dp))
        if (live) RateRow()
        Spacer(Modifier.height(4.dp))
        Text(
            if (live) MFD_COST else "Live picture off: the bezels show BMS's own button legends and the buttons still work. " +
                "Nothing is read from Falcon BMS for the picture and nothing is sent.",
            color = Hud.TextFaint, fontSize = 11.sp, lineHeight = 15.sp,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "A button here sends the key your Falcon BMS has bound to it, so BMS must be the window in front on the PC. " +
                "The OSBs, BRT and GAIN are bound by default; SYM and CON are not implemented in BMS.",
            color = Hud.TextFaint, fontSize = 11.sp, lineHeight = 15.sp,
        )
    }
}

/**
 * What the live picture costs, said where the switch is.
 *
 * Two costs, in two places, and it matters which is which: BMS pays for the export every frame whether anything is
 * reading it or not — the "Expt" figure in its own FPS overlay — so that one is switched in the Launcher; this
 * program's reading is a copy of two small rectangles and only while a card is on screen, which this switch stops.
 */
internal const val MFD_COST =
    "Live picture: Falcon BMS spends a little of each frame copying its displays out while Export RTT Textures is on " +
        "(\"Expt\" in its FPS overlay), whether this card is open or not. Reading them costs the PC well under a " +
        "millisecond a frame for its own window and about 4 ms for a phone or browser, and nothing at all when a " +
        "display is not moving, when the card is off screen or when the live picture is off."

@Composable
private fun LiveChip(on: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(12.dp)).background(if (on) Hud.Green.copy(alpha = 0.18f) else Hud.Surface2)
            .border(1.dp, if (on) Hud.Green.copy(alpha = 0.6f) else Hud.Outline, RoundedCornerShape(12.dp))
            .clickable { onChange(!on) }.padding(horizontal = 10.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(RoundedCornerShape(4.dp)).background(if (on) Hud.Green else Hud.TextFaint))
        Spacer(Modifier.width(6.dp))
        Text(if (on) "Live picture on" else "Live picture off", fontSize = 11.sp, color = if (on) Hud.Green else Hud.TextDim, fontWeight = FontWeight.SemiBold)
    }
}

/** The full page's way back to the dashboard. */
@Composable
private fun FullExit(onExit: (() -> Unit)?) {
    Row(
        Modifier.clip(RoundedCornerShape(10.dp)).background(Hud.Amber.copy(alpha = 0.12f))
            .border(1.dp, Hud.Amber.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
            .clickable { onExit?.invoke() ?: run { MfdFull.on = false } }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.Icon(
            Icons.Default.FullscreenExit, null,
            tint = Hud.Amber, modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text("Dashboard", fontSize = 12.sp, color = Hud.Amber, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
    }
}

/** Side by side, stacked or whichever is larger: a tap moves to the next. */
@Composable
private fun ArrangeChip(now: MfdArrange, onChange: (MfdArrange) -> Unit) {
    Text(
        now.label,
        Modifier.clip(RoundedCornerShape(12.dp)).background(Hud.Surface2)
            .border(1.dp, Hud.Outline, RoundedCornerShape(12.dp))
            .clickable { onChange(MfdArrange.entries[(now.ordinal + 1) % MfdArrange.entries.size]) }
            .padding(horizontal = 10.dp, vertical = 3.dp),
        fontSize = 11.sp, color = Hud.TextDim, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false,
    )
}

/** How often the picture is asked for. */
@Composable
private fun RateRow() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Frames a second", fontSize = 11.sp, color = Hud.TextDim)
        for (r in MfdPrefs.rates) {
            val on = MfdPrefs.fps == r
            Text(
                "$r", Modifier.clip(RoundedCornerShape(6.dp)).background(if (on) Hud.Green.copy(alpha = 0.2f) else Hud.Surface2)
                    .clickable { MfdPrefs.fps = r }.padding(horizontal = 8.dp, vertical = 2.dp),
                fontSize = 11.sp, color = if (on) Hud.Green else Hud.TextDim, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
            )
        }
    }
}

// ---------------------------------------------------------------- Setup

/**
 * The MFD picture in Setup: the same switch and rate as on the card, what this PC's Falcon BMS says about the export,
 * and the guide to switching it on. This is the page the glass's SETUP GUIDE opens.
 */
@Composable
fun MfdSetupCard() {
    val link by MissionLink.state.collectAsState()
    val rtt = rememberRttState(everyMs = 5000, enabled = link is LinkState.Online)
    SectionCard("Cockpit displays (MFDs)", accent = Hud.Green, trailing = { LiveChip(MfdPrefs.live) { MfdPrefs.live = it } }) {
        Text(
            "The Dashboard's MFDs card shows Falcon BMS's own two displays — radar, pod, whatever page is up — inside " +
                "working bezels. The buttons always work; the picture needs one switch in BMS.",
            color = Hud.TextDim, fontSize = 12.sp, lineHeight = 16.sp,
        )
        Spacer(Modifier.height(8.dp))
        val cfg = rtt?.config
        val status = when {
            link !is LinkState.Online -> null
            rtt == null -> null
            rtt.phase == RttPhase.LIVE -> "This PC: Falcon BMS is exporting its displays."
            cfg?.on == true -> "This PC: Export RTT Textures is on" + (if (cfg.launcher) " in the Launcher." else ".") +
                (if (rtt.phase == RttPhase.RESTART) " Restart Falcon BMS for it to take effect." else "")
            cfg?.on == false -> "This PC: Export RTT Textures is off" + (if (cfg.launcher) " in the Launcher." else ".")
            else -> null
        }
        status?.let {
            Text(it, color = if (rtt?.phase == RttPhase.LIVE || cfg?.on == true) Hud.Green else Hud.Amber, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
        }
        GuideLine(1, "In the **Falcon BMS Launcher**, on its main page under the VR choice, set **Export RTT Textures** to **Enable**. The Launcher writes that choice into Falcon BMS User.cfg every time it starts BMS, after everything else, so it is the switch that counts.")
        GuideLine(2, "Launch BMS and enter the cockpit. The pictures appear in the bezels on their own; until then the glass says what it is waiting for.")
        GuideLine(3, "A cold jet's displays are dark until the **MFD** switch on the AVIONICS POWER panel is on.")
        GuideLine(4, "Launching BMS without the Launcher's overrides? Then **g_bExportRTTTextures 1** can go into your config instead: the glass offers a SWITCH ON button that writes it through the Config page's backed-up path.")
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) { RateRow() }
        Spacer(Modifier.height(6.dp))
        Text(MFD_COST, color = Hud.TextFaint, fontSize = 11.sp, lineHeight = 15.sp)
    }
}

@Composable
private fun GuideLine(n: Int, text: String) {
    Row(Modifier.padding(vertical = 3.dp)) {
        Text("$n", color = Hud.Green, fontWeight = FontWeight.Bold, fontSize = 12.5.sp, modifier = Modifier.width(18.dp))
        Text(bold(text), color = Hud.Text, fontSize = 12.5.sp, lineHeight = 17.sp)
    }
}

/** `**bold**` in a sentence, the way the guides on the Setup page write it. */
private fun bold(text: String) = androidx.compose.ui.text.buildAnnotatedString {
    var on = false
    for (part in text.split("**")) {
        if (on) pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold))
        append(part)
        if (on) pop()
        on = !on
    }
}
