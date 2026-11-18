package com.bmscompanion.desktop.bridge.command

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.FileTime

/**
 * `--cmdbackuptest <a scratch folder> out.txt`: the Command section's backups across a Falcon BMS update
 * ([CommandBackup.refreshUpdated]), in a made-up campaign folder (never a BMS install).
 *
 * 1. a campaign start (`Save0.cam`) is backed up once;
 * 2. an "update" replaces it, its modified time kept older than its created time, as BMS's updater leaves the files it
 *    lays down: the copy becomes the new file and the earlier copy is kept under `Older copies\<date>\`;
 * 3. the section (or the pilot) writes it in place — modified when created: the copy stays the update's;
 * 4. Restore puts the update's file back, not the one before the update;
 * 5. a file an update replaced before the section was opened is refreshed by Restore itself.
 */
object CommandBackupTest {
    fun run(scratch: File): String = buildString {
        fun ok(what: String, good: Boolean, info: String = "") = appendLine((if (good) "PASS " else "FAIL ") + what + if (info.isEmpty()) "" else "  ($info)")
        val folder = File(scratch, "bmsc-cmdbackuptest-${System.currentTimeMillis()}").apply { mkdirs() }
        appendLine("folder ${folder.path}")
        val start = File(folder, "Save0.cam")
        val now = System.currentTimeMillis()

        fun lay(f: File, bytes: ByteArray, modified: Long) {
            f.delete()
            f.writeBytes(bytes)
            Files.setAttribute(f.toPath(), "creationTime", FileTime.fromMillis(System.currentTimeMillis()))
            f.setLastModified(modified)
        }
        fun copyBytes(name: String) = runCatching { File(CommandBackup.dirFor(folder), name).readBytes().decodeToString() }.getOrNull()

        // 1. BMS's file as installed (an older date than its creation, as every installed file has)
        lay(start, "4.38.1 start".toByteArray(), now - 400L * 86_400_000)
        val first = CommandBackup.ensureFolder(folder)
        ok("1. the start is backed up once", first.error == null && copyBytes("Save0.cam") == "4.38.1 start", "copied ${first.copied}, ${first.error ?: "no error"}")
        ok("1. nothing to refresh right after", CommandBackup.refreshUpdated(folder).copied == 0)

        // 2. an update lays down a new file, its own (older) build date kept
        Thread.sleep(50)
        lay(start, "4.38.2 start".toByteArray(), now - 5L * 3_600_000)
        ok("2. the new file reads as laid by an update", CommandBackup.laidByUpdate(start))
        val second = CommandBackup.ensureFolder(folder)
        ok("2. the update's file becomes the original", second.error == null && copyBytes("Save0.cam") == "4.38.2 start", "refreshed ${second.copied}, ${second.error ?: "no error"}")
        val older = File(File(CommandBackup.dirFor(folder), CommandBackup.OLDER), "").listFiles()?.flatMap { d -> d.listFiles()?.toList().orEmpty() }.orEmpty()
        ok("2. the earlier copy is kept under Older copies", older.any { it.name == "Save0.cam" && it.readBytes().decodeToString() == "4.38.1 start" }, older.joinToString { it.parentFile.name + "\\" + it.name })
        val entry = CommandBackup.list(folder).firstOrNull { it.name == "Save0.cam" }
        ok("2. the record says why", entry?.why?.startsWith("updated by Falcon BMS") == true, entry?.why.orEmpty())

        // 3. written in place by the section or the pilot: modified as it is created, so not an update
        start.writeBytes("edited start".toByteArray())
        ok("3. an in-place write does not read as an update", !CommandBackup.laidByUpdate(start))
        val third = CommandBackup.ensureFolder(folder)
        ok("3. the copy stays the update's file", third.error == null && copyBytes("Save0.cam") == "4.38.2 start", "refreshed ${third.copied}")

        // 4. Restore gives the update's file back
        val restored = CommandBackup.restore(folder, "Save0.cam")
        ok("4. Restore puts back the update's file", restored == null && start.readBytes().decodeToString() == "4.38.2 start", restored ?: "")
        ok("4. nothing to refresh after the restore", CommandBackup.refreshUpdated(folder).copied == 0)

        // 5. a later update arrives and Restore is pressed before the section opens the folder again
        Thread.sleep(50)
        lay(start, "4.38.3 start".toByteArray(), now - 2L * 3_600_000)
        val r5 = CommandBackup.restore(folder, "Save0.cam")
        ok("5. Restore takes the newer update as the original first", r5 == null && start.readBytes().decodeToString() == "4.38.3 start" && copyBytes("Save0.cam") == "4.38.3 start", r5 ?: "")

        runCatching { folder.deleteRecursively() }
    }
}
