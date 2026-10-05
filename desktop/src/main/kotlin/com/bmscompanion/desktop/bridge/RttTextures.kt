package com.bmscompanion.desktop.bridge

import com.bmscompanion.app.data.mission.RttArea
import com.bmscompanion.app.data.mission.RttConfig
import com.bmscompanion.app.data.mission.RttPhase
import com.bmscompanion.app.data.mission.RttState
import com.bmscompanion.app.data.mission.frameHash
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.BaseTSD
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinNT
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface
import java.awt.image.BufferedImage
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min

/**
 * The cockpit displays themselves, out of Falcon BMS's render-to-texture export.
 *
 * BMS draws the HUD, the PFL, the DED, the RWR, both MFDs and the HMS into one off-screen texture and, when
 * `g_bExportRTTTextures` is set, copies that texture into a shared memory area every frame — the same door BMS's
 * own `Tools/RTTRemote` goes through. So unlike the button legends in [Osb], this is the real picture: the radar
 * as the jet is painting it, the targeting pod video, whatever page the MFD is actually on.
 *
 * Three things have to line up, and none of them is guessed:
 *
 * 1. **The area is `FalconTexturesSharedMemoryArea`**, created by BMS with `CreateFileMapping` (the failure path in
 *    BMS itself reads "CreateFileMapping for RTT shared memory area failed").
 * 2. **Where each display sits inside it** comes from `FlightData2`: `RTT_size` is the whole texture and
 *    `RTT_area[7][4]` gives each display's left/top/right/bottom in it, in the order of `enum RTT_areas`. That is
 *    why this works for any aircraft — a cockpit with a different display layout simply publishes different rects.
 * 3. **The row pitch is BMS's own, not assumed.** The texture is copied out of a Direct3D staging buffer, whose rows
 *    are padded to the hardware's liking, so a row is not width x 4 bytes. The area is a DDS file in memory — "DDS ",
 *    a 124-byte header carrying the width, height, pitch and pixel format, then the pixels at byte 128 — so the
 *    pitch is read from the header ([shapeOf]). Getting this wrong is what shears the picture diagonally, and
 *    leaving out the 128 bytes is what made the first 1.3.8 test builds refuse BMS's picture outright.
 *
 * **What it costs, and why it is cheap.** A display is read only when something on screen asks for it, and what is
 * read is that display's own rectangle, not the texture. The door is opened once and kept open while pictures are
 * being asked for ([openDoor]), because opening, mapping and faulting in the pages of a many-megabyte section for
 * every frame cost more than the copy itself; it is closed a couple of seconds after the last request, and at once
 * when BMS goes away, so a section BMS will want to create again is never held. A frame is kept as BMS wrote it —
 * BGRA bytes — and compared with the last one ([frame]): an MFD on a page that is not moving is the same bytes, and
 * costs a comparison and nothing else, here or on the wire. The PC's own window draws those bytes directly, with no
 * encoding at all; only a phone or a browser gets a compressed picture ([encoded]), made once per changed frame
 * however many devices ask. `--mfdbench` measures all of it.
 *
 * BMS signals `BMS_RTTExport_Done` when a frame has been written. We do not wait on it: the app asks for a picture
 * when it wants to draw one, at its own rate, and a copy that catches BMS mid-write costs one slightly torn frame
 * now and then rather than a thread parked on an event.
 *
 * Nothing here throws. A BMS that is not running, an export that is switched off and an older BMS that has no such
 * area all come back the same way: [state] says it is not available and says why.
 */
object RttTextures {
    const val AREA = "FalconTexturesSharedMemoryArea"

    /** The BMS setting that opens the door, and BMS's own ceiling on how often it copies the displays out. */
    const val SETTING = "g_bExportRTTTextures"
    const val FPS_SETTING = "g_nRTTExport_FPS"

    /** `enum RTT_areas` in FlightData.h, in its own order — the index into `RTT_area[]`. */
    enum class Display(val id: String, val label: String) {
        HUD("hud", "HUD"),
        PFL("pfl", "PFL"),
        DED("ded", "DED"),
        RWR("rwr", "RWR"),
        MFDLEFT("mfdleft", "Left MFD"),
        MFDRIGHT("mfdright", "Right MFD"),
        HMS("hms", "HMS"),
        ;

        companion object {
            fun of(id: String?) = entries.firstOrNull { it.id.equals(id, ignoreCase = true) }
        }
    }

    /** One display's rectangle in the shared texture, as BMS publishes it. */
    private data class Rect4(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val w get() = right - left
        val h get() = bottom - top
        val real get() = w > 0 && h > 0
    }

    private class Layout(val texW: Int, val texH: Int, val rects: List<Rect4>) {
        fun sameAs(o: Layout?) = o != null && o.texW == texW && o.texH == texH && o.rects == rects
    }

    /**
     * Normally the reader refuses unless Falcon BMS is running, because a stale mapping left by something else is
     * not a cockpit. `--rttselftest` and `--mfdbench` turn that off to read a texture they published themselves.
     */
    @Volatile internal var requireBms = true

    private val lock = Any()

    // ---------------------------------------------------------------- where everything is

    private var layoutCache: Layout? = null
    private var layoutAt = 0L

    /**
     * Where everything is, read from FlightData2. Null when BMS is not running or publishes no layout.
     *
     * Kept for half a second: the rectangles change when the pilot changes aircraft, not between two frames, and
     * reading FlightData2 means opening and copying a shared area of its own. Called under [lock].
     */
    private fun layout(): Layout? {
        val now = System.currentTimeMillis()
        if (now - layoutAt < 500) return layoutCache
        layoutAt = now
        layoutCache = readLayout()
        return layoutCache
    }

    private fun readLayout(): Layout? {
        val fd2 = SharedMemoryReader.mapArea(SharedMemoryReader.FLIGHT_DATA2, FD2.size) ?: return null
        val w = fd2.getShort(FD2.rttSize).toInt() and 0xFFFF
        val h = fd2.getShort(FD2.rttSize + 2).toInt() and 0xFFFF
        if (w <= 0 || h <= 0 || w > 16384 || h > 16384) return null
        val rects = (0 until Display.entries.size).map { i ->
            val at = FD2.rttArea + i * 8
            Rect4(
                fd2.getShort(at).toInt() and 0xFFFF,
                fd2.getShort(at + 2).toInt() and 0xFFFF,
                fd2.getShort(at + 4).toInt() and 0xFFFF,
                fd2.getShort(at + 6).toInt() and 0xFFFF,
            )
        }
        return Layout(w, h, rects)
    }

    // ---------------------------------------------------------------- the door, kept open while it is used

    /** The texture area mapped into this process: its bytes, its size and where the pixels are in it. */
    private class Door(val handle: WinNT.HANDLE, val view: Pointer, val size: Long, val bytes: ByteBuffer, val layout: Layout, val shape: Shape?)

    private var door: Door? = null
    private var doorUsed = 0L

    /** Closes the door once nothing has asked for a picture for this long. */
    private const val IDLE_MS = 2500L

    /**
     * One background thread that closes the door behind the last reader, and at once when BMS has gone.
     *
     * Holding the section open past BMS's exit would keep BMS's old section alive under the same name, and a BMS
     * started again would be handed that one — at the old size — instead of making its own.
     */
    private val closer: Thread by lazy {
        Thread({
            while (true) {
                try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
                synchronized(lock) {
                    if (door != null) {
                        val idle = System.currentTimeMillis() - doorUsed > IDLE_MS
                        if (idle || (requireBms && !BmsInstall.isBmsRunning())) closeDoor()
                    }
                }
            }
        }, "rtt-door").apply { isDaemon = true; start() }
    }

    /** The open door, opening it when needed. Called under [lock]. */
    private fun openDoor(lay: Layout): Door? {
        door?.let { d -> if (d.layout.sameAs(lay)) return d else closeDoor() }
        val (view, size, handle) = openArea() ?: return null
        val shape = shapeOf(view, size, lay)
        // A direct buffer over the view: each row is then one bulk copy in Java, not a native call per row.
        val bytes = runCatching { view.getByteBuffer(0, size) }.getOrNull()
        if (bytes == null) { close(view, handle); return null }
        closer
        return Door(handle, view, size, bytes, lay, shape).also { door = it }
    }

    private fun closeDoor() {
        door?.let { close(it.view, it.handle) }
        door = null
        frames.clear()
        encodings.clear()
    }

    /** Forgets everything held, so the next read starts from the shared areas again. For the checks. */
    internal fun reset() = synchronized(lock) {
        closeDoor()
        layoutCache = null
        layoutAt = 0L
    }

    // ---------------------------------------------------------------- frames

    /**
     * One display's picture as BMS wrote it: BGRA, four bytes a pixel, `w * 4` to a row with the padding dropped.
     *
     * [tag] changes when the pixels do and only then, so a client that already has this tag has this picture.
     * [dark] is true when the picture is black, which on an MFD means it is not powered yet — worth saying rather
     * than showing a black square that looks like a fault. The bytes are never written again once a frame holds them.
     */
    class Frame(val display: Display, val w: Int, val h: Int, val bgra: ByteArray, val tag: Long, val dark: Boolean) {
        @Volatile internal var readAt = 0L
        /** When these pixels were first read: a picture that has not moved for a while may be one BMS left behind. */
        @Volatile internal var changedAt = 0L
    }

    private val frames = HashMap<Display, Frame>()
    private val scratch = HashMap<Display, ByteArray>()
    private val tags = AtomicLong(System.currentTimeMillis())

    /**
     * Out of the cockpit, a picture that has not changed for this long is taken to be one BMS left published behind
     * it, and the glass says so instead of showing it as live. A picture still moving is believed over the flag.
     */
    @Volatile internal var staleMs = 10_000L

    /** Two readers inside this interval get the same copy: a phone and the PC asking at once cost one read. */
    @Volatile internal var shareMs = 15L

    /** One display's current picture, or null when there is nothing to read. */
    fun frame(display: Display): Frame? = synchronized(lock) {
        try {
            if (requireBms && !BmsInstall.isBmsRunning()) { closeDoor(); return null }
            val lay = layout() ?: run { closeDoor(); return null }
            val rect = lay.rects.getOrNull(display.ordinal)?.takeIf { it.real } ?: return null
            val d = openDoor(lay) ?: return null
            doorUsed = System.currentTimeMillis()
            val shape = d.shape ?: return null
            val pitch = shape.pitch
            val last = frames[display]
            if (last != null && doorUsed - last.readAt < shareMs) return last

            // Clamp to the texture: BMS publishes the rects and the size separately, and a rect that ran past the
            // end would read whatever the next page of memory holds.
            val texW = min(lay.texW, shape.w)
            val texH = min(lay.texH, shape.h)
            val left = rect.left.coerceIn(0, texW)
            val top = rect.top.coerceIn(0, texH)
            val w = min(rect.w, texW - left)
            val h = min(rect.h, texH - top)
            if (w <= 0 || h <= 0) return null
            val row = w * 4
            val need = row * h
            val buf = scratch[display]?.takeIf { it.size == need } ?: ByteArray(need)
            for (y in 0 until h) {
                val at = shape.offset + (top + y).toLong() * pitch + left.toLong() * 4
                if (at + row > d.size) return null
                d.bytes.get(at.toInt(), buf, y * row, row)
            }
            // The same bytes as last time — a page that is not moving — keep the frame and its tag, and the buffer
            // just filled goes back to be filled again: no new frame, no encoding, nothing sent.
            if (last != null && last.w == w && last.h == h && last.bgra.contentEquals(buf)) {
                scratch[display] = buf
                last.readAt = doorUsed
                return last
            }
            scratch.remove(display)
            val f = Frame(display, w, h, buf, tags.incrementAndGet(), isDark(buf))
            f.readAt = doorUsed
            f.changedAt = doorUsed
            frames[display] = f
            f
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Whether a picture is black: every sampled pixel below a faint glow.
     *
     * Sampled on a stride, because the answer is about the whole picture — an MFD that is on has symbology across it,
     * and an unpowered one is black everywhere. The stride is odd so it walks across columns rather than down one.
     */
    private fun isDark(bgra: ByteArray): Boolean {
        var i = 0
        val step = 4 * 7
        while (i + 2 < bgra.size) {
            if ((bgra[i].toInt() and 0xFF) > 24 || (bgra[i + 1].toInt() and 0xFF) > 24 || (bgra[i + 2].toInt() and 0xFF) > 24) return false
            i += step
        }
        return true
    }

    /** A frame as a Skia image, for the PC's own window and for encoding. Skia copies the bytes. */
    fun image(f: Frame): Image =
        Image.makeRaster(ImageInfo(f.w, f.h, ColorType.BGRA_8888, ColorAlphaType.OPAQUE), f.bgra, f.w * 4)

    // ---------------------------------------------------------------- over the network

    /**
     * How a display travels to a phone or a browser: JPEG at 85.
     *
     * Chosen by `--mfdbench`, which tries PNG, JPEG and WebP on a radar page and on pod video, because the cost
     * that matters is the BMS PC's processor, which is busy flying the sim. JPEG costs about 2 ms a display where
     * WebP and PNG cost ten times that for pictures that look the same at a glance, and the bytes are well inside
     * what a home network carries (a radar page is about 20 KB, moving pod video about 100 KB). It is also what
     * BMS's own RTTServer sends (`JPG_QUALITY = 80` in its ini), for the same reason. Every client decodes it:
     * Android natively, the browser through the same Skia that draws the rest of the app.
     */
    @Volatile internal var quality = 85
    @Volatile internal var format: EncodedImageFormat = EncodedImageFormat.JPEG

    /** A picture ready to send, and the hash a client quotes back to say it already has it. */
    class Encoded(val bytes: ByteArray, val hash: Long, val tag: Long)

    private val encodings = HashMap<String, Encoded>()

    /** What [encoded] answers when the client already has the picture: an empty body, sent as 204. */
    val UNCHANGED = Encoded(ByteArray(0), 0, 0)

    /**
     * One display ready for the wire, or [UNCHANGED] when the client's [since] is already this picture.
     *
     * The client quotes the hash of the bytes it last drew rather than a counter of ours, so it needs nothing but
     * the image — no header to read, which a browser's fetch and Android's plain connection both make awkward — and
     * a client that has never been here quotes nothing and simply gets the picture. The encoding is kept per size,
     * so two devices on the same display cost one encode between them.
     */
    fun encoded(display: Display, maxWidth: Int = 0, since: Long? = null): Encoded? {
        val f = frame(display) ?: return null
        val key = "${display.id}/$maxWidth/$quality/$format"
        val e = synchronized(lock) { encodings[key]?.takeIf { it.tag == f.tag } } ?: run {
            val bytes = encode(f, maxWidth) ?: return null
            Encoded(bytes, frameHash(bytes), f.tag).also { synchronized(lock) { encodings[key] = it } }
        }
        return if (since != null && since == e.hash) UNCHANGED else e
    }

    internal fun encode(f: Frame, maxWidth: Int, fmt: EncodedImageFormat = format, q: Int = quality): ByteArray? = runCatching {
        val full = image(f)
        val img = if (maxWidth in 1 until f.w) {
            val h = max(1, f.h * maxWidth / f.w)
            val s = Surface.makeRasterN32Premul(maxWidth, h)
            try {
                // Linear, not a cubic: on the processor Skia's cubic costs a hundred milliseconds a frame
                // (`--mfdbench`), and a display is only ever shrunk a little — a phone asks for about what it shows.
                s.canvas.drawImageRect(full, Rect.makeWH(f.w.toFloat(), f.h.toFloat()), Rect.makeWH(maxWidth.toFloat(), h.toFloat()), SamplingMode.LINEAR, null, true)
                s.makeImageSnapshot()
            } finally {
                s.close()
                full.close()
            }
        } else full
        try { img.encodeToData(fmt, q)?.bytes } finally { img.close() }
    }.getOrNull()

    /** One display as a PNG — for `--rtttest`, which writes what BMS published to files for a person to look at. */
    fun png(display: Display, maxWidth: Int = 0): ByteArray? = frame(display)?.let { encode(it, maxWidth, EncodedImageFormat.PNG, 100) }

    /** One display's pixels as an AWT image, for the self-test to compare pixel by pixel. */
    fun grab(display: Display, maxWidth: Int = 0): BufferedImage? {
        val f = frame(display) ?: return null
        val out = BufferedImage(f.w, f.h, BufferedImage.TYPE_INT_RGB)
        val px = IntArray(f.w)
        for (y in 0 until f.h) {
            val o = y * f.w * 4
            for (x in 0 until f.w) {
                val b = f.bgra[o + x * 4].toInt() and 0xFF
                val g = f.bgra[o + x * 4 + 1].toInt() and 0xFF
                val r = f.bgra[o + x * 4 + 2].toInt() and 0xFF
                px[x] = (r shl 16) or (g shl 8) or b
            }
            out.setRGB(0, y, f.w, 1, px, 0, f.w)
        }
        if (maxWidth !in 1 until f.w) return out
        val h = max(1, f.h * maxWidth / f.w)
        return BufferedImage(maxWidth, h, BufferedImage.TYPE_INT_RGB).also { s ->
            val g = s.createGraphics()
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.drawImage(out, 0, 0, maxWidth, h, null)
            g.dispose()
        }
    }

    // ---------------------------------------------------------------- what to tell the pilot

    /** The raw shape of whatever is published, for `--rtttest` to print when the picture does not come out. */
    fun diagnose(): String {
        val lay = readLayout()
        val open = openArea()
        return try {
            buildString {
                appendLine("BMS running: ${BmsInstall.isBmsRunning()}")
                appendLine("layout from FlightData2: " + (lay?.let { "${it.texW} x ${it.texH}, rects " + it.rects.joinToString { r -> "${r.left},${r.top},${r.right},${r.bottom}" } } ?: "none"))
                appendLine("$AREA: " + (open?.let { "${it.second} bytes" } ?: "not open") + "  (NtQuerySection: $lastStatus)")
                if (open != null && open.second >= DDS_DATA) {
                    val v = open.first
                    appendLine(
                        "header: magic 0x" + Integer.toHexString(v.getInt(0)) + " (DDS = 0x" + Integer.toHexString(DDS_MAGIC) + ")" +
                            ", size ${v.getInt(4)}, flags 0x${Integer.toHexString(v.getInt(8))}, ${v.getInt(16)} x ${v.getInt(12)}" +
                            ", pitch ${v.getInt(20)}, ${v.getInt(88)} bits, masks R 0x${Integer.toHexString(v.getInt(92))}" +
                            " G 0x${Integer.toHexString(v.getInt(96))} B 0x${Integer.toHexString(v.getInt(100))}",
                    )
                }
                if (lay != null && open != null) {
                    val exact = lay.texW.toLong() * lay.texH * 4
                    appendLine("width*height*4 = $exact, area/height = ${open.second / lay.texH}, remainder = ${open.second % lay.texH}")
                    val s = shapeOf(open.first, open.second, lay)
                    appendLine(
                        "pixels: " + (s?.let { "${it.w} x ${it.h} at offset ${it.offset}, pitch ${it.pitch}" + (if (it.dds) " (from BMS's DDS header)" else " (measured, no header)") }
                            ?: "none — $shapeWhy"),
                    )
                }
            }
        } finally {
            open?.let { close(it.first, it.third) }
        }
    }

    /**
     * What the app needs to decide what to show: whether there is a picture to be had, and if not, why not.
     *
     * The reason matters more than the flag, and [RttState.phase] names it so each device can say the same thing
     * in its own words. The pilot's side comes in from the caller — [inCockpit] from the flight data every other
     * page uses, [config] from BMS's config files — because "not in 3D" and "switched off" look identical from the
     * texture's side: in both, there is no texture.
     */
    fun state(inCockpit: Boolean = false, config: RttConfig? = null): RttState {
        val running = !requireBms || BmsInstall.isBmsRunning()
        fun off(phase: String, reason: String) =
            RttState(available = false, reason = reason, phase = phase, bmsRunning = running, inCockpit = inCockpit, config = config)
        if (!running) return off(RttPhase.NO_BMS, "Falcon BMS is not running.")
        val lay = synchronized(lock) { layout() }
        // The door when it is already open for the card; otherwise a look that opens the area and closes it again.
        val (open, shape) = synchronized(lock) {
            val d = door
            if (d != null && lay != null && d.layout.sameAs(lay)) true to d.shape
            else openArea()?.let { (v, size, h) -> try { true to lay?.let { shapeOf(v, size, it) } } finally { close(v, h) } } ?: (false to null)
        }
        if (lay == null || !open) {
            return when {
                !inCockpit -> off(RttPhase.NO_3D, "Falcon BMS is running; the displays appear once you are in the cockpit.")
                config?.on == true -> off(RttPhase.RESTART, "The display export is on in BMS's config, but this BMS was started before it was. Restart Falcon BMS.")
                else -> off(RttPhase.EXPORT_OFF, "Falcon BMS is not exporting its displays. Turn on Export RTT Textures in the Launcher ($SETTING 1) and restart BMS.")
            }
        }
        if (shape == null) {
            // Refusing is the right answer here: a guessed row pitch draws the picture a pixel further over on
            // every row, which looks like a display rather than announcing itself as a bug.
            return off(
                RttPhase.PITCH,
                "Falcon BMS is exporting its displays, but this version cannot read the picture for " +
                    "${lay.texW} x ${lay.texH} pixels" + (shapeWhy?.let { " ($it)" } ?: "") + ". Please report it — " +
                    "running \"BMS Companion.exe --rtttest\" writes the numbers out in full.",
            ).copy(settingOn = true)
        }
        val areas = Display.entries.mapIndexedNotNull { i, d ->
            val r = lay.rects[i]
            if (!r.real) null else RttArea(d.id, d.label, r.w, r.h)
        }
        val mfds = listOf(Display.MFDLEFT, Display.MFDRIGHT).filter { d -> areas.any { it.id == d.id } }
        // Dark is judged only from frames a card is already reading. Asking for the state is not a reason to read a
        // display: Setup asks every few seconds with no MFD on screen. The card keeps reading a dark display (it
        // shows the black picture under its note), so the answer stays put while anyone is looking.
        val dark = mfds.isNotEmpty() && mfds.all { d -> synchronized(lock) { frames[d] }?.dark == true }
        // Back in the menus, BMS may leave its texture area published with the last picture in it. The flight data
        // says the pilot is out of the cockpit; a picture that is still changing says otherwise, and wins.
        if (!inCockpit) {
            val now = System.currentTimeMillis()
            val moving = mfds.any { d -> synchronized(lock) { frames[d] }?.let { now - it.changedAt < staleMs } == true }
            if (!moving) return off(RttPhase.NO_3D, "Falcon BMS is running; the displays appear once you are in the cockpit.")
        }
        return RttState(
            available = true,
            settingOn = true,
            width = lay.texW,
            height = lay.texH,
            areas = areas,
            phase = when {
                mfds.isEmpty() -> RttPhase.NO_MFDS
                dark -> RttPhase.DARK
                else -> RttPhase.LIVE
            },
            bmsRunning = running,
            inCockpit = inCockpit,
            config = config,
        )
    }

    // ---------------------------------------------------------------- the shared area itself

    /** Where the pixels are in the area: [offset] bytes in, [pitch] bytes to a row, [w] x [h] of them, BGRA. */
    private class Shape(val offset: Long, val pitch: Long, val w: Int, val h: Int, val dds: Boolean)

    /** "DDS " as a little-endian int, and where the pixels start after it and the 124-byte header. */
    internal const val DDS_MAGIC = 0x20534444
    internal const val DDS_DATA = 128L

    /** Why the last area could not be read, for the glass's reason and `--rtttest`. */
    @Volatile internal var shapeWhy: String? = null

    /**
     * Where the pixels are, read from the area itself.
     *
     * **The area is a DDS file in memory.** Falcon BMS (4.38.1, read from its own code: the caller of the
     * "CreateFileMapping for RTT shared memory area failed" path) creates it at `RowPitch * height + 128` bytes —
     * `RowPitch` being what Direct3D's Map of the staging texture answered — and writes "DDS " and a 124-byte
     * DDS_HEADER at its start: flags 0x100F, the height, the width, the pitch, a pixel format of 32 bits with red in
     * 0xFF0000 and blue in 0xFF (BGRA). The pixels follow at byte 128. So the pitch is not measured at all: BMS says
     * it. Up to the first 1.3.8 test builds the reader took the pixels to start at byte 0 and divided the section's
     * size by the height; the 128 bytes, rounded up to Windows' page by `NtQuerySection`, made that division
     * explain nothing for most heights, and the glass said it could not read the picture.
     *
     * An area without the header (what the older checks publish) is still measured the old way ([pitchOf]).
     */
    private fun shapeOf(view: Pointer, size: Long, lay: Layout): Shape? = runCatching {
        if (size >= DDS_DATA && view.getInt(0) == DDS_MAGIC) {
            val hdr = view.getInt(4)
            val h = view.getInt(12)
            val w = view.getInt(16)
            val pitch = view.getInt(20).toLong() and 0xFFFFFFFFL
            val bits = view.getInt(88)
            val r = view.getInt(92)
            val g = view.getInt(96)
            val b = view.getInt(100)
            // a header without masks says nothing about the order; BMS always writes them, as BGRA
            val bgra = (r == 0xFF0000 && g == 0xFF00 && b == 0xFF) || (r == 0 && g == 0 && b == 0)
            shapeWhy = when {
                hdr != 124 || w <= 0 || h <= 0 || w > 16384 || h > 16384 -> "the header says $w x $h, size $hdr"
                bits != 32 -> "$bits-bit pixels"
                !bgra -> "pixel masks R 0x${Integer.toHexString(r)} G 0x${Integer.toHexString(g)} B 0x${Integer.toHexString(b)}"
                pitch < w * 4L || pitch > w * 4L + 65536 -> "a row pitch of $pitch for $w pixels"
                DDS_DATA + pitch * h > size -> "$size bytes, short of ${DDS_DATA + pitch * h}"
                else -> null
            }
            return@runCatching if (shapeWhy == null) Shape(DDS_DATA, pitch, w, h, true) else null
        }
        val p = pitchOf(size, lay)
        shapeWhy = if (p == null) "no header, and $size bytes are not a padded texture of that size" else null
        p?.let { Shape(0, it, lay.texW, lay.texH, false) }
    }.getOrElse { shapeWhy = it.toString(); null }

    /** Windows rounds a *mapped view* up to whole pages, so a measured region is not the size BMS asked for. */
    private const val PAGE = 4096L

    /**
     * The row pitch — from the exact size of the section, not from the size of the view.
     *
     * A Direct3D staging texture pads each row, so the area is `pitch * height` for some pitch of at least
     * `width * 4`, and a pitch wrong by four bytes shears the picture one pixel further over on every row. The
     * trap is that `VirtualQuery` on a mapped view reports whole pages: for a 1024 x 500 texture at pitch 4160 the
     * real section is 2,080,000 bytes and the view measures 2,080,768, and dividing *that* by the height gives
     * anything from 4156 to 4160. `--rttselftest` publishes exactly that shape and catches it.
     *
     * So the section is asked how big it is, through `NtQuerySection`, which answers with the size BMS passed to
     * `CreateFileMapping`. When that is not available the candidates are narrowed instead — `width * 4` first
     * (what a texture created at its own width usually gets), then the 256-byte alignment Direct3D uses — and
     * anything still ambiguous is refused rather than drawn crooked.
     */
    private fun pitchOf(size: Long, lay: Layout): Long? {
        if (lay.texH <= 0 || lay.texW <= 0 || size <= 0) return null
        val least = lay.texW.toLong() * 4
        val h = lay.texH.toLong()
        if (size % h == 0L && size / h >= least && size / h <= least + PAGE) return size / h
        // the view was rounded up to a page: a candidate has to fill it to within one page and no more
        fun fits(p: Long) = p * h <= size && size - p * h < PAGE
        if (fits(least)) return least
        var p = (least + 255) / 256 * 256
        while (p <= least + PAGE) { if (fits(p)) return p; p += 256 }
        return null
    }

    /** `SECTION_QUERY`, so the section can be asked its real size, alongside `FILE_MAP_READ`. */
    private const val SECTION_QUERY = 0x0001

    private interface NtDll : com.sun.jna.Library {
        /** `SectionBasicInformation` is class 0; on x64 the record is base (8), attributes (4 + 4 padding), size (8). */
        fun NtQuerySection(
            handle: WinNT.HANDLE,
            infoClass: Int,
            info: com.sun.jna.Memory,
            length: Int,
            returned: com.sun.jna.ptr.IntByReference?,
        ): Int
    }

    private val ntdll: NtDll? by lazy {
        runCatching { com.sun.jna.Native.load("ntdll", NtDll::class.java) }.getOrNull()
    }

    /** The last NTSTATUS from [sectionSize], for the diagnostics to print. */
    internal var lastStatus: String = "not asked"

    /** The size BMS gave `CreateFileMapping`, or 0 when Windows will not say. */
    private fun sectionSize(handle: WinNT.HANDLE): Long = runCatching {
        val nt = ntdll ?: return 0L
        com.sun.jna.Memory(24).use { mem ->
            mem.clear()
            val st = nt.NtQuerySection(handle, 0, mem, 24, null)
            lastStatus = "0x" + Integer.toHexString(st)
            if (st != 0) return 0L
            val got = mem.getLong(16)
            lastStatus += " size=" + got
            got.takeIf { it > 0 } ?: 0L
        }
    }.getOrElse { lastStatus = it.toString(); 0L }

    /** The texture area, its size in bytes and the handle to close. Null when BMS is not exporting. */
    private fun openArea(): Triple<Pointer, Long, WinNT.HANDLE>? {
        val k = Kernel32.INSTANCE
        val handle = k.OpenFileMapping(WinNT.FILE_MAP_READ or SECTION_QUERY, false, AREA)
            ?: k.OpenFileMapping(WinNT.FILE_MAP_READ, false, AREA)
            ?: return null
        val view = k.MapViewOfFile(handle, WinNT.FILE_MAP_READ, 0, 0, 0)
        if (view == null) { k.CloseHandle(handle); return null }
        // the exact size when Windows will give it, and the rounded-up view when it will not
        var size = sectionSize(handle)
        if (size <= 0) {
            val info = WinNT.MEMORY_BASIC_INFORMATION()
            k.VirtualQueryEx(k.GetCurrentProcess(), view, info, BaseTSD.SIZE_T(info.size().toLong()))
            size = info.regionSize.toLong()
        }
        return Triple(view, size, handle)
    }

    private fun close(view: Pointer, handle: WinNT.HANDLE) {
        runCatching { Kernel32.INSTANCE.UnmapViewOfFile(view) }
        runCatching { Kernel32.INSTANCE.CloseHandle(handle) }
    }
}
