package com.bmscompanion.desktop.bridge

import com.bmscompanion.app.data.Repo
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
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
    /** UOAF's kneeboard exporter, a separate download (looked for in Tools\\html_brief_win). Null means "the default place". */
    val KneeboardExporterDir: String? = null,
    /**
     * The folder holding `WeaponDeliveryPlanner.exe`. Null means "look in the usual places".
     *
     * WDP is not shipped with BMS and installs nowhere in particular, so the guess is only a convenience; this is
     * the answer. Nothing in that folder is ever written — the app starts the tool and shows its window.
     */
    val WeaponPlannerDir: String? = null,
    /**
     * The Planner's DataCards folder (WDP's Settings → DataCard directory, its `Setup.ini` `[Main] DatacardDir`): where
     * Load/Backup DataCard, Save/Load Codewords and Package Timing, Cards Directory and the plan pictures open. Null
     * (or blank) means the default, `DataCards` in the Planner's own folder inside the BMS install
     * (`<BMS>\User\BMS Companion Planner\DataCards`). Read and set from any device through `/api/files/planner`.
     */
    val PlannerDataCardDir: String? = null,
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
    /**
     * Whether hostile contacts from the live feed leave the PC (`/api/contacts`): **off** unless the pilot turns it on
     * (any Setup page, `POST /api/contacts/hostiles`), so the app is no cheat — the enemy is what the briefing and the
     * cockpit show. Set off once for everyone by step 3 of [migrate]. See `HostileContacts`.
     */
    val ShowHostiles: Boolean = false,
    /** What each VR board shows. Null until the pilot opens the VR board page, which starts from the defaults. */
    val Boards: com.bmscompanion.app.data.mission.BoardConfig? = null,
    /**
     * Where the Mission section's data comes from, on every device: `"ezboards"` (**EZBoards mode**, Falcon BMS's
     * printed briefing and the cartridge — the default, and the app as it always was) or `"wdp"` (**WDP mode**, the
     * snapshot the Planner's Populate from Planner took). See [MissionSource]; `MissionMode` has the words.
     */
    val MissionSource: String = com.bmscompanion.app.data.mission.MissionMode.EZBOARDS,
    /** When [MissionSource] was last switched (ms since 1970); 0 = never. */
    val MissionSourceSince: Long = 0,
    /**
     * Delete Falcon BMS's old debug logs (`*_xlog.txt` and the same sessions' `*_xlog_*.csv`) to the Recycle Bin, at
     * program start and when a new log starts. **Off** unless the pilot turns it on (Setup, the radio log card); never
     * the newest session's, never anything else in the folder ([RadioLog.plan]).
     */
    val RadioLogCleanup: Boolean = false,
    /** how many sessions to keep, or days when [RadioLogKeepDays] */
    val RadioLogKeep: Int = 5,
    val RadioLogKeepDays: Boolean = false,
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
        const val CURRENT = 3

        fun load(): BridgeSettings = runCatching {
            val text = file.readText()
            val s = json.decodeFromString(serializer(), text)
            // A file from before the number existed has no SettingsVersion, and decoding one gives the default —
            // this build's — which would skip every step. Such a file is layout 0.
            val numbered = runCatching { "SettingsVersion" in json.parseToJsonElement(text).jsonObject }.getOrDefault(true)
            migrate(if (numbered) s else s.copy(SettingsVersion = 0))
        }.getOrElse {
            if (file.exists()) BridgeLog.warn("Settings load failed: ${it.message}")
            BridgeSettings()
        }

        /**
         * Brings a stored file forward, once, one step at a time: each step runs only for a file older than it.
         *
         * 1: **kneeboards on PRINT.** It is on for a new install and every page in the program says PRINT produces
         *    them, but it was off to begin with and that off was written into everyone's file as a default rather
         *    than as a choice. Turned on once; a pilot who turns it off afterwards keeps it off.
         * 2: **the Mission section's source** (1.3.8). Everyone who had the program before starts in EZBoards mode —
         *    the printed briefing, exactly as it was — whatever the file holds; WDP mode is a choice made on the page.
         * 3: **hostile contacts off** (1.3.8). The live feed's enemy is no longer shown unless the pilot turns
         *    [ShowHostiles] on; set off once whatever the file holds, and a pilot who turns it on afterwards keeps it on.
         */
        internal fun migrate(s: BridgeSettings): BridgeSettings = migrate(s, save = true)

        /** [migrate], writing the file only when [save] (the check brings a settings object forward without a disk). */
        internal fun migrate(s: BridgeSettings, save: Boolean): BridgeSettings {
            if (s.SettingsVersion >= CURRENT) return s
            var next = s
            if (next.SettingsVersion < 1) {
                next = next.copy(AutoEzBoardsOnPrint = true, SettingsVersion = 1)
                BridgeLog.info("Settings brought forward to 1: kneeboards are now generated when the briefing is printed")
            }
            if (next.SettingsVersion < 2) {
                next = next.copy(MissionSource = com.bmscompanion.app.data.mission.MissionMode.EZBOARDS, MissionSourceSince = 0, SettingsVersion = 2)
                BridgeLog.info("Settings brought forward to 2: the Mission section is in EZBoards mode (the printed briefing)")
            }
            if (next.SettingsVersion < 3) {
                next = next.copy(ShowHostiles = false, SettingsVersion = 3)
                BridgeLog.info("Settings brought forward to 3: hostile contacts from the live feed are off (Setup turns them on)")
            }
            next = next.copy(SettingsVersion = CURRENT)
            if (save) next.save()
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
