package com.bmscompanion.desktop.bridge

import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.airfield.Airfield
import com.bmscompanion.app.data.airfield.routeShown
import com.bmscompanion.app.data.mission.RadioCalls
import com.bmscompanion.app.data.mission.RadioCategory
import com.bmscompanion.app.data.mission.RadioContext
import com.bmscompanion.app.data.mission.RadioTaxi
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * `--radiotest out.txt <a copy of the BMS folder>`: the radio log, read only except in the copy's own scratch.
 *
 * 1. The real sample debug log in the copy's `User\Logs` (the newest `*_xlog.txt`, e.g. a copy of a flight's log):
 *    every `Subtitle:` line read, the AWACS picture's group lines joined to their call, each call filed.
 * 2. Calls built from `CommFile.xml`'s templates in the controllers' and the pilots' voices (call numbers in the
 *    lines), one or more for every group: ATC taxi out (284, 387), line up (306), takeoff (39), landing (38), vacate
 *    (305), taxi back hold short (504), park NN (517, with and without "park"), welcome back (391), a pilot's request
 *    (322), a broadcast (506), AWACS (375, 524), tanker (211), the pilot's own flight, other flights, other.
 * 3. A log read while it grows: a line written in two halves is read once, whole.
 * 4. A log replaced by a shorter one (BMS restarted with the same name): read again from the start.
 * 5. Debug mode off: a logs folder with no `*_xlog.txt` gives no log and no calls.
 * 6. The taxi automation's decisions on Gunsan's real chart: taxi to 36 picks runway 36; "park 0 0 4" after landing on
 *    36 picks spot 04 in the taxi-in network (runway 18's); a call to a wingman or another flight picks nothing.
 * 7. Deleting old logs, on a scratch folder inside the copy: keeps the newest N sessions, never the newest, never the
 *    log being read, never crash dumps, screenshots or other files; the packet `.csv` files go with their session.
 */
object RadioTest {
    fun run(copy: File): String = buildString {
        fun check(ok: Boolean, what: String) = appendLine((if (ok) "ok   " else "FAIL ") + what)
        appendLine("Radio log check — ${copy.path}")
        // it writes a scratch folder into the copy's User\Logs: never into the install itself
        DevGuard.why(copy)?.let { appendLine("FAIL not a copy of a BMS folder: $it"); return@buildString }
        val sounds = File(copy, "Data\\Sounds")
        val (callsigns, fields) = RadioLog.readBmsWords(sounds.takeIf { it.isDirectory })
        appendLine("BMS's voiced callsigns: ${callsigns.size}, controllers' fields: ${fields.size} (from ${if (sounds.isDirectory) sounds.path else "no Data\\Sounds in the copy"})")
        val ctx = RadioContext(ownName = "jaguar", ownFlight = 2, ownSeat = 1, flights = setOf("cyborg", "mako"), callsigns = callsigns, fields = fields)

        // ---- 1. the real sample
        appendLine(); appendLine("1. The sample debug log")
        val logs = File(copy, "User\\Logs")
        val sample = RadioLog.newestLog(logs)
        if (sample == null) appendLine("FAIL no *_xlog.txt in ${logs.path} (copy a flight's debug log there)")
        else {
            val r = RadioLogReader({ ctx }).apply { missionKey = null; follow(sample) }
            val lines = r.poll()
            val calls = r.all()
            appendLine("   ${sample.name}: $lines subtitle lines, ${calls.size} calls")
            for (m in calls) appendLine("   #${m.seq} ${m.time} [${m.category}${if (m.mine) ", mine" else ""}] from=${m.from} to=${m.to}: ${m.text.replace("\n", " | ")}")
            check(lines > 0, "subtitle lines read")
            check(calls.size < lines || lines == 0, "group lines joined to their call (${lines - calls.size} joined)")
            val picture = calls.firstOrNull { it.text.contains("picture is") }
            if (picture != null) check(picture.text.count { it == '\n' } >= 1 && picture.category == RadioCategory.AWACS, "the AWACS picture keeps its groups under it and is AWACS")
            calls.firstOrNull { it.text.startsWith("Cyborg 1 in") }?.let { check(it.category == RadioCategory.FLIGHTS, "\"Cyborg 1 in\" is another flight") }
            calls.firstOrNull { it.text.startsWith("Jaguar 2 engage") }?.let { check(it.category == RadioCategory.MINE, "\"Jaguar 2 engage targets\" is my flight") }
            calls.firstOrNull { it.text.startsWith("magic 5 Jaguar 2-1") }?.let { check(it.category == RadioCategory.AWACS && it.mine && it.to == "magic 5" && it.from == "Jaguar 2-1", "my request to AWACS: AWACS, mine, to magic 5 from Jaguar 2-1") }
        }

        // ---- 2. fixtures from CommFile's templates
        appendLine(); appendLine("2. Calls built from CommFile.xml's templates")
        data class Fx(val call: String, val text: String, val cat: String, val kind: String? = null, val runway: String? = null, val spot: Int? = null, val letters: List<String>? = null, val cue: String? = null)
        val fixtures = listOf(
            Fx("387", "Good morning Jaguar 2-1 you are number 1 for departure taxi Alpha Charlie and hold short runway 3-6", RadioCategory.ATC, RadioTaxi.OUT, "36", null, listOf("A", "C"), RadioTaxi.OUT),
            Fx("284", "Jaguar 2-1 taxi Bravo and hold short runway 1-8", RadioCategory.ATC, RadioTaxi.OUT, "18", null, listOf("B"), RadioTaxi.OUT),
            Fx("306", "Jaguar 2-1 Gunsan Tower position and hold runway 3-6", RadioCategory.ATC, RadioTaxi.LINEUP, "36", cue = RadioTaxi.LINEUP),
            Fx("39", "Jaguar 2-1 Gunsan Tower wind 3-6-0 at 5 knots runway 3-6 you are cleared for takeoff", RadioCategory.ATC, RadioTaxi.TAKEOFF, "36", cue = RadioTaxi.TAKEOFF),
            Fx("38", "Good morning Jaguar 2-1 Gunsan Tower wind 0-1-0 at 3 knots runway 3-6 cleared for landing check gear down", RadioCategory.ATC, RadioTaxi.LANDING, "36"),
            Fx("305", "Jaguar 2-1 Gunsan Tower taxi clear of the runway", RadioCategory.ATC, RadioTaxi.VACATE, cue = RadioTaxi.VACATE),
            Fx("504", "Jaguar 2-1 Taxi back Alpha and hold short runway 0-9 left", RadioCategory.ATC, RadioTaxi.BACK, "09L", letters = listOf("A"), cue = RadioTaxi.BACK),
            Fx("517", "Jaguar 2-1 Taxi back to the ramp Alpha Charlie park 0 0 4", RadioCategory.ATC, RadioTaxi.PARK, spot = 4, letters = listOf("A", "C"), cue = RadioTaxi.PARK),
            Fx("517c", "Jaguar 2-1 Taxi back to the ramp Alpha Charlie 0 2 8", RadioCategory.ATC, RadioTaxi.PARK, spot = 28, letters = listOf("A", "C"), cue = RadioTaxi.PARK),
            Fx("391", "Jaguar 2-1 Taxi to the ramp welcome back", RadioCategory.ATC, RadioTaxi.NOSPOT, cue = RadioTaxi.NOSPOT),
            Fx("517w", "Jaguar 2-2 Taxi back to the ramp Alpha 0 0 5", RadioCategory.ATC, RadioTaxi.PARK, spot = 5),
            Fx("284o", "Cyborg 1-1 taxi Alpha and hold short runway 3-6", RadioCategory.ATC, RadioTaxi.OUT, "36"),
            Fx("322", "Gunsan Tower Jaguar 2-1 4 ship F-16 Request taxi", RadioCategory.ATC, "atc"),
            Fx("506", "All flights Gunsan Tower be advised base is under attack divert to alternate field", RadioCategory.ATC, "atc"),
            Fx("308", "Jaguar 2-1 Gunsan Tower your mission has been scrubbed, taxi back to the ramp", RadioCategory.ATC, "atc"),
            Fx("375", "Jaguar 2-1 magic 5 picture is 1 group bullseye 1 3 7 / 1 3 0 13000 hot hostile", RadioCategory.AWACS),
            Fx("524", "Cyborg 1 magic 5 heads up new bandit bullseye 1 6 0 / 8 9 22000 contact ID'ed as MiG-23s", RadioCategory.AWACS),
            Fx("211", "Jaguar 2-1 Texaco 1 heads up, tanker is entering turn", RadioCategory.TANKER),
            Fx("flt", "Jaguar 2 engage targets BRAA 0 0 5 / 2 3 21000", RadioCategory.MINE),
            Fx("pkg", "Mako 4 in", RadioCategory.FLIGHTS),
            Fx("oth", "Southeast group bullseye 1 3 7 / 1 3 0 13000 hot hostile", RadioCategory.OTHER),
        )
        var landed: String? = null
        var seq = 0L
        for (fx in fixtures) {
            val p = RadioCalls.parse(fx.text, ctx)
            val cat = RadioCalls.categoryOf(p, ctx)
            if (p.atc?.kind == RadioTaxi.LANDING && RadioCalls.isOwn(p.to, ctx)) landed = p.atc?.runway
            val cue = RadioCalls.taxiCue(++seq, seq, "04:00:00", fx.text, p, ctx, landed)
            val ok = cat == fx.cat && (fx.kind == null || p.atc?.kind == fx.kind) && (fx.runway == null || p.atc?.runway == fx.runway) &&
                (fx.spot == null || p.atc?.spot == fx.spot) && (fx.letters == null || p.atc?.letters == fx.letters) && cue?.kind == fx.cue
            check(ok, "call ${fx.call}: \"${fx.text}\" → $cat, ${p.atc?.kind ?: "-"}, runway ${p.atc?.runway ?: "-"}, spot ${p.atc?.spot ?: "-"}, letters ${p.atc?.letters ?: "-"}, " +
                "to ${p.to?.text ?: "-"}, from ${p.from?.text ?: "-"}, cue ${cue?.kind ?: "none"}${cue?.runway?.let { " rwy $it" } ?: ""}")
        }
        check(landed == "36", "the landing clearance's runway is kept for the way in (36)")
        check(RadioCalls.runwayIn("runway 0-9 left") == "09L" && RadioCalls.runwayIn("Runway 1-8 Right") == "18R" && RadioCalls.runwayIn("runway 3-6") == "36", "runway words → designators")
        check(RadioCalls.designatorIn("36", listOf("18", "36")) == "36" && RadioCalls.designatorIn("36", listOf("36L", "36R")) == null &&
            RadioCalls.designatorIn("09L", listOf("09L", "09R", "27L", "27R")) == "09L", "a runway said → the chart's designator (none when two match)")

        // continuation lines: same millisecond, no callsign → one call; same millisecond with a callsign → two
        val th = com.bmscompanion.app.data.mission.RadioThread()
        val fileIt = { s: Long, p: RadioCalls.Parsed -> com.bmscompanion.app.data.mission.RadioMessage(s, text = "") }
        listOf(
            "[04:12:30.535] 1 Subtitle: Jaguar 2-1 magic 5 picture is multiple groups azimuth split",
            "[04:12:30.535] 1 Subtitle: Southeast group bullseye 1 3 7 / 1 3 0 13000 hot hostile",
            "[04:15:45.649] 1 Subtitle: Cyborg 1 magic 5 heads up new bandit",
            "[04:15:45.649] 1 Subtitle: magic 5 Cyborg 1 copy",
        ).forEach { raw -> RadioCalls.logLine(raw)?.let { l -> th.add(l, ctx) { s, p -> fileIt(s, p).copy(text = l.text) } } }
        check(th.messages.size == 3 && th.messages[0].text.lines().size == 2, "group line joined; two calls in the same millisecond kept apart")

        // ---- 3. appended while reading
        appendLine(); appendLine("3. A log that grows while it is read")
        val scratch = File(copy, "User\\Logs\\radiotest-scratch").also { it.deleteRecursively(); it.mkdirs() }
        val grow = File(scratch, "2026-01-01_120000_xlog.txt")
        grow.writeText("[00:00:00.000] 1 something else\r\n[04:00:01.000] 2 Subtitle: Jaguar 2-1 taxi Alpha and hold", Charsets.ISO_8859_1)
        val g = RadioLogReader({ ctx }).apply { missionKey = null; follow(grow) }
        val first = g.poll()
        grow.appendText(" short runway 3-6\r\n[04:00:02.000] 2 Subtitle: Jaguar 2-1 Gunsan Tower position and hold runway 3-6\r\n", Charsets.ISO_8859_1)
        val second = g.poll()
        val third = g.poll()
        check(first == 0 && second == 2 && third == 0 && g.all().size == 2 && g.all()[0].text.endsWith("hold short runway 3-6"),
            "half a line waits for its end (read $first, then $second, then $third; ${g.all().size} calls)")
        check(g.taxi?.kind == RadioTaxi.LINEUP && g.taxi?.runway == "36", "the last cue is the line-up on 36 (${g.taxi?.kind})")

        // ---- 4. replaced by a shorter file
        appendLine(); appendLine("4. A log replaced by a shorter one")
        val before = g.session
        grow.writeText("[04:10:00.000] 2 Subtitle: Mako 4 in\r\n", Charsets.ISO_8859_1)
        val again = g.poll()
        check(again == 1 && g.all().size == 1 && g.all()[0].seq == 1L && g.session != before, "read again from the start, numbered from 1, a new session name")

        // ---- 5. debug mode off
        appendLine(); appendLine("5. Debug mode off")
        val empty = File(scratch, "empty").also { it.mkdirs() }
        check(RadioLog.newestLog(empty) == null, "no *_xlog.txt: no log")
        val none = RadioLogReader({ ctx })
        check(none.poll() == 0 && none.all().isEmpty() && none.session.isEmpty(), "no log: no calls, nothing read")
        check(com.bmscompanion.app.data.mission.RadioLogStatus().state == com.bmscompanion.app.data.mission.RadioLogStatus.OFF, "the status a PC without the log answers is off")

        // ---- 6. the taxi automation on Gunsan's chart
        appendLine(); appendLine("6. The Taxi page's decisions on Gunsan AB")
        val gunsan = runCatching { findField("Gunsan AB") }.getOrNull()
        if (gunsan == null) appendLine("FAIL no Gunsan AB chart")
        else {
            val des = gunsan.routes.map { it.designator }
            val out = RadioCalls.applyTo(RadioTaxi(kind = RadioTaxi.OUT, runway = "36"), des)
            check(out == RadioCalls.TaxiApply(true, "36", null) && routeShown(gunsan, "36", true) != null, "taxi to runway 3-6: Taxi out, RWY 36")
            val park = RadioCalls.applyTo(RadioTaxi(kind = RadioTaxi.PARK, runway = "36", spot = 4), des)
            val net = park?.let { routeShown(gunsan, it.runway, it.outbound) }
            val spot = net?.parking?.firstOrNull { it.n == park.spot }
            check(park?.outbound == false && park.runway == "36" && net?.designator == "18" && spot?.label == "04",
                "park 0 0 4 after landing on 36: Taxi in, runway 18's network, spot ${spot?.label ?: "none"} (e${spot?.let { net.nodes.getOrNull(it.k)?.e?.toInt() }} n${spot?.let { net.nodes.getOrNull(it.k)?.n?.toInt() }})")
            val noSpot = RadioCalls.applyTo(RadioTaxi(kind = RadioTaxi.NOSPOT, runway = null), des)
            check(noSpot == RadioCalls.TaxiApply(false, null, null), "welcome back: Taxi in, the network the jet stands in, no spot")
            check(RadioCalls.applyTo(RadioTaxi(kind = RadioTaxi.OUT, runway = "27"), des) == null, "a runway Gunsan has not: nothing applied")
        }

        // ---- 7. deleting old logs
        appendLine(); appendLine("7. Deleting old logs (on a scratch folder in the copy)")
        val dir = File(scratch, "logs").also { it.mkdirs() }
        val old = System.currentTimeMillis() - 3 * 86_400_000L
        val stamps = listOf("2026-09-01_100000", "2026-09-02_100000", "2026-09-03_100000", "2026-09-04_100000", "2026-09-05_100000", "2026-09-06_100000", "2026-09-07_100000")
        for ((i, s) in stamps.withIndex()) {
            File(dir, "${s}_xlog.txt").apply { writeText("x".repeat(100)); setLastModified(old + i * 1000L) }
            File(dir, "${s}_xlog_packets.csv").apply { writeText("y"); setLastModified(old + i * 1000L) }
        }
        val others = listOf("2026-09-01_100000_crash.dmp", "2026-09-01_100000_crash.txt", "2026-09-01_100000_screenshot_1.bmp", "ErrorsInCampain.txt", "Placeholder.txt", "dtc_last_flight_faults.txt")
        for (o in others) File(dir, o).apply { writeText("keep"); setLastModified(old) }
        val reading = File(dir, "${stamps[1]}_xlog.txt")
        val plan = RadioLog.plan(dir, keep = 3, byDays = false, now = System.currentTimeMillis(), protect = reading)
        val planned = plan.map { it.name }.sorted()
        val want = listOf(stamps[0], stamps[2], stamps[3]).flatMap { listOf("${it}_xlog.txt", "${it}_xlog_packets.csv") }.sorted()
        check(planned == want, "keep 3 sessions: takes ${planned.size} files of the 3 oldest sessions but the one being read (${planned.joinToString()})")
        check(plan.none { f -> others.any { it == f.name } } && plan.none { it.name.startsWith(stamps.last()) }, "never a crash dump, screenshot or other file, never the newest session")
        val onlyNewest = RadioLog.plan(dir, keep = 1, byDays = false, now = System.currentTimeMillis())
        check(onlyNewest.none { it.name.startsWith(stamps.last()) } && onlyNewest.size == 12, "keep 1: everything but the newest session (${onlyNewest.size} files)")
        val now = System.currentTimeMillis()
        val byDays = RadioLog.plan(dir, keep = 30, byDays = true, now = now)
        val stampMs = { s: String -> java.time.LocalDateTime.parse(s, java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss")).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() }
        check(byDays.size == stamps.dropLast(1).count { stampMs(it) < now - 30 * 86_400_000L } * 2 && byDays.none { it.name.startsWith(stamps.last()) },
            "keep 30 days: ${byDays.size} files of sessions older than 30 days, never the newest session")
        val fresh = File(dir, "2026-09-08_100000_xlog.txt").apply { writeText("new") }
        val fresher = RadioLog.plan(dir, keep = 1, byDays = false, now = System.currentTimeMillis())
        check(fresher.none { it == fresh } && fresher.any { it.name.startsWith(stamps.last()) }, "a new session becomes the newest and the old newest may go")
        fresh.delete()
        // the real deletion, to the Recycle Bin, on the copy (DevGuard lets a copy through)
        val r = RadioLog.cleanupDir(dir, keep = 3, byDays = false, baseDir = copy.path, protect = reading)
        val left = dir.listFiles()!!.map { it.name }.toSet()
        check(r.error == null && r.deleted == want.size && want.none { it in left } && others.all { it in left } && "${stamps.last()}_xlog.txt" in left && reading.name in left,
            "cleanup on the copy: ${r.deleted} files to the Recycle Bin (${r.bytes} bytes), the rest kept${r.error?.let { " — $it" } ?: ""}")
        val install = DevGuard.registryBaseDirs().firstOrNull()
        // the real install is never handed to cleanupDir here: only the guard's answer for it is looked at
        if (install != null) {
            val refusal = DevGuard.refusal(File(install, "User\\Logs").path, install.path)
            check(DevGuard.on && refusal?.startsWith(DevGuard.REFUSAL) == true, "the real install's logs folder would be refused in a developer run ($refusal)")
        }
        scratch.deleteRecursively()

        val fails = lines().count { it.startsWith("FAIL") }
        appendLine(); appendLine(if (fails == 0) "All radio checks passed." else "$fails radio check(s) failed.")
    }

    private fun findField(name: String): Airfield? {
        val index = runBlocking { Repo.index() }
        for (th in index.theaters) {
            val set = th.airfieldSet ?: continue
            val a = runBlocking { Repo.airportSet(th.airportSet).airports }.firstOrNull { it.name.equals(name, true) } ?: continue
            runBlocking { Repo.airfield(set, a.id) }?.let { return it }
        }
        return null
    }
}
