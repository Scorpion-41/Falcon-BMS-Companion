package com.bmscompanion.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.bmscompanion.app.data.Airport
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.ui.components.BmsTopBar
import com.bmscompanion.app.ui.components.CollapsibleCard
import com.bmscompanion.app.ui.components.ContentColumn
import com.bmscompanion.app.ui.components.Fmt
import com.bmscompanion.app.ui.components.KeyValueRow
import com.bmscompanion.app.ui.components.Paragraph
import com.bmscompanion.app.ui.components.SearchField
import com.bmscompanion.app.ui.theme.Hud
import com.bmscompanion.app.ui.theme.LocalExtra
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

@Composable
private fun NumField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, suffix: String? = null) {
    OutlinedTextField(
        value = value, onValueChange = { v -> onChange(v.filter { it.isDigit() || it == '.' || it == '-' }) },
        label = { Text(label) }, singleLine = true, modifier = modifier,
        suffix = suffix?.let { { Text(it, color = Hud.TextDim) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        textStyle = LocalExtra.current.mono,
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Hud.Amber, unfocusedBorderColor = Hud.Outline, focusedLabelColor = Hud.Amber, cursorColor = Hud.Amber),
    )
}

private fun String.d() = toDoubleOrNull()

@Composable
private fun Result(label: String, value: String?) = KeyValueRow(label, value ?: "—", mono = true, valueColor = Hud.Green)

@Composable
fun ToolsScreen(nav: NavHostController) {
    Column(Modifier.fillMaxSize()) {
        BmsTopBar("Tools", "Flight planning calculators", onBack = { nav.popBackStack() })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            ContentColumn(maxWidth = 1400.dp) { com.bmscompanion.app.ui.components.Masonry(minColumn = 440.dp) {
                CollapsibleCard("Bearing & distance between airfields", initiallyOpen = true, accent = Hud.Cyan) { AirfieldBrg() }
                CollapsibleCard("Descent planner (glidepath)", accent = Hud.Green) { Descent() }
                CollapsibleCard("Wind triangle", accent = Hud.Cyan) { Wind() }
                CollapsibleCard("Turn performance", accent = Hud.Amber) { Turn() }
                CollapsibleCard("Fuel & bingo", accent = Hud.Red) { Fuel() }
                CollapsibleCard("Unit converter", accent = Hud.Blue) { Units() }
                CollapsibleCard("Speed: TAS / Mach / CAS (ISA)", accent = Hud.Magenta) { Speed() }
            } }
        }
    }
}

@Composable
private fun AirportPicker(label: String, list: List<Airport>, selected: Airport?, onPick: (Airport) -> Unit, modifier: Modifier) {
    var open by remember { mutableStateOf(false) }
    var q by remember { mutableStateOf("") }
    Box(modifier) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Hud.Surface2).clickable { open = true }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, fontSize = 11.sp, color = Hud.TextDim)
                Text(selected?.let { (it.icao?.let { i -> "$i · " } ?: "") + it.name } ?: "Select…", maxLines = 1)
            }
            Icon(Icons.Default.ArrowDropDown, null, tint = Hud.TextDim)
        }
        DropdownMenu(open, { open = false }, Modifier.heightIn(max = 420.dp)) {
            Box(Modifier.padding(8.dp).width(280.dp)) { SearchField(q, { q = it }, "Filter") }
            list.filter { q.isBlank() || it.name.contains(q, true) || (it.icao ?: "").contains(q, true) }.take(80).forEach { a ->
                DropdownMenuItem({ Text((a.icao?.let { "$it · " } ?: "") + a.name) }, { onPick(a); open = false; q = "" })
            }
        }
    }
}

@Composable
private fun AirfieldBrg() {
    val theaterId = Repo.selectedTheater.value
    val list by produceState<List<Airport>>(emptyList(), theaterId) {
        value = Repo.theater(theaterId)?.let { Repo.airportSet(it.airportSet).airports }.orEmpty()
    }
    var a by remember(theaterId) { mutableStateOf<Airport?>(null) }
    var b by remember(theaterId) { mutableStateOf<Airport?>(null) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Theater", color = Hud.TextDim, modifier = Modifier.weight(1f))
        TheaterPicker(theaterId) { Repo.setTheater(it) }
    }
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AirportPicker("From", list, a, { a = it }, Modifier.fillMaxWidth())
            AirportPicker("To", list, b, { b = it }, Modifier.fillMaxWidth())
        }
        IconButton({ val t = a; a = b; b = t }) { Icon(Icons.Default.SwapVert, "Swap", tint = Hud.Amber) }
    }
    val x = a; val y = b
    if (x != null && y != null) {
        val (brg, rng) = bearingRange(x.x, x.y, y.x, y.y)
        Spacer(Modifier.height(8.dp))
        Result("Bearing (true = mag)", Fmt.hdg(brg))
        Result("Distance", "${Fmt.num(rng, 1)} nm")
        Result("Reciprocal", Fmt.hdg(brg + 180))
        Result("ETE @ 360 kt / 480 kt", "${fmtTime(rng / 360)} / ${fmtTime(rng / 480)}")
        y.tacan?.let { Result("Destination TACAN", it.label) }
        y.freqs?.towerUhf?.let { Result("Destination tower", it) }
    }
}

private fun fmtTime(hours: Double): String { val m = (hours * 60).toInt(); return if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m" }

@Composable
private fun Descent() {
    var alt by rememberSaveable { mutableStateOf("25000") }
    var target by rememberSaveable { mutableStateOf("2000") }
    var gs by rememberSaveable { mutableStateOf("350") }
    var angle by rememberSaveable { mutableStateOf("3") }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumField("Altitude", alt, { alt = it }, Modifier.weight(1f), "ft"); NumField("Target alt", target, { target = it }, Modifier.weight(1f), "ft")
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumField("Ground speed", gs, { gs = it }, Modifier.weight(1f), "kt"); NumField("Path angle", angle, { angle = it }, Modifier.weight(1f), "°")
    }
    val dAlt = (alt.d() ?: 0.0) - (target.d() ?: 0.0)
    val ang = Math.toRadians(angle.d() ?: 3.0)
    val dist = dAlt / tan(ang) / 6076.12
    val vvi = (gs.d() ?: 0.0) * 101.27 * tan(ang)
    Spacer(Modifier.height(6.dp))
    Result("Start descent at", "${Fmt.num(dist, 1)} nm to go")
    Result("Required VVI", "${Fmt.num(vvi)} fpm")
    Result("Time to descend", fmtTime(dist / ((gs.d() ?: 1.0).coerceAtLeast(1.0))))
    Paragraph("Rule of thumb (3°): distance ≈ altitude to lose ÷ 318; VVI ≈ ground speed × 5.3.", Hud.TextDim)
}

@Composable
private fun Wind() {
    var tas by rememberSaveable { mutableStateOf("420") }
    var crs by rememberSaveable { mutableStateOf("090") }
    var wdir by rememberSaveable { mutableStateOf("270") }
    var wspd by rememberSaveable { mutableStateOf("40") }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { NumField("TAS", tas, { tas = it }, Modifier.weight(1f), "kt"); NumField("Course", crs, { crs = it }, Modifier.weight(1f), "°") }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { NumField("Wind from", wdir, { wdir = it }, Modifier.weight(1f), "°"); NumField("Wind speed", wspd, { wspd = it }, Modifier.weight(1f), "kt") }
    val v = tas.d() ?: 0.0; val c = Math.toRadians(crs.d() ?: 0.0); val wd = Math.toRadians(wdir.d() ?: 0.0); val ws = wspd.d() ?: 0.0
    if (v > ws) {
        val wca = asin((ws * sin(wd - c)) / v)
        val gsv = v * cos(wca) - ws * cos(wd - c)
        Result("Heading to fly", Fmt.hdg(Math.toDegrees(c + wca)))
        Result("Wind correction", "${Fmt.num(Math.toDegrees(wca), 1)}°")
        Result("Ground speed", "${Fmt.num(gsv)} kt")
        Result("Head/tail component", "${Fmt.num(ws * cos(wd - c))} kt head")
        Result("Crosswind component", "${Fmt.num(ws * sin(wd - c))} kt")
    }
}

@Composable
private fun Turn() {
    var tas by rememberSaveable { mutableStateOf("450") }
    var bank by rememberSaveable { mutableStateOf("80") }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { NumField("TAS", tas, { tas = it }, Modifier.weight(1f), "kt"); NumField("Bank", bank, { bank = it }, Modifier.weight(1f), "°") }
    val v = (tas.d() ?: 0.0) * 1.68781 // ft/s
    val phi = Math.toRadians((bank.d() ?: 0.0).coerceIn(1.0, 89.0))
    val r = v * v / (32.174 * tan(phi))
    val rate = Math.toDegrees(v / r)
    Result("Load factor", "${Fmt.num(1 / cos(phi), 1)} G")
    Result("Turn radius", "${Fmt.num(r)} ft · ${Fmt.num(r / 6076.12, 2)} nm")
    Result("Turn rate", "${Fmt.num(rate, 1)} °/s")
    Result("360° turn time", "${Fmt.num(360 / rate)} s")
    Result("Standard rate bank (3°/s)", "${Fmt.num(Math.toDegrees(atan(3.0 * Math.PI / 180 * v / 32.174)), 0)}°")
}

@Composable
private fun Fuel() {
    var fuel by rememberSaveable { mutableStateOf("5000") }
    var ff by rememberSaveable { mutableStateOf("3500") }
    var dist by rememberSaveable { mutableStateOf("120") }
    var gs by rememberSaveable { mutableStateOf("420") }
    var reserve by rememberSaveable { mutableStateOf("1500") }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { NumField("Fuel", fuel, { fuel = it }, Modifier.weight(1f), "lb"); NumField("Fuel flow", ff, { ff = it }, Modifier.weight(1f), "pph") }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { NumField("Dist home", dist, { dist = it }, Modifier.weight(1f), "nm"); NumField("GS", gs, { gs = it }, Modifier.weight(1f), "kt") }
    NumField("Landing reserve", reserve, { reserve = it }, Modifier.fillMaxWidth(), "lb")
    val t = (dist.d() ?: 0.0) / (gs.d() ?: 1.0).coerceAtLeast(1.0)
    val req = t * (ff.d() ?: 0.0) + (reserve.d() ?: 0.0)
    val endurance = (fuel.d() ?: 0.0) / (ff.d() ?: 1.0).coerceAtLeast(1.0)
    Result("Time home", fmtTime(t))
    Result("Bingo (fuel to get home + reserve)", "${Fmt.num(req)} lb")
    Result("Margin over bingo", "${Fmt.num((fuel.d() ?: 0.0) - req)} lb")
    Result("Endurance at this flow", fmtTime(endurance))
    Result("Fuel in gallons (JP-8, 6.7 lb/gal)", "${Fmt.num((fuel.d() ?: 0.0) / 6.7)} gal")
}

@Composable
private fun Units() {
    var v by rememberSaveable { mutableStateOf("1000") }
    NumField("Value", v, { v = it }, Modifier.fillMaxWidth())
    val x = v.d() ?: 0.0
    Text("DISTANCE", style = LocalExtra.current.overline, color = Hud.TextDim, modifier = Modifier.padding(top = 8.dp))
    Result("nm → km / sm", "${Fmt.num(x * 1.852, 2)} km · ${Fmt.num(x * 1.15078, 2)} sm")
    Result("km → nm", "${Fmt.num(x / 1.852, 2)} nm")
    Text("ALTITUDE", style = LocalExtra.current.overline, color = Hud.TextDim, modifier = Modifier.padding(top = 8.dp))
    Result("ft → m", "${Fmt.num(x * 0.3048, 1)} m")
    Result("m → ft", "${Fmt.num(x / 0.3048)} ft")
    Text("SPEED", style = LocalExtra.current.overline, color = Hud.TextDim, modifier = Modifier.padding(top = 8.dp))
    Result("kt → km/h / m/s", "${Fmt.num(x * 1.852, 1)} km/h · ${Fmt.num(x * 0.514444, 1)} m/s")
    Result("km/h → kt", "${Fmt.num(x / 1.852, 1)} kt")
    Text("WEIGHT & FUEL", style = LocalExtra.current.overline, color = Hud.TextDim, modifier = Modifier.padding(top = 8.dp))
    Result("lb → kg", "${Fmt.num(x * 0.453592, 1)} kg")
    Result("kg → lb", "${Fmt.num(x / 0.453592, 1)} lb")
    Result("lb JP-8 → gal / L", "${Fmt.num(x / 6.7, 1)} gal · ${Fmt.num(x / 6.7 * 3.78541, 1)} L")
    Result("inHg → hPa", "${Fmt.num(x * 33.8639, 1)} hPa")
    Result("hPa → inHg", "${Fmt.num(x / 33.8639, 2)} inHg")
}

@Composable
private fun Speed() {
    var alt by rememberSaveable { mutableStateOf("20000") }
    var tas by rememberSaveable { mutableStateOf("450") }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { NumField("Altitude", alt, { alt = it }, Modifier.weight(1f), "ft"); NumField("TAS", tas, { tas = it }, Modifier.weight(1f), "kt") }
    val h = (alt.d() ?: 0.0).coerceIn(0.0, 65000.0)
    val tempK = if (h < 36089) 288.15 - 0.0019812 * h else 216.65
    val a = 38.967854 * sqrt(tempK) // speed of sound kt
    val ratioP = if (h < 36089) (1 - 6.87559e-6 * h).pow(5.2559) else 0.22336 * Math.exp(-4.80634e-5 * (h - 36089))
    val rho = ratioP * 288.15 / tempK
    val t = tas.d() ?: 0.0
    Result("Mach", Fmt.num(t / a, 2))
    Result("Speed of sound", "${Fmt.num(a)} kt")
    Result("EAS ≈ CAS (no compressibility)", "${Fmt.num(t * sqrt(rho))} kt")
    Result("OAT (ISA)", "${Fmt.num(tempK - 273.15)} °C")
    Paragraph("TAS rises ~2% per 1,000 ft over CAS. ISA standard atmosphere assumed.", Hud.TextDim)
}
