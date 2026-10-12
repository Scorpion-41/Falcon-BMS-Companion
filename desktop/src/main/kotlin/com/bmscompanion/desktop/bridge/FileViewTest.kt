package com.bmscompanion.desktop.bridge

import com.bmscompanion.app.data.mission.PcFileView
import com.bmscompanion.app.data.mission.PcPicture
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import java.io.File

/**
 * The Files tab's routes (`/api/files/view`, `/api/files/pdfpage`; [FileViewRoutes]), run as part of `--filestest`:
 * each kind opens from a scratch folder (a text, a log longer than the PC sends, an HTML page with a picture beside it,
 * a picture, a two-page PDF), and the guards hold — no program, no script, no network path, device name or stream,
 * nothing the tab does not open, also behind a link.
 */
object FileViewTest {
    /** Sample files of every kind the tab opens, in [dir] (also drawn by `--docshots files-…`). */
    fun samples(dir: File): Map<String, File> {
        dir.mkdirs()
        val txt = File(dir, "notes.txt").apply {
            writeText((1..60).joinToString("\r\n") { "Line $it: STPT ${it % 24 + 1}, bingo 2,400 lbs, joker 3,200 lbs — Ω °" }, Charsets.UTF_8)
        }
        val log = File(dir, "big.log").apply {
            val line = "[12:00:00.000] 0042 Subtitle: Osan Tower, Viper 1-1, runway 0-9 left, cleared for takeoff\r\n"
            val n = (PcFileView.MAX_TEXT / line.length + 400).toInt()
            bufferedWriter(Charsets.ISO_8859_1).use { w -> repeat(n) { i -> w.write(line.replace("0042", i.toString().padStart(6, '0'))) } }
        }
        val png = File(dir, "chart.png").apply {
            val img = java.awt.image.BufferedImage(640, 400, java.awt.image.BufferedImage.TYPE_INT_RGB)
            val g = img.createGraphics()
            g.color = java.awt.Color(30, 40, 52); g.fillRect(0, 0, 640, 400)
            g.color = java.awt.Color(255, 190, 60); g.stroke = java.awt.BasicStroke(6f); g.drawLine(60, 340, 580, 60)
            g.color = java.awt.Color.WHITE; g.font = java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, 36); g.drawString("RWY 09L", 230, 220)
            g.dispose()
            javax.imageio.ImageIO.write(img, "png", this)
        }
        val html = File(dir, "page.html").apply {
            writeText(
                """<!DOCTYPE html><html><head><title>Mission notes</title><style>body{color:red}</style><script>alert(1)</script></head>
                |<body><h1>Mission notes</h1><p>The <b>package</b> pushes at <i>08:20z</i> &amp; holds at <code>STPT 3</code>.</p>
                |<ul><li>Tanker: Texaco 1-1, <a href="notes.txt">the notes</a></li><li>AWACS: Magic 5-1</li></ul>
                |<table><tr><th>Field</th><th>APP</th><th>TWR</th></tr><tr><td>Osan</td><td>358.400</td><td>308.800</td></tr></table>
                |<img src="chart.png" alt="the chart"><hr><p>Links out: <a href="https://www.falcon-bms.com">BMS</a></p></body></html>""".trimMargin(),
                Charsets.UTF_8,
            )
        }
        val pdf = File(dir, "manual.pdf").apply {
            PDDocument().use { doc ->
                for (k in 1..2) {
                    val page = PDPage(PDRectangle.A4)
                    doc.addPage(page)
                    PDPageContentStream(doc, page).use { s ->
                        s.beginText(); s.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 28f); s.newLineAtOffset(72f, 740f)
                        s.showText("BMS Companion - page $k"); s.endText()
                        s.beginText(); s.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 14f); s.newLineAtOffset(72f, 700f)
                        s.showText("Bingo is the fuel to return to base with the reserve."); s.endText()
                    }
                }
                doc.save(this)
            }
        }
        val exe = File(dir, "tool.exe").apply { writeBytes(byteArrayOf(0x4D, 0x5A)) }
        val bat = File(dir, "run.bat").apply { writeText("echo hi") }
        return mapOf("txt" to txt, "log" to log, "png" to png, "html" to html, "pdf" to pdf, "exe" to exe, "bat" to bat)
    }

    fun run(scratch: File): String = buildString {
        fun check(what: String, ok: Boolean, detail: String = "") = appendLine((if (ok) "ok   " else "FAIL ") + what + if (detail.isNotEmpty()) "  [$detail]" else "")
        fun api(path: String, q: Map<String, String>): Pair<Int, String> {
            val r = Bridge.handle(ApiRequest("GET", path, q, ByteArray(0), remote = "check"))
            return r.status to r.body.toString(Charsets.UTF_8)
        }
        fun err(t: String) = Regex("\"error\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(t)?.groupValues?.get(1).orEmpty()
        fun view(f: File) = api("/api/files/view", mapOf("path" to f.path)).let { (s, t) -> s to runCatching { Bridge.json.decodeFromString(PcFileView.serializer(), t) }.getOrNull() }
        appendLine("")
        appendLine("== 7. The Files tab: /api/files/view and /api/files/pdfpage (FileViewRoutes)")
        val dir = File(scratch, "files-view").absoluteFile
        dir.deleteRecursively()
        val f = samples(dir)

        val (s1, v1) = view(f["txt"]!!)
        check("a text opens whole, as text", s1 == 200 && v1?.kind == "text" && v1.truncated == false && v1.text?.lines()?.size == 60, "HTTP $s1")
        check("…UTF-8 decoded", v1?.text?.contains("Ω °") == true)
        val (s2, v2) = view(f["log"]!!)
        check("a log longer than the PC sends: its last part, from a whole line", s2 == 200 && v2?.truncated == true && (v2.offset) > 0 &&
            v2.text?.startsWith("[12:00:00.000]") == true && (v2.text?.length ?: 0) <= PcFileView.MAX_TEXT, "offset ${v2?.offset} note ${v2?.note}")
        val (s3, v3) = view(f["html"]!!)
        check("an HTML page opens as html with its text", s3 == 200 && v3?.kind == "html" && v3.text?.contains("<h1>Mission notes</h1>") == true)
        val blocks = com.bmscompanion.app.ui.screens.mission.FilesHtml.parse(v3?.text.orEmpty(), androidx.compose.ui.graphics.Color.Cyan, androidx.compose.ui.graphics.Color.Yellow) {}
        val words = blocks.joinToString(" | ") { b ->
            when (b) {
                is com.bmscompanion.app.ui.screens.mission.FilesHtml.Heading -> "H${b.level} ${b.text.text}"
                is com.bmscompanion.app.ui.screens.mission.FilesHtml.Para -> "P ${b.text.text}"
                is com.bmscompanion.app.ui.screens.mission.FilesHtml.Item -> "${b.marker} ${b.text.text}"
                is com.bmscompanion.app.ui.screens.mission.FilesHtml.Row -> "ROW " + b.cells.joinToString("/") { it.text }
                is com.bmscompanion.app.ui.screens.mission.FilesHtml.Image -> "IMG ${b.src}"
                com.bmscompanion.app.ui.screens.mission.FilesHtml.Rule -> "HR"
            }
        }
        appendLine("     blocks: $words")
        check("…laid out as blocks: heading, paragraph, list, table, picture, rule; no script or style",
            "H1 Mission notes" in words && "P The package pushes at 08:20z & holds at STPT 3." in words && "• Tanker: Texaco 1-1, the notes" in words &&
                "ROW Field/APP/TWR" in words && "ROW Osan/358.400/308.800" in words && "IMG chart.png" in words && "HR" in words &&
                "alert" !in words && "color:red" !in words && "DOCTYPE" !in words)
        check("…a relative link resolves beside the page", com.bmscompanion.app.ui.screens.mission.resolveOnPc(f["html"]!!.path, "sub/../notes.txt").equals(f["txt"]!!.path, true))
        check("…a link to the web stays out of the PC", com.bmscompanion.app.ui.screens.mission.resolveOnPc(f["html"]!!.path, "https://www.falcon-bms.com") == null)
        val (s4, v4) = view(f["png"]!!)
        check("a picture opens as image", s4 == 200 && v4?.kind == "image", "HTTP $s4")
        val (pp, pt) = api("/api/files/picture", mapOf("path" to f["png"]!!.path, "max" to "3000"))
        check("…and is sent by the picture route", pp == 200 && runCatching { Bridge.json.decodeFromString(PcPicture.serializer(), pt).width }.getOrNull() == 640, "HTTP $pp")
        val (s5, v5) = view(f["pdf"]!!)
        check("a PDF opens with its page count", s5 == 200 && v5?.kind == "pdf" && v5.pages == 2, "HTTP $s5 pages ${v5?.pages} ${err(api("/api/files/view", mapOf("path" to f["pdf"]!!.path)).second)}")
        val (p1, p1t) = api("/api/files/pdfpage", mapOf("path" to f["pdf"]!!.path, "page" to "1", "max" to "1000"))
        val pic = runCatching { Bridge.json.decodeFromString(PcPicture.serializer(), p1t) }.getOrNull()
        check("…a page drawn as a PNG, fitted to the size asked", p1 == 200 && pic != null && maxOf(pic.width, pic.height) in 900..1000 && pic.data.isNotEmpty(), "HTTP $p1 ${pic?.width}x${pic?.height}")
        val (p2, p2t) = api("/api/files/pdfpage", mapOf("path" to f["pdf"]!!.path, "page" to "7"))
        check("…a page it does not have is refused in words", p2 == 400 && err(p2t).isNotEmpty(), err(p2t))

        fun refused(what: String, path: String, want: Int, route: String = "/api/files/view") {
            val (s, t) = api(route, mapOf("path" to path))
            check(what, s == want && err(t).isNotEmpty(), "HTTP $s: ${err(t)}")
        }
        refused("a program is refused", f["exe"]!!.path, 400)
        refused("a script is refused", f["bat"]!!.path, 400)
        refused("a program on the system drive is refused", "C:\\Windows\\notepad.exe", 400)
        refused("a network path is refused", "\\\\server\\share\\notes.txt", 400)
        refused("a device path is refused", "\\\\?\\C:\\notes.txt", 400)
        refused("a reserved device name is refused", dir.path + "\\CON.txt", 400)
        refused("an alternate data stream is refused", dir.path + "\\notes.txt:hidden", 400)
        refused("a relative path is refused", "notes.txt", 400)
        refused("a folder is refused", dir.path + "\\sub.txt\\..", 400)
        refused("a file that is not there is 404", dir.path + "\\missing.txt", 404)
        refused("a PDF page of a text is refused", f["txt"]!!.path, 400, "/api/files/pdfpage")
        // a link named .txt that leads to a program
        val link = File(dir, "innocent.txt")
        val linked = runCatching { java.nio.file.Files.createSymbolicLink(link.toPath(), f["exe"]!!.toPath()) }.isSuccess
        if (linked) refused("a link named .txt that leads to a program is refused", link.path, 400)
        else appendLine("     (no symbolic link made: Windows lets an ordinary account make none without developer mode; that guard not run)")
        val (pm, _) = Bridge.handle(ApiRequest("POST", "/api/files/view", mapOf("path" to f["txt"]!!.path), ByteArray(0), remote = "check")).let { it.status to "" }
        check("only GET is answered", pm == 405, "HTTP $pm")
    }
}
