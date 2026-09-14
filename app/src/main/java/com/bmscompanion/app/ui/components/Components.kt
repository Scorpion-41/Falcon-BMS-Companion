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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.drawText
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
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
    val focus = LocalFocusManager.current
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        placeholder = { Text(placeholder, color = Hud.TextFaint) },
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
    Box(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (filled) color.copy(alpha = 0.18f) else Color.Transparent)
            .border(1.dp, color.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagFlow(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
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
            containerColor = Hud.Surface2,
            labelColor = Hud.TextDim,
            selectedContainerColor = Hud.Amber.copy(alpha = 0.18f),
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
