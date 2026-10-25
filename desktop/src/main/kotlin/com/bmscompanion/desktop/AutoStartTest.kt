package com.bmscompanion.desktop

import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg

/**
 * `--autostarttest out.txt`: Start with Windows and Start minimized ([AutoStart]).
 *
 * Refuses to run unless `BMSC_RUN_NAME` names a value other than the program's own ("BMS Companion"), so a pilot's
 * own Start with Windows is never touched; run it from the launcher ("BMS Companion.exe", which has `jpackage.app-path`)
 * with a scratch APPDATA. It writes only that one test value under `HKCU\…\Run` and `StartupApproved\Run`, and takes
 * both away again at the end, whatever happened; Start minimized is put back as it was found.
 *
 * 1. How a start shows its window ([AutoStart.launch]) for each kind of start, the setting off and on.
 * 2. Switching on and off, Task Manager's off (`StartupApproved`, first byte 03) shown as off and cleared by on.
 * 3. At start: a value of another copy or of an earlier version (`--tray`) written again to this copy, and Start
 *    minimized turned on once for the `--tray` one.
 */
object AutoStartTest {
    fun run(): String = buildString {
        fun check(ok: Boolean, what: String) = appendLine((if (ok) "ok   " else "FAIL ") + what)
        appendLine("Start with Windows / Start minimized check (BMS Companion ${com.bmscompanion.app.AppVersion.NAME})")
        val name = AutoStart.name
        if (System.getenv("BMSC_RUN_NAME").isNullOrBlank() || name == "BMS Companion") {
            appendLine("FAIL refused: set BMSC_RUN_NAME to a test value name (never \"BMS Companion\")")
            return@buildString
        }
        appendLine("value name: $name; exe: ${AutoStart.exe ?: "(none: not started from the launcher)"}")
        appendLine("this process's STARTUPINFO: ${AutoStart.startupInfoText()}; Windows' minimized request: ${AutoStart.windowsShow()}")
        val hkcu = WinReg.HKEY_CURRENT_USER
        val before = PcConfig.startMinimizedRaw
        fun exists(key: String) = runCatching { Advapi32Util.registryValueExists(hkcu, key, name) }.getOrDefault(false)
        check(!exists(AutoStart.RUN), "no test value before the check")
        try {
            appendLine(); appendLine("1. How a start shows its window")
            val windowsAsked = AutoStart.windowsShow() != null
            if (windowsAsked) appendLine("note: this check was itself started minimized by Windows, so every start reads as minimized")
            fun case(pref: Boolean, args: List<String>, route: String?, show: AutoStart.Show, quiet: Boolean) {
                PcConfig.setStartMinimized(pref)
                val l = AutoStart.launch(args.toTypedArray(), route)
                val wantShow = if (windowsAsked && show == AutoStart.Show.SHOWN) AutoStart.Show.MINIMIZED else show
                val wantQuiet = quiet || windowsAsked
                check(l.show == wantShow && l.quiet == wantQuiet,
                    "Start minimized ${if (pref) "on " else "off"} ${args.joinToString(" ").ifEmpty { "(no arguments)" }}${route?.let { " route=$it" } ?: ""}: ${l.show} quiet=${l.quiet} (${l.why})")
            }
            case(false, emptyList(), null, AutoStart.Show.SHOWN, false)
            case(false, listOf("--autostart"), null, AutoStart.Show.SHOWN, true)
            case(false, listOf("--minimized"), null, AutoStart.Show.MINIMIZED, true)
            case(false, listOf("--tray"), null, AutoStart.Show.TRAY, true)
            case(true, emptyList(), null, AutoStart.Show.MINIMIZED, false)
            case(true, listOf("--autostart"), null, AutoStart.Show.MINIMIZED, true)
            case(true, listOf("--show"), null, AutoStart.Show.SHOWN, false)
            case(true, listOf("--route", "mission"), "mission", AutoStart.Show.SHOWN, false)
            case(true, listOf("--minimized", "--route", "mission"), "mission", AutoStart.Show.MINIMIZED, true)
            case(true, listOf("--tray", "--minimized"), null, AutoStart.Show.TRAY, true)

            appendLine(); appendLine("2. Switching")
            val exe = AutoStart.exe
            if (exe == null) {
                val why = AutoStart.set(true)
                check(why != null && !exists(AutoStart.RUN), "from source: refused in a sentence (\"$why\"), nothing written")
            } else {
                check(AutoStart.set(true) == null, "on: no error")
                val s = AutoStart.state()
                check(s.on && s.command == AutoStart.commandFor(exe), "on: the value is ${s.command}")
                // what Task Manager → Startup apps → Disable writes
                Advapi32Util.registrySetBinaryValue(hkcu, AutoStart.APPROVED, name, byteArrayOf(3, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0))
                val d = AutoStart.state()
                check(d.registered && d.disabledInWindows && !d.on, "turned off in Task Manager: shown as off (registered ${d.registered}, disabled ${d.disabledInWindows})")
                AutoStart.refreshAtStart()
                check(AutoStart.state().disabledInWindows, "a start leaves Task Manager's off as it is")
                check(AutoStart.set(true) == null && AutoStart.state().on && !exists(AutoStart.APPROVED), "switched on here again: Task Manager's off cleared")
                Advapi32Util.registrySetBinaryValue(hkcu, AutoStart.APPROVED, name, byteArrayOf(2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0))
                check(AutoStart.state().on, "Task Manager's on (02) reads as on")
                check(AutoStart.set(false) == null && !exists(AutoStart.RUN) && !exists(AutoStart.APPROVED), "off: the value and Task Manager's entry are gone")
                check(!AutoStart.state().registered, "off: reads as off")
                check(AutoStart.set(false) == null, "off again: no error")

                appendLine(); appendLine("3. At start")
                Advapi32Util.registrySetStringValue(hkcu, AutoStart.RUN, name, "\"C:\\Moved\\BMS Companion\\BMS Companion.exe\" --autostart")
                PcConfig.putStartMinimizedForCheck(-1)
                AutoStart.refreshAtStart()
                check(AutoStart.state().command == AutoStart.commandFor(exe), "another copy's value written again to this one: ${AutoStart.state().command}")
                check(!PcConfig.startMinimizedChosen, "…and Start minimized left unchosen")
                Advapi32Util.registrySetStringValue(hkcu, AutoStart.RUN, name, "\"$exe\" --tray")
                AutoStart.refreshAtStart()
                check(AutoStart.state().command == AutoStart.commandFor(exe), "an earlier version's --tray value written again: ${AutoStart.state().command}")
                check(PcConfig.startMinimized, "…and Start minimized turned on once")
                Advapi32Util.registrySetStringValue(hkcu, AutoStart.RUN, name, "\"$exe\" --tray")
                PcConfig.setStartMinimized(false)
                AutoStart.refreshAtStart()
                check(!PcConfig.startMinimized, "…but never over the pilot's own choice")
                AutoStart.set(false)
                AutoStart.refreshAtStart()
                check(!exists(AutoStart.RUN), "off: a start writes nothing")
            }
        } catch (t: Throwable) {
            appendLine("FAIL threw: $t")
        } finally {
            runCatching { if (exists(AutoStart.RUN)) Advapi32Util.registryDeleteValue(hkcu, AutoStart.RUN, name) }
            runCatching { if (exists(AutoStart.APPROVED)) Advapi32Util.registryDeleteValue(hkcu, AutoStart.APPROVED, name) }
            PcConfig.putStartMinimizedForCheck(before)
        }
        check(!exists(AutoStart.RUN) && !exists(AutoStart.APPROVED), "the test value is gone again")
    }
}
