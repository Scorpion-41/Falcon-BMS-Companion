package com.bmscompanion.desktop.bridge

import com.bmscompanion.app.data.PcText
import com.bmscompanion.app.data.mission.PcFileView
import com.bmscompanion.app.data.mission.PcPicture
import kotlinx.serialization.KSerializer
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.Base64

/**
 * The Mission section's **Files** tab (1.4.0): a file of the BMS PC opened on any device to be read — a text or log,
 * an HTML page, a picture, a PDF. Read only; nothing here writes.
 *
 * - `GET /api/files/view?path=` → [PcFileView]: what the file is, and a text's or HTML page's text ([view])
 * - `GET /api/files/pdfpage?path=&page=&max=` → [PcPicture]: one page of a PDF (0 = the first) as a PNG at most [max]
 *   px wide ([pdfPage])
 * - a picture goes through `GET /api/files/picture` ([PcFileRoutes.picture]) as it does for Upd Kneeboard
 *
 * **The same guards as the Planner's file routes** ([PcFileRoutes.resolve]: the PC's own drives only, never a network
 * share, a device path, a stream or a reserved device name, `..` resolved), and only the kinds the tab opens
 * ([PcFileView.TYPES]) — never a program or a script — also where a link leads. A text is decoded as the Planner's
 * files are ([PcText.decode]: UTF-8 when it is valid, a byte order mark honoured, else Windows-1252). Files are opened
 * shared for reading, so a log BMS is still writing is read as it stands. Nothing here throws: the answer is
 * `{"error": "<sentence>"}` with 400 (a path or type not taken), 404 (not there) or 409 (could not be read).
 */
object FileViewRoutes {
    val ROUTES = listOf("GET /api/files/view", "GET /api/files/pdfpage")

    /** The largest PDF opened (PDFBox reads the whole cross-reference table). */
    private const val MAX_PDF = 96L * 1024 * 1024

    fun handles(path: String) = path == "/api/files/view" || path == "/api/files/pdfpage"

    fun handle(req: ApiRequest): ApiResponse {
        val path = req.path.trimEnd('/')
        if (req.method != "GET") return error("Only GET is answered on $path.", 405)
        return try {
            when (path) {
                "/api/files/view" -> view(req.query["path"])
                "/api/files/pdfpage" -> pdfPage(req.query["path"], req.query["page"], req.query["max"])
                else -> ApiResponse.notFound()
            }
        } catch (e: Throwable) {
            BridgeLog.warn("$path: ${e.message}")
            error("The PC could not open that file: ${e.message ?: e::class.java.simpleName}", 409)
        }
    }

    /** A file the tab may open: the file itself (a link followed), or the refusal. */
    private sealed interface Opened {
        class Ok(val asked: File, val real: File, val kind: String) : Opened
        class Bad(val response: ApiResponse) : Opened
    }

    private fun open(rawPath: String?): Opened {
        val f = when (val t = PcFileRoutes.resolve(rawPath)) {
            is PcFileRoutes.Target.Bad -> return Opened.Bad(error(t.why, 400))
            is PcFileRoutes.Target.Ok -> t.file
        }
        val kind = PcFileView.kindOf(f.name)
            ?: return Opened.Bad(error("The Files tab does not open ${typeWords(f)}: only ${PcFileView.TYPES.joinToString(", ") { ".$it" }}.", 400))
        if (quiet { f.isDirectory } == true) return Opened.Bad(error("${f.path} is a folder.", 400))
        if (quiet { f.isFile } != true) return Opened.Bad(error("${f.path} is not on the PC.", 404))
        val real = quiet { f.toPath().toRealPath().toFile() } ?: f
        if (PcFileView.kindOf(real.name) != kind) return Opened.Bad(error("${f.path} leads to ${real.name}, which the Files tab does not open as one.", 400))
        // a link is held to the same rules where it leads (own drives only, no network share)
        if (real.path != f.path) (PcFileRoutes.resolve(real.path) as? PcFileRoutes.Target.Bad)?.let { return Opened.Bad(error("${f.path} leads to ${real.path}: ${it.why}", 400)) }
        return Opened.Ok(f, real, kind)
    }

    private fun view(rawPath: String?): ApiResponse {
        val o = when (val r = open(rawPath)) { is Opened.Bad -> return r.response; is Opened.Ok -> r }
        val size = quiet { Files.size(o.real.toPath()) } ?: 0L
        val modified = quiet { Files.getLastModifiedTime(o.real.toPath()).toMillis() } ?: 0L
        val base = PcFileView(path = o.asked.path, name = o.asked.name, size = size, modified = modified, kind = o.kind)
        return when (o.kind) {
            "text", "html" -> {
                val max = PcFileView.MAX_TEXT
                // a log is read for its newest lines, anything else from the start
                val tail = o.kind == "text" && o.asked.name.lowercase().let { it.endsWith(".log") || it.contains("xlog") }
                val from = if (size > max && tail) size - max else 0L
                val bytes = try { readPart(o.real, from, minOf(size, max).toInt()) } catch (e: Throwable) {
                    return error("${o.asked.path} could not be read: ${e.message ?: e::class.java.simpleName}", 409)
                }
                var text = PcText.decode(bytes)
                // a cut in the middle of a line: start at the next whole one
                if (from > 0) text = text.substringAfter('\n', text)
                val cut = size > max
                encode(PcFileView.serializer(), base.copy(
                    text = text, truncated = cut, offset = from,
                    note = if (!cut) null else if (tail) "The file is ${mb(size)}: these are its last ${mb(max)}." else "The file is ${mb(size)}: this is its first ${mb(max)}.",
                ))
            }
            "pdf" -> {
                if (size > MAX_PDF) return error("${o.asked.name} is ${mb(size)}: the Files tab opens PDFs up to ${mb(MAX_PDF)}.", 409)
                val pages = try { Loader.loadPDF(o.real).use { it.numberOfPages } } catch (e: Throwable) {
                    return error("${o.asked.name} could not be read as a PDF: ${e.message ?: e::class.java.simpleName}", 409)
                }
                encode(PcFileView.serializer(), base.copy(pages = pages))
            }
            else -> encode(PcFileView.serializer(), base)
        }
    }

    private fun pdfPage(rawPath: String?, rawPage: String?, rawMax: String?): ApiResponse {
        val o = when (val r = open(rawPath)) { is Opened.Bad -> return r.response; is Opened.Ok -> r }
        if (o.kind != "pdf") return error("${o.asked.name} is not a PDF.", 400)
        val size = quiet { Files.size(o.real.toPath()) } ?: 0L
        if (size > MAX_PDF) return error("${o.asked.name} is ${mb(size)}: the Files tab opens PDFs up to ${mb(MAX_PDF)}.", 409)
        val page = rawPage?.trim()?.toIntOrNull() ?: 0
        val max = (rawMax?.trim()?.toIntOrNull() ?: 1600).coerceIn(256, 3000)
        val img = try {
            Loader.loadPDF(o.real).use { doc ->
                if (page !in 0 until doc.numberOfPages) return error("${o.asked.name} has ${doc.numberOfPages} pages: there is no page ${page + 1}.", 400)
                val box = doc.getPage(page).cropBox
                val side = maxOf(box.width, box.height).takeIf { it > 1f } ?: 842f
                val dpi = (max / (side / 72f)).coerceIn(36f, 300f)
                PDFRenderer(doc).renderImageWithDPI(page, dpi, ImageType.RGB)
            }
        } catch (e: Throwable) {
            return error("Page ${page + 1} of ${o.asked.name} could not be drawn: ${e.message ?: e::class.java.simpleName}", 409)
        }
        val png = java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(img, "png", it) }.toByteArray()
        val modified = quiet { Files.getLastModifiedTime(o.real.toPath()).toMillis() } ?: 0L
        return encode(PcPicture.serializer(), PcPicture(
            path = o.asked.path, name = o.asked.name, size = size, modified = modified, format = "PDF page ${page + 1}",
            width = img.width, height = img.height, sentWidth = img.width, sentHeight = img.height,
            data = Base64.getEncoder().encodeToString(png),
        ))
    }

    /** [length] bytes of [f] from [from], the file opened for reading only (and shared, so a log being written reads). */
    private fun readPart(f: File, from: Long, length: Int): ByteArray = RandomAccessFile(f, "r").use { raf ->
        raf.seek(from)
        val out = ByteArray(minOf(length.toLong(), maxOf(0L, raf.length() - from)).toInt())
        raf.readFully(out)
        out
    }

    private fun typeWords(f: File): String = f.name.substringAfterLast('.', "").lowercase().let { if (it.isEmpty()) "a file with no type" else ".$it files" }
    private fun mb(b: Long): String = if (b >= 1024 * 1024) "${(b + 512 * 1024) / (1024 * 1024)} MB" else "${(b + 512) / 1024} KB"
    private inline fun <T> quiet(body: () -> T): T? = try { body() } catch (_: Throwable) { null }
    private fun <T> encode(serializer: KSerializer<T>, value: T) = ApiResponse.json(Bridge.json.encodeToString(serializer, value))
    private fun error(sentence: String, status: Int) =
        ApiResponse.json("""{"error":${kotlinx.serialization.json.JsonPrimitive(sentence)}}""", status)
}
