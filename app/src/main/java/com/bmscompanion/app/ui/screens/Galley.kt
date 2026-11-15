package com.bmscompanion.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.bmscompanion.app.ui.components.BmsTopBar
import com.bmscompanion.app.ui.components.RwrBadge
import com.bmscompanion.app.ui.components.RwrMark
import com.bmscompanion.app.ui.components.SectionCard
import com.bmscompanion.app.ui.theme.Hud
import com.bmscompanion.app.ui.theme.LocalExtra
import kotlinx.coroutines.delay

/**
 * The galley: an easter egg for the pilots who asked where the microwave is for the long flights (and who until now
 * held their sausages on a stick in front of the radome). Found by searching the RWR emitters or the app for
 * "microwave", "galley", "sausage" or "popcorn" ([Galley.WORDS]): the MWO-16 shows up as an emitter, MW with a hat
 * (it is airborne: it is in your jet), and opens this page — a microwave with a working timer, its turntable turning,
 * the display in the DED's green, and a ding.
 */
object Galley {
    val WORDS = listOf("microwave", "galley", "sausage", "popcorn", "mwo")

    /** True for a search that asks for the galley. */
    fun asked(q: String): Boolean {
        val s = q.trim().lowercase()
        return s.length >= 3 && WORDS.any { it.startsWith(s) || s.startsWith(it) }
    }

    /** The joke emitter, as the RWR emitters tab lists it. */
    const val SYSTEM = "MWO-16 galley microwave"
    const val TYPE = "AIRCRAFT"
    const val DETAIL = "Own ship · 2.45 GHz (E band), 800 W · Block 70 long-range option"
    const val NOTE = "Always at 12 o'clock, inner ring. New guy tone when the popcorn starts."

    class Dish(val name: String, val seconds: Int, val line: String)

    val DISHES = listOf(
        Dish("Sausage on a stick", 90, "No more holding it out in front of the radome."),
        Dish("Popcorn", 150, "Chaff and flares look the same from the outside. Pick the right button."),
        Dish("Coffee reheat", 45, "For the second hour of the CAP."),
        Dish("Long-haul lunch", 240, "Tanker's on station, lunch is on station."),
        Dish("Defrost", 60, "Everything freezes at FL450."),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GalleyScreen(nav: NavHostController) {
    var dish by remember { mutableStateOf(Galley.DISHES[0]) }
    var left by remember { mutableIntStateOf(dish.seconds) }
    var running by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    var plate by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(running) {
        while (running && left > 0) {
            repeat(10) { delay(100); plate = (plate + 6f) % 360f }
            left -= 1
        }
        if (running && left <= 0) { running = false; done = true }
    }
    // the ding: the display flashes it for a few seconds
    var flash by remember { mutableStateOf(false) }
    LaunchedEffect(done) { if (done) repeat(10) { flash = !flash; delay(400) }; flash = false }
    Column(Modifier.fillMaxSize()) {
        BmsTopBar("Galley", "MWO-16 microwave · long-flight option", onBack = { nav.popBackStack() })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // the oven: its door with the turntable behind the glass, and the panel with the display
                Row(
                    Modifier.fillMaxWidth().aspectRatio(1.75f).clip(RoundedCornerShape(14.dp))
                        .background(Brush.verticalGradient(listOf(Color(0xFF3A4048), Color(0xFF23272D))))
                        .border(1.dp, Color(0xFF55606C), RoundedCornerShape(14.dp)).padding(12.dp),
                ) {
                    Box(
                        Modifier.weight(1f).fillMaxSize().clip(RoundedCornerShape(8.dp))
                            .background(if (running) Color(0xFF3B3216) else Color(0xFF0D1014)),
                    ) {
                        Canvas(Modifier.fillMaxSize()) {
                            val c = Offset(size.width / 2, size.height * 0.68f)
                            val r = size.minDimension * 0.42f
                            // the glass's dots
                            val step = 9.dp.toPx()
                            var y = step / 2
                            while (y < size.height) {
                                var x = step / 2
                                while (x < size.width) { drawCircle(Color.White.copy(alpha = 0.035f), 1.1.dp.toPx(), Offset(x, y)); x += step }
                                y += step
                            }
                            if (running) drawCircle(Color(0x33FFD27A), size.maxDimension * 0.6f, c)
                            // the turntable, turning, and on it whatever is cooking: a sausage on its stick
                            drawOval(Color(0xFF8FA0AE).copy(alpha = 0.55f), Offset(c.x - r, c.y - r * 0.28f), Size(2 * r, r * 0.56f))
                            rotate(plate, c) {
                                drawLine(Color(0xFFB98A5A), Offset(c.x - r * 0.75f, c.y + r * 0.05f), Offset(c.x + r * 0.15f, c.y - r * 0.05f), 2.dp.toPx(), StrokeCap.Round)
                                drawRoundRect(
                                    Color(0xFFB0473A), Offset(c.x + r * 0.05f, c.y - r * 0.16f), Size(r * 0.6f, r * 0.2f),
                                    CornerRadius(r * 0.1f), alpha = if (done) 1f else 0.92f,
                                )
                            }
                            if (done) drawLine(Color(0x66FFFFFF), Offset(c.x + r * 0.3f, c.y - r * 0.3f), Offset(c.x + r * 0.25f, c.y - r * 0.6f), 1.5.dp.toPx(), StrokeCap.Round)
                            drawRoundRect(Color.White.copy(alpha = 0.08f), Offset.Zero, size, CornerRadius(8.dp.toPx()), style = Stroke(1.dp.toPx()))
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.width(132.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        // the display, in the DED's green
                        Box(
                            Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF07130C))
                                .border(1.dp, Color(0xFF1D3A27), RoundedCornerShape(6.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            val t = if (done && flash) "DING" else if (done) "READY" else "%d:%02d".format(left / 60, left % 60)
                            Text(t, style = LocalExtra.current.mono.copy(fontSize = 24.sp), color = Color(0xFF5BE38A))
                        }
                        RwrBadge("MW", 40.dp, Hud.Green, RwrMark.AIR)
                        Text(if (running) "Cooking · RWR: MW 12 o'clock" else if (done) "Snack ready" else "Door closed · standby", fontSize = 11.sp, color = Color(0xFFB5C0CA), lineHeight = 14.sp)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GalleyKey(if (running) "Stop" else "Start", primary = true, Modifier.weight(1f)) {
                        if (running) running = false
                        else { if (left <= 0 || done) left = dish.seconds; done = false; running = true }
                    }
                    GalleyKey("+30 s", false, Modifier.weight(1f)) { left += 30; done = false }
                    GalleyKey("Clear", false, Modifier.weight(1f)) { running = false; done = false; left = dish.seconds }
                }
                SectionCard("Presets") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Galley.DISHES.forEach { d ->
                            val on = d == dish
                            Text(
                                "${d.name} · ${d.seconds / 60}:${"%02d".format(d.seconds % 60)}",
                                Modifier.clip(RoundedCornerShape(8.dp)).background(if (on) Hud.Amber.copy(alpha = 0.18f) else Hud.Surface2)
                                    .border(1.dp, if (on) Hud.Amber.copy(alpha = 0.6f) else Hud.Outline, RoundedCornerShape(8.dp))
                                    .clickable { dish = d; running = false; done = false; left = d.seconds }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                color = if (on) Hud.Amber else Hud.Text, fontSize = 13.sp,
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(dish.line, style = MaterialTheme.typography.bodyMedium, color = Hud.TextDim)
                }
                SectionCard("Operating limits") {
                    Text(
                        "Do not operate in EMCON: the MWO-16 radiates at 2.45 GHz and your RWR will call it. Not to be used with " +
                            "the canopy open, under negative G or as a countermeasure. Popcorn is not chaff. Eject without the plate.",
                        style = MaterialTheme.typography.bodyMedium, color = Hud.TextDim,
                    )
                }
            }
        }
    }
}

@Composable
private fun GalleyKey(label: String, primary: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.height(44.dp).clip(RoundedCornerShape(10.dp)).background(if (primary) Hud.Green.copy(alpha = 0.2f) else Hud.Surface2)
            .border(1.dp, if (primary) Hud.Green.copy(alpha = 0.6f) else Hud.Outline, RoundedCornerShape(10.dp)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (primary) Hud.Green else Hud.Text, fontWeight = FontWeight.SemiBold) }
}
