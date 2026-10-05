package com.bmscompanion.desktop.bridge

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Density
import com.bmscompanion.app.data.FileFilter
import com.bmscompanion.app.data.FileMode
import com.bmscompanion.app.data.FileRequest
import com.bmscompanion.app.data.NativeAnswer
import com.bmscompanion.app.data.PcFiles
import com.bmscompanion.app.data.PcText
import com.bmscompanion.app.data.Platform
import com.bmscompanion.app.data.mission.MissionLink
import com.bmscompanion.app.data.mission.PcFileData
import com.bmscompanion.app.data.mission.PcFileEntry
import com.bmscompanion.app.data.mission.PcFileWrite
import com.bmscompanion.app.data.mission.PcFolder
import com.bmscompanion.app.data.mission.PcPlaces
import com.bmscompanion.app.ui.screens.wdp.PcFileWindowHost
import com.bmscompanion.app.ui.theme.BmsTheme
import com.bmscompanion.app.ui.theme.Hud
import com.bmscompanion.desktop.PcFileDialogs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import java.nio.file.Files
import java.util.Base64

/**
 * `--filestest out.txt <scratch folder> [<picture folder>]`: the Planner's file windows (`/api/files`, [PcFiles],
 * Windows' dialog) in-process, against the BMS folder the settings point at (a copy) and a scratch folder that is
 * wiped and filled here. Nothing is written anywhere else.
 *
 * 1. the places and the drives; 2. paths the PC will not take (a network path, a device name, a stream, `..`) and
 * types it will not read or write (a program, a script, BMS's own config and weather); 3. write, the question for a
 * file already there, overwrite, read back, a binary file, a folder that is not there, hidden files left out, the
 * type filter, a missing folder answered with the nearest one; 4. the client's side over the same routes (text in and
 * out, WDP's filter strings, a whole path as the file name, no window drawn); 5. Windows' dialog built, unshown;
 * 6. with a picture folder, the remote window drawn at a phone's and a PC's size.
 */
object PcFilesTest {
    fun run(scratch: File, pics: File?): String {
        val sb = StringBuilder()
        fun line(s: String) { sb.appendLine(s) }
        fun check(what: String, ok: Boolean, detail: String = "") = line((if (ok) "ok   " else "FAIL ") + what + if (detail.isNotEmpty()) "  [$detail]" else "")
        fun api(method: String, path: String, query: Map<String, String> = emptyMap(), body: String? = null): Pair<Int, String> {
            val r = Bridge.handle(ApiRequest(method, path, query, body?.toByteArray() ?: ByteArray(0), remote = "check"))
            return r.status to r.body.toString(Charsets.UTF_8)
        }
        fun err(text: String) = Regex("\"error\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(text)?.groupValues?.get(1).orEmpty()
        val json = Bridge.json

        Bridge.startForCheck()
        val base = Bridge.install.baseDir
        line("BMS folder (the settings' copy): ${base ?: "none"}")
        check("a developer run (writes into a BMS folder refused)", DevGuard.on)
        val dir = File(scratch, "files-test").absoluteFile
        dir.deleteRecursively()
        dir.mkdirs()
        line("scratch: ${dir.path}")
        line("")

        // ---------------------------------------------------------------- 1. places
        line("== 1. Places and drives")
        val (ps, pt) = api("GET", "/api/files/places")
        val places = runCatching { json.decodeFromString(PcPlaces.serializer(), pt) }.getOrNull()
        check("places answers", ps == 200 && places != null, "HTTP $ps")
        val kinds = places?.places?.map { it.kind }.orEmpty()
        for (k in listOf("bms", "config", "campaign")) check("place $k is offered", k in kinds, places?.places?.firstOrNull { it.kind == k }?.let { "${it.name} = ${it.path}" } ?: kinds.joinToString())
        places?.places?.forEach { line("     place ${it.kind}: ${it.name} (${it.detail ?: ""}) ${it.path}") }
        check("drives listed", places?.drives?.isNotEmpty() == true, places?.drives?.joinToString { "${it.name}=${it.path}" }.orEmpty())
        check("types: .exe never read or written", places != null && "exe" !in places.readTypes && "exe" !in places.writeTypes)
        check("types: .fmap and .cfg read, not written", places != null && "fmap" in places.readTypes && "fmap" !in places.writeTypes && "cfg" !in places.writeTypes)
        line("")

        // ---------------------------------------------------------------- 2. refusals
        line("== 2. Paths and types the PC will not take")
        fun refused(what: String, method: String, path: String, q: Map<String, String>, want: Int, body: String? = null) {
            val (s, t) = api(method, path, q, body)
            check(what, s == want, "HTTP $s: ${err(t)}")
        }
        val some = """{"data":"${Base64.getEncoder().encodeToString("x".toByteArray())}"}"""
        refused("a network path is refused", "GET", "/api/files/list", mapOf("path" to "\\\\server\\share"), 400)
        refused("a device path is refused", "GET", "/api/files/read", mapOf("path" to "\\\\?\\C:\\x.ini"), 400)
        refused("a relative path is refused", "GET", "/api/files/list", mapOf("path" to "Data\\Campaign"), 400)
        refused("a reserved device name is refused", "GET", "/api/files/read", mapOf("path" to dir.path + "\\CON.ini"), 400)
        refused("an alternate data stream is refused", "GET", "/api/files/read", mapOf("path" to dir.path + "\\a.ini:hidden"), 400)
        refused("an unknown place is refused", "GET", "/api/files/list", mapOf("path" to "@nowhere"), 400)
        refused("reading a program is refused", "GET", "/api/files/read", mapOf("path" to "C:\\Windows\\notepad.exe"), 400)
        refused("writing a program is refused", "POST", "/api/files/write", mapOf("path" to dir.path + "\\evil.exe"), 400, some)
        refused("writing a script is refused", "POST", "/api/files/write", mapOf("path" to dir.path + "\\run.bat"), 400, some)
        refused("writing a .cfg is refused (the Config page's)", "POST", "/api/files/write", mapOf("path" to dir.path + "\\Falcon BMS User.cfg"), 400, some)
        refused("writing a .fmap is refused (the Weather page's)", "POST", "/api/files/write", mapOf("path" to dir.path + "\\1200.fmap"), 400, some)
        refused("a file with no type is refused", "POST", "/api/files/write", mapOf("path" to dir.path + "\\noname"), 400, some)
        refused("GET on write is refused", "GET", "/api/files/write", mapOf("path" to dir.path + "\\a.ini"), 405)
        val (ds, dt) = api("GET", "/api/files/list", mapOf("path" to dir.path + "\\sub\\..\\..\\files-test"))
        check("`..` is resolved before anything is looked at", ds == 200 && runCatching { json.decodeFromString(PcFolder.serializer(), dt).path }.getOrNull().equals(dir.path, true), "HTTP $ds")
        line("")

        // ---------------------------------------------------------------- 3. write, read, list
        line("== 3. Writing, reading, listing (in the scratch folder)")
        val ini = File(dir, "Viper1.ini")
        val first = "[STPT]\r\ntarget_0=1.0,2.0,3.0\r\n".toByteArray()
        fun write(f: File, bytes: ByteArray, overwrite: Boolean) =
            api("POST", "/api/files/write", mapOf("path" to f.path, "overwrite" to if (overwrite) "1" else "0"),
                json.encodeToString(PcFileWrite.serializer(), PcFileWrite(Base64.getEncoder().encodeToString(bytes))))
        val (w1, w1t) = write(ini, first, overwrite = false)
        val e1 = runCatching { json.decodeFromString(PcFileEntry.serializer(), w1t) }.getOrNull()
        check("a new file is written", w1 == 200 && ini.readBytes().contentEquals(first) && e1?.size == first.size.toLong(), "HTTP $w1 ${err(w1t)}")
        val (w2, w2t) = write(ini, "other".toByteArray(), overwrite = false)
        check("a file already there is not replaced without the pilot's yes", w2 == 409 && ini.readBytes().contentEquals(first), "HTTP $w2: ${err(w2t)}")
        val second = "[STPT]\r\ntarget_0=4.0,5.0,6.0\r\n".toByteArray()
        val (w3, _) = write(ini, second, overwrite = true)
        check("…and is with it", w3 == 200 && ini.readBytes().contentEquals(second), "HTTP $w3")
        check("no temporary file is left beside it", dir.listFiles().orEmpty().none { it.name.endsWith(".bmsc-new") })
        val (r1, r1t) = api("GET", "/api/files/read", mapOf("path" to ini.path))
        val data = runCatching { json.decodeFromString(PcFileData.serializer(), r1t) }.getOrNull()
        check("read back as written", r1 == 200 && data != null && Base64.getDecoder().decode(data.data).contentEquals(second) && data.name == "Viper1.ini", "HTTP $r1")
        val png = File(dir, "map.png")
        val bin = ByteArray(256) { it.toByte() }
        write(png, bin, overwrite = false)
        val (r2, r2t) = api("GET", "/api/files/read", mapOf("path" to png.path))
        check("a binary file goes both ways unchanged", r2 == 200 && runCatching { Base64.getDecoder().decode(json.decodeFromString(PcFileData.serializer(), r2t).data) }.getOrNull()?.contentEquals(bin) == true)
        val (w4, w4t) = write(File(dir, "missing\\x.ini"), first, overwrite = false)
        check("a folder that is not there is not made", w4 == 404 && !File(dir, "missing").exists(), "HTTP $w4: ${err(w4t)}")
        val (r3, r3t) = api("GET", "/api/files/read", mapOf("path" to File(dir, "none.ini").path))
        check("a file that is not there is 404, in words", r3 == 404 && err(r3t).isNotEmpty(), err(r3t))
        val (st, stt) = api("GET", "/api/files/stat", mapOf("path" to File(dir, "New.bdc").path))
        val ste = runCatching { json.decodeFromString(PcFileEntry.serializer(), stt) }.getOrNull()
        check("stat of a new name: not there, and writable", st == 200 && ste?.exists == false && ste.write, "HTTP $st")
        File(dir, "notes.txt").writeText("x")
        File(dir, "Sub").mkdirs()
        val hidden = File(dir, "hidden.ini").also { it.writeText("h") }
        runCatching { Files.setAttribute(hidden.toPath(), "dos:hidden", true) }
        val (l1, l1t) = api("GET", "/api/files/list", mapOf("path" to dir.path, "ext" to "ini"))
        val f1 = runCatching { json.decodeFromString(PcFolder.serializer(), l1t) }.getOrNull()
        val names = f1?.entries?.map { it.name }.orEmpty()
        check("the listing: folders first, then files of the type asked for", l1 == 200 && names == listOf("Sub", "Viper1.ini"), names.joinToString())
        check("hidden files are left out, and counted", f1?.skipped == 1 && "hidden.ini" !in names, "skipped ${f1?.skipped}")
        check("each file says whether the PC reads and writes it", f1?.entries?.firstOrNull { it.name == "Viper1.ini" }?.let { it.read && it.write } == true)
        val (l2, l2t) = api("GET", "/api/files/list", mapOf("path" to dir.path, "ext" to "*.png;*.txt"))
        check("WDP's patterns work as types", l2 == 200 && runCatching { json.decodeFromString(PcFolder.serializer(), l2t).entries.map { it.name } }.getOrNull() == listOf("Sub", "map.png", "notes.txt"))
        val (l3, l3t) = api("GET", "/api/files/list", mapOf("path" to dir.path + "\\Nope\\Deeper"))
        val f3 = runCatching { json.decodeFromString(PcFolder.serializer(), l3t) }.getOrNull()
        check("a folder that is not there shows the nearest one above it, and says so", l3 == 200 && f3?.path.equals(dir.path, true) && f3?.note != null, f3?.note.orEmpty())
        val (l4, l4t) = api("GET", "/api/files/list", mapOf("path" to ini.path))
        check("a file as the start shows its folder", l4 == 200 && runCatching { json.decodeFromString(PcFolder.serializer(), l4t) }.getOrNull()?.let { it.path.equals(dir.path, true) && it.note == null } == true)
        val (l5, l5t) = api("GET", "/api/files/list", mapOf("path" to "@config", "ext" to "ini"))
        val f5 = runCatching { json.decodeFromString(PcFolder.serializer(), l5t) }.getOrNull()
        check("@config lists User\\Config", l5 == 200 && f5?.path?.endsWith("User\\Config", true) == true, "${f5?.path} ${f5?.entries?.size} entries ${err(l5t)}")
        val (l6, l6t) = api("GET", "/api/files/list", mapOf("path" to "@campaign", "ext" to "fmap,twx"))
        val f6 = runCatching { json.decodeFromString(PcFolder.serializer(), l6t) }.getOrNull()
        check(
            "@campaign lists the theater's campaign folder, its weather files and nothing else",
            l6 == 200 && f6 != null && f6.entries.any { !it.dir } && f6.entries.all { it.dir || it.name.substringAfterLast('.').lowercase() in setOf("fmap", "twx") },
            "${f6?.path}: ${f6?.entries?.count { !it.dir }} files",
        )
        line("")

        // ---------------------------------------------------------------- 4. the client
        line("== 4. The client's side (MissionLink on This PC, in-process)")
        MissionLink.useThisPc()
        val text = "[Codewords]\r\nAsFragged=Café au lait\r\n"
        val codewords = File(dir, "Codewords.ini")
        val wrote = runBlocking { PcFiles.file(codewords.path).writeText(text) }
        check("PickedFile.writeText writes 8-bit text, as WDP's ini calls do", wrote.ok && codewords.readBytes().contentEquals(text.toByteArray(Charsets.ISO_8859_1)), wrote.error.orEmpty())
        val back = runBlocking { PcFiles.file(codewords.path).readText() }
        check("…and readText reads it back", back.value == text, back.error.orEmpty())
        val refusedRead = runBlocking { PcFiles.file("C:\\Windows\\notepad.exe").read() }
        check("a refused read comes back as the PC's sentence", refusedRead.value == null && refusedRead.error?.contains("does not read") == true, refusedRead.error.orEmpty())
        check("PcText: UTF-8 with a BOM", PcText.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte(), 0x41, 0xC3.toByte(), 0xA9.toByte())) == "Aé")
        check("PcText: UTF-16 with a BOM", PcText.decode(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x41, 0, 0xE9.toByte(), 0)) == "Aé")
        check("PcText: 8-bit text", PcText.decode(byteArrayOf(0x41, 0xE9.toByte())) == "Aé")
        val wx = FileFilter.parse("FMAP(*.fmap)|*.fmap|TWX(*.twx)|*.twx")
        check("WDP's filter string: Reload WX", wx == listOf(FileFilter("FMAP(*.fmap)", "fmap"), FileFilter("TWX(*.twx)", "twx")), wx.toString())
        val cam = FileFilter.parse("all|*.cam;*.tac;*.trn|(*.cam)|*.cam|(*.tac)|*.tac|(*.trn)|*.trn")
        check("WDP's filter string: Open Campaign or TE", cam.size == 4 && cam[0].extensions == listOf("cam", "tac", "trn") && cam[0].pattern == "*.cam;*.tac;*.trn", cam.toString())
        var seen: FileRequest? = null
        PcFiles.testAnswer = { r -> seen = r; "G:\\Some\\Folder\\Picked.bdc" }
        val picked = runBlocking { PcFiles.open("Load backup DataCard File", null, FileFilter.parse("Backup DataCard (*.bdc)|*.bdc"), "C:\\WDP\\DataCards\\*.bdc") }
        check("a whole path as the file name starts in its folder", seen?.startDir == "C:\\WDP\\DataCards" && seen?.fileName == "*.bdc", "${seen?.startDir} | ${seen?.fileName}")
        check("PickedFile: name, folder, type", picked?.name == "Picked.bdc" && picked.folder == "G:\\Some\\Folder" && picked.extension == "bdc" && picked.baseName == "Picked")
        PcFiles.testAnswer = null
        val none = runBlocking { withTimeoutOrNull(3000) { PcFiles.open("t", "@bms") } }
        check("with no window drawn, open answers null at once", none == null && !PcFiles.isBrowsing)
        line("")

        // ---------------------------------------------------------------- 5. Windows' dialog
        line("== 5. Windows' dialog (built, never shown)")
        val nOpen = PcFileDialogs.buildUnshown(FileRequest(FileMode.OPEN, "Load WX FMAP File", "@campaign", "1200.fmap", wx, 0, null))
        val campDir = PcFileRoutes.placeDir("campaign")?.path
        // the shell answers with the long form of a path the settings may give in its short 8.3 form
        fun same(a: String?, b: String?) = a != null && b != null && runCatching { File(a).canonicalPath.equals(File(b).canonicalPath, true) }.getOrDefault(false)
        check("Open: every setting taken, the start folder read back", nOpen is NativeAnswer.Picked && same(nOpen.path, campDir), nOpen.toString())
        val nSave = PcFileDialogs.buildUnshown(FileRequest(FileMode.SAVE, "Backup DataCard", dir.path, "Viper1", FileFilter.parse("Backup DataCard (*.bdc)|*.bdc"), 0, "bdc"))
        check("Save: every setting taken, the start folder read back", nSave is NativeAnswer.Picked && same(nSave.path, dir.path), nSave.toString())
        val nFolder = PcFileDialogs.buildUnshown(FileRequest(FileMode.FOLDER, "Datacard directory", dir.path, null, emptyList(), 0, null))
        check("Folder (WDP's FolderBrowserDialog): every setting taken, the start folder read back", nFolder is NativeAnswer.Picked && same(nFolder.path, dir.path), nFolder.toString())
        line("")

        // shown for real, on a desktop of the check's own that nobody sees (never the pilot's screen), and answered by
        // posting the dialog its own OK or Close: Show, GetResult, the type chosen, the default extension
        line("== 5b. Windows' dialog, shown on a desktop of its own and answered there")
        val ini2 = FileFilter.parse("Callsign DTC File|*.ini|All files|*.*")
        val (a1, n1) = onHiddenDesktop("BMSC check open", 1) {
            PcFileDialogs.showHere(FileRequest(FileMode.OPEN, "BMSC check open", dir.path, "Viper1.ini", ini2, 0, ".ini"))
        }
        check("Open + OK: the file named, the type it was picked under", a1 is NativeAnswer.Picked && same(a1.path, ini.path) && a1.filterIndex == 0, "$a1 $n1")
        val (a2, n2) = onHiddenDesktop("BMSC check save", 1) {
            PcFileDialogs.showHere(FileRequest(FileMode.SAVE, "BMSC check save", dir.path, "New7", FileFilter.parse("Backup DataCard (*.bdc)|*.bdc"), 0, "bdc"))
        }
        check("Save + OK: a new name gets WDP's default extension", a2 is NativeAnswer.Picked && same(a2.path, File(dir, "New7.bdc").path), "$a2 $n2")
        check("…and nothing is written by picking", !File(dir, "New7.bdc").exists())
        val (a3, n3) = onHiddenDesktop("BMSC check cancel", 2) {
            PcFileDialogs.showHere(FileRequest(FileMode.OPEN, "BMSC check cancel", dir.path, null, ini2, 1, null))
        }
        check("Cancel answers Cancelled", a3 == NativeAnswer.Cancelled, "$a3 $n3")
        val (a4, n4) = onHiddenDesktop("BMSC check folder", 1) {
            PcFileDialogs.showHere(FileRequest(FileMode.FOLDER, "BMSC check folder", dir.path, null, emptyList(), 0, null))
        }
        check("Folder + OK: the folder it opened on", a4 is NativeAnswer.Picked && same(a4.path, dir.path), "$a4 $n4")
        line("")

        // ---------------------------------------------------------------- 6. pictures
        if (pics != null) {
            line("== 6. The window on a device that is not the BMS PC")
            pics.mkdirs()
            val wxDir = PcFileRoutes.placeDir("campaign")?.path ?: "@bms"
            shot(sb, File(pics, "files-open-pc.png"), 1280, 800, 1f, touch = false) {
                PcFiles.open("Load WX FMAP File", wxDir, wx, "Fair.fmap")
            }
            shot(sb, File(pics, "files-open-phone.png"), 393, 851, 2.75f, touch = true) {
                PcFiles.open("Load WX FMAP File", wxDir, wx, "Fair.fmap")
            }
            shot(sb, File(pics, "files-save-phone.png"), 393, 851, 2.75f, touch = true) {
                PcFiles.save("Backup DataCard", dir.path, "Viper1", FileFilter.parse("Backup DataCard (*.bdc)|*.bdc"), 0, "bdc")
            }
            shot(sb, File(pics, "files-save-tablet.png"), 1180, 820, 2f, touch = true) {
                PcFiles.save("Save Callsign.ini File", dir.path, "Viper1", FileFilter.parse("Callsign DTC File|*.ini"), 0, ".ini")
            }
        }
        return sb.toString()
    }

    /**
     * Runs [body] (which shows a dialog titled [title] and waits for it) on a thread moved to a new, unseen desktop, and
     * answers the dialog from a second thread on that desktop: [press] 1 posts its OK (`WM_COMMAND IDOK`), anything
     * else its Close. Nothing is sent to the pilot's own desktop. Answers what [body] answered and a note.
     */
    private fun onHiddenDesktop(title: String, press: Int, body: () -> NativeAnswer): Pair<NativeAnswer?, String> {
        val user32 = com.sun.jna.NativeLibrary.getInstance("user32")
        fun fn(name: String) = user32.getFunction(name, com.sun.jna.Function.ALT_CONVENTION)
        val desk = fn("CreateDesktopW").invokePointer(
            arrayOf(com.sun.jna.WString("bmsc-filestest-" + System.nanoTime()), null, null, 0, 0x10000000, null),
        ) ?: return null to "CreateDesktop failed"
        var answer: NativeAnswer? = null
        val note = StringBuffer()
        val shower = Thread {
            if (fn("SetThreadDesktop").invokeInt(arrayOf(desk)) == 0) { note.append("SetThreadDesktop failed; "); return@Thread }
            answer = body()
        }.apply { isDaemon = true }
        val presser = Thread {
            if (fn("SetThreadDesktop").invokeInt(arrayOf(desk)) == 0) { note.append("presser SetThreadDesktop failed; "); return@Thread }
            val until = System.currentTimeMillis() + 20_000
            while (shower.isAlive && System.currentTimeMillis() < until) {
                val h = fn("FindWindowW").invokePointer(arrayOf(null, com.sun.jna.WString(title)))
                if (h == null) { Thread.sleep(100); continue }
                Thread.sleep(800)   // let it finish opening its folder
                if (press == 1) fn("PostMessageW").invokeInt(arrayOf(h, 0x0111, 1L, 0L)) else fn("PostMessageW").invokeInt(arrayOf(h, 0x0010, 0L, 0L))
                note.append(if (press == 1) "OK posted; " else "Close posted; ")
                shower.join(4000)
                // an OK the dialog did not take would leave it open: close it, so the check ends
                if (shower.isAlive) { fn("PostMessageW").invokeInt(arrayOf(h, 0x0010, 0L, 0L)); note.append("still open, closed; ") }
                break
            }
        }.apply { isDaemon = true }
        shower.start(); presser.start()
        shower.join(30_000); presser.join(2_000)
        if (shower.isAlive) note.append("the dialog never returned; ")
        else fn("CloseDesktop").invokeInt(arrayOf(desk))
        return answer to note.toString().trim()
    }

    /** Draws the app's file window at [wDp] x [hDp] while [ask] waits on it, then cancels it. */
    private fun shot(sb: StringBuilder, file: File, wDp: Int, hDp: Int, density: Float, touch: Boolean, ask: suspend () -> Any?) {
        val before = Platform.touchFirst
        Platform.touchFirst = touch
        val scene = ImageComposeScene((wDp * density).toInt(), (hDp * density).toInt(), Density(density)) {
            CompositionLocalProvider(LocalConfiguration provides Configuration(wDp, hDp)) {
                BmsTheme { Box(Modifier.fillMaxSize().background(Hud.Bg)) { PcFileWindowHost() } }
            }
        }
        try {
            var t = 0L
            fun frames(n: Int) = repeat(n) { Snapshot.sendApplyNotifications(); scene.render(t); t += 50_000_000; Thread.sleep(40) }
            frames(3)
            val job = CoroutineScope(Dispatchers.Default).launch { ask() }
            frames(50)
            Snapshot.sendApplyNotifications()
            file.writeBytes(scene.render(t).encodeToData(EncodedImageFormat.PNG)!!.bytes)
            sb.appendLine((if (file.length() > 5000) "ok   " else "FAIL ") + "picture ${file.name} (${wDp}x$hDp dp at $density)  [${file.length()} bytes]")
            job.cancel()
            frames(2)
        } catch (e: Throwable) {
            sb.appendLine("FAIL picture ${file.name}  [${e::class.simpleName}: ${e.message}]")
        } finally {
            scene.close()
            Platform.touchFirst = before
        }
    }
}
