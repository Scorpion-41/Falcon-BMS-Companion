package com.bmscompanion.desktop.bridge

import com.bmscompanion.app.data.mission.WdpState
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.GDI32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinGDI
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.IIOImage

/**
 * Weapon Delivery Planner, brought into the app instead of rebuilt inside it.
 *
 * WDP is the tool the community already uses to plan a delivery, and the app spent a version trying to re-make a
 * slice of it. It is a .NET desktop program: it cannot run on a tablet, in a browser, or anywhere but the Windows
 * PC that runs Falcon BMS. So this does the only thing that actually puts it on a tablet — it runs the real WDP on
 * the BMS PC and hands its window out over the same API everything else here goes through, taps and keys going
 * back the other way. Nothing in WDP's folder is modified; it is read, started and talked to as any user would.
 *
 * **The window is captured, not the screen.** `PrintWindow` asks a window to draw itself into a bitmap, so it works
 * while WDP is behind the sim, off-screen, or on a PC nobody is sitting at. Screen-grabbing would capture whatever
 * happens to be on top of it.
 *
 * **Hidden means off-screen, not hidden.** A window that Windows has actually hidden (`SW_HIDE`) is not asked to
 * paint and comes back blank, so "run it hidden" moves it to -32000 instead, where it still renders and still takes
 * posted input but never appears on the PC's screen or in its task switcher's way.
 *
 * **A dialog is its own window.** WDP's dropdowns and its message boxes are separate top-level windows owned by the
 * main one — which is why a capture can suddenly return a 296x199 image of a "Caution" box. [frame] composites the
 * owned windows on top of the main one, so a remote pilot sees the dialog and can dismiss it.
 *
 * **Input is posted, never injected.** `PostMessage` to the control under the point, so nothing touches the real
 * mouse or the foreground window: BMS keeps focus, and the pilot in the headset never sees the pointer move. (The
 * MFD buttons in `BmsKeys` have to do the opposite, because BMS reads DirectInput.)
 */
object WdpRemote {

    const val EXE = "WeaponDeliveryPlanner.exe"

    private val NL = System.lineSeparator()

    /** Where the window is parked when it is running hidden. Far enough out that no monitor arrangement reaches it. */
    private const val OFFSCREEN = -32000

    /**
     * How big a window has to be before it counts as the program rather than a splash or a message box.
     *
     * WDP puts a small window up first and its own dialogs are a few hundred pixels across; the real form is
     * 1600x1200 on a stock setup. Anything under this is something in front of the program, not the program.
     */
    private const val MIN_MAIN_W = 700
    private const val MIN_MAIN_H = 450

    /**
     * WDP's own "Failed to creater the HDR" box, which it raises when its MAP page asks for an object-database
     * header that Falcon BMS 4.38 no longer keeps (4.38 replaced `Terrdata\objects\*.HDR` with numbered `Models`
     * folders). It is WDP's message and only this program's problem in that a pilot on a tablet cannot reach the OK
     * button quickly — so it is dismissed for them, by exact text, and nothing else is ever dismissed automatically.
     */
    private const val HDR_BOX = "Failed to creater the HDR"

    // ---------------------------------------------------------------- finding it

    fun dirOf(path: String?): File? = path?.takeIf { it.isNotBlank() }
        ?.let { File(it) }
        ?.takeIf { it.isDirectory && File(it, EXE).isFile }

    /**
     * Where it usually is, when the pilot has not said.
     *
     * WDP is not shipped with BMS and has no installer that registers itself, so this is a short list of the places
     * people actually unzip it into rather than anything authoritative. The Settings field is the real answer.
     */
    fun defaultDir(install: BmsInstall): String? {
        val home = System.getProperty("user.home") ?: return null
        val guesses = buildList {
            install.baseDir?.let { add(File(it, "Tools\\Weapon Delivery Planner")) }
            add(File(home, "Downloads"))
            add(File(home, "Documents"))
            add(File("C:\\Program Files (x86)"))
            add(File("C:\\Program Files"))
        }
        for (g in guesses) {
            if (File(g, EXE).isFile) return g.path
            // one level down, which is what unzipping into Downloads gives you
            g.listFiles { f -> f.isDirectory && f.name.contains("Weapon", true) }
                ?.firstOrNull { File(it, EXE).isFile }?.let { return it.path }
        }
        return null
    }

    fun root(settings: BridgeSettings, install: BmsInstall): File? =
        dirOf(settings.WeaponPlannerDir) ?: dirOf(defaultDir(install))

    // ---------------------------------------------------------------- what the page needs to know

    fun state(settings: BridgeSettings, install: BmsInstall): WdpState = unaware {
        val root = root(settings, install)
        val w = main()
        val r = w?.let { rectOf(it) }
        WdpState(
            available = root != null,
            path = root?.path,
            configured = dirOf(settings.WeaponPlannerDir) != null,
            running = w != null,
            hidden = r != null && r.left <= OFFSCREEN / 2,
            width = r?.let { it.right - it.left } ?: 0,
            height = r?.let { it.bottom - it.top } ?: 0,
            message = when {
                root == null -> "Weapon Delivery Planner has not been found. Point Settings at the folder holding $EXE."
                w == null -> "Not running on the BMS PC."
                else -> null
            },
        )
    }

    // ---------------------------------------------------------------- running it

    /** Starts WDP if it is not already up. [hidden] parks it off the screen, so only the remote shows it. */
    fun open(root: File, hidden: Boolean): String {
        main()?.let { if (hidden) park(it, true) else park(it, false); return "Weapon Delivery Planner is already running." }
        val exe = File(root, EXE)
        if (!exe.isFile) return "$EXE is not in ${root.path}"
        return runCatching {
            ProcessBuilder(exe.path).directory(root).start()
            // It takes a few seconds to put its real window up, and it shows a small one first — parking that one
            // moves a splash off the screen and leaves the program itself sitting in the middle of the PC. So the
            // wait is for a window big enough to be the program, not for the first window of any size.
            val until = System.currentTimeMillis() + 40_000
            var w: WinDef.HWND? = null
            while (System.currentTimeMillis() < until) {
                Thread.sleep(250)
                val cand = main() ?: continue
                val r = rectOf(cand) ?: continue
                if (r.right - r.left >= MIN_MAIN_W && r.bottom - r.top >= MIN_MAIN_H) { w = cand; break }
            }
            if (w == null) return "Weapon Delivery Planner was started but has not shown its window yet."
            if (hidden) park(w, true)
            BridgeLog.info("Weapon Delivery Planner opened" + if (hidden) " (hidden)" else "")
            if (hidden) "Weapon Delivery Planner is running out of sight on the PC; it is on this page."
            else "Weapon Delivery Planner is open on the PC."
        }.getOrElse { "Could not start $EXE: ${it.message}" }
    }

    /** Puts the window back where a pilot at the PC can use it, or takes it away again. */
    fun setHidden(hidden: Boolean): String = unaware {
        val w = main() ?: return@unaware "Weapon Delivery Planner is not running."
        park(w, hidden)
        if (hidden) "Moved out of sight on the PC." else "Put back on the PC's screen."
    }

    private fun park(w: WinDef.HWND, away: Boolean) {
        val r = rectOf(w) ?: return
        val width = r.right - r.left
        val height = r.bottom - r.top
        // SWP_NOSIZE would keep the size but the position has to move, so both are given and nothing else changes
        if (away) U.INSTANCE.SetWindowPos(w, null, OFFSCREEN, OFFSCREEN, width, height, SWP_NOZORDER or SWP_NOACTIVATE)
        else U.INSTANCE.SetWindowPos(w, null, 80, 60, width, height, SWP_NOZORDER or SWP_NOACTIVATE)
    }

    /** Closes it politely, the way its own close button would. */
    fun close(): String {
        val w = main() ?: return "Weapon Delivery Planner is not running."
        U.INSTANCE.PostMessage(w, WM_CLOSE, WinDef.WPARAM(0), WinDef.LPARAM(0))
        return "Asked Weapon Delivery Planner to close."
    }

    // ---------------------------------------------------------------- the picture

    /**
     * One frame: the main window, with any dialog or dropdown it owns composited on top in the right place.
     *
     * [maxWidth] scales it down for the device asking, which is most of the bandwidth saved — WDP is a form, and a
     * form re-sent whole at full size four times a second is wasteful for a picture that mostly does not change.
     */
    fun frame(maxWidth: Int): BufferedImage? = unaware {
        val w = main() ?: return@unaware null
        dismissHdrBox()
        val base = shot(w) ?: return@unaware null
        val r = rectOf(w) ?: return@unaware base
        // the owned windows: message boxes, dropdown lists, anything modal
        for (o in others(w)) {
            val or = rectOf(o) ?: continue
            val img = shot(o) ?: continue
            val g = base.createGraphics()
            g.drawImage(img, or.left - r.left, or.top - r.top, null)
            g.dispose()
        }
        if (maxWidth <= 0 || base.width <= maxWidth) return@unaware base
        val h = (base.height.toLong() * maxWidth / base.width).toInt().coerceAtLeast(1)
        val out = BufferedImage(maxWidth, h, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.drawImage(base, 0, 0, maxWidth, h, null)
        g.dispose()
        out
    }

    /** JPEG, because this is a photograph of a window rather than a diagram, and it is sent again and again. */
    fun jpeg(img: BufferedImage, quality: Float = 0.72f): ByteArray {
        val out = ByteArrayOutputStream()
        val writer = ImageIO.getImageWritersByFormatName("jpg").next()
        val p = writer.defaultWriteParam
        p.compressionMode = ImageWriteParam.MODE_EXPLICIT
        p.compressionQuality = quality
        ImageIO.createImageOutputStream(out).use { s ->
            writer.output = s
            writer.write(null, IIOImage(img, null, null), p)
        }
        writer.dispose()
        return out.toByteArray()
    }

    /**
     * A window's own pixels, asked for rather than read off the screen.
     *
     * `PW_RENDERFULLCONTENT` (2) is the flag that makes this work on a .NET window: without it a window that is
     * composed by the desktop manager comes back empty.
     */
    private fun shot(w: WinDef.HWND): BufferedImage? {
        val r = rectOf(w) ?: return null
        val width = r.right - r.left
        val height = r.bottom - r.top
        if (width <= 0 || height <= 0 || width > 8192 || height > 8192) return null
        val screenDc = U.INSTANCE.GetDC(null) ?: return null
        val memDc = GDI32.INSTANCE.CreateCompatibleDC(screenDc)
        val info = WinGDI.BITMAPINFO()
        info.bmiHeader.biWidth = width
        info.bmiHeader.biHeight = -height          // top-down, so the rows come out the way Java wants them
        info.bmiHeader.biPlanes = 1
        info.bmiHeader.biBitCount = 32
        info.bmiHeader.biCompression = WinGDI.BI_RGB
        val bitsRef = com.sun.jna.ptr.PointerByReference()
        val dib = GDI32.INSTANCE.CreateDIBSection(memDc, info, WinGDI.DIB_RGB_COLORS, bitsRef, null, 0)
        return try {
            if (dib == null) return null
            val old = GDI32.INSTANCE.SelectObject(memDc, dib)
            val ok = U.INSTANCE.PrintWindow(w, memDc, 2)
            GDI32.INSTANCE.SelectObject(memDc, old)
            if (!ok) return null
            val pixels = bitsRef.value.getIntArray(0, width * height)
            BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).also {
                it.setRGB(0, 0, width, height, pixels, 0, width)
            }
        } catch (_: Throwable) {
            null
        } finally {
            if (dib != null) GDI32.INSTANCE.DeleteObject(dib)
            GDI32.INSTANCE.DeleteDC(memDc)
            U.INSTANCE.ReleaseDC(null, screenDc)
        }
    }

    // ---------------------------------------------------------------- input

    /**
     * A tap, a move, a wheel turn or a key, in the coordinates of the frame that was sent.
     *
     * [w] is the width of the frame the client drew, so a tablet showing a scaled-down picture can send the point it
     * touched without knowing anything about the real window. The message goes to the **deepest child control** at
     * that point, in that control's own client coordinates, which is what makes a .NET form respond to it at all —
     * posting to the top-level window puts every click in the form's background.
     */
    fun input(kind: String, x: Int, y: Int, w: Int, data: Int): String = unaware {
        val win = main() ?: return@unaware "Weapon Delivery Planner is not running."
        val r = rectOf(win) ?: return@unaware "No window."
        val width = r.right - r.left
        val scale = if (w > 0 && width > 0) width.toDouble() / w else 1.0
        val sx = r.left + (x * scale).toInt()
        val sy = r.top + (y * scale).toInt()
        val target = deepest(topAt(win, sx, sy), sx, sy)
        val p = WinDef.POINT(sx, sy)
        U.INSTANCE.ScreenToClient(target, p)
        val lp = WinDef.LPARAM(((p.y.toLong() and 0xFFFF) shl 16) or (p.x.toLong() and 0xFFFF))
        runCatching {
            when (kind) {
                "move" -> U.INSTANCE.PostMessage(target, WM_MOUSEMOVE, WinDef.WPARAM(0), lp)
                "down" -> U.INSTANCE.PostMessage(target, WM_LBUTTONDOWN, WinDef.WPARAM(1), lp)
                "up" -> U.INSTANCE.PostMessage(target, WM_LBUTTONUP, WinDef.WPARAM(0), lp)
                "click" -> {
                    U.INSTANCE.PostMessage(target, WM_MOUSEMOVE, WinDef.WPARAM(0), lp)
                    U.INSTANCE.PostMessage(target, WM_LBUTTONDOWN, WinDef.WPARAM(1), lp)
                    U.INSTANCE.PostMessage(target, WM_LBUTTONUP, WinDef.WPARAM(0), lp)
                }
                "rclick" -> {
                    U.INSTANCE.PostMessage(target, WM_RBUTTONDOWN, WinDef.WPARAM(2), lp)
                    U.INSTANCE.PostMessage(target, WM_RBUTTONUP, WinDef.WPARAM(0), lp)
                }
                "dbl" -> {
                    U.INSTANCE.PostMessage(target, WM_LBUTTONDOWN, WinDef.WPARAM(1), lp)
                    U.INSTANCE.PostMessage(target, WM_LBUTTONUP, WinDef.WPARAM(0), lp)
                    U.INSTANCE.PostMessage(target, WM_LBUTTONDBLCLK, WinDef.WPARAM(1), lp)
                    U.INSTANCE.PostMessage(target, WM_LBUTTONUP, WinDef.WPARAM(0), lp)
                }
                // the wheel is posted in screen coordinates, which is the one message that does not use client ones
                "wheel" -> U.INSTANCE.PostMessage(
                    target, WM_MOUSEWHEEL,
                    WinDef.WPARAM(((data.toLong() and 0xFFFF) shl 16)),
                    WinDef.LPARAM(((sy.toLong() and 0xFFFF) shl 16) or (sx.toLong() and 0xFFFF)),
                )
                // a character goes to whatever has the keyboard inside WDP, not to the control under the pointer
                "char" -> U.INSTANCE.PostMessage(focused(win) ?: target, WM_CHAR, WinDef.WPARAM(data.toLong()), WinDef.LPARAM(1))
                "key" -> {
                    val t = focused(win) ?: target
                    U.INSTANCE.PostMessage(t, WM_KEYDOWN, WinDef.WPARAM(data.toLong()), WinDef.LPARAM(1))
                    U.INSTANCE.PostMessage(t, WM_KEYUP, WinDef.WPARAM(data.toLong()), WinDef.LPARAM(1))
                }
                else -> return@unaware "Unknown input '$kind'."
            }
            "ok"
        }.getOrElse { "Input failed: ${it.message}" }
    }

    /** What sits under a point: which control a click would be posted to, for working out why one did not land. */
    fun probe(x: Int, y: Int, w: Int): String = unaware {
        val win = main() ?: return@unaware "not running"
        val r = rectOf(win) ?: return@unaware "no rect"
        val width = r.right - r.left
        val scale = if (w > 0 && width > 0) width.toDouble() / w else 1.0
        val sx = r.left + (x * scale).toInt()
        val sy = r.top + (y * scale).toInt()
        val top = topAt(win, sx, sy)
        val target = deepest(top, sx, sy)
        val p = WinDef.POINT(sx, sy)
        U.INSTANCE.ScreenToClient(target, p)
        "screen $sx,$sy -> " + classOf(target) + " " + JSONish(textOf(target)) + " at client ${p.x},${p.y}" +
            (if (top != win) " (in a dialog)" else "")
    }

    private fun JSONish(s: String) = if (s.length > 40) s.take(40) + "…" else s

    private fun classOf(h: WinDef.HWND): String {
        val buf = CharArray(256)
        val n = U.INSTANCE.GetClassNameW(h, buf, buf.size)
        return if (n <= 0) "?" else String(buf, 0, n)
    }

    /**
     * Runs a block in Weapon Delivery Planner's own coordinate world.
     *
     * WDP is a DPI-unaware program and BMS Companion is not, so on a scaled display the two disagree about what a
     * pixel is. `PrintWindow` draws WDP at **its** size — 1280x950 on a 125% display — while `GetWindowRect` from
     * this process answers in real pixels, 1600x1200. Mixing the two puts every click a quarter of the way off:
     * the tab strip at the top of the picture hit-tested into the title bar, with a client y of -1.
     *
     * `SetThreadDpiAwarenessContext(UNAWARE)` makes Windows answer this thread the way it answers WDP, so the
     * rectangle, the hit test and the picture are all in the same units and a point in the frame is a point in the
     * window. It is set around the calls rather than for the process, because the rest of the app wants the truth.
     */
    private fun <T> unaware(block: () -> T): T {
        val prev = runCatching { U.INSTANCE.SetThreadDpiAwarenessContext(DPI_UNAWARE) }.getOrNull()
        try {
            return block()
        } finally {
            if (prev != null && prev != 0L) runCatching { U.INSTANCE.SetThreadDpiAwarenessContext(prev) }
        }
    }

    /** Every direct child control of the main window, for working out what a click can be aimed at. */
    fun children(): String = unaware {
        val w = main() ?: return@unaware "not running"
        val r = rectOf(w) ?: return@unaware "no rect"
        val out = StringBuilder()
        var n = 0
        U.INSTANCE.EnumChildWindows(w, object : U.WndEnumProc {
            override fun callback(c: WinDef.HWND, data: Pointer?): Boolean {
                if (n < 25) {
                    val cr = rectOf(c)
                    out.append(NL).append("      ").append(classOf(c)).append("  ")
                    if (cr != null) out.append("${cr.left - r.left},${cr.top - r.top} ${cr.right - cr.left}x${cr.bottom - cr.top}")
                    out.append("  ").append(JSONish(textOf(c)))
                }
                n++
                return true
            }
        }, Pointer.NULL)
        "$n child controls" + out
    }

    // ---------------------------------------------------------------- windows

    /**
     * WDP's main window: the biggest visible top-level window its process owns, and not one owned by another.
     *
     * A window it owns is a dialog or a dropdown; those are composited on top in [frame] rather than mistaken for
     * the program. Windows itself is no help here — with a message box up it reports that box as the process's
     * main window, which is how a capture ends up being a 296x199 picture of a Caution box.
     */
    private fun main(): WinDef.HWND? {
        var best: WinDef.HWND? = null
        var bestArea = 0L
        forEachWdpWindow { h ->
            if (U.INSTANCE.GetWindow(h, GW_OWNER) != null) return@forEachWdpWindow  // a dialog, not the main window
            val r = rectOf(h) ?: return@forEachWdpWindow
            val area = (r.right - r.left).toLong() * (r.bottom - r.top)
            if (area > bestArea) { bestArea = area; best = h }
        }
        return best
    }

    /**
     * Every other window WDP has up: its message boxes and its open dropdowns.
     *
     * Not just the ones it formally owns. WDP raises its Caution box as an **unowned** top-level window, which
     * is exactly why Windows reports that box as the process' main window while it is up — so anything visible
     * that is not the main window counts, or a dialog is invisible to the remote while still blocking every click.
     */
    private fun others(w: WinDef.HWND): List<WinDef.HWND> {
        val out = ArrayList<WinDef.HWND>()
        forEachWdpWindow { h -> if (h != w) out += h }
        return out
    }

    /** The owned window at a point, if one is over it — a dialog takes the click, not the form behind it. */
    private fun topAt(w: WinDef.HWND, x: Int, y: Int): WinDef.HWND {
        for (o in others(w)) {
            val r = rectOf(o) ?: continue
            if (x >= r.left && x < r.right && y >= r.top && y < r.bottom) return o
        }
        return w
    }

    private fun forEachWdpWindow(fn: (WinDef.HWND) -> Unit) {
        val pids = pidsOf(EXE)
        if (pids.isEmpty()) return
        U.INSTANCE.EnumWindows(object : U.WndEnumProc {
            override fun callback(h: WinDef.HWND, data: Pointer?): Boolean {
                val pid = com.sun.jna.ptr.IntByReference()
                U.INSTANCE.GetWindowThreadProcessId(h, pid)
                if (pid.value in pids && U.INSTANCE.IsWindowVisible(h)) fn(h)
                return true
            }
        }, Pointer.NULL)
    }

    /** Down through the child controls to the one actually under the point. */
    private fun deepest(from: WinDef.HWND, sx: Int, sy: Int): WinDef.HWND {
        var cur = from
        repeat(8) {
            val p = WinDef.POINT(sx, sy)
            U.INSTANCE.ScreenToClient(cur, p)
            val kid = U.INSTANCE.RealChildWindowFromPoint(cur, p) ?: return cur
            if (kid == cur) return cur
            cur = kid
        }
        return cur
    }

    /** Whatever inside WDP has the keyboard, so typed characters reach the field the pilot clicked into. */
    private fun focused(w: WinDef.HWND): WinDef.HWND? = runCatching {
        val thread = U.INSTANCE.GetWindowThreadProcessId(w, com.sun.jna.ptr.IntByReference())
        val info = U.GUITHREADINFO()
        info.cbSize = info.size()
        if (U.INSTANCE.GetGUIThreadInfo(thread, info)) info.hwndFocus else null
    }.getOrNull()

    /** Presses OK on WDP's HDR complaint, and on nothing else. */
    private fun dismissHdrBox() = unaware {
        val w = main() ?: return@unaware
        for (o in others(w)) {
            if (!textOf(o).equals("Caution", true)) continue
            var isHdr = false
            U.INSTANCE.EnumChildWindows(o, object : U.WndEnumProc {
                override fun callback(c: WinDef.HWND, data: Pointer?): Boolean {
                    if (textOf(c).contains(HDR_BOX, true)) isHdr = true
                    return true
                }
            }, Pointer.NULL)
            if (!isHdr) continue
            U.INSTANCE.EnumChildWindows(o, object : U.WndEnumProc {
                override fun callback(c: WinDef.HWND, data: Pointer?): Boolean {
                    if (textOf(c).trim().equals("OK", true)) {
                        U.INSTANCE.PostMessage(c, WM_LBUTTONDOWN, WinDef.WPARAM(1), WinDef.LPARAM(0x00050005))
                        U.INSTANCE.PostMessage(c, WM_LBUTTONUP, WinDef.WPARAM(0), WinDef.LPARAM(0x00050005))
                    }
                    return true
                }
            }, Pointer.NULL)
            BridgeLog.info("Dismissed Weapon Delivery Planner's HDR notice")
        }
    }

    private fun textOf(h: WinDef.HWND): String {
        val buf = CharArray(512)
        val n = U.INSTANCE.GetWindowTextW(h, buf, buf.size)
        return if (n <= 0) "" else String(buf, 0, n)
    }

    private fun rectOf(h: WinDef.HWND): WinDef.RECT? {
        val r = WinDef.RECT()
        return if (U.INSTANCE.GetWindowRect(h, r)) r else null
    }

    private fun pidsOf(exe: String): Set<Int> = runCatching {
        val k = com.sun.jna.platform.win32.Kernel32.INSTANCE
        val snap = k.CreateToolhelp32Snapshot(com.sun.jna.platform.win32.Tlhelp32.TH32CS_SNAPPROCESS, WinDef.DWORD(0))
        try {
            val out = HashSet<Int>()
            val entry = com.sun.jna.platform.win32.Tlhelp32.PROCESSENTRY32.ByReference()
            var ok = k.Process32First(snap, entry)
            while (ok) {
                if (Native.toString(entry.szExeFile).equals(exe, true)) out += entry.th32ProcessID.toInt()
                ok = k.Process32Next(snap, entry)
            }
            out
        } finally {
            k.CloseHandle(snap)
        }
    }.getOrDefault(emptySet())

    // ---------------------------------------------------------------- constants and JNA

    private const val WM_CLOSE = 0x0010
    private const val WM_MOUSEMOVE = 0x0200
    private const val WM_LBUTTONDOWN = 0x0201
    private const val WM_LBUTTONUP = 0x0202
    private const val WM_LBUTTONDBLCLK = 0x0203
    private const val WM_RBUTTONDOWN = 0x0204
    private const val WM_RBUTTONUP = 0x0205
    private const val WM_MOUSEWHEEL = 0x020A
    private const val WM_KEYDOWN = 0x0100
    private const val WM_KEYUP = 0x0101
    private const val WM_CHAR = 0x0102
    private const val GW_OWNER = 4
    /** DPI_AWARENESS_CONTEXT_UNAWARE */
    private const val DPI_UNAWARE = -1L
    private const val SWP_NOZORDER = 0x0004
    private const val SWP_NOACTIVATE = 0x0010

    @Suppress("FunctionName", "unused")
    internal interface U : StdCallLibrary {
        fun PrintWindow(h: WinDef.HWND, hdc: WinDef.HDC, flags: Int): Boolean
        fun GetWindowRect(h: WinDef.HWND, r: WinDef.RECT): Boolean
        fun GetDC(h: WinDef.HWND?): WinDef.HDC?
        fun ReleaseDC(h: WinDef.HWND?, dc: WinDef.HDC): Int
        fun PostMessage(h: WinDef.HWND, msg: Int, wp: WinDef.WPARAM, lp: WinDef.LPARAM): Boolean
        fun ScreenToClient(h: WinDef.HWND, p: WinDef.POINT): Boolean
        fun RealChildWindowFromPoint(parent: WinDef.HWND, p: WinDef.POINT): WinDef.HWND?
        fun EnumWindows(cb: WndEnumProc, data: Pointer?): Boolean
        fun EnumChildWindows(parent: WinDef.HWND, cb: WndEnumProc, data: Pointer?): Boolean
        fun GetWindowThreadProcessId(h: WinDef.HWND, pid: com.sun.jna.ptr.IntByReference): Int
        fun IsWindowVisible(h: WinDef.HWND): Boolean
        fun GetWindow(h: WinDef.HWND, cmd: Int): WinDef.HWND?
        fun GetWindowTextW(h: WinDef.HWND, buf: CharArray, max: Int): Int
        fun GetClassNameW(h: WinDef.HWND, buf: CharArray, max: Int): Int
        fun SetWindowPos(h: WinDef.HWND, after: WinDef.HWND?, x: Int, y: Int, cx: Int, cy: Int, flags: Int): Boolean
        fun GetGUIThreadInfo(thread: Int, info: GUITHREADINFO): Boolean
        fun SetThreadDpiAwarenessContext(context: Long): Long

        interface WndEnumProc : StdCallLibrary.StdCallCallback {
            fun callback(h: WinDef.HWND, data: Pointer?): Boolean
        }

        @com.sun.jna.Structure.FieldOrder(
            "cbSize", "flags", "hwndActive", "hwndFocus", "hwndCapture",
            "hwndMenuOwner", "hwndMoveSize", "hwndCaret", "rcCaret",
        )
        class GUITHREADINFO : com.sun.jna.Structure() {
            @JvmField var cbSize: Int = 0
            @JvmField var flags: Int = 0
            @JvmField var hwndActive: WinDef.HWND? = null
            @JvmField var hwndFocus: WinDef.HWND? = null
            @JvmField var hwndCapture: WinDef.HWND? = null
            @JvmField var hwndMenuOwner: WinDef.HWND? = null
            @JvmField var hwndMoveSize: WinDef.HWND? = null
            @JvmField var hwndCaret: WinDef.HWND? = null
            @JvmField var rcCaret: WinDef.RECT = WinDef.RECT()
        }

        companion object {
            val INSTANCE: U = Native.load("user32", U::class.java, W32APIOptions.DEFAULT_OPTIONS)
        }
    }
}
