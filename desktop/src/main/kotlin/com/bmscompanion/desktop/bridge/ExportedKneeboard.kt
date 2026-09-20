package com.bmscompanion.desktop.bridge

import com.bmscompanion.app.data.mission.KneeboardInfo
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO

/**
 * The kneeboard UOAF's html_brief exports, made readable on every device BMS Companion serves.
 *
 * That tool takes a printed BMS briefing and lays it out as a kneeboard: `kneeboard.pdf` in its own folder (six A4
 * pages out of the box — package and weather, roster and loadout, the flight plan, targets and ROE, the map, the
 * airfield and comms), alongside any `.pdf`, `.png` or `.jpg` a pilot has dropped in there themselves. It also
 * copies the result into BMS as in-cockpit kneeboards, which is what it is for.
 *
 * BMS Companion does not make any of that and does not write to it. It finds the pages the tool last exported,
 * renders them once each, and hands them out — so the same kneeboard is on the tablet, in the browser and on a VR
 * board. When BMS prints a newer briefing than the export, the app says so rather than showing a stale page: the
 * pilot exports again in the tool's own window (or lets its auto-export do it) and the pages follow.
 */
class ExportedKneeboard {
    /** Rendered pages, keyed by file, page, width and the file's own timestamp: a new export renders afresh. */
    private val cache = object : LinkedHashMap<String, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean {
            if (size <= 12) return false
            var bytes = 0L
            for (v in values) bytes += v.size
            return bytes > 32L * 1024 * 1024
        }
    }

    /** A folder is the exporter's when its program is in it. */
    fun isValidDir(path: String?): Boolean = dirOf(path) != null

    private fun dirOf(path: String?): File? = path?.takeIf { it.isNotBlank() }
        ?.let { File(it) }
        ?.takeIf { it.isDirectory && File(it, "html_brief.exe").isFile }

    /** Where BMS ships it, when the pilot has not said otherwise. */
    fun defaultDir(install: BmsInstall): String? =
        install.baseDir?.let { File(it, "Tools\\html_brief_win") }?.takeIf { File(it, "html_brief.exe").isFile }?.path

    fun root(settings: BridgeSettings, install: BmsInstall): File? =
        dirOf(settings.KneeboardExporterDir) ?: dirOf(defaultDir(install))

    /**
     * Where the exported pages are. The tool's `config.ini` can move them, and a pilot who has moved them should not
     * have to tell BMS Companion as well.
     */
    private fun exportDir(root: File): File {
        val named = runCatching {
            File(root, "config.ini").takeIf { it.isFile }?.readLines()
                ?.firstOrNull { it.trimStart().startsWith("pdf_output_dir", true) }
                ?.substringAfter('=')?.trim()?.takeIf { it.isNotEmpty() }
        }.getOrNull()
        val dir = named?.let { if (File(it).isAbsolute) File(it) else File(root, it) } ?: File(root, "kneeboards")
        return if (dir.isDirectory) dir else File(root, "kneeboards")
    }

    /**
     * The exported files, kneeboard first.
     *
     * Only what the tool exports or a pilot put there on purpose: its working copy in `.html_brief` is the same
     * document again and is left out.
     */
    fun files(root: File): List<File> {
        val dir = exportDir(root)
        val all = runCatching { dir.listFiles()?.filter { it.isFile && isPage(it.name) }.orEmpty() }.getOrDefault(emptyList())
        return all.sortedWith(compareBy({ if (it.name.equals("kneeboard.pdf", true)) 0 else 1 }, { it.name.lowercase() }))
    }

    /** One page of the export: which file it is in, and which page of that file. */
    data class Page(val file: File, val index: Int)

    /** Every page across every exported file, in the order a pilot would flip through them. */
    fun pages(root: File): List<Page> = files(root).flatMap { f ->
        if (f.extension.equals("pdf", true)) {
            (0 until pdfPages(f)).map { Page(f, it) }
        } else {
            listOf(Page(f, 0))
        }
    }

    fun status(settings: BridgeSettings, install: BmsInstall, briefingModified: Long): KneeboardInfo {
        val root = root(settings, install)
        val pages = root?.let { pages(it) }.orEmpty()
        val exported = pages.maxOfOrNull { it.file.lastModified() } ?: 0
        return KneeboardInfo(
            configured = root != null,
            path = root?.path,
            detected = defaultDir(install),
            available = pages.isNotEmpty(),
            pages = pages.size,
            exported = exported,
            // a briefing printed after the export means these pages are last flight's
            stale = exported > 0 && briefingModified > exported + 5_000,
            message = when {
                root == null -> "No kneeboard exporter set up. BMS ships html_brief in Tools\\html_brief_win; set that " +
                    "folder in Settings and its exported kneeboard appears here."
                pages.isEmpty() -> "The exporter is set up but has not written a kneeboard yet. Print the briefing in " +
                    "BMS, then export in the HTML Briefing window."
                else -> null
            },
        )
    }

    /** One page as a JPEG, at most [maxWidth] across. Rendered once and kept until the file changes. */
    fun page(root: File, index: Int, maxWidth: Int): ByteArray? {
        val page = pages(root).getOrNull(index) ?: return null
        val key = "${page.file.path}|${page.file.lastModified()}|${page.index}|$maxWidth"
        synchronized(cache) { cache[key] }?.let { return it }
        val bytes = runCatching {
            val image = if (page.file.extension.equals("pdf", true)) renderPdf(page.file, page.index, maxWidth)
            else ImageIO.read(page.file)?.let { scale(it, maxWidth) }
            image?.let { jpeg(it) }
        }.onFailure { BridgeLog.warn("Kneeboard page ${index + 1} could not be rendered: ${it.message}") }.getOrNull()
        if (bytes != null) synchronized(cache) { cache[key] = bytes }
        return bytes
    }

    private fun pdfPages(file: File): Int = runCatching {
        Loader.loadPDF(file).use { it.numberOfPages }
    }.getOrDefault(0)

    private fun renderPdf(file: File, index: Int, maxWidth: Int): BufferedImage? = Loader.loadPDF(file).use { doc ->
        if (index !in 0 until doc.numberOfPages) return null
        val width = doc.getPage(index).cropBox.width.takeIf { it > 1f } ?: 595f
        // 150 dpi is what the app's own charts are rendered at, and it stays sharp when a page is zoomed into
        val dpi = (maxWidth / (width / 72f)).coerceIn(72f, 220f)
        PDFRenderer(doc).renderImageWithDPI(index, dpi, ImageType.RGB)
    }

    private fun scale(image: BufferedImage, maxWidth: Int): BufferedImage {
        if (image.width <= maxWidth) return image
        val h = image.height * maxWidth / image.width
        val out = BufferedImage(maxWidth, h, BufferedImage.TYPE_INT_RGB)
        out.createGraphics().apply {
            setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            drawImage(image, 0, 0, maxWidth, h, null)
            dispose()
        }
        return out
    }

    private fun jpeg(image: BufferedImage): ByteArray {
        val rgb = if (image.type == BufferedImage.TYPE_INT_RGB) image else BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_RGB).also {
            it.createGraphics().apply { drawImage(image, 0, 0, java.awt.Color.WHITE, null); dispose() }
        }
        val out = ByteArrayOutputStream()
        ImageIO.write(rgb, "jpg", out)
        return out.toByteArray()
    }

    /** Opens the exporter, so a pilot can export a new kneeboard. Their own tool, doing what it is for. */
    fun open(root: File): String {
        val exe = File(root, "html_brief.exe")
        if (!exe.isFile) return "html_brief.exe is not in ${root.path}"
        return runCatching {
            ProcessBuilder(exe.path).directory(root).start()
            BridgeLog.info("Kneeboard exporter opened")
            "The HTML Briefing window is opening. Export there, and the new pages appear here."
        }.getOrElse { "Could not start html_brief.exe: ${it.message}" }
    }

    companion object {
        private val kinds = setOf("pdf", "png", "jpg", "jpeg")
        fun isPage(name: String) = name.substringAfterLast('.', "").lowercase() in kinds
    }
}
