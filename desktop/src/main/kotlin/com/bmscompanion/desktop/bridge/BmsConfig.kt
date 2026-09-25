package com.bmscompanion.desktop.bridge

import com.bmscompanion.app.data.mission.CfgFile
import com.bmscompanion.app.data.mission.CfgLine
import com.bmscompanion.app.data.mission.CfgProfiles
import com.bmscompanion.app.data.mission.CfgState
import java.io.File

/**
 * Editing Falcon BMS's own config files, and keeping a way back.
 *
 * This is the one place BMS Companion writes into the BMS folder, and it does so only after the pilot has pressed
 * the button that takes a copy first. Everything it touches lives in `User/Config`:
 *
 *     Falcon BMS User.cfg    the settings BMS reads. This is what a profile is copied onto.
 *     Falcon BMS VR.cfg      the same for a headset, written by BMS the first time it starts in one.
 *     BackUp/                made here, and never read or written by BMS
 *       Falcon BMS User.cfg  the untouched original — taken once, never replaced, the way back
 *       User Profile 1..3.cfg   the three sets the pilot switches between
 *       (and the same four for VR)
 *
 * Two things are handled carefully, because getting either wrong breaks somebody's install.
 *
 * **The launcher's block.** A cfg can end with "LAUNCHER OVERRIDES BEGIN HERE - DO NOT EDIT OR ADD BELOW THIS
 * LINE", which the BMS launcher writes and owns. Settings are written above that line and the block is carried
 * across untouched when a profile is applied, so a pilot's device setup survives switching profiles.
 *
 * **The original.** The first backup is taken once and never overwritten, however many times the button is
 * pressed afterwards: it is the only copy of what they had before any of this.
 *
 * A setting is cleared rather than written at its default value. BMS's own `Falcon BMS.cfg` already holds every
 * default, so the way to say "default" in a User cfg is to say nothing at all.
 */
class BmsConfig(private val install: BmsInstall) {

    /** The two files BMS reads, and what they are called in the API. */
    enum class Kind(val id: String, val file: String, val profile: String) {
        USER("user", "Falcon BMS User.cfg", "User Profile"),
        VR("vr", "Falcon BMS VR.cfg", "VR Profile");

        companion object {
            fun of(id: String?): Kind = if (id.equals("vr", true)) VR else USER
        }
    }

    private companion object {
        const val LAUNCHER_MARK = "LAUNCHER OVERRIDES BEGIN HERE"
        val SET = Regex("""set\s+(\S+)\s+("[^"]*"|\S+).*""")
    }

    /**
     * Nothing in here throws.
     *
     * Falcon BMS is often installed somewhere Windows will not let an ordinary program write — Program Files, a
     * drive mounted read-only, a folder another tool has open — and a pilot pressing a button must get a sentence
     * saying so, not a page that goes quiet or a program that falls over. Every step that touches the disk runs
     * through [attempt], and the reason for a failure travels back in `CfgState.error` / `CfgFile.error`.
     */
    private fun <T> attempt(fallback: (String) -> T, body: () -> T): T = try {
        body()
    } catch (e: Throwable) {
        BridgeLog.warn("Config: ${e::class.simpleName}: ${e.message}")
        fallback(reason(e))
    }

    private fun reason(e: Throwable): String = when {
        e is java.nio.file.AccessDeniedException || e is SecurityException ->
            "Windows would not let BMS Companion write in the Falcon BMS folder. It is usually installed somewhere " +
                "that needs administrator rights: start BMS Companion as administrator, or move the install."
        e is java.nio.file.FileSystemException && e.reason?.contains("another process", true) == true ->
            "Another program has that file open — close Falcon BMS and its launcher, then try again."
        e is java.io.IOException -> "The file could not be written: ${e.message ?: "no reason given"}."
        else -> e.message ?: e::class.simpleName ?: "something went wrong"
    }

    private fun configDir(): File? = install.baseDir?.let { File(it, "User\\Config") }?.takeIf { it.isDirectory }
    private fun backupDir(): File? = configDir()?.let { File(it, "BackUp") }

    private fun active(kind: Kind) = configDir()?.let { File(it, kind.file) }
    private fun original(kind: Kind) = backupDir()?.let { File(it, kind.file) }
    private fun profileFile(kind: Kind, n: Int) = backupDir()?.let { File(it, "${kind.profile} ${n.coerceIn(1, 3)}.cfg") }

    /** Which profile the live file is a copy of, remembered beside the profiles themselves. */
    private fun markerFile(kind: Kind) = backupDir()?.let { File(it, "${kind.profile} selected.txt") }

    // ---------------------------------------------------------------- what the page needs to know

    fun state(error: String? = null): CfgState = attempt({ CfgState(error = it) }) {
        val dir = configDir()
        CfgState(
            available = dir != null,
            configDir = dir?.path,
            backupDir = backupDir()?.path,
            userBackedUp = original(Kind.USER)?.isFile == true,
            vrPresent = active(Kind.VR)?.isFile == true,
            vrBackedUp = original(Kind.VR)?.isFile == true,
            user = profilesOf(Kind.USER),
            vr = profilesOf(Kind.VR),
            error = error,
        )
    }

    private fun profilesOf(kind: Kind) = CfgProfiles(
        selected = selected(kind),
        profiles = (1..3).map { n -> profileFile(kind, n)?.isFile == true },
    )

    /** Profile 1 if the marker cannot be read: a missing note means nothing has been switched. */
    private fun selected(kind: Kind): Int = attempt({ 1 }) {
        markerFile(kind)?.takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull()?.coerceIn(1, 3) ?: 1
    }

    // ---------------------------------------------------------------- the one-time copy

    /**
     * Takes the copy that unlocks the page, and lays down the three profiles.
     *
     * Profile 1 starts as whatever the pilot already had, so selecting it changes nothing. Profiles 2 and 3 start
     * empty, which in a cfg means every setting at its default.
     */
    fun backUp(): CfgState = attempt({ state(it) }) {
        val dir = configDir() ?: return@attempt state()
        val back = File(dir, "BackUp")
        if (!back.isDirectory && !back.mkdirs()) {
            return@attempt state(
                "The BackUp folder could not be made in ${dir.path}. Falcon BMS is usually installed somewhere that " +
                    "needs administrator rights: start BMS Companion as administrator, or move the install.",
            )
        }
        // each file on its own: a VR file that cannot be copied must not cost the User one its backup
        val errors = Kind.entries.mapNotNull { k -> attempt<String?>({ it }) { backUp(k); null } }
        state(errors.firstOrNull())
    }

    private fun backUp(kind: Kind) {
        val live = active(kind)?.takeIf { it.isFile } ?: return
        val orig = original(kind) ?: return
        // once, and never again: this is the only copy of what they had before any of this
        if (!orig.isFile) live.copyTo(orig)
        profileFile(kind, 1)?.takeIf { !it.isFile }?.let { live.copyTo(it) }
        for (n in 2..3) {
            val f = profileFile(kind, n) ?: continue
            if (!f.isFile) f.writeText(blank(kind, n))
        }
        markerFile(kind)?.takeIf { !it.isFile }?.writeText("1")
    }

    private fun blank(kind: Kind, n: Int) = listOf(
        "// ${kind.profile} $n — written by BMS Companion.",
        "// Empty means every setting at its default; the original file is in BackUp.",
        "",
    ).joinToString("\r\n")

    // ---------------------------------------------------------------- reading

    /**
     * Every `set` line a profile holds, in the order it holds them — one per setting.
     *
     * A cfg may well name the same setting twice, and BMS takes the **last** one, so that is the one reported: a
     * page showing both would be showing a value that is not in force, and a list with the same key twice is a
     * list that cannot be drawn.
     */
    fun read(kind: Kind, profile: Int, error: String? = null): CfgFile {
        val n = profile.coerceIn(1, 3)
        return attempt({ CfgFile(kind.id, n, emptyList(), error ?: it) }) {
            val f = profileFile(kind, n)?.takeIf { it.isFile }
                ?: return@attempt CfgFile(kind.id, n, emptyList(), error)
            val lines = ArrayList<CfgLine>()
            var belowLauncher = false
            for (raw in f.readLines()) {
                if (raw.contains(LAUNCHER_MARK)) { belowLauncher = true; continue }
                val m = SET.matchEntire(raw.trim()) ?: continue
                lines += CfgLine(m.groupValues[1], m.groupValues[2], belowLauncher)
            }
            // last one wins, and it keeps its place in the file
            val once = lines.asReversed().distinctBy { it.key to it.launcher }.asReversed()
            CfgFile(kind.id, n, once, error)
        }
    }

    // ---------------------------------------------------------------- changing one setting

    /**
     * Sets or clears one line in a profile, and in the live file when that profile is the one in use.
     *
     * A null [value] removes the line, which is how a setting goes back to BMS's default.
     */
    fun set(kind: Kind, profile: Int, key: String, value: String?): CfgFile =
        attempt({ read(kind, profile, it) }) {
            if (!key.matches(Regex("[A-Za-z0-9_]{1,64}"))) return@attempt read(kind, profile, "\"$key\" is not a setting name.")
            val f = profileFile(kind, profile)?.takeIf { it.isFile }
                ?: return@attempt read(kind, profile, "That profile has no file yet — press the backup button first.")
            writeSafely(f, edited(f.readText(), key, value?.trim()?.takeIf { it.isNotEmpty() && '\n' !in it }))
            if (selected(kind) == profile) apply(kind, profile)
            read(kind, profile)
        }

    /**
     * Writes a settings file somewhere else first and moves it into place.
     *
     * Writing over a file truncates it before the new text goes in, so a write that fails half way — a full disk, a
     * folder that turned read-only, the machine going down — would leave a pilot with a Falcon BMS config that is
     * empty or half a file. The move is the only moment the real file changes, and it either happens or it does not.
     */
    private fun writeSafely(file: File, text: String) {
        val tmp = File(file.parentFile, file.name + ".bmsc-new")
        try {
            tmp.writeText(text)
            val from = tmp.toPath()
            val to = file.toPath()
            try {
                java.nio.file.Files.move(
                    from, to,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
                java.nio.file.Files.move(from, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            // gone already after a successful move; this is for the run that did not get that far
            runCatching { if (tmp.exists()) tmp.delete() }
        }
    }

    /** The text of a cfg with one line set, cleared, or added above the launcher's block. */
    private fun edited(text: String, key: String, value: String?): String {
        val eol = if ("\r\n" in text) "\r\n" else "\n"
        val lines = text.split(Regex("\r?\n")).toMutableList()
        val mark = lines.indexOfFirst { LAUNCHER_MARK in it }
        val limit = if (mark >= 0) mark else lines.size
        // BMS reads the file top to bottom and the last line wins, so that is the one to change — and clearing a
        // setting has to take every copy out, or an earlier one it was hiding would come back into force.
        val hits = (0 until limit).filter { SET.matchEntire(lines[it].trim())?.groupValues?.get(1) == key }
        if (hits.isNotEmpty()) {
            if (value == null) hits.asReversed().forEach { lines.removeAt(it) }
            else {
                lines[hits.last()] = "set $key $value"
                hits.dropLast(1).asReversed().forEach { lines.removeAt(it) }
            }
        } else {
            if (value == null) return text
            // a new line goes above the launcher's block, which the launcher owns and rewrites
            lines.add(limit, "set $key $value")
        }
        return lines.joinToString(eol)
    }

    // ---------------------------------------------------------------- switching, copying, going back

    /** Makes a profile the one BMS reads, by copying it over the live file. */
    fun select(kind: Kind, profile: Int): CfgState = attempt({ state(it) }) {
        if (profileFile(kind, profile)?.isFile != true) {
            return@attempt state("Profile $profile has no file yet — press the backup button first.")
        }
        apply(kind, profile)
        markerFile(kind)?.let { writeSafely(it, profile.coerceIn(1, 3).toString()) }
        state()
    }

    private fun apply(kind: Kind, profile: Int) {
        val from = profileFile(kind, profile)?.takeIf { it.isFile } ?: return
        val to = active(kind) ?: return
        // the launcher's block belongs to the install, not to the profile, so it stays where it is
        val keep = to.takeIf { it.isFile }?.readText()?.let { fromMark(it) }
        val body = untilMark(from.readText())
        writeSafely(to, if (keep == null) body else body.trimEnd() + "\r\n\r\n" + keep)
    }

    private fun untilMark(text: String): String {
        val i = text.indexOf(LAUNCHER_MARK)
        if (i < 0) return text
        return text.substring(0, text.lastIndexOf('\n', i).let { if (it < 0) 0 else it + 1 })
    }

    private fun fromMark(text: String): String? {
        val i = text.indexOf(LAUNCHER_MARK)
        if (i < 0) return null
        return text.substring(text.lastIndexOf('\n', i).let { if (it < 0) 0 else it + 1 })
    }

    /** Copies one profile's settings onto another, as a starting point rather than a blank sheet. */
    fun copy(kind: Kind, from: Int, to: Int): CfgFile = attempt({ read(kind, to, it) }) {
        val src = profileFile(kind, from)?.takeIf { it.isFile }
            ?: return@attempt read(kind, to, "Profile $from has no file to copy from.")
        val dst = profileFile(kind, to) ?: return@attempt read(kind, to, "Profile $to has no file yet.")
        if (src.path != dst.path) writeSafely(dst, src.readText())
        if (selected(kind) == to) apply(kind, to)
        read(kind, to)
    }

    /** Puts a profile back to the file that was there before any of this. */
    fun restore(kind: Kind, profile: Int): CfgFile = attempt({ read(kind, profile, it) }) {
        val orig = original(kind)?.takeIf { it.isFile }
            ?: return@attempt read(kind, profile, "There is no backup of that file to go back to.")
        val dst = profileFile(kind, profile) ?: return@attempt read(kind, profile, "Profile $profile has no file yet.")
        writeSafely(dst, orig.readText())
        if (selected(kind) == profile) apply(kind, profile)
        read(kind, profile)
    }
}
