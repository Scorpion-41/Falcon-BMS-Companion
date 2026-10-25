package com.bmscompanion.desktop

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinBase
import com.sun.jna.platform.win32.WinReg
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.LongByReference

/**
 * Start with Windows, and how this start was asked to show its window.
 *
 * **Start with Windows** is one value under `HKCU\Software\Microsoft\Windows\CurrentVersion\Run` (per user, no
 * elevation; the MSI is per machine, the value is each pilot's own), `"<BMS Companion.exe>" --autostart`. The exe is
 * the jpackage launcher's own path (`jpackage.app-path`); started from source there is none, and the switch says so.
 * The registry is the only record: the switch shows what is there, and Task Manager → Startup apps can turn it off
 * behind the program's back (`StartupApproved\Run`, first byte odd = disabled), which the switch then shows as off.
 * While the value is there every start of any copy writes it again to point at itself (the portable zip can be moved).
 * The uninstaller leaves it: Windows passes over a Run value whose file is gone (Task Manager lists it, nothing runs),
 * and the next copy started writes it again to itself.
 *
 * **Start minimized** ([PcConfig.startMinimized], every start) and the two other ways to ask for it — `--minimized`,
 * and Windows' own request (`start /min`, a shortcut set to Run: Minimized) that reaches the process as STARTUPINFO —
 * are read once here by [launch]. That request was ignored before 1.4.1 twice over: jpackage's launcher runs the JVM
 * in a child copy of itself, which is handed an ordinary STARTUPINFO, and a Compose window never looks at nCmdShow
 * anyway; [windowsShow] reads the launcher's own.
 */
object AutoStart {
    internal const val RUN = "Software\\Microsoft\\Windows\\CurrentVersion\\Run"
    internal const val APPROVED = "Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\StartupApproved\\Run"
    /** The value's name (`BMSC_RUN_NAME` for a developer's test, so a test never touches a pilot's own value). */
    internal val name: String = System.getenv("BMSC_RUN_NAME")?.trim()?.takeIf { it.isNotEmpty() } ?: "BMS Companion"
    const val ARG = "--autostart"

    /** The launcher to register; null from source (`:desktop:run`), where there is none. */
    val exe: String? get() = SystemTools.appExe

    fun commandFor(exe: String) = "\"$exe\" $ARG"

    /** What the registry says now. */
    data class State(val registered: Boolean, val disabledInWindows: Boolean, val command: String?) {
        val on: Boolean get() = registered && !disabledInWindows
    }

    fun state(): State = runCatching {
        val cmd = if (Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, RUN, name))
            Advapi32Util.registryGetStringValue(WinReg.HKEY_CURRENT_USER, RUN, name) else null
        val disabled = cmd != null && runCatching {
            Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, APPROVED, name) &&
                (Advapi32Util.registryGetBinaryValue(WinReg.HKEY_CURRENT_USER, APPROVED, name).firstOrNull()?.toInt() ?: 2) and 1 == 1
        }.getOrDefault(false)
        State(cmd != null, disabled, cmd)
    }.getOrDefault(State(false, false, null))

    /** Switches it on or off. Null when done, else the reason in a sentence. Never throws. */
    fun set(on: Boolean): String? = runCatching {
        if (on) {
            val e = exe ?: return "Only the installed or unzipped program can start with Windows."
            Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, RUN, name, commandFor(e))
            // switched on here after Task Manager turned it off: on means on
            clearApproval()
        } else {
            if (Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, RUN, name))
                Advapi32Util.registryDeleteValue(WinReg.HKEY_CURRENT_USER, RUN, name)
            clearApproval()
        }
        null
    }.getOrElse { "Windows did not take the change: ${it.message ?: it.javaClass.simpleName}" }

    private fun clearApproval() {
        runCatching {
            if (Advapi32Util.registryKeyExists(WinReg.HKEY_CURRENT_USER, APPROVED) &&
                Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, APPROVED, name)
            ) Advapi32Util.registryDeleteValue(WinReg.HKEY_CURRENT_USER, APPROVED, name)
        }
    }

    /**
     * At every start, while the value is there: point it at this copy with `--autostart`. A value an earlier version
     * wrote (`--tray`, which started with no window) also turns Start minimized on once, the nearest thing the new
     * switches have. Task Manager's own on/off is left as it is. Never throws.
     */
    fun refreshAtStart() {
        runCatching {
            val st = state()
            val cmd = st.command ?: return
            if (cmd.contains("--tray") && !PcConfig.startMinimizedChosen) PcConfig.setStartMinimized(true)
            val e = exe ?: return
            val want = commandFor(e)
            if (cmd != want) Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, RUN, name, want)
        }
    }

    // ---------------------------------------------------------------- how this start shows its window

    /** How this start shows its window. */
    enum class Show {
        /** The window as it was last left (normal or maximized), in front. */
        SHOWN,
        /** On the taskbar, minimized, without taking the focus. */
        MINIMIZED,
        /** No window, only the tray icon (`--tray`). */
        TRAY,
    }

    /** What this start asked for, worked out once in `main`. */
    data class Launch(val show: Show, val quiet: Boolean, val why: String)

    /**
     * - `--tray`: no window (as before 1.4.1).
     * - asked minimized — `--minimized`, or Windows' STARTUPINFO: minimized, whatever else.
     * - asked to show something — `--show`, `--route`/`BMSC_ROUTE`: shown, the setting not applied.
     * - otherwise the setting, Start minimized.
     *
     * [Launch.quiet]: a second start that was asked to stay out of the way (`--autostart`, `--minimized`, `--tray`,
     * Windows' minimized request) does not bring the running window up; any other second start does.
     */
    fun launch(args: Array<String>, route: String?): Launch {
        val windows = windowsShow()
        val askedMin = "--minimized" in args || windows != null
        val quiet = "--tray" in args || ARG in args || askedMin
        return when {
            "--tray" in args -> Launch(Show.TRAY, quiet, "--tray")
            askedMin -> Launch(Show.MINIMIZED, quiet, if ("--minimized" in args) "--minimized" else "Windows asked (show $windows)")
            "--show" in args || route != null -> Launch(Show.SHOWN, quiet, "asked to show")
            PcConfig.startMinimized -> Launch(Show.MINIMIZED, quiet, "the Start minimized setting")
            else -> Launch(Show.SHOWN, quiet, "default")
        }
    }

    @Suppress("FunctionName")
    private interface Kernel : StdCallLibrary {
        fun GetStartupInfoW(info: WinBase.STARTUPINFO)
        fun OpenProcess(access: Int, inherit: Boolean, pid: Int): Pointer?
        fun ReadProcessMemory(process: Pointer, address: Pointer, buffer: Memory, size: Long, read: LongByReference?): Boolean
        fun CloseHandle(handle: Pointer): Boolean
    }

    @Suppress("FunctionName")
    private interface NtDll : StdCallLibrary {
        fun NtQueryInformationProcess(process: Pointer, infoClass: Int, info: Memory, size: Int, returned: IntByReference?): Int
    }

    private const val STARTF_USESHOWWINDOW = 0x1
    private const val SW_HIDE = 0
    private const val SW_SHOWMINIMIZED = 2
    private const val SW_MINIMIZE = 6
    private const val SW_SHOWMINNOACTIVE = 7
    private val MINIMIZED = setOf(SW_HIDE, SW_SHOWMINIMIZED, SW_MINIMIZE, SW_SHOWMINNOACTIVE)

    /** The STARTF flags and the SW_ number a process was handed. */
    data class StartInfo(val flags: Int, val show: Int, val from: String) {
        val minimized: Boolean get() = flags and STARTF_USESHOWWINDOW != 0 && show in MINIMIZED
        override fun toString() = "$from flags=0x${flags.toString(16)} show=$show"
    }

    private val kernel by lazy { Native.load("kernel32", Kernel::class.java, W32APIOptions.DEFAULT_OPTIONS) }

    /** This process's own STARTUPINFO. */
    private fun ownStartInfo(): StartInfo? = runCatching {
        val si = WinBase.STARTUPINFO()
        kernel.GetStartupInfoW(si)
        si.read()
        StartInfo(si.dwFlags, si.wShowWindow?.toInt() ?: 1, "own")
    }.getOrNull()

    /**
     * The launcher's STARTUPINFO. jpackage's Windows launcher does not run the program in its own process: it starts a
     * second copy of itself (the JVM's) as its child and waits for it, and that child is handed an ordinary STARTUPINFO —
     * which is why `start /min` never reached the window. So the parent, when it is this same exe, is asked too: its
     * PEB's RTL_USER_PROCESS_PARAMETERS keep `WindowFlags` and `ShowWindowFlags` (x64: +0xA4, +0xA8; the PEB's
     * ProcessParameters at +0x20), read with NtQueryInformationProcess and ReadProcessMemory. Null when it is not that
     * exe or cannot be read.
     */
    private fun launcherStartInfo(): StartInfo? = runCatching {
        if (Native.POINTER_SIZE != 8) return null
        val parent = ProcessHandle.current().parent().orElse(null) ?: return null
        val ownExe = ProcessHandle.current().info().command().orElse(null) ?: return null
        val parentExe = parent.info().command().orElse(null) ?: return null
        if (!java.io.File(ownExe).canonicalPath.equals(java.io.File(parentExe).canonicalPath, ignoreCase = true)) return null
        val h = kernel.OpenProcess(0x0400 or 0x0010, false, parent.pid().toInt()) ?: return null // QUERY_INFORMATION, VM_READ
        try {
            val pbi = Memory(48)
            if (Native.load("ntdll", NtDll::class.java).NtQueryInformationProcess(h, 0, pbi, 48, null) != 0) return null
            val peb = pbi.getPointer(8) ?: return null
            val p = Memory(8)
            if (!kernel.ReadProcessMemory(h, peb.share(0x20), p, 8, null)) return null
            val params = p.getPointer(0) ?: return null
            val f = Memory(8)
            if (!kernel.ReadProcessMemory(h, params.share(0xA4), f, 8, null)) return null
            StartInfo(f.getInt(0), f.getInt(4), "launcher")
        } finally {
            kernel.CloseHandle(h)
        }
    }.getOrNull()

    /** Windows' own minimized/hidden request (`start /min`, Run: Minimized), as its SW_ number; null when none was made. */
    fun windowsShow(): Int? {
        if (!System.getProperty("os.name").orEmpty().startsWith("Windows")) return null
        ownStartInfo()?.takeIf { it.minimized }?.let { return it.show }
        return launcherStartInfo()?.takeIf { it.minimized }?.show
    }

    /** For the log and the check: what this process and its launcher were handed. */
    fun startupInfoText(): String = listOf(ownStartInfo()?.toString() ?: "own unreadable", launcherStartInfo()?.toString() ?: "no launcher parent").joinToString("; ")

    /** Read by the window once, at its first creation: true only for the very first window of a minimized start. */
    @Volatile var minimizedPending = false
        private set

    fun startMinimizedOnce(on: Boolean) { minimizedPending = on }
    fun consumeMinimized(): Boolean = minimizedPending.also { minimizedPending = false }
}
