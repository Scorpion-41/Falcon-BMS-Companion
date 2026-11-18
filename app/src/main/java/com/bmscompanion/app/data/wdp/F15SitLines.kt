package com.bmscompanion.app.data.wdp

import com.bmscompanion.app.data.mission.CartridgeEdit

/**
 * The F-15C's SIT lines in the pilot's data cartridge (Falcon BMS 4.38.2), the `[F15_SIT_LINES]` section. Read out
 * of `Falcon BMS.exe` with its pdb (`DataCartridgeClass::F15_CUSTOM_SIT_LINES_Load`, `FireControlComputerF15::
 * DrawCustomSitLines`); BMS loads it and nothing in BMS writes it:
 * - **Lines 1-4** style the cartridge's four steerpoint lines (`lineSTPT_0…23`, six points each) on the SIT: a name,
 *   shown or not, a colour and a dash. They carry no points of their own.
 * - **Lines 6-12** are seven lines of their own, up to 30 points each: `SITLINE_<n>_<k>` = "north, east, z" in
 *   theater feet, k 0-29; BMS skips a point whose first two figures are 0 and draws a line of two points or more.
 * - Per line `SITLINE_<n>_NAME` (at most 12 characters), `_ENABLED` (1/0), `_COLORIDX` (0-11, BMS's MFD colours
 *   [COLORS]), `_COLORHEX` (RRGGBB, which wins over the index; never written here), `_DASHOPT` (0 solid, 1-3 dotted at
 *   three spacings, [DASHES]). Line 5 is not read.
 * - In a campaign BMS takes them from the save's mission file when it has the section, else from the cartridge;
 *   nothing writes them into a mission file, so the cartridge's are the ones flown.
 *
 * Pure: read from and written to cartridge text, never throwing.
 */
object F15SitLines {
    const val SECTION = "F15_SIT_LINES"
    val STEERPOINT_LINES = 1..4
    val CUSTOM_LINES = 6..12
    const val MAX_POINTS = 30
    const val MAX_NAME = 12

    /** BMS's `Color` enum, 0-11 (`MFD_GREEN` … `MFD_BLACK`), with a colour to show it by. */
    val COLORS: List<Pair<String, Long>> = listOf(
        "Green" to 0xFF2BD12BL, "White" to 0xFFFFFFFFL, "Red" to 0xFFFF3B30L, "Yellow" to 0xFFFFE233L,
        "Cyan" to 0xFF33E1FFL, "Magenta" to 0xFFFF4FD8L, "Blue" to 0xFF3B7BFFL, "Grey" to 0xFF9AA0A6L,
        "Bright green" to 0xFF7DFF5AL, "Light grey" to 0xFFD6D9DCL, "Dark grey" to 0xFF5F6368L, "Black" to 0xFF101214L,
    )

    /** `_DASHOPT`: 0 solid, 1-3 dotted, closest to widest (`DottedLine` at three spacings). */
    val DASHES = listOf("Solid", "Dots, close", "Dots", "Dots, wide")

    data class Point(val north: Double, val east: Double)

    data class Line(
        val n: Int,
        val name: String = "",
        val shown: Boolean = true,
        val color: Int = 0,
        val dash: Int = 0,
        /** lines 6-12 only */
        val points: List<Point> = emptyList(),
        /** where the points came from, for the window ("Copper2 tanker track"); not written */
        val from: String = "",
    ) {
        val custom: Boolean get() = n in CUSTOM_LINES
    }

    /** The lines as [text] (a cartridge) has them; a line it does not set is shown, green, solid, unnamed. */
    fun read(text: String?): List<Line> = (STEERPOINT_LINES + CUSTOM_LINES).map { n ->
        fun r(k: String) = DtcIni.read(text, SECTION, "SITLINE_${n}_$k").trim()
        val pts = if (n in CUSTOM_LINES) (0 until MAX_POINTS).mapNotNull { k ->
            val f = DtcIni.read(text, SECTION, "SITLINE_${n}_$k").split(',').mapNotNull { it.trim().toDoubleOrNull() }
            if (f.size < 2 || (f[0] == 0.0 && f[1] == 0.0)) null else Point(f[0], f[1])
        } else emptyList()
        Line(
            n = n,
            name = r("NAME").take(MAX_NAME),
            shown = r("ENABLED").toIntOrNull()?.let { it != 0 } ?: true,
            color = r("COLORIDX").toIntOrNull()?.takeIf { it in COLORS.indices } ?: 0,
            dash = r("DASHOPT").toIntOrNull()?.takeIf { it in DASHES.indices } ?: 0,
            points = pts,
        )
    }

    /** Whether [text] has the section at all (BMS's own window never writes it). */
    fun present(text: String?): Boolean = (STEERPOINT_LINES + CUSTOM_LINES).any { n -> DtcIni.read(text, SECTION, "SITLINE_${n}_ENABLED").isNotBlank() }

    /**
     * The edits that put [lines] into a cartridge: every key of each line, the points of a custom line in order and
     * the rest of its 30 slots taken out (a line made shorter leaves nothing of the longer one behind). Figures as BMS
     * prints them ("%f").
     */
    fun edits(lines: List<Line>): List<CartridgeEdit> = buildList {
        for (l in lines) {
            fun w(k: String, v: String?) = add(CartridgeEdit(SECTION, "SITLINE_${l.n}_$k", v))
            // an unnamed line leaves BMS its own name (the key taken out rather than written empty)
            w("NAME", l.name.trim().take(MAX_NAME).takeIf { it.isNotEmpty() })
            w("ENABLED", if (l.shown) "1" else "0")
            w("COLORIDX", l.color.coerceIn(0, COLORS.size - 1).toString())
            w("DASHOPT", l.dash.coerceIn(0, DASHES.size - 1).toString())
            if (l.custom) for (k in 0 until MAX_POINTS) {
                val p = l.points.getOrNull(k)
                w(k.toString(), p?.let { "${f6(it.north)}, ${f6(it.east)}, 0.000000" })
            }
        }
    }

    private fun f6(v: Double): String {
        val neg = v < 0
        val scaled = kotlin.math.round(kotlin.math.abs(v) * 1_000_000.0).toLong()
        return (if (neg && scaled != 0L) "-" else "") + (scaled / 1_000_000L).toString() + "." + (scaled % 1_000_000L).toString().padStart(6, '0')
    }
}
