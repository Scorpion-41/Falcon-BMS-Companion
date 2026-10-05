package com.bmscompanion.desktop.bridge

import com.bmscompanion.app.data.mission.KbFileResult
import com.bmscompanion.app.data.mission.KbFileSend
import com.bmscompanion.app.data.mission.KbHalf
import com.bmscompanion.app.data.mission.KbKind
import com.bmscompanion.app.data.mission.KbOwner
import com.bmscompanion.app.data.mission.KbPrintState
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinBase
import com.sun.jna.platform.win32.WinNT
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

/**
 * `--kbprinttest <copy> out.txt [page.png…] [pictures=<folder>] [devil=<a DDS WDP wrote>] [texconv=<texconv.exe>]`:
 * Upd Kneeboard ([KneeboardPrint], [Dds]) against a **copy** of a BMS folder, file by file (R3-KNEEBOARD §9,
 * without the backup, README and enable steps, which the user's rule removed: the pages are written in place).
 *
 * 1. Where the pages are, per theater (LHTO → Hellas, EF2000 → Balkans, the Korean add-ons → KTO, OFM KTO one page),
 *    and where BMS ships its own copies (KTO, Hellas, Balkans).
 * 2. Who made each page now: BMS, EZBoards, html_brief, WDP (DevIL), this program (per half, from its tag); EZBoards'
 *    claimed halves from its `CONFIG_USER.BAT`.
 * 3-6. A print (the test page on 1L+1R, a DataCard-like page on 2L, the test page on 16R): same size, header (but the
 *    tag), format and mips; the printed half ≥ 32 dB; the other half's bytes kept on every block-aligned level; mips
 *    ≥ 35 dB; no other file changed, no stray file; BMS's texconv reads the files (±2); under 2 s a file; printing
 *    again changes nothing, printing the other half keeps the first.
 * 7. The routes, in-process (state, thumb, file, shipped, the 400s).
 * 8. A `KoreaObj_HiRes` twin is printed too, in its own size.
 * 9. Failures, each a sentence with the file left as it was: writes denied (icacls), a file held open, a missing page
 *    (never created), a DX10 header, a file cut short, a read-only file.
 * 10-11. Putting BMS's shipped pages back (SHA equal to BMS's copy; again: unchanged), and the picture of 7983.dds
 *    with the test page on the left knee and BMS's own page on the right.
 *
 * Refuses a folder that holds `Falcon BMS.exe` or is Falcon BMS's installed folder. Pictures go to `pictures=` (default:
 * `<copy>-kbprint` beside the copy).
 */
object KbPrintTest {
    private val client = Json { ignoreUnknownKeys = true; isLenient = true }

    fun run(copy: File, args: List<String>): String {
        val sb = StringBuilder()
        var fails = 0
        fun line(s: String = "") { sb.append(s).append('\n') }
        fun check(ok: Boolean, what: String, detail: String = "") {
            if (!ok) fails++
            line((if (ok) "PASS " else "FAIL ") + what + if (detail.isNotEmpty()) "  | $detail" else "")
        }
        val root = copy.absoluteFile
        DevGuard.why(root)?.let { return "FAIL: refused to run against ${root.path}: $it. Run it against a copy.\n" }
        if (!File(root, "Data").isDirectory) return "FAIL: ${root.path} has no Data folder.\n"
        val pictures = args.firstOrNull { it.startsWith("pictures=") }?.substringAfter('=')?.let(::File) ?: File(root.parentFile, root.name + "-kbprint")
        runCatching { pictures.deleteRecursively() }
        pictures.mkdirs()
        val devil = args.firstOrNull { it.startsWith("devil=") }?.substringAfter('=')?.let(::File)?.takeIf { it.isFile }
        val texconv = args.firstOrNull { it.startsWith("texconv=") }?.substringAfter('=')?.let(::File)?.takeIf { it.isFile }
            ?: listOf("Tools\\Textureconverter\\texconv.exe", "Tools\\EZBoards\\bin\\texconv.exe").map { File(root, it) }.firstOrNull { it.isFile }
        val pngs = args.filter { it.endsWith(".png", true) && !it.contains('=') }.map(::File).filter { it.isFile }

        try {
            body(root, pictures, devil, texconv, pngs, ::line, ::check)
        } catch (e: Throwable) {
            check(false, "the check ran to the end", "${e::class.java.simpleName}: ${e.message}\n" + e.stackTrace.take(10).joinToString("\n") { "    at $it" })
        }
        line()
        line(if (fails == 0) "ALL PASS" else "$fails FAIL(s)")
        return sb.toString()
    }

    // ------------------------------------------------------------------ pages

    /** The test page: a grid, a circle (round on the pad when the 2:3 shape is right), corner labels and the page's name. */
    fun testPage(label: String): BufferedImage = page { g, w, h ->
        g.color = Color(0xDD, 0xDD, 0xDD)
        g.stroke = BasicStroke(2f)
        for (x in 0..w step 64) g.drawLine(x, 0, x, h)
        for (y in 0..h step 64) g.drawLine(0, y, w, y)
        g.color = Color(0x20, 0x20, 0x20)
        g.stroke = BasicStroke(8f)
        g.drawRect(8, 8, w - 16, h - 16)
        g.stroke = BasicStroke(6f)
        val d = 800
        g.drawOval((w - d) / 2, (h - d) / 2, d, d)
        g.drawLine(w / 2, (h - d) / 2 - 40, w / 2, (h + d) / 2 + 40)
        g.drawLine((w - d) / 2 - 40, h / 2, (w + d) / 2 + 40, h / 2)
        g.font = Font(Font.SANS_SERIF, Font.BOLD, 40)
        g.drawString("TOP LEFT", 30, 70)
        g.drawString("TOP RIGHT", w - 30 - g.fontMetrics.stringWidth("TOP RIGHT"), 70)
        g.drawString("BOTTOM LEFT", 30, h - 40)
        g.drawString("BOTTOM RIGHT", w - 30 - g.fontMetrics.stringWidth("BOTTOM RIGHT"), h - 40)
        g.color = Color(0xB0, 0x10, 0x10)
        g.font = Font(Font.SANS_SERIF, Font.BOLD, 150)
        val fm = g.fontMetrics
        g.drawString(label, (w - fm.stringWidth(label)) / 2, h / 2 + 50)
        g.color = Color.BLACK
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 28)
        g.drawString("BMS Companion test page: the circle must look round", 60, 150)
        g.font = Font(Font.MONOSPACED, Font.PLAIN, 20)
        for (i in 0 until 6) g.drawString("0123456789 ABCDEFGHIJKLMNOPQRSTUVWXYZ abcdefghijklmnopqrstuvwxyz", 40, h - 280 + i * 26)
    }

    /** A page like the DataCard's left half: a header band and a ruled table of steerpoints. */
    fun dataCardPage(): BufferedImage = page { g, w, h ->
        g.color = Color(0x30, 0x38, 0x40)
        g.fillRect(0, 0, w, 110)
        g.color = Color.WHITE
        g.font = Font(Font.SANS_SERIF, Font.BOLD, 56)
        g.drawString("DATA CARD  -  JAGUAR 2", 40, 78)
        g.color = Color.BLACK
        g.font = Font(Font.MONOSPACED, Font.BOLD, 26)
        g.drawString("STPT  POSITION                 ALT     TOS", 30, 160)
        g.stroke = BasicStroke(2f)
        g.font = Font(Font.MONOSPACED, Font.PLAIN, 26)
        for (i in 0 until 36) {
            val y = 200 + i * 36
            g.color = if (i % 2 == 0) Color(0xF0, 0xF0, 0xF0) else Color.WHITE
            g.fillRect(20, y - 26, w - 40, 36)
            g.color = Color.BLACK
            g.drawString("%2d  N37 %02d.%03d E127 %02d.%03d  %5d  04:%02d:%02d".format(i + 1, 10 + i, (i * 137) % 1000, i % 60, (i * 91) % 1000, 2500 + i * 500, 21 + i / 2, (i * 17) % 60), 30, y)
            g.color = Color(0xA0, 0xA0, 0xA0)
            g.drawLine(20, y + 10, w - 20, y + 10)
        }
    }

    private fun page(draw: (java.awt.Graphics2D, Int, Int) -> Unit): BufferedImage {
        val w = KneeboardPrint.PAGE_W
        val h = KneeboardPrint.PAGE_H
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = Color.WHITE
        g.fillRect(0, 0, w, h)
        draw(g, w, h)
        g.dispose()
        return img
    }

    // ------------------------------------------------------------------ helpers

    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun sha(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { s -> val buf = ByteArray(1 shl 16); while (true) { val r = s.read(buf); if (r < 0) break; md.update(buf, 0, r) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Every file under [root] (relative path, lower case) with its SHA-256. */
    private fun snapshot(root: File): Map<String, String> =
        root.walkTopDown().filter { it.isFile }.associate { it.path.substring(root.path.length).trimStart('\\', '/').lowercase() to sha(it) }

    private fun diff(a: Map<String, String>, b: Map<String, String>): String {
        val changed = a.keys.intersect(b.keys).filter { a[it] != b[it] }
        val added = b.keys - a.keys
        val removed = a.keys - b.keys
        return listOf("changed: ${changed.sorted()}", "added: ${added.sorted()}", "removed: ${removed.sorted()}").joinToString("; ")
    }

    private fun copyable(info: Dds.Info, l: Int): Boolean { val lw = info.levelWidth(l); return lw >= 2 * info.kind.block && (lw / 2) % info.kind.block == 0 }

    private fun halfPx(bytes: ByteArray, info: Dds.Info, l: Int, side: Int): IntArray {
        val lw = info.levelWidth(l); val lh = info.levelHeight(l)
        return Dds.decode(bytes, info, l, side * (lw / 2), 0, lw / 2, lh)
    }

    private fun png(img: BufferedImage, f: File) { runCatching { f.parentFile?.mkdirs(); ImageIO.write(img, "png", f) } }

    private fun pngBase64(img: BufferedImage): String {
        val out = ByteArrayOutputStream()
        ImageIO.write(img, "png", out)
        return java.util.Base64.getEncoder().encodeToString(out.toByteArray())
    }

    private fun strayFiles(dir: File?): List<String> = dir?.listFiles()?.filter { it.name.endsWith(".bmsc-new", true) }?.map { it.name }.orEmpty()

    // ------------------------------------------------------------------ the check

    private fun body(
        root: File, pictures: File, devil: File?, texconv: File?, pngs: List<File>,
        line: (String) -> Unit, report: (Boolean, String, String) -> Unit,
    ) {
        fun check(ok: Boolean, what: String, detail: String = "") = report(ok, what, detail)
        line("Upd Kneeboard, against ${root.name} (a copy)")
        line("pictures: ${pictures.name}; DevIL sample: ${devil?.name ?: "synthetic"}; texconv: ${if (texconv != null) "yes" else "none"}; pages given: ${pngs.size}")
        line("")

        // ---------------------------------------------------------------- 1. where the pages are
        line("== 1. Where each theater's pages are")
        val set = Theaters.at(root)
        line("%-24s %-40s %5s %5s  %s".format("theater", "page folder (under the BMS folder)", "pages", "twin", "BMS's own copies"))
        for (t in set.all) {
            val p = KneeboardPrint.place(root, t.name)
            val pages = (1..16).count { p.page(it)?.isFile == true }
            val shipped = p.shipped?.let { s -> s.path.substring(Theaters.canonical(root).length).trimStart('\\') } ?: "-"
            line("%-24s %-40s %5d %5s  %s".format(t.name, p.folderLabel ?: ("(none) " + (p.error ?: "")), pages, (1..16).any { p.twin(it) != null }, shipped))
        }
        fun folder(name: String) = KneeboardPrint.place(root, name).folderLabel?.lowercase()
        check(folder("Hellas") != null && folder("LHTO") == folder("Hellas") && folder("Hellas WCP") == folder("Hellas"),
            "LHTO and Hellas WCP use Hellas's pages", "${folder("LHTO")}")
        check(folder("Balkans") != null && folder("EF2000 BTO") == folder("Balkans"), "EF2000 BTO uses the Balkans' pages", "${folder("EF2000 BTO")}")
        val koreans = listOf("Korea TvT", "KTO 80s Revamp 4.38", "LKTO 4.38 + Papa", "LKTO 4.38 - Mike")
        check(folder("Korea KTO") != null && koreans.all { folder(it) == folder("Korea KTO") }, "Korea TvT, KTO 80s, LKTO + and - use Korea KTO's pages",
            koreans.joinToString { "$it=${folder(it)}" })
        val ofm = KneeboardPrint.place(root, "OFM KTO")
        check((1..16).count { ofm.page(it)?.isFile == true } == 1, "OFM KTO has one page (7982 only)")
        val kto = KneeboardPrint.place(root, "Korea KTO")
        val hellas = KneeboardPrint.place(root, "Hellas")
        val balkans = KneeboardPrint.place(root, "Balkans")
        check(kto.shipped?.path?.lowercase()?.endsWith("docs\\07 kneeboard templates\\f-16\\dds_backup") == true, "Korea KTO: BMS's own copies in Docs\\07 Kneeboard Templates\\F-16\\DDS_Backup", "${kto.shipped?.name}")
        if (File(root, "Data\\Add-On Hellas\\Docs").isDirectory)
            check(hellas.shipped?.path?.lowercase()?.endsWith("add-on hellas\\docs\\03 3d kneeboard backup") == true, "Hellas: BMS's own copies in Add-On Hellas\\Docs\\03 3d Kneeboard Backup", "${hellas.shipped?.name}")
        else line("  (no Add-On Hellas\\Docs in this copy)")
        if (File(root, "Data\\Add-On Balkans\\Docs").isDirectory)
            check(balkans.shipped?.path?.lowercase()?.endsWith("04 3d kneeboards backup\\01 f-16") == true, "Balkans: BMS's own copies in Add-On Balkans\\Docs\\04 3d Kneeboards Backup\\01 F-16", "${balkans.shipped?.name}")
        else line("  (no Add-On Balkans\\Docs in this copy)")
        check(ofm.shipped == null, "OFM KTO: no shipped copies")
        check(KneeboardPrint.place(root, "No Such Theater").error?.contains("No Such Theater") == true, "an unknown theater name is an error, not a guess",
            KneeboardPrint.place(root, "No Such Theater").error ?: "")
        check(KneeboardPrint.place(root, null).theater?.name == set.all.firstOrNull()?.name, "no theater name: the base theater")
        check(kto.folderLabel?.contains(':') == false && kto.folderLabel?.startsWith("Data", true) == true, "the folder is given relative to the BMS folder", "${kto.folderLabel}")
        line("")

        // ---------------------------------------------------------------- 2. owners
        line("== 2. Who made each page now")
        fun owners(p: KneeboardPrint.Place): List<String> {
            val s = KneeboardPrint.state(p, null, false)
            return s.pages.map { if (it.left == it.right) it.left else "${it.left}/${it.right}" }
        }
        val ktoOwners = owners(kto)
        line("  Korea KTO: " + ktoOwners.mapIndexed { i, o -> "${i + 1}=$o" }.joinToString(" "))
        check(ktoOwners[0] == KbOwner.EZBOARDS && ktoOwners[1] == KbOwner.HTMLBRIEF && ktoOwners[2] == KbOwner.HTMLBRIEF && ktoOwners.drop(3).all { it == KbOwner.BMS },
            "Korea KTO: page 1 EZBoards (IMAGEMAGICK), 2-3 html_brief (Pillow BGRA), 4-16 BMS's own (equal to DDS_Backup)")
        val hellasOwners = owners(hellas)
        line("  Hellas:    " + hellasOwners.mapIndexed { i, o -> "${i + 1}=$o" }.joinToString(" "))
        check(hellasOwners[0] == KbOwner.EZBOARDS && hellasOwners.drop(1).all { it == KbOwner.BMS }, "Hellas: page 1 EZBoards, 2-16 BMS's own (DXT1, one level)")
        val balkansOwners = owners(balkans)
        line("  Balkans:   " + balkansOwners.mapIndexed { i, o -> "${i + 1}=$o" }.joinToString(" "))
        check(balkansOwners.all { it == KbOwner.BMS }, "Balkans: all BMS's own (DXT1, 12 levels, depth 0)")
        val ofmOwners = owners(ofm)
        check(ofmOwners[0] == KbOwner.BMS && ofmOwners.drop(1).all { it == KbOwner.MISSING }, "OFM KTO: page 1 BMS's layout, 2-16 missing", ofmOwners.take(3).joinToString())

        val ownerDir = File(pictures, "owners").also { it.mkdirs() }
        run {
            // WDP's own output (DevIL): DXT1, flags A1007, one level, depth 0, caps 1000, no tag
            val sample = File(ownerDir, "7982.dds")
            if (devil != null) devil.copyTo(sample, overwrite = true)
            else {
                val b = kto.page(1)!!.readBytes()
                fun put(o: Int, v: Int) { b[o] = v.toByte(); b[o + 1] = (v shr 8).toByte(); b[o + 2] = (v shr 16).toByte(); b[o + 3] = (v shr 24).toByte() }
                put(8, 0xA1007); put(24, 0); put(28, 1); put(108, 0x1000)
                for (i in 32 until 76) b[i] = 0
                sample.writeBytes(b)
            }
            val o = KneeboardPrint.owners(sample, Dds.header(sample.readBytes()), null)
            check(o.left == KbOwner.WDP && o.right == KbOwner.WDP, "WDP's DevIL page is told as WDP's (${if (devil != null) "WDP's own output" else "synthetic header"})", o.left)
            // this program's own tag names each half, and what was printed there
            val tagged = Dds.print(kto.page(4)!!.readBytes(), Dds.header(kto.page(4)!!.readBytes()), arrayOf(null, null),
                KneeboardPrint.tag(KneeboardPrint.Owners(KbOwner.COMPANION, KbOwner.EZBOARDS, KbKind.DATACARD_LEFT, null)))
            val tf = File(ownerDir, "7985.dds").also { it.writeBytes(tagged) }
            val t = KneeboardPrint.owners(tf, Dds.header(tagged), null)
            check(t.left == KbOwner.COMPANION && t.right == KbOwner.EZBOARDS && t.leftKind == KbKind.DATACARD_LEFT && t.rightKind == null,
                "our tag says who made each half and what was printed", "'${Dds.header(tagged).tag}' -> ${t.left}(${t.leftKind})/${t.right}")
            check(Dds.header(tagged).tag.length <= Dds.TAG_LENGTH, "the tag fits the header's reserved field", "${Dds.header(tagged).tag.length} of ${Dds.TAG_LENGTH}")
            // a kind past the sixteenth (Picture, 1.3.8) is one base-36 digit, and the first sixteen read as they did
            val picTagged = Dds.print(kto.page(4)!!.readBytes(), Dds.header(kto.page(4)!!.readBytes()), arrayOf(null, null),
                KneeboardPrint.tag(KneeboardPrint.Owners(KbOwner.COMPANION, KbOwner.COMPANION, KbKind.PICTURE, KbKind.ALTERNATE)))
            val pf = File(ownerDir, "7985-picture.dds").also { it.writeBytes(picTagged) }
            val pt = KneeboardPrint.owners(pf, Dds.header(picTagged), null)
            check(pt.leftKind == KbKind.PICTURE && pt.rightKind == KbKind.ALTERNATE && Dds.header(picTagged).tag.endsWith(" Lcg Rcf"),
                "a Picture half is tagged and read back (base-36 kind 'g'; Alternate still 'f')", "'${Dds.header(picTagged).tag}' -> ${pt.leftKind}/${pt.rightKind}")
            // another tool's page: a DXT1 file with some other layout
            val other = Dds.newFile(Dds.Kind.DXT1, 64, 1, -1)
            val of = File(ownerDir, "7986.dds").also { it.writeBytes(other) }
            check(KneeboardPrint.owners(of, Dds.header(other), kto.shippedPage(5)).left == KbOwner.OTHER, "any other page is another tool's")
            check(KneeboardPrint.owners(File(ownerDir, "7999.dds"), Dds.header(ByteArray(0), 0), null).left == KbOwner.MISSING, "no file: missing")
        }
        run {
            // EZBoards' claimed halves: from its CONFIG_USER.BAT, REM'd and empty lines ignored (a harmless EZBOARDS.BAT, so it counts as set up)
            val ez = File(pictures, "ezclaims").also { it.mkdirs() }
            File(ez, "EZBOARDS.BAT").writeText("@EXIT /B 1\r\n")
            File(ez, "CONFIG_USER.BAT").writeText(
                listOf(
                    "REM /// in-game kneeboards", "SET KNEEBOARD[F16_1L]=page_1", "SET KNEEBOARD[F16_1R]=page_2", "SET KNEEBOARD[F15_1]=page_1",
                    "SET KNEEBOARD[F15_2]=page_2", "REM SET KNEEBOARD[F16_5L]=page_1", ":: SET KNEEBOARD[F16_6R]=page_1", "SET KNEEBOARD[F16_7L]=",
                    "set kneeboard[f16_9r]=page_2",
                ).joinToString("\r\n")
            )
            val claims = KneeboardPrint.ezClaims(ez)
            check(claims == setOf("1L", "1R", "9R"), "EZBoards' claims from CONFIG_USER.BAT (F-16 lines only; REM, :: and empty ones ignored)", claims.joinToString())
            val s = KneeboardPrint.state(kto, ez.path, false)
            check(s.pages[0].ezLeft && s.pages[0].ezRight && s.pages[8].ezRight && !s.pages[8].ezLeft && s.pages.count { it.ezLeft || it.ezRight } == 2,
                "the state marks those halves", "ezConfig ${s.ezConfig}")
            check(s.ezConfig == "ezclaims\\CONFIG_USER.BAT", "the config is named without an absolute path", "${s.ezConfig}")
            val stripped = File(root, "Tools\\EZBoards")
            if (EzBoardsRunner.isValidDir(stripped.path)) {
                val s2 = KneeboardPrint.state(kto, stripped.path, true)
                check(s2.ezConfig == "Tools\\EZBoards\\CONFIG_USER.BAT" && s2.pages.none { it.ezLeft || it.ezRight } && s2.bmsIn3d,
                    "the copy's stripped EZBoards: named, claims nothing", "${s2.ezConfig}")
            }
            val s3 = KneeboardPrint.state(kto, File(pictures, "no-ezboards").path, false)
            check(s3.ezConfig == null, "no EZBoards: no config named")
            val slot = s.pages[0]
            check(slot.format == "DXT1" && slot.mips == 1 && slot.size == 2048 && slot.problem == null && s.pages[1].format == "BGRA" && s.pages[15].format == "DXT5" && s.pages[15].mips == 12,
                "the state gives each file's format, levels and size", "${slot.format}/${slot.mips}/${slot.size}, ${s.pages[1].format}, ${s.pages[15].format}/${s.pages[15].mips}")
            check(s.shipped && !s.twin && s.folder == kto.folderLabel && s.theater == "Korea KTO" && s.error == null, "the state's folder, theater, shipped copies, no twin",
                "${s.folder}, ${s.theater}, shipped=${s.shipped}")
        }
        line("")

        // ---------------------------------------------------------------- 3-4. a print
        line("== 3. Before: every file of the copy hashed")
        val before = snapshot(root)
        line("  ${before.size} files")
        line("")
        line("== 4. Print: the test page on 1L and 1R, a DataCard-like page on 2L, the test page on 16R")
        val dataCard = pngs.getOrNull(0)?.let { ImageIO.read(it) } ?: dataCardPage()
        val test = { label: String -> pngs.getOrNull(1)?.let { ImageIO.read(it) } ?: testPage(label) }
        class Job(val n: Int, val left: BufferedImage?, val right: BufferedImage?, val kl: String?, val kr: String?)
        val jobs = listOf(
            Job(1, test("1L"), test("1R"), KbKind.TEST, KbKind.TEST),
            Job(2, dataCard, null, KbKind.DATACARD_LEFT, null),
            Job(16, null, test("16R"), null, KbKind.TEST),
        )
        jobs.forEach { j ->
            j.left?.let { png(it, File(pictures, "page-${j.n}L.png")) }
            j.right?.let { png(it, File(pictures, "page-${j.n}R.png")) }
        }
        val original = HashMap<Int, ByteArray>()
        val afterPrint = HashMap<Int, ByteArray>()
        val times = ArrayList<String>()
        val texconvLevels = HashMap<Int, Pair<Double, Double>>()
        fun checkWritten(n: Int, was: ByteArray, now: ByteArray, rasters: Array<IntArray?>) {
            val name = KneeboardPrint.pageName(n)
            val bi = Dds.header(was)
            val ai = Dds.header(now)
            check(now.size == was.size, "$name: the same size", "${now.size} of ${was.size}")
            val headSame = (0 until Dds.HEADER).all { it in Dds.TAG_OFFSET until Dds.TAG_OFFSET + Dds.TAG_LENGTH || now[it] == was[it] }
            check(headSame && ai.tag.startsWith(KneeboardPrint.TAG), "$name: the header as it was, but for the tag at offset 32", "'${bi.tag}' -> '${ai.tag}'")
            check(ai.format == bi.format && ai.levels == bi.levels, "$name: format and mips as they were", "${bi.format}/${bi.levels} -> ${ai.format}/${ai.levels}")
            for (s in 0..1) {
                val side = if (s == 0) "left" else "right"
                val r = rasters[s]
                if (r != null) {
                    val db = Dds.psnr(r, halfPx(now, ai, 0, s))
                    check(db >= 32.0, "$name: the printed $side half decodes at %.1f dB".format(db), "floor 32 dB")
                } else {
                    val levels = (0 until bi.levels).filter { copyable(bi, it) }
                    val same = levels.count { DdsTest.halfBytes(was, bi, it, s).contentEquals(DdsTest.halfBytes(now, ai, it, s)) }
                    check(same == levels.size, "$name: the $side half, not printed, byte-identical on levels ${levels.firstOrNull()}-${levels.lastOrNull()}", "$same of ${levels.size}")
                }
            }
            if (ai.levels > 1) {
                // (a) each level against the box filter of the decoded level above; (b) against the source picture's own box-filtered chain
                var worst = 99.0
                var at = -1
                val per = ArrayList<String>()
                val src = Array(2) { s -> rasters[s]?.let { arrayListOf(it) } }
                for (l in 1 until ai.levels) {
                    for (s in 0..1) src[s]?.let { c -> c.add(Dds.boxDown(c[l - 1], maxOf(1, ai.levelWidth(l - 1) / 2), ai.levelHeight(l - 1))) }
                    val a: Double
                    val b: Double
                    if (copyable(ai, l)) {
                        val sides = (0..1).filter { rasters[it] != null }
                        a = sides.minOfOrNull { s ->
                            val up = halfPx(now, ai, l - 1, s)
                            Dds.psnr(Dds.boxDown(up, ai.levelWidth(l - 1) / 2, ai.levelHeight(l - 1)), halfPx(now, ai, l, s))
                        } ?: 99.0
                        b = sides.minOfOrNull { s -> Dds.psnr(src[s]!![l], halfPx(now, ai, l, s)) } ?: 99.0
                    } else {
                        val up = Dds.decode(now, ai, l - 1, 0, 0, ai.levelWidth(l - 1), ai.levelHeight(l - 1))
                        a = Dds.psnr(Dds.boxDown(up, ai.levelWidth(l - 1), ai.levelHeight(l - 1)), Dds.decode(now, ai, l, 0, 0, ai.levelWidth(l), ai.levelHeight(l)))
                        b = a
                    }
                    per += "L$l %.1f/%.1f".format(a, b)
                    if (b < worst) { worst = b; at = l }
                }
                line("  $name mip levels, dB against (the box filter of the level above) / (the source picture's chain): " + per.joinToString(" "))
                check(worst >= 32.0, "$name: every mip level is the box filter of the level above, encoded; worst %.1f dB against the source chain (level $at)".format(worst), "floor 32 dB, as the printed half; texconv on the same levels: section 5")
            }
        }
        for (j in jobs) {
            val f = kto.page(j.n)!!
            val was = f.readBytes()
            original[j.n] = was
            val info = Dds.header(was)
            val rasters = arrayOf(j.left?.let { KneeboardPrint.raster(it, info.width / 2, info.height) }, j.right?.let { KneeboardPrint.raster(it, info.width / 2, info.height) })
            val printed = KneeboardPrint.printPage(kto, j.n, arrayOf(j.left, j.right), arrayOf(j.kl, j.kr))
            times += "${f.name} ${info.format}/${info.levels}: ${printed.ms} ms"
            check(printed.result.status == KbFileResult.WRITTEN, "${f.name} (${info.format}, ${info.levels} level(s)) written", "${printed.result.status} ${printed.result.reason ?: ""}")
            check(printed.ms < 2000, "${f.name}: encoded and written in ${printed.ms} ms", "target < 2000 ms")
            val now = f.readBytes()
            afterPrint[j.n] = now
            checkWritten(j.n, was, now, rasters)
            png(Dds.image(now, Dds.header(now)), File(pictures, "print1-${f.nameWithoutExtension}.png"))
        }
        val afterOne = snapshot(root)
        val changed = afterOne.keys.filter { before[it] != afterOne[it] }.sorted()
        val want = jobs.map { "${kto.folderLabel!!.lowercase()}\\${KneeboardPrint.pageName(it.n)}" }.sorted()
        check(changed == want && afterOne.keys == before.keys, "only those three files changed, and no file appeared or went", diff(before, afterOne))
        val st = KneeboardPrint.state(kto, null, false)
        fun o(n: Int) = st.pages[n - 1].let { "${it.left}(${it.leftKind ?: "-"})/${it.right}(${it.rightKind ?: "-"})" }
        check(o(1) == "companion(test)/companion(test)" && o(2) == "companion(datacard-l)/htmlbrief(-)" && o(16) == "bms(-)/companion(test)",
            "the owners now: page 1 ours both halves, 2 ours left and html_brief's right, 16 BMS's left and ours right", "${o(1)}, ${o(2)}, ${o(16)}")
        line("")

        // ---------------------------------------------------------------- 5. texconv
        line("== 5. BMS's own converter (texconv) reads the written files")
        if (texconv == null) line("  (no texconv.exe given or in the copy: skipped)")
        else for (j in jobs) {
            val f = kto.page(j.n)!!
            val dir = File(pictures, "texconv").also { it.mkdirs() }
            val p = ProcessBuilder(texconv.path, "-nologo", "-y", "-ft", "png", "-o", dir.path, f.path).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor(60, TimeUnit.SECONDS)
            val pngFile = File(dir, f.nameWithoutExtension + ".png")
            if (!pngFile.isFile) { check(false, "texconv reads ${f.name}", out.lines().filter { it.isNotBlank() }.takeLast(2).joinToString(" / ")); continue }
            val img = ImageIO.read(pngFile)
            val bytes = f.readBytes()
            val info = Dds.header(bytes)
            val (worst, over) = Dds.maxDiff(Dds.decode(bytes, info, 0, 0, 0, info.width, info.height), img.getRGB(0, 0, img.width, img.height, null, 0, img.width), 2)
            check(over == 0, "texconv reads ${f.name} as we do (±2)", "largest difference $worst, $over pixels over 2")
        }
        line("")

        if (texconv != null) {
            // the same source, level by level, through our encoder and through BMS's own converter (DXT5, uniform weights)
            line("  Our encoder against texconv's on 16R's source, level by level (dB against the source picture):")
            val dir = File(pictures, "texconv-levels").also { it.mkdirs() }
            val now = afterPrint[16]!!
            val ai = Dds.header(now)
            var src = KneeboardPrint.raster(jobs[2].right!!, ai.width / 2, ai.height)
            var w = ai.width / 2
            var h = ai.height
            for (l in 0 until ai.levels) {
                if (!copyable(ai, l)) break
                if (l > 0) { src = Dds.boxDown(src, w, h); w = maxOf(1, w / 2); h = maxOf(1, h / 2) }
                val ours = Dds.psnr(src, halfPx(now, ai, l, 1))
                val pngFile = File(dir, "L$l.png")
                png(Dds.toImage(src, w, h), pngFile)
                val p = ProcessBuilder(texconv.path, "-nologo", "-y", "-f", "BC3_UNORM", "-bc", "u", "-m", "1", "-ft", "dds", "-o", dir.path, pngFile.path)
                    .redirectErrorStream(true).start()
                p.inputStream.bufferedReader().readText()
                p.waitFor(60, TimeUnit.SECONDS)
                val dds = File(dir, "L$l.dds")
                val theirs = if (dds.isFile) dds.readBytes().let { b -> val i = Dds.header(b); if (i.readable) Dds.psnr(src, Dds.decode(b, i, 0, 0, 0, w, h)) else null } else null
                line("    L$l ${w}x$h: ours %.1f, texconv %s".format(ours, theirs?.let { "%.1f".format(it) } ?: "-"))
                texconvLevels[l] = ours to (theirs ?: 0.0)
            }
            val behind = texconvLevels.entries.filter { (_, v) -> v.second > 0 && v.first < v.second - 0.5 }.map { it.key }
            check(texconvLevels.isNotEmpty() && behind.isEmpty(), "our encoder is as good as BMS's texconv at every level (within 0.5 dB)", if (behind.isEmpty()) "worst level %.1f dB (texconv %.1f)".format(texconvLevels.values.minOf { it.first }, texconvLevels.values.minOf { it.second }) else "behind on levels $behind")
        }
        line("")

        // ---------------------------------------------------------------- 6. print again
        line("== 6. Print again")
        val again = jobs.map { j -> KneeboardPrint.printPage(kto, j.n, arrayOf(j.left, j.right), arrayOf(j.kl, j.kr)).result }
        check(again.all { it.status == KbFileResult.UNCHANGED }, "the same print again: every file unchanged", again.joinToString { "${it.file} ${it.status}" })
        check(snapshot(root) == afterOne, "and nothing on disk moved")
        run {
            val f = kto.page(2)!!
            val p = KneeboardPrint.printPage(kto, 2, arrayOf(test("2L"), null), arrayOf(KbKind.TEST, null))
            val now = f.readBytes()
            val wi = Dds.header(original[2]!!)
            check(p.result.status == KbFileResult.WRITTEN && DdsTest.halfBytes(original[2]!!, wi, 0, 1).contentEquals(DdsTest.halfBytes(now, Dds.header(now), 0, 1)),
                "another page on 2L: 7983's right half is still html_brief's, byte for byte")
            val f16 = kto.page(16)!!
            val p16 = KneeboardPrint.printPage(kto, 16, arrayOf(test("16L"), null), arrayOf(KbKind.TEST, null))
            val n16 = f16.readBytes()
            val i16 = Dds.header(n16)
            val levels = (0 until i16.levels).filter { copyable(i16, it) }
            val kept = levels.count { DdsTest.halfBytes(afterPrint[16]!!, i16, it, 1).contentEquals(DdsTest.halfBytes(n16, i16, it, 1)) }
            check(p16.result.status == KbFileResult.WRITTEN && kept == levels.size, "the test page on 16L: the right half printed before is kept on levels 0-${levels.last()}", "$kept of ${levels.size}")
            val t16 = KneeboardPrint.state(kto, null, false).pages[15]
            check(t16.left == KbOwner.COMPANION && t16.right == KbOwner.COMPANION && t16.leftKind == KbKind.TEST, "and both halves are now ours")
        }
        line("")

        // ---------------------------------------------------------------- 7. the routes
        line("== 7. The routes, in-process")
        routes(root, pictures, test, line, report)
        line("")

        // ---------------------------------------------------------------- 8. a HiRes twin
        line("== 8. A KoreaObj_HiRes twin is printed too, in its own size")
        run {
            val hi = File(kto.dir!!.parentFile, "KoreaObj_HiRes")
            val made = !hi.exists()
            hi.mkdirs()
            val twinFile = File(hi, KneeboardPrint.pageName(9))
            try {
                twinFile.writeBytes(Dds.newFile(Dds.Kind.DXT1, 4096, 1, 0xFF6080A0.toInt()))
                val k2 = KneeboardPrint.place(root, "Korea KTO")
                check(KneeboardPrint.state(k2, null, false).twin, "the state says there is a twin")
                val was = twinFile.readBytes()
                val img = test("9L")
                val p = KneeboardPrint.printPage(k2, 9, arrayOf(img, null), arrayOf(KbKind.TEST, null))
                val now = twinFile.readBytes()
                val ti = Dds.header(now)
                check(p.result.status == KbFileResult.WRITTEN && p.twin?.status == KbFileResult.WRITTEN, "page 9 and its 4096-pixel twin written in ${p.ms} ms",
                    "${p.result.status}, twin ${p.twin?.status} ${p.twin?.reason ?: ""}")
                check(now.size == was.size && ti.width == 4096, "the twin keeps its size")
                val db = Dds.psnr(KneeboardPrint.raster(img, 2048, 4096), halfPx(now, ti, 0, 0))
                check(db >= 32.0, "the twin's printed half at 2048x4096 decodes at %.1f dB".format(db))
                check(DdsTest.halfBytes(was, Dds.header(was), 0, 1).contentEquals(DdsTest.halfBytes(now, ti, 0, 1)), "the twin's other half is kept")
            } finally {
                if (made) hi.deleteRecursively() else twinFile.delete()
            }
            check(!KneeboardPrint.state(KneeboardPrint.place(root, "Korea KTO"), null, false).twin, "twin removed again")
        }
        line("")

        // ---------------------------------------------------------------- 9. failures
        line("== 9. Failures: each a sentence, the file left as it was")
        val dir = kto.dir!!
        fun refused(n: Int, what: String, expect: (String) -> Boolean, setup: () -> Unit, undo: () -> Unit, verifyUndo: Boolean = true) {
            val f = kto.page(n)!!
            val shaBefore = if (f.isFile) sha(f) else null
            try {
                setup()
                val shaSet = runCatching { sha(f) }.getOrNull()
                val result = KneeboardPrint.printPage(KneeboardPrint.place(root, "Korea KTO"), n, arrayOf(test("${n}L"), null), arrayOf(KbKind.TEST, null)).result
                val after = runCatching { sha(f) }.getOrNull()
                check(result.status == KbFileResult.REFUSED && expect(result.reason.orEmpty()) && after == shaSet && strayFiles(dir).isEmpty(),
                    "$what: refused, nothing written", result.reason ?: result.status)
            } catch (e: Throwable) {
                check(false, "$what: refused without an exception", "${e::class.java.simpleName}: ${e.message}")
            } finally {
                runCatching { undo() }.onFailure { check(false, "$what: undone", "${it.message}") }
            }
            if (verifyUndo && shaBefore != null) check(f.isFile && sha(f) == shaBefore, "$what: undone (${f.name} as before)")
        }
        fun icacls(vararg a: String): Pair<Int, String> {
            val p = ProcessBuilder(listOf("icacls") + a).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor(30, TimeUnit.SECONDS)
            return p.exitValue() to out.lines().firstOrNull { it.isNotBlank() }.orEmpty()
        }
        refused(5, "writes denied in the folder (icacls deny W to Everyone)", { it.startsWith("Windows would not let BMS Companion write") },
            { val (code, out) = icacls(dir.path, "/deny", "*S-1-1-0:(OI)(CI)(W)"); check(code == 0, "icacls deny", out) },
            { val (code, out) = icacls(dir.path, "/remove:d", "*S-1-1-0"); check(code == 0, "icacls remove the deny", out) })
        run {
            val probe = File(dir, "bmsc-probe.tmp")
            val ok = runCatching { probe.writeText("x"); probe.delete() }.getOrDefault(false)
            check(ok && !probe.exists(), "the folder is writable again")
        }
        var handle: WinNT.HANDLE? = null
        refused(6, "the file held open by another program (share: read only)", { it.contains("in use by another program") },
            {
                handle = Kernel32.INSTANCE.CreateFile(kto.page(6)!!.path, WinNT.GENERIC_READ, WinNT.FILE_SHARE_READ, null, WinNT.OPEN_EXISTING, WinNT.FILE_ATTRIBUTE_NORMAL, null)
                check(handle != null && handle != WinBase.INVALID_HANDLE_VALUE, "held open", "")
            },
            { handle?.let { Kernel32.INSTANCE.CloseHandle(it) } })
        run {
            val f = kto.page(14)!!
            val keep = f.readBytes()
            f.delete()
            try {
                val r = KneeboardPrint.printPage(KneeboardPrint.place(root, "Korea KTO"), 14, arrayOf(test("14L"), null), arrayOf(KbKind.TEST, null)).result
                check(r.status == KbFileResult.REFUSED && r.reason.orEmpty().contains("never creates") && !f.exists(), "a missing page: refused, never created", r.reason ?: "")
                check(KneeboardPrint.state(kto, null, false).pages[13].missing, "the state says it is missing")
                val rs = KneeboardPrint.putShipped(kto, listOf(14))
                check(rs.single().status == KbFileResult.REFUSED && !f.exists(), "putting BMS's page back does not create it either", rs.single().reason ?: "")
            } finally {
                f.writeBytes(keep)
            }
            val ro = KneeboardPrint.printPage(ofm, 5, arrayOf(test("5L"), null), arrayOf(KbKind.TEST, null)).result
            check(ro.status == KbFileResult.REFUSED && ro.reason.orEmpty().contains("never creates"), "OFM KTO page 5 (not there): refused", ro.reason ?: "")
        }
        run {
            val f = kto.page(13)!!
            val keep = f.readBytes()
            refused(13, "a DX10 header", { it.contains("DirectX 10") },
                { f.writeBytes(keep.copyOf().also { System.arraycopy("DX10".toByteArray(), 0, it, 84, 4) }) },
                { f.writeBytes(keep) })
            f.writeBytes(keep.copyOf().also { System.arraycopy("DX10".toByteArray(), 0, it, 84, 4) })
            val s = KneeboardPrint.state(kto, null, false).pages[12]
            check(s.format == "DX10" && s.problem != null, "the state names the format and the problem", "${s.format}: ${s.problem}")
            f.writeBytes(keep)
        }
        run {
            val f = kto.page(12)!!
            val keep = f.readBytes()
            refused(12, "a file cut short", { it.contains("cut short") }, { f.writeBytes(keep.copyOf(3_000_000)) }, { }, verifyUndo = false)
            val s = KneeboardPrint.state(kto, null, false).pages[11]
            check(s.problem?.contains("cut short") == true, "the state says it is cut short", s.problem ?: "")
            val r = KneeboardPrint.putShipped(kto, listOf(12)).single()
            check(r.status == KbFileResult.WRITTEN && sha(f) == sha(kto.shippedPage(12)!!), "BMS's shipped copy rebuilds it", "${r.status} ${r.reason ?: ""}")
            if (!keep.contentEquals(f.readBytes())) f.writeBytes(keep)
        }
        run {
            val f = kto.page(11)!!
            refused(11, "a read-only file", { it.contains("read-only") }, { f.setReadOnly() }, { f.setWritable(true) })
        }
        check(strayFiles(dir).isEmpty(), "no .bmsc-new file left in the folder")
        line("")

        // ---------------------------------------------------------------- 10. BMS's own pages back
        line("== 10. Put BMS's own pages back")
        val rs = KneeboardPrint.putShipped(kto, (1..16).toList())
        line("  " + rs.joinToString(" ") { "${it.file}=${it.status}" })
        check(rs.none { it.status == KbFileResult.REFUSED }, "Korea KTO: nothing refused", rs.filter { it.status == KbFileResult.REFUSED }.joinToString { "${it.file}: ${it.reason}" })
        val equal = (1..16).count { n -> kto.page(n)!!.isFile && sha(kto.page(n)!!) == sha(kto.shippedPage(n)!!) }
        check(equal == 16, "every page is SHA-equal to BMS's DDS_Backup copy", "$equal of 16")
        check(KneeboardPrint.state(kto, null, false).pages.all { it.left == KbOwner.BMS && it.right == KbOwner.BMS }, "and every page is BMS's again")
        val rs2 = KneeboardPrint.putShipped(kto, (1..16).toList())
        check(rs2.all { it.status == KbFileResult.UNCHANGED }, "again: every page unchanged")
        if (hellas.shipped != null) {
            val h = KneeboardPrint.putShipped(hellas, listOf(1)).single()
            check(h.status == KbFileResult.WRITTEN && sha(hellas.page(1)!!) == sha(hellas.shippedPage(1)!!), "Hellas page 1 (EZBoards') back to BMS's own", "${h.status} ${h.reason ?: ""}")
        }
        if (balkans.shipped != null) {
            val b = KneeboardPrint.putShipped(balkans, (1..16).toList())
            check(b.all { it.status == KbFileResult.UNCHANGED }, "Balkans: already BMS's own, all unchanged", b.map { it.status }.distinct().joinToString())
        }
        val o1 = KneeboardPrint.putShipped(ofm, listOf(1)).single()
        check(o1.status == KbFileResult.REFUSED && o1.reason.orEmpty().contains("ships no copy"), "OFM KTO: no shipped copies, refused", o1.reason ?: "")
        line("")

        // ---------------------------------------------------------------- 11. the picture
        line("== 11. The test page on the left knee, BMS's own page on the right (7983.dds)")
        run {
            val f = kto.page(2)!!
            val was = f.readBytes()
            val p = KneeboardPrint.printPage(kto, 2, arrayOf(test("2L"), null), arrayOf(KbKind.TEST, null))
            val now = f.readBytes()
            val wi = Dds.header(was)
            val ni = Dds.header(now)
            val levels = (0 until wi.levels).filter { copyable(wi, it) }
            val kept = levels.count { DdsTest.halfBytes(was, wi, it, 1).contentEquals(DdsTest.halfBytes(now, ni, it, 1)) }
            check(p.result.status == KbFileResult.WRITTEN && kept == levels.size && ni.format == "DXT5" && ni.levels == 12,
                "7983.dds (BMS's DXT5, 12 levels): left printed, right kept on levels 0-${levels.last()}", "$kept of ${levels.size}, ${p.ms} ms")
            png(Dds.image(now, ni), File(pictures, "done-7983.png"))
            KneeboardPrint.halfImage(now, 0, 512)?.let { png(it, File(pictures, "done-7983-left-as-seen.png")) }
            KneeboardPrint.halfImage(now, 1, 512)?.let { png(it, File(pictures, "done-7983-right-as-seen.png")) }
            line("  pictures: done-7983.png (the whole texture), done-7983-left/right-as-seen.png (each half at the pad's 2:3)")
        }
        line("")
        line("== Timing")
        times.forEach { line("  $it") }
    }

    // ------------------------------------------------------------------ 7. routes

    private fun routes(root: File, pictures: File, test: (String) -> BufferedImage, line: (String) -> Unit, report: (Boolean, String, String) -> Unit) {
        fun check(ok: Boolean, what: String, detail: String = "") = report(ok, what, detail)
        val before = Bridge.settings.value
        val wasRunning = Bridge.running
        try {
            Bridge.update { it.copy(BmsDirOverride = root.path, AutoEzBoardsOnPrint = false) }
            Bridge.startForCheck()
            check(Bridge.install.baseDir?.let { Theaters.canonical(File(it)) } == Theaters.canonical(root), "the bridge reads the copy", "")
            line("  guard on: ${DevGuard.on}; the copy is ${DevGuard.why(root) ?: "a copy (writes allowed)"}")
            fun call(method: String, path: String, query: Map<String, String> = emptyMap(), body: String = ""): ApiResponse =
                Bridge.handle(ApiRequest(method, path, query, body.toByteArray(Charsets.UTF_8)))
            fun text(r: ApiResponse) = r.body.toString(Charsets.UTF_8)
            val here = KneeboardPrint.place(root, Bridge.install.theater)
            line("  BMS is set to: ${Bridge.install.theater} -> ${here.folderLabel}")

            val s = call("GET", "/api/kbprint/state")
            val state = runCatching { client.decodeFromString(KbPrintState.serializer(), text(s)) }.getOrNull()
            check(s.status == 200 && state != null && state.pages.size == 16 && state.theater == here.theater?.name && state.folder == here.folderLabel,
                "GET /api/kbprint/state: 200, sixteen pages, the current theater's folder", "${s.status} ${state?.theater} ${state?.folder}")
            check(state?.ezConfig == "Tools\\EZBoards\\CONFIG_USER.BAT", "the settings' EZBoards config is named", "${state?.ezConfig}")

            val t = call("GET", "/api/kbprint/thumb", mapOf("n" to "4", "side" to "L", "w" to "256"))
            val img = if (t.status == 200) runCatching { ImageIO.read(ByteArrayInputStream(t.body)) }.getOrNull() else null
            check(t.status == 200 && t.contentType == "image/jpeg" && img?.width == 256 && img.height == 384, "GET thumb n=4 L w=256: a 256x384 JPEG (the pad's 2:3)",
                "${t.status} ${t.contentType} ${img?.width}x${img?.height}")
            if (t.status == 200) File(pictures, "thumb-4L.jpg").writeBytes(t.body)
            check(call("GET", "/api/kbprint/thumb", mapOf("n" to "4", "side" to "X")).status == 400, "thumb with side X: 400")
            check(call("GET", "/api/kbprint/thumb", mapOf("n" to "0", "side" to "L")).status == 400, "thumb with n=0: 400")

            check(call("POST", "/api/kbprint/file", body = "not json").status == 400, "POST file with a body that is not a page: 400")
            check(call("POST", "/api/kbprint/file", body = """{"n":5,"left":{"kind":"test","png":"bm90IGEgcGljdHVyZQ=="}}""").status == 400, "POST file whose picture is not a picture: 400")
            check(call("POST", "/api/kbprint/file", body = """{"n":17}""").status == 400, "POST file for page 17: 400")
            val page5 = here.page(5)
            if (page5 != null && page5.isFile) {
                val img5 = test("5L")
                val send = KbFileSend(5, left = KbHalf(KbKind.TEST, "Test page", pngBase64(img5)), right = KbHalf(KbKind.LEAVE, "Leave as is", ""))
                val body = Json.encodeToString(KbFileSend.serializer(), send)
                val r = call("POST", "/api/kbprint/file", body = body)
                val res = runCatching { client.decodeFromString(KbFileResult.serializer(), text(r)) }.getOrNull()
                val now = page5.readBytes()
                val ni = Dds.header(now)
                val db = Dds.psnr(KneeboardPrint.raster(img5, ni.width / 2, ni.height), halfPx(now, ni, 0, 0))
                check(r.status == 200 && res?.status == KbFileResult.WRITTEN && db >= 32.0, "POST file n=5 (a PNG in base64): written, %.1f dB".format(db), "${r.status} ${text(r).take(120)}")
                val r2 = call("POST", "/api/kbprint/file", body = """{"n":5,"left":{"kind":"leave"}}""")
                check(r2.status == 200 && text(r2).contains("\"unchanged\""), "POST file with every half left as it is: unchanged", text(r2))
                val sh = call("POST", "/api/kbprint/shipped", mapOf("n" to "5"))
                val list = runCatching { client.decodeFromString(ListSerializer(KbFileResult.serializer()), text(sh)) }.getOrNull()
                val shipped = here.shippedPage(5)
                check(sh.status == 200 && list?.singleOrNull()?.status == KbFileResult.WRITTEN && shipped != null && sha(page5) == sha(shipped),
                    "POST shipped n=5: BMS's own page back, SHA-equal", "${sh.status} ${text(sh)}")
                check(call("POST", "/api/kbprint/shipped", mapOf("n" to "x")).status == 400, "POST shipped n=x: 400")
            } else line("  (the current theater has no page 5 in this copy: file and shipped routes not called)")
        } finally {
            Bridge.update { before }
            if (!wasRunning) Bridge.stop()
        }
        check(Bridge.settings.value == before, "the settings are back as they were", "")
    }
}
