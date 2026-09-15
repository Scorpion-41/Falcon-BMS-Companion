package java.text

import java.util.Date
import java.util.Locale

@JsFun("(ms) => { const d = new Date(ms); return [d.getFullYear(), d.getMonth(), d.getDate(), d.getDay(), d.getHours(), d.getMinutes(), d.getSeconds()].join(','); }")
private external fun localParts(ms: Double): String

/** Browser stand-in for java.text.SimpleDateFormat (local time, English): yyyy MMMM MMM MM dd d EEEE EEE HH mm ss. */
class SimpleDateFormat(private val pattern: String, @Suppress("UNUSED_PARAMETER") locale: Locale = Locale.US) {
    private val months = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
    private val days = listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")
    private val token = Regex("yyyy|MMMM|MMM|MM|dd|d|EEEE|EEE|HH|mm|ss")

    fun format(date: Date): String {
        val p = localParts(date.time.toDouble()).split(',').map { it.toInt() }
        return token.replace(pattern) { m ->
            when (m.value) {
                "yyyy" -> p[0].toString()
                "MMMM" -> months[p[1]]
                "MMM" -> months[p[1]].take(3)
                "MM" -> (p[1] + 1).toString().padStart(2, '0')
                "dd" -> p[2].toString().padStart(2, '0')
                "d" -> p[2].toString()
                "EEEE" -> days[p[3]]
                "EEE" -> days[p[3]].take(3)
                "HH" -> p[4].toString().padStart(2, '0')
                "mm" -> p[5].toString().padStart(2, '0')
                else -> p[6].toString().padStart(2, '0')
            }
        }
    }
}
