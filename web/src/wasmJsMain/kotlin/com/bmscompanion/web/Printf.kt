package com.bmscompanion.web

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * The subset of java.util.Formatter the shared screens use (English formatting): %d %s %f %x %%, with the flags
 * `-` `+` `0` `,` and width/precision, e.g. "%,d", "%+,d", "%03d", "%.1f".
 */
object Printf {
    private val spec = Regex("%([-+0, #]*)(\\d+)?(?:\\.(\\d+))?([dfsxX%])")

    fun format(format: String, args: Array<out Any?>): String {
        var next = 0
        return spec.replace(format) { m ->
            val flags = m.groupValues[1]
            val width = m.groupValues[2].toIntOrNull() ?: 0
            val precision = m.groupValues[3].toIntOrNull()
            val conv = m.groupValues[4]
            if (conv == "%") return@replace "%"
            val arg = args.getOrNull(next++)
            val body = when (conv) {
                "d" -> integer((arg as? Number)?.toLong() ?: 0L, flags)
                "f" -> decimal((arg as? Number)?.toDouble() ?: 0.0, precision ?: 6, flags)
                "x", "X" -> ((arg as? Number)?.toLong() ?: 0L).toString(16).let { if (conv == "X") it.uppercase() else it }
                else -> arg.toString().let { if (precision != null) it.take(precision) else it }
            }
            pad(body, width, flags, conv != "s")
        }
    }

    private fun integer(v: Long, flags: String): String {
        val digits = abs(v).toString().let { if (',' in flags) group(it) else it }
        return sign(v < 0, flags) + digits
    }

    private fun decimal(v: Double, precision: Int, flags: String): String {
        if (v.isNaN()) return "NaN"
        if (v.isInfinite()) return if (v > 0) "Infinity" else "-Infinity"
        val unit = 10.0.pow(precision).toLong()
        val scaled = (abs(v) * unit).roundToLong()
        val whole = (scaled / unit).toString().let { if (',' in flags) group(it) else it }
        val frac = if (precision > 0) "." + (scaled % unit).toString().padStart(precision, '0') else ""
        return sign(v < 0 && scaled != 0L, flags) + whole + frac
    }

    private fun sign(negative: Boolean, flags: String) = when {
        negative -> "-"
        '+' in flags -> "+"
        ' ' in flags -> " "
        else -> ""
    }

    private fun group(digits: String): String = digits.reversed().chunked(3).joinToString(",").reversed()

    private fun pad(s: String, width: Int, flags: String, numeric: Boolean): String = when {
        s.length >= width -> s
        '-' in flags -> s.padEnd(width)
        '0' in flags && numeric -> {
            val signed = s.firstOrNull()?.let { it == '-' || it == '+' || it == ' ' } == true
            if (signed) s[0] + s.substring(1).padStart(width - 1, '0') else s.padStart(width, '0')
        }
        else -> s.padStart(width)
    }
}
