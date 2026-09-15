package com.bmscompanion.app.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.bmscompanion.app.data.ChartRef
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.ui.components.AssetImage
import com.bmscompanion.app.ui.components.BmsTopBar
import com.bmscompanion.app.ui.components.LoadingBox
import com.bmscompanion.app.ui.components.SectionCard
import com.bmscompanion.app.ui.go
import com.bmscompanion.app.ui.theme.Hud
import kotlin.math.min
import kotlin.math.pow

// PC version of app/.../ui/screens/Charts.kt: same screens, with mouse-wheel zoom and drag to pan.

fun chartRoute(file: String, title: String) = "chart?file=${android.net.Uri.encode(file)}&title=${android.net.Uri.encode(title)}"

@Composable
fun AirportChartsCard(nav: NavHostController, setId: String, airportId: Int, airportName: String) {
    val charts by produceState<List<ChartRef>?>(null, setId, airportId) { value = Repo.charts(setId)[airportId.toString()].orEmpty() }
    val list = charts ?: return
    if (list.isEmpty()) return
    SectionCard("Charts (${list.size})", accent = Hud.Amber) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            list.forEach { c ->
                Column(Modifier.width(128.dp).clip(RoundedCornerShape(10.dp)).background(Hud.Surface2).clickable { nav.go(chartRoute(c.file, "$airportName · ${c.title}")) }) {
                    Box(Modifier.fillMaxWidth().height(160.dp).background(Color.White)) {
                        AssetImage(c.file, Modifier.fillMaxSize().padding(2.dp), ContentScale.Fit, sample = 8)
                    }
                    Text(c.title, Modifier.padding(8.dp), fontSize = 12.sp, maxLines = 2)
                }
            }
        }
        Text("Click to open · scroll to zoom", style = MaterialTheme.typography.bodySmall, color = Hud.TextFaint, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
fun ChartViewerScreen(nav: NavHostController, file: String, title: String) {
    val bmp by produceState<Bitmap?>(null, file) { value = Repo.bitmap(file) }
    Column(Modifier.fillMaxSize().background(Hud.Bg)) {
        BmsTopBar(title.substringAfter(" · ", title), title.substringBefore(" · ", ""), onBack = { nav.popBackStack() })
        val b = bmp ?: run { LoadingBox(); return@Column }
        ZoomableBitmap(b, Modifier.fillMaxSize())
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ZoomableBitmap(b: Bitmap, modifier: Modifier, background: Color = Color.White) {
    var scale by remember { mutableFloatStateOf(1f) }
    var panX by remember { mutableFloatStateOf(0f) }
    var panY by remember { mutableFloatStateOf(0f) }
    val img = remember(b) { b.asImageBitmap() }
    BoxWithConstraints(modifier.clipToBounds().background(background)) {
        val d = LocalDensity.current
        val w = with(d) { maxWidth.toPx() }; val h = with(d) { maxHeight.toPx() }
        val fit = min(w / b.width, h / b.height)
        fun zoomAt(p: Offset, ns: Float) {
            val c = Offset(p.x - w / 2, p.y - h / 2)
            val f = ns / scale
            panX = c.x - (c.x - panX) * f
            panY = c.y - (c.y - panY) * f
            scale = ns
            if (scale <= 1f) { panX = 0f; panY = 0f }
        }
        Canvas(
            Modifier.fillMaxSize()
                .onPointerEvent(PointerEventType.Scroll) { e ->
                    val ch = e.changes.firstOrNull() ?: return@onPointerEvent
                    if (ch.scrollDelta.y != 0f) { zoomAt(ch.position, (scale * 1.25f.pow(-ch.scrollDelta.y)).coerceIn(1f, 8f)); ch.consume() }
                }
                .pointerInput(b) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        val ns = (scale * zoom).coerceIn(1f, 8f)
                        val c = Offset(centroid.x - w / 2, centroid.y - h / 2)
                        val f = ns / scale
                        panX = c.x - (c.x - (panX + pan.x)) * f
                        panY = c.y - (c.y - (panY + pan.y)) * f
                        scale = ns
                    }
                }
                .pointerInput(b) {
                    detectTapGestures(onDoubleTap = { p ->
                        if (scale > 1.5f) { scale = 1f; panX = 0f; panY = 0f } else {
                            val ns = 3f; val c = Offset(p.x - w / 2, p.y - h / 2)
                            panX = c.x - c.x * ns; panY = c.y - c.y * ns; scale = ns
                        }
                    })
                },
        ) {
            val dw = b.width * fit * scale; val dh = b.height * fit * scale
            drawImage(img, dstOffset = IntOffset(((w - dw) / 2 + panX).toInt(), ((h - dh) / 2 + panY).toInt()), dstSize = IntSize(dw.toInt(), dh.toInt()))
        }
        Text("Scroll or double-click to zoom · drag to pan", Modifier.align(Alignment.BottomCenter).padding(12.dp).clip(RoundedCornerShape(8.dp)).background(Hud.Bg.copy(alpha = 0.75f)).border(1.dp, Hud.Outline, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 4.dp), fontSize = 11.sp, color = Hud.TextDim)
    }
}
