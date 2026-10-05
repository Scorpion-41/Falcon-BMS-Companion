package com.bmscompanion.app.data.mission

import android.graphics.Bitmap
import com.bmscompanion.app.data.Repo

/**
 * One frame of a cockpit display, as `MissionLink.rttFrame` hands it to the MFD card.
 *
 * [image] is null when the display has not changed since the [tag] the card asked about: an MFD on a page that is
 * not moving is the same picture for seconds at a time, and the card simply keeps drawing the one it has.
 */
class RttFrame(val tag: Long, val image: Bitmap?)

/**
 * A short fingerprint of a frame's bytes (FNV-1a, 32 bits, as a non-negative number).
 *
 * The PC and the device compute it the same way over the same compressed bytes, so a device can say "I have the
 * picture whose hash is this" in the query and the PC answer 204 with nothing in it when that is still the picture.
 * It is not a security measure and does not need to be one; two different frames of one display agreeing on 32 bits
 * would cost one frame not being redrawn.
 */
fun frameHash(bytes: ByteArray): Long {
    var h = 0x811C9DC5.toInt()
    for (b in bytes) {
        h = h xor (b.toInt() and 0xFF)
        h *= 16777619
    }
    return h.toLong() and 0xFFFFFFFFL
}

/** Where one display's picture is fetched from: [width] 0 takes BMS's own size, [since] 0 means "I have nothing". */
fun rttFramePath(display: String, width: Int, since: Long): String =
    "/api/rtt/img?d=" + display + (if (width > 0) "&w=$width" else "") + (if (since != 0L) "&since=$since" else "")

/**
 * A display over the network, for a phone, a browser or a PC reading another PC.
 *
 * An empty answer is the PC saying "unchanged" (204): nothing was sent and nothing is decoded. Anything else is a
 * compressed picture, decoded here and fingerprinted so the next request can quote it.
 */
suspend fun rttFrameOverHttp(display: String, width: Int, since: Long, fetch: suspend (String) -> ByteArray?): RttFrame? {
    val bytes = fetch(rttFramePath(display, width, since)) ?: return null
    if (bytes.isEmpty()) return RttFrame(since, null)
    val bmp = Repo.decodeBitmap(bytes) ?: return null
    return RttFrame(frameHash(bytes), bmp)
}
