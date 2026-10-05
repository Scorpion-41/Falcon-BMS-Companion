package com.bmscompanion.app.ui.screens.wdp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp

/*
 * The line an attack page (Pop-up, TOSS) shows about the IP STPT that **IP STPT at the VRP** created
 * ([AttackSelection.ipNotice], 1.3.8): that it was created and where to delete it, or — once the Input Panel has moved
 * the VRP away from it — the warning that it no longer is at the VRP. It is shown wherever the page is looked at:
 * across the top of the map (Profile panel), and in the free band at the foot of the Selections panel (under the
 * button) and of the DED Data panel. On the screen only: Save Map's picture and the kneeboard pages never carry it.
 */

private val INFO_INK = Color(0xFFBFD9FF)
private val WARN_INK = Color(0xFFFFC145)

/** Across the top of the attack map (picSatView), on a band of its own so it reads over any map style. */
@Composable
internal fun AttackNoticeStrip(notice: AttackSelection.IpNotice) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val d = LocalDensity.current
        // the map is WDP's 435 pixels; the line at 11.5 of them, as the caption at its foot is about 13
        val k = constraints.maxWidth / 435f
        val size = with(d) { (11.5f * k).toSp() }
        Text(
            notice.text,
            Modifier.align(Alignment.TopStart).fillMaxWidth()
                .background(if (notice.stale) Color(0xEBFFF3CD) else Color(0xE6E6F0FF))
                .padding(horizontal = with(d) { (5f * k).toDp() }, vertical = with(d) { (2f * k).toDp() }),
            color = if (notice.stale) Color(0xFF6B4E00) else Color(0xFF12365F),
            fontSize = size, lineHeight = size * 1.2f, maxLines = 2, overflow = TextOverflow.Ellipsis,
            fontWeight = if (notice.stale) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

/**
 * In a free band of one of the page's 475 x 670 right-hand panels: [top] and [height] in the panel's design pixels,
 * from x 30 to 445. The panel's own charcoal is the ground: the notice in a pale blue, the warning in amber and bold.
 */
@Composable
internal fun AttackNoticeBand(notice: AttackSelection.IpNotice, top: Float, height: Float, fontPx: Float = 11.5f) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val d = LocalDensity.current
        val k = constraints.maxWidth / 475f
        val size = with(d) { (fontPx * k).toSp() }
        Box(Modifier.offset(with(d) { (30f * k).toDp() }, with(d) { (top * k).toDp() })) {
            Text(
                notice.text,
                Modifier.width(with(d) { (415f * k).toDp() }),
                color = if (notice.stale) WARN_INK else INFO_INK,
                fontSize = size, lineHeight = size * 1.15f,
                maxLines = maxOf(1, (height / (fontPx * 1.15f)).toInt()), overflow = TextOverflow.Ellipsis,
                fontWeight = if (notice.stale) FontWeight.Bold else FontWeight.Normal,
            )
        }
    }
}

/** The DED Data panel's own content ([content], the ELEV hint) with the [notice] in the band under it. */
@Composable
internal fun WithDedNotice(notice: AttackSelection.IpNotice?, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        content()
        notice?.let { AttackNoticeBand(it, DED_NOTICE_TOP, DED_NOTICE_H, fontPx = 10f) }
    }
}

/** The bands' design pixels: under the Selections panel's delivery-mode lines, and under the DED Data panel's ELEV hint. */
internal const val SELECTIONS_NOTICE_TOP = 578f
internal const val SELECTIONS_NOTICE_H = 56f
internal const val DED_NOTICE_TOP = 642f
internal const val DED_NOTICE_H = 26f
