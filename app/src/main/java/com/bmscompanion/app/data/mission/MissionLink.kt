package com.bmscompanion.app.data.mission

import android.content.Context
import android.net.wifi.WifiManager
import com.bmscompanion.app.data.Repo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.decodeFromString
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.net.URL

/** Connection state shown in the Mission header. */
sealed interface LinkState {
    data object Idle : LinkState
    data object Connecting : LinkState
    data class Online(val host: String) : LinkState
    data class Offline(val host: String, val reason: String) : LinkState
}

data class FoundBridge(val host: String, val port: Int, val name: String, val version: String)

/**
 * Talks to the BMS Companion Bridge running next to Falcon BMS on the PC (plain HTTP + JSON on the LAN).
 * Polling only runs while a Mission screen is visible ([acquire]/[release]) so the app is idle otherwise.
 */
object MissionLink {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var users = 0

    private val _state = MutableStateFlow<LinkState>(LinkState.Idle)
    val state: StateFlow<LinkState> = _state
    private val _info = MutableStateFlow<BridgeInfo?>(null)
    val info: StateFlow<BridgeInfo?> = _info
    private val _live = MutableStateFlow<Live?>(null)
    val live: StateFlow<Live?> = _live
    private val _contacts = MutableStateFlow<Contacts?>(null)
    val contacts: StateFlow<Contacts?> = _contacts
    private val _mission = MutableStateFlow<MissionData?>(null)
    val mission: StateFlow<MissionData?> = _mission
    private val _ez = MutableStateFlow<EzUi>(EzUi())
    val ez: StateFlow<EzUi> = _ez

    data class EzUi(val running: Boolean = false, val result: EzRun? = null)

    val host: String? get() = Repo.getString("bridge_host")
    val port: Int get() = Repo.getInt("bridge_port", 47474)
    private fun base() = host?.let { "http://$it:$port" }

    fun setBridge(host: String, port: Int) {
        Repo.putString("bridge_host", host.trim())
        Repo.putInt("bridge_port", port)
        _mission.value = null
        _info.value = null
        _live.value = null
        _contacts.value = null
        restart()
    }

    fun forget() {
        Repo.putString("bridge_host", null)
        stop()
        _state.value = LinkState.Idle
        _info.value = null; _live.value = null; _contacts.value = null; _mission.value = null
    }

    @Volatile private var fastUsers = 0

    /** The AWACS page wants contacts twice a second instead of once. */
    @Synchronized fun fastContacts(on: Boolean) { fastUsers = (fastUsers + if (on) 1 else -1).coerceAtLeast(0) }

    @Synchronized fun acquire() { users++; if (job == null) restart() }
    @Synchronized fun release() { users = (users - 1).coerceAtLeast(0); if (users == 0) stop() }

    @Synchronized private fun stop() { job?.cancel(); job = null }

    @Synchronized private fun restart() {
        job?.cancel()
        job = null
        if (users == 0 || host == null) return
        job = scope.launch { pollLoop() }
    }

    private suspend fun pollLoop() {
        val h = host ?: return
        _state.value = LinkState.Connecting
        var tick = 0L
        var failures = 0
        var missionVersion = ""
        while (scope.isActive) {
            val info = runCatching { get<BridgeInfo>("/api/info") }
            if (info.isFailure) {
                failures++
                if (failures >= 2 || _state.value is LinkState.Connecting) _state.value = LinkState.Offline(h, reasonOf(info.exceptionOrNull()))
                delay(if (failures < 5) 1500 else 4000)
                continue
            }
            failures = 0
            val i = info.getOrThrow()
            _info.value = i
            _state.value = LinkState.Online(h)
            val want = "${i.briefing.modified}-${i.briefing.dtcModified}-${i.ezBoards.lastRun?.time ?: 0}"
            if (want != missionVersion || _mission.value == null) {
                runCatching { get<MissionData>("/api/mission") }.onSuccess { _mission.value = it; missionVersion = want }
            }
            // ~4 Hz live data, 1 Hz contacts (2 Hz on the AWACS page), info every ~2 s (the info call above doubles as the heartbeat)
            repeat(8) {
                runCatching { get<Live>("/api/live") }.onSuccess { _live.value = it }
                if (tick % (if (fastUsers > 0) 2 else 4) == 0L) runCatching { get<Contacts>("/api/contacts") }.onSuccess { _contacts.value = it }
                tick++
                delay(250)
            }
        }
    }

    private fun reasonOf(e: Throwable?): String = when (e) {
        is SocketTimeoutException -> "timed out: is BMS Companion running on the PC and allowed through the Windows firewall?"
        is java.net.ConnectException -> "connection refused: start BMS Companion on the PC"
        is java.net.UnknownHostException -> "unknown host"
        is IOException -> e.message ?: "network error"
        else -> e?.message ?: "error"
    }

    private suspend inline fun <reified T> get(path: String): T = withContext(Dispatchers.IO) {
        val text = request("GET", path, timeoutMs = 2500)
        Repo.json.decodeFromString<T>(text)
    }

    private fun request(method: String, path: String, timeoutMs: Int, body: String? = null): String {
        val b = base() ?: throw IOException("no BMS PC set")
        val c = URL(b + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.useCaches = false
            if (method == "POST") {
                val bytes = body?.encodeToByteArray() ?: ByteArray(0)
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.setFixedLengthStreamingMode(bytes.size)
                c.outputStream.use { it.write(bytes) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream ?: throw IOException("HTTP $code")
            // Reading to the end and closing (without disconnect) lets HttpURLConnection reuse the keep-alive socket.
            return stream.use { it.readBytes().decodeToString() }
        } catch (e: IOException) {
            c.disconnect()
            throw e
        }
    }

    /**
     * What each VR board shows. Read by the boards themselves and by the VR board page on the PC; written only by
     * that page. Null means the PC could not be reached — an empty configuration is a real answer, not a failure.
     */
    suspend fun boards(): BoardConfig? = runCatching { get<BoardConfig>("/api/boards") }.getOrNull()

    /** Saves it, and answers with what the PC stored. */
    suspend fun saveBoards(config: BoardConfig): BoardConfig? = withContext(Dispatchers.IO) {
        runCatching {
            val body = Repo.json.encodeToString(BoardConfig.serializer(), config)
            Repo.json.decodeFromString<BoardConfig>(request("POST", "/api/boards", timeoutMs = 8000, body = body))
        }.getOrNull()
    }

    /**
     * Opens UOAF's kneeboard exporter on the BMS PC and answers with what it said.
     *
     * A shortcut, not an export: that tool has no headless mode, so this puts its window up on the PC where the
     * pilot can press export. From a tablet it is the same window, on the PC across the room.
     */
    suspend fun openKneeboardExporter(): String = withContext(Dispatchers.IO) {
        runCatching {
            val text = request("POST", "/api/kneeboard/open", timeoutMs = 10_000)
            Regex("\"message\"\\s*:\\s*\"(.*?)\"").find(text)?.groupValues?.get(1)?.replace("\\\\", "\\")
                ?: "The exporter was asked to open on the BMS PC."
        }.getOrElse { "Could not reach the PC: ${reasonOf(it)}" }
    }

    /** Asks the bridge to run EZBoards; the result also refreshes the board data. */
    fun generateBoards() {
        if (_ez.value.running) return
        _ez.value = EzUi(running = true, result = _ez.value.result)
        scope.launch {
            val r = runCatching {
                val text = request("POST", "/api/ezboards/generate", timeoutMs = 95_000)
                Repo.json.decodeFromString<EzRun>(text)
            }.getOrElse { EzRun(time = System.currentTimeMillis(), ok = false, message = "Could not reach the PC: ${reasonOf(it)}") }
            _ez.value = EzUi(running = false, result = r)
            runCatching { get<MissionData>("/api/mission") }.onSuccess { _mission.value = it }
        }
    }

    // ---------- screenshots on the BMS PC ----------

    suspend fun mediaList(): MediaList? = withContext(Dispatchers.IO) { runCatching { get<MediaList>("/api/media") }.getOrNull() }

    /** Raw bytes of a bridge resource (screenshot thumbnail, preview or original), or null. */
    suspend fun fetchBytes(path: String, timeoutMs: Int = 15_000): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            val b = base() ?: throw IOException("no BMS PC set")
            val c = URL(b + path).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 2500; c.readTimeout = timeoutMs
                if (c.responseCode !in 200..299) null else c.inputStream.use { it.readBytes() }
            } finally { c.disconnect() }
        }.getOrNull()
    }

    /** Moves screenshots on the BMS PC to its Recycle Bin. Returns how many were deleted, or null when the bridge is unreachable. */
    suspend fun deleteMedia(names: List<String>): Int? = withContext(Dispatchers.IO) {
        runCatching {
            val b = base() ?: throw IOException("no BMS PC set")
            val c = URL("$b/api/media/delete").openConnection() as HttpURLConnection
            try {
                val body = Repo.json.encodeToString(kotlinx.serialization.builtins.ListSerializer(String.serializer()), names).toByteArray()
                c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 2500; c.readTimeout = 15_000
                c.setFixedLengthStreamingMode(body.size)
                c.outputStream.use { it.write(body) }
                val text = c.inputStream.use { it.readBytes().decodeToString() }
                Regex("\"deleted\"\\s*:\\s*(\\d+)").find(text)?.groupValues?.get(1)?.toInt() ?: 0
            } finally { c.disconnect() }
        }.getOrNull()
    }

    fun mediaPath(kind: String, name: String, max: Int? = null) =
        "/api/media/$kind?name=" + java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20") + (max?.let { "&max=$it" } ?: "")

    /** One-off connection test used by the setup screen. */
    suspend fun probe(host: String, port: Int): Result<BridgeInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val c = URL("http://$host:$port/api/info").openConnection() as HttpURLConnection
            c.connectTimeout = 2500; c.readTimeout = 2500
            try { Repo.json.decodeFromString<BridgeInfo>(c.inputStream.use { it.readBytes().decodeToString() }) } finally { c.disconnect() }
        }
    }

    /** Broadcasts a discovery probe on the local network and collects bridge replies for [timeoutMs]. */
    suspend fun discover(context: Context, timeoutMs: Int = 1800): List<FoundBridge> = withContext(Dispatchers.IO) {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = runCatching { wifi?.createMulticastLock("bms-companion-discovery")?.apply { setReferenceCounted(false); acquire() } }.getOrNull()
        val found = LinkedHashMap<String, FoundBridge>()
        try {
            DatagramSocket().use { sock ->
                sock.broadcast = true
                sock.soTimeout = 300
                val probe = "BMSC_DISCOVER".toByteArray()
                val targets = broadcastAddresses() + InetAddress.getByName("255.255.255.255")
                val end = System.currentTimeMillis() + timeoutMs
                var nextSend = 0L
                val buf = ByteArray(2048)
                while (System.currentTimeMillis() < end) {
                    if (System.currentTimeMillis() >= nextSend) {
                        targets.forEach { t -> runCatching { sock.send(DatagramPacket(probe, probe.size, t, 47475)) } }
                        nextSend = System.currentTimeMillis() + 600
                    }
                    try {
                        val p = DatagramPacket(buf, buf.size)
                        sock.receive(p)
                        val reply = runCatching { Repo.json.decodeFromString<DiscoveryReply>(String(p.data, 0, p.length)) }.getOrNull()
                        if (reply?.service == "bms-companion") {
                            val h = p.address.hostAddress ?: continue
                            found[h] = FoundBridge(h, reply.port, reply.name, reply.version)
                        }
                    } catch (_: SocketTimeoutException) { }
                }
            }
        } catch (_: Exception) {
        } finally {
            runCatching { lock?.release() }
        }
        found.values.toList()
    }

    private fun broadcastAddresses(): List<InetAddress> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { it.interfaceAddresses }.filter { it.address is Inet4Address }.mapNotNull { it.broadcast }
    }.getOrDefault(emptyList())
}
