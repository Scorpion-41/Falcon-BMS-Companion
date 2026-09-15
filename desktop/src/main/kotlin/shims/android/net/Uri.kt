package android.net

import java.net.URLEncoder

/**
 * PC stand-in for android.net.Uri: the shared screens only use [encode] to put names into navigation routes.
 * Like Android, spaces become %20 (not '+').
 */
object Uri {
    fun encode(s: String?): String = URLEncoder.encode(s.orEmpty(), Charsets.UTF_8).replace("+", "%20")
}
