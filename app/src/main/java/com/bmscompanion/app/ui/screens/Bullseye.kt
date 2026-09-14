package com.bmscompanion.app.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.bmscompanion.app.data.Airport
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.Theater
import com.bmscompanion.app.ui.components.BmsTopBar
import com.bmscompanion.app.ui.components.ContentColumn
import com.bmscompanion.app.ui.components.Fmt
import com.bmscompanion.app.ui.components.HudChip
import com.bmscompanion.app.ui.components.LoadingBox
import com.bmscompanion.app.ui.components.Paragraph
import com.bmscompanion.app.ui.components.SectionCard
import com.bmscompanion.app.ui.components.TheaterMap
import com.bmscompanion.app.ui.components.rememberMapState
import com.bmscompanion.app.ui.components.safeText
import com.bmscompanion.app.ui.theme.Hud
import com.bmscompanion.app.ui.theme.LocalExtra
import com.bmscompanion.app.ui.theme.Mono
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

// ------------------------------------------------------------------ model

enum class GameMode(val title: String, val blurb: String, val how: String) {
    PLOT("Plot the call", "AWACS gives a bullseye call — tap where the group is.", "Read the BULLSEYE bearing/range and tap that point on the HSD. Rings every 20 nm, bearings are TRUE from the bullseye."),
    WHERE("Call your bullseye", "Read your own position off the HSD and call it.", "Enter bearing and range FROM the bullseye TO your jet. On Flight Lead the display is heading-up — mentally rotate it!"),
    BRAA("BRAA from bullseye", "Convert a bullseye call into BRAA from your jet.", "You know your own bullseye position and the bandit's. Work out bearing and range from you to the bandit. Picture is hidden on harder levels."),
    CLOCK("Clock & reciprocal drill", "Rapid-fire: clock positions, reciprocals, beam headings.", "Answer as fast as you can. Clock positions are relative to your heading (12 o'clock = nose)."),
    THEATER("Theater map", "Bullseye is a real airfield — plot calls on the theater map.", "Use the real theater geography. Pinch to zoom, tap to answer. Great for learning where fields are."),
}

enum class Level(val title: String, val maxRange: Int, val brgTol: Double, val rngTol: Double, val seconds: Int, val labels: Boolean) {
    ROOKIE("Rookie", 50, 40.0, 0.50, 45, true),
    WINGMAN("Wingman", 80, 30.0, 0.40, 35, true),
    LEAD("Flight Lead", 120, 22.0, 0.30, 25, false),
}

private data class Round(
    val prompt: String,
    val subPrompt: String = "",
    val bx: Double = 0.0, val by: Double = 0.0,          // bullseye (nm, x=north, y=east) or theater ft for THEATER
    val ox: Double = 0.0, val oy: Double = 0.0,          // ownship
    val ownHdg: Double = 0.0,
    val tx: Double = 0.0, val ty: Double = 0.0,          // target
    val answerBrg: Double? = null, val answerRng: Double? = null, // for keypad modes
    val singleField: Boolean = false,
    val bullseyeName: String? = null,
    val showPicture: Boolean = true,
)

private data class Result(val points: Int, val detail: String, val userX: Double? = null, val userY: Double? = null, val zoneNm: Double? = null)

private fun wrap360(d: Double) = ((d % 360) + 360) % 360
private fun angDiff(a: Double, b: Double) = abs(((a - b + 540) % 360) - 180)
private fun polar(brg: Double, rng: Double) = Pair(rng * cos(Math.toRadians(brg)), rng * sin(Math.toRadians(brg)))
private fun brgRng(fromX: Double, fromY: Double, toX: Double, toY: Double) = Pair(wrap360(Math.toDegrees(kotlin.math.atan2(toY - fromY, toX - fromX))), hypot(toX - fromX, toY - fromY))
private fun b3(d: Double) = String.format("%03d", wrap360(d).roundToInt() % 360)

private val aspects = listOf("HOT", "FLANK", "BEAM", "DRAG")
private val callsigns = listOf("Viper 1", "Uzi 1", "Cowboy 2", "Enfield 1", "Springfield 3", "Dodge 4", "Colt 2", "Chevy 1")
private val awacs = listOf("Magic", "Darkstar", "Chalice", "Sentry", "Wizard", "Overlord")

private fun makeRound(mode: GameMode, level: Level, rnd: Random, fields: List<Airport>, theater: Theater?): Round {
    val max = level.maxRange.toDouble()
    when (mode) {
        GameMode.PLOT -> {
            val brg = rnd.nextInt(0, 360).toDouble(); val rng = rnd.nextInt(8, level.maxRange).toDouble()
            val (tx, ty) = polar(brg, rng)
            val angels = rnd.nextInt(5, 40)
            return Round("${awacs.random(rnd)}: GROUP, BULLSEYE ${b3(brg)}/${rng.toInt()}, ANGELS $angels, ${aspects.random(rnd)}, HOSTILE", "Tap the group's position", tx = tx, ty = ty, answerBrg = brg, answerRng = rng)
        }
        GameMode.WHERE -> {
            val brg = rnd.nextInt(0, 360).toDouble(); val rng = rnd.nextInt(5, level.maxRange).toDouble()
            val (ox, oy) = polar(brg, rng)
            val hdg = rnd.nextInt(0, 36) * 10.0
            return Round("${callsigns.random(rnd)}, say your bullseye position.", "Bearing & range from bullseye to you", ox = ox, oy = oy, ownHdg = hdg, answerBrg = brg, answerRng = rng)
        }
        GameMode.BRAA -> {
            var tries = 0
            while (true) {
                val ob = rnd.nextInt(0, 360).toDouble(); val orng = rnd.nextInt(5, (max * 0.7).toInt()).toDouble()
                val tb = rnd.nextInt(0, 360).toDouble(); val trng = rnd.nextInt(5, (max * 0.7).toInt()).toDouble()
                val (ox, oy) = polar(ob, orng); val (tx, ty) = polar(tb, trng)
                val (brg, rng) = brgRng(ox, oy, tx, ty)
                if ((rng in 5.0..max) || tries++ > 30) {
                    val hdg = rnd.nextInt(0, 36) * 10.0
                    return Round(
                        "Your position: BULLSEYE ${b3(ob)}/${orng.toInt()}, heading ${b3(hdg)}.",
                        "${awacs.random(rnd)}: BANDIT, BULLSEYE ${b3(tb)}/${trng.toInt()} — give BRAA",
                        ox = ox, oy = oy, ownHdg = hdg, tx = tx, ty = ty, answerBrg = brg, answerRng = rng,
                        showPicture = level == Level.ROOKIE,
                    )
                }
            }
        }
        GameMode.CLOCK -> {
            val hdg = rnd.nextInt(0, 36) * 10.0
            return when (rnd.nextInt(3)) {
                0 -> { val clock = rnd.nextInt(1, 13); Round("Heading ${b3(hdg)}. Bandit at your $clock o'clock.", "Bearing to the bandit?", ownHdg = hdg, answerBrg = wrap360(hdg + clock * 30.0), singleField = true, showPicture = false) }
                1 -> { val h = rnd.nextInt(0, 360).toDouble(); Round("Reciprocal of ${b3(h)}?", "Enter the reciprocal heading", answerBrg = wrap360(h + 180), singleField = true, showPicture = false) }
                else -> { val brg = rnd.nextInt(0, 360).toDouble(); val left = rnd.nextBoolean(); Round("Threat bears ${b3(brg)}. Beam it — put it on your ${if (left) "LEFT" else "RIGHT"} 3/9 line.", "Heading to fly?", answerBrg = wrap360(if (left) brg + 90 else brg - 90), singleField = true, showPicture = false) }
            }
        }
        GameMode.THEATER -> {
            val th = theater!!
            val size = th.sizeFt
            repeat(60) {
                val bull = fields.random(rnd)
                val brg = rnd.nextInt(0, 360).toDouble(); val rng = rnd.nextInt(10, level.maxRange).toDouble()
                val (dn, de) = polar(brg, rng * FT_PER_NM)
                val tx = bull.x + dn; val ty = bull.y + de
                if (tx in size * 0.03..size * 0.97 && ty in size * 0.03..size * 0.97) {
                    return Round("BULLSEYE is ${bull.name}${bull.icao?.let { " ($it)" } ?: ""}.", "Target: BULLSEYE ${b3(brg)}/${rng.toInt()} — tap it on the map", bx = bull.x, by = bull.y, tx = tx, ty = ty, answerBrg = brg, answerRng = rng, bullseyeName = bull.icao ?: bull.name)
                }
            }
            return makeRound(GameMode.PLOT, level, rnd, fields, theater)
        }
    }
}

/** Linear fall-off: 1.0 inside [full], 0.0 at [zero]. */
private fun falloff(err: Double, full: Double, zero: Double) = if (err <= full) 1.0 else (1 - (err - full) / (zero - full)).coerceIn(0.0, 1.0)

private fun scoreKeypad(r: Round, level: Level, brg: Double?, rng: Double?, timeFrac: Float): Result {
    // Generous by design: this trains orientation, not exact mental arithmetic.
    val bonus = 0.85 + 0.15 * timeFrac
    val brgFull = level.brgTol / 2
    if (r.singleField) {
        val e = brg?.let { angDiff(it, r.answerBrg!!) } ?: 180.0
        val p = (100 * falloff(e, brgFull, level.brgTol * 2) * bonus).roundToInt()
        return Result(p, "Answer ${b3(r.answerBrg!!)} · yours ${brg?.let { b3(it) } ?: "—"} · error ${e.roundToInt()}° (full marks within ±${brgFull.roundToInt()}°)")
    }
    val eb = brg?.let { angDiff(it, r.answerBrg!!) } ?: 180.0
    val er = rng?.let { abs(it - r.answerRng!!) } ?: r.answerRng!!
    val rngFull = maxOf(5.0, r.answerRng!! * level.rngTol / 2)
    val p = ((60 * falloff(eb, brgFull, level.brgTol * 2) + 40 * falloff(er, rngFull, rngFull * 3)) * bonus).roundToInt()
    return Result(p, "Answer ${b3(r.answerBrg!!)}/${r.answerRng!!.roundToInt()} · yours ${brg?.let { b3(it) } ?: "—"}/${rng?.roundToInt() ?: "—"} · Δ ${eb.roundToInt()}° / ${Fmt.num(er, 1)} nm (full marks within ±${brgFull.roundToInt()}° / ±${rngFull.roundToInt()} nm)")
}

private fun scoreTap(r: Round, level: Level, ux: Double, uy: Double, timeFrac: Float, unitNm: Double): Result {
    // Tapping is imprecise by nature: anything inside the "close enough" circle scores full marks,
    // then points fall off gently out to 4x that radius.
    val bonus = 0.85 + 0.15 * timeFrac
    val errNm = hypot(ux - r.tx, uy - r.ty) / unitNm
    val mult = when (level) { Level.ROOKIE -> 1.5; Level.WINGMAN -> 1.25; Level.LEAD -> 1.0 }
    val minFull = if (unitNm > 1.0) 12.0 else 8.0
    val full = maxOf(minFull, r.answerRng!! * 0.25) * mult
    val zero = full * 3.5
    val accuracy = if (errNm <= full) 1.0 else (1 - (errNm - full) / (zero - full)).coerceIn(0.0, 1.0)
    val p = (100 * accuracy * bonus).roundToInt()
    val (ub, ur) = brgRng(r.bx, r.by, ux, uy)
    return Result(p, "You plotted ${b3(ub)}/${(ur / unitNm).roundToInt()} · miss ${Fmt.num(errNm, 1)} nm" + if (errNm <= full) " (inside the ${Fmt.num(full, 0)} nm close-enough zone)" else " (close-enough zone ${Fmt.num(full, 0)} nm)", ux, uy, full)
}

// ------------------------------------------------------------------ screen

@Composable
fun BullseyeScreen(nav: NavHostController) {
    var mode by remember { mutableStateOf<GameMode?>(null) }
    var level by remember { mutableStateOf(Level.values()[Repo.getInt("bullseye_level", 0).coerceIn(0, 2)]) }
    val m = mode
    if (m == null) {
        GameMenu(level, { level = it; Repo.putInt("bullseye_level", it.ordinal) }, onBack = { nav.popBackStack() }) { mode = it }
    } else {
        GamePlay(m, level) { mode = null }
    }
}

@Composable
private fun GameMenu(level: Level, onLevel: (Level) -> Unit, onBack: () -> Unit, onStart: (GameMode) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        BmsTopBar("Bullseye Trainer", "Orientation · heading · bearing · SA", onBack = onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            ContentColumn(maxWidth = 1200.dp) {
                SectionCard("Difficulty", accent = Hud.Cyan) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Level.values().forEach { l -> HudChip(l.title, l == level) { onLevel(l) } } }
                    Spacer(Modifier.height(6.dp))
                    Text("Max range ${level.maxRange} nm · ${level.seconds}s per call · full marks within ±${(level.brgTol / 2).toInt()}°" + if (!level.labels) " · no ring labels, heading-up" else "", style = MaterialTheme.typography.bodySmall, color = Hud.TextDim)
                }
                com.bmscompanion.app.ui.components.Masonry(minColumn = 440.dp) {
                GameMode.values().forEach { gm ->
                    val best = Repo.getInt("bullseye_best_${gm.name}_${level.name}", 0)
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Hud.Surface).border(1.dp, Hud.Outline, RoundedCornerShape(16.dp))
                            .clickable { onStart(gm) }.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(54.dp).clip(CircleShape).background(Color(0xFF06140C)).border(1.5.dp, Hud.Green, CircleShape), contentAlignment = Alignment.Center) {
                            MiniScopeIcon(gm)
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(gm.title, style = MaterialTheme.typography.titleMedium)
                            Text(gm.blurb, style = MaterialTheme.typography.bodySmall, color = Hud.TextDim)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("BEST", style = LocalExtra.current.overline, color = Hud.TextFaint)
                            Text(if (best > 0) "$best" else "—", style = LocalExtra.current.mono, color = Hud.Amber)
                        }
                    }
                }
                }
                com.bmscompanion.app.ui.components.CollapsibleCard("Bullseye basics", accent = Hud.Green, preview = "A bullseye is a pre-briefed reference point. Positions are called as BEARING/RANGE FROM the bullseye…") {
                    Paragraph("A bullseye is a pre-briefed reference point. Positions are called as BEARING/RANGE FROM the bullseye (e.g. “BULLSEYE 270/35” = 35 nm west of it). BRAA is bearing/range/altitude/aspect FROM YOUR JET. In BMS bearings are true and magnetic variation is 0°, so the HSD, HUD and calls agree.")
                    Spacer(Modifier.height(6.dp))
                    Paragraph("Quick math: reciprocal = ±180°. Beam = threat bearing ±90°. Clock → bearing = heading + clock×30°. For BRAA from two bullseye positions, picture both points and read the vector between them.", Hud.TextDim)
                }
            }
        }
    }
}

@Composable
private fun MiniScopeIcon(gm: GameMode) {
    Canvas(Modifier.size(36.dp)) {
        val c = center; val r = size.minDimension / 2
        drawCircle(Hud.Green.copy(alpha = 0.6f), r * 0.95f, c, style = Stroke(2f))
        drawCircle(Hud.Green.copy(alpha = 0.4f), r * 0.5f, c, style = Stroke(1.5f))
        when (gm) {
            GameMode.CLOCK -> for (i in 0 until 12) { val a = Math.toRadians(i * 30.0); drawLine(Hud.Green, Offset(c.x + (r * 0.75f * sin(a)).toFloat(), c.y - (r * 0.75f * cos(a)).toFloat()), Offset(c.x + (r * 0.95f * sin(a)).toFloat(), c.y - (r * 0.95f * cos(a)).toFloat()), 2f) }
            else -> { drawCircle(Hud.Cyan, 3f, c); drawCircle(Hud.Red, 4f, Offset(c.x + r * 0.5f, c.y - r * 0.4f)) }
        }
    }
}

@Composable
private fun GamePlay(mode: GameMode, level: Level, onExit: () -> Unit) {
    val theaterId = Repo.selectedTheater.value
    val theater by produceState<Theater?>(null, theaterId) { value = Repo.theater(theaterId) }
    val fields by produceState<List<Airport>?>(null, theater) {
        value = theater?.let { t -> Repo.airportSet(t.airportSet).airports.filter { it.runways.isNotEmpty() && airportKind(it) != "Carrier" } }
    }
    if (mode == GameMode.THEATER && (theater == null || fields == null)) { LoadingBox(); return }
    val totalRounds = 10
    val rnd = remember { Random(System.nanoTime()) }
    var roundIdx by remember { mutableIntStateOf(0) }
    var round by remember { mutableStateOf(makeRound(mode, level, rnd, fields.orEmpty(), theater)) }
    var result by remember { mutableStateOf<Result?>(null) }
    val results = remember { mutableStateListOf<Int>() }
    var timeLeft by remember { mutableFloatStateOf(level.seconds.toFloat()) }
    var finished by remember { mutableStateOf(false) }
    var brgText by remember { mutableStateOf("") }
    var rngText by remember { mutableStateOf("") }
    var activeField by remember { mutableIntStateOf(0) }
    var streak by remember { mutableIntStateOf(0) }

    fun submit(res: Result) {
        if (result != null) return
        result = res; results += res.points
        streak = if (res.points >= 70) streak + 1 else 0
    }
    fun submitKeypad() = submit(scoreKeypad(round, level, brgText.toDoubleOrNull(), rngText.toDoubleOrNull(), timeLeft / level.seconds))
    fun next() {
        if (roundIdx + 1 >= totalRounds) {
            finished = true
            val total = results.sum()
            val key = "bullseye_best_${mode.name}_${level.name}"
            if (total > Repo.getInt(key, 0)) Repo.putInt(key, total)
            if (total > Repo.getInt("bullseye_best", 0)) Repo.putInt("bullseye_best", total)
            return
        }
        roundIdx++; round = makeRound(mode, level, rnd, fields.orEmpty(), theater); result = null
        brgText = ""; rngText = ""; activeField = 0; timeLeft = level.seconds.toFloat()
    }

    LaunchedEffect(roundIdx, finished) {
        while (!finished && result == null && timeLeft > 0) { delay(100); timeLeft -= 0.1f }
        if (!finished && result == null && timeLeft <= 0) {
            submit(if (mode == GameMode.PLOT || mode == GameMode.THEATER) Result(0, "Time! Answer was ${b3(round.answerBrg!!)}/${round.answerRng!!.roundToInt()}") else scoreKeypad(round, level, brgText.toDoubleOrNull(), rngText.toDoubleOrNull(), 0f))
        }
    }

    Column(Modifier.fillMaxSize()) {
        BmsTopBar(mode.title, "${level.title} · call ${roundIdx + 1}/$totalRounds · score ${results.sum()}" + if (streak >= 2) " · 🔥$streak" else "", onBack = onExit)
        if (finished) { GameOver(mode, level, results, onRetry = { roundIdx = 0; results.clear(); finished = false; streak = 0; round = makeRound(mode, level, rnd, fields.orEmpty(), theater); result = null; timeLeft = level.seconds.toFloat(); brgText = ""; rngText = "" }, onExit = onExit); return@Column }
        LinearProgressIndicator(
            progress = { (timeLeft / level.seconds).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(4.dp),
            color = if (timeLeft < level.seconds * 0.3f) Hud.Red else Hud.Green, trackColor = Hud.Surface2,
        )
        val landscape = LocalConfiguration.current.screenWidthDp > LocalConfiguration.current.screenHeightDp
        val scope: @Composable (Modifier) -> Unit = { mod ->
            when {
                mode == GameMode.THEATER -> TheaterBoard(theater!!, round, level, result, mod) { x, y -> submit(scoreTap(round, level, x, y, timeLeft / level.seconds, FT_PER_NM)) }
                round.showPicture -> HsdScope(round, level, result, tapEnabled = mode == GameMode.PLOT, headingUp = !level.labels && mode == GameMode.WHERE, modifier = mod) { x, y ->
                    submit(scoreTap(round, level, x, y, timeLeft / level.seconds, 1.0))
                }
                else -> DrillCard(round, result, mod)
            }
        }
        val controls: @Composable (Modifier) -> Unit = { mod ->
            Column(mod.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                CallBox(round)
                val r = result
                if (r != null) {
                    ResultBox(r) { next() }
                } else if (mode != GameMode.PLOT && mode != GameMode.THEATER) {
                    Keypad(
                        brgText, rngText, activeField, round.singleField,
                        onField = { activeField = it },
                        onDigit = { d ->
                            if (activeField == 0) { if (brgText.length < 3) brgText += d; if (brgText.length == 3 && !round.singleField) activeField = 1 }
                            else if (rngText.length < 3) rngText += d
                        },
                        onClear = { if (activeField == 0) brgText = "" else rngText = "" },
                        onEnter = { if (brgText.isNotEmpty() && (round.singleField || rngText.isNotEmpty())) submitKeypad() else if (brgText.isNotEmpty()) activeField = 1 },
                    )
                }
            }
        }
        if (landscape) {
            Row(Modifier.fillMaxSize()) {
                scope(Modifier.weight(1f).fillMaxHeight().padding(8.dp))
                Box(Modifier.width(380.dp).fillMaxHeight().verticalScroll(rememberScrollState())) { controls(Modifier.fillMaxWidth()) }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                // The scope draws centred on its smaller dimension, so filling the box never clips the bearing labels
                // (forcing a square from the height used to push 09/27 off-screen while the timer was running).
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    scope(Modifier.fillMaxSize().padding(6.dp))
                }
                controls(Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun CallBox(r: Round) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF06140C)).border(1.dp, Hud.Green.copy(alpha = 0.5f), RoundedCornerShape(14.dp)).padding(14.dp)) {
        Text(r.prompt, fontFamily = Mono, color = Hud.Green, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        if (r.subPrompt.isNotBlank()) Text(r.subPrompt, color = Hud.TextDim, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun ResultBox(r: Result, onNext: () -> Unit) {
    val color = when { r.points >= 80 -> Hud.Green; r.points >= 50 -> Hud.Amber; else -> Hud.Red }
    val anim by animateFloatAsState(r.points / 100f, label = "pts")
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(color.copy(alpha = 0.10f)).border(1.dp, color, RoundedCornerShape(14.dp)).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("+${r.points}", fontFamily = Mono, fontSize = 30.sp, color = color, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(12.dp))
            Text(when { r.points >= 90 -> "SHACK!"; r.points >= 70 -> "Good call"; r.points >= 40 -> "Close — refine"; else -> "Miss" }, style = MaterialTheme.typography.titleMedium, color = color)
        }
        LinearProgressIndicator(progress = { anim }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).height(6.dp).clip(RoundedCornerShape(3.dp)), color = color, trackColor = Hud.Surface3)
        Text(r.detail, fontFamily = Mono, fontSize = 13.sp, color = Hud.Text)
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Hud.Amber).clickable(onClick = onNext).padding(14.dp), contentAlignment = Alignment.Center) {
            Text("NEXT CALL ›", color = Hud.Bg, fontWeight = FontWeight.Bold)
        }
    }
}

/** UFC/ICP-style entry pad. */
@Composable
private fun Keypad(brg: String, rng: String, active: Int, single: Boolean, onField: (Int) -> Unit, onDigit: (String) -> Unit, onClear: () -> Unit, onEnter: () -> Unit) {
    Column(Modifier.fillMaxWidth().widthIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DedField(if (single) "HDG/BRG" else "BRG", brg, "°", active == 0, Modifier.weight(1f)) { onField(0) }
            if (!single) DedField("RNG", rng, "NM", active == 1, Modifier.weight(1f)) { onField(1) }
        }
        listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("RCL", "0", "ENTR")).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { k ->
                    val special = k == "RCL" || k == "ENTR"
                    Box(
                        Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(10.dp))
                            .background(if (k == "ENTR") Hud.Green.copy(alpha = 0.25f) else Hud.Surface3)
                            .border(1.dp, if (k == "ENTR") Hud.Green else Hud.Outline, RoundedCornerShape(10.dp))
                            .clickable { when (k) { "RCL" -> onClear(); "ENTR" -> onEnter(); else -> onDigit(k) } },
                        contentAlignment = Alignment.Center,
                    ) { Text(if (k == "RCL") "CLR" else k, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = if (special) 14.sp else 20.sp, color = if (k == "ENTR") Hud.Green else Hud.Text) }
                }
            }
        }
    }
}

@Composable
private fun DedField(label: String, value: String, unit: String, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xFF0B1A0F)).border(if (active) 2.dp else 1.dp, if (active) Hud.Green else Hud.Outline, RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontFamily = Mono, color = Hud.TextDim, fontSize = 12.sp)
        Spacer(Modifier.weight(1f))
        Text(if (active) "*${value.padEnd(3, ' ')}*" else value.ifEmpty { "---" }, fontFamily = Mono, color = Hud.Green, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(" $unit", fontFamily = Mono, color = Hud.TextDim, fontSize = 11.sp)
    }
}

@Composable
private fun DrillCard(r: Round, result: Result?, modifier: Modifier) {
    val tm = rememberTextMeasurer()
    Canvas(modifier.clip(RoundedCornerShape(16.dp)).background(Color(0xFF040A06))) {
        val c = center; val rad = size.minDimension * 0.42f
        // compass rose rotated heading-up
        val hdg = r.ownHdg.toFloat()
        rotate(-hdg, c) {
            drawCircle(Hud.Green.copy(alpha = 0.5f), rad, c, style = Stroke(2f))
            for (d in 0 until 360 step 10) {
                val a = Math.toRadians(d.toDouble()); val len = if (d % 30 == 0) 18f else 8f
                drawLine(Hud.Green.copy(alpha = if (d % 30 == 0) 0.9f else 0.4f), Offset(c.x + ((rad - len) * sin(a)).toFloat(), c.y - ((rad - len) * cos(a)).toFloat()), Offset(c.x + (rad * sin(a)).toFloat(), c.y - (rad * cos(a)).toFloat()), 2f)
            }
            for (d in 0 until 360 step 30) {
                val a = Math.toRadians(d.toDouble()); val p = Offset(c.x + ((rad - 38f) * sin(a)).toFloat(), c.y - ((rad - 38f) * cos(a)).toFloat())
                val label = when (d) { 0 -> "N"; 90 -> "E"; 180 -> "S"; 270 -> "W"; else -> "${d / 10}" }
                rotate(hdg, p) { centeredText(tm, label, p, if (d % 90 == 0) Hud.Amber else Hud.Green, 14f) }
            }
        }
        drawOwnship(c, 0f, Hud.Cyan, size.minDimension * 0.06f)
        centeredText(tm, "HDG ${b3(r.ownHdg)}", Offset(c.x, c.y + rad * 0.35f), Hud.Cyan, 13f)
        result?.let {
            val brg = r.answerBrg ?: return@let
            val rel = Math.toRadians(brg - r.ownHdg)
            drawLine(Hud.Amber, c, Offset(c.x + (rad * sin(rel)).toFloat(), c.y - (rad * cos(rel)).toFloat()), 4f)
        }
    }
}

@Composable
private fun HsdScope(r: Round, level: Level, result: Result?, tapEnabled: Boolean, headingUp: Boolean, modifier: Modifier, onTap: (Double, Double) -> Unit) {
    val tm = rememberTextMeasurer()
    val scopeRange = level.maxRange * 1.1
    BoxWithConstraints(modifier) {
        Canvas(
            Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)).background(Color(0xFF02070A))
                .pointerInput(r, tapEnabled, result) {
                    if (tapEnabled && result == null) detectTapGestures { p ->
                        val c = Offset(size.width / 2f, size.height / 2f); val rad = minOf(size.width, size.height) * 0.38f
                        val east = (p.x - c.x) / rad * scopeRange; val north = -(p.y - c.y) / rad * scopeRange
                        onTap(north, east)
                    }
                },
        ) {
            val c = center; val rad = size.minDimension * 0.38f
            val rot = if (headingUp) -r.ownHdg.toFloat() else 0f
            fun pt(n: Double, e: Double) = Offset(c.x + (e / scopeRange * rad).toFloat(), c.y - (n / scopeRange * rad).toFloat())
            rotate(rot, c) {
                // rings every 20 nm from bullseye
                var ring = 20
                while (ring <= scopeRange) {
                    drawCircle(Hud.Cyan.copy(alpha = 0.55f), (ring / scopeRange * rad).toFloat(), c, style = Stroke(2.5f))
                    if (level.labels) centeredText(tm, "$ring", Offset(c.x + 4f, c.y - (ring / scopeRange * rad).toFloat() + 10f), Hud.Cyan.copy(alpha = 0.7f), 10f)
                    ring += 20
                }
                // bearing spokes
                for (d in 0 until 360 step 30) {
                    val a = Math.toRadians(d.toDouble())
                    drawLine(Hud.Cyan.copy(alpha = 0.12f), c, Offset(c.x + (rad * sin(a)).toFloat(), c.y - (rad * cos(a)).toFloat()), 1f)
                    val lm = size.minDimension * 0.06f
                    val lp = Offset(c.x + ((rad + lm) * sin(a)).toFloat(), c.y - ((rad + lm) * cos(a)).toFloat())
                    rotate(-rot, lp) { centeredText(tm, if (d == 0) "N" else String.format("%02d", d / 10), lp, if (d == 0) Hud.Amber else Hud.TextDim, 11f) }
                }
                // bullseye symbol
                drawCircle(Hud.Cyan, 14f, c, style = Stroke(2.5f)); drawCircle(Hud.Cyan, 5f, c)
                // ownship
                if (r.ox != 0.0 || r.oy != 0.0) drawOwnship(pt(r.ox, r.oy), r.ownHdg.toFloat(), Hud.Green, size.minDimension * 0.035f)
                // target (BRAA picture)
                if ((r.tx != 0.0 || r.ty != 0.0) && !tapEnabled) drawHostile(pt(r.tx, r.ty))
            }
            if (headingUp) centeredText(tm, "HDG UP ${b3(r.ownHdg)}", Offset(c.x, 16f), Hud.Amber, 12f)
            // reveal
            result?.let { res ->
                rotate(rot, c) {
                    when {
                        res.userX != null -> {
                            val truth = pt(r.tx, r.ty); val user = pt(res.userX, res.userY!!)
                            res.zoneNm?.let { z -> drawCircle(Hud.Green.copy(alpha = 0.18f), (z / scopeRange * rad).toFloat(), truth); drawCircle(Hud.Green.copy(alpha = 0.7f), (z / scopeRange * rad).toFloat(), truth, style = Stroke(2f)) }
                            drawLine(Hud.Amber, c, truth, 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
                            drawLine(Hud.Red, user, truth, 2f)
                            drawCircle(Hud.Magenta, 9f, user, style = Stroke(3f))
                            drawHostile(truth)
                        }
                        r.answerRng != null && (r.ox != 0.0 || r.oy != 0.0) && (r.tx != 0.0 || r.ty != 0.0) -> drawLine(Hud.Amber, pt(r.ox, r.oy), pt(r.tx, r.ty), 3f)
                        r.answerRng != null && (r.ox != 0.0 || r.oy != 0.0) -> drawLine(Hud.Amber, c, pt(r.ox, r.oy), 3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
                    }
                }
            }
            centeredText(tm, "${scopeRange.roundToInt()} NM", Offset(size.width - 60f, size.height - 20f), Hud.TextFaint, 10f)
        }
    }
}

@Composable
private fun TheaterBoard(th: Theater, r: Round, level: Level, result: Result?, modifier: Modifier, onTap: (Double, Double) -> Unit) {
    val tm = rememberTextMeasurer()
    val state = rememberMapState()
    // Rings every 10 nm on Rookie (small area), 20 nm otherwise; out to the level's max call range.
    val step = if (level.maxRange <= 50) 10 else 20
    val outerNm = ((level.maxRange + step - 1) / step) * step
    // Zoom so the whole ring set just fits the smaller screen dimension, centred on the bullseye.
    LaunchedEffect(r) { state.flyTo(r.bx, r.by, (th.sizeFt / (2.0 * (outerNm + step * 0.8) * FT_PER_NM)).toFloat()) }
    Box(modifier.clip(RoundedCornerShape(16.dp))) {
        TheaterMap(th.map, th.sizeFt, Modifier.fillMaxSize(), state, onTap = { x, y, _ -> if (result == null) onTap(x, y) }) { pr ->
            val b = pr.toScreen(r.bx, r.by)
            val ringLabel = TextStyle(color = Hud.Cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold, shadow = androidx.compose.ui.graphics.Shadow(Color.Black, blurRadius = 6f))
            var ringNm = step
            while (ringNm <= outerNm) {
                val rp = pr.pxPerNm * ringNm
                drawCircle(Color.Black.copy(alpha = 0.55f), rp, b, style = Stroke(5f))
                drawCircle(Hud.Cyan.copy(alpha = if (ringNm % 20 == 0) 0.95f else 0.7f), rp, b, style = Stroke(if (ringNm % 20 == 0) 2.8f else 2f))
                // range label on the 045° radial so it never sits on a cardinal spoke
                val lp = Offset(b.x + rp * 0.7071f + 4f, b.y - rp * 0.7071f - 16f)
                safeText(tm, "$ringNm", lp, ringLabel)
                ringNm += step
            }
            // bearing spokes + labels from the bullseye (true bearings)
            val outer = pr.pxPerNm * outerNm
            val labelStyle = TextStyle(color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, shadow = androidx.compose.ui.graphics.Shadow(Color.Black, blurRadius = 5f))
            for (d in 0 until 360 step 30) {
                val a = Math.toRadians(d.toDouble())
                val tip = Offset(b.x + (outer * sin(a)).toFloat(), b.y - (outer * cos(a)).toFloat())
                drawLine(Hud.Cyan.copy(alpha = 0.45f), b, tip, 1.5f)
                val lp = Offset(b.x + ((outer + 18f) * sin(a)).toFloat(), b.y - ((outer + 18f) * cos(a)).toFloat())
                val label = if (d == 0) "N" else String.format("%03d", d)
                val lay = tm.measure(label, labelStyle)
                val tl = Offset(lp.x - lay.size.width / 2f, lp.y - lay.size.height / 2f)
                if (tl.x > -lay.size.width && tl.y > -lay.size.height && tl.x < size.width && tl.y < size.height) drawText(lay, topLeft = tl)
            }
            drawCircle(Hud.Cyan, 14f, b, style = Stroke(3f)); drawCircle(Hud.Cyan, 5f, b)
            r.bullseyeName?.let { safeText(tm, it, b + Offset(16f, -24f), TextStyle(color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, shadow = androidx.compose.ui.graphics.Shadow(Color.Black, blurRadius = 5f))) }
            result?.userX?.let { ux ->
                val t = pr.toScreen(r.tx, r.ty); val u = pr.toScreen(ux, result.userY!!)
                result.zoneNm?.let { z -> drawCircle(Hud.Green.copy(alpha = 0.18f), pr.pxPerNm * z.toFloat(), t); drawCircle(Hud.Green.copy(alpha = 0.7f), pr.pxPerNm * z.toFloat(), t, style = Stroke(2f)) }
                drawLine(Hud.Amber, b, t, 2.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f)))
                drawLine(Hud.Red, u, t, 2.5f)
                drawCircle(Hud.Magenta, 10f, u, style = Stroke(3f))
                drawHostile(t)
            }
        }
        Text("$step nm rings", Modifier.align(Alignment.TopEnd).padding(8.dp).clip(RoundedCornerShape(6.dp)).background(Hud.Bg.copy(alpha = 0.7f)).padding(horizontal = 6.dp, vertical = 2.dp), fontSize = 10.sp, color = Hud.TextDim)
    }
}

@Composable
private fun GameOver(mode: GameMode, level: Level, results: List<Int>, onRetry: () -> Unit, onExit: () -> Unit) {
    val total = results.sum()
    val best = Repo.getInt("bullseye_best_${mode.name}_${level.name}", 0)
    val grade = when { total >= 850 -> "Weapons School"; total >= 700 -> "Flight Lead"; total >= 500 -> "Wingman"; total >= 300 -> "Student"; else -> "Back to the sim" }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("MISSION COMPLETE", style = LocalExtra.current.overline, color = Hud.Green)
        Text("$total", fontFamily = Mono, fontSize = 64.sp, fontWeight = FontWeight.Bold, color = Hud.Amber)
        Text(grade, style = MaterialTheme.typography.headlineSmall)
        Text(if (total >= best && total > 0) "New best for ${mode.title} (${level.title})!" else "Best: $best", color = Hud.TextDim)
        Row(Modifier.fillMaxWidth().widthIn(max = 480.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
            results.forEach { p ->
                Box(Modifier.weight(1f).height((12 + p).dp).clip(RoundedCornerShape(4.dp)).background(if (p >= 70) Hud.Green else if (p >= 40) Hud.Amber else Hud.Red))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.clip(RoundedCornerShape(12.dp)).background(Hud.Amber).clickable(onClick = onRetry).padding(horizontal = 28.dp, vertical = 14.dp)) { Text("FLY AGAIN", color = Hud.Bg, fontWeight = FontWeight.Bold) }
            Box(Modifier.clip(RoundedCornerShape(12.dp)).border(1.dp, Hud.Outline, RoundedCornerShape(12.dp)).clickable(onClick = onExit).padding(horizontal = 28.dp, vertical = 14.dp)) { Text("MENU", color = Hud.Text) }
        }
        SectionCard("How to improve", accent = Hud.Cyan) { Paragraph(mode.how) }
    }
}

// ------------------------------------------------------------------ drawing helpers

private fun DrawScope.drawOwnship(p: Offset, hdg: Float, color: Color, s: Float) {
    rotate(hdg, p) {
        val path = Path().apply {
            moveTo(p.x, p.y - s * 1.3f); lineTo(p.x + s * 0.25f, p.y - s * 0.2f); lineTo(p.x + s, p.y + s * 0.3f); lineTo(p.x + s * 0.25f, p.y + s * 0.2f)
            lineTo(p.x + s * 0.2f, p.y + s * 0.8f); lineTo(p.x + s * 0.5f, p.y + s * 1.1f); lineTo(p.x - s * 0.5f, p.y + s * 1.1f); lineTo(p.x - s * 0.2f, p.y + s * 0.8f)
            lineTo(p.x - s * 0.25f, p.y + s * 0.2f); lineTo(p.x - s, p.y + s * 0.3f); lineTo(p.x - s * 0.25f, p.y - s * 0.2f); close()
        }
        drawPath(path, color)
        drawLine(color.copy(alpha = 0.7f), Offset(p.x, p.y - s * 1.3f), Offset(p.x, p.y - s * 3.2f), 2f)
    }
}

private fun DrawScope.drawHostile(p: Offset) {
    val s = 12f
    val path = Path().apply { moveTo(p.x - s, p.y + s * 0.4f); lineTo(p.x, p.y - s * 0.8f); lineTo(p.x + s, p.y + s * 0.4f) }
    drawPath(path, Hud.Red, style = Stroke(4f))
    drawCircle(Hud.Red, 4f, p)
}

private fun DrawScope.centeredText(tm: TextMeasurer, s: String, p: Offset, color: Color, sizeSp: Float) {
    val layout = tm.measure(s, TextStyle(color = color, fontSize = sizeSp.sp, fontFamily = Mono, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center))
    drawText(layout, topLeft = Offset(p.x - layout.size.width / 2f, p.y - layout.size.height / 2f))
}
