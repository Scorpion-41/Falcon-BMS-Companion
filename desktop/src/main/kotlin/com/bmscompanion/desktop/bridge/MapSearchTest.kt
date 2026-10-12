@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)

package com.bmscompanion.desktop.bridge

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation.compose.rememberNavController
import com.bmscompanion.app.ui.screens.AirfieldsMapPage
import com.bmscompanion.app.ui.theme.BmsTheme
import com.bmscompanion.app.ui.theme.Hud
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.awt.event.KeyEvent as AwtKey
import java.io.File

/**
 * `--mapsearchtest <out folder>`: the Reference section's Map search picked with a mouse and with the keyboard, headless
 * at a PC window (1400 x 900): "Wonju-si" typed and its suggestion **clicked** (the list stayed open under the pointer;
 * up to 1.3.9 the press took the focus off the field, the list closed and only a whole name typed worked), then
 * "Wonju" typed and **Enter** pressed (the first suggestion, an airfield, opened beside the map and drawn as its own
 * diagram). Each pick must leave the search marker (the magenta ring) in the middle of the map. Pictures beside the report.
 */
object MapSearchTest {
    private class Owner : LifecycleOwner, ViewModelStoreOwner {
        private val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
        override val viewModelStore = ViewModelStore()
    }

    private fun <T> edt(f: () -> T): T {
        var r: Result<T>? = null
        javax.swing.SwingUtilities.invokeAndWait { r = runCatching(f) }
        return r!!.getOrThrow()
    }

    fun run(out: File): String = buildString {
        out.mkdirs()
        org.jetbrains.skia.Surface.makeRasterN32Premul(1, 1).close()
        val w = 1400; val h = 900
        val owner = Owner()
        val scene = edt {
            ImageComposeScene(w, h, Density(1f)) {
                CompositionLocalProvider(LocalLifecycleOwner provides owner, LocalViewModelStoreOwner provides owner) {
                    val nav = rememberNavController()
                    BmsTheme { Box(Modifier.fillMaxSize().background(Hud.Bg)) { AirfieldsMapPage(nav) } }
                }
            }
        }
        var nanos = 0L
        var ms = 1_000L
        val src = java.awt.Canvas()
        fun frame(): Image = edt { Snapshot.sendApplyNotifications(); nanos += 16_666_667L; scene.render(nanos) }
        fun settle(n: Int, sleep: Long = 30) = repeat(n) { frame().close(); Thread.sleep(sleep) }
        fun click(at: Offset) {
            ms += 50
            edt {
                scene.sendPointerEvent(PointerEventType.Move, at, timeMillis = ms)
                scene.sendPointerEvent(PointerEventType.Press, at, timeMillis = ms, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
            }
            settle(2)
            ms += 80
            edt { scene.sendPointerEvent(PointerEventType.Release, at, timeMillis = ms, buttons = PointerButtons(), button = PointerButton.Primary) }
            settle(3)
            ms += 400
        }
        fun key(id: Int, code: Int, ch: Char, mods: Int = 0) {
            ms += 20
            val e = AwtKey(src, id, ms, mods, code, ch, if (id == AwtKey.KEY_TYPED) AwtKey.KEY_LOCATION_UNKNOWN else AwtKey.KEY_LOCATION_STANDARD)
            val type = when (id) {
                AwtKey.KEY_PRESSED -> androidx.compose.ui.input.key.KeyEventType.KeyDown
                AwtKey.KEY_RELEASED -> androidx.compose.ui.input.key.KeyEventType.KeyUp
                else -> androidx.compose.ui.input.key.KeyEventType.Unknown
            }
            val k = if (id == AwtKey.KEY_TYPED) androidx.compose.ui.input.key.Key.Unknown else androidx.compose.ui.input.key.Key(code, e.keyLocation)
            val cp = if (ch == AwtKey.CHAR_UNDEFINED) 0 else ch.code
            edt { scene.sendKeyEvent(androidx.compose.ui.input.key.KeyEvent(k, type, cp, false, false, false, e.isShiftDown, e)) }
        }
        fun type(text: String) {
            for (c in text) {
                val code = AwtKey.getExtendedKeyCodeForChar(c.code)
                val mods = if (c.isUpperCase()) java.awt.event.InputEvent.SHIFT_DOWN_MASK else 0
                key(AwtKey.KEY_PRESSED, code, c, mods); key(AwtKey.KEY_TYPED, AwtKey.VK_UNDEFINED, c, mods); key(AwtKey.KEY_RELEASED, code, c, mods)
                settle(1)
            }
        }
        fun press(code: Int, typed: Char? = null) {
            key(AwtKey.KEY_PRESSED, code, typed ?: AwtKey.CHAR_UNDEFINED)
            if (typed != null) key(AwtKey.KEY_TYPED, AwtKey.VK_UNDEFINED, typed)
            key(AwtKey.KEY_RELEASED, code, typed ?: AwtKey.CHAR_UNDEFINED)
            settle(1)
        }
        fun shot(name: String): IntArray {
            val img = frame()
            val bytes = img.encodeToData(EncodedImageFormat.PNG)!!.bytes
            File(out, "$name.png").writeBytes(bytes)
            val bmp = org.jetbrains.skia.Bitmap.makeFromImage(img)
            val px = IntArray(w * h) { i -> bmp.getColor(i % w, i / w) }
            img.close()
            appendLine("     picture $name.png")
            return px
        }
        /** magenta pixels (the search marker, Hud.Magenta #E07BFF) within [r] px of ([cx], [cy]) */
        fun marker(px: IntArray, cx: Int, cy: Int, r: Int = 60): Int {
            var n = 0
            for (y in (cy - r).coerceAtLeast(0) until (cy + r).coerceAtMost(h)) for (x in (cx - r).coerceAtLeast(0) until (cx + r).coerceAtMost(w)) {
                val v = px[y * w + x]
                val rr = (v shr 16) and 0xFF; val gg = (v shr 8) and 0xFF; val bb = v and 0xFF
                if (rr > 190 && gg in 90..170 && bb > 220) n++
            }
            return n
        }
        fun check(ok: Boolean, what: String) = appendLine((if (ok) "ok    " else "FAIL  ") + what)

        try {
            settle(60, 50)
            // the search field: the slim bar at the map's top left
            val field = Offset(200f, 29f)
            click(field)
            type("Wonju-si")
            settle(5)
            shot("search-1-suggestions")
            // the first suggestion's row, under the field (8 + 42 + 6 px, a row about 38 px tall)
            click(Offset(200f, 76f))
            settle(40, 40)
            val a = shot("search-2-clicked")
            val ma = marker(a, w / 2, h / 2)
            check(ma > 40, "a suggestion clicked with the mouse puts the marker in the middle of the map ($ma marker pixels)")

            // clear, type again, Enter: the first suggestion (Wonju AB, an airfield) opens beside the map
            click(field)
            repeat(12){ press(AwtKey.VK_BACK_SPACE, '\b') }
            type("Wonju")
            settle(5)
            shot("search-3-typed")
            press(AwtKey.VK_ENTER, '\n')
            settle(60, 40)
            val b = shot("search-4-enter")
            // the airfield's page takes 461 px at the right; the map is what is left
            val mb = marker(b, (w - 461) / 2, h / 2)
            check(mb > 40, "Enter picks the first suggestion and puts the marker in the middle of the map ($mb marker pixels)")
        } catch (e: Throwable) {
            appendLine("FAIL  ${e::class.simpleName}: ${e.message}")
        } finally {
            runCatching { edt { scene.close() } }
        }
        val fails = lines().count { it.startsWith("FAIL") }
        appendLine(if (fails == 0) "ALL PASS" else "$fails FAIL")
    }
}
