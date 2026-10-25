package com.bmscompanion.desktop.bridge.command

import java.io.File
import java.nio.file.AccessDeniedException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** The Command section's small file helpers: atomic writes that never throw, hashes, reasons as sentences. */
internal object CommandFiles {
    const val TEMP_SUFFIX = ".bmsc-new"

    fun sha256(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    fun sha256(f: File): String? = runCatching { sha256(f.readBytes()) }.getOrNull()

    /** A plain sentence for an exception (no stack, no class names where a message exists). */
    fun reason(e: Throwable): String = when (e) {
        is AccessDeniedException -> "Windows refused access to ${e.file ?: "the file"} (start BMS Companion as administrator, or check the file is not read only)"
        is java.io.FileNotFoundException -> (e.message ?: "the file was not found").let { if ("being used" in it) "the file is open in another program" else it }
        else -> (e.message ?: e.javaClass.simpleName).trim().trimEnd('.')
    }

    /** The temp file beside [target]. */
    fun tempFor(target: File): File = File(target.absoluteFile.parentFile, target.name + TEMP_SUFFIX)

    /**
     * Moves [tmp] over [target] (atomically where the drive allows; the old file is replaced). Null when done, else
     * the reason. The temp file is removed either way.
     */
    fun moveOver(tmp: File, target: File): String? = try {
        try {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        null
    } catch (e: AccessDeniedException) {
        // (the exception names the temp file; the pilot knows the file being replaced)
        "Windows refused to replace ${target.path} (check the file is not read only, or start BMS Companion as administrator)"
    } catch (e: Throwable) {
        reason(e)
    } finally {
        runCatching { if (tmp.exists()) tmp.delete() }
    }

    /** Writes [bytes] to [target] through a temp file beside it, read back before the move. Null when done, else why not. */
    fun writeAtomic(target: File, bytes: ByteArray): String? {
        val tmp = tempFor(target)
        return try {
            target.absoluteFile.parentFile?.mkdirs()
            tmp.writeBytes(bytes)
            val back = tmp.readBytes()
            if (!back.contentEquals(bytes)) "the file written could not be read back as written" else moveOver(tmp, target)
        } catch (e: Throwable) {
            reason(e)
        } finally {
            runCatching { if (tmp.exists()) tmp.delete() }
        }
    }

    /** Copies [from] to [to] through a temp file, keeping its time, and checks the copy. Null when done. */
    fun copyAtomic(from: File, to: File): String? {
        val bytes = try { from.readBytes() } catch (e: Throwable) { return reason(e) }
        writeAtomic(to, bytes)?.let { return it }
        runCatching { to.setLastModified(from.lastModified()) }
        return null
    }
}
