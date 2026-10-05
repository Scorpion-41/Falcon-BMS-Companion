package com.bmscompanion.desktop.bridge

import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinBase
import com.sun.jna.platform.win32.WinNT
import java.io.File

/**
 * Proves the display reader against textures we publish ourselves.
 *
 * Falcon BMS has to be in 3D before there is a real picture to read, which is not something a check can arrange.
 * What a check *can* do is put a known picture where BMS puts its own — same two shared memory areas, same layout
 * fields — and insist that [RttTextures] hands back exactly the pixels that went in.
 *
 * **The interesting case is the row pitch.** A Direct3D staging texture pads each row, and the padding cannot be
 * recovered from outside: Windows rounds a section up to whole pages when it is created, so a 1024 x 500 texture at
 * pitch 4160 asks for 2,080,000 bytes and every way of measuring it afterwards — `VirtualQuery` on the view,
 * `NtQuerySection` on the section — answers 2,080,768. Dividing that by the height gives anything from 4156 to
 * 4161, and a pitch wrong by four bytes shears the picture one pixel further over on every row. So the pitch is
 * *chosen* from the alignments a graphics driver actually uses, and a shape that matches none of them is refused.
 *
 * Hence three cases, and the third matters as much as the other two:
 *
 * | case | pitch | what must happen |
 * |---|---|---|
 * | A | exactly `width * 4` | every pixel exact |
 * | B | `width * 4 + 256`, a driver alignment | every pixel exact |
 * | C | `width * 4 + 64`, which no alignment explains | refused, with a reason |
 *
 * Those three are the area with no header, kept for anything that publishes one so. **Falcon BMS's own area carries
 * a header** — "DDS " and a 124-byte DDS_HEADER with the width, height, row pitch and pixel format, the pixels from
 * byte 128, read out of BMS's own code (see `RttTextures.shapeOf`) — and the BMS-n cases publish exactly that, at
 * the sizes a cockpit export takes (1024 x 500, 1024 x 1024, 2048 x 2048, 1200 x 900 and 1366 x 768 with rows padded
 * to 256 bytes, a pitch of width x 4 + 64 that only the header can explain) and with 64-bit pixels, which must be
 * refused with a reason. Each readable case also goes over the wire the way a phone gets it — the JPEG
 * `/api/rtt/img` sends, decoded, at the widths a device asks for, and the 204 for the picture it already has.
 * `--rtttest` against a real BMS prints the header it finds ([RttTextures.diagnose]).
 */
object RttSelfTest {
    private const val TEX_W = 1024

    /** Not a height that makes the mapping land on whole pages, so the page rounding is in play throughout. */
    private const val TEX_H = 500

    private val RECTS = listOf(
        intArrayOf(0, 0, 400, 200),        // HUD
        intArrayOf(400, 0, 600, 100),      // PFL
        intArrayOf(600, 0, 900, 80),       // DED
        intArrayOf(0, 200, 200, 400),      // RWR
        intArrayOf(200, 200, 456, 450),    // MFDLEFT
        intArrayOf(456, 200, 712, 450),    // MFDRIGHT
        intArrayOf(712, 200, 812, 300),    // HMS
    )

    fun run(outDir: File?): String {
        val report = StringBuilder()
        var failures = 0
        // Falcon BMS's own layout (read from its code, see RttTextures.shapeOf): "DDS ", a 124-byte header with the
        // width, height, pitch and pixel format, the pixels from byte 128. At the sizes a cockpit's export takes, with
        // rows as Direct3D pads them, and one a header says it cannot be read.
        failures += case(report, outDir, "BMS-1", TEX_W * 4, dds = true, mustRead = true)
        failures += case(report, null, "BMS-2", TEX_W * 4 + 64, dds = true, mustRead = true)
        failures += case(report, null, "BMS-3", 1024 * 4, dds = true, mustRead = true, w = 1024, h = 1024)
        failures += case(report, null, "BMS-4", 2048 * 4, dds = true, mustRead = true, w = 2048, h = 2048)
        failures += case(report, null, "BMS-5", (1200 * 4 + 255) / 256 * 256, dds = true, mustRead = true, w = 1200, h = 900)
        failures += case(report, null, "BMS-6", (1366 * 4 + 255) / 256 * 256, dds = true, mustRead = true, w = 1366, h = 768)
        failures += case(report, null, "BMS-7", TEX_W * 8, dds = true, mustRead = false, bits = 64)
        // An area with no header, measured as the first readers did: kept for anything that publishes one so.
        failures += case(report, null, "A", TEX_W * 4, dds = false, mustRead = true)
        failures += case(report, null, "B", TEX_W * 4 + 256, dds = false, mustRead = true)
        failures += case(report, null, "C", TEX_W * 4 + 64, dds = false, mustRead = false)
        failures += phases(report)
        report.appendLine()
        report.appendLine(
            if (failures == 0) "PASS — exactly what was published comes back, and what cannot be explained is refused"
            else "FAIL — $failures check(s)",
        )
        return report.toString()
    }

    /** One published texture, read back. Returns how many checks failed. */
    private fun case(
        report: StringBuilder, outDir: File?, name: String, pitch: Int, dds: Boolean, mustRead: Boolean,
        w: Int = TEX_W, h: Int = TEX_H, bits: Int = 32,
    ): Int {
        var failures = 0
        fun check(what: String, ok: Boolean, detail: String = "") {
            report.appendLine((if (ok) "  ok   " else "  FAIL ") + what + (if (detail.isEmpty()) "" else "  — $detail"))
            if (!ok) failures++
        }
        val TEX_W = w
        val TEX_H = h
        val start = if (dds) RttTextures.DDS_DATA else 0L

        // BMS asks for exactly this: RowPitch * height + 128 (the header), which Windows rounds up to a page
        val mapping = start + pitch.toLong() * TEX_H
        report.appendLine()
        report.appendLine(
            "case $name: $TEX_W x $TEX_H ${if (bits == 32) "BGRA" else "$bits-bit"} at pitch $pitch (width*4" +
                (if (pitch == TEX_W * 4) "" else " + ${pitch - TEX_W * 4}") + ")" +
                (if (dds) " after BMS's DDS header" else ", no header") + ", $mapping bytes, " +
                "which Windows reports as ${(mapping + 4095) / 4096 * 4096}",
        )

        val fd2 = create(SharedMemoryReader.FLIGHT_DATA2, FD2.size.toLong())
        if (fd2 == null) {
            report.appendLine("  could not create ${SharedMemoryReader.FLIGHT_DATA2} — is Falcon BMS or another tool publishing it?")
            return 1
        }
        val tex = create(RttTextures.AREA, mapping)
        if (tex == null) {
            closeAll(fd2)
            report.appendLine("  could not create ${RttTextures.AREA}")
            return 1
        }

        try {
            fd2.second.setShort(FD2.rttSize.toLong(), TEX_W.toShort())
            fd2.second.setShort((FD2.rttSize + 2).toLong(), TEX_H.toShort())
            RECTS.forEachIndexed { i, r ->
                val at = (FD2.rttArea + i * 8).toLong()
                for (k in 0..3) fd2.second.setShort(at + k * 2, r[k].toShort())
            }

            // the header exactly as Falcon BMS writes it (RttTextures.shapeOf)
            if (dds) writeDdsHeader(tex.second, TEX_W, TEX_H, pitch, bits)
            // a texture whose every pixel says where it is, so a shifted or sheared read cannot pass
            val row = ByteArray(pitch)
            for (y in 0 until TEX_H) {
                for (x in 0 until TEX_W) {
                    val (r, g, b) = colourAt(x, y)
                    row[x * 4] = b.toByte()
                    row[x * 4 + 1] = g.toByte()
                    row[x * 4 + 2] = r.toByte()
                    row[x * 4 + 3] = 0xFF.toByte()
                }
                // the padding holds a value that is never a legal colour here, so reading it shows up at once
                for (k in TEX_W * 4 until pitch) row[k] = 0x7F
                tex.second.write(start + y.toLong() * pitch, row, 0, pitch)
            }

            RttTextures.requireBms = false
            // The reader keeps the area open between frames; each case publishes a new one under the same name.
            RttTextures.reset()
            try {
                val state = RttTextures.state(inCockpit = true)
                if (!mustRead) {
                    check("refused, rather than drawn crooked", !state.available, state.reason ?: "")
                    for (d in RttTextures.Display.entries) check("${d.id} gives nothing", RttTextures.grab(d) == null)
                    return failures
                }

                check("available", state.available, state.reason ?: "")
                check("texture size read back", state.width == TEX_W && state.height == TEX_H, "${state.width} x ${state.height}")
                check("all seven displays listed", state.areas.size == 7, "${state.areas.size}")

                for (d in RttTextures.Display.entries) {
                    val r = RECTS[d.ordinal]
                    val wantW = r[2] - r[0]
                    val wantH = r[3] - r[1]
                    val img = RttTextures.grab(d)
                    if (img == null) { check(d.id, false, "no image"); continue }
                    if (img.width != wantW || img.height != wantH) {
                        check(d.id, false, "size ${img.width} x ${img.height}, wanted $wantW x $wantH")
                        continue
                    }
                    var wrong = 0
                    var firstWrong = ""
                    for (y in 0 until img.height) {
                        for (x in 0 until img.width) {
                            val (er, eg, eb) = colourAt(r[0] + x, r[1] + y)
                            val expect = (er shl 16) or (eg shl 8) or eb
                            if ((img.getRGB(x, y) and 0xFFFFFF) != expect) {
                                if (wrong == 0) firstWrong = "first at $x,$y"
                                wrong++
                            }
                        }
                    }
                    check("${d.id} ${img.width} x ${img.height}", wrong == 0, if (wrong == 0) "" else "$wrong wrong pixels, $firstWrong")
                    if (outDir != null) {
                        RttTextures.png(d)?.let { File(outDir.apply { mkdirs() }, "self-$name-${d.id}.png").writeBytes(it) }
                    }
                }
                val small = RttTextures.grab(RttTextures.Display.MFDLEFT, maxWidth = 128)
                check("scaling for a small card", small?.width == 128, "${small?.width}")
                // What a phone or a browser gets: `/api/rtt/img` answers RttTextures.encoded, and the device decodes
                // the JPEG (the browser and a linked PC with this same Skia; Android with its own decoder).
                for (d in listOf(RttTextures.Display.MFDLEFT, RttTextures.Display.MFDRIGHT)) {
                    val r = RECTS[d.ordinal]
                    val rw = r[2] - r[0]
                    val rh = r[3] - r[1]
                    for (ask in listOf(0, 128, 256, 384)) {
                        val e = RttTextures.encoded(d, ask, null)
                        val img = e?.bytes?.let { b -> runCatching { org.jetbrains.skia.Image.makeFromEncoded(b) }.getOrNull() }
                        val wantW = if (ask in 1 until rw) ask else rw
                        val wantH = if (ask in 1 until rw) maxOf(1, rh * ask / rw) else rh
                        if (img == null) { check("${d.id} over the wire at w=$ask", false, "nothing decoded (${e?.bytes?.size} bytes)"); continue }
                        val okSize = img.width == wantW && img.height == wantH
                        // the middle pixel within JPEG's error of what was published there
                        val bmp = org.jetbrains.skia.Bitmap.makeFromImage(img)
                        val c = bmp.getColor(img.width / 2, img.height / 2)
                        val (er, eg, eb) = colourAt(r[0] + (img.width / 2) * rw / img.width, r[1] + (img.height / 2) * rh / img.height)
                        val near = kotlin.math.abs(((c shr 16) and 0xFF) - er) < 40 && kotlin.math.abs(((c shr 8) and 0xFF) - eg) < 40 &&
                            kotlin.math.abs((c and 0xFF) - eb) < 40
                        bmp.close(); img.close()
                        val again = RttTextures.encoded(d, ask, e.hash)
                        check(
                            "${d.id} over the wire at w=$ask: JPEG ${img.width} x ${img.height}, colour right, then 204",
                            okSize && near && again === RttTextures.UNCHANGED,
                            "${e.bytes.size} bytes, wanted $wantW x $wantH, middle 0x${Integer.toHexString(c and 0xFFFFFF)} for $er,$eg,$eb",
                        )
                    }
                }
            } finally {
                RttTextures.reset()
                RttTextures.requireBms = true
            }
        } finally {
            closeAll(tex)
            closeAll(fd2)
        }
        return failures
    }

    /**
     * Every state the MFD glass explains, reached through [RttTextures.state] itself rather than handed to the card.
     *
     * The card's renders (`--mfdrender`) prove each state is worded and drawn; this proves the PC arrives at it. The
     * shared areas are published here the way BMS publishes them at each step — nothing, then the layout without a
     * texture, then a black texture, then a picture — and the answer is demanded at each one, along with the two
     * things that make the picture cheap: an unchanged display keeps its tag, and a client quoting the picture it
     * has gets the empty "unchanged" answer.
     */
    private fun phases(report: StringBuilder): Int {
        var failures = 0
        fun check(what: String, ok: Boolean, detail: String = "") {
            report.appendLine((if (ok) "  ok   " else "  FAIL ") + what + (if (detail.isEmpty()) "" else "  — $detail"))
            if (!ok) failures++
        }
        val off = com.bmscompanion.app.data.mission.RttConfig(on = false, launcher = true)
        val on = com.bmscompanion.app.data.mission.RttConfig(on = true, launcher = true)
        report.appendLine()
        report.appendLine("phases: what the glass is told at each step")

        if (!BmsInstall.isBmsRunning()) {
            RttTextures.reset()
            check("no Falcon BMS -> nobms", RttTextures.state(inCockpit = false, config = off).phase == com.bmscompanion.app.data.mission.RttPhase.NO_BMS)
        } else report.appendLine("  skip no Falcon BMS -> nobms (Falcon BMS is running on this PC)")

        val fd2 = create(SharedMemoryReader.FLIGHT_DATA2, FD2.size.toLong()) ?: run {
            report.appendLine("  FAIL could not create ${SharedMemoryReader.FLIGHT_DATA2}")
            return failures + 1
        }
        RttTextures.requireBms = false
        val shareWas = RttTextures.shareMs
        RttTextures.shareMs = 0
        fun layout(mfds: Boolean) {
            fd2.second.setShort(FD2.rttSize.toLong(), TEX_W.toShort())
            fd2.second.setShort((FD2.rttSize + 2).toLong(), TEX_H.toShort())
            RECTS.forEachIndexed { i, r ->
                val gone = !mfds && (i == RttTextures.Display.MFDLEFT.ordinal || i == RttTextures.Display.MFDRIGHT.ordinal)
                val at = (FD2.rttArea + i * 8).toLong()
                for (k in 0..3) fd2.second.setShort(at + k * 2, if (gone) 0 else r[k].toShort())
            }
            RttTextures.reset()
        }
        fun phase(cockpit: Boolean, cfg: com.bmscompanion.app.data.mission.RttConfig?) = RttTextures.state(inCockpit = cockpit, config = cfg).phase
        var tex: Pair<WinNT.HANDLE, com.sun.jna.Pointer>? = null
        try {
            // BMS in its menus: FlightData2 is there but publishes no texture, and there is no texture area
            RttTextures.reset()
            check("menus, no layout -> no3d", phase(false, off) == com.bmscompanion.app.data.mission.RttPhase.NO_3D, "${phase(false, off)}")
            check("cockpit, export off -> exportoff", phase(true, off) == com.bmscompanion.app.data.mission.RttPhase.EXPORT_OFF, "${phase(true, off)}")
            check("cockpit, config on but no area -> restart", phase(true, on) == com.bmscompanion.app.data.mission.RttPhase.RESTART, "${phase(true, on)}")
            layout(mfds = true)
            check("cockpit, layout but no area, export off -> exportoff", phase(true, off) == com.bmscompanion.app.data.mission.RttPhase.EXPORT_OFF, "${phase(true, off)}")

            // the export running, with a black texture: an unpowered jet
            val pitch = TEX_W * 4
            tex = create(RttTextures.AREA, RttTextures.DDS_DATA + pitch.toLong() * TEX_H) ?: run {
                report.appendLine("  FAIL could not create ${RttTextures.AREA}")
                return failures + 1
            }
            writeDdsHeader(tex.second, TEX_W, TEX_H, pitch)
            RttTextures.reset()
            check("export on, nothing read yet -> live (dark is judged only from frames being read)", phase(true, on) == com.bmscompanion.app.data.mission.RttPhase.LIVE, "${phase(true, on)}")
            val l0 = RttTextures.frame(RttTextures.Display.MFDLEFT)
            val r0 = RttTextures.frame(RttTextures.Display.MFDRIGHT)
            check("black texture read as dark", l0?.dark == true && r0?.dark == true)
            check("both displays black -> dark", phase(true, on) == com.bmscompanion.app.data.mission.RttPhase.DARK, "${phase(true, on)}")

            // the displays come on
            val row = ByteArray(pitch)
            for (y in 0 until TEX_H) {
                for (x in 0 until TEX_W) {
                    val (r, g, b) = colourAt(x, y)
                    row[x * 4] = b.toByte(); row[x * 4 + 1] = g.toByte(); row[x * 4 + 2] = r.toByte(); row[x * 4 + 3] = -1
                }
                tex.second.write(RttTextures.DDS_DATA + y.toLong() * pitch, row, 0, pitch)
            }
            val l1 = RttTextures.frame(RttTextures.Display.MFDLEFT)
            RttTextures.frame(RttTextures.Display.MFDRIGHT)
            check("a picture is a new frame", l1 != null && l1.tag != l0?.tag && !l1.dark)
            check("picture -> live", phase(true, on) == com.bmscompanion.app.data.mission.RttPhase.LIVE, "${phase(true, on)}")
            // Back in the menus with the area still published: a picture still moving is believed over the flag,
            // one that has stopped is taken as left behind (whether BMS keeps the area there is not known without it).
            check("out of the cockpit, picture still changing -> live", phase(false, on) == com.bmscompanion.app.data.mission.RttPhase.LIVE, "${phase(false, on)}")
            val keep = RttTextures.staleMs
            RttTextures.staleMs = 0
            check("out of the cockpit, picture stopped -> no3d", phase(false, on) == com.bmscompanion.app.data.mission.RttPhase.NO_3D, "${phase(false, on)}")
            RttTextures.staleMs = keep
            val l2 = RttTextures.frame(RttTextures.Display.MFDLEFT)
            check("an unchanged display keeps its frame and tag", l2 === l1)
            val e = RttTextures.encoded(RttTextures.Display.MFDLEFT, 0, null)
            check("a picture for the wire", e != null && e.bytes.isNotEmpty(), "${e?.bytes?.size} bytes")
            val again = RttTextures.encoded(RttTextures.Display.MFDLEFT, 0, e?.hash)
            check("the client's own picture quoted back -> unchanged (204, nothing sent)", again === RttTextures.UNCHANGED)

            // an aircraft whose cockpit publishes no MFD rectangles
            layout(mfds = false)
            check("no MFD rectangles -> nomfds", phase(true, on) == com.bmscompanion.app.data.mission.RttPhase.NO_MFDS, "${phase(true, on)}")
        } finally {
            RttTextures.reset()
            RttTextures.shareMs = shareWas
            RttTextures.requireBms = true
            tex?.let { closeAll(it) }
            closeAll(fd2)
        }
        return failures
    }

    /**
     * "DDS " and the 124-byte header, as Falcon BMS writes them at the start of its texture area: flags 0x100F
     * (caps, height, width, pitch, pixel format), the pixel format RGB with [bits] bits and BGRA masks, caps texture.
     */
    internal fun writeDdsHeader(p: com.sun.jna.Pointer, w: Int, h: Int, pitch: Int, bits: Int = 32) {
        p.setInt(0, RttTextures.DDS_MAGIC)
        p.setInt(4, 124)
        p.setInt(8, 0x100F)
        p.setInt(12, h)
        p.setInt(16, w)
        p.setInt(20, pitch)
        p.setInt(76, 32)           // pixel format: its size
        p.setInt(80, 0x40)         // DDPF_RGB
        p.setInt(88, bits)
        p.setInt(92, 0xFF0000)
        p.setInt(96, 0xFF00)
        p.setInt(100, 0xFF)
        p.setInt(108, 0x1000)      // DDSCAPS_TEXTURE
    }

    /** A colour that encodes the pixel's own position, so any offset error changes it. */
    private fun colourAt(x: Int, y: Int): Triple<Int, Int, Int> =
        Triple(x and 0xFF, y and 0xFF, ((x shr 8) shl 4 or (y shr 8)) and 0xFF)

    internal fun create(name: String, size: Long): Pair<WinNT.HANDLE, com.sun.jna.Pointer>? {
        val k = Kernel32.INSTANCE
        val handle = k.CreateFileMapping(
            WinBase.INVALID_HANDLE_VALUE, null, WinNT.PAGE_READWRITE,
            (size ushr 32).toInt(), (size and 0xFFFFFFFFL).toInt(), name,
        ) ?: return null
        val view = k.MapViewOfFile(handle, WinNT.FILE_MAP_WRITE, 0, 0, 0)
        if (view == null) { k.CloseHandle(handle); return null }
        return handle to view
    }

    internal fun closeAll(p: Pair<WinNT.HANDLE, com.sun.jna.Pointer>) {
        runCatching { Kernel32.INSTANCE.UnmapViewOfFile(p.second) }
        runCatching { Kernel32.INSTANCE.CloseHandle(p.first) }
    }
}
