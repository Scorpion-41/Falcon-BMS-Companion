package com.bmscompanion.desktop.bridge

import com.bmscompanion.app.data.Repo
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Settings of the part of BMS Companion that reads Falcon BMS and serves devices, in %APPDATA%\BMS Companion\bridge-settings.json
 * (the same file and keys the earlier separate bridge used, so existing settings carry over).
 */
@Serializable
data class BridgeSettings(
    val Port: Int = 47474,
    val BmsDirOverride: String? = null,
    val EzBoardsDir: String? = null,
    /** UOAF's kneeboard exporter (BMS ships it in Tools\\html_brief_win). Null means "wherever BMS has it". */
    val KneeboardExporterDir: String? = null,
    /** Screenshot folder when it isn't the one BMS reports (rarely needed). */
    val PicturesDirOverride: String? = null,
    val AutoEzBoardsOnPrint: Boolean = false,
    val TacviewEnabled: Boolean = true,
    val TacviewHost: String = "127.0.0.1",
    val TacviewPort: Int = 42674,
    val TacviewPassword: String = "",
    /** Off by default so a first-time user always sees real BMS status. */
    val DemoMode: Boolean = false,
    /** What each VR board shows. Null until the pilot opens the VR board page, which starts from the defaults. */
    val Boards: com.bmscompanion.app.data.mission.BoardConfig? = null,
) {
    companion object {
        private val file get() = File(Repo.settingsFolder, "bridge-settings.json")
        private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true; explicitNulls = true }

        fun load(): BridgeSettings = runCatching { json.decodeFromString(serializer(), file.readText()) }.getOrElse {
            if (file.exists()) BridgeLog.warn("Settings load failed: ${it.message}")
            BridgeSettings()
        }
    }

    fun save() {
        runCatching {
            file.parentFile.mkdirs()
            val tmp = File(file.parentFile, "bridge-settings.json.tmp")
            tmp.writeText(json.encodeToString(serializer(), this))
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        }.onFailure { BridgeLog.warn("Settings save failed: ${it.message}") }
    }
}

/** Recent activity shown in the server page (last 300 lines). */
object BridgeLog {
    private val lines = ArrayDeque<String>()
    private val time = DateTimeFormatter.ofPattern("HH:mm:ss")

    fun info(msg: String) = add("INFO", msg)
    fun warn(msg: String) = add("WARN", msg)

    @Synchronized private fun add(level: String, msg: String) {
        lines.addLast("${LocalTime.now().format(time)} $level $msg")
        while (lines.size > 300) lines.removeFirst()
    }

    @Synchronized fun snapshot(): List<String> = lines.toList()
}
