package com.bmscompanion.desktop

import com.bmscompanion.app.AppVersion
import com.bmscompanion.app.data.Repo
import java.io.File

/**
 * The one sweep a new version makes of what older ones left behind.
 *
 * An update is not an install: the copy being replaced leaves files in `%APPDATA%\BMS Companion` — a cached
 * installer of the version you have just moved off, a lock file from a copy that is no longer running, rendered
 * pages from a build whose renderer has changed — and the pre-1.3 bridge left a settings folder of its own. None of
 * it is read by this version, all of it can be mistaken for something current, and the cached installer alone is
 * a third of a gigabyte. So the first run after a version change throws it out.
 *
 * **What is never swept is what the pilot set up**: `bridge-settings.json` (the VR boards, the BMS, EZBoards, HTML
 * Briefing and screenshots folders, the port, the Tacview settings) and `pc-app.properties` (the Dashboard
 * layouts, the map look, the print size and the board shape). Those carry across updates — that is the point of
 * keeping them in the settings folder rather than beside the program — and every reader of them copes with a file
 * written by an older version.
 */
object Housekeeping {
    private const val KEY = "swept_version"

    /** Files and folders of this program that no longer mean anything once the version has changed. */
    private val ownLeftovers = listOf("updates", "app.lock", "app.port", "cache", "pages", "render-cache")

    /** Where earlier versions — and the separate bridge that came before them — kept their own settings. */
    private fun legacyFolders(): List<File> = listOfNotNull(
        System.getenv("APPDATA")?.let { File(it, "BMSCompanionBridge") },
        System.getenv("APPDATA")?.let { File(it, "BMS Companion Bridge") },
        System.getenv("LOCALAPPDATA")?.let { File(it, "BMS Companion Bridge") },
        System.getenv("LOCALAPPDATA")?.let { File(it, "BMSCompanionBridge") },
    )

    /**
     * Sweeps once per version. Returns what it removed, for the log.
     *
     * It runs before anything else reads the settings folder, and it cannot fail the start: a file Windows will not
     * let go of is left where it is and tried again next time.
     */
    fun sweep(): List<String> {
        val seen = Repo.getString(KEY)
        if (seen == AppVersion.NAME) return emptyList()
        val gone = mutableListOf<String>()
        val here = Repo.settingsFolder
        for (name in ownLeftovers) {
            val f = File(here, name)
            if (f.exists() && runCatching { f.deleteRecursively() }.getOrDefault(false)) gone += name
        }
        // a log from a copy that is no longer installed says nothing about this one
        File(here, "error.log").takeIf { it.isFile && it.length() > 0 }?.let {
            if (runCatching { it.delete() }.getOrDefault(false)) gone += "error.log"
        }
        for (f in legacyFolders()) {
            if (f.isDirectory && runCatching { f.deleteRecursively() }.getOrDefault(false)) gone += f.path
        }
        Repo.putString(KEY, AppVersion.NAME)
        if (seen != null) PcLog.write("Swept what $seen left behind: ${gone.joinToString(", ").ifEmpty { "nothing" }}", null)
        return gone
    }
}
