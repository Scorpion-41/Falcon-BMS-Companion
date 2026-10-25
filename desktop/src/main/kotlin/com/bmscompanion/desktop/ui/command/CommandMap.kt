package com.bmscompanion.desktop.ui.command

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.ui.components.MapLook
import com.bmscompanion.app.ui.components.MapProjection
import com.bmscompanion.app.ui.components.MapState
import com.bmscompanion.app.ui.components.MapText
import com.bmscompanion.app.ui.components.SEA_UNIT_KINDS
import com.bmscompanion.app.ui.components.TheaterMap
import com.bmscompanion.app.ui.components.drawUnitSymbol
import com.bmscompanion.app.ui.screens.mission.siteRingFt
import com.bmscompanion.app.ui.theme.Hud
import com.bmscompanion.app.ui.theme.inMapInks
import com.bmscompanion.desktop.bridge.CampaignArchive
import com.bmscompanion.desktop.bridge.command.CampMapIcons
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import com.bmscompanion.app.data.FileFilter
import com.bmscompanion.app.data.FileMode
import com.bmscompanion.app.data.FileRequest
import com.bmscompanion.app.data.NativeAnswer
import com.bmscompanion.desktop.PcFileDialogs
import com.bmscompanion.desktop.bridge.command.CommandExport
import kotlinx.coroutines.launch
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ImageInfo
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

// The Command section's map: the app's TheaterMap (every style, BMS's own chart among them) with the whole file drawn
// over it — objectives by type and owner, links, parents, air-defence rings per team, squadrons, flights, packages,
// battalions, brigades, divisions, task forces and their routes — every side. Picking is by screen distance on what
// the last frame drew (as the Planner's Map page picks), so a filter that hides a thing also makes it unpickable.

private const val FT_PER_NM = 6076.12

/** One thing drawn in the last frame, where it was, how near a press must come. */
private class Hit(val ref: CmdRef, val x: Float, val y: Float, val r: Float, val prio: Int, val hidden: Boolean = false)

/**
 * Room on the map for one family of symbols (objectives, ground units, flights): a symbol is left out where one more
 * important of its family is already drawn closer than [gap] px (0: every one is drawn). A thing left out is still in
 * an Area selection, never under a click.
 */
private class Room(val gap: Float) {
    private val cells = HashMap<Long, ArrayList<Offset>>()
    private fun key(ix: Int, iy: Int) = (ix.toLong() shl 32) or (iy.toLong() and 0xffffffffL)
    fun free(p: Offset): Boolean {
        if (gap <= 0f) return true
        val ix = kotlin.math.floor(p.x / gap).toInt()
        val iy = kotlin.math.floor(p.y / gap).toInt()
        for (dx in -1..1) for (dy in -1..1) cells[key(ix + dx, iy + dy)]?.forEach { q -> if (hypot(q.x - p.x, q.y - p.y) < gap) return false }
        return true
    }
    fun take(p: Offset) {
        if (gap <= 0f) return
        cells.getOrPut(key(kotlin.math.floor(p.x / gap).toInt(), kotlin.math.floor(p.y / gap).toInt())) { ArrayList() } += p
    }
}

/** What the last frame drew and with which projection. */
private class MapFrame {
    var pr: MapProjection? = null
    var hits = ArrayList<Hit>(4096)
    /** the map's place in the window (for [CommandSession.screenHook]) */
    var origin = Offset.Zero
    var next = ArrayList<Hit>(4096)
    fun begin() { next.clear() }
    fun end() { val t = hits; hits = next; next = t }
}

/** The tools whose click edits: acted on at the release, not after the map's double-tap wait. */
private val CLICK_TOOLS = setOf(CmdTool.CLONE, CmdTool.OWNER, CmdTool.LINK_ADD, CmdTool.LINK_REMOVE)

/** A pointer drag in progress (Area, Move, Ruler), in screen pixels. */
private class Drag(val start: Offset, var now: Offset, val ref: CmdRef?, val additive: Boolean)

/** The context menu: where, what is under it, and where on the map. */
private class MenuAt(val at: Offset, val ref: CmdRef?, val north: Double, val east: Double)

@Composable
internal fun CommandMap(ctx: CmdContext, mapState: MapState, modifier: Modifier = Modifier) {
    val doc = ctx.doc
    @Suppress("UNUSED_VARIABLE") val v = CommandSession.version
    val world = doc.world
    val layers = CmdLayers.prefs
    val tool = CommandSession.tool
    val sel = ctx.selection
    val frame = remember(doc) { MapFrame() }
    val tm = rememberTextMeasurer(cacheSize = 256)

    // the base map: the theater's own picture (the app's bundled levels, deeper from the PC)
    val imagePath by produceState<String?>(doc.mapId?.let { "maps/$it/relief.webp" }, doc) {
        value = runCatching { Repo.theater(doc.theaterId)?.map }.getOrNull() ?: doc.mapId?.let { "maps/$it/relief.webp" }
    }
    // the threat reference, for the air-defence rings (the theater's own `Ppt.ini` ranges when known)
    val threats by produceState(emptyList<com.bmscompanion.app.data.Threat>(), doc) { value = runCatching { Repo.threats() }.getOrDefault(emptyList()) }
    remember(world, threats) {
        if (threats.isNotEmpty()) world.units.forEach { u -> u.system?.let { s -> u.ringFt = runCatching { siteRingFt(s, emptyList(), threats) }.getOrNull() } }
        0
    }
    // the side every symbol's frame is drawn from
    val seen = remember(doc, layers.seenFrom, CommandSession.version) {
        val team = layers.seenFrom.takeIf { it > 0 } ?: playerTeam(doc)
        runCatching { Sides(doc.core, team) }.getOrNull()
    }
    val icons = remember(doc, layers.bmsIcons) { if (layers.bmsIcons) CampMapIcons.forTheater(doc.set, doc.theater) else null }
    val iconCache = remember(doc) { HashMap<String, ImageBitmap?>() }
    val style = CmdLayers.styleFor(doc.theaterId)

    var cursor by remember { mutableStateOf<Offset?>(null) }
    var drag by remember { mutableStateOf<Drag?>(null) }
    var ruler by remember { mutableStateOf<Pair<Pair<Double, Double>, Pair<Double, Double>>?>(null) }
    var linkFirst by remember { mutableStateOf<CmdRef?>(null) }
    var menu by remember { mutableStateOf<MenuAt?>(null) }
    var shift by remember { mutableStateOf(false) }

    DisposableEffect(doc, mapState) {
        CommandSession.flyHook = { ref ->
            world.place(ref)?.let { (n, e) -> mapState.flyToSpan(n, e, if (ref.kind == CmdKind.WAYPOINT) 30.0 else 60.0) }
        }
        CommandSession.screenHook = { ref -> frame.hits.firstOrNull { it.ref == ref && !it.hidden }?.let { frame.origin + Offset(it.x, it.y) } }
        onDispose { CommandSession.flyHook = null; CommandSession.screenHook = null }
    }

    fun pick(p: Offset, maxPx: Float = 12f): CmdRef? {
        var best: Hit? = null
        var bestD = Float.MAX_VALUE
        for (h in frame.hits) {
            if (h.hidden) continue
            val d = hypot(h.x - p.x, h.y - p.y) - h.r - h.prio * 1.5f
            if (d < bestD) { bestD = d; best = h }
        }
        return best?.takeIf { bestD <= maxPx }?.ref
    }

    fun theaterAt(p: Offset): Pair<Double, Double>? = frame.pr?.toTheater(p)

    fun tap(p: Offset) {
        val ref = pick(p)
        val at = theaterAt(p) ?: return
        val edits = CommandSlots.mapEdits
        when (tool) {
            CmdTool.SELECT, CmdTool.AREA, CmdTool.MOVE, CmdTool.RULER -> {
                if (ref == null) { if (!shift) sel.clear() } else if (shift) sel.toggle(ref) else sel.set(ref)
            }
            CmdTool.CLONE -> {
                val src = sel.primary
                CommandSession.status = when {
                    src == null || src.kind == CmdKind.OBJECTIVE -> "Select the unit to clone first, then click where the copy goes."
                    edits == null -> "Cloning needs the editor, which is not installed in this build yet."
                    else -> edits.clone(src, at.first, at.second) ?: "Done: cloned ${world.nameOf(src)}.".also { ctx.changed() }
                }
            }
            CmdTool.LINK_ADD, CmdTool.LINK_REMOVE -> {
                if (ref == null || ref.kind != CmdKind.OBJECTIVE) { CommandSession.status = "Click an objective."; return }
                val a = linkFirst
                if (a == null || a == ref) { linkFirst = ref; CommandSession.status = "${world.nameOf(ref)}: now click the objective to ${if (tool == CmdTool.LINK_ADD) "link it to" else "unlink from it"}."; return }
                linkFirst = null
                CommandSession.status = when {
                    edits == null -> "Links are edited by the editor, which is not installed in this build yet."
                    tool == CmdTool.LINK_ADD -> edits.addLink(a, ref) ?: "Done: linked ${world.nameOf(a)} and ${world.nameOf(ref)}.".also { ctx.changed() }
                    else -> edits.removeLink(a, ref) ?: "Done: unlinked ${world.nameOf(a)} and ${world.nameOf(ref)}.".also { ctx.changed() }
                }
            }
            CmdTool.OWNER -> {
                if (ref == null) return
                val team = CommandSession.ownerTeam
                CommandSession.status = if (edits == null) "Owners are set by the editor, which is not installed in this build yet."
                else edits.setOwner(listOf(ref), team) ?: "Done: ${world.nameOf(ref)} now belongs to ${world.team(team)?.name ?: "team $team"}.".also { ctx.changed() }
            }
        }
    }

    fun finishDrag(d: Drag) {
        val pr = frame.pr ?: return
        when (tool) {
            CmdTool.AREA -> {
                val r = Rect(min(d.start.x, d.now.x), min(d.start.y, d.now.y), max(d.start.x, d.now.x), max(d.start.y, d.now.y))
                val inside = frame.hits.filter { r.contains(Offset(it.x, it.y)) }.map { it.ref }.distinct()
                if (d.additive) sel.setAll(sel.refs + inside) else sel.setAll(inside)
                CommandSession.status = "${inside.size} selected."
            }
            CmdTool.MOVE -> {
                val ref = d.ref ?: return
                val (n, e) = pr.toTheater(d.now)
                val edits = CommandSlots.mapEdits
                CommandSession.status = if (edits == null) "Moving needs the editor, which is not installed in this build yet."
                else edits.move(ref, n, e) ?: "Done: moved ${world.nameOf(ref)}.".also { ctx.changed() }
            }
            CmdTool.RULER -> ruler = pr.toTheater(d.start) to pr.toTheater(d.now)
            else -> Unit
        }
    }

    // the pointer loop below lives across compositions (keyed on the tool only), so it calls the latest tap and drag end
    val tapNow by rememberUpdatedState<(Offset) -> Unit> { p -> tap(p) }
    val finishNow by rememberUpdatedState<(Drag) -> Unit> { d -> finishDrag(d) }

    // Save picture: the map as drawn (base, layers, ruler), recorded as it draws; the zoom buttons step aside for the shot
    val shot = rememberGraphicsLayer()
    var shooting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val savePicture: () -> Unit = { scope.launch { saveMapPicture(ctx, shot, setShooting = { shooting = it }) } }

    Column(modifier) {
        MapToolStrip(ctx, mapState, world, onClearRuler = { ruler = null; linkFirst = null }, onSavePicture = savePicture)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            TheaterMap(
                imagePath = imagePath,
                sizeFt = world.sizeFt,
                modifier = Modifier.fillMaxSize()
                    .drawWithContent { shot.record { this@drawWithContent.drawContent() }; drawLayer(shot) }
                    .onGloballyPositioned { frame.origin = it.positionInWindow() }
                    .onPreviewKeyEvent { e -> if (e.key == Key.Escape) { drag = null; ruler = null; linkFirst = null; menu = null }; false }
                    .pointerInput(tool) {
                        awaitPointerEventScope {
                            // a click of a tool that edits (Clone, Owner, Link ±) acts on its release: the map's own tap
                            // waits out the double-tap time first, so the edit landed one click late
                            var clickAt: Offset? = null
                            while (true) {
                                val e = awaitPointerEvent(PointerEventPass.Initial)
                                val ch = e.changes.firstOrNull() ?: continue
                                shift = e.keyboardModifiers.isShiftPressed
                                when (e.type) {
                                    PointerEventType.Move, PointerEventType.Enter -> {
                                        cursor = ch.position
                                        val d = drag
                                        if (d != null) { d.now = ch.position; drag = Drag(d.start, ch.position, d.ref, d.additive); ch.consume() }
                                        else sel.hover = pick(ch.position)
                                        clickAt?.let { c -> if (hypot(ch.position.x - c.x, ch.position.y - c.y) >= 4f) clickAt = null } // a pan
                                    }
                                    PointerEventType.Exit -> { cursor = null; sel.hover = null }
                                    PointerEventType.Press -> {
                                        clickAt = if (e.buttons.isPrimaryPressed && !e.buttons.isSecondaryPressed && tool in CLICK_TOOLS) ch.position else null
                                        if (e.buttons.isSecondaryPressed && !e.buttons.isPrimaryPressed) {
                                            ch.consume()
                                            val at = theaterAt(ch.position)
                                            val ref = pick(ch.position)
                                            if (ref != null && ref !in sel) sel.set(ref)
                                            if (at != null) menu = MenuAt(ch.position, ref, at.first, at.second)
                                        } else if (e.buttons.isPrimaryPressed && (tool == CmdTool.AREA || tool == CmdTool.RULER || tool == CmdTool.MOVE)) {
                                            val ref = if (tool == CmdTool.MOVE) pick(ch.position)?.takeIf { it.kind != CmdKind.DIVISION && it.kind != CmdKind.PACKAGE } else null
                                            if (tool == CmdTool.MOVE && ref == null) continue // nothing to move: the press pans
                                            if (ref != null) sel.set(ref)
                                            drag = Drag(ch.position, ch.position, ref, shift)
                                            ch.consume()
                                        }
                                    }
                                    PointerEventType.Release -> {
                                        val d = drag
                                        val c = clickAt
                                        clickAt = null
                                        if (d != null) {
                                            ch.consume()
                                            drag = null
                                            if (hypot(d.now.x - d.start.x, d.now.y - d.start.y) < 4f) tapNow(d.start) else finishNow(d)
                                        } else if (c != null && hypot(ch.position.x - c.x, ch.position.y - c.y) < 4f) {
                                            ch.consume() // the map's own tap then sees a consumed release and does nothing
                                            tapNow(c)
                                        }
                                    }
                                    else -> Unit
                                }
                            }
                        }
                    },
                state = mapState,
                maxScale = 400f,
                landmarks = layers.landmarks && !com.bmscompanion.app.ui.components.BmsChart.isChart(style),
                style = style,
                mission = false,
                // the theater covers the map's box at every zoom (fitted inside a wide box it left dark bands down both sides)
                fillBox = true,
                zoomButtons = !shooting,
                onTap = { x, y, pr -> frame.pr = pr; if (CommandSession.tool !in CLICK_TOOLS) tapNow(pr.toScreen(x, y)) },
            ) { pr ->
                frame.pr = pr
                frame.begin()
                inMapInks(MapLook.isLight(style)) {
                    drawWorld(pr, ctx, world, layers, seen, icons, iconCache, tm, frame)
                    // tools
                    drag?.let { d ->
                        when (tool) {
                            CmdTool.AREA -> {
                                val r = Rect(min(d.start.x, d.now.x), min(d.start.y, d.now.y), max(d.start.x, d.now.x), max(d.start.y, d.now.y))
                                drawRect(Hud.Amber.copy(alpha = 0.12f), r.topLeft, r.size)
                                drawRect(Hud.Amber, r.topLeft, r.size, style = Stroke(1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f))))
                            }
                            CmdTool.MOVE -> {
                                drawLine(Hud.Amber, d.start, d.now, 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 4f)))
                                drawCircle(Hud.Amber, 7f, d.now, style = Stroke(2f))
                            }
                            CmdTool.RULER -> drawRuler(pr, pr.toTheater(d.start), pr.toTheater(d.now), tm)
                            else -> Unit
                        }
                    }
                    if (drag == null) ruler?.let { (a, b) -> drawRuler(pr, a, b, tm) }
                    linkFirst?.let { a -> world.place(a)?.let { (n, e) -> drawCircle(Hud.Amber, 11f, pr.toScreen(n, e), style = Stroke(2.5f)) } }
                }
                frame.end()
            }
            // the hover box: what the mouse is over
            val hov = sel.hover
            val cur = cursor
            if (hov != null && cur != null && drag == null && menu == null) HoverBox(world, doc, hov, cur)
            // the context menu
            menu?.let { m -> ContextMenu(ctx, m, onClose = { menu = null }, onRulerFrom = { ruler = (m.north to m.east) to (m.north to m.east); CommandSession.tool = CmdTool.RULER }) }
            // the readout under the mouse
            Readout(cursor?.let { theaterAt(it) }, ruler, Modifier.align(Alignment.BottomStart))
        }
    }
}

/** The player's team: the player squadron's owner, else a player flight's, else team 1. */
internal fun playerTeam(doc: CmdDocument): Int {
    val s = doc.core
    return s.unit(s.cmp?.playerSquadronId)?.owner ?: s.flights.firstOrNull { f -> f.playerSlots.any { it != 255 } }?.owner
        ?: doc.world.teams.firstOrNull { it.inUse && it.n > 0 }?.n ?: 1
}

// ------------------------------------------------------------------ drawing

private fun teamColour(world: CmdWorld, owner: Int): Int = world.team(owner)?.colour ?: owner

private val Outline = Color(0xE0101418)
private val LightOutline = Color(0xE0F4F6F8)

/** Draws the whole file under [layers], filling [frame] with what was drawn. */
private fun DrawScope.drawWorld(
    pr: MapProjection, ctx: CmdContext, world: CmdWorld, layers: CmdLayerPrefs, seen: Sides?,
    icons: CampMapIcons.IconSet?, iconCache: HashMap<String, ImageBitmap?>, tm: androidx.compose.ui.text.TextMeasurer, frame: MapFrame,
) {
    val sel = ctx.selection
    val nm = pr.pxPerNm
    val w = size.width
    val h = size.height
    val light = Hud.onLightMap
    val edge = if (light) LightOutline else Outline
    val labelInk = if (light) Color(0xFF15191E) else Color(0xFFF2F4F6)
    val labelHalo = if (light) Color(0xCCFFFFFF) else Color(0xCC000000)
    fun on(p: Offset, m: Float = 40f) = p.x > -m && p.y > -m && p.x < w + m && p.y < h + m
    fun teamShown(owner: Int) = owner !in layers.hiddenTeams
    val placed = ArrayList<Rect>()
    fun label(text: String, at: Offset, size: Float = 11f, bold: Boolean = false, ink: Color = labelInk) {
        if (!layers.names || placed.size > 700) return
        val style = TextStyle(color = ink, fontSize = size.sp, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal, shadow = androidx.compose.ui.graphics.Shadow(labelHalo, Offset.Zero, 3f))
        val l = MapText.label(this, tm, text, style)
        val r = Rect(at.x, at.y - l.height / 2f, at.x + l.width, at.y + l.height / 2f)
        if (r.right < 0 || r.left > w || r.bottom < 0 || r.top > h) return
        for (k in placed.indices) if (placed[k].overlaps(r)) return
        placed += r
        MapText.draw(this, l, r.topLeft)
    }

    val selectedObjectives = sel.refs.filter { it.kind == CmdKind.OBJECTIVE }.mapNotNull { world.objective(it.id) }

    // ---- air-defence rings (under everything)
    if (layers.adRings) for (u in world.units) {
        val ring = u.ringFt ?: continue
        if (u.symbol != "airdefence" || !teamShown(u.owner)) continue
        if (layers.adTeams.isNotEmpty() && u.owner !in layers.adTeams) continue
        val rnm = ring / FT_PER_NM
        val band = when { rnm >= 20 -> 0; rnm >= 8 -> 1; else -> 2 }
        if ((band == 0 && !layers.adLong) || (band == 1 && !layers.adMedium) || (band == 2 && !layers.adShort)) continue
        val c = pr.toScreen(u.north, u.east)
        val r = (rnm * nm).toFloat()
        if (c.x + r < 0 || c.y + r < 0 || c.x - r > w || c.y - r > h || r < 2f) continue
        val ink = teamInk(teamColour(world, u.owner))
        drawCircle(ink.copy(alpha = 0.07f), r, c)
        drawCircle(ink.copy(alpha = 0.75f), r, c, style = Stroke(1.3f))
    }

    // ---- objectives: which are shown
    val objShown = if (!layers.objectives) emptyList() else world.objectives.filter { o ->
        val owner = when (layers.ownerView) { OwnerView.FIRST -> o.firstOwner; else -> o.owner }
        o.group.name in layers.groups && teamShown(owner) &&
            (!layers.frontLine || o.frontLine == 1) && (!layers.secondLine || o.frontLine == 2) && (!layers.thirdLine || o.frontLine == 3) &&
            (!layers.samCapable || o.samCapable) && (!layers.noLinks || o.links?.isEmpty() == true) &&
            (o.priority == null || o.priority >= layers.minPriority)
    }

    // ---- links and parents
    val linkInk = if (light) Color(0xFF2B6E2B) else Color(0xFF9BE89B)
    fun linkLines(o: ViewObjective) {
        val a = pr.toScreen(o.north, o.east)
        o.links?.forEach { id ->
            val b = world.objective(id.toString()) ?: return@forEach
            if (layers.links == LinkView.ALL && id.toString() < o.ref.id) return@forEach // each link once
            val q = pr.toScreen(b.north, b.east)
            if (!on(a, 2000f) && !on(q, 2000f)) return@forEach
            val long = hypot(o.north - b.north, o.east - b.east) / FT_PER_NM > 60
            drawLine(edge.copy(alpha = 0.6f), a, q, 3f)
            drawLine(if (long) Color(0xFFF08A1C) else linkInk, a, q, 1.3f)
        }
    }
    when (layers.links) {
        LinkView.ALL -> objShown.forEach { linkLines(it) }
        LinkView.SELECTED -> selectedObjectives.forEach { linkLines(it) }
        LinkView.OFF -> Unit
    }
    if (layers.parents) for (o in objShown) {
        val p = world.objective(o.parent.toString()) ?: continue
        val a = pr.toScreen(o.north, o.east)
        if (!on(a)) continue
        drawLine(Hud.Magenta.copy(alpha = 0.7f), a, pr.toScreen(p.north, p.east), 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 3f)))
    }

    // ---- objectives: the app's symbols and BMS's icons grow with the zoom (BMS's never past its own 1:1), and while
    // the map is far out the less important of two that would overlap is left out (Declutter, on by default)
    val k = (nm / 3f).coerceIn(0.6f, 1.35f)
    val iconScale = (nm / 8f).coerceIn(0.45f, 1f)
    val forced = HashSet<CmdRef>(sel.refs).also { s -> sel.hover?.let { s += it } }
    val tidy = layers.declutter && nm < 20f
    val objRoom = Room(if (!tidy) 0f else if (icons != null) 15f * iconScale + 2f else (4f * k * 2.5f).coerceAtLeast(11f))
    val objDrawn = ArrayList<Pair<ViewObjective, Offset>>()
    for (o in objShown.sortedByDescending { if (it.ref in forced) 10_000 else objectiveRank(it) }) {
        val c = pr.toScreen(o.north, o.east)
        if (!on(c)) continue
        if (o.ref in forced || objRoom.free(c)) { objRoom.take(c); objDrawn += o to c } else frame.next += Hit(o.ref, c.x, c.y, 0f, 0, hidden = true)
    }
    val objR = HashMap<CmdRef, Float>()
    for ((o, c) in objDrawn.asReversed()) {
        val owner = when (layers.ownerView) { OwnerView.FIRST -> o.firstOwner; else -> o.owner }
        val changed = o.owner != o.startOwner
        val dimmed = layers.ownerView == OwnerView.CHANGED && !changed
        val ink = if (dimmed) Color(0xFF8C8C8C).copy(alpha = 0.5f) else teamInk(teamColour(world, owner))
        val icon = icons?.let { iconFor(iconCache, "o${o.ocd}|${teamColour(world, owner)}") { it.objective(o.ocd, teamColour(world, owner)) } }
        val r = if (icon != null) drawIcon(icon.first, icon.second, c, iconScale) else drawObjectiveSymbol(o.type, o.radar, c, 4f * k, ink, edge)
        objR[o.ref] = r
        frame.next += Hit(o.ref, c.x, c.y, r, 0)
    }
    for ((o, c) in objDrawn) {
        val labelAt = when (o.group) {
            ObjGroup.AIRPORTS -> 2.2f
            ObjGroup.CITIES -> if (o.type == 8) 3f else 9f
            else -> 14f
        }
        val r = objR[o.ref] ?: 4f
        if (nm >= labelAt) label(o.name, c + Offset(r + 3f, 0f), if (o.group == ObjGroup.AIRPORTS) 11.5f else 10.5f, o.group == ObjGroup.AIRPORTS)
    }

    // ---- routes (hovered, selected, and every one where asked)
    val routed = LinkedHashSet<ViewUnit>()
    fun addRoute(u: ViewUnit?) { if (u != null && u.route.size > 1) routed += u }
    for (r in sel.refs) {
        val u = world.unit(r.id) ?: continue
        if (u.kind == CmdKind.PACKAGE) u.elements.forEach { addRoute(world.unit(it)) } else addRoute(u)
    }
    sel.hover?.let { hv -> if (layers.routeOnHover) world.unit(hv.id)?.let { u -> if (u.kind == CmdKind.PACKAGE) u.elements.forEach { addRoute(world.unit(it)) } else addRoute(u) } }
    if (layers.groundRoutes) world.units.filter { it.kind == CmdKind.BATTALION && teamShown(it.owner) }.forEach(::addRoute)
    if (layers.allNavalRoutes) world.units.filter { it.kind == CmdKind.TASKFORCE && teamShown(it.owner) }.forEach(::addRoute)
    for (u in routed) drawRoute(pr, u, world, edge, nm, sel, frame, ::label)

    // ---- assignments, brigade and division elements
    fun tie(a: Offset, b: Offset, ink: Color) {
        drawLine(edge.copy(alpha = 0.5f), a, b, 3f)
        drawLine(ink, a, b, 1.4f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f)))
    }
    for (u in world.units) {
        if (u.kind != CmdKind.BATTALION && u.kind != CmdKind.BRIGADE) continue
        val selected = u.ref in sel
        if (!(layers.assignments || selected)) continue
        val o = world.objective(u.objective) ?: continue
        tie(pr.toScreen(u.north, u.east), pr.toScreen(o.north, o.east), Hud.Amber)
    }
    for (r in sel.refs) {
        if (r.kind == CmdKind.BRIGADE && layers.brigadeElements) world.unit(r.id)?.let { b ->
            b.elements.mapNotNull { world.unit(it) }.forEach { e -> tie(pr.toScreen(b.north, b.east), pr.toScreen(e.north, e.east), Hud.Cyan) }
        }
        if (r.kind == CmdKind.DIVISION && layers.divisionElements) world.division(r.id)?.let { d ->
            d.elements.mapNotNull { world.unit(it) }.forEach { e -> tie(pr.toScreen(d.north, d.east), pr.toScreen(e.north, e.east), Hud.Cyan) }
        }
    }

    // ---- ground and naval units (the most important first into the room, drawn last so they lie on top)
    val symbolPx = (nm * 1.6f).coerceIn(11f, 20f)
    val far = nm < 2.5f
    val groundRoom = Room(if (!tidy) 0f else if (icons != null) 15f * iconScale + 2f else if (far) 10f else symbolPx * 1.15f)
    val groundDrawn = ArrayList<Pair<ViewUnit, Offset>>()
    for (u in world.units) {
        val shown = when (u.kind) {
            CmdKind.BATTALION -> layers.battalions && u.symbol in layers.landKinds &&
                (layers.orders < 0 || u.orders == layers.orders) && (!layers.inBrigadeOnly || u.parent != null) &&
                (!layers.movingOnly || u.moving) && (!layers.inactiveOnly || u.inactive)
            CmdKind.BRIGADE -> layers.brigades && (layers.orders < 0 || u.orders == layers.orders) && (!layers.inactiveOnly || u.inactive)
            CmdKind.TASKFORCE -> layers.taskForces && (!layers.inactiveOnly || u.inactive)
            else -> false
        }
        if (!shown || !teamShown(u.owner)) continue
        val c = pr.toScreen(u.north, u.east)
        if (!on(c)) continue
        groundDrawn += u to c
    }
    val groundKept = ArrayList<Pair<ViewUnit, Offset>>()
    for ((u, c) in groundDrawn.sortedByDescending { (u, _) -> if (u.ref in forced) 10_000 else when (u.kind) { CmdKind.TASKFORCE -> 300; CmdKind.BRIGADE -> 200; else -> 100 } + if (u.symbol == "airdefence") 10 else 0 }) {
        if (u.ref in forced || groundRoom.free(c)) { groundRoom.take(c); groundKept += u to c } else frame.next += Hit(u.ref, c.x, c.y, 0f, 1, hidden = true)
    }
    for ((u, c) in groundKept.asReversed()) {
        val colour = teamColour(world, u.owner)
        val side = seen?.of(u.owner) ?: "unknown"
        val icon = icons?.let { iconFor(iconCache, "u${u.ucd}|$colour") { it.unit(u.ucd, colour, air = false) } }
        val sea = u.kind == CmdKind.TASKFORCE
        val r = if (icon != null) drawIcon(icon.first, icon.second, c, iconScale)
        else if (far && u.ref !in forced) { drawUnitDot(side, c, if (u.kind == CmdKind.BATTALION) 2.4f else 3.2f, teamInk(colour), edge); 3.5f }
        else {
            val kind = if (sea && u.symbol !in SEA_UNIT_KINDS) "ship" else u.symbol
            val px = if (u.kind == CmdKind.BRIGADE) symbolPx * 1.3f else symbolPx
            drawUnitSymbol(kind, side, c, px, moving = u.moving, dim = u.dead || u.inactive)
            // the team's own colour, as a dot on the frame's corner (the frame's ink is the side's)
            val dot = c + Offset(px * 0.75f, -px * 0.5f)
            drawCircle(edge, 3.4f, dot); drawCircle(teamInk(colour), 2.5f, dot)
            // the echelon over the frame: II a battalion, X a brigade
            if (!sea) drawEchelon(if (u.kind == CmdKind.BRIGADE) 3 else 2, Offset(c.x, c.y - frameTop(side, px) - 1.5f), px, labelInk, labelHalo)
            px * 0.7f
        }
        frame.next += Hit(u.ref, c.x, c.y, r, if (u.kind == CmdKind.BRIGADE) 2 else 1)
        if (nm >= 14f) label(u.name, c + Offset(r + 4f, r * 0.6f), 10f)
    }
    if (layers.divisions) for (d in world.divisions) {
        if (!teamShown(d.owner)) continue
        val c = pr.toScreen(d.north, d.east)
        if (!on(c)) continue
        val ink = teamInk(teamColour(world, d.owner))
        val s = (symbolPx * 1.45f)
        drawRect(edge, c - Offset(s * 0.75f + 1.5f, s * 0.5f + 1.5f), Size(s * 1.5f + 3f, s + 3f))
        drawRect(ink.copy(alpha = 0.85f), c - Offset(s * 0.75f, s * 0.5f), Size(s * 1.5f, s))
        val t = MapText.label(this, tm, "${d.number}", TextStyle(color = glyphOn(ink), fontSize = 10.sp, fontWeight = FontWeight.Bold))
        MapText.draw(this, t, c - Offset(t.width / 2f, t.height / 2f))
        drawEchelon(4, Offset(c.x, c.y - s * 0.5f - 3f), s, labelInk, labelHalo)
        frame.next += Hit(d.ref, c.x, c.y, s * 0.75f, 3)
    }

    // ---- air: squadrons round their base, flights where they are
    if (layers.squadrons) {
        val atBase = world.units.filter { it.kind == CmdKind.SQUADRON && teamShown(it.owner) && it.role.name in layers.roles }
            .groupBy { (it.north / 3000).roundToInt() to (it.east / 3000).roundToInt() }
        // far out (Declutter on) a base's squadrons are one symbol with their count; closer they fan out round it
        val merge = layers.declutter && nm < 4f
        for ((_, group) in atBase) {
            val base0 = pr.toScreen(group[0].north, group[0].east)
            if (!on(base0)) continue
            val shownHere = if (merge && group.size > 1) listOf(group.firstOrNull { it.ref in forced } ?: group[0]) else group
            if (merge) group.forEach { u -> if (u !in shownHere) frame.next += Hit(u.ref, base0.x, base0.y, 0f, 2, hidden = true) }
            for ((i, u) in shownHere.withIndex()) {
                val base = pr.toScreen(u.north, u.east)
                val spread = if (shownHere.size > 1 && nm >= 2f) (12f + shownHere.size * 1.5f) * k.coerceAtMost(1f) / 0.9f else 0f
                val ang = 2 * PI * i / shownHere.size - PI / 2
                val c = base + Offset((spread * cos(ang)).toFloat(), (spread * sin(ang)).toFloat())
                val colour = teamColour(world, u.owner)
                val icon = icons?.let { iconFor(iconCache, "a${u.ucd}|$colour") { it.unit(u.ucd, colour, air = true) } }
                val r = if (icon != null) drawIcon(icon.first, icon.second, c, iconScale) else drawSquadronSymbol(u.role, c, (5f * k).coerceAtLeast(3.8f), teamInk(colour), edge)
                frame.next += Hit(u.ref, c.x, c.y, r, 2)
                if (merge && group.size > 1) {
                    // the count of squadrons at this base, on the symbol's lower right
                    val t = MapText.label(this, tm, "${group.size}", TextStyle(color = labelInk, fontSize = 9.5.sp, fontWeight = FontWeight.Bold, shadow = androidx.compose.ui.graphics.Shadow(labelHalo, Offset.Zero, 3f)))
                    MapText.draw(this, t, c + Offset(r * 0.6f, r * 0.2f))
                }
                if (nm >= 8f) label(u.aircraft ?: u.cls, c + Offset(r + 3f, 0f), 10f)
            }
        }
    }
    if (layers.flights) {
        val flightRoom = Room(if (!tidy) 0f else if (icons != null) 14f * iconScale + 2f else 9f * k)
        val flights = world.units.filter { u -> u.kind == CmdKind.FLIGHT && teamShown(u.owner) && u.role.name in layers.roles }
            .map { it to pr.toScreen(it.north, it.east) }.filter { on(it.second) }
            .sortedByDescending { (u, _) -> if (u.ref in forced) 1 else 0 }
        val kept = ArrayList<Pair<ViewUnit, Offset>>()
        for ((u, c) in flights) if (u.ref in forced || flightRoom.free(c)) { flightRoom.take(c); kept += u to c } else frame.next += Hit(u.ref, c.x, c.y, 0f, 3, hidden = true)
        for ((u, c) in kept.asReversed()) {
            val next = u.route.getOrNull(u.currentWp) ?: u.route.lastOrNull()
            val heading = next?.let { Math.toDegrees(atan2(it.east - u.east, it.north - u.north)) } ?: 0.0
            val colour = teamColour(world, u.owner)
            val icon = icons?.let { set -> val hd = CampMapIcons.headingName(heading); iconFor(iconCache, "f${u.ucd}|$colour|$hd") { set.unit(u.ucd, colour, air = true, heading = hd) } }
            val r = if (icon != null) drawIcon(icon.first, icon.second, c, iconScale) else drawAircraft(u.role, c, heading, 6f * k.coerceAtLeast(0.8f), teamInk(colour), edge)
            frame.next += Hit(u.ref, c.x, c.y, r, 3)
            if (nm >= 10f) label(u.name, c + Offset(r + 3f, 0f), 10f)
        }
    }

    // ---- the selection, on top
    for (r in sel.refs) {
        val p = world.place(r) ?: continue
        val c = pr.toScreen(p.first, p.second)
        if (!on(c)) continue
        drawCircle(Color.Black.copy(alpha = 0.5f), 12f, c, style = Stroke(4f))
        drawCircle(Hud.Amber, 12f, c, style = Stroke(2f))
    }
    sel.hover?.let { r -> world.place(r)?.let { p -> drawCircle(Hud.Amber.copy(alpha = 0.6f), 10f, pr.toScreen(p.first, p.second), style = Stroke(1.2f)) } }
}

/** A BMS icon from the cache (made once): the picture and its hot spot. */
internal fun iconFor(cache: HashMap<String, ImageBitmap?>, key: String, load: () -> CampMapIcons.Icon?): Pair<ImageBitmap, Offset>? {
    val img = if (cache.containsKey(key)) cache[key] else load()?.let { ic ->
        val bytes = ByteArray(ic.argb.size * 4)
        for (i in ic.argb.indices) {
            val v = ic.argb[i]
            bytes[i * 4] = (v and 0xFF).toByte(); bytes[i * 4 + 1] = ((v shr 8) and 0xFF).toByte()
            bytes[i * 4 + 2] = ((v shr 16) and 0xFF).toByte(); bytes[i * 4 + 3] = ((v ushr 24) and 0xFF).toByte()
        }
        runCatching { org.jetbrains.skia.Image.makeRaster(ImageInfo.makeN32(ic.w, ic.h, ColorAlphaType.PREMUL), bytes, ic.w * 4).toComposeImageBitmap() }.getOrNull()
            .also { b -> if (b != null) hotspots[b] = Offset(ic.cx.toFloat(), ic.cy.toFloat()) }
    }.also { cache[key] = it }
    return img?.let { it to (hotspots[it] ?: Offset(it.width / 2f, it.height / 2f)) }
}

private val hotspots = java.util.WeakHashMap<ImageBitmap, Offset>()

/**
 * Draws [img] with its hot spot on [c] at [scale] (1 = BMS's own 1:1 pixels, whole and sharp; below 1 smoothed down,
 * so far out the icons stay small); answers its pick radius.
 */
internal fun DrawScope.drawIcon(img: ImageBitmap, hot: Offset, c: Offset, scale: Float): Float {
    val s = scale.coerceIn(0.2f, 1f)
    val w = (img.width * s).roundToInt().coerceAtLeast(1)
    val h = (img.height * s).roundToInt().coerceAtLeast(1)
    val tl = IntOffset((c.x - hot.x * s).roundToInt(), (c.y - hot.y * s).roundToInt())
    drawImage(img, IntOffset.Zero, IntSize(img.width, img.height), tl, IntSize(w, h), filterQuality = if (s >= 0.99f) FilterQuality.None else FilterQuality.Medium)
    return max(w, h) / 2f
}

/** Which objectives stay when the map is too far out to show them all: airbases and cities first, then by priority. */
private fun objectiveRank(o: ViewObjective): Int = when (o.type) {
    1 -> 400; 8 -> 330; 2 -> 260; 31 -> 220; 21 -> 210; 28 -> 190; 19 -> 170; 9 -> 160; 3, 13, 30 -> 150
    17, 20 -> 140; 7, 11, 25 -> 130; 10, 23 -> 120; 22 -> 110; 6 -> 80; 29 -> 60
    else -> 30
} + (o.priority ?: 0)

/** How far above a unit's centre its frame of [side] ends, for a frame [px] high (UnitSymbols' frames). */
private fun frameTop(side: String, px: Float): Float = when (side) { "hostile" -> px * 0.72f; "friendly", "neutral" -> px * 0.5f; else -> px * 0.6f }

/** A unit's route: legs, waypoints (numbered when close), the leg it is flying lit. */
private fun DrawScope.drawRoute(
    pr: MapProjection, u: ViewUnit, world: CmdWorld, edge: Color, nm: Float, sel: CmdSelection, frame: MapFrame,
    label: (String, Offset, Float, Boolean, Color) -> Unit,
) {
    val ink = teamInk(teamColour(world, u.owner)).let { if (it.red + it.green + it.blue > 2.6f) Color(0xFFE8E8E8) else it }
    val pts = u.route.map { pr.toScreen(it.north, it.east) }
    for (i in 0 until pts.size - 1) {
        drawLine(edge.copy(alpha = 0.7f), pts[i], pts[i + 1], 4f, cap = StrokeCap.Round)
        drawLine(if (i + 1 == u.currentWp) Hud.Amber else ink, pts[i], pts[i + 1], 2f, cap = StrokeCap.Round)
    }
    for ((i, p) in pts.withIndex()) {
        drawCircle(edge, 4.2f, p); drawCircle(ink, 3f, p)
        frame.next += Hit(CmdRef(CmdKind.WAYPOINT, u.ref.id, i), p.x, p.y, 3f, -1)
        if (nm >= 3f) label("${i + 1}", p + Offset(5f, -6f), 9.5f, true, ink)
    }
}

/** The ruler: a line from [a] to [b] with its true track and distance. */
private fun DrawScope.drawRuler(pr: MapProjection, a: Pair<Double, Double>, b: Pair<Double, Double>, tm: androidx.compose.ui.text.TextMeasurer) {
    val p = pr.toScreen(a.first, a.second)
    val q = pr.toScreen(b.first, b.second)
    drawLine(Color.Black.copy(alpha = 0.6f), p, q, 4f)
    drawLine(Hud.Amber, p, q, 2f)
    drawCircle(Hud.Amber, 3.5f, p); drawCircle(Hud.Amber, 3.5f, q)
    val text = rulerText(a, b)
    val l = MapText.label(this, tm, text, TextStyle(color = Hud.Amber, fontSize = 12.sp, fontWeight = FontWeight.Bold, shadow = androidx.compose.ui.graphics.Shadow(Color.Black, Offset.Zero, 3f)))
    MapText.draw(this, l, q + Offset(8f, -l.height - 2f))
}

internal fun rulerText(a: Pair<Double, Double>, b: Pair<Double, Double>): String {
    val dn = b.first - a.first
    val de = b.second - a.second
    val trk = ((Math.toDegrees(atan2(de, dn)) + 360) % 360).roundToInt() % 360
    return "TRK ${trk.toString().padStart(3, '0')}° · ${"%.1f".format(hypot(dn, de) / FT_PER_NM)} nm"
}

// ------------------------------------------------------------------ chrome over the map

/** The tools strip over the map: the tools (with their letters), the Owner tool's team, Find, the whole theater. */
@Composable
private fun MapToolStrip(ctx: CmdContext, mapState: MapState, world: CmdWorld, onClearRuler: () -> Unit, onSavePicture: () -> Unit) {
    Strip {
        CmdTool.entries.forEach { t ->
            ToolChip("${t.label}", CommandSession.tool == t, "${t.hint}  (${t.key})") { CommandSession.tool = t; onClearRuler() }
        }
        VRule()
        FindBox(ctx, world)
        Box(Modifier.weight(1f))
        // (the two together, so a narrow strip takes them onto its next line as a pair)
        Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            ToolChip("Save picture…", false, "Save the map as it is shown now as a PNG picture, where you pick (never into the Falcon BMS folder)") { onSavePicture() }
            ToolChip("Zoom out", false, "Zoom out as far as the map goes: the theater fills the map's box") { mapState.flyTo(world.sizeFt / 2, world.sizeFt / 2, 1f) }
        }
    }
    // the Owner tool's teams on a line of their own (beside the tools they pushed Find and the buttons off the strip)
    if (CommandSession.tool == CmdTool.OWNER) Strip(Hud.Surface.copy(alpha = 0.35f)) {
        Text("Owner tool: a click gives the objective or unit to", color = Hud.TextDim, fontSize = 12.sp)
        world.teams.filter { it.inUse }.forEach { tm ->
            MiniChip(tm.name, CommandSession.ownerTeam == tm.n, teamInk(tm.colour), lead = { TeamFlagLead(tm.name, tm.colour) }) { CommandSession.ownerTeam = tm.n }
        }
    }
}

/**
 * Save picture: the map view as drawn now (the zoom buttons left out) into a PNG where the pilot picks in Windows' own
 * Save dialog (Pictures to begin with), refused inside a Falcon BMS folder ([CommandExport.writeBytes]); never throws.
 */
private suspend fun saveMapPicture(ctx: CmdContext, layer: GraphicsLayer, setShooting: (Boolean) -> Unit) {
    val png = try {
        setShooting(true)
        withFrameNanos { }; withFrameNanos { }
        val img = layer.toImageBitmap()
        if (img.width <= 0 || img.height <= 0) null
        else org.jetbrains.skia.Image.makeFromBitmap(img.asSkiaBitmap()).use { it.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)?.bytes }
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) {
        CommandSession.error = "The map could not be drawn to a picture: ${e.message ?: e.javaClass.simpleName}"; return
    } finally { setShooting(false) }
    if (png == null) { CommandSession.error = "The map is not on screen: nothing to save."; return }
    val home = System.getProperty("user.home") ?: "."
    val start = listOf("Pictures", "Documents").map { java.io.File(home, it) }.firstOrNull { it.isDirectory } ?: java.io.File(home)
    val req = FileRequest(FileMode.SAVE, "Save the map as a picture", start.path, "${ctx.doc.file.nameWithoutExtension} map.png",
        listOf(FileFilter("PNG picture (*.png)", "png")), 0, "png")
    when (val a = PcFileDialogs.show(req)) {
        is NativeAnswer.Picked -> {
            val why = CommandExport.writeBytes(java.io.File(a.path), png)
            if (why != null) CommandSession.error = why else ctx.say("The map picture was saved to ${a.path}.")
        }
        is NativeAnswer.Failed -> CommandSession.error = "The Save dialog could not be shown: ${a.why}"
        else -> Unit
    }
}

/** Find by name or CampId, any kind: the matches drop down; Enter takes the first. */
@Composable
private fun FindBox(ctx: CmdContext, world: CmdWorld) {
    var q by remember { mutableStateOf("") }
    var open by remember { mutableStateOf(false) }
    val matches = remember(q, world) {
        val s = q.trim()
        if (s.length < 2 && s.toIntOrNull() == null) emptyList() else {
            val n = s.toIntOrNull()
            val objs = world.objectives.filter { (n != null && it.campId == n) || it.name.contains(s, ignoreCase = true) }.map { it.ref to "${it.name} · ${it.typeName} · #${it.campId}" }
            val units = world.units.filter { (n != null && it.campId == n) || it.name.contains(s, ignoreCase = true) || it.cls.contains(s, ignoreCase = true) }
                .map { it.ref to "${it.name} · ${it.kind.label} · #${it.campId}" }
            (objs + units).take(14)
        }
    }
    Box {
        BasicTextField(
            q, { q = it; open = true },
            singleLine = true,
            textStyle = TextStyle(color = Hud.Text, fontSize = 12.sp),
            cursorBrush = SolidColor(Hud.Amber),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { matches.firstOrNull()?.let { ctx.focus(it.first); open = false } }),
            modifier = Modifier.width(200.dp).clip(RoundedCornerShape(3.dp)).background(Hud.Bg).border(1.dp, Hud.Outline, RoundedCornerShape(3.dp))
                .padding(horizontal = 7.dp, vertical = 4.dp)
                .onPreviewKeyEvent { e -> if (e.key == Key.Enter && e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown) { matches.firstOrNull()?.let { ctx.focus(it.first); open = false }; true } else false },
            decorationBox = { inner -> Box { if (q.isEmpty()) Text("Find: name or CampId", color = Hud.TextFaint, fontSize = 12.sp, maxLines = 1, softWrap = false); inner() } },
        )
        DropdownMenu(open && matches.isNotEmpty(), onDismissRequest = { open = false }, modifier = Modifier.background(Hud.Surface)) {
            matches.forEach { (ref, text) ->
                DropdownMenuItem(text = { Text(text, fontSize = 12.sp, color = Hud.Text) }, onClick = { ctx.focus(ref); open = false }, modifier = Modifier.height(28.dp))
            }
        }
    }
}

/** What the mouse is over, beside it. */
@Composable
private fun HoverBox(world: CmdWorld, doc: CmdDocument, ref: CmdRef, at: Offset) {
    val lines = hoverLines(world, doc, ref)
    if (lines.isEmpty()) return
    val owner = when (ref.kind) {
        CmdKind.OBJECTIVE -> world.objective(ref.id)?.owner
        CmdKind.DIVISION -> world.division(ref.id)?.owner
        else -> world.unit(ref.id)?.owner
    }
    Column(
        Modifier.offset { IntOffset(at.x.roundToInt() + 16, at.y.roundToInt() + 14) }
            .widthIn(max = 320.dp).clip(RoundedCornerShape(4.dp)).background(Hud.Surface.copy(alpha = 0.96f))
            .border(1.dp, Hud.Outline, RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 5.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (owner != null) TeamFlagLead(world.team(owner)?.name, world.team(owner)?.colour ?: owner, 12.dp)
            Text(lines[0], color = Hud.Text, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
        }
        lines.drop(1).forEach { Text(it, color = Hud.TextDim, fontSize = 11.5.sp) }
    }
}

/** The hover box's lines for [ref]: its name first, then the facts of its kind. */
internal fun hoverLines(world: CmdWorld, doc: CmdDocument, ref: CmdRef): List<String> {
    fun team(n: Int) = world.team(n)?.name ?: "team $n"
    val strings = doc.names.strings
    return when (ref.kind) {
        CmdKind.OBJECTIVE -> world.objective(ref.id)?.let { o ->
            listOfNotNull(
                o.name,
                "${o.typeName} · #${o.campId} · ${team(o.owner)}" + if (o.firstOwner != o.owner) " (first ${team(o.firstOwner)})" else "",
                when (o.frontLine) { 1 -> "Front line"; 2 -> "Second line"; 3 -> "Third line"; else -> null },
                o.priority?.let { "Priority $it" },
                o.links?.let { "${it.size} links" },
                if (o.owner != o.startOwner) "Owner changed in this save" else null,
            )
        }
        CmdKind.WAYPOINT -> world.unit(ref.id)?.let { u ->
            val w = u.route.getOrNull(ref.sub) ?: return@let null
            listOfNotNull(
                "${u.name} · waypoint ${ref.sub + 1}",
                strings[CampaignArchive.ACTION_STRINGS + w.action]?.trim()?.takeIf { it.isNotEmpty() },
                "${w.altFt.roundToInt()} ft" + if (w.arrive > 0) " · ${CampaignArchive.clock(w.arrive)}" else "",
            )
        }
        CmdKind.DIVISION -> world.division(ref.id)?.let { d -> listOf("Division ${d.number}", "${team(d.owner)} · ${d.elements.size} units") }
        else -> world.unit(ref.id)?.let { u ->
            val base = u.airbase?.let { world.objective(it)?.name ?: world.unit(it)?.name }
            listOfNotNull(
                u.name,
                listOfNotNull(u.kind.label, u.aircraft ?: u.cls.ifEmpty { null }, "#${u.campId}", team(u.owner)).joinToString(" · "),
                u.mission?.let { strings[CampaignArchive.MISSION_STRINGS + it]?.trim()?.let { m -> "Mission: $m" } },
                base?.let { "Base $it" },
                u.orders?.let { CommandWorldBuilder.ORDERS.getOrNull(it)?.let { o -> "Orders: $o" } },
                u.objective?.let { world.objective(it)?.name }?.let { "Objective: $it" },
                u.system?.let { s -> u.ringFt?.let { "$s · reach ${(it / FT_PER_NM).roundToInt()} nm" } ?: s },
                if (u.elements.isNotEmpty()) "${u.elements.size} ${if (u.kind == CmdKind.PACKAGE) "flights" else "units"}" else null,
                listOfNotNull(if (u.inactive) "inactive" else null, if (u.dead) "destroyed" else null, if (u.moving) "moving" else null).takeIf { it.isNotEmpty() }?.joinToString(", "),
            )
        }
    } ?: emptyList()
}

/** The position under the mouse (feet and the campaign grid), and the ruler's figures. */
@Composable
private fun Readout(at: Pair<Double, Double>?, ruler: Pair<Pair<Double, Double>, Pair<Double, Double>>?, modifier: Modifier) {
    if (at == null && ruler == null) return
    Row(
        modifier.padding(6.dp).clip(RoundedCornerShape(3.dp)).background(Hud.Surface.copy(alpha = 0.88f)).padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (at != null) {
            val (n, e) = at
            Text(
                "N ${thousands(n)} ft  E ${thousands(e)} ft  ·  grid ${(e / CampaignArchive.FEET_PER_CELL).toInt()}, ${(n / CampaignArchive.FEET_PER_CELL).toInt()}",
                color = Hud.TextDim, fontSize = 11.5.sp,
            )
        }
        if (ruler != null) Text("   ${rulerText(ruler.first, ruler.second)}", color = Hud.Amber, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
    }
}

private fun thousands(v: Double): String = "%,d".format(v.roundToInt())

/** The context menu: the built-in lines for what is under it, then every registered action that accepts it. */
@Composable
private fun ContextMenu(ctx: CmdContext, m: MenuAt, onClose: () -> Unit, onRulerFrom: () -> Unit) {
    val world = ctx.world
    val ref = m.ref
    Box(Modifier.offset { IntOffset(m.at.x.roundToInt(), m.at.y.roundToInt()) }.size(1.dp)) {
        DropdownMenu(true, onDismissRequest = onClose, modifier = Modifier.background(Hud.Surface).widthIn(min = 220.dp)) {
            if (ref != null) {
                Text(world.nameOf(ref), color = Hud.TextDim, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                Item("Inspect") { ctx.selection.set(ref); onClose() }
                Item("Centre the map here") { ctx.focus(ref); onClose() }
                val table = when (ref.kind) { CmdKind.OBJECTIVE -> CmdPage.OBJECTIVES; CmdKind.TEAM -> CmdPage.TEAMS; else -> CmdPage.UNITS }
                Item("Show in ${table.label}") { ctx.selection.set(ref); ctx.open(table); onClose() }
                if (ref.kind != CmdKind.WAYPOINT && ref.kind != CmdKind.DIVISION && ref.kind != CmdKind.PACKAGE) {
                    HorizontalDivider(color = Hud.Outline.copy(alpha = 0.5f))
                    Text("Set owner", color = Hud.TextFaint, fontSize = 10.5.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp))
                    world.teams.filter { it.inUse }.forEach { tm ->
                        Item(tm.name, enabled = CommandSlots.mapEdits != null, lead = { TeamFlagLead(tm.name, tm.colour) }) {
                            val refs = if (ref in ctx.selection) ctx.selection.refs else listOf(ref)
                            ctx.say(CommandSlots.mapEdits?.setOwner(refs, tm.n) ?: "${refs.size} now ${tm.name}'s.".also { ctx.changed() })
                            onClose()
                        }
                    }
                }
            } else {
                Text("N ${thousands(m.north)}  E ${thousands(m.east)}", color = Hud.TextDim, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            }
            HorizontalDivider(color = Hud.Outline.copy(alpha = 0.5f))
            Item("Ruler from here") { onRulerFrom(); onClose() }
            val extra = CommandSlots.actions.filter { runCatching { it.accepts(ctx, ref) }.getOrDefault(false) }
            if (extra.isNotEmpty()) HorizontalDivider(color = Hud.Outline.copy(alpha = 0.5f))
            extra.forEach { a ->
                val why = runCatching { a.refusal(ctx, ref) }.getOrNull()
                Item(a.label, enabled = why == null, tip = why) { runCatching { a.run(ctx, ref, m.north, m.east) }.onFailure { ctx.say(it.message ?: "That did not work.") }; onClose() }
            }
        }
    }
}

@Composable
private fun Item(text: String, enabled: Boolean = true, tip: String? = null, lead: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    val row: @Composable () -> Unit = {
        DropdownMenuItem(
            text = { Row(verticalAlignment = Alignment.CenterVertically) { if (lead != null) { Spacer(Modifier.width(10.dp)); lead() }; Text(text, fontSize = 12.5.sp, color = if (enabled) Hud.Text else Hud.TextFaint) } },
            onClick = onClick, enabled = enabled, modifier = Modifier.height(28.dp),
        )
    }
    if (tip == null) row() else Tip(tip) { row() }
}
