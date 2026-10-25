package com.bmscompanion.desktop.ui.command

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.bmscompanion.app.ui.theme.Hud
import org.jetbrains.skia.Data
import org.jetbrains.skia.Surface
import org.jetbrains.skia.svg.SVGDOM
import kotlin.math.roundToInt

/**
 * The country flags beside the team names of the Command section. BMS names a team by its country ("U.S.", "ROK",
 * "Hellas", "N.Macedonia" …; every 4.38.1 theater's teams are in [CODES]); each name is matched to a vector flag in
 * `resources/command/flags/<code>.svg` (flag-icons 7.2.3, MIT, 4:3; `nato.svg` and `fry.svg` drawn for the program)
 * and drawn from the SVG at the exact pixel size asked for, so it is crisp at any scale. A team whose name is no
 * country (XX, Rogue, Hezbollah) keeps its team colour swatch.
 */
object CountryFlags {
    /** BMS's team names, without dots, spaces or case, to the flag file. */
    private val CODES: Map<String, String> = buildMap {
        fun add(code: String, vararg names: String) = names.forEach { this[norm(it)] = code }
        add("us", "U.S.", "USA", "United States")
        add("kr", "ROK", "South Korea", "Korea")
        add("jp", "Japan")
        add("ru", "CIS", "Russia", "USSR")
        add("cn", "PRC", "China")
        add("kp", "DPRK", "North Korea")
        add("nato", "NATO")
        add("gr", "Greece", "Hellas")
        add("si", "Slovenia")
        add("ba", "Bosnia")
        add("al", "Albania")
        add("hu", "Hungary")
        add("fry", "FRY", "Yugoslavia")
        add("hr", "Croatia")
        add("gb", "UK", "British", "Great Britain", "United Kingdom")
        add("fk", "Falklands")
        add("cl", "Chile", "Chilean")
        add("py", "Paraguay", "Paraguayan")
        add("uy", "Uruguay", "Uruguayan")
        add("ar", "Argentina", "Argentine")
        add("br", "Brazil", "Brazilian")
        add("bg", "Bulgaria")
        add("mk", "N.Macedonia", "Macedonia", "North Macedonia", "FYROM")
        add("tr", "Turkey")
        add("il", "Israel")
        add("ps", "PA", "Palestine")
        add("jo", "Jordan")
        add("sy", "Syria")
        add("eg", "Egypt")
        add("sa", "Saudi Arabia")
    }

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    /** The flag file's code for a team's name, null when it names no country this program has a flag of. */
    fun codeFor(teamName: String?): String? = teamName?.let { CODES[norm(it)] }

    private val svg = HashMap<String, SVGDOM?>()
    private val bitmaps = HashMap<String, ImageBitmap?>()

    /** The flag [code] drawn at [w] x [h] pixels (cached), null when the file is missing or unreadable. */
    @Synchronized
    fun bitmap(code: String, w: Int, h: Int): ImageBitmap? = bitmaps.getOrPut("$code@${w}x$h") {
        runCatching {
            val dom = svg.getOrPut(code) {
                javaClass.getResourceAsStream("/command/flags/$code.svg")?.use { SVGDOM(Data.makeFromBytes(it.readBytes())) }
            } ?: return@runCatching null
            dom.setContainerSize(w.toFloat(), h.toFloat())
            Surface.makeRasterN32Premul(w, h).use { s ->
                dom.render(s.canvas)
                s.makeImageSnapshot().toComposeImageBitmap()
            }
        }.getOrNull()
    }
}

/**
 * A team's flag (4:3, [height] tall, a hairline round it so a white field reads on the dark panel), or its colour
 * swatch when its name is no country.
 */
@Composable
fun TeamFlag(name: String?, colour: Int?, height: Dp = 11.dp, modifier: Modifier = Modifier) {
    val code = CountryFlags.codeFor(name)
    val swatch = colour?.let { teamInk(it) } ?: Hud.TextFaint
    Canvas(modifier.size(height * 4f / 3f, height)) {
        val w = size.width.roundToInt().coerceAtLeast(1)
        val h = size.height.roundToInt().coerceAtLeast(1)
        val img = code?.let { CountryFlags.bitmap(it, w, h) }
        if (img != null) {
            drawImage(img, dstOffset = IntOffset.Zero, dstSize = IntSize(w, h))
            drawRect(Color.Black.copy(alpha = 0.45f), Offset.Zero, Size(w.toFloat(), h.toFloat()), style = Stroke(1f))
        } else {
            // no country: the team's colour, a smaller rounded patch in the flag's place
            val sw = h * 0.8f
            drawRoundRect(swatch, Offset((w - sw) / 2f, (h - sw) / 2f), Size(sw, sw), CornerRadius(sw * 0.25f))
        }
    }
}

/** Team [n]'s flag (or swatch) as a lead for a chip, a menu, a table cell or a fact row. */
fun CmdWorld.teamLead(n: Int): @Composable () -> Unit {
    val t = team(n)
    return { TeamFlagLead(t?.name, t?.colour ?: n) }
}

/** The same from the file's own team part (the Teams page, which edits names and colours). */
fun com.bmscompanion.desktop.bridge.command.CommandSave.teamLead(n: Int): @Composable () -> Unit {
    val name = teamName(n)
    val colour = teams.getOrNull(n)?.team?.teamColor ?: n
    return { TeamFlagLead(name, colour) }
}

/** [TeamFlag] then a gap: put before a team's name. */
@Composable
fun TeamFlagLead(name: String?, colour: Int?, height: Dp = 11.dp) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TeamFlag(name, colour, height)
        Spacer(Modifier.width(5.dp))
    }
}
