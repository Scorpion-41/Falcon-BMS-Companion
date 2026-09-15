package com.bmscompanion.desktop

import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.TimeUnit

/** Small Windows helpers for the PC program: firewall rules, start with Windows, local addresses, opening files. */
object SystemTools {
    const val FIREWALL_RULE = "BMS Companion"
    /** Rule names of earlier versions (separate bridge, browser access port), removed when the rules are added again. */
    private val oldRules = listOf("BMS Companion Bridge", "BMS Companion Browser access")

    /** The installed or unzipped "BMS Companion.exe" (null when running from Gradle). */
    val appExe: String? get() = System.getProperty("jpackage.app-path")

    private fun run(vararg cmd: String, timeoutS: Long = 20): Pair<Int, String> = runCatching {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(timeoutS, TimeUnit.SECONDS)) { p.destroy(); -1 to out } else p.exitValue() to out
    }.getOrDefault(-1 to "")

    fun firewallRuleExists(): Boolean = run("netsh", "advfirewall", "firewall", "show", "rule", "name=$FIREWALL_RULE").let { (code, out) -> code == 0 && out.contains(FIREWALL_RULE) }

    /** Inbound rules limited to the local subnet: TCP [port] (app, browsers, client PCs) and UDP 47475 (discovery). One administrator prompt. */
    fun addFirewallRules(port: Int): Boolean {
        val cmds = buildList {
            (oldRules + FIREWALL_RULE).forEach { add("netsh advfirewall firewall delete rule name=\"$it\"") }
            add("netsh advfirewall firewall add rule name=\"$FIREWALL_RULE\" dir=in action=allow protocol=TCP localport=$port remoteip=localsubnet profile=any")
            add("netsh advfirewall firewall add rule name=\"$FIREWALL_RULE\" dir=in action=allow protocol=UDP localport=47475 remoteip=localsubnet profile=any")
        }
        val inner = "/c " + cmds.joinToString(" & ")
        // Start-Process -Verb RunAs shows the UAC prompt; the doubled single quotes escape the argument for PowerShell
        val ps = "Start-Process cmd -Verb RunAs -WindowStyle Hidden -Wait -ArgumentList '${inner.replace("'", "''")}'"
        return run("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", ps, timeoutS = 120).first == 0
    }

    private const val RUN_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run"
    private const val RUN_NAME = "BMS Companion"

    fun startsWithWindows(): Boolean = run("reg", "query", RUN_KEY, "/v", RUN_NAME).first == 0

    /** Starts BMS Companion in the tray when Windows starts (only for the installed/unzipped app). */
    fun setStartWithWindows(on: Boolean): Boolean {
        val exe = appExe ?: return false
        return if (on) run("reg", "add", RUN_KEY, "/v", RUN_NAME, "/t", "REG_SZ", "/d", "\"$exe\" --tray", "/f").first == 0
        else run("reg", "delete", RUN_KEY, "/v", RUN_NAME, "/f").first == 0
    }

    /** IPv4 addresses other devices on the LAN can reach this PC at (virtual adapters last). */
    fun lanAddresses(): List<String> = System.getenv("BMSC_SHOW_ADDRESS")?.let { listOf(it) } ?: runCatching { // env: example address for screenshots
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isPointToPoint }
            .flatMap { ni -> ni.inetAddresses.toList().filterIsInstance<Inet4Address>().filter { it.isSiteLocalAddress }.map { ni to it } }
            .sortedBy { (ni, _) -> val n = (ni.displayName + ni.name).lowercase(); if (listOf("virtual", "vethernet", "vmware", "hyper-v", "wsl", "vbox").any { n.contains(it) }) 1 else 0 }
            .map { it.second.hostAddress }
            .distinct()
    }.getOrDefault(emptyList())

    /** EZBoards (xbrief.exe) needs the .NET 8 runtime. */
    fun dotNet8Installed(): Boolean = listOfNotNull(System.getenv("DOTNET_ROOT"), System.getenv("ProgramFiles")?.let { "$it\\dotnet" }).any { root ->
        File(root, "shared\\Microsoft.NETCore.App").listFiles()?.any { it.isDirectory && it.name.startsWith("8.") } == true
    }

    fun openUrl(url: String) {
        runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(url)) }
    }

    /** Opens a folder or file with its default program. */
    fun open(path: String) {
        runCatching { ProcessBuilder("explorer.exe", path).start() }
    }

    /** Opens a text file in Notepad (the user edits it; BMS Companion never writes BMS files). */
    fun openInNotepad(path: String) {
        runCatching { ProcessBuilder("notepad.exe", path).start() }
    }
}
