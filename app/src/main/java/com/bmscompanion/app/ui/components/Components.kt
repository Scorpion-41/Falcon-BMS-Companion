package com.bmscompanion.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import kotlinx.coroutines.launch
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.drawText
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.ui.theme.Hud
import com.bmscompanion.app.ui.theme.LocalExtra
import com.bmscompanion.app.ui.theme.Mono
import java.util.Locale

// ---------------- layout helpers ----------------

@Composable
fun isWide(): Boolean = LocalConfiguration.current.screenWidthDp >= 840

@Composable
fun isMedium(): Boolean = LocalConfiguration.current.screenWidthDp >= 600

/** Two-pane list/detail on wide screens; single pane otherwise (detail shown when selected). */
@Composable
fun ListDetail(
    selected: Boolean,
    list: @Composable () -> Unit,
    detail: @Composable () -> Unit,
    emptyDetail: @Composable () -> Unit = { EmptyState("Select an item") },
    listWidth: Dp = 380.dp,
) {
    if (isWide()) {
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.width(listWidth).fillMaxHeight()) { list() }
            Box(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
            Box(Modifier.weight(1f).fillMaxHeight()) { if (selected) detail() else emptyDetail() }
        }
    } else {
        if (selected) detail() else list()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BmsTopBar(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = {
            Column {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleLarge)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.labelMedium, color = Hud.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        navigationIcon = {
            if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Hud.Bg, scrolledContainerColor = Hud.Surface),
    )
}

@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    // On a kneeboard there is no keyboard to reach: you are strapped in with a helmet on and a mouse. Tapping the
    // field opens one on the page, and physical keys keep working for anyone who has them.
    if (Hud.onPaper) {
        var typing by rememberSaveable { mutableStateOf(false) }
        val requester = remember { FocusRequester() }
        Column(Modifier.fillMaxWidth()) {
            SearchInput(value, onValueChange, placeholder, modifier.focusRequester(requester)) { typing = true }
            if (typing) {
                OnScreenKeyboard(
                    onKey = { c -> onValueChange(value + c); runCatching { requester.requestFocus() } },
                    onBackspace = { onValueChange(value.dropLast(1)); runCatching { requester.requestFocus() } },
                    onClear = { onValueChange(""); runCatching { requester.requestFocus() } },
                    onClose = { typing = false },
                )
            }
        }
        return
    }
    SearchInput(value, onValueChange, placeholder, modifier)
}

@Composable
private fun SearchInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit = {},
) {
    val focus = LocalFocusManager.current
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) onFocused() },
        singleLine = true,
        // one line, always: a long hint ("Name, ICAO, TACAN (75X), ILS or frequency") used to wrap in a narrow
        // column and leave that section with a search bar twice the height of the one on Home
        placeholder = { Text(placeholder, color = Hud.TextFaint, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(Icons.Default.Search, null, tint = Hud.TextDim) },
        trailingIcon = {
            if (value.isNotEmpty()) IconButton(onClick = { onValueChange("") }) { Icon(Icons.Default.Clear, "Clear", tint = Hud.TextDim) }
        },
        shape = RoundedCornerShape(14.dp),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Hud.Surface2,
            unfocusedContainerColor = Hud.Surface2,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            cursorColor = Hud.Cyan,
        ),
    )
}

// ---------------- content blocks ----------------

@Composable
fun SectionCard(
    title: String? = null,
    modifier: Modifier = Modifier,
    accent: Color = Hud.Amber,
    trailing: @Composable (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    // On a kneeboard a card is not a card. A page of paper has no raised, rounded, tinted panels on it: it has a
    // heading, a rule under it and the figures. That also gives back the 16dp of padding and the gap between panels,
    // which is most of a small board.
    if (Hud.onPaper) {
        Column(modifier.fillMaxWidth().padding(bottom = 2.dp)) {
            if (title != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // no accent colour: on a printed page every heading is the same ink, and the rule under it does
                    // the separating that a tinted card used to do
                    Text(
                        title.uppercase(Locale.US),
                        style = LocalExtra.current.overline,
                        color = Hud.Text,
                        modifier = Modifier.weight(1f),
                    )
                    trailing?.invoke()
                }
                Box(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 5.dp).height(1.dp).background(Hud.Outline))
            }
            content()
        }
        return
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Hud.Surface,
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Hud.Outline.copy(alpha = 0.6f)),
    ) {
        Column(Modifier.padding(16.dp)) {
            if (title != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(width = 3.dp, height = 14.dp).background(accent, RoundedCornerShape(2.dp)))
                    Spacer(Modifier.width(8.dp))
                    Text(title.uppercase(Locale.US), style = LocalExtra.current.overline, color = accent, modifier = Modifier.weight(1f))
                    trailing?.invoke()
                }
                Spacer(Modifier.height(12.dp))
            }
            content()
        }
    }
}

@Composable
fun CollapsibleCard(title: String, initiallyOpen: Boolean = false, accent: Color = Hud.Amber, preview: String? = null, content: @Composable () -> Unit) {
    var open by rememberSaveable(title) { mutableStateOf(initiallyOpen) }
    Surface(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        color = Hud.Surface,
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Hud.Outline.copy(alpha = 0.6f)),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(width = 3.dp, height = 14.dp).background(accent, RoundedCornerShape(2.dp)))
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = Hud.TextDim)
            }
            if (!open && !preview.isNullOrBlank()) {
                Text(
                    preview, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = Hud.TextDim,
                    modifier = Modifier.fillMaxWidth().clickable { open = true }.padding(start = 27.dp, end = 16.dp, bottom = 14.dp),
                )
            }
            AnimatedVisibility(open, enter = fadeIn()) {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) { content() }
            }
        }
    }
}

/** Long text becomes a collapsible card (with a preview); short text stays a plain card. */
@Composable
fun TextCard(title: String, text: String?, accent: Color = Hud.Amber, threshold: Int = 260, initiallyOpen: Boolean = false) {
    if (text.isNullOrBlank()) return
    if (text.length <= threshold) SectionCard(title, accent = accent) { Paragraph(text) }
    else CollapsibleCard(title, initiallyOpen = initiallyOpen, accent = accent, preview = text) { Paragraph(text) }
}

data class Stat(val label: String, val value: String?, val mono: Boolean = true, val color: Color? = null)

@Composable
fun StatGrid(stats: List<Stat>, minCell: Dp = 150.dp) {
    val visible = stats.filter { !it.value.isNullOrBlank() }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cols = (maxWidth / minCell).toInt().coerceIn(2, 6)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            visible.chunked(cols).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { s ->
                        Column(
                            Modifier.weight(1f).background(Hud.Surface2, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 8.dp),
                        ) {
                            Text(s.label, style = MaterialTheme.typography.labelSmall, color = Hud.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                s.value!!,
                                style = if (s.mono) LocalExtra.current.mono else MaterialTheme.typography.bodyMedium,
                                color = s.color ?: Hud.Text,
                            )
                        }
                    }
                    repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
fun KeyValueRow(label: String, value: String?, mono: Boolean = false, valueColor: Color = Hud.Text) {
    if (value.isNullOrBlank()) return
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Hud.TextDim, modifier = Modifier.weight(0.42f))
        Text(
            value,
            style = if (mono) LocalExtra.current.mono.copy(fontSize = 14.sp) else MaterialTheme.typography.bodyMedium,
            color = valueColor,
            modifier = Modifier.weight(0.58f),
        )
    }
}

@Composable
fun Tag(text: String, color: Color = Hud.TextDim, filled: Boolean = false, modifier: Modifier = Modifier) {
    // printed, a tag is a word in a hairline box: the page is one ink, and a coloured pill is the most digital
    // thing on it
    val ink = if (Hud.onPaper) (if (filled) Hud.Text else Hud.TextDim) else color
    Box(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (filled && !Hud.onPaper) color.copy(alpha = 0.18f) else Color.Transparent)
            .border(1.dp, if (Hud.onPaper) Hud.Outline else color.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        Text(text, color = ink, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagFlow(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
}

/**
 * The A-Z strip down the side of a long list: the letter under your finger jumps the list there, and the letter you
 * are looking at is the one drawn brightest. Dragging runs through them without lifting off, which is the point of it
 * on a touchscreen — and with a mouse in a headset it beats flicking a scrollbar.
 *
 * [before] is how many rows the list draws before the data (a search field, a count line), so the jump lands right.
 */
@Composable
fun <T> BoxScope.AlphabetScrubber(
    items: List<T>,
    state: LazyListState,
    label: (T) -> String,
    before: Int = 0,
    modifier: Modifier = Modifier,
) {
    val firstRow = remember(items) {
        val found = LinkedHashMap<Char, Int>()
        // putIfAbsent is a JVM map method and does not exist in the browser build
        items.forEachIndexed { i, item -> initialOf(label(item)).let { c -> if (c !in found) found[c] = i } }
        found
    }
    if (firstRow.size < 4) return // a handful of rows finds itself
    val letters = firstRow.keys.toList()
    val scope = rememberCoroutineScope()
    var height by remember { mutableIntStateOf(0) }
    val current by remember(items, letters) {
        derivedStateOf {
            val row = (state.firstVisibleItemIndex - before).coerceIn(0, (items.size - 1).coerceAtLeast(0))
            items.getOrNull(row)?.let { initialOf(label(it)) }
        }
    }
    val jumpTo = { y: Float ->
        if (height > 0) {
            val letter = letters[((y / height) * letters.size).toInt().coerceIn(0, letters.lastIndex)]
            firstRow[letter]?.let { row -> scope.launch { state.scrollToItem(row + before) } }
        }
        Unit
    }
    Column(
        modifier.align(Alignment.CenterEnd).fillMaxHeight().width(26.dp).padding(vertical = 8.dp)
            .onSizeChanged { height = it.height }
            .pointerInput(letters, height) { detectTapGestures { jumpTo(it.y) } }
            .pointerInput(letters, height) { detectVerticalDragGestures { change, _ -> jumpTo(change.position.y) } },
        verticalArrangement = Arrangement.SpaceEvenly,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        letters.forEach { c ->
            val here = c == current
            Text(
                c.toString(),
                fontSize = if (here) 13.sp else 10.sp,
                fontWeight = if (here) FontWeight.Bold else FontWeight.Normal,
                color = if (here) Hud.Amber else Hud.TextFaint,
                maxLines = 1,
            )
        }
    }
}

/** The letter a row files under; anything not a letter files under #. */
private fun initialOf(label: String): Char {
    val c = label.trim().firstOrNull()?.uppercaseChar() ?: '#'
    return if (c in 'A'..'Z') c else '#'
}

/**
 * A keyboard drawn on the page, for a kneeboard in VR: the pilot has a mouse and no reachable keys. It types into the
 * search field above it, and closes with the tick. Physical typing keeps working the whole time.
 */
@Composable
private fun OnScreenKeyboard(
    onKey: (Char) -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
    onClose: () -> Unit,
) {
    val rows = listOf("1234567890", "QWERTYUIOP", "ASDFGHJKL", "ZXCVBNM-")
    Column(
        Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(10.dp))
            .background(Hud.Surface2).border(1.dp, Hud.Outline, RoundedCornerShape(10.dp)).padding(5.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { c -> Key(c.toString(), Modifier.weight(1f)) { onKey(c) } }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Key("space", Modifier.weight(3f)) { onKey(' ') }
            Key("del", Modifier.weight(1f), onClick = onBackspace)
            Key("clear", Modifier.weight(1.4f), onClick = onClear)
            Key("done", Modifier.weight(1.4f), accent = true, onClick = onClose)
        }
    }
}

@Composable
private fun Key(label: String, modifier: Modifier = Modifier, accent: Boolean = false, onClick: () -> Unit) {
    Text(
        label,
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (accent) Hud.Amber.copy(alpha = 0.25f) else Hud.Surface)
            .border(1.dp, Hud.Outline.copy(alpha = 0.8f), RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        color = Hud.Text,
        fontSize = if (label.length > 1) 11.sp else 15.sp,
        textAlign = TextAlign.Center,
        maxLines = 1,
    )
}

@Composable
fun <T> ChipRow(
    options: List<T>,
    selected: T?,
    label: (T) -> String,
    onSelect: (T?) -> Unit,
    allLabel: String? = "All",
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp),
) {
    // A kneeboard shows one list and the means to find something in it. Narrowing by category is planning-room work
    // you did before you strapped in, and every row of chips is a row of the board not showing the list.
    if (Hud.onPaper) return
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(contentPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (allLabel != null) HudChip(allLabel, selected == null) { onSelect(null) }
        options.forEach { o -> HudChip(label(o), selected == o) { onSelect(if (selected == o && allLabel != null) null else o) } }
    }
}

@Composable
fun HudChip(text: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(text, maxLines = 1) },
        colors = FilterChipDefaults.filterChipColors(
            // printed, a dim grey on a cream chip on a cream page is three shades of nothing: ink and a tinted chip
            containerColor = if (Hud.onPaper) Hud.Surface3 else Hud.Surface2,
            labelColor = if (Hud.onPaper) Hud.Text else Hud.TextDim,
            selectedContainerColor = Hud.Amber.copy(alpha = if (Hud.onPaper) 0.22f else 0.18f),
            selectedLabelColor = Hud.Amber,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true, selected = selected,
            borderColor = Hud.Outline, selectedBorderColor = Hud.Amber.copy(alpha = 0.6f),
        ),
    )
}

@Composable
fun AssetImage(path: String?, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Fit, sample: Int = 1, placeholder: @Composable () -> Unit = {}) {
    val bmp by produceState<android.graphics.Bitmap?>(null, path) { value = path?.let { Repo.bitmap(it, sample) } }
    val b = bmp
    if (b != null) {
        Image(b.asImageBitmap(), null, modifier = modifier, contentScale = contentScale)
    } else {
        Box(modifier) { placeholder() }
    }
}

@Composable
fun LoadingBox(modifier: Modifier = Modifier.fillMaxSize()) {
    Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Hud.Green, strokeWidth = 2.dp) }
}

@Composable
fun EmptyState(text: String, modifier: Modifier = Modifier.fillMaxSize()) {
    Box(modifier.padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = Hud.TextFaint, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun FavoriteButton(id: String) {
    val favs by Repo.favorites.collectAsState()
    val on = id in favs
    IconButton(onClick = { Repo.toggleFavorite(id) }) {
        Icon(if (on) Icons.Default.Star else Icons.Default.StarBorder, "Favorite", tint = if (on) Hud.Amber else Hud.TextDim)
    }
}

/** RWR-scope style symbol badge. */
@Composable
fun RwrBadge(symbol: String?, size: Dp = 40.dp, color: Color = Hud.Green) {
    if (symbol.isNullOrBlank()) return
    val tm = androidx.compose.ui.text.rememberTextMeasurer()
    androidx.compose.foundation.Canvas(Modifier.size(size)) {
        val r = this.size.minDimension / 2
        drawCircle(Color(0xFF06140C), r, center)
        drawCircle(color.copy(alpha = 0.7f), r - 1.5.dp.toPx() / 2, center, style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
        var fs = when { symbol.length <= 2 -> 0.46f; symbol.length <= 4 -> 0.30f; else -> 0.22f } * this.size.minDimension
        val style = { px: Float -> androidx.compose.ui.text.TextStyle(color = color, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = (px / density / fontScale).sp,
            platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
            lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center, androidx.compose.ui.text.style.LineHeightStyle.Trim.Both)) }
        var layout = tm.measure(symbol, style(fs), maxLines = 1)
        val maxW = r * 1.55f
        if (layout.size.width > maxW) { fs *= maxW / layout.size.width; layout = tm.measure(symbol, style(fs), maxLines = 1) }
        // center the glyphs (cap height ≈ 0.72 em) rather than the font line box
        val baselineY = center.y + fs * 0.36f
        drawText(layout, topLeft = Offset(center.x - layout.size.width / 2f, baselineY - layout.firstBaseline))
    }
}

@Composable
fun ListRow(
    title: String,
    subtitle: String? = null,
    selected: Boolean = false,
    leading: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) Hud.Amber.copy(alpha = 0.10f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) { leading(); Spacer(Modifier.width(12.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (selected) Hud.Amber else Hud.Text)
            if (!subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Hud.TextDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) { Spacer(Modifier.width(8.dp)); trailing() }
    }
}

@Composable
fun GroupHeader(text: String, count: Int? = null) {
    Row(
        Modifier.fillMaxWidth().background(Hud.Bg).padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text.uppercase(Locale.US), style = LocalExtra.current.overline, color = Hud.Green, modifier = Modifier.weight(1f))
        if (count != null) Text("$count", style = LocalExtra.current.monoSmall, color = Hud.TextFaint)
    }
}

@Composable
fun Thumb(pic: String?, modifier: Modifier = Modifier.size(width = 64.dp, height = 36.dp)) {
    Box(modifier.clip(RoundedCornerShape(6.dp)).background(Hud.Surface2)) {
        AssetImage(Repo.tacrefImagePath(pic), Modifier.fillMaxSize(), ContentScale.Crop, sample = 2)
    }
}

@Composable
fun Divider() = HorizontalDivider(color = Hud.Outline.copy(alpha = 0.5f))

@Composable
fun Paragraph(text: String?, color: Color = Hud.Text) {
    if (text.isNullOrBlank()) return
    Text(text, style = MaterialTheme.typography.bodyMedium, color = color, lineHeight = 21.sp)
}

@Composable
fun ContentColumn(modifier: Modifier = Modifier, maxWidth: Dp = 980.dp, content: @Composable () -> Unit) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = maxWidth).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
    }
}

// ---------------- formatting ----------------
object Fmt {
    fun num(v: Double?, digits: Int = 0): String? = v?.let { if (digits == 0) String.format(Locale.US, "%,d", Math.round(it)) else String.format(Locale.US, "%,.${digits}f", it) }
    fun num(v: Int?): String? = v?.let { String.format(Locale.US, "%,d", it) }
    fun ft(v: Double?) = num(v)?.let { "$it ft" }
    fun ft(v: Int?) = num(v)?.let { "$it ft" }
    fun lbs(v: Double?) = num(v)?.let { "$it lb" }
    fun kts(v: Double?) = num(v)?.let { "$it kt" }
    fun nm(v: Double?) = v?.let { (if (it % 1.0 == 0.0) num(it) else num(it, 1)) + " nm" }
    fun hdg(v: Double) = String.format(Locale.US, "%03d°", (Math.round(v).toInt() % 360 + 360) % 360)
    fun titleCase(s: String) = s.lowercase(Locale.US).split('_', ' ').joinToString(" ") { it.replaceFirstChar { c -> c.titlecase(Locale.US) } }
}
