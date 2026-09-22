package com.bmscompanion.desktop

import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.AppVersion
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import java.io.File

/**
 * What else this PC needs, and whether it has it.
 *
 * BMS Companion brings its own Java runtime, so the program itself needs nothing installed. Two of the things it
 * *does* — running EZBoards, and putting boards in a headset through OpenKneeboard — lean on runtimes Windows may
 * or may not already have, and the failure without them is confusing: a button that does nothing, or a kneeboard
 * tab that stays blank. So they are checked once, named plainly, and each one comes with the page its own makers
 * publish it on. Nothing is installed by us and nothing is downloaded in the background.
 */
object Prerequisites {
    /** One thing this PC may need, and where to get it. */
    data class Need(
        val name: String,
        /** what stops working without it */
        val forWhat: String,
        val installed: Boolean,
        /** false when it is only needed for something optional */
        val required: Boolean,
        val url: String,
    )

    private const val SEEN_KEY = "prereq_seen"

    /** Everything worth checking, in the order a new pilot meets it. */
    fun check(status: () -> Boolean): List<Need> = listOf(
        Need(
            name = "Microsoft Edge WebView2 runtime",
            forWhat = "the VR kneeboards: OpenKneeboard draws a board in a WebView2 window",
            installed = hasWebView2(),
            required = false,
            url = "https://developer.microsoft.com/microsoft-edge/webview2/",
        ),
        Need(
            name = ".NET 8 runtime",
            forWhat = "generating kneeboards with EZBoards, which BMS ships",
            installed = status(),
            required = false,
            url = "https://dotnet.microsoft.com/en-us/download/dotnet/8.0",
        ),
        Need(
            name = "OpenKneeboard",
            forWhat = "showing the boards in the headset (and in the flat window)",
            installed = hasOpenKneeboard(),
            required = false,
            url = "https://openkneeboard.com",
        ),
    )

    /** True the first time this version runs, so the list can be shown once without nagging afterwards. */
    fun firstRunOfThisVersion(): Boolean = Repo.getString(SEEN_KEY) != AppVersion.NAME

    /** Remembers that the list has been seen. */
    fun markSeen() = Repo.putString(SEEN_KEY, AppVersion.NAME)

    /**
     * Whether the WebView2 runtime is on this PC.
     *
     * Windows 11 has it; a Windows 10 machine may not, and OpenKneeboard's web tabs are blank without it. It
     * registers itself under the Edge updater, per machine or per user.
     */
    private fun hasWebView2(): Boolean {
        val client = "{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}"
        val places = listOf(
            WinReg.HKEY_LOCAL_MACHINE to "SOFTWARE\\WOW6432Node\\Microsoft\\EdgeUpdate\\Clients\\$client",
            WinReg.HKEY_LOCAL_MACHINE to "SOFTWARE\\Microsoft\\EdgeUpdate\\Clients\\$client",
            WinReg.HKEY_CURRENT_USER to "SOFTWARE\\Microsoft\\EdgeUpdate\\Clients\\$client",
        )
        return places.any { (root, path) ->
            runCatching {
                Advapi32Util.registryKeyExists(root, path) &&
                    Advapi32Util.registryGetStringValue(root, path, "pv").let { !it.isNullOrBlank() && it != "0.0.0.0" }
            }.getOrDefault(false)
        }
    }

    /** Whether OpenKneeboard is installed, looked for where its installer puts it. */
    private fun hasOpenKneeboard(): Boolean = listOfNotNull(
        System.getenv("ProgramFiles")?.let { File(it, "OpenKneeboard\\bin\\OpenKneeboardApp.exe") },
        System.getenv("ProgramFiles(x86)")?.let { File(it, "OpenKneeboard\\bin\\OpenKneeboardApp.exe") },
        System.getenv("LOCALAPPDATA")?.let { File(it, "Programs\\OpenKneeboard\\bin\\OpenKneeboardApp.exe") },
    ).any { it.isFile }
}
