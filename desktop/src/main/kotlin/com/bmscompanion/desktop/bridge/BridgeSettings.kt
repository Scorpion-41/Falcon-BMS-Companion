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
    /**
     * Generate the BMS kneeboards whenever the briefing is printed. **On.**
     *
     * Pressing PRINT in the briefing is the moment a pilot's kneeboards ought to appear, and it is what every page
     * in this program says happens. It was off to begin with, and following those instructions on a tablet did
     * nothing and said nothing. It only ever runs the EZBoards folder that has been configured, so with none set
     * it does nothing at all.
     */
    val AutoEzBoardsOnPrint: Boolean = true,
    val TacviewEnabled: Boolean = true,
    val TacviewHost: String = "127.0.0.1",
    val TacviewPort: Int = 42674,
    val TacviewPassword: String = "",
    /** What each VR board shows. Null until the pilot opens the VR board page, which starts from the defaults. */
    val Boards: com.bmscompanion.app.data.mission.BoardConfig? = null,
    /**
     * How far the stored file has been brought forward. See [migrate].
     *
     * Every setting is written to disk, defaults included, so a default that changes later never reaches anyone who
     * has run the program once. This is the number that lets a changed default be applied exactly once.
     */
    val SettingsVersion: Int = CURRENT,
) {
    companion object {
        private val file get() = File(Repo.settingsFolder, "bridge-settings.json")
        private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true; explicitNulls = true }

        /** The settings layout this build writes. Bump it when a default changes and add the step to [migrate]. */
        const val CURRENT = 1

        fun load(): BridgeSettings = runCatching {
            migrate(json.decodeFromString(serializer(), file.readText()))
        }.getOrElse {
            if (file.exists()) BridgeLog.warn("Settings load failed: ${it.message}")
            BridgeSettings()
        }

        /**
         * Brings a stored file forward, once.
         *
         * 1: **kneeboards on PRINT.** It is on for a new install and every page in the program says PRINT produces
         *    them, but it was off to begin with and that off was written into everyone's file as a default rather
         *    than as a choice. Turned on once; a pilot who turns it off afterwards keeps it off.
         */
        private fun migrate(s: BridgeSettings): BridgeSettings {
            if (s.SettingsVersion >= CURRENT) return s
            val next = s.copy(AutoEzBoardsOnPrint = true, SettingsVersion = CURRENT)
            BridgeLog.info("Settings brought forward to $CURRENT: kneeboards are now generated when the briefing is printed")
            next.save()
            return next
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
