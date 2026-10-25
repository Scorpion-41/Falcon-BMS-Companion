package com.bmscompanion.desktop.ui.command

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpOffset
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.onClick
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bmscompanion.app.ui.screens.editor.Rule
import com.bmscompanion.app.ui.theme.Hud

/**
 * One column of a [CmdGrid]: its heading, width, the cell's text, and how it sorts ([sort]: numbers as numbers, else
 * the text); [ink] colours a cell (an error row's red, a team's colour).
 */
class GridCol<T>(
    val title: String,
    val width: Dp,
    val numeric: Boolean = false,
    val sort: ((T) -> Comparable<*>?)? = null,
    val ink: ((T) -> Color?)? = null,
    /** drawn before the cell's words (a team's flag, [teamLead]) */
    val lead: ((T) -> (@Composable () -> Unit)?)? = null,
    val text: (T) -> String,
)

/**
 * The section's table: a heading row, sortable by a click on a heading (again to reverse), the first [frozen] columns
 * held in place while the rest scroll sideways, rows of 22 dp ruled by hairlines. A click selects ([onClick], with
 * Shift held: [additive]), [selected] rows are lit, and the list scrolls to [scrollTo] when it changes (a selection
 * made on the map). The editor's tables use it so every table of the section works alike.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun <T> CmdGrid(
    rows: List<T>,
    cols: List<GridCol<T>>,
    key: (T) -> Any,
    selected: (T) -> Boolean,
    onClick: (row: T, additive: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    frozen: Int = 1,
    scrollTo: Any? = null,
    rowInk: ((T) -> Color?)? = null,
    /** a right-click on a row opens a menu of these lines ([RefMenuItems] for an objective or a unit); null = none */
    rowMenu: (@Composable (row: T, close: () -> Unit) -> Unit)? = null,
) {
    var menuKey by remember { mutableStateOf<Any?>(null) }
    var menuX by remember { mutableStateOf(0f) }
    var sortCol by remember { mutableIntStateOf(-1) }
    var asc by remember { mutableStateOf(true) }
    val sorted = remember(rows, sortCol, asc) {
        val c = cols.getOrNull(sortCol) ?: return@remember rows
        val k: (T) -> Comparable<*>? = c.sort ?: { r -> if (c.numeric) c.text(r).replace(",", "").toDoubleOrNull() else c.text(r).lowercase() }
        @Suppress("UNCHECKED_CAST")
        val cmp = Comparator<T> { a, b -> compareValues(k(a) as Comparable<Any>?, k(b) as Comparable<Any>?) }
        if (asc) rows.sortedWith(cmp) else rows.sortedWith(cmp.reversed())
    }
    val hscroll = rememberScrollState()
    val list = rememberLazyListState()
    LaunchedEffect(scrollTo, sorted) {
        if (scrollTo != null) {
            val i = sorted.indexOfFirst { key(it) == scrollTo }
            if (i >= 0 && (i < list.firstVisibleItemIndex || i > list.firstVisibleItemIndex + list.layoutInfo.visibleItemsInfo.size - 2)) list.scrollToItem((i - 3).coerceAtLeast(0))
        }
    }
    val fz = frozen.coerceIn(0, cols.size)
    val frozenWidth = cols.take(fz).fold(0.dp) { a, c -> a + c.width }
    Column(modifier.fillMaxSize().shiftWheel(hscroll)) {
        Row(Modifier.fillMaxWidth().background(Hud.Surface.copy(alpha = 0.7f)).height(24.dp).padding(end = CMD_BAR), verticalAlignment = Alignment.CenterVertically) {
            cols.take(fz).forEachIndexed { i, c -> Heading(c, i == sortCol, asc) { if (sortCol == i) asc = !asc else { sortCol = i; asc = true } } }
            Row(Modifier.weight(1f).horizontalScroll(hscroll)) {
                cols.drop(fz).forEachIndexed { j, c -> val i = j + fz; Heading(c, i == sortCol, asc) { if (sortCol == i) asc = !asc else { sortCol = i; asc = true } } }
            }
        }
        Rule(0.5f)
        Box(Modifier.weight(1f).fillMaxWidth()) {
        LazyColumn(Modifier.fillMaxSize().padding(end = CMD_BAR), state = list) {
            itemsIndexed(sorted, key = { _, r -> key(r) }) { _, r ->
                val on = selected(r)
                val ink = rowInk?.invoke(r)
                val k = key(r)
                Box {
                    Row(
                        Modifier.fillMaxWidth().height(22.dp)
                            .background(if (on || menuKey == k) Hud.Amber.copy(alpha = 0.16f) else Color.Transparent)
                            .onClick(keyboardModifiers = { isShiftPressed }) { onClick(r, true) }
                            .onClick(keyboardModifiers = { !isShiftPressed }) { onClick(r, false) }
                            .then(
                                if (rowMenu == null) Modifier else Modifier.onPointerEvent(PointerEventType.Press) { e ->
                                    if (e.buttons.isSecondaryPressed) { menuKey = k; menuX = e.changes.firstOrNull()?.position?.x ?: 0f }
                                },
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        cols.take(fz).forEach { c -> Cell(c, r, ink) }
                        Row(Modifier.weight(1f).horizontalScroll(hscroll)) { cols.drop(fz).forEach { c -> Cell(c, r, ink) } }
                    }
                    if (rowMenu != null && menuKey == k) {
                        val dx = with(LocalDensity.current) { menuX.toDp() }
                        DropdownMenu(true, onDismissRequest = { menuKey = null }, offset = DpOffset(dx, 0.dp), modifier = Modifier.background(Hud.Surface).widthIn(min = 220.dp)) {
                            rowMenu(r) { menuKey = null }
                        }
                    }
                }
                Rule(0.12f)
            }
        }
            CmdVBar(rememberScrollbarAdapter(list), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
        CmdHBar(hscroll, Modifier.fillMaxWidth().padding(start = frozenWidth, end = CMD_BAR))
        Text("${rows.size} rows", color = Hud.TextFaint, fontSize = 10.5.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
    }
}

/** The width the section's scroll bars take (a list leaves it free at its right for the vertical one). */
val CMD_BAR: Dp = 10.dp

/** The section's scroll bar look: visible on the dark desk at rest, brighter under the mouse. */
@Composable
fun cmdScrollbarStyle(): ScrollbarStyle = ScrollbarStyle(
    minimalHeight = 24.dp, thickness = 8.dp, shape = RoundedCornerShape(4.dp), hoverDurationMillis = 250,
    unhoverColor = Hud.TextFaint.copy(alpha = 0.45f), hoverColor = Hud.TextDim.copy(alpha = 0.85f),
)

/**
 * A horizontal scroll bar for [state], drawn only while the content is wider than its box (draggable; a click on the
 * track jumps a page). Every wide table and page of the section has one at its foot.
 */
@Composable
fun CmdHBar(state: ScrollState, modifier: Modifier = Modifier) {
    if (state.maxValue <= 0) return
    HorizontalScrollbar(rememberScrollbarAdapter(state), modifier.height(CMD_BAR).padding(vertical = 1.dp), style = cmdScrollbarStyle())
}

/** A vertical scroll bar for a list or a scrolled column (it draws nothing while everything fits). */
@Composable
fun CmdVBar(adapter: androidx.compose.foundation.v2.ScrollbarAdapter, modifier: Modifier = Modifier) {
    VerticalScrollbar(adapter, modifier.width(CMD_BAR).padding(horizontal = 1.dp), style = cmdScrollbarStyle())
}

/**
 * The mouse wheel with Shift held scrolls [state] sideways anywhere in the box (over the frozen columns too, which have
 * no scroll of their own); a wheel already taken by a part that scrolls sideways itself is left alone.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.shiftWheel(state: ScrollState): Modifier = this.onPointerEvent(PointerEventType.Scroll, PointerEventPass.Initial) { e ->
    if (!e.keyboardModifiers.isShiftPressed || state.maxValue <= 0) return@onPointerEvent
    val ch = e.changes.firstOrNull() ?: return@onPointerEvent
    val d = ch.scrollDelta.let { if (it.x != 0f) it.x else it.y }
    if (d != 0f) { state.dispatchRawDelta(d * 48.dp.toPx()); ch.consume() }
}

@Composable
private fun <T> Heading(c: GridCol<T>, on: Boolean, asc: Boolean, onClick: () -> Unit) {
    Text(
        c.title.uppercase() + if (on) (if (asc) " ▴" else " ▾") else "",
        Modifier.width(c.width).clickable(onClick = onClick).padding(horizontal = 6.dp),
        color = if (on) Hud.Amber else Hud.TextFaint, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
        textAlign = if (c.numeric) TextAlign.End else TextAlign.Start,
    )
}

@Composable
private fun <T> Cell(c: GridCol<T>, r: T, rowInk: Color?) {
    val lead = c.lead?.invoke(r)
    if (lead != null) {
        Row(Modifier.width(c.width).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            lead()
            Text(c.text(r), color = c.ink?.invoke(r) ?: rowInk ?: Hud.Text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        return
    }
    Text(
        c.text(r), Modifier.width(c.width).padding(horizontal = 6.dp),
        color = c.ink?.invoke(r) ?: rowInk ?: Hud.Text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
        fontFamily = if (c.numeric) FontFamily.Monospace else null, textAlign = if (c.numeric) TextAlign.End else TextAlign.Start,
    )
}
