package com.bmscompanion.desktop

import com.bmscompanion.app.data.mission.LinkMode
import com.bmscompanion.app.data.mission.MissionLink
import com.bmscompanion.desktop.bridge.ApiRequest
import com.bmscompanion.desktop.bridge.ApiResponse
import com.bmscompanion.desktop.bridge.Bridge
import com.bmscompanion.desktop.bridge.PcServerPort
import androidx.compose.foundation.layout.fillMaxSize
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.JarURLConnection
import java.net.URL
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.zip.GZIPOutputStream

/** A device that used this PC recently. */
data class Device(val address: String, val kind: String, val lastSeen: Long, val requests: Long)

/**
 * The one HTTP server of BMS Companion for Windows (default port 47474, local network):
 * - `/api/...` the mission API for the Android app, browsers and client PCs: answered by [Bridge] when Falcon BMS is on this PC,
 *   or forwarded to the BMS PC when this PC is a client;
 * - `/` the browser version of the app (WebAssembly, runs on the device) with `/assets/...` (the bundled BMS data), when browser access is on.
 */
object PcServer {
    private var server: HttpServer? = null
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error
    var port = 0
        private set

    private val devices = ConcurrentHashMap<String, Device>()
    private val gzipCache = ConcurrentHashMap<String, ByteArray>()

    /** Devices seen in the last two minutes (this PC itself excluded). */
    fun recentDevices(): List<Device> {
        val cutoff = System.currentTimeMillis() - 120_000
        devices.values.removeIf { it.lastSeen < cutoff }
        return devices.values.sortedBy { it.address }
    }

    @Synchronized
    fun start(port: Int) {
        if (server != null && this.port == port) return
        stop()
        runCatching {
            val s = HttpServer.create(InetSocketAddress(port), 64)
            s.executor = Executors.newFixedThreadPool(16) { r -> Thread(r, "http").apply { isDaemon = true } }
            s.createContext("/") { ex -> handle(ex) }
            s.start()
            server = s
            this.port = port
            PcServerPort.current = port
            _running.value = true
            _error.value = null
        }.onFailure {
            _running.value = false
            _error.value = "Port $port is in use or blocked (${it.message}). Another program (or an old BMS Companion Bridge) may be using it."
        }
    }

    @Synchronized
    fun stop() {
        server?.stop(0)
        server = null
        _running.value = false
    }

    private fun handle(ex: HttpExchange) {
        try {
            val remote = ex.remoteAddress.address.hostAddress
            val path = ex.requestURI.rawPath.let { URLDecoder.decode(it.replace("+", "%2B"), Charsets.UTF_8) }
            val query = ex.requestURI.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.associate { kv ->
                val eq = kv.indexOf('=')
                if (eq < 0) URLDecoder.decode(kv, Charsets.UTF_8) to ""
                else URLDecoder.decode(kv.substring(0, eq), Charsets.UTF_8) to URLDecoder.decode(kv.substring(eq + 1), Charsets.UTF_8)
            }
            ex.responseHeaders.add("Access-Control-Allow-Origin", "*")
            ex.responseHeaders.add("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
            if (ex.requestMethod == "OPTIONS") return send(ex, ApiResponse(204, "text/plain", ByteArray(0)), cache = false)

            val isLocal = ex.remoteAddress.address.isLoopbackAddress
            if (!isLocal) {
                val ua = ex.requestHeaders.getFirst("User-Agent").orEmpty()
                val kind = when {
                    ua.contains("Dalvik") || ua.contains("okhttp") -> "Android app"
                    ua.startsWith("Java") -> "BMS Companion (PC)"
                    ua.contains("Mozilla") -> "Browser"
                    else -> "Device"
                }
                devices.compute(remote) { _, d ->
                    Device(remote, if (d != null && d.kind == "Browser" && kind != "Browser") d.kind else kind, System.currentTimeMillis(), (d?.requests ?: 0) + 1)
                }
            }

            when {
                path.startsWith("/api/") -> {
                    if (path == "/api/assets") return send(ex, assetList(query["dir"].orEmpty()), cache = false)
                    val body = ex.requestBody.readBytes()
                    val resp = when {
                        Bridge.running -> Bridge.handle(ApiRequest(ex.requestMethod, path, query, body, remote))
                        MissionLink.mode.value == LinkMode.REMOTE && MissionLink.host != null -> proxy(ex.requestMethod, ex.requestURI.rawPath + (ex.requestURI.rawQuery?.let { "?$it" } ?: ""), body)
                        else -> ApiResponse.json("""{"error":"BMS Companion on this PC has no mission data source: choose where Falcon BMS runs in Mission → Setup"}""", 502)
                    }
                    send(ex, resp, cache = false)
                }
                !PcConfig.webEnabled -> send(ex, ApiResponse(200, "text/html; charset=utf-8", statusPage().toByteArray()), cache = false)
                path == "/" || path == "/index.html" -> sendResource(ex, "/webapp/index.html", "text/html; charset=utf-8", revalidate = true)
                path == "/icon.png" -> send(ex, ApiResponse(200, "image/png", icon), cache = true)
                path == "/manifest.json" -> send(ex, ApiResponse(200, "application/manifest+json", manifest.toByteArray()), cache = false)
                path.startsWith("/assets/") -> {
                    val rel = path.removePrefix("/assets/")
                    if (".." in rel || rel.split('/').firstOrNull() !in assetRoots) return send(ex, ApiResponse.notFound(), cache = false)
                    sendResource(ex, "/$rel", contentType(rel), revalidate = false)
                }
                ".." !in path -> sendResource(ex, "/webapp$path", contentType(path), revalidate = true)
                else -> send(ex, ApiResponse.notFound(), cache = false)
            }
        } catch (e: Exception) {
            runCatching { send(ex, ApiResponse.json("""{"error":"${e.message?.replace("\"", "'")}"}""", 500), cache = false) }
        } finally {
            ex.close()
        }
    }

    private val assetRoots = setOf("data", "img", "maps", "charts")

    private fun contentType(path: String) = when (path.substringAfterLast('.').lowercase()) {
        "html" -> "text/html; charset=utf-8"
        "js", "mjs" -> "text/javascript; charset=utf-8"
        "wasm" -> "application/wasm"
        "json" -> "application/json; charset=utf-8"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "svg" -> "image/svg+xml"
        "ttf" -> "font/ttf"
        "otf" -> "font/otf"
        "txt" -> "text/plain; charset=utf-8"
        else -> "application/octet-stream"
    }

    private fun sendResource(ex: HttpExchange, resource: String, type: String, revalidate: Boolean) {
        val etag = "\"${AppInfo.version}-${resource.hashCode()}\""
        if (ex.requestHeaders.getFirst("If-None-Match") == etag) {
            ex.responseHeaders.add("ETag", etag)
            ex.sendResponseHeaders(304, -1)
            return
        }
        val bytes = PcServer::class.java.getResourceAsStream(resource)?.use { it.readBytes() }
            ?: return send(ex, ApiResponse.notFound(), cache = false)
        ex.responseHeaders.add("ETag", etag)
        // the app files change with each version (revalidated with the ETag); the BMS data can be kept for a day
        ex.responseHeaders.add("Cache-Control", if (revalidate) "no-cache" else "max-age=86400")
        send(ex, ApiResponse(200, type, bytes), cache = true, gzipKey = resource)
    }

    private fun send(ex: HttpExchange, r: ApiResponse, cache: Boolean, gzipKey: String? = null) {
        if (!cache) ex.responseHeaders.add("Cache-Control", "no-store")
        ex.responseHeaders.add("Content-Type", r.contentType)
        if (r.status == 204 || r.status == 304) { ex.sendResponseHeaders(r.status, -1); return }
        var body = r.body
        val compressible = !r.contentType.startsWith("image/") && body.size > 1024
        if (compressible && ex.requestHeaders.getFirst("Accept-Encoding")?.contains("gzip") == true) {
            body = if (gzipKey != null) gzipCache.getOrPut(gzipKey) { gzip(r.body) } else gzip(r.body)
            ex.responseHeaders.add("Content-Encoding", "gzip")
        }
        if (ex.requestMethod == "HEAD") { ex.sendResponseHeaders(r.status, -1); return }
        ex.sendResponseHeaders(r.status, body.size.toLong())
        ex.responseBody.use { it.write(body) }
    }

    private fun gzip(b: ByteArray): ByteArray = ByteArrayOutputStream(b.size / 3).also { out -> GZIPOutputStream(out).use { it.write(b) } }.toByteArray()

    /** Forwards an API call to the BMS PC this PC is a client of. */
    private fun proxy(method: String, pathAndQuery: String, body: ByteArray): ApiResponse = runCatching {
        val c = URL("http://${MissionLink.host}:${MissionLink.port}$pathAndQuery").openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 2500
            c.readTimeout = if (pathAndQuery.contains("ezboards/generate")) 95_000 else 15_000
            if (method == "POST") {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body) }
            }
            val code = c.responseCode
            val bytes = (if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
            ApiResponse(code, c.contentType ?: "application/json", bytes)
        } finally {
            c.disconnect()
        }
    }.getOrElse { ApiResponse.json("""{"error":"The BMS PC is not answering: ${it.message?.replace("\"", "'")}"}""", 502) }

    /** File names directly inside a bundled data folder (for the browser version's checklist list). */
    private fun assetList(dir: String): ApiResponse {
        if (".." in dir || dir.split('/').firstOrNull() !in assetRoots) return ApiResponse.notFound()
        val names = runCatching {
            val url = PcServer::class.java.classLoader.getResource("$dir/") ?: PcServer::class.java.classLoader.getResource(dir) ?: return@runCatching emptyList()
            when (url.protocol) {
                "file" -> File(url.toURI()).list()?.toList().orEmpty()
                "jar" -> (url.openConnection() as JarURLConnection).apply { useCaches = false }.jarFile.use { jar ->
                    jar.entries().toList().map { it.name }.filter { it.startsWith("$dir/") && it.length > dir.length + 1 }.map { it.removePrefix("$dir/") }.filter { '/' !in it }
                }
                else -> emptyList()
            }
        }.getOrDefault(emptyList())
        return ApiResponse.json(names.joinToString(",", "[", "]") { "\"$it\"" })
    }

    private val manifest =
        """{"name":"BMS Companion","short_name":"BMS Companion","display":"standalone","background_color":"#0a0f14","theme_color":"#0a0f14","start_url":"/","icons":[{"src":"/icon.png","sizes":"192x192","type":"image/png"}]}"""

    private val icon: ByteArray by lazy { renderIcon(192) }

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    private fun renderIcon(size: Int): ByteArray {
        val scene = androidx.compose.ui.ImageComposeScene(size, size, androidx.compose.ui.unit.Density(size / 108f)) {
            androidx.compose.foundation.Image(androidx.compose.ui.graphics.vector.rememberVectorPainter(AppIcon), null, androidx.compose.ui.Modifier.fillMaxSize())
        }
        return try {
            scene.render().encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)?.bytes ?: ByteArray(0)
        } finally {
            scene.close()
        }
    }

    private fun statusPage(): String {
        val i = if (Bridge.running) Bridge.info() else null
        fun esc(s: String?) = (s ?: "—").replace("&", "&amp;").replace("<", "&lt;")
        return """<!doctype html><html><head><meta name=viewport content='width=device-width'><title>BMS Companion</title>
<style>body{background:#0a0f14;color:#dfe8ef;font:15px system-ui;margin:24px}h1{color:#ffb547}td{padding:4px 12px}.k{color:#8aa}</style></head><body>
<h1>BMS Companion ${esc(AppInfo.version)}</h1><p>Browser access is switched off on this PC. Turn it on in BMS Companion to use the app in this browser.</p><table>
<tr><td class=k>Falcon BMS</td><td>${if (i == null) "not read on this PC" else if (i.bms.running) "running ${esc(i.bms.version)} · ${esc(i.bms.theater)}" else "not running"}</td></tr>
<tr><td class=k>AWACS feed</td><td>${esc(i?.tacview?.state)}</td></tr>
<tr><td class=k>Briefing</td><td>${if (i?.briefing?.available == true) esc(i.briefing.generated) else "not printed yet"}</td></tr>
</table><p class=k>API: /api/info · /api/live · /api/contacts · /api/mission</p></body></html>"""
    }
}
