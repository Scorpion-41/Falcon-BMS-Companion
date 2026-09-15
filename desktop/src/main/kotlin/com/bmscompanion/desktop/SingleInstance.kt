package com.bmscompanion.desktop

import com.bmscompanion.app.data.Repo
import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileLock
import kotlin.concurrent.thread

/**
 * Only one BMS Companion runs per Windows user. A second start asks the running one to show its window (from the tray too)
 * and exits. The running copy holds a lock file and listens on a loopback port written next to it.
 */
object SingleInstance {
    private var lock: FileLock? = null
    private var server: ServerSocket? = null
    private val dir get() = Repo.settingsFolder

    /** True when this is the only copy. Otherwise the running one is told to show itself (unless [quiet]) and false is returned. */
    fun acquire(quiet: Boolean, onShow: () -> Unit): Boolean {
        dir.mkdirs()
        val channel = RandomAccessFile(File(dir, "app.lock"), "rw").channel
        val l = runCatching { channel.tryLock() }.getOrNull()
        if (l == null) {
            channel.close()
            if (!quiet) signal()
            return false
        }
        lock = l
        runCatching {
            val ss = ServerSocket(0, 4, InetAddress.getLoopbackAddress())
            server = ss
            File(dir, "app.port").writeText(ss.localPort.toString())
            thread(isDaemon = true, name = "single-instance") {
                while (!ss.isClosed) {
                    val s = runCatching { ss.accept() }.getOrNull() ?: break
                    runCatching { s.use { if (it.getInputStream().bufferedReader().readLine() == "SHOW") onShow() } }
                }
            }
        }
        return true
    }

    private fun signal() {
        repeat(10) {
            val port = runCatching { File(dir, "app.port").readText().trim().toInt() }.getOrNull()
            val sent = port != null && runCatching {
                Socket(InetAddress.getLoopbackAddress(), port).use { it.getOutputStream().write("SHOW\n".toByteArray()) }
            }.isSuccess
            if (sent) return
            Thread.sleep(300) // the running copy may still be starting
        }
    }
}
