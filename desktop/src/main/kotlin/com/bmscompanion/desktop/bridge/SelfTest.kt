package com.bmscompanion.desktop.bridge

import com.bmscompanion.app.data.mission.Board
import com.bmscompanion.app.data.mission.Briefing
import com.bmscompanion.app.data.mission.EzRun
import com.bmscompanion.app.data.mission.Live
import com.bmscompanion.app.data.mission.NavPoint
import com.bmscompanion.app.data.mission.Voice
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * Developer checks, run as `"BMS Companion.exe" --selftest out.txt` (or `./gradlew :desktop:run --args="--selftest out.txt"`):
 * - `--selftest out.txt`: shared memory struct sizes and parser output on the demo data;
 * - `--dumpstrings out.txt`: raw StringData ids and values from a running BMS (to verify the id table);
 * - `--eztest <EZBoards folder> out.txt`: runs EZBoards exactly like the app button does (use a copy of the folder).
 * - `--api <path>[,<path>…] out.txt`: API responses (e.g. /api/info,/api/mission) from Falcon BMS on this PC, one per line.
 * - `--maprender <theater> <folder> [xFt,yFt]`: map styles and landmarks rendered to PNGs (see MapRender).
 * - `--updatetest out.txt [download]`: what the About page sees on GitHub — the releases newer than this build, the
 *   file this platform would fetch and the checksum it would check it against. With `download`, it fetches that
 *   file and verifies it, without installing anything.
 */
object SelfTest {
    fun run(args: Array<String>): Boolean {
        val json = Bridge.json
        when {
            args.size == 2 && args[0] == "--selftest" -> {
                val demo = DemoSource()
                val (fd, fd2) = SharedMemoryReader.structSizes
                File(args[1]).writeText(buildString {
                    appendLine("sizeof(FlightData)=$fd sizeof(FlightData2)=$fd2")
                    appendLine(json.encodeToString(Briefing.serializer(), BriefingParser.parse(demo.briefingText)))
                    appendLine(json.encodeToString(Live.serializer(), demo.live()))
                    SharedMemoryReader.parseNavPoint("NP:56,PT,2910660.5,258538.0,0.0,52.1;PT:\"SA-5\",364567.0,0;")?.let { appendLine(json.encodeToString(NavPoint.serializer(), it)) }
                    appendLine(json.encodeToString(Voice.serializer(), SharedMemoryReader.parseVoice("Ouranos5|PAXX,None,Dragnet5,Larissa,Larissa,Nea Anchialos")))
                    System.getenv("BMSC_TEST_BOARD_HTML")?.let(::File)?.takeIf { it.isFile }?.let { appendLine(json.encodeToString(Board.serializer(), EzBoardsRunner.parseHtml(it.readText()))) }
                })
            }
            args.size == 2 && args[0] == "--dumpstrings" -> {
                val snap = SharedMemoryReader.read()
                File(args[1]).writeText(buildString {
                    appendLine("available=${snap.available} flying=${snap.flying} version=${snap.version}")
                    snap.strings.toSortedMap().forEach { (id, v) -> appendLine("${id.toString().padStart(3)} ${StringId.name(id).padEnd(24)} $v") }
                    snap.navPointStrings.forEach { appendLine("NP $it") }
                    appendLine("hsiBits=0x${snap.hsiBits.toString(16).uppercase().padStart(8, '0')} lightBits=0x${snap.lightBits.toString(16).uppercase().padStart(8, '0')} pilotsOnline=${snap.pilotsOnline} status=${snap.pilotStatus.joinToString(",")} ded0=${snap.live.ded.firstOrNull()}")
                })
            }
            args.size == 3 && args[0] == "--eztest" -> File(args[2]).writeText(json.encodeToString(EzRun.serializer(), EzBoardsRunner().generate(args[1], auto = false)))
            args.size == 3 && args[0] == "--api" -> {
                Bridge.start()
                Thread.sleep(2500) // first tick: files and Tacview
                // several paths separated by commas: one response per line
                File(args[2]).writeText(args[1].split(',').joinToString("\n") { Bridge.handle(ApiRequest("GET", it)).body.toString(Charsets.UTF_8) })
                Bridge.stop()
            }
            args.size >= 2 && args[0] == "--updatetest" -> {
                com.bmscompanion.app.data.Platform.fetchText = { url -> com.bmscompanion.desktop.fetchTextFromWeb(url) }
                com.bmscompanion.app.data.Platform.installer = com.bmscompanion.desktop.PcInstaller
                com.bmscompanion.app.data.Platform.nowMillis = { System.currentTimeMillis() }
                val out = StringBuilder()
                kotlinx.coroutines.runBlocking {
                    val updates = com.bmscompanion.app.data.update.Updates
                    // --updatetest out.txt [download] [<version to pretend to be>]
                    args.drop(2).firstOrNull { it.firstOrNull()?.isDigit() == true }?.let { updates.baseline = it }
                    updates.check(force = true)
                    val state = updates.state.value
                    out.appendLine("running=${com.bmscompanion.app.AppVersion.NAME} comparing against ${updates.baseline} error=${state.error}")
                    out.appendLine("newer=${state.newer.size}")
                    state.newer.forEach { r ->
                        val asset = updates.assetFor(r)
                        out.appendLine("  ${r.version} (${r.date}) \"${r.title}\" notes=${r.body?.length ?: 0} chars")
                        out.appendLine("    asset=${asset?.name} ${asset?.size} bytes digest=${asset?.digest}")
                    }
                    // the same path the About page takes, stopping short of running the installer
                    if (args.getOrNull(2) == "download") {
                        val r = state.latest
                        if (r == null) out.appendLine("nothing newer to download") else {
                            // what the About page would be showing while it runs, one line per change
                            val watcher = launch {
                                var last = ""
                                while (true) {
                                    updates.state.value.progress?.let { p ->
                                        val line = "    ${p.stage} ${p.text}"
                                        if (line != last) { out.appendLine(line); last = line }
                                    }
                                    delay(700)
                                }
                            }
                            val name = updates.fetch(r)
                            watcher.cancel()
                            out.appendLine("fetched=$name verified=${name != null}")
                            out.appendLine("in the cache: ${com.bmscompanion.desktop.PcInstaller.cachedFiles()}")
                            com.bmscompanion.desktop.PcInstaller.clearCache()
                            out.appendLine("cache cleared: ${com.bmscompanion.desktop.PcInstaller.cachedFiles()}")
                        }
                    }
                }
                File(args[1]).writeText(out.toString())
            }
            args.size >= 3 && args[0] == "--maprender" -> MapRender.run(args[1], File(args[2]),
                args.getOrNull(3)?.split(',')?.let { it[0].toDouble() to it[1].toDouble() })
            else -> return false
        }
        return true
    }
}
