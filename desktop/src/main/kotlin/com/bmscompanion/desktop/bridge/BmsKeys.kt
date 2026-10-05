package com.bmscompanion.desktop.bridge

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.win32.StdCallLibrary
import java.io.File

/**
 * Pressing a cockpit button in Falcon BMS from somewhere that is not the cockpit.
 *
 * The MFD card lets a pilot work the multi-function displays from a tablet — which means a tap on the tablet has to
 * become the same key press the pilot would otherwise make. Three things decide how that is done.
 *
 * **BMS reads the keyboard through DirectInput, not through window messages.** So this cannot post a message to the
 * BMS window the way the Weapon Delivery Planner remote does: DirectInput never sees those. It has to inject at the
 * system level with `SendInput`, in **scan codes** rather than virtual keys, because that is the layer DirectInput
 * reads and it is what makes the press independent of the pilot's keyboard layout.
 *
 * **Injected input goes to whatever is in front.** That is the whole risk here: `Ctrl+Alt+1` typed into a browser
 * because BMS was minimised is a bad surprise, so nothing is sent unless `Falcon BMS.exe` **owns the foreground
 * window** ([bmsInFront]). In the case this is for — flying, with the tablet on your knee — it always is.
 *
 * **The bindings are read, not assumed.** BMS ships every MFD button bound (left `Ctrl+Alt`, right `Shift+Alt`,
 * over the number row and the keypad), but a pilot may have moved them, so the pilot's own `.key` file wins and
 * [DEFAULTS] is only the fallback. Nothing here writes: the key file is read exactly as BMS left it.
 */
object BmsKeys {

    /** A key as BMS's own key file records it: a scan code and a modifier mask (1 shift, 2 ctrl, 4 alt). */
    data class Binding(val scan: Int, val mods: Int) {
        val text: String
            get() = buildString {
                if (mods and 2 != 0) append("Ctrl+")
                if (mods and 1 != 0) append("Shift+")
                if (mods and 4 != 0) append("Alt+")
                append(SCAN_NAMES[scan] ?: ("0x" + scan.toString(16).uppercase()))
            }
    }

    /**
     * What Falcon BMS ships bound, for an install whose key file cannot be read.
     *
     * The twenty buttons run along the number row (OSB 1-10) and then the keypad (11-20), with Ctrl+Alt for the
     * left display and Shift+Alt for the right. Taken from the shipped `BMS - Full.key`, where all forty are bound.
     */
    private val DEFAULT_SCANS = intArrayOf(
        0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B,
        0x4F, 0x50, 0x51, 0x4B, 0x4C, 0x4D, 0x47, 0x48, 0x49, 0x52,
    )

    private val SCAN_NAMES = mapOf(
        0x02 to "1", 0x03 to "2", 0x04 to "3", 0x05 to "4", 0x06 to "5", 0x07 to "6", 0x08 to "7", 0x09 to "8",
        0x0A to "9", 0x0B to "0", 0x47 to "Num7", 0x48 to "Num8", 0x49 to "Num9", 0x4B to "Num4", 0x4C to "Num5",
        0x4D to "Num6", 0x4F to "Num1", 0x50 to "Num2", 0x51 to "Num3", 0x52 to "Num0",
        0x0C to "-", 0x0D to "=", 0x29 to "`",
    )

    /**
     * A key file's scan code is DirectInput's `DIK_` number, where the keys that send an E0 prefix — the arrows,
     * Home, End, the right-hand Ctrl and Alt — are the base scan code with 0x80 added: `0xCF` is End and `0x4F` the
     * keypad's 1, and BMS binds the two to different things (`0xCF` is TMS down). So the high bit, and only the high
     * bit, makes an injected key extended. Marking the keypad itself extended turned OSB 11-20 into Home, End, the
     * arrows and Insert.
     */
    private const val DIK_EXTENDED = 0x80

    /** A key file's scan field: `0x4F` as BMS writes it, a plain number, or `0XFFFFFFFF` (-1) for "not bound". */
    private fun scanOf(field: String): Int? {
        val hex = field.startsWith("0x") || field.startsWith("0X")
        val v = (if (hex) field.substring(2).toLongOrNull(16) else field.toLongOrNull()) ?: return null
        return if (v <= 0 || v > 0xFF) -1 else v.toInt()
    }

    private const val LEFT_MODS = 6   // ctrl + alt
    private const val RIGHT_MODS = 5  // shift + alt

    // ---------------------------------------------------------------- the key file

    private var cached: Map<String, Binding?>? = null
    private var cachedFrom: Pair<String, Long>? = null

    /**
     * Every `SimCBEOSB_<n><L|R>` binding in the pilot's key file.
     *
     * A line is `callback sound ? scan mods combo comboMods hold "description"`, so the fourth and fifth fields are
     * what matter. Which file is found by [activeKeyFile].
     */
    private fun fromKeyFile(install: BmsInstall): Map<String, Binding?> {
        val dir = install.baseDir?.let { File(it, "User\\Config") } ?: return emptyMap()
        val file = activeKeyFile(dir) ?: return emptyMap()
        val stamp = file.path to file.lastModified()
        cached?.let { if (cachedFrom == stamp) return it }
        val out = HashMap<String, Binding?>()
        runCatching {
            for (line in file.readLines()) {
                // the OSBs and the BRT rockers are SimCBEOSB_*, the GAIN rocker is the radar's own gain callback
                if (!line.startsWith("SimCBEOSB_") && !line.startsWith("SimRadarGain")) continue
                val f = line.split(' ').filter { it.isNotBlank() }
                if (f.size < 5) continue
                val scan = scanOf(f[3]) ?: continue
                val mods = f[4].toIntOrNull() ?: continue
                val name = f[0].removePrefix("SimCBEOSB_").removePrefix("SimRadar").uppercase()
                // A button the pilot has unbound (`0XFFFFFFFF`) is recorded as unbound rather than skipped: skipping
                // it fell back to the stock key, which on the pilot's own layout may now do something else entirely.
                // A real binding for the same button elsewhere in the file still wins.
                if (scan <= 0) { if (name !in out) out[name] = null; continue }
                out[name] = Binding(scan, mods)
            }
        }
        cached = out
        cachedFrom = stamp
        return out
    }

    /**
     * The key file Falcon BMS has loaded for the F-16.
     *
     * BMS keeps the pilot's choice in the pilot file, `<callsign>.pop`, as the file's name without `.key` ("BMS -
     * Auto"), a plain null-terminated string. So the names in the newest `.pop` are matched against the key files
     * that exist. The newest `.key` is only the fallback, and never one of the `-F15ABCD` files: BMS writes those
     * beside the F-16's at the same moment, to the same millisecond, and they bind the right-hand display (the F-15's
     * MPCD) to Ctrl+Alt — so a tie that picked one sent every right-MFD press to the left MFD.
     */
    private fun activeKeyFile(dir: File): File? {
        val keys = dir.listFiles { f -> f.isFile && f.name.endsWith(".key", true) }?.toList().orEmpty()
        if (keys.isEmpty()) return null
        namedByPilotFile(dir, keys)?.let { return it }
        return keys.filterNot { it.name.contains("-F15", ignoreCase = true) }.ifEmpty { keys }
            .sortedBy { it.name }.maxByOrNull { it.lastModified() }
    }

    /** The key file the newest pilot file names, of [keys]; null when none does. */
    private fun namedByPilotFile(dir: File, keys: List<File>): File? {
        val byName = keys.associateBy { it.name.dropLast(4).lowercase() }
        val pops = dir.listFiles { f -> f.isFile && f.name.endsWith(".pop", true) }?.sortedByDescending { it.lastModified() }.orEmpty()
        for (pop in pops) {
            val bytes = runCatching { pop.readBytes() }.getOrNull() ?: continue
            // every printable run of the file, as the strings it holds
            val named = ArrayList<String>()
            val run = StringBuilder()
            for (b in bytes) {
                val c = b.toInt() and 0xFF
                if (c in 0x20..0x7E) run.append(c.toChar()) else { if (run.length >= 3) named += run.toString(); run.setLength(0) }
            }
            if (run.length >= 3) named += run.toString()
            // a run may carry a printable byte of the field before it ("?BMS - Auto": the 0x3F of a float 1.0 right
            // before the name in a 4.38.1 pilot file), so a name counts where a run ends with it
            named.flatMap { r -> val t = r.trim().lowercase(); byName.filterKeys { t == it || t.endsWith(it) }.values }
                .maxByOrNull { it.name.length }?.let { return it }
        }
        return null
    }

    /** The key for one button: 1-20, side `L` or `R`. Null when the pilot has unbound it. */
    fun osb(install: BmsInstall, side: Char, n: Int): Binding? {
        if (n !in 1..20) return null
        val s = side.uppercaseChar()
        if (s != 'L' && s != 'R') return null
        val keys = fromKeyFile(install)
        if ("$n$s" in keys) return keys["$n$s"]
        return Binding(DEFAULT_SCANS[n - 1], if (s == 'L') LEFT_MODS else RIGHT_MODS)
    }

    /**
     * The Falcon BMS callback one half of a rocker works, or null for SYM and CON.
     *
     * Checked against the callback table inside `Falcon BMS.exe` (4.38.1): the only MFD rocker callbacks are
     * `SimCBEOSB_BRTUP_<L|R|T|F>` / `SimCBEOSB_BRTDOWN_<…>` and `SimRadarGainUp` / `SimRadarGainDown`. The SYM
     * callbacks that do exist (`SimSymWheelUp/Dn`, `SimHmsSymWheelUp/Dn`) are the HUD's and the helmet's wheels.
     */
    fun rockerCallback(side: Char, which: String, up: Boolean): String? {
        val s = side.uppercaseChar().takeIf { it == 'L' || it == 'R' } ?: return null
        return when (which.lowercase()) {
            "brt" -> "SimCBEOSB_" + (if (up) "BRTUP_" else "BRTDOWN_") + s
            "gain" -> if (up) "SimRadarGainUp" else "SimRadarGainDown"
            else -> null
        }
    }

    /** Every half of both displays' four rockers, with its callback and its key ("" when unbound or none). */
    fun rockerBindings(install: BmsInstall): List<com.bmscompanion.app.data.mission.MfdRockerKey> =
        listOf('L', 'R').flatMap { s ->
            listOf("gain", "sym", "con", "brt").flatMap { w ->
                listOf(true, false).map { up ->
                    com.bmscompanion.app.data.mission.MfdRockerKey(
                        side = s.toString(), which = w, up = up,
                        callback = rockerCallback(s, w, up).orEmpty(),
                        key = rocker(install, s, w, up)?.text.orEmpty(),
                    )
                }
            }
        }

    /**
     * The key for one half of an MFD corner rocker, or null when Falcon BMS has none.
     *
     * Of the four rockers on the real bezel only two do anything in BMS 4.38: **BRT** has a callback per display
     * (`SimCBEOSB_BRTUP_L` / `BRTDOWN_L`, the same Ctrl+Alt / Shift+Alt as that display's OSBs, on = and -), and
     * **GAIN** is the radar's own gain (`SimRadarGainUp` / `Down`, Shift+Alt+` and Ctrl+Alt+`), which the key file
     * lists under the left display and marks "change @ LMFD" under the right — it is one sensor gain, so both
     * bezels send it. **SYM** and **CON** have no callback at all; the Dash-34 calls them not implemented. Both
     * halves were always mapped right: "the down half does nothing" (1.3.8 test) was the bezel drawing a pressed
     * lower half in a grey two shades off the unpressed one.
     */
    fun rocker(install: BmsInstall, side: Char, which: String, up: Boolean): Binding? {
        val s = side.uppercaseChar().takeIf { it == 'L' || it == 'R' } ?: return null
        val keys = fromKeyFile(install)
        return when (which.lowercase()) {
            "brt" -> ((if (up) "BRTUP_" else "BRTDOWN_") + s).let { key ->
                if (key in keys) keys[key] else Binding(if (up) 0x0D else 0x0C, if (s == 'L') LEFT_MODS else RIGHT_MODS)
            }
            "gain" -> (if (up) "GAINUP" else "GAINDOWN").let { key ->
                if (key in keys) keys[key] else Binding(0x29, if (up) RIGHT_MODS else LEFT_MODS)
            }
            else -> null
        }
    }

    /** Rocks one corner switch, if Falcon BMS is the window in front; the answer is in the card's words. */
    fun pressRocker(install: BmsInstall, side: Char, which: String, up: Boolean): String {
        val name = which.uppercase()
        if (name == "SYM" || name == "CON") return "$name is not implemented in Falcon BMS, so there is nothing to press."
        val b = rocker(install, side, which, up)
            ?: return "$name ${if (up) "up" else "down"} is not bound in your key file: bind ${rockerCallback(side, which, up) ?: "it"} to a key in Falcon BMS."
        if (!bmsInFront()) return "Falcon BMS is not the window in front on the PC, so nothing was pressed."
        blockedByElevation()?.let { return it }
        return runCatching {
            send(b)?.let { return it }
            "$name ${if (up) "up" else "down"} (${b.text})."
        }.getOrElse { "Could not send the key: ${it.message}" }
    }

    /** The key file the bindings are read from (the one the pilot file names), for the checks. */
    internal fun keyFile(install: BmsInstall): File? =
        install.baseDir?.let { activeKeyFile(File(it, "User\\Config")) }

    /**
     * `--mfdkeystest out.txt [BMS folder]`: every rocker half of both displays → its callback → its key, as the key
     * file the pilot file names has it. Reads only. A FAIL line for a BRT or GAIN half with no key, or for two
     * halves of one rocker sharing a key (which is what "the down half does nothing" would look like in the file).
     */
    internal fun rockerReport(install: BmsInstall): String = buildString {
        appendLine("key file: ${keyFile(install)?.name ?: "(none: the stock bindings)"}")
        val dir = install.baseDir?.let { File(it, "User\\Config") }
        val keys = dir?.listFiles { f -> f.isFile && f.name.endsWith(".key", true) }?.toList().orEmpty()
        val named = dir?.let { namedByPilotFile(it, keys) }
        appendLine("named by the pilot file: ${named?.name ?: "(none: the newest key file)"}")
        if (keys.isNotEmpty() && named == null) appendLine("FAIL: no pilot file names a key file that exists")
        appendLine("OSB 1 left ${osb(install, 'L', 1)?.text ?: "(unbound)"}, OSB 20 right ${osb(install, 'R', 20)?.text ?: "(unbound)"}")
        // the elevation the presses depend on (UIPI): read only, nothing is sent
        val sendSize = U32.INPUT().size()
        appendLine("INPUT size $sendSize (Windows x64: 40)")
        if (sendSize != 40) appendLine("FAIL: INPUT is $sendSize bytes, SendInput would refuse every press")
        appendLine("this program elevated: ${elevated(null)}; a running Falcon BMS: ${bmsBlocked() ?: "nothing in the way (or not running)"}")
        val all = rockerBindings(install)
        for (r in all) {
            val dir = if (r.up) "up  " else "down"
            appendLine("${r.side} ${r.which.uppercase().padEnd(4)} $dir -> ${r.callback.ifBlank { "(no BMS callback)" }.padEnd(20)} -> ${r.key.ifBlank { "(unbound)" }}")
        }
        for (r in all.filter { it.callback.isNotBlank() && it.key.isBlank() })
            appendLine("FAIL: ${r.side} ${r.which} ${if (r.up) "up" else "down"}: ${r.callback} is not bound")
        all.filter { it.callback.isNotBlank() && it.key.isNotBlank() }.groupBy { it.side + it.which }.forEach { (k, v) ->
            if (v.size == 2 && v[0].key == v[1].key) appendLine("FAIL: $k: up and down share ${v[0].key}")
        }
    }

    /** True when the key file gave the bindings, rather than the built-in defaults. */
    fun fromPilotsKeyFile(install: BmsInstall): Boolean = fromKeyFile(install).values.any { it != null }

    // ---------------------------------------------------------------- pressing it

    /**
     * Presses one MFD button, if Falcon BMS is the window in front.
     *
     * Returns what happened, in the words the card shows: this is one of the places where a control that silently
     * does nothing is the worst outcome, because the pilot cannot see the PC to know why.
     */
    fun pressOsb(install: BmsInstall, side: Char, n: Int): String {
        val b = osb(install, side, n) ?: return "OSB $n is not bound to a key in Falcon BMS."
        if (!bmsInFront()) return "Falcon BMS is not the window in front on the PC, so nothing was pressed."
        blockedByElevation()?.let { return it }
        return runCatching {
            send(b)?.let { return it }
            "OSB $n pressed (${b.text})."
        }.getOrElse { "Could not send the key: ${it.message}" }
    }

    /**
     * What to say when Windows will drop the keys although Falcon BMS is in front, or null when nothing stands in the
     * way. **Windows does not let a program send input to one running with more rights** (UIPI): when Falcon BMS runs as
     * administrator — as it does whenever what starts it does, a launcher set to "Run this program as an administrator"
     * included — and BMS Companion does not, `SendInput` drops every key and reports nothing, so each OSB said "pressed"
     * and did nothing. Both elevations are read from the processes' own tokens (a program that is not elevated may
     * still query an elevated one of the same user); where either cannot be read nothing is claimed.
     */
    internal fun blockedByElevation(pid: Int? = foregroundPid()): String? {
        if (pid == null) return null
        if (elevated(null) != false) return null
        if (elevated(pid) != true) return null
        return ELEVATED_BMS
    }

    /**
     * [blockedByElevation] for a running Falcon BMS whether or not it is in front (`/api/mfd/keys` `blocked`), so the
     * card can say it before the pilot taps anything; null when BMS is not running or nothing stands in the way.
     */
    fun bmsBlocked(): String? = runCatching {
        val k = com.sun.jna.platform.win32.Kernel32.INSTANCE
        val snap = k.CreateToolhelp32Snapshot(com.sun.jna.platform.win32.Tlhelp32.TH32CS_SNAPPROCESS, WinDef.DWORD(0))
        val pids = ArrayList<Int>()
        try {
            val entry = com.sun.jna.platform.win32.Tlhelp32.PROCESSENTRY32.ByReference()
            var ok = k.Process32First(snap, entry)
            while (ok) {
                if (Native.toString(entry.szExeFile).equals("Falcon BMS.exe", ignoreCase = true)) pids += entry.th32ProcessID.toInt()
                ok = k.Process32Next(snap, entry)
            }
        } finally {
            k.CloseHandle(snap)
        }
        pids.firstNotNullOfOrNull { blockedByElevation(it) }
    }.getOrNull()

    /** The card's words for [blockedByElevation]. */
    const val ELEVATED_BMS = "Falcon BMS is running as administrator and BMS Companion is not, so Windows drops the key " +
        "presses it sends to BMS. Start BMS Companion as administrator too (right-click it, Run as administrator), or " +
        "start Falcon BMS without administrator rights (its launcher's Properties, Compatibility, \"Run this program as " +
        "an administrator\" off)."

    /** The foreground window's process id, or null. */
    private fun foregroundPid(): Int? = runCatching {
        val h = U32.INSTANCE.GetForegroundWindow() ?: return null
        val pid = java.nio.IntBuffer.allocate(1)
        U32.INSTANCE.GetWindowThreadProcessId(h, pid)
        pid.get(0).takeIf { it > 0 }
    }.getOrNull()

    /** Whether process [pid] (null: this one) runs elevated; null when its token cannot be read. */
    internal fun elevated(pid: Int?): Boolean? = runCatching {
        val k = com.sun.jna.platform.win32.Kernel32.INSTANCE
        val a = com.sun.jna.platform.win32.Advapi32.INSTANCE
        // PROCESS_QUERY_LIMITED_INFORMATION: granted on an elevated process of the same user
        val h = if (pid == null) k.GetCurrentProcess() else (k.OpenProcess(0x1000, false, pid) ?: return null)
        try {
            val tok = com.sun.jna.platform.win32.WinNT.HANDLEByReference()
            if (!a.OpenProcessToken(h, com.sun.jna.platform.win32.WinNT.TOKEN_QUERY, tok)) return null
            try {
                val e = com.sun.jna.platform.win32.WinNT.TOKEN_ELEVATION()
                val len = com.sun.jna.ptr.IntByReference()
                if (!a.GetTokenInformation(tok.value, com.sun.jna.platform.win32.WinNT.TOKEN_INFORMATION_CLASS.TokenElevation, e, e.size(), len)) return null
                e.read()
                e.TokenIsElevated != 0
            } finally {
                k.CloseHandle(tok.value)
            }
        } finally {
            if (pid != null) k.CloseHandle(h)
        }
    }.getOrNull()

    /**
     * Modifiers down, key down, key up, modifiers up — the order a keyboard makes. Null when Windows took every
     * event, else the card's sentence ([SendInput] answers how many it inserted; a refusal by UIPI is not among them,
     * which is what [blockedByElevation] is for).
     */
    private fun send(b: Binding): String? {
        val mods = ArrayList<Int>()
        if (b.mods and 1 != 0) mods += 0x2A   // left shift
        if (b.mods and 2 != 0) mods += 0x1D   // left ctrl
        if (b.mods and 4 != 0) mods += 0x38   // left alt
        val events = ArrayList<U32.INPUT>()
        for (m in mods) events += key(m, false)
        events += key(b.scan, false)
        events += key(b.scan, true)
        for (m in mods.asReversed()) events += key(m, true)
        val arr = U32.INPUT().toArray(events.size) as Array<*>
        events.forEachIndexed { i, e ->
            val t = arr[i] as U32.INPUT
            t.type = e.type
            t.ki.wVk = e.ki.wVk
            t.ki.wScan = e.ki.wScan
            t.ki.dwFlags = e.ki.dwFlags
            t.ki.time = 0
            t.ki.dwExtraInfo = Pointer.NULL
            t.write()
        }
        val sent = U32.INSTANCE.SendInput(events.size, arr[0] as U32.INPUT, (arr[0] as U32.INPUT).size())
        return if (sent == events.size) null
        else "Windows took ${sent.coerceAtLeast(0)} of the ${events.size} key events (error ${Native.getLastError()}), so the press may not have reached Falcon BMS."
    }

    private fun key(scan: Int, up: Boolean): U32.INPUT {
        val i = U32.INPUT()
        i.type = 1 // INPUT_KEYBOARD
        i.ki.wVk = 0
        i.ki.wScan = (scan and 0x7F).toShort()
        var flags = 0x0008 // KEYEVENTF_SCANCODE
        if (up) flags = flags or 0x0002
        if (scan and DIK_EXTENDED != 0) flags = flags or 0x0001 // KEYEVENTF_EXTENDEDKEY: the E0 prefix
        i.ki.dwFlags = flags
        return i
    }

    /**
     * Whether the window in front belongs to Falcon BMS. Nothing is injected unless it does.
     *
     * The process name is taken from the foreground window's own process, not from "is BMS running anywhere":
     * BMS running behind a browser is exactly the case this has to refuse.
     */
    fun bmsInFront(): Boolean = runCatching {
        val h = U32.INSTANCE.GetForegroundWindow() ?: return false
        val pid = java.nio.IntBuffer.allocate(1)
        U32.INSTANCE.GetWindowThreadProcessId(h, pid)
        exeOf(pid.get(0)).equals("Falcon BMS.exe", ignoreCase = true)
    }.getOrDefault(false)

    /** A process's image name, by id. Toolhelp, so it also sees a BMS that runs elevated. */
    private fun exeOf(pid: Int): String = runCatching {
        val k = com.sun.jna.platform.win32.Kernel32.INSTANCE
        val snap = k.CreateToolhelp32Snapshot(com.sun.jna.platform.win32.Tlhelp32.TH32CS_SNAPPROCESS, WinDef.DWORD(0))
        try {
            val entry = com.sun.jna.platform.win32.Tlhelp32.PROCESSENTRY32.ByReference()
            var ok = k.Process32First(snap, entry)
            while (ok) {
                if (entry.th32ProcessID.toInt() == pid) return Native.toString(entry.szExeFile)
                ok = k.Process32Next(snap, entry)
            }
            ""
        } finally {
            k.CloseHandle(snap)
        }
    }.getOrDefault("")

    // ---------------------------------------------------------------- JNA

    @Suppress("FunctionName", "PropertyName", "unused")
    internal interface U32 : StdCallLibrary {
        fun SendInput(n: Int, inputs: INPUT, size: Int): Int
        fun GetForegroundWindow(): WinDef.HWND?
        fun GetWindowThreadProcessId(h: WinDef.HWND, pid: java.nio.IntBuffer): Int

        @Structure.FieldOrder("wVk", "wScan", "dwFlags", "time", "dwExtraInfo")
        class KEYBDINPUT : Structure() {
            @JvmField var wVk: Short = 0
            @JvmField var wScan: Short = 0
            @JvmField var dwFlags: Int = 0
            @JvmField var time: Int = 0
            @JvmField var dwExtraInfo: Pointer? = Pointer.NULL
        }

        // A real INPUT is a union of mouse, keyboard and hardware; the keyboard arm is the largest one this needs,
        // and padding it to the union's size is what keeps the array stride right on 64-bit.
        @Structure.FieldOrder("type", "ki", "pad")
        class INPUT : Structure() {
            @JvmField var type: Int = 0
            @JvmField var ki: KEYBDINPUT = KEYBDINPUT()
            @JvmField var pad: ByteArray = ByteArray(8)
        }

        companion object {
            val INSTANCE: U32 = Native.load("user32", U32::class.java)
        }
    }
}
