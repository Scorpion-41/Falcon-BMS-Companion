package com.bmscompanion.desktop

import com.bmscompanion.app.data.Repo
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Where a failure goes when there is nobody to show it to.
 *
 * BMS Companion has no console: when something threw, it took the window with it and left the pilot with nothing to
 * send. Everything caught (and everything uncaught, through [install]) is appended to `%APPDATA%\BMS Companion\error.log`
 * instead, newest last, so a bug report can carry the stack that caused it.
 *
 * The file is trimmed when it passes a few hundred KB: it is a place to look after something went wrong, not a record.
 */
object PcLog {
    private const val MAX_BYTES = 256 * 1024
    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    val file: File get() = File(Repo.settingsFolder, "error.log")

    /** One entry: what was being done, and what went wrong doing it. */
    @Synchronized
    fun write(doing: String, error: Throwable?) {
        runCatching {
            val f = file
            f.parentFile?.mkdirs()
            if (f.length() > MAX_BYTES) f.writeText(f.readText().takeLast(MAX_BYTES / 2))
            val trace = error?.let { StringWriter().also { w -> it.printStackTrace(PrintWriter(w)) }.toString() }.orEmpty()
            f.appendText("${stamp.format(Date())}  $doing\n$trace\n")
        }
    }

    /** Catches what nothing else caught, on every thread including the one Compose draws on. */
    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            write("uncaught on thread ${thread.name}", error)
            previous?.uncaughtException(thread, error)
        }
    }
}
