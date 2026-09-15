package java.util

/** Browser stand-in for java.util.Locale: the shared screens only pass Locale.US to get English formatting. */
class Locale private constructor(val tag: String) {
    companion object {
        val US = Locale("en-US")
        val ENGLISH = Locale("en")
        val ROOT = Locale("")
        fun getDefault() = US
    }
}

/** Browser stand-in for java.util.Date (milliseconds since the epoch). */
class Date(val time: Long) {
    constructor() : this(com.bmscompanion.web.nowMillis().toLong())
}
