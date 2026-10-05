package com.bmscompanion.app.ui.screens.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bmscompanion.app.ui.theme.Hud
import kotlin.math.roundToInt

/**
 * The Editor's own furniture: a settings panel rather than a page of cards.
 *
 * Everything else in the app is read while flying, so it is drawn large and spaced out. The Editor is the opposite
 * — it is worked at, on a desk, before a flight — so it is a settings panel: one line per setting, a label column
 * and a value column, hairline rules between groups, and none of the padding a card carries.
 *
 * All the spacing comes from two numbers, [ROW] and [GAP], and nothing here lets a page put two controls side by
 * side without one. A row of chips that touch reads as a single smeared control, and a number glued to its unit
 * reads as one word — so the gap lives in the furniture rather than in the pages, where it gets forgotten.
 */
/** One line of the panel. Everything here is sized off it. */
val ROW = 38.dp

/** The gap between any two things side by side. One number, so nothing ends up glued to its neighbour. */
val GAP = 12.dp

/** A hairline between rows of the same group. */
@Composable
fun Rule(alpha: Float = 0.3f) =
    Box(Modifier.fillMaxWidth().height(1.dp).background(Hud.Outline.copy(alpha = alpha)))

/**
 * A number on one line: the name, a slider that takes the space left over, then the value and its unit.
 *
 * The value and the unit are separate columns. Run together they read as one word, and the digits stop lining up
 * down the panel as soon as one of them needs a decimal point.
 *
 * [mixed] is for a row that edits several things at once which do not agree on this value (several override regions
 * selected together): the value column says "mixed" and the slider is dimmed until it is moved, which sets them all.
 */
@Composable
fun NumRow(
    label: String,
    value: Double,
    min: Double,
    max: Double,
    unit: String,
    step: Double,
    enabled: Boolean = true,
    mixed: Boolean = false,
    onChange: (Double) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(ROW).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.width(122.dp), color = Hud.TextDim, fontSize = 13.sp, maxLines = 1)
        Spacer(Modifier.width(GAP))
        Slider(
            value = value.coerceIn(min, max).toFloat(),
            onValueChange = { onChange(((it / step).roundToInt() * step).coerceIn(min, max)) },
            valueRange = min.toFloat()..max.toFloat(),
            enabled = enabled,
            modifier = Modifier.weight(1f).height(26.dp),
            colors = SliderDefaults.colors(
                thumbColor = if (mixed) Hud.Amber.copy(alpha = 0.45f) else Hud.Amber,
                activeTrackColor = Hud.Amber.copy(alpha = if (mixed) 0.25f else 0.65f),
                inactiveTrackColor = Hud.Outline.copy(alpha = 0.7f),
            ),
        )
        Spacer(Modifier.width(GAP))
        Text(
            if (mixed) "mixed" else num(value),
            Modifier.width(56.dp),
            color = if (!enabled || mixed) Hud.TextFaint else Hud.Text,
            fontSize = if (mixed) 11.5.sp else 13.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
            fontStyle = if (mixed) FontStyle.Italic else FontStyle.Normal,
            textAlign = TextAlign.End,
            maxLines = 1,
        )
        Spacer(Modifier.width(7.dp))
        Text(
            unit,
            Modifier.width(30.dp),
            color = Hud.TextFaint,
            fontSize = 11.5.sp,
            maxLines = 1,
        )
    }
}

/**
 * A small flat chip. The Editor's only button shape, so a row of them reads as one control.
 *
 * [mixed] is the third state of a switch that several things are being edited through at once and they disagree on:
 * half lit, in italics, so it reads as neither on nor off. A press sets it for all of them.
 */
@Composable
fun EditorChip(
    text: String,
    selected: Boolean = false,
    enabled: Boolean = true,
    tint: Color? = null,
    mixed: Boolean = false,
    onClick: () -> Unit,
) {
    val ink = tint ?: Hud.Amber
    Text(
        text,
        Modifier.clip(RoundedCornerShape(4.dp))
            .background(
                when {
                    selected -> ink.copy(alpha = 0.22f)
                    mixed -> ink.copy(alpha = 0.08f)
                    else -> Hud.Surface
                },
            )
            .border(
                1.dp,
                when {
                    selected -> ink.copy(alpha = 0.9f)
                    mixed -> ink.copy(alpha = 0.45f)
                    else -> Hud.Outline.copy(alpha = 0.6f)
                },
                RoundedCornerShape(4.dp),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 8.dp),
        color = when {
            !enabled -> Hud.TextFaint
            selected -> ink
            mixed -> ink.copy(alpha = 0.75f)
            else -> Hud.TextDim
        },
        fontSize = 12.5.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        fontStyle = if (mixed && !selected) FontStyle.Italic else FontStyle.Normal,
        maxLines = 1,
    )
}

/** The one strong button a page gets: an action that changes Falcon BMS. */
@Composable
fun ActionButton(text: String, enabled: Boolean, danger: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val ink = if (danger) Hud.Red else Hud.Amber
    Box(
        modifier.clip(RoundedCornerShape(5.dp))
            .background(if (enabled) ink else Hud.Surface2)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (enabled) Hud.Bg else Hud.TextFaint,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

/** A thin strip across the top of a pane: selectors and actions, never more than one line high. */
@Composable
fun ToolBar(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .background(Hud.Surface.copy(alpha = 0.55f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        content = content,
    )
}

/** A short note under a group, for the one sentence a setting needs. */
@Composable
fun Note(text: String, colour: Color = Hud.TextFaint) =
    Text(text, color = colour, fontSize = 11.5.sp, lineHeight = 15.sp, modifier = Modifier.padding(vertical = 6.dp))

/** Two panels side by side on a wide screen, stacked on a narrow one. */
@Composable
fun EditorSplit(wide: Boolean, left: @Composable () -> Unit, right: @Composable () -> Unit) {
    if (wide) {
        Row(Modifier.fillMaxWidth()) {
            Box(Modifier.weight(1f)) { left() }
            Spacer(Modifier.width(22.dp))
            Box(Modifier.weight(1f)) { right() }
        }
    } else {
        Column(Modifier.fillMaxWidth()) { left(); right() }
    }
}

internal fun num(v: Double): String =
    if (v == v.roundToInt().toDouble()) v.roundToInt().toString() else ((v * 10).roundToInt() / 10.0).toString()
