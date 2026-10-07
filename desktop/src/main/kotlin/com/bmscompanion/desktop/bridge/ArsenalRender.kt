package com.bmscompanion.desktop.bridge

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.rememberNavController
import com.bmscompanion.app.data.Placeholders
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.ui.screens.ArsenalPage
import com.bmscompanion.app.ui.screens.WeaponDetail
import com.bmscompanion.app.ui.screens.wdp.WdpProbe
import com.bmscompanion.app.ui.theme.BmsTheme
import com.bmscompanion.app.ui.theme.Hud
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/**
 * `--arsenalrender <out folder>` — the Reference section's Arsenal (Arsenal.kt), headless, from the bundled data:
 *
 * 1. No placeholder (BMS's "*free", "--Free Slot--" …) among the aircraft, stores, encyclopedia or threats, and no
 *    store carried by one.
 * 2. The Aircraft list scrolled well down: the search field stays where it was (pinned above the list).
 * 3. A store's page (AIM-120C, Mk-82): the picture, Tactical reference, General info, then Carried by (folded), each
 *    under the one before in the page's first column; then Carried by opened with one family unfolded (F-16).
 *
 * At a phone (400 x 800 dp), a tablet (1280 x 800, the rail beside it) and a PC window (1600 x 1000).
 */
object ArsenalRender {
    private const val RAIL_DP = 80

    private data class Shape(val name: String, val w: Int, val h: Int, val d: Float, val rail: Boolean)

    private val SHAPES = listOf(
        Shape("phone-400x800", 400, 800, 2.625f, rail = false),
        Shape("tablet-1280x800", 1280, 800, 1f, rail = true),
        Shape("pc-1600x1000", 1600, 1000, 1f, rail = false),
    )

    fun run(out: File): String = buildString {
        out.mkdirs()
        org.jetbrains.skia.Surface.makeRasterN32Premul(1, 1).close()
        appendLine("The Arsenal (Reference): placeholders, the pinned search, a store's page")
        appendLine()
        appendLine("1. Placeholders:")
        val ac = runBlocking { Repo.aircraft() }
        val wp = runBlocking { Repo.weapons() }
        val en = runBlocking { Repo.encyclopedia() }
        val th = runBlocking { Repo.threats() }
        val raw = File("app/src/main/assets/data/aircraft.json").takeIf { it.exists() }?.readText()
        fun ok(b: Boolean) = if (b) "ok  " else "FAIL"
        appendLine("${ok(ac.none { Placeholders.isPlaceholder(it.name) })} aircraft: ${ac.size}, none a placeholder")
        appendLine("${ok(wp.none { Placeholders.isPlaceholder(it.name) })} stores: ${wp.size}, none a placeholder")
        appendLine("${ok(wp.none { w -> w.carriedBy.any { Placeholders.isPlaceholder(it) } })} no store carried by a placeholder")
        val keys = ac.map { it.key }.toSet()
        val dangling = wp.flatMap { w -> w.carriedBy.filter { it !in keys }.map { "${w.name} → $it" } }
        appendLine("${ok(dangling.isEmpty())} every carrier is a listed aircraft${if (dangling.isEmpty()) "" else ": " + dangling.take(10)}")
        appendLine("${ok(en.none { Placeholders.isPlaceholder(it.name) })} encyclopedia: ${en.size}, none a placeholder")
        appendLine("${ok(th.none { Placeholders.isPlaceholder(it.name) })} threats: ${th.size}, none a placeholder")
        if (raw != null) appendLine("${ok("*free" !in raw && "Free Slot" !in raw)} the bundled aircraft.json carries no \"*free\" / \"--Free Slot--\"")
        appendLine("${ok(listOf("F-5A Freedom Fighter", "*free", "--Free Slot--", "", "none").map(Placeholders::isPlaceholder) == listOf(false, true, true, true, true))} the rule: whole names only")

        appendLine()
        appendLine("2. The Aircraft list scrolled down:")
        for (s in SHAPES) {
            WdpProbe.on = true
            WdpProbe.rects.keys.removeAll { it.startsWith("arsenal/") }
            scene(s, { ArsenalPage(rememberNavController()) }) { ctx -> val scene = ctx.scene; val render = ctx::render
                render(40)
                val before = WdpProbe.rects["arsenal/search"]
                val listX = ((if (s.rail) RAIL_DP else 0) + 150) * s.d
                val listY = s.h * 0.6f * s.d
                repeat(30) {
                    scene.sendPointerEvent(PointerEventType.Scroll, Offset(listX, listY), scrollDelta = Offset(0f, 3f))
                    render(1)
                }
                render(20)
                val after = WdpProbe.rects["arsenal/search"]
                File(out, "arsenal-aircraft-scrolled-${s.name}.png").writeBytes(ctx.png())
                val same = before != null && after != null && kotlin.math.abs(before.top - after.top) < 1f && after.top >= 0f
                appendLine("${ok(same)} ${s.name}: search at ${before?.top?.div(s.d)?.toInt()} dp before, ${after?.top?.div(s.d)?.toInt()} dp after the scroll · arsenal-aircraft-scrolled-${s.name}.png")
            }
            WdpProbe.on = false
        }

        appendLine()
        appendLine("3. A store's page:")
        for (key in listOf("aim-120c-amraam", "mk-82")) for (s in SHAPES) {
            WdpProbe.on = true
            WdpProbe.rects.keys.removeAll { it.startsWith("arsenal/") }
            Repo.putInt("open_wp_carriers", 0)
            Repo.putInt("open_wp_tacref", 1)
            Repo.putInt("open_wp_general", 1)
            scene(s, { WeaponDetail(rememberNavController(), key, onBack = {}) }) { ctx -> val scene = ctx.scene; val render = ctx::render
                render(40)
                File(out, "weapon-$key-${s.name}.png").writeBytes(ctx.png())
                val r = WdpProbe.rects
                val (t, g, c) = Triple(r["arsenal/tacref"], r["arsenal/general"], r["arsenal/carriers"])
                val order = t != null && g != null && c != null && t.bottom <= g.top + 1 && g.bottom <= c.top + 1 && t.left == g.left && g.left == c.left
                appendLine(
                    "${ok(order)} $key ${s.name}: Tactical reference ${t?.top?.div(s.d)?.toInt()} dp, General info ${g?.top?.div(s.d)?.toInt()}, " +
                        "Carried by ${c?.top?.div(s.d)?.toInt()} (folded ${c?.height?.div(s.d)?.toInt()} dp tall) · weapon-$key-${s.name}.png",
                )
            }
            // Carried by open, the F-16 family unfolded: a separate scene, the card's fold being read when it is first drawn
            Repo.putInt("open_wp_carriers", 1)
            WdpProbe.rects.keys.removeAll { it.startsWith("arsenal/") }
            scene(s, { WeaponDetail(rememberNavController(), key, onBack = {}) }) { ctx -> val scene = ctx.scene; val render = ctx::render
                render(40)
                val fam: androidx.compose.ui.geometry.Rect = WdpProbe.rects["arsenal/family/F-16"]
                    ?: run { appendLine("FAIL $key ${s.name}: no F-16 family row"); return@scene }
                val at = fam.center
                // on a phone the row may be below the fold: scroll the page until it is in view
                var guard = 0
                var row = fam
                while (row.center.y > s.h * s.d - 40 && guard++ < 40) {
                    scene.sendPointerEvent(PointerEventType.Scroll, Offset(row.center.x, s.h * s.d / 2), scrollDelta = Offset(0f, 2f))
                    render(2)
                    row = WdpProbe.rects["arsenal/family/F-16"] ?: row
                }
                // let the wheel's smooth scroll come to rest: a press while it moves only stops it
                render(30)
                row = WdpProbe.rects["arsenal/family/F-16"] ?: row
                val c0 = WdpProbe.rects["arsenal/carriers"]
                scene.sendPointerEvent(PointerEventType.Press, row.center)
                render(1)
                scene.sendPointerEvent(PointerEventType.Release, row.center)
                render(20)
                val c1 = WdpProbe.rects["arsenal/carriers"]
                // and bring the opened family into view
                guard = 0
                while ((WdpProbe.rects["arsenal/family/F-16"]?.top ?: 0f) > s.h * s.d * 0.25f && guard++ < 40) {
                    scene.sendPointerEvent(PointerEventType.Scroll, Offset(row.center.x, s.h * s.d / 2), scrollDelta = Offset(0f, 2f))
                    render(2)
                }
                render(10)
                File(out, "weapon-$key-carriers-${s.name}.png").writeBytes(ctx.png())
                val grew = c0 != null && c1 != null && c1.height > c0.height + 40
                appendLine("${ok(grew)} $key ${s.name}: F-16 unfolded, Carried by ${c0?.height?.div(s.d)?.toInt()} → ${c1?.height?.div(s.d)?.toInt()} dp (at ${at.y.div(s.d).toInt()} dp) · weapon-$key-carriers-${s.name}.png")
            }
            Repo.putInt("open_wp_carriers", 0)
            WdpProbe.on = false
        }

        appendLine()
        appendLine("4. An aircraft's page (F-15C, eight theater variants): the variant drop-down, closed and open, and the stations")
        for (s in SHAPES) {
            WdpProbe.on = true
            WdpProbe.rects.keys.removeAll { it.startsWith("arsenal/") }
            scene(s, { com.bmscompanion.app.ui.screens.AircraftDetail(rememberNavController(), "f-15c", onBack = {}) }) { ctx ->
                ctx.render(40)
                File(out, "aircraft-f-15c-${s.name}.png").writeBytes(ctx.png())
                val field = WdpProbe.rects["arsenal/variants"]
                val st = WdpProbe.rects["arsenal/stations"]
                val width = s.w * s.d // the probes are in the scene's own coordinates, the rail included
                appendLine("${ok(field != null)} ${s.name}: variant field ${field?.let { "${(it.width / s.d).toInt()} dp wide" } ?: "not drawn"}")
                appendLine("${ok(st != null && st.right <= width)} ${s.name}: stations ${st?.let { "${(it.left / s.d).toInt()}-${(it.right / s.d).toInt()} dp, ${(it.height / s.d).toInt()} dp tall" } ?: "not drawn"} (page ${(width / s.d).toInt()} dp wide)")
                if (field != null) {
                    ctx.scene.sendPointerEvent(PointerEventType.Press, field.center)
                    ctx.render(1)
                    ctx.scene.sendPointerEvent(PointerEventType.Release, field.center)
                    ctx.render(20)
                    File(out, "aircraft-f-15c-variants-${s.name}.png").writeBytes(ctx.png())
                    appendLine("     pictures aircraft-f-15c-${s.name}.png, aircraft-f-15c-variants-${s.name}.png")
                }
            }
            WdpProbe.on = false
        }
    }

    private class Ctx(val scene: ImageComposeScene) {
        private var t = 0L
        fun render(n: Int) = repeat(n) { scene.render(t).close(); t += 50_000_000; Thread.sleep(25) }
        fun png(): ByteArray = scene.render(t).encodeToData(EncodedImageFormat.PNG)!!.bytes.also { t += 50_000_000 }
    }

    private fun StringBuilder.scene(
        s: Shape,
        content: @Composable () -> Unit,
        body: (Ctx) -> Unit,
    ) {
        val scene = ImageComposeScene((s.w * s.d).toInt(), (s.h * s.d).toInt(), Density(s.d)) {
            CompositionLocalProvider(LocalConfiguration provides Configuration(s.w, s.h)) {
                BmsTheme {
                    Row(Modifier.fillMaxSize().background(Hud.Bg)) {
                        if (s.rail) Box(Modifier.width(RAIL_DP.dp).fillMaxHeight().background(Hud.Surface))
                        Box(Modifier.weight(1f).fillMaxHeight()) { content() }
                    }
                }
            }
        }
        try {
            body(Ctx(scene))
        } catch (e: Throwable) {
            appendLine("FAIL ${s.name}: ${e::class.simpleName}: ${e.message}")
        } finally {
            scene.close()
        }
    }
}
