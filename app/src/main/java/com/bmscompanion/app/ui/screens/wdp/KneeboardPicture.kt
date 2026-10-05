package com.bmscompanion.app.ui.screens.wdp

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bmscompanion.app.data.FileFilter
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.mission.KbHalf
import com.bmscompanion.app.data.mission.KbKind
import com.bmscompanion.app.data.mission.MissionLink
import com.bmscompanion.app.data.mission.PcPicture
import kotlinx.coroutines.CancellationException
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Upd Kneeboard's **Browse picture…**: WDP's Browse Picture (`fclsKneeboardMulti.btnBrowsePicture_Click`), which puts
 * a picture file on a half page ("Selected Picture", `m_Left[n].File`) and stretches it over the whole half
 * (`cntDataCard`'s kneeboard save resizes it to 1021 x 2046 with `preserveAspectRatio: false`).
 *
 * The file is on the **BMS PC**, as WDP's is: it is picked with the Planner's file window ([com.bmscompanion.app.data.PcFiles]:
 * Windows' own dialog on the PC's window, the PC's folders in a window everywhere else), and read by the PC, which
 * sends it as a PNG whatever it was — a `.jpg`, `.png`, `.bmp` or `.dds` (`GET /api/files/picture`, [PcPicture]) — so
 * a phone and a browser decode it exactly as the PC does. What comes here is the **preview** ([PREVIEW] pixels on
 * its longer side, [PicturePage] and the chooser): stretched over the 2:3 half page, as the pad shows it.
 *
 * **The page itself is drawn by the PC from the file**: a print sends the picture's path ([KbHalf.picture]) and no
 * capture, and the PC reads the file at that moment and stretches its own pixels over the file's 1024 x 2048 half, as
 * WDP resizes the file itself. (A capture of this device's 1024 x 1536 page would lose a quarter of a full-size
 * picture's rows on the way.) A file changed since it was chosen prints as it is now; a file gone or unreadable by then
 * leaves its half as it is, with the reason in the window.
 *
 * Previews are kept here by path while a Picture half or the chooser shows them, without the PNG they came as.
 */
object KneeboardPictures {
    /** What the Open picture window is remembered as ([WdpFiles.last]): WDP's `KneeboardLastFile`. */
    const val LAST_FILE = "kneeboardpicture"

    /** WDP's Open picture filter, after one type showing them all. */
    val FILTERS: List<FileFilter> =
        listOf(FileFilter("Pictures (*.jpg, *.png, *.bmp, *.dds)", PcPicture.TYPES)) +
            FileFilter.parse("(*.jpg)|*.jpg;*.jpeg|(*.png)|*.png|(*.bmp)|*.bmp|(*.dds)|*.dds")

    /** The preview's longer side, in pixels: enough for the window's page and the chooser at any size. */
    const val PREVIEW = 1024

    /**
     * One picture as the PC sent it: [image] to draw, or [error] (the PC's sentence, or this device's). [info] is what
     * the PC said of the file (format, size), without its PNG ([PcPicture.data] is emptied once decoded).
     */
    class Picture(val path: String, val image: ImageBitmap?, val error: String?, val info: PcPicture?)

    private val cache = mutableStateMapOf<String, Picture>()
    private val loading = mutableStateMapOf<String, Boolean>()

    fun cached(path: String): Picture? = cache[path]

    fun isLoading(path: String): Boolean = loading[path] == true

    /** "photo.jpg": the name of [path] without its folder. */
    fun nameOf(path: String?): String = path?.substringAfterLast('\\')?.substringAfterLast('/').orEmpty()

    /**
     * The picture at [path] on the BMS PC, read by the PC and decoded here; the one read before unless [fresh]. Never
     * throws: a file that is not there, not a picture, or not readable comes back with [Picture.error].
     */
    @OptIn(ExperimentalEncodingApi::class)
    suspend fun load(path: String, fresh: Boolean = false): Picture {
        if (!fresh) cache[path]?.let { return it }
        loading[path] = true
        try {
            val a = try { MissionLink.filesPicture(path, PREVIEW) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
            val v = a?.value
            val p = if (a == null || v == null || a.error != null) {
                Picture(path, null, (a?.error ?: "the PC did not answer").trimEnd('.'), v?.copy(data = ""))
            } else {
                val bytes = try { Base64.Default.decode(v.data) } catch (e: Exception) { null }
                val img = bytes?.let { b -> try { Repo.decodeBitmap(b)?.asImageBitmap() } catch (e: CancellationException) { throw e } catch (e: Exception) { null } }
                // the PNG is not kept: the bitmap is what is drawn, and the page itself is drawn by the PC
                val info = v.copy(data = "")
                if (img == null) Picture(path, null, "this device could not decode the picture the PC sent", info) else Picture(path, img, null, info)
            }
            cache[path] = p
            trim(path)
            return p
        } finally {
            loading.remove(path)
        }
    }

    /**
     * Keeps the previews a Picture half of the plan or the chooser shows, and a few more. A half set to another kind
     * keeps its picture's path ([KneeboardPrintSession.pictures], as WDP keeps `m_Left[n].File`) but not its preview.
     */
    private fun trim(keep: String) {
        if (cache.size <= 6) return
        val s = KneeboardPrintSession
        val used = s.pictures.filter { (key, _) -> s.plan[key] == KbKind.PICTURE }.values.toSet() + setOfNotNull(keep, s.browse?.path)
        for (k in cache.keys.toList()) if (k !in used && cache.size > 6) cache.remove(k)
    }
}

/**
 * A Picture half page: the file chosen for page [KbPageScope.n], [KbPageScope.side], stretched over the whole page as
 * WDP stretches it: the preview. While it is being read, or when it cannot be, the page says so. A print never
 * captures this page: it sends the picture's path, and the PC draws the half from the file itself.
 */
@Composable
internal fun PicturePage(scope: KbPageScope) {
    val path = KneeboardPrintSession.picture(scope.n, scope.side)
    val pic = path?.let { KneeboardPictures.cached(it) }
    if (path != null && pic == null) LaunchedEffect(path) { KneeboardPictures.load(path) }
    val img = pic?.image
    if (img != null) {
        Image(img, contentDescription = KneeboardPictures.nameOf(path), modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds, filterQuality = FilterQuality.High)
        return
    }
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(
            when {
                path == null -> "No picture chosen for this half: Browse picture… puts one here."
                pic?.error != null -> "${KneeboardPictures.nameOf(path)}: ${pic.error}."
                else -> "Reading ${KneeboardPictures.nameOf(path)} on the PC…"
            },
            color = Color(0xFF8A8A8A), fontSize = 14.sp, lineHeight = 19.sp, textAlign = TextAlign.Center,
        )
    }
}
