package com.bmscompanion.desktop.bridge

import com.bmscompanion.app.data.mission.MediaInfo
import com.bmscompanion.app.data.mission.MediaList
import com.bmscompanion.app.data.mission.Shot
import com.sun.jna.platform.win32.ShellAPI
import com.sun.jna.platform.win32.Shell32
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * BMS screenshots (User\Pictures, or g_sPicturesDirectory): list, thumbnails, downscaled previews, originals, and
 * deleting chosen files to the Recycle Bin. Nothing else in the folder is touched.
 */
class ScreenshotStore {
    // thumbnails and previews (JPEG). Previews are a few hundred KB each, so the cache is bounded by size
    private val cache = object : LinkedHashMap<String, ByteArray>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean {
            if (size <= 24) return false
            var bytes = 0L
            for (v in values) bytes += v.size
            return bytes > 24L * 1024 * 1024
        }
    }

    fun list(dir: String?): MediaList {
        val folder = dir?.let(::File)?.takeIf { it.isDirectory } ?: return MediaList(dir = dir, available = false)
        val shots = folder.listFiles().orEmpty().filter { it.isFile && isImage(it.name) }.sortedByDescending { it.lastModified() }.map { f ->
            val (w, h) = pngSize(f)
            Shot(name = f.name, time = f.lastModified(), size = f.length(), w = w, h = h)
        }
        return MediaList(dir = dir, available = true, shots = shots)
    }

    fun info(dir: String?): MediaInfo {
        val folder = dir?.let(::File)?.takeIf { it.isDirectory } ?: return MediaInfo()
        var count = 0
        var latest = 0L
        folder.listFiles().orEmpty().forEach { if (it.isFile && isImage(it.name)) { count++; latest = max(latest, it.lastModified()) } }
        return MediaInfo(available = true, count = count, latest = latest)
    }

    /** JPEG no larger than [maxSide] pixels on its longer side (cached per file and size). */
    fun scaled(file: File, maxSide: Int): ByteArray? {
        val key = "${file.path}|${file.lastModified()}|$maxSide"
        synchronized(cache) { cache[key] }?.let { return it }
        val data = runCatching {
            // read into memory first so BMS or Explorer can still rename or delete the file
            Image.makeFromEncoded(file.readBytes()).use { src ->
                val scale = min(1.0, maxSide / max(src.width, src.height).toDouble())
                val w = max(1, (src.width * scale).roundToInt())
                val h = max(1, (src.height * scale).roundToInt())
                Surface.makeRasterN32Premul(w, h).use { s ->
                    s.canvas.drawImageRect(src, Rect.makeWH(src.width.toFloat(), src.height.toFloat()), Rect.makeWH(w.toFloat(), h.toFloat()), FilterMipmap(FilterMode.LINEAR, MipmapMode.LINEAR), null, true)
                    s.makeImageSnapshot().use { it.encodeToData(EncodedImageFormat.JPEG, if (maxSide <= 400) 78 else 88)?.bytes }
                }
            }
        }.onFailure { BridgeLog.warn("Screenshot ${file.name} could not be read: ${it.message}") }.getOrNull() ?: return null
        synchronized(cache) { cache[key] = data }
        return data
    }

    /** Moves the named screenshots to the Recycle Bin (no dialogs). Returns how many were removed. */
    fun delete(dir: String?, names: Collection<String>): Int {
        val files = names.mapNotNull { resolve(dir, it) }.distinct()
        if (files.isEmpty()) return 0
        val op = ShellAPI.SHFILEOPSTRUCT().apply {
            wFunc = ShellAPI.FO_DELETE
            pFrom = encodePaths(files.map { it.path }.toTypedArray())
            fFlags = (ShellAPI.FOF_ALLOWUNDO or ShellAPI.FOF_NOCONFIRMATION or ShellAPI.FOF_SILENT or ShellAPI.FOF_NOERRORUI).toShort()
        }
        val rc = Shell32.INSTANCE.SHFileOperation(op)
        val removed = files.count { !it.exists() }
        BridgeLog.info("$removed screenshot(s) moved to the Recycle Bin${if (rc != 0) " (shell code $rc)" else ""}")
        synchronized(cache) { cache.clear() }
        return removed
    }

    companion object {
        private val extensions = setOf("png", "jpg", "jpeg", "bmp")
        fun isImage(name: String) = name.substringAfterLast('.', "").lowercase() in extensions

        /** The screenshot file by name, or null when the name is not a plain image file in the folder. */
        fun resolve(dir: String?, name: String?): File? {
            if (dir == null || name.isNullOrBlank() || name != File(name).name || '/' in name || '\\' in name || !isImage(name)) return null
            return File(dir, name).takeIf { it.isFile }
        }

        /** Width and height from a PNG header (cheap); null for other formats. */
        private fun pngSize(f: File): Pair<Int?, Int?> = runCatching {
            if (!f.name.endsWith(".png", ignoreCase = true)) return null to null
            val b = ByteArray(24)
            RandomAccessFile(f, "r").use { if (it.read(b) < 24) return null to null }
            if (b[12] != 'I'.code.toByte() || b[13] != 'H'.code.toByte()) return null to null
            fun int(o: Int) = (b[o].toInt() and 0xFF shl 24) or (b[o + 1].toInt() and 0xFF shl 16) or (b[o + 2].toInt() and 0xFF shl 8) or (b[o + 3].toInt() and 0xFF)
            int(16) to int(20)
        }.getOrDefault(null to null)
    }
}
