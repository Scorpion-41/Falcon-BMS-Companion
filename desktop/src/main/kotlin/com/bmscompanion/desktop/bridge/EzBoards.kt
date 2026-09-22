package com.bmscompanion.desktop.bridge

import com.bmscompanion.app.data.mission.Board
import com.bmscompanion.app.data.mission.BoardRow
import com.bmscompanion.app.data.mission.BoardTable
import com.bmscompanion.app.data.mission.EzRun
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Runs the EZBoards tool (by "Logic", shipped in <BMS>\Tools\EZBoards) without a console window.
 * EZBOARDS.BAT skips its PAUSE when given any argument and ends with "SUCCESS." / "### ERROR ###", but a pilot may
 * have rewritten it: the exit code decides whether a run worked, and those two lines only colour the message.
 */
class EzBoardsRunner {
    private val busy = AtomicBoolean(false)
    val running: Boolean get() = busy.get()
    @Volatile var lastRun: EzRun? = null; private set
    var onCompleted: ((EzRun) -> Unit)? = null

    fun generate(dir: String?, auto: Boolean): EzRun {
        val time = System.currentTimeMillis()
        if (!isValidDir(dir)) return finish(EzRun(time = time, auto = auto, message = "EZBoards folder not set (EZBOARDS.BAT not found). Choose it in the settings."))
        if (!busy.compareAndSet(false, true)) return EzRun(time = time, auto = auto, message = "EZBoards is already running.")
        val started = System.nanoTime()
        var result: EzRun
        try {
            val bat = File(dir!!, "EZBOARDS.BAT")
            // full path + any argument: EZBOARDS.BAT skips its PAUSE when it gets a parameter
            val pb = ProcessBuilder(System.getenv("ComSpec") ?: "cmd.exe", "/d", "/s", "/c", "\"\"${bat.path}\" companion\"")
                .directory(File(dir))
                .redirectErrorStream(true)
            // the .BAT calls CONFIG.BAT, bin\xbrief.exe etc. relative to its folder; make sure cmd looks there
            pb.environment().remove("NoDefaultCurrentDirectoryInExePath")
            val p = pb.start()
            p.outputStream.close() // any stray PAUSE returns immediately
            val log = ArrayList<String>()
            val reader = thread(isDaemon = true) {
                runCatching { p.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { synchronized(log) { log += clean(it) } } }
            }
            if (!p.waitFor(90, TimeUnit.SECONDS)) {
                runCatching { p.descendants().forEach { it.destroyForcibly() }; p.destroyForcibly() }
                synchronized(log) { log += "Timed out after 90 s." }
            }
            reader.join(2000)
            val lines = synchronized(log) { log.filter { it.isNotEmpty() && !progressLine.matches(it) } }
            // What counts as having worked is what cmd says: the batch finished and returned 0. The stock
            // EZBOARDS.BAT also prints "SUCCESS." or "### ERROR ###", but pilots edit that file — one who had
            // replaced it with his own was told every run had failed while the boards were being written
            // perfectly well. The stock lines are now only read for what to say, never for the verdict.
            val ended = !p.isAlive && runCatching { p.exitValue() }.getOrDefault(-1) == 0
            val complained = lines.any { "### ERROR ###" in it }
            val success = ended && !complained
            result = EzRun(
                time = time, auto = auto, ok = success, log = lines.takeLast(40),
                message = when {
                    success -> "Kneeboards generated."
                    !ended -> "EZBoards did not finish (see log)."
                    else -> lines.lastOrNull { "Could not find" in it || "ERROR" in it || it.contains("error", ignoreCase = true) } ?: "EZBoards failed (see log)."
                },
            )
        } catch (e: Exception) {
            result = EzRun(time = time, auto = auto, message = "Could not start EZBoards: ${e.message}")
        } finally {
            busy.set(false)
        }
        return finish(result.copy(durationMs = (System.nanoTime() - started) / 1_000_000))
    }

    private fun finish(r: EzRun): EzRun {
        lastRun = r
        BridgeLog.info("EZBoards: ${if (r.ok) "OK" else "FAILED"} - ${r.message} (${r.durationMs} ms)")
        onCompleted?.invoke(r)
        return r
    }

    companion object {
        fun isValidDir(dir: String?) = !dir.isNullOrBlank() && File(dir, "EZBOARDS.BAT").isFile

        // wkhtmltoimage progress bars ("[=====>   ] 25%") only clutter the log shown in the app
        private val progressLine = Regex("^\\s*\\[[=> ]*]\\s*\\d+%$")
        private val ansi = Regex("\\[[0-9;]*[A-Za-z]")
        private fun clean(s: String) = ansi.replace(s, "").trimEnd()

        /**
         * Runs EZBoards' xbrief.exe directly (read only: output goes to stdout, nothing is written) to get every board section,
         * including target steerpoints and min-fuel columns, then parses the HTML tables into data.
         */
        fun readBoard(dir: String?, briefingTxt: String, callsignIni: String?, format: String = "pcstw"): Board? {
            if (!isValidDir(dir)) return null
            val exe = File(dir!!, "bin\\xbrief.exe")
            if (!exe.isFile || !File(briefingTxt).isFile) return null
            val args = arrayListOf(exe.path, "--format", format)
            if (File(dir, "Comments.txt").isFile) args += listOf("--comments", "Comments.txt")
            args += briefingTxt
            if (callsignIni != null && File(callsignIni).isFile) args += callsignIni
            return try {
                val p = ProcessBuilder(args).directory(File(dir)).redirectError(ProcessBuilder.Redirect.DISCARD).start()
                p.outputStream.close()
                var html = ""
                val reader = thread(isDaemon = true) { html = runCatching { p.inputStream.readBytes().toString(Charsets.UTF_8) }.getOrDefault("") }
                if (!p.waitFor(20, TimeUnit.SECONDS)) { p.destroyForcibly(); return null }
                reader.join(2000)
                if (p.exitValue() != 0) null else parseHtml(html).copy(format = format, time = System.currentTimeMillis())
            } catch (e: Exception) {
                BridgeLog.warn("xbrief failed: ${e.message}")
                null
            }
        }

        fun parseHtml(html: String): Board {
            val tables = ArrayList<BoardTable>()
            val opts = setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
            for (t in Regex("<table[^>]*>(.*?)</table>", opts).findAll(html)) {
                var title = ""
                var header = emptyList<String>()
                val rows = ArrayList<BoardRow>()
                for (tr in Regex("<tr([^>]*)>(.*?)</tr>", opts).findAll(t.groupValues[1])) {
                    val attrs = tr.groupValues[1]
                    val inner = tr.groupValues[2]
                    val titleCell = titleRegex.find(inner)
                    if (titleCell != null) { title = text(titleCell.groupValues[1]); continue }
                    val cls = Regex("class=\"([^\"]*)\"").find(attrs)?.groupValues?.get(1).orEmpty()
                    val ths = Regex("<th[^>]*>(.*?)</th>", RegexOption.DOT_MATCHES_ALL).findAll(inner).toList()
                    if (cls == "header" || (ths.isNotEmpty() && "<td" !in inner)) {
                        header = ths.map { text(it.groupValues[1]).trimEnd(':') }
                        continue
                    }
                    val cells = Regex("<td[^>]*>(.*?)</td>", RegexOption.DOT_MATCHES_ALL).findAll(inner).map { text(it.groupValues[1]) }.toList()
                    if (cells.isNotEmpty()) rows += BoardRow(cls.ifEmpty { null }, cells)
                }
                if (title.isNotEmpty() || rows.isNotEmpty()) tables += BoardTable(title, header, rows)
            }
            return Board(tables = tables)
        }

        private val titleRegex = Regex("<th[^>]*class=\"(?:title|stamp)\"[^>]*>(.*?)</th>", RegexOption.DOT_MATCHES_ALL)

        private fun text(html: String) = htmlDecode(html.replace(Regex("<[^>]+>"), " ")).replace(' ', ' ').trim()

        private fun htmlDecode(s: String): String = Regex("&(#x?[0-9a-fA-F]+|[a-zA-Z]+);").replace(s) { m ->
            val e = m.groupValues[1]
            when {
                e.startsWith("#x") || e.startsWith("#X") -> e.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value
                e.startsWith("#") -> e.substring(1).toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value
                else -> when (e) { "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; "apos" -> "'"; "nbsp" -> " "; "deg" -> "°"; else -> m.value }
            }
        }
    }
}
