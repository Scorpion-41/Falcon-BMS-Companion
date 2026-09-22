package com.bmscompanion.desktop

import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.update.Installer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Updating BMS Companion on Windows: fetch the MSI of a newer release, check it, and hand it to Windows Installer.
 *
 * The file is kept in `%APPDATA%\BMS Companion\updates` rather than a temporary folder, deliberately — a download
 * interrupted by a closed laptop or a dropped connection is worth keeping, because the next attempt hashes what is
 * there and skips a few hundred megabytes if it is sound. It is thrown away once the new version runs.
 *
 * The installer is started detached and this copy then exits: Windows Installer cannot replace files that are open,
 * and the MSI's own first step closes any copy still running.
 */
object PcInstaller : Installer {
    override val canInstall = true
    override val assetSuffix = ".msi"

    private val dir: File get() = File(Repo.settingsFolder, "updates").apply { mkdirs() }
    private fun file(name: String) = File(dir, name.substringAfterLast('/').substringAfterLast('\\'))

    override suspend fun cachedHash(name: String): String? = withContext(Dispatchers.IO) {
        val f = file(name)
        if (!f.isFile || f.length() == 0L) return@withContext null
        runCatching { sha256(f) }.getOrNull()
    }

    override fun cachedFiles(): List<String> =
        runCatching { dir.listFiles()?.filter { it.isFile && !it.name.endsWith(".part") }?.map { it.name }.orEmpty() }.getOrDefault(emptyList())

    override suspend fun download(url: String, name: String, size: Long, onProgress: (done: Long, total: Long) -> Unit): String =
        withContext(Dispatchers.IO) {
            val target = file(name)
            val part = File(target.path + ".part")
            part.delete()
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 15_000
            c.readTimeout = 60_000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", "BMS Companion")
            c.setRequestProperty("Accept", "application/octet-stream")
            try {
                val total = if (size > 0) size else c.contentLengthLong.coerceAtLeast(1)
                c.inputStream.use { input ->
                    part.outputStream().buffered(1 shl 16).use { out ->
                        val buf = ByteArray(1 shl 16)
                        var done = 0L
                        var lastReport = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            // a progress bar that repaints on every 64 KB is a progress bar that costs more than the download
                            if (done - lastReport > total / 400 + 1) {
                                lastReport = done
                                onProgress(done, total)
                            }
                        }
                    }
                }
            } finally {
                runCatching { c.disconnect() }
            }
            target.delete()
            check(part.renameTo(target)) { "Could not save the download to ${target.path}" }
            onProgress(part.length(), part.length())
            target.name
        }

    override suspend fun install(name: String): String? = withContext(Dispatchers.IO) {
        val f = file(name)
        check(f.isFile) { "The downloaded installer is missing." }
        // /passive: a progress bar and no questions. The MSI closes this copy itself, so nothing here has to.
        //
        // Started through `cmd /c start`, which hands msiexec over and exits, so the installer is nobody's child.
        // Started directly it was a child of this process, and anything that closes this app by its process tree
        // takes the installer with it — which is what happened, from the MSI's own first step.
        ProcessBuilder("cmd.exe", "/c", "start", "\"BMS Companion update\"", "/b", "msiexec.exe", "/i", f.path, "/passive", "/norestart")
            .directory(dir)
            .start()
        PcLog.write("update: started the installer for ${f.name}", null)
        "Windows Installer is taking over. BMS Companion will close and reopen as the new version."
    }

    override fun clearCache(keep: String?) {
        runCatching {
            dir.listFiles()?.forEach { f -> if (keep == null || !f.name.equals(keep, true)) f.delete() }
        }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().buffered(1 shl 16).use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { b -> ((b.toInt() and 0xff) + 0x100).toString(16).substring(1) }
    }
}

/** Reads a URL as text: the release list, and the checksums beside it. */
suspend fun fetchTextFromWeb(url: String): String = withContext(Dispatchers.IO) {
    val c = URL(url).openConnection() as HttpURLConnection
    c.connectTimeout = 10_000
    c.readTimeout = 20_000
    c.setRequestProperty("User-Agent", "BMS Companion")
    c.setRequestProperty("Accept", "application/vnd.github+json")
    try {
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val text = stream?.use { it.readBytes().decodeToString() }.orEmpty()
        if (code !in 200..299) error("HTTP $code")
        text
    } finally {
        runCatching { c.disconnect() }
    }
}
