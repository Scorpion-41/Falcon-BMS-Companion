package android.net

import com.bmscompanion.web.encodeUriComponent

/** Browser stand-in for android.net.Uri: the shared screens only use [encode] to put names into navigation routes. */
object Uri {
    fun encode(s: String?): String = encodeUriComponent(s.orEmpty())
}
