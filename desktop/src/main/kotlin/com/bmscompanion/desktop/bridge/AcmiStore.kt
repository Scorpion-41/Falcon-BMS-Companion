package com.bmscompanion.desktop.bridge

import com.bmscompanion.app.data.mission.AcmiInfo
import com.sun.jna.platform.win32.Shell32
import com.sun.jna.platform.win32.ShellAPI
import java.io.File

/**
 * The ACMI recordings Falcon BMS writes while you fly, and clearing them out.
 *
 * Every flight with ACMI recording on leaves a file behind — the AWACS picture needs that recording switched on, so
 * anyone using this program is making them — and nobody ever goes looking for the folder. A few dozen flights later
 * it is several gigabytes of nothing anyone will watch again.
 *
 * The BMS folder is otherwise read only to this program. This is the one thing it removes from it, and only when a
 * pilot presses the button: the files go to the **Recycle Bin**, so a recording somebody did want is a right-click
 * away, exactly as deleting a screenshot from Media works.
 */
class AcmiStore {
    /** Where BMS puts them: what it reports while running, else the folder under the install. */
    fun dir(fromSharedMemory: String?, install: BmsInstall): File? {
        if (!fromSharedMemory.isNullOrBlank() && File(fromSharedMemory).isDirectory) return File(fromSharedMemory)
        val base = install.baseDir ?: return null
        return File(base, "User\\Acmi").takeIf { it.isDirectory }
    }

    /** How many recordings there are and what they weigh. */
    fun info(dir: File?): AcmiInfo {
        val files = recordings(dir)
        return AcmiInfo(
            available = dir != null,
            path = dir?.path,
            count = files.size,
            bytes = files.sumOf { it.length() },
            latest = files.maxOfOrNull { it.lastModified() } ?: 0,
        )
    }

    /**
     * Moves every recording to the Recycle Bin and says how many went.
     *
     * Only the recordings: a `.acmi`, `.acmi.zip` or `.vhs` file directly in that folder. Anything else a pilot has
     * put there is left alone, and so is the folder itself.
     */
    fun clear(dir: File?): Pair<Int, Long> {
        val files = recordings(dir)
        if (files.isEmpty()) return 0 to 0L
        val bytes = files.sumOf { it.length() }
        val op = ShellAPI.SHFILEOPSTRUCT().apply {
            wFunc = ShellAPI.FO_DELETE
            pFrom = encodePaths(files.map { it.path }.toTypedArray())
            fFlags = (ShellAPI.FOF_ALLOWUNDO or ShellAPI.FOF_NOCONFIRMATION or ShellAPI.FOF_SILENT or ShellAPI.FOF_NOERRORUI).toShort()
        }
        val rc = Shell32.INSTANCE.SHFileOperation(op)
        val gone = files.count { !it.exists() }
        BridgeLog.info("$gone ACMI recording(s) moved to the Recycle Bin${if (rc != 0) " (shell code $rc)" else ""}")
        return gone to bytes
    }

    private fun recordings(dir: File?): List<File> = runCatching {
        dir?.listFiles()?.filter { it.isFile && isRecording(it.name) }.orEmpty()
    }.getOrDefault(emptyList())

    companion object {
        /** A flight left behind by BMS that stopped before it finished writing: `…zip.acmi.013cc310`. */
        private val partial = Regex("""\.acmi\.[0-9a-f]{4,}$""")

        /**
         * A recording of the pilot's own flights, and nothing else.
         *
         * `.acmi` (BMS writes `2026-09-18_22-06-48.zip.acmi`), the plain `.acmi.zip` some versions write, and the
         * half-written files a crash or an alt-tab out of 3D leaves behind.
         *
         * **Not `.vhs`.** Falcon BMS ships its training tapes in the same folder — Basic BVR Engagement, Pop Up
         * Delivery, Flameout Landing Pattern — and they are part of the install, not something this pilot recorded.
         */
        fun isRecording(name: String): Boolean {
            val lower = name.lowercase()
            return lower.endsWith(".acmi") || lower.endsWith(".acmi.zip") || partial.containsMatchIn(lower)
        }
    }
}
