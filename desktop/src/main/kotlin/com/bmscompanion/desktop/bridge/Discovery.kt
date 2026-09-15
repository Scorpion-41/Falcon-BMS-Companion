package com.bmscompanion.desktop.bridge

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import kotlin.concurrent.thread

/**
 * LAN auto-discovery: the apps broadcast "BMSC_DISCOVER" to UDP port 47475 and this PC answers
 * with a small JSON describing where its HTTP API is.
 */
class Discovery {
    @Volatile private var socket: DatagramSocket? = null

    @Synchronized
    fun start(httpPort: () -> Int, version: String) {
        stop()
        val s = runCatching {
            DatagramSocket(null).apply { reuseAddress = true; bind(InetSocketAddress(UDP_PORT)) }
        }.getOrElse {
            BridgeLog.warn("Discovery disabled (UDP $UDP_PORT unavailable): ${it.message}")
            return
        }
        socket = s
        thread(isDaemon = true, name = "discovery") {
            val buf = ByteArray(512)
            while (!s.isClosed) {
                try {
                    val p = DatagramPacket(buf, buf.size)
                    s.receive(p)
                    if (!String(p.data, 0, p.length, Charsets.US_ASCII).startsWith("BMSC_DISCOVER")) continue
                    val name = System.getenv("COMPUTERNAME") ?: "PC"
                    val reply = """{"service":"bms-companion","name":"${name.replace("\"", "")}","port":${httpPort()},"version":"$version","api":1}"""
                    val bytes = reply.toByteArray(Charsets.UTF_8)
                    s.send(DatagramPacket(bytes, bytes.size, p.socketAddress))
                } catch (_: Exception) {
                    if (s.isClosed) break
                }
            }
        }
    }

    @Synchronized
    fun stop() {
        runCatching { socket?.close() }
        socket = null
    }

    companion object {
        const val UDP_PORT = 47475
    }
}
