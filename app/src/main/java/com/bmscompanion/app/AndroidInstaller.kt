package com.bmscompanion.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.bmscompanion.app.data.update.Installer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Updating BMS Companion on Android: fetch the APK of a newer release, check it, and hand it to the package installer.
 *
 * Android will not install an APK on an app's say-so — the pilot has to allow this app to install apps, once, and
 * that prompt can be dismissed or arrive at a bad moment. That is exactly why the download is kept: the next attempt
 * hashes what is in the cache, finds it sound, and goes straight back to the permission prompt instead of pulling
 * a quarter of a gigabyte down again. It is cleared once the new version runs.
 */
class AndroidInstaller(private val activity: Activity) : Installer {
    override val canInstall = true
    override val assetSuffix = ".apk"

    /** The app's own external files folder: no permission needed, survives a reboot, and cleanable by the app. */
    private val dir: File
        get() = File(activity.getExternalFilesDir(null) ?: activity.cacheDir, "updates").apply { mkdirs() }

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
                val total = if (size > 0) size else c.contentLength.toLong().coerceAtLeast(1)
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
            check(part.renameTo(target)) { "Could not save the download." }
            onProgress(part.length(), part.length())
            target.name
        }

    override suspend fun install(name: String): String? {
        val f = file(name)
        check(f.isFile) { "The downloaded update is missing." }
        // Android 8 and later ask once, per app, before letting it install anything. Send the pilot to that switch
        // rather than failing silently; the download stays where it is and the next press carries straight on.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.packageManager.canRequestPackageInstalls()) {
            runCatching {
                activity.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            return "Android needs permission to install apps. Allow it for BMS Companion, then press Install again — " +
                "the download is kept, so nothing is downloaded twice."
        }
        val uri = FileProvider.getUriForFile(activity, activity.packageName + ".files", f)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        activity.startActivity(intent)
        return "Android is taking over the install."
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
