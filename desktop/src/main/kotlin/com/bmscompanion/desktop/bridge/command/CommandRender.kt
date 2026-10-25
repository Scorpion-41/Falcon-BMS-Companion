package com.bmscompanion.desktop.bridge.command

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.bmscompanion.app.ui.components.BmsChart
import com.bmscompanion.app.ui.components.MapDeep
import com.bmscompanion.app.ui.theme.BmsTheme
import com.bmscompanion.app.ui.theme.Hud
import com.bmscompanion.desktop.bridge.ApiRequest
import com.bmscompanion.desktop.bridge.MapTiles
import com.bmscompanion.desktop.bridge.Theaters
import com.bmscompanion.desktop.ui.command.CmdDocument
import com.bmscompanion.desktop.ui.command.CmdKind
import com.bmscompanion.desktop.ui.command.CmdLayers
import com.bmscompanion.desktop.ui.command.CmdPage
import com.bmscompanion.desktop.ui.command.CmdTool
import com.bmscompanion.desktop.ui.command.CommandSection
import com.bmscompanion.desktop.ui.command.CommandSession
import com.bmscompanion.desktop.ui.command.LinkView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/**
 * `--cmdrender <a copy of a BMS folder> <out folder>`: the Command section drawn headless with a save open, at 1600 x
 * 1000 and 2560 x 1440 — the Map page with its layers (air-defence rings, links of the selection, a flight's route
 * under the mouse), the BMS chart with BMS's icons, the Units page with its drawer, the Teams page, a campaign start, and
 * the Open window, and the layout at eight window sizes (`size-*`, [SIZES]). The save is the copy's newest campaign of the theater BMS is set to (else any); the map's levels past
 * the bundled ones come from the PC's own tile code on the copy (MapTiles), BMS's chart from `BMSC_CAMPMAP_ROOT` when set
 * (read only; the real install's 500 MB chart need not be copied), else the copy. Nothing is written into the copy but
 * the section's own one-time backup of the folder's BMS files (a copy has no Falcon BMS.exe, so DevGuard lets it).
 */
object CommandRender {
    private class Owner : LifecycleOwner, ViewModelStoreOwner {
        private val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
        override val viewModelStore = ViewModelStore()
    }

    fun run(copy: File, out: File): String = buildString {
        out.mkdirs()
        runCatching { org.jetbrains.skia.Surface.makeRasterN32Premul(1, 1).close() }
        val chartRoot = System.getenv("BMSC_CAMPMAP_ROOT")?.let(::File)?.takeIf { it.isDirectory } ?: copy
        CommandSession.rootOverride = copy
        MapTiles.rootOverride = copy
        CampMap2D.rootOverride = chartRoot
        CampMap2D.forget()
        MapDeep.fetcher = { path -> withContext(Dispatchers.IO) { fetch(path) } }
        val before = CmdLayers.prefs
        try {
            val set = Theaters.at(copy)
            val saves = set.all.flatMap { set.saves(it) }
            val save = saves.firstOrNull { it.extension.equals("cam", true) && it.name.startsWith("Save-Day", true) }
                ?: saves.firstOrNull { it.extension.equals("cam", true) && !Regex("(?i)save\\d+|instant").matches(it.nameWithoutExtension) }
                ?: run { append("FAIL: no campaign save in ${copy.path}\n"); return@buildString }
            val start = saves.firstOrNull { Regex("(?i)save\\d+").matches(it.nameWithoutExtension) }
            appendLine("save: ${save.name}; start: ${start?.name ?: "none"}; BMS chart from ${if (chartRoot == copy) "the copy" else "BMSC_CAMPMAP_ROOT"}")
            val t0 = System.nanoTime()
            val doc = CmdDocument.open(save, copy).getOrElse { append("FAIL: ${save.name} did not open: ${it.message}\n"); return@buildString }
            appendLine("opened in ${(System.nanoTime() - t0) / 1_000_000} ms: ${doc.world.objectives.size} objectives, ${doc.world.units.size} units, ${doc.world.teams.count { it.inUse }} teams; ${doc.core.identity.why}")
            doc.world.notes.forEach { appendLine("note: $it") }
            if (doc.world.objectives.isEmpty()) appendLine("FAIL: no objectives")
            if (doc.world.units.isEmpty()) appendLine("FAIL: no units")
            val chart = CampMap2D.list()
            appendLine("BMS charts: ${chart.theaters.joinToString { "${it.theater} z${it.nativeZ}" }.ifEmpty { "none" }}")
            CommandSession.doc = doc
            val airbase = doc.world.objectives.firstOrNull { it.type == 1 && it.links?.isNotEmpty() == true } ?: doc.world.objectives.firstOrNull { it.type == 1 }
            val flight = doc.world.units.firstOrNull { it.kind == CmdKind.FLIGHT && it.route.size > 3 }
            val sam = doc.world.units.firstOrNull { it.symbol == "airdefence" && it.system != null }

            fun shot(name: String, w: Int, h: Int, setup: () -> Unit, focus: (() -> Unit)? = null, density: Float = 1f) {
                setup()
                render(out, name, w, h, focus, density)?.let { appendLine(it) }
            }
            // the layout at every window size a pilot has (the toolbar, the strips and the chip rows wrap, the side
            // panels give way, the map fills its box): the file named by BMSC_CMDRENDER_FILE (else the start, else the
            // save), Map / Units / Objectives / ATO / Teams, at density 1 and the 1490 x 1350 window also at 1.25;
            // BMSC_CMDRENDER_ONLY=sizes draws only these
            val sizesDoc = System.getenv("BMSC_CMDRENDER_FILE")?.let { n -> saves.firstOrNull { it.name.equals(n, true) } }
                ?.let { f -> CmdDocument.open(f, copy).getOrNull() } ?: start?.let { CmdDocument.open(it, copy).getOrNull() } ?: doc
            CommandSession.doc = sizesDoc
            appendLine("sizes: ${sizesDoc.file.name}")
            val sizeFlight = sizesDoc.world.units.firstOrNull { it.kind == CmdKind.FLIGHT }?.ref ?: sizesDoc.world.units.firstOrNull()?.ref
            val sizeAirbase = sizesDoc.world.objectives.firstOrNull { it.type == 1 }?.ref
            for ((sw, sh, dens) in SIZES) {
                val tag = "${sw}x$sh" + if (dens != 1f) "@${(dens * 100).toInt()}" else ""
                for (p in listOf(CmdPage.MAP, CmdPage.UNITS, CmdPage.OBJECTIVES, CmdPage.ATO, CmdPage.TEAMS)) {
                    shot("size-$tag-${p.name.lowercase()}", sw, sh, {
                        CmdLayers.update { it.copy(style = "relief", bmsIcons = false, adRings = false, links = LinkView.SELECTED) }
                        CommandSession.page = p; CommandSession.tool = CmdTool.SELECT
                        CommandSession.selection.set(if (p == CmdPage.OBJECTIVES || p == CmdPage.MAP) sizeAirbase else sizeFlight)
                    }, density = dens)
                }
            }
            CommandSession.doc = doc
            if (System.getenv("BMSC_CMDRENDER_ONLY") == "sizes") { appendLine("pictures: ${out.listFiles { f -> f.name.endsWith(".png") }?.size ?: 0}"); return@buildString }
            for ((w, h) in listOf(1600 to 1000, 2560 to 1440)) {
                shot("map-$w", w, h, {
                    CmdLayers.update { it.copy(style = "relief", bmsIcons = false, adRings = true, adShort = true, links = LinkView.SELECTED, battalions = true, brigades = true, divisions = false) }
                    CommandSession.page = CmdPage.MAP; CommandSession.tool = CmdTool.SELECT
                    CommandSession.selection.set(airbase?.ref); CommandSession.selection.hover = flight?.ref
                }, focus = { (airbase?.ref ?: sam?.ref)?.let { CommandSession.flyTo(it) } })
                shot("map-wide-$w", w, h, {
                    CmdLayers.update { it.copy(style = "relief", adRings = true, links = LinkView.OFF) }
                    CommandSession.selection.set(sam?.ref); CommandSession.selection.hover = null
                })
                if (chart.theaters.any { it.theater == doc.theaterId }) shot("bmschart-$w", w, h, {
                    CmdLayers.update { it.copy(style = BmsChart.STYLE, bmsIcons = true, adRings = false, links = LinkView.ALL) }
                    CommandSession.selection.set(airbase?.ref)
                }, focus = { airbase?.ref?.let { CommandSession.flyTo(it) } })
                shot("units-$w", w, h, {
                    CmdLayers.update { it.copy(style = "dark", bmsIcons = false, links = LinkView.SELECTED) }
                    CommandSession.page = CmdPage.UNITS; CommandSession.selection.set(flight?.ref)
                }, focus = { flight?.ref?.let { CommandSession.flyTo(it) } })
                shot("teams-$w", w, h, { CommandSession.page = CmdPage.TEAMS })
                // the unit editor: the ATO tree, a flight with a waypoint open, a squadron
                if (w == 1600) {
                    val sqRef = doc.world.units.firstOrNull { it.kind == CmdKind.SQUADRON && doc.world.units.any { f -> f.squadron == it.ref.id } }?.ref
                    shot("ato-$w", w, h, { CommandSession.page = CmdPage.ATO; CommandSession.selection.set(flight?.ref) }, focus = { flight?.ref?.let { CommandSession.flyTo(it) } })
                    shot("flight-$w", w, h, {
                        CommandSession.page = CmdPage.UNITS
                        CommandSession.selection.set(flight?.let { com.bmscompanion.desktop.ui.command.CmdRef(CmdKind.WAYPOINT, it.ref.id, 2) })
                    }, focus = { flight?.ref?.let { CommandSession.flyTo(it) } })
                    shot("squadron-$w", w, h, { CommandSession.page = CmdPage.UNITS; CommandSession.selection.set(sqRef) }, focus = { sqRef?.let { CommandSession.flyTo(it) } })
                }
                // the objective, campaign and checks editors (a played save: objective changes as deltas)
                shot("objectives-$w", w, h, {
                    CmdLayers.update { it.copy(style = "relief", links = LinkView.SELECTED) }
                    CommandSession.page = CmdPage.OBJECTIVES; CommandSession.selection.set(airbase?.ref)
                }, focus = { airbase?.ref?.let { CommandSession.flyTo(it) } })
                shot("campaign-$w", w, h, { CommandSession.page = CmdPage.CAMPAIGN; CommandSession.selection.clear() })
                if (w == 1600) shot("occupation-$w", w, h, { com.bmscompanion.desktop.ui.command.inspectors.CampaignPage.tab = com.bmscompanion.desktop.ui.command.inspectors.CampaignPage.Tab.OCCUPATION })
                com.bmscompanion.desktop.ui.command.inspectors.CampaignPage.tab = com.bmscompanion.desktop.ui.command.inspectors.CampaignPage.Tab.HEADER
                shot("checks-$w", w, h, { CommandSession.page = CmdPage.CHECKS })
                if (w == 1600) shot("pilots-$w", w, h, { CommandSession.page = CmdPage.PILOTS })
                if (w == 1600) shot("stats-$w", w, h, { CommandSession.page = CmdPage.STATS })
                // the Files page: Stations+Ils.dat with a station chosen (its ILS slots named), and a MissionData file
                val campDir = doc.set.campaignDir(doc.theater)
                if (campDir != null) {
                    shot("files-$w", w, h, { CommandSession.page = CmdPage.FILES; com.bmscompanion.desktop.ui.command.inspectors.FilesPage.pickForCheck(campDir, "Stations+Ils.dat", 2) })
                    if (w == 1600) shot("files-missiondata-$w", w, h, { com.bmscompanion.desktop.ui.command.inspectors.FilesPage.pickForCheck(campDir, "MissionData_XX.xml", 1) })
                    if (w == 1600) shot("files-teunits-$w", w, h, { com.bmscompanion.desktop.ui.command.inspectors.FilesPage.pickForCheck(campDir, "TeUnits.lst", 3) })
                }
            }
            if (start != null) {
                CmdDocument.open(start, copy).onSuccess { d ->
                    CommandSession.doc = d
                    appendLine("start ${start.name}: ${d.world.objectives.size} objectives; editable objectives ${d.core.objectivesEditable}; ${d.core.identity.why}")
                    shot("start-1600", 1600, 1000, {
                        CmdLayers.update { it.copy(style = "chart", links = LinkView.ALL, adRings = false) }
                        CommandSession.page = CmdPage.OBJECTIVES; CommandSession.selection.clear()
                    })
                    // Edit BMS's campaign start (backs the start up into the copy's own backup folder), an airbase's whole record
                    val ab = d.world.objectives.firstOrNull { it.type == 1 && (it.links?.size ?: 0) >= 2 }
                    d.editing?.editStart()?.let { appendLine("FAIL: Edit BMS's campaign start: $it") }
                    shot("start-edit-1600", 1600, 1000, {
                        CmdLayers.update { it.copy(style = "chart", links = LinkView.SELECTED, adRings = false) }
                        CommandSession.page = CmdPage.OBJECTIVES; CommandSession.selection.set(ab?.ref)
                    }, focus = { ab?.ref?.let { CommandSession.flyTo(it) } })
                    shot("start-checks-1600", 1600, 1000, { CommandSession.page = CmdPage.CHECKS; CommandSession.selection.clear() })
                }.onFailure { appendLine("FAIL: ${start.name} did not open: ${it.message}") }
            }
            CommandSession.doc = null
            shot("open-1600", 1600, 1000, { CommandSession.openWindow = false })
            CommandSession.doc = doc
            shot("openwindow-1600", 1600, 1000, { CommandSession.page = CmdPage.MAP; CommandSession.openWindow = true })
            CommandSession.openWindow = false
            appendLine("pictures: ${out.listFiles { f -> f.name.endsWith(".png") }?.size ?: 0}")
        } catch (e: Throwable) {
            appendLine("FAIL: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            CmdLayers.update { before }
            MapDeep.fetcher = null
            CommandSession.doc = null
            CommandSession.selection.clear()
        }
    }

    /** The PC's answers the section asks for (BMS's charts, the deep map tiles), without a link. */
    private fun fetch(path: String): ByteArray? {
        val p = path.substringBefore('?')
        val q = path.substringAfter('?', "").split('&').filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
        return when {
            p == "/api/campmap/info" -> CampMap2D.info().body
            p == "/api/maptile" && q["style"] == CampMap2D.STYLE -> CampMap2D.handle(ApiRequest("GET", p, q)).takeIf { it.status == 200 }?.body
            p == "/api/maptile" -> runCatching { MapTiles.tile(q["map"]!!, q["style"]!!, q["z"]!!.toInt(), q["r"]!!.toInt(), q["c"]!!.toInt()).bytes }.getOrNull()
            else -> null
        }
    }

    /** The window sizes the layout pass draws (pixels, and the display's scale). */
    private val SIZES = listOf(
        Triple(1280, 720, 1f), Triple(1366, 768, 1f), Triple(1490, 1350, 1f), Triple(1490, 1350, 1.25f), Triple(1600, 900, 1f),
        Triple(1920, 1080, 1f), Triple(2560, 1440, 1f), Triple(1100, 1300, 1f),
    )

    /** One picture of the section at [w] x [h] pixels at [density]; [focus] runs once the map is up. Null when written, else a FAIL line. */
    private fun render(out: File, name: String, w: Int, h: Int, focus: (() -> Unit)?, density: Float = 1f): String? {
        val owner = Owner()
        fun <T> edt(f: () -> T): T {
            var r: Result<T>? = null
            javax.swing.SwingUtilities.invokeAndWait { r = runCatching(f) }
            return r!!.getOrThrow()
        }
        var scene: ImageComposeScene? = null
        return try {
            scene = edt {
                ImageComposeScene(w, h, Density(density)) {
                    CompositionLocalProvider(
                        LocalLifecycleOwner provides owner,
                        LocalViewModelStoreOwner provides owner,
                        LocalConfiguration provides Configuration((w / density).toInt(), (h / density).toInt()),
                    ) {
                        BmsTheme { Box(Modifier.fillMaxSize().background(Hud.Bg)) { CommandSection(null, local = true) } }
                    }
                }
            }
            var t = 0L
            repeat(100) { i ->
                val at = t
                edt {
                    androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
                    if (i == 6) focus?.invoke()
                    scene.render(at).close()
                }
                t += 50_000_000; Thread.sleep(40)
            }
            val bytes = edt {
                androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
                scene.render(t).use { it.encodeToData(EncodedImageFormat.PNG)?.bytes }
            }
            if (bytes == null) "FAIL: $name: nothing drawn" else { File(out, "$name.png").writeBytes(bytes); null }
        } catch (e: Throwable) {
            "FAIL: $name: ${e.javaClass.simpleName} ${e.message}"
        } finally {
            runCatching { edt { scene?.close() } }
        }
    }
}
