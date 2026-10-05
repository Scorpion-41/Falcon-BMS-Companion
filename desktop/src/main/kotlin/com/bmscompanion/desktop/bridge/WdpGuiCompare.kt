package com.bmscompanion.desktop.bridge

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.mission.MissionData
import com.bmscompanion.app.data.wdp.PopupCoords
import com.bmscompanion.app.ui.screens.wdp.DataCardWiring
import com.bmscompanion.app.ui.screens.wdp.HadbWiring
import com.bmscompanion.app.ui.screens.wdp.PerformanceWiring
import com.bmscompanion.app.ui.screens.wdp.PopupWiring
import com.bmscompanion.app.ui.screens.wdp.TossWiring
import com.bmscompanion.app.ui.screens.wdp.WdpControlContent
import com.bmscompanion.app.ui.screens.wdp.WdpDialogHost
import com.bmscompanion.app.ui.screens.wdp.WdpFormView
import com.bmscompanion.app.ui.screens.wdp.WdpMission
import com.bmscompanion.app.ui.screens.wdp.WdpPage
import com.bmscompanion.app.ui.screens.wdp.WdpWiring
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import java.io.RandomAccessFile

/**
 * `--wdpguicompare <out folder> <briefing.txt> <cartridge.ini> <WDP Setup.ini> <target stpt> <tN,tE,tZ> <ipN,ipE,ipZ> [HeightMap.raw]`
 *
 * A test mode for the side-by-side check against the running Weapon Delivery Planner: puts the port's pages in the
 * state WDP's own window was in, so the two can be compared field by field.
 *
 * - The attack pages and Performance read their saved settings from WDP's own Setup.ini (the sections `[PopUp]`,
 *   `[HADB]`, `[TOSS]`, `[Performance]`, keys trimmed as GetPrivateProfileString trims them), exactly what WDP's
 *   `Setup()` read when its pages loaded. The app's own saved settings are put back afterwards.
 * - The target is the steerpoint WDP was on, with the target and IP positions WDP took from the campaign (sim feet,
 *   north, east, altitude) — the pilot's cartridge may not hold the flight plan (a campaign cartridge often does
 *   not), which is itself one of the differences the check records. They are given to the pages as WDP had them: a
 *   save's flight whose steerpoint <stpt> is a strike (so each page's TGT STPT is set to it, as `CreateFlightplan`
 *   sets WDP's) at the target, with the IP at the one before, and the cartridge's own two slots left out (WDP with
 *   its Precision question answered No reads the flight table, not the DTC).
 * - Given BMS's `HeightMap.raw` (read only, two bytes at the target's cell), the Pop-up page gets a flat terrain of
 *   that height, as WDP's terrain lookup gives it under the target; without it the page runs as the app does (0).
 *
 * Writes `labels-<page>-<panel>.txt` (every value the page hands the renderer, sorted) and a PNG of each panel.
 */
object WdpGuiCompare {
    private fun section(ini: File, name: String): String? {
        if (!ini.isFile) return null
        val out = ArrayList<String>()
        var inside = false
        for (raw in ini.readLines()) {
            val line = raw.trim()
            if (line.startsWith("[") && line.endsWith("]")) { inside = line.substring(1, line.length - 1).trim().equals(name, true); continue }
            if (inside && '=' in line) out += line.substringBefore('=').trim() + "=" + line.substringAfter('=').trim()
        }
        return out.joinToString("\n").ifEmpty { null }
    }

    private fun triple(s: String): Triple<Double, Double, Double> =
        s.split(',').map { it.trim().toDouble() }.let { Triple(it[0], it[1], it[2]) }

    fun run(args: List<String>): String = buildString {
        val out = File(args[0]).also { it.mkdirs() }
        val briefing = File(args[1]); val cartridge = File(args[2]); val setup = File(args[3])
        val stpt = args[4].toInt()
        val tgt = triple(args[5]); val ip = triple(args[6])
        val heightmapFile = args.getOrNull(7)?.let(::File)?.takeIf { it.isFile }

        val keys = mapOf("PopUp" to "wdp_popup_ini", "HADB" to "wdp_hadb_ini", "TOSS" to "wdp_toss_ini", "Performance" to PerformanceWiring.INI_KEY)
        val saved = keys.values.associateWith { Repo.getString(it) }
        for ((sec, key) in keys) {
            val s = section(setup, sec)
            Repo.putString(key, s)
            appendLine("Setup.ini [$sec] -> $key: ${s?.lines()?.size ?: 0} keys")
        }
        try {
            val data = MissionData(
                briefing = runCatching { BriefingParser.parse(briefing.readText()) }.getOrNull(),
                dtc = runCatching { DtcParser.parse(cartridge) }.getOrNull(),
            )
            val theater = System.getenv("BMSC_WDP_THEATER")?.let { name ->
                runBlocking { com.bmscompanion.app.ui.screens.wdp.plannerTheater(Repo.index().theaters, name) }
            }
            val base = WdpMission.of(data).copy(theater = theater)
            appendLine("theater: ${theater?.name}; cartridge steerpoints: ${base.steerpoints.joinToString { it.n.toString() }}")
            appendLine("targets in the mission: ${base.choices.joinToString { it.label }}")
            // WDP's window state: its flight table's steerpoint <stpt> (a strike, so every page's TGT STPT goes to it)
            // at the target, the one before at the IP; the cartridge's own two slots out of the way
            val flight = com.bmscompanion.app.data.mission.CampFlight(
                row = com.bmscompanion.app.data.mission.CampFlightRow(id = "as-wdp", callsign = data.briefing?.overview?.flight ?: "", f16 = true),
                route = listOf(
                    com.bmscompanion.app.data.mission.CampWaypoint(n = stpt - 1, x = ip.first, y = ip.second, altFt = ip.third),
                    com.bmscompanion.app.data.mission.CampWaypoint(n = stpt, x = tgt.first, y = tgt.second, altFt = tgt.third, action = WdpMission.STRIKE),
                ),
            )
            val dtc = data.dtc?.let { d -> d.copy(steerpoints = d.steerpoints.filter { it.n != stpt && it.n != stpt - 1 }) }
            val mission = base.copy(dtc = dtc, flight = flight)
            appendLine("as WDP: TGT STPT ${mission.defaultStpt}, target ${mission.steerpoint(stpt)?.let { "${it.x}, ${it.y}" }} (${mission.source(stpt)?.label}), IP ${mission.steerpoint(stpt - 1)?.let { "${it.x}, ${it.y}" }}")

            val popup = PopupWiring(onSaveDtc = { })
            val hadb = HadbWiring()
            val toss = TossWiring()
            val perf = PerformanceWiring().also { runBlocking { it.prepare(mission) } }
            for (w in listOf(popup, hadb, toss)) w.onMission(mission)
            mission.coords?.let { c ->
                popup.coordData(c.originLat, c.originLong, c.campW, c.campH, c.enableNewTerrain, c.tm)
                hadb.coordData(c.originLat, c.originLong, c.campW, c.campH, c.enableNewTerrain, c.tm)
            }
            // the terrain under the target, from BMS's own heightmap, by WDP's formula (ReadNewTerrainElvLoc)
            if (heightmapFile != null && mission.coords != null) {
                val c = mission.coords!!
                val len = heightmapFile.length()
                val num = Math.rint(Math.sqrt(len / 2.0)).toLong()
                val row = Math.rint((c.campH - tgt.first.toFloat()) / c.campH * num).toLong().coerceAtMost(num)
                val col = Math.rint(tgt.second.toFloat() / c.campW * num).toLong().coerceAtMost(num)
                val at = row * num * 2 + col * 2
                val elev = RandomAccessFile(heightmapFile, "r").use { f -> f.seek(at); val lo = f.read(); val hi = f.read(); ((hi shl 8) or lo).toShort().toInt() }
                appendLine("HeightMap.raw: ${len} bytes, ${num}x$num cells; target cell row $row col $col -> $elev ft")
                val flat = ByteArray(8) { i -> if (i % 2 == 0) (elev and 0xff).toByte() else ((elev shr 8) and 0xff).toByte() }
                val m = popup.plan.main
                m.heightmap = flat; m.terrainLoaded = true; m.newTerrainElvLoaded = true
                // run the page's flow again as WDP does after its terrain is loaded (STPTChange → Get_Coords → flow)
                popup.onClick("numWaypoint:up"); popup.onClick("numWaypoint:down")
            }

            // BMSC_GUI_OPS="popup:lblE;hadb:pnlBomb_Up;…": the clicks the pilot made in WDP's window, by control name
            val byName = mapOf("popup" to popup as WdpWiring, "hadb" to hadb, "toss" to toss, "performance" to perf)
            for (op in System.getenv("BMSC_GUI_OPS").orEmpty().split(';').map { it.trim() }.filter { ':' in it }) {
                val w = byName[op.substringBefore(':')] ?: continue
                val rest = op.substringAfter(':')
                if ('=' in rest) w.onValue(rest.substringBefore('='), rest.substringAfter('=')) else w.onClick(rest)
                appendLine("op   $op")
            }

            fun dump(page: WdpPage, w: WdpWiring, name: String) {
                val values = w.values(page.hidden)
                File(out, "labels-$name.txt").writeText(values.values.entries.sortedBy { it.key }.joinToString("\n") { "${it.key}\t${it.value}" })
                val form = runBlocking { Repo.wdpForm(page.form) } ?: return
                val scene = ImageComposeScene(1400, 1500, Density(1f)) {
                    Box(Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxSize().background(Color(0xFF15181C))) {
                            WdpFormView(form, values, Modifier.fillMaxWidth().padding(8.dp), fitWidth = true, onValue = w::onValue, onClick = w::onClick,
                                content = (w as? WdpControlContent)?.controlContent() ?: emptyMap())
                        }
                        WdpDialogHost()
                    }
                }
                var t = 0L
                repeat(30) { scene.render(t); t += 50_000_000; Thread.sleep(40) }
                File(out, "port-$name.png").writeBytes(scene.render(t).encodeToData(EncodedImageFormat.PNG)!!.bytes)
                scene.close()
                appendLine("ok   $name: ${values.values.size} values")
            }
            for ((page, w, n) in listOf(Triple(WdpPage.POPUP, popup as WdpWiring, "popup"), Triple(WdpPage.HADB, hadb, "hadb"), Triple(WdpPage.TOSS, toss, "toss"))) {
                dump(page, w, "$n-selections")
                w.onClick(if (page == WdpPage.TOSS) "lblProfileSelect" else "lblShowProfile")
                dump(page, w, "$n-profile")
                w.onClick(if (page == WdpPage.TOSS) "lblDEDDataSelec" else "lblShowSelections")
                dump(page, w, "$n-ded")
                w.onClick(if (page == WdpPage.TOSS) "lblShowSelections" else "lblHideSelections")
            }
            dump(WdpPage.PERFORMANCE, perf, "performance")
        } finally {
            for ((k, v) in saved) Repo.putString(k, v)
        }
    }
}
