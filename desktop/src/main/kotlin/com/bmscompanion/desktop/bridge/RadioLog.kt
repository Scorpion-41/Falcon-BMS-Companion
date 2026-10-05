package com.bmscompanion.desktop.bridge

import com.bmscompanion.app.data.mission.RadioCalls
import com.bmscompanion.app.data.mission.RadioCategory
import com.bmscompanion.app.data.mission.RadioContext
import com.bmscompanion.app.data.mission.RadioLogCleanup
import com.bmscompanion.app.data.mission.RadioLogStatus
import com.bmscompanion.app.data.mission.RadioMessage
import com.bmscompanion.app.data.mission.RadioTaxi
import com.bmscompanion.app.data.mission.RadioThread
import com.bmscompanion.app.data.mission.ownFlight
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The radio, from Falcon BMS's debug log.
 *
 * With debug mode on (BMS Launcher or the Alternative Launcher), BMS writes `User\Logs\<YYYY-MM-DD_HHMMSS>_xlog.txt`
 * (another folder when `g_sLogsDirectory` says so) and keeps adding to it while it runs. Every radio subtitle it puts on
 * screen is a line of it, `[hh:mm:ss.mmm] <thread> Subtitle: <text>` (`RadioSubTitle::MonoPrintCurrentSubTitle`), and
 * only while **Display Radio Subtitles** is ticked (BMS: SETUP → SIMULATION; kept per pilot in `<callsign>.pop`). BMS's
 * own `RadioSubtitles-*.txt` (`g_bWindowedRadioSubtitles`, "Save on exit") is written only on exit, too late to use.
 *
 * Read only: the file is opened for a moment, shared with BMS for reading, writing and deleting, read from where the
 * last read stopped, and closed — never locked, never written. Polled once a second and only while BMS runs. Without a
 * log nothing here does anything, and every other page works as before.
 *
 * The one write is [cleanup], off unless the pilot turns it on: BMS's own old debug logs to the Recycle Bin (a writer of
 * the ground rules, guarded by [DevGuard]).
 */
object RadioLog {
    const val CAP = 2000

    /** `2026-10-05_161445_xlog.txt` */
    private val XLOG = Regex("""^(\d{4}-\d{2}-\d{2}_\d{6})_xlog\.txt$""", RegexOption.IGNORE_CASE)
    /** `2026-10-05_161445_xlog_<anything>.csv`: the packet files of the same session */
    private val XLOG_CSV = Regex("""^(\d{4}-\d{2}-\d{2}_\d{6})_xlog_.*\.csv$""", RegexOption.IGNORE_CASE)
    private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss")

    val reader = RadioLogReader({ context() })

    @Volatile private var bmsWasRunning = false
    @Volatile private var bmsSince = 0L
    @Volatile private var flyingSince = 0L
    /** this run of BMS has a log (the last look at the folder found one written since it started, or lately) */
    @Volatile private var currentOk = false
    private var scanAt = 0L
    /** the newest log in the folder, looked at every few seconds (and when BMS is not running, for the status) */
    @Volatile private var newestSeen: File? = null
    private var newestAt = 0L

    // ------------------------------------------------------------------ where

    /** BMS's logs folder: `g_sLogsDirectory` when it names a folder, else `<BMS>\User\Logs`. */
    fun logsDir(install: BmsInstall): File? = runCatching {
        install.cfgValue("g_sLogsDirectory")?.trim()?.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.isDirectory }
            ?: install.baseDir?.let { File(it, "User\\Logs") }?.takeIf { it.isDirectory }
    }.getOrNull()

    /** The newest `*_xlog.txt` of [dir] (the names start with BMS's start time), or null. */
    fun newestLog(dir: File?): File? = runCatching {
        dir?.listFiles { f -> f.isFile && XLOG.matches(f.name) }?.maxWithOrNull(compareBy<File>({ it.name.lowercase() }, { it.lastModified() }))
    }.getOrNull()

    // ------------------------------------------------------------------ the tick

    /**
     * Once a second from the bridge's tick. Nothing at all while BMS is not running; while it runs, the folder is looked
     * at every five seconds for a new session (BMS restarted) and the current log is read from where it stopped.
     */
    fun tick(install: BmsInstall, flying: Boolean) {
        runCatching {
            val now = System.currentTimeMillis()
            val running = BmsInstall.isBmsRunning()
            if (running && !bmsWasRunning) bmsSince = now
            bmsWasRunning = running
            flyingSince = if (flying) (if (flyingSince == 0L) now else flyingSince) else 0L
            if (!running) return
            if (now - scanAt > 5000) {
                scanAt = now
                val newest = newestLog(logsDir(install))
                newestSeen = newest; newestAt = now
                // the log of this run of BMS: written since BMS was seen starting, or lately
                val current = newest?.takeIf { it.lastModified() >= bmsSince - 120_000 || now - it.lastModified() < 30 * 60_000L }
                currentOk = current != null
                if (current != null && current != reader.file) {
                    reader.follow(current)
                    BridgeLog.info("Radio: reading BMS's debug log ${current.name}")
                    // a new session: BMS's older logs go, when the pilot asked for that
                    if (Bridge.settings.value.RadioLogCleanup) Thread({ cleanup(install, manual = false) }, "radio-log-cleanup").apply { isDaemon = true }.start()
                }
            }
            reader.poll()
        }.onFailure { BridgeLog.warn("Radio log: ${it.message}") }
    }

    /** At program start: the clean-up, when it is on. */
    fun startup(install: BmsInstall) {
        if (!Bridge.settings.value.RadioLogCleanup) return
        Thread({ cleanup(install, manual = false) }, "radio-log-cleanup").apply { isDaemon = true }.start()
    }

    // ------------------------------------------------------------------ what the mission says

    private var ctxAt = 0L
    private var ctxCached: RadioContext? = null
    private var bmsWords: Pair<String?, Pair<Set<String>, List<String>>>? = null

    /**
     * Who is who on the radio, from what the PC knows: the pilot's own flight and seat (BMS's live voice helpers, else
     * the briefing's flight; the seat from the roster), the package's flights, the AWACS and tankers the briefing and the
     * sim name, every callsign BMS voices and the fields its controllers are named by (its own `F4Talk.csv`). Refreshed
     * every ten seconds.
     */
    fun context(): RadioContext = synchronized(this) {
        val now = System.currentTimeMillis()
        ctxCached?.takeIf { now - ctxAt < 10_000 }?.let { return it }
        val built = runCatching { buildContext() }.getOrElse { RadioContext() }
        ctxCached = built; ctxAt = now
        built
    }

    private fun buildContext(): RadioContext {
        val install = Bridge.install
        val voice = runCatching { Bridge.snapshot().live.voice }.getOrNull()
        val briefing = runCatching { if (MissionSource.wdp) MissionSource.mission().briefing else Bridge.currentBriefing() }.getOrNull()
        val own = RadioCalls.flightOf(voice?.flight) ?: RadioCalls.flightOf(briefing?.overview?.flight)
            ?: RadioCalls.flightOf(briefing?.ownFlight()?.callsign)
        val seat = runCatching {
            val pilot = install.callsign?.trim()?.lowercase()
            val row = briefing?.roster?.firstOrNull { r -> RadioCalls.flightOf(r.callsign)?.let { it.first == own?.first && it.second == own.second } == true }
            row?.pilots?.indexOfFirst { it.trim().lowercase() == pilot }?.takeIf { it >= 0 }?.plus(1)
        }.getOrNull()
        val support = briefing?.support.orEmpty()
        fun named(role: String, extra: String?) = (support.filter { (it.role ?: "").contains(role, true) || (it.notes ?: "").contains(role, true) }
            .mapNotNull { RadioCalls.flightOf(it.callsign)?.first } + listOfNotNull(RadioCalls.flightOf(extra)?.first)).toSet()
        val awacs = RadioCalls.DEFAULT_AWACS + named("AWACS", voice?.awacs) + named("AEW", null)
        val tankers = RadioCalls.DEFAULT_TANKERS + named("TANKER", voice?.tanker) + named("REFUEL", null)
        val flights = briefing?.`package`.orEmpty().mapNotNull { RadioCalls.flightOf(it.callsign)?.first }.toSet()
        val (callsigns, fields) = bmsWords(install)
        return RadioContext(
            ownName = own?.first, ownFlight = own?.second, ownSeat = seat ?: own?.third,
            awacs = awacs, tankers = tankers - awacs, flights = flights, callsigns = callsigns, fields = fields,
        )
    }

    /** The callsigns BMS voices and the fields it names controllers by, out of `Data\Sounds` (read once per install). */
    private fun bmsWords(install: BmsInstall): Pair<Set<String>, List<String>> {
        val base = install.baseDir
        bmsWords?.takeIf { it.first == base }?.let { return it.second }
        val words = runCatching { readBmsWords(base?.let { File(it, "Data\\Sounds") }) }.getOrElse { emptySet<String>() to emptyList() }
        bmsWords = base to words
        return words
    }

    /**
     * From BMS's own radio data: the texts of the fragments `EvalFile.xml` lists for its callsign evaluations (15, 86,
     * 164) and its controllers' names (81 and 87, the ones without Approach/Tower), in `F4Talk.csv` (every voice).
     */
    internal fun readBmsWords(sounds: File?): Pair<Set<String>, List<String>> {
        if (sounds == null || !sounds.isDirectory) return emptySet<String>() to emptyList()
        val eval = File(sounds, "EvalFile.xml").takeIf { it.isFile }?.readText(Charsets.UTF_8) ?: return emptySet<String>() to emptyList()
        val talk = File(sounds, "F4Talk.csv").takeIf { it.isFile }?.readText(Charsets.UTF_8) ?: return emptySet<String>() to emptyList()
        val frags = HashMap<Int, List<String>>()
        for (row in csvRows(talk).drop(1)) {
            val id = row.getOrNull(0)?.trim()?.toIntOrNull() ?: continue
            frags[id] = row.subList(2, minOf(row.size, 17)).map { it.trim() }.filter { it.isNotEmpty() }
        }
        fun fragsOf(evalId: Int): List<Int> =
            Regex("""<Eval id="$evalId">([\s\S]*?)</Eval>""").find(eval)?.groupValues?.get(1)
                ?.let { body -> Regex("""fragId="(\d+)"""").findAll(body).map { it.groupValues[1].toInt() }.toList() }.orEmpty()
        val callsigns = listOf(15, 86, 164).flatMap(::fragsOf).flatMap { frags[it].orEmpty() }
            .map { it.trim().lowercase() }.filter { it.isNotEmpty() && it.none(Char::isDigit) && ' ' !in it }.toSet()
        val suffix = Regex("""\s+(approach|tower|ground|departure|arrival)$""", RegexOption.IGNORE_CASE)
        val fields = listOf(81, 87).flatMap(::fragsOf).flatMap { frags[it].orEmpty() }.map { it.trim().replace(Regex("""\s+"""), " ") }
            .filter { it.isNotEmpty() && !suffix.containsMatchIn(it) && it.none(Char::isDigit) }
            .distinctBy { it.lowercase() }.sortedByDescending { it.split(' ').size }
        return callsigns to fields
    }

    private fun csvRows(t: String): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>(); val f = StringBuilder(); var q = false
        var i = 0
        while (i < t.length) {
            val c = t[i]
            if (q) { if (c == '"') { if (i + 1 < t.length && t[i + 1] == '"') { f.append('"'); i++ } else q = false } else f.append(c) }
            else when (c) {
                '"' -> q = true
                ',' -> { row.add(f.toString()); f.setLength(0) }
                '\n' -> { row.add(f.toString().trimEnd('\r')); f.setLength(0); rows.add(row); row = ArrayList() }
                else -> f.append(c)
            }
            i++
        }
        if (f.isNotEmpty() || row.isNotEmpty()) { row.add(f.toString()); rows.add(row) }
        return rows
    }

    // ------------------------------------------------------------------ what is served

    /** The status the Setup pages and the Radio page show (`BridgeInfo.radio`). Never throws. */
    fun status(install: BmsInstall): RadioLogStatus = runCatching {
        val now = System.currentTimeMillis()
        val running = BmsInstall.isBmsRunning()
        val f = reader.file
        val cleanup = cleanupState(install)
        if (!running) {
            if (now - newestAt > 30_000) { newestSeen = newestLog(logsDir(install)); newestAt = now }
            val last = newestSeen
            return@runCatching if (last == null) RadioLogStatus(
                RadioLogStatus.OFF, note = OFF_NOTE, cleanup = cleanup,
            ) else RadioLogStatus(
                RadioLogStatus.WAITING, file = last.name, lines = if (f == last) reader.lines else 0, lastLineAt = reader.lastLineAt,
                note = "Falcon BMS is not running. Its last debug log is ${last.name}" +
                    (if (f == last && reader.lines > 0) " — the calls below are from that session." else "."),
                cleanup = cleanup,
            )
        }
        when {
            f == null || !currentOk -> RadioLogStatus(RadioLogStatus.OFF, bmsRunning = true, note = "Falcon BMS is running without a debug log: debug mode is off. $OFF_NOTE", cleanup = cleanup)
            reader.lines > 0 -> RadioLogStatus(RadioLogStatus.LIVE, f.name, reader.lines, reader.lastLineAt, true, cleanup = cleanup)
            else -> RadioLogStatus(
                RadioLogStatus.WAITING, f.name, 0, 0, true,
                note = if (flyingSince != 0L && now - flyingSince > 120_000) "No radio lines yet — check Display Radio Subtitles is on (BMS: SETUP → SIMULATION)."
                else "Debug log found; no radio lines yet. They come in 3D, with Display Radio Subtitles on (SETUP → SIMULATION).",
                cleanup = cleanup,
            )
        }
    }.getOrElse { RadioLogStatus(note = "The debug log could not be read: ${it.message}") }

    const val OFF_NOTE = "The radio log and the automatic taxi need BMS's debug mode (BMS Launcher or Alternative Launcher, " +
        "its debug option), which writes User\\Logs\\…_xlog.txt, and Display Radio Subtitles ticked (SETUP → SIMULATION). " +
        "Without them these stay off and everything else works as before."

    /** `GET /api/radio?since=`, `POST /api/radio/cleanup?on=&keep=&days=` (the settings) and `/api/radio/cleanup/now`. */
    fun handle(req: ApiRequest): ApiResponse {
        val install = Bridge.install
        val path = req.path.trimEnd('/')
        val json = Bridge.json
        return when (req.method to path) {
            "GET" to "/api/radio" -> {
                val since = req.query["since"]?.toLongOrNull() ?: 0L
                val (session, msgs, last) = reader.since(since)
                ApiResponse.json(
                    json.encodeToString(
                        com.bmscompanion.app.data.mission.RadioLog.serializer(),
                        com.bmscompanion.app.data.mission.RadioLog(status(install), session, msgs, last, reader.context?.own, taxiNow()),
                    ),
                )
            }
            "POST" to "/api/radio/cleanup" -> {
                val q = req.query
                Bridge.update(reapply = false) { s ->
                    s.copy(
                        RadioLogCleanup = q["on"]?.let { it == "1" || it.equals("true", true) } ?: s.RadioLogCleanup,
                        RadioLogKeep = q["keep"]?.toIntOrNull()?.coerceIn(1, 365) ?: s.RadioLogKeep,
                        RadioLogKeepDays = q["days"]?.let { it == "1" || it.equals("true", true) } ?: s.RadioLogKeepDays,
                    )
                }
                ApiResponse.json(json.encodeToString(RadioLogStatus.serializer(), status(install)))
            }
            "POST" to "/api/radio/cleanup/now" -> {
                cleanup(install, manual = true)
                ApiResponse.json(json.encodeToString(RadioLogStatus.serializer(), status(install)))
            }
            else -> ApiResponse.notFound()
        }
    }

    /** The taxi cue to serve: this session's last, for the mission it was heard in, within three hours. */
    fun taxiNow(): RadioTaxi? {
        val c = reader.taxi ?: return null
        if (System.currentTimeMillis() - c.at > 3 * 3600_000L) return null
        val key = runCatching { Bridge.missionKeyNow() }.getOrNull()
        return c.takeIf { reader.taxiMission == null || reader.taxiMission == key }
    }

    // ------------------------------------------------------------------ deleting old logs

    @Volatile private var lastRun = 0L
    @Volatile private var lastDeleted = 0
    @Volatile private var lastBytes = 0L
    @Volatile private var lastError: String? = null
    private var planAt = 0L
    private var planCached: Pair<List<File>, Long>? = null

    /**
     * Which files a clean-up would take: BMS's own debug logs of the sessions beyond the [keep] newest (or older than
     * [keep] days), each session's `_xlog.txt` and its `_xlog_*.csv` packet files — never the newest session's, never
     * [protect]'s (the log being read), never one written in the last two minutes, and nothing else in the folder (crash
     * dumps, screenshots, BMS's other files).
     */
    fun plan(dir: File?, keep: Int, byDays: Boolean, now: Long, protect: File? = null): List<File> {
        val files = runCatching { dir?.listFiles()?.filter { it.isFile }.orEmpty() }.getOrDefault(emptyList())
        val bySession = files.mapNotNull { f -> (XLOG.find(f.name) ?: XLOG_CSV.find(f.name))?.groupValues?.get(1)?.let { it to f } }
            .groupBy({ it.first }, { it.second })
        // a session is one that has its log (packet files alone are kept with nothing to tie them to)
        val sessions = bySession.keys.filter { s -> bySession[s].orEmpty().any { XLOG.matches(it.name) } }.sortedDescending()
        if (sessions.isEmpty()) return emptyList()
        val newest = sessions.first()
        val guarded = setOfNotNull(newest, protect?.name?.let { XLOG.find(it)?.groupValues?.get(1) })
        val old = if (byDays) {
            val cut = now - keep.coerceAtLeast(1) * 86_400_000L
            sessions.filter { s ->
                val t = runCatching { LocalDateTime.parse(s, STAMP).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull()
                    ?: bySession[s].orEmpty().maxOf { it.lastModified() }
                t < cut
            }
        } else sessions.drop(keep.coerceAtLeast(1))
        return old.filter { it !in guarded }.flatMap { bySession[it].orEmpty() }.filter { now - it.lastModified() > 120_000 }
    }

    private fun cleanupState(install: BmsInstall): RadioLogCleanup {
        val s = Bridge.settings.value
        val now = System.currentTimeMillis()
        val p = synchronized(this) {
            planCached?.takeIf { now - planAt < 10_000 } ?: run {
                val files = plan(logsDir(install), s.RadioLogKeep, s.RadioLogKeepDays, now, reader.file)
                (files to files.sumOf { it.length() }).also { planCached = it; planAt = now }
            }
        }
        return RadioLogCleanup(
            on = s.RadioLogCleanup, keep = s.RadioLogKeep, byDays = s.RadioLogKeepDays, files = p.first.size, bytes = p.second,
            lastRun = lastRun, lastDeleted = lastDeleted, lastBytes = lastBytes, error = lastError,
        )
    }

    /**
     * Moves the old debug logs [plan] picks to the Recycle Bin. Refused in a developer run unless the folder is a copy
     * ([DevGuard]). Never throws: the reason is kept for the page ([RadioLogCleanup.error]).
     */
    fun cleanup(install: BmsInstall, manual: Boolean): RadioLogCleanup = synchronized(cleanupLock) {
        val now = System.currentTimeMillis()
        try {
            val s = Bridge.settings.value
            val dir = logsDir(install)
            if (dir == null) { lastError = "BMS's logs folder was not found."; return cleanupState(install) }
            val r = cleanupDir(dir, s.RadioLogKeep, s.RadioLogKeepDays, install.baseDir, reader.file, now)
            lastRun = now; lastDeleted = r.deleted; lastBytes = r.bytes; lastError = r.error
            if (r.deleted > 0 || manual) BridgeLog.info("Radio log clean-up: ${r.deleted} old debug log file(s) moved to the Recycle Bin")
        } catch (e: Throwable) {
            lastError = "The old logs could not be deleted: ${e.message ?: e::class.java.simpleName}"
        }
        synchronized(this) { planCached = null }
        cleanupState(install)
    }

    private val cleanupLock = Any()

    class Cleaned(val deleted: Int, val bytes: Long, val error: String?)

    /**
     * The clean-up itself on one folder: [plan]'s files to the Recycle Bin, after [DevGuard] (a developer run writes
     * only a copy). Never throws; [Cleaned.error] says why not, in a sentence.
     */
    fun cleanupDir(dir: File, keep: Int, byDays: Boolean, baseDir: String?, protect: File?, now: Long = System.currentTimeMillis()): Cleaned = try {
        val refused = DevGuard.refusal(dir.path, baseDir)
        if (refused != null) Cleaned(0, 0, refused)
        else {
            val files = plan(dir, keep, byDays, now, protect)
            val sizes = files.associateWith { it.length() }
            val gone = if (files.isEmpty()) 0 else AcmiStore.recycle(files)
            Cleaned(
                gone, sizes.filterKeys { !it.exists() }.values.sum(),
                if (gone < files.size) "${files.size - gone} of ${files.size} old log files could not be moved to the Recycle Bin." else null,
            )
        }
    } catch (e: Throwable) {
        Cleaned(0, 0, "The old logs could not be deleted: ${e.message ?: e::class.java.simpleName}")
    }
}

/**
 * Follows one debug log: reads what BMS added since the last read, joins its subtitle lines into calls ([RadioThread])
 * and files each ([RadioCalls]). One per session; [follow] another file starts afresh.
 */
class RadioLogReader(private val contextOf: () -> RadioContext) {
    @Volatile var file: File? = null; private set
    private var pos = 0L
    private var carry = ByteArray(0)
    private val thread = RadioThread(RadioLog.CAP)
    @Volatile var lines = 0; private set
    @Volatile var lastLineAt = 0L; private set
    /** bumped when the context changed and every call was filed again: the session's name changes with it */
    private var generation = 0
    @Volatile var context: RadioContext? = null; private set
    @Volatile var taxi: RadioTaxi? = null; private set
    /** the mission key ([Bridge.missionKeyNow]) [taxi] was heard in */
    @Volatile var taxiMission: String? = null; private set
    /** the runway the last landing clearance to the pilot's flight named */
    private var landed: String? = null
    /** how [taxiMission] is found (a check sets it to null) */
    var missionKey: (() -> String?)? = { runCatching { Bridge.missionKeyNow() }.getOrNull() }

    val session: String get() = file?.name?.let { "$it#$generation" } ?: ""

    @Synchronized fun follow(f: File?) {
        file = f; pos = 0; carry = ByteArray(0); thread.clear(); lines = 0; lastLineAt = 0; taxi = null; taxiMission = null; landed = null
        generation++
    }

    /** This session's calls after [since], the session's name and the last number. */
    @Synchronized fun since(since: Long): Triple<String, List<RadioMessage>, Long> =
        Triple(session, thread.messages.filter { it.seq > since }, thread.seq)

    /** Every call kept, oldest first. */
    @Synchronized fun all(): List<RadioMessage> = thread.messages.toList()

    /**
     * Reads what was added since the last read and answers how many subtitle lines it held. A file that got shorter was
     * replaced: it is read again from the start. A last line without its end is kept for the next read. Never throws.
     */
    @Synchronized fun poll(): Int = runCatching {
        val f = file ?: return 0
        val ctx = contextOf()
        if (ctx != context) refile(ctx)
        val p = f.toPath()
        val size = runCatching { Files.size(p) }.getOrElse { return 0 }
        if (size < pos) { val keep = f; follow(keep) }
        if (size <= pos) return 0
        var added = 0
        while (pos < size) {
            val want = minOf(size - pos, 4L shl 20).toInt()
            val buf = java.nio.ByteBuffer.allocate(want)
            // shared with BMS for reading, writing and deleting (NIO's default on Windows), open for this read only
            Files.newByteChannel(p, StandardOpenOption.READ).use { ch ->
                ch.position(pos)
                while (buf.hasRemaining()) { if (ch.read(buf) <= 0) break }
            }
            val got = buf.position()
            if (got <= 0) break
            pos += got
            val data = carry + buf.array().copyOf(got)
            val nl = data.lastIndexOf('\n'.code.toByte())
            if (nl < 0) { carry = if (data.size > 1 shl 16) ByteArray(0) else data; continue }
            carry = data.copyOfRange(nl + 1, data.size)
            added += feed(String(data, 0, nl + 1, CP1252), ctx)
        }
        added
    }.getOrDefault(0)

    /** Files the subtitle lines of [text] (whole lines of the log). Answers how many there were. */
    @Synchronized fun feed(text: String, ctx: RadioContext = context ?: contextOf().also { context = it }): Int {
        var n = 0
        for (raw in text.split('\n')) {
            if (!raw.contains("Subtitle:")) continue
            val line = RadioCalls.logLine(raw) ?: continue
            n++
            lines++
            lastLineAt = System.currentTimeMillis()
            val (msg, isNew) = thread.add(line, ctx) { seq, p -> file(seq, line, p, ctx) }
            if (!isNew) continue
            val parsed = RadioCalls.parse(line.text, ctx)
            val atc = parsed.atc
            if (atc?.kind == RadioTaxi.LANDING && RadioCalls.isOwn(parsed.to, ctx) && atc.runway != null) landed = atc.runway
            RadioCalls.taxiCue(msg.seq, lastLineAt, line.time, line.text, parsed, ctx, landed)?.let {
                taxi = it
                taxiMission = missionKey?.invoke()
            }
        }
        return n
    }

    private fun file(seq: Long, line: RadioCalls.LogLine, p: RadioCalls.Parsed, ctx: RadioContext) = RadioMessage(
        seq = seq, time = line.time, text = line.text, category = RadioCalls.categoryOf(p, ctx),
        from = p.from?.text, to = p.to?.text, atc = p.atc,
        mine = RadioCalls.isOwn(p.to, ctx) || RadioCalls.isOwn(p.from, ctx),
    )

    /** A new picture of the mission (a PRINT, another flight): every call filed again, and the session renamed so devices fetch them all. */
    private fun refile(ctx: RadioContext) {
        val had = context
        context = ctx
        if (had == null || thread.messages.isEmpty()) return
        for (i in thread.messages.indices) {
            val m = thread.messages[i]
            val p = RadioCalls.parse(m.text.substringBefore('\n'), ctx)
            thread.messages[i] = m.copy(
                category = RadioCalls.categoryOf(p, ctx), from = p.from?.text, to = p.to?.text,
                mine = RadioCalls.isOwn(p.to, ctx) || RadioCalls.isOwn(p.from, ctx),
            )
        }
        generation++
    }

    companion object {
        private val CP1252 = charset("windows-1252")
        /** the six groups, for a check's report */
        val groups = RadioCategory.ALL
    }
}
