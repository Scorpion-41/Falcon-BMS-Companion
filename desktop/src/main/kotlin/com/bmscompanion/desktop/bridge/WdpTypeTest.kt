@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)

package com.bmscompanion.desktop.bridge

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.scene.ComposeScenePointer
import androidx.compose.ui.unit.Density
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.mission.MissionData
import com.bmscompanion.app.data.wdp.WdpControl
import com.bmscompanion.app.data.wdp.WdpForm
import com.bmscompanion.app.data.wdp.WdpValues
import com.bmscompanion.app.ui.screens.wdp.WdpDialog
import com.bmscompanion.app.ui.screens.wdp.WdpDialogs
import com.bmscompanion.app.ui.screens.wdp.WdpMessage
import com.bmscompanion.app.ui.screens.wdp.WdpMission
import com.bmscompanion.app.ui.screens.wdp.WdpPage
import com.bmscompanion.app.ui.screens.wdp.WdpProbe
import com.bmscompanion.app.ui.screens.wdp.WdpSession
import com.bmscompanion.app.ui.screens.wdp.WdpWiring
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Surface
import java.awt.event.KeyEvent as AwtKey
import java.io.File

/**
 * `--wdptypetest <out folder> [briefing.txt] [cartridge.ini]` — typing into every box of the Planner, key by key,
 * the way a pilot does, and checking after every key that the box shows what a Windows TextBox would.
 *
 * The real Planner ([com.bmscompanion.app.ui.screens.wdp.WdpPlanner], header, page and child windows)
 * is composed in a headless scene the size of a PC window. For every box a pilot can type in — each text box, each
 * up/down box and each combo that takes typing, on every page, every DTC tab, and every child window one level
 * down (found by pressing everything on the pages) — the check:
 *
 * 1. presses the mouse at the box's centre and demands that the box then has the keyboard (a box under another
 *    control, as the DTC page's pilot box was under the pilot's name, fails here);
 * 2. selects all and deletes, types a realistic text a key at a time (a callsign "VIPER 21", a frequency "251.300",
 *    numbers in the shape the box already shows, mixed case elsewhere) and after every key compares the box's text
 *    and caret with what Windows shows;
 * 3. moves the caret three to the left, types in the middle, deletes it again, goes to the end, deletes and retypes
 *    the last character — the caret must stay where the keys put it, never jump to the end;
 * 4. in an up/down box types a letter, which must be refused;
 * 5. commits with Enter (a page) or Tab (a window, where Enter presses the window's Apply) and leaves the box, then
 *    reads back what the page's wiring holds: the text typed, or WDP's own validated form of it (a clamped number,
 *    a masked frequency), which is listed for each box.
 *
 * Every box gets a picture, before above and after below (`boxes/`), and a line PASS or FAIL with the step that
 * failed. The DTC page's "Change" pilot box — the box the pilot reported — is also checked on its own: Change must
 * put the keyboard in it straight away, and Enter must accept it. Last, on a phone-sized scene, two fingers must zoom
 * the page and one finger must still type and still move a slider.
 *
 * Run with APPDATA on a scratch folder: pages remember settings. The cartridge is read, never written
 * ([WdpDtcFixture]).
 */
internal object WdpTypeTest {
    private const val W = 1600
    private const val H = 1000
    /** BMSC_TYPE_ONLY=<regex>: only the boxes whose "<page or window>/<box>" matches (a quick look at a few) */
    private val only: Regex? = System.getenv("BMSC_TYPE_ONLY")?.takeIf { it.isNotBlank() }?.let { Regex(it) }
    private fun wanted(where: String, box: String) = only?.containsMatchIn("$where/$box") ?: true

    private class Result(val where: String, val box: String, val kind: String, val verdict: String, val detail: String)

    /**
     * Runs [block] on the UI thread, as the app's window runs everything: the scene, its input and the pages' wirings.
     * Driving the scene from the check's own thread while Compose sends its snapshot notices on the UI thread lost a
     * key now and then (one in several thousand, a different box each run), which a window never does.
     */
    private fun <T> edt(block: () -> T): T {
        if (javax.swing.SwingUtilities.isEventDispatchThread()) return block()
        var r: Result2<T>? = null
        javax.swing.SwingUtilities.invokeAndWait { r = try { Result2(block(), null) } catch (t: Throwable) { Result2(null, t) } }
        r!!.error?.let { throw it }
        @Suppress("UNCHECKED_CAST") return r!!.value as T
    }
    private class Result2<T>(val value: T?, val error: Throwable?)

    fun run(out: File, briefing: File?, cartridge: File?): String {
        out.mkdirs()
        val pngDir = File(out, "boxes").also { it.mkdirs() }
        val report = StringBuilder()
        val results = ArrayList<Result>()
        val data = WdpFixtureMission.data(briefing, cartridge)
        val theater = System.getenv("BMSC_WDP_THEATER")?.let { name ->
            runBlocking { com.bmscompanion.app.ui.screens.wdp.plannerTheater(Repo.index().theaters, name) }
        }
        val mission = WdpFixtureMission.mission(data, theater)
        // the Planner's own pages, as the tab makes them; the DTC page on the check's copy of the cartridge, never the PC's
        WdpDtcFixture.file = cartridge
        val s = WdpSession
        s.dtcSource = WdpDtcFixture.source()
        s.theaterName = theater?.name; s.theater = theater
        val perfIni = Repo.getString(com.bmscompanion.app.ui.screens.wdp.PerformanceWiring.INI_KEY)
        Repo.putString(com.bmscompanion.app.ui.screens.wdp.PerformanceWiring.INI_KEY, null)
        if (data.briefing != null || data.dtc != null) {
            for (w in listOf(s.toss, s.popup, s.hadb)) w.onMission(mission)
            runBlocking { s.dataCard.prepare(mission) { cartridge?.takeIf { it.isFile }?.readText() } }
            s.dtc.onMission(mission)
            runBlocking { s.performance.prepare(mission) }
            com.bmscompanion.app.ui.screens.wdp.WdpHandOff.cardToPerformance(s.dataCard, s.performance, mission.briefing)
            s.appliedMission = mission
            Thread.sleep(1500)
        }
        if (System.getenv("BMSC_TYPE_BENCH") != null) return bench(out)
        WdpProbe.on = true
        WdpProbe.clear()
        report.appendLine("Weapon Delivery Planner: typing into every box, key by key")
        report.appendLine("briefing: ${if (data.briefing != null) "read" else "none"}, cartridge: ${if (data.dtc != null) "read" else "none"}, theater: ${theater?.name ?: "none"}")
        report.appendLine("scene: $W x $H px at density 1 (a PC window)")
        report.appendLine()

        val d = Driver(W, H, 1f) { com.bmscompanion.app.ui.screens.wdp.WdpPlanner(data, theater?.name, Modifier.fillMaxSize()) }
        try {
            // the pilot's box first: the one the pilot reported
            if (wanted("DTC pilot", "txtPilot")) pilotBox(d, report, results, pngDir)
            // the child windows, while the pages are as the mission left them
            if (only == null || only.containsMatchIn("fcls")) windows(d, report, results, pngDir, mission, cartridge)
            // every page, every DTC tab
            pages(d, report, results, pngDir)
        } catch (e: Throwable) {
            report.appendLine("FAIL the check itself threw ${e::class.simpleName}: ${e.message}")
            e.stackTrace.take(12).forEach { report.appendLine("       at $it") }
        } finally {
            d.close()
        }
        // the phone: pinch zoom, and one finger still types and still moves a slider
        if (wanted("phone", "")) try { phone(report, results, pngDir, data, theater?.name) } catch (e: Throwable) {
            report.appendLine("FAIL the phone check threw ${e::class.simpleName}: ${e.message}")
            e.stackTrace.take(12).forEach { report.appendLine("       at $it") }
        }
        WdpProbe.on = false
        edt { WdpDialogs.stack.clear() }
        s.page = WdpPage.DATACARD.name
        Repo.putString(com.bmscompanion.app.ui.screens.wdp.PerformanceWiring.INI_KEY, perfIni)

        // the table, then the summary
        report.appendLine()
        report.appendLine("== every box")
        for (r in results) report.appendLine(r.verdict.padEnd(6) + (r.where + " / " + r.box).padEnd(52) + r.kind.padEnd(8) + r.detail)
        report.appendLine()
        report.appendLine("== how long a typed character takes to reach the screen here (key events, the page's wiring, one frame at $W x $H)")
        for ((k, a) in d.keyTimes) if (a[1] > 0) report.appendLine("   " + k.padEnd(28) + "%.1f ms a key over %d keys".format(a[0] / 1e6 / a[1], a[1]))
        report.appendLine()
        report.appendLine("== by page and window")
        for ((where, rs) in results.groupBy { it.where }) {
            report.appendLine(where.padEnd(40) + "PASS ${rs.count { it.verdict == "PASS" }}".padEnd(10) + "FAIL ${rs.count { it.verdict == "FAIL" }}".padEnd(10) +
                "other ${rs.count { it.verdict != "PASS" && it.verdict != "FAIL" }}")
        }
        val fails = results.count { it.verdict == "FAIL" }
        val passes = results.count { it.verdict == "PASS" }
        report.appendLine()
        report.appendLine("boxes typed in: ${passes + fails}, PASS $passes, FAIL $fails; not typed: ${results.size - passes - fails} (each with its reason above)")
        report.appendLine(if (fails == 0 && passes > 0) "PASS" else "FAIL")
        File(out, "wdptypetest.txt").writeText(report.toString())
        return report.toString()
    }

    /** Developer's look at what a frame costs with a window up (BMSC_TYPE_BENCH): the host alone and its parts. */
    private fun bench(out: File): String {
        val r = StringBuilder()
        fun time(label: String, content: @androidx.compose.runtime.Composable () -> Unit) {
            val d = Driver(W, H, 1f, content)
            d.settle(10, 20)
            val t0 = System.nanoTime(); repeat(20) { d.frame().close() }
            r.appendLine(label.padEnd(50) + "%.1f ms a frame".format((System.nanoTime() - t0) / 2e7))
            d.close()
        }
        val msg = WdpMessage("Caution", "A message")
        time("nothing") { androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) }
        time("dim backdrop only") { androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.45f))) }
        WdpDialogs.stack.clear(); WdpDialogs.stack += msg
        time("host with a message box") { com.bmscompanion.app.ui.screens.wdp.WdpDialogHost(pageScale = 1f) }
        edt { WdpDialogs.stack.clear() }
        WdpDialogs.stack += com.bmscompanion.app.ui.screens.wdp.WdpDialog("fclsPackageNr", "Save DataCard")
        time("host with fclsPackageNr (no wiring)") { com.bmscompanion.app.ui.screens.wdp.WdpDialogHost(pageScale = 1f) }
        time("host with fclsPackageNr, no page scale") { com.bmscompanion.app.ui.screens.wdp.WdpDialogHost() }
        edt { WdpDialogs.stack.clear() }
        val form = runBlocking { Repo.wdpForm("fclsPackageNr") }!!
        time("the form alone, fitted") { com.bmscompanion.app.ui.screens.wdp.WdpFormView(form, WdpValues(), Modifier.fillMaxSize()) }
        time("the form alone, fixed 1.0") { com.bmscompanion.app.ui.screens.wdp.WdpFormView(form, WdpValues(), Modifier.fillMaxSize(), fixedScale = 1f) }
        time("the form alone, with a zoom") { com.bmscompanion.app.ui.screens.wdp.WdpFormView(form, WdpValues(), Modifier.fillMaxSize(), zoom = androidx.compose.runtime.remember { com.bmscompanion.app.ui.screens.wdp.WdpZoom() }) }
        File(out.also { it.mkdirs() }, "bench.txt").writeText(r.toString())
        return r.toString()
    }

    // ------------------------------------------------------------------------------------------------ the scene

    /** A headless scene with a keyboard and a mouse (and fingers), driven as AWT would drive the window. */
    private class Driver(val w: Int, val h: Int, density: Float, content: @androidx.compose.runtime.Composable () -> Unit) {
        val scene = edt { ImageComposeScene(w, h, Density(density), content = content) }
        private var nanos = 0L
        private var ms = 1_000L
        private val src = java.awt.Canvas()

        fun frame(): Image = edt {
            Snapshot.sendApplyNotifications()
            nanos += 16_666_667L
            scene.render(nanos)
        }

        fun settle(n: Int = 3, sleepMs: Long = 0) { repeat(n) { frame().close(); if (sleepMs > 0) Thread.sleep(sleepMs) } }

        fun click(at: Offset) {
            ms += 50
            edt {
                scene.sendPointerEvent(PointerEventType.Move, at, timeMillis = ms)
                scene.sendPointerEvent(PointerEventType.Press, at, timeMillis = ms, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
            }
            settle(1)
            ms += 60
            edt { scene.sendPointerEvent(PointerEventType.Release, at, timeMillis = ms, buttons = PointerButtons(), button = PointerButton.Primary) }
            settle(2)
            ms += 400   // never a double click
        }

        /** Fingers: each list is the pointers' positions at one moment; pressed while listed. */
        fun touch(frames: List<List<Offset>>) {
            var prev = emptyList<Offset>()
            for ((i, f) in frames.withIndex()) {
                ms += 16
                val pointers = f.mapIndexed { k, p -> ComposeScenePointer(PointerId(k.toLong()), p, pressed = true, type = PointerType.Touch) }
                val type = if (i == 0 || f.size > prev.size) PointerEventType.Press else PointerEventType.Move
                edt { scene.sendPointerEvent(type, pointers, timeMillis = ms) }
                settle(1)
                prev = f
            }
            ms += 16
            edt { scene.sendPointerEvent(PointerEventType.Release, prev.mapIndexed { k, p -> ComposeScenePointer(PointerId(k.toLong()), p, pressed = false, type = PointerType.Touch) }, timeMillis = ms) }
            settle(3)
            ms += 400
        }

        private fun key(id: Int, code: Int, ch: Char, mods: Int = 0) {
            val loc = if (id == AwtKey.KEY_TYPED) AwtKey.KEY_LOCATION_UNKNOWN else AwtKey.KEY_LOCATION_STANDARD
            ms += 20
            val e = composeKey(AwtKey(src, id, ms, mods, code, ch, loc))
            edt { scene.sendKeyEvent(e) }
        }

        /** How long a typed character took to reach the screen (the key events, the wiring, one frame), per page. */
        val keyTimes = LinkedHashMap<String, LongArray>()
        var timing = ""

        /** A character, as AWT delivers a key that types one: pressed, typed, released (with Shift for a capital). */
        fun type(c: Char) {
            val t0 = System.nanoTime()
            val code = AwtKey.getExtendedKeyCodeForChar(c.code)
            val shift = c.isUpperCase()
            val mods = if (shift) java.awt.event.InputEvent.SHIFT_DOWN_MASK else 0
            if (shift) key(AwtKey.KEY_PRESSED, AwtKey.VK_SHIFT, AwtKey.CHAR_UNDEFINED, mods)
            key(AwtKey.KEY_PRESSED, code, c, mods)
            key(AwtKey.KEY_TYPED, AwtKey.VK_UNDEFINED, c, mods)
            key(AwtKey.KEY_RELEASED, code, c, mods)
            if (shift) key(AwtKey.KEY_RELEASED, AwtKey.VK_SHIFT, AwtKey.CHAR_UNDEFINED, 0)
            settle(1)
            val a = keyTimes.getOrPut(timing) { LongArray(2) }
            a[0] += System.nanoTime() - t0; a[1]++
        }

        /** A key that types nothing printable (arrows, Home, End) or a control character (Backspace, Enter, Tab). */
        fun press(code: Int, typed: Char? = null, mods: Int = 0) {
            key(AwtKey.KEY_PRESSED, code, typed ?: AwtKey.CHAR_UNDEFINED, mods)
            if (typed != null) key(AwtKey.KEY_TYPED, AwtKey.VK_UNDEFINED, typed, mods)
            key(AwtKey.KEY_RELEASED, code, typed ?: AwtKey.CHAR_UNDEFINED, mods)
            settle(1)
        }

        fun backspace() = press(AwtKey.VK_BACK_SPACE, '\b')
        fun left() = press(AwtKey.VK_LEFT)
        fun end() = press(AwtKey.VK_END)
        fun enter() = press(AwtKey.VK_ENTER, '\n')
        fun tab() = press(AwtKey.VK_TAB, '\t')
        fun escape() = press(AwtKey.VK_ESCAPE, 27.toChar())

        fun selectAll() {
            val ctrl = java.awt.event.InputEvent.CTRL_DOWN_MASK
            key(AwtKey.KEY_PRESSED, AwtKey.VK_CONTROL, AwtKey.CHAR_UNDEFINED, ctrl)
            key(AwtKey.KEY_PRESSED, AwtKey.VK_A, 1.toChar(), ctrl)
            key(AwtKey.KEY_TYPED, AwtKey.VK_UNDEFINED, 1.toChar(), ctrl)
            key(AwtKey.KEY_RELEASED, AwtKey.VK_A, 1.toChar(), ctrl)
            key(AwtKey.KEY_RELEASED, AwtKey.VK_CONTROL, AwtKey.CHAR_UNDEFINED, 0)
            settle(1)
        }

        fun close() = runCatching { edt { scene.close() } }

        /** The key as the window hands it to Compose: its key and kind, with the AWT event inside (a typed character is read from it). */
        private fun composeKey(e: AwtKey): androidx.compose.ui.input.key.KeyEvent {
            val type = when (e.id) {
                AwtKey.KEY_PRESSED -> androidx.compose.ui.input.key.KeyEventType.KeyDown
                AwtKey.KEY_RELEASED -> androidx.compose.ui.input.key.KeyEventType.KeyUp
                else -> androidx.compose.ui.input.key.KeyEventType.Unknown
            }
            val key = if (e.id == AwtKey.KEY_TYPED) androidx.compose.ui.input.key.Key.Unknown else androidx.compose.ui.input.key.Key(e.keyCode, e.keyLocation)
            val cp = if (e.keyChar == AwtKey.CHAR_UNDEFINED) 0 else e.keyChar.code
            return androidx.compose.ui.input.key.KeyEvent(key, type, cp, e.isControlDown, e.isMetaDown, e.isAltDown, e.isShiftDown, e)
        }
    }

    // ------------------------------------------------------------------------------------------------ one box

    /** What the renderer shows in a control from the page's values ([com.bmscompanion.app.ui.screens.wdp.WdpFormView]). */
    private fun shownOf(v: WdpValues, c: WdpControl): String {
        val raw = v[c.name]?.takeUnless { it == "shown" || it == "hidden" }
        return v["${c.name}.text"] ?: raw ?: c.text ?: ""
    }

    private fun editable(c: WdpControl, v: WdpValues): Boolean = when (c.kind) {
        "text" -> !c.readOnly
        "number" -> true
        "combo" -> v["${c.name}.editable"] == "true"
        else -> false
    }

    /**
     * The boxes WDP checks as they are typed, putting its own figure back — the one thing a Windows TextBox does not
     * do by itself — with where WDP does it. A step there that shows the page's figure instead of the keys is that
     * check, and the box is committed from there.
     */
    private val PER_KEY = mapOf(
        "cntPerformance/txtCruiseAlt" to "WDP's txtCruiseAlt_TextChanged holds it to the service ceiling",
        "cntPerformance/txtWindSpd" to "WDP's ProgramFlow, run by TextChanged, holds the wind to 0-200 kt",
        "cntPerformance/txtWindDir" to "WDP's ProgramFlow, run by TextChanged, holds the direction to 0-359",
        "cntPerformance/txtTemp" to "WDP's ProgramFlow, run by TextChanged, holds the temperature to its table",
    )

    /** Each form's controls in designer order (the first is on top) and their kinds, for what covers what. */
    private val formOrder = HashMap<String, List<String>>()
    private val kindOf = HashMap<String, Map<String, String>>()

    private fun learn(f: WdpForm) {
        if (f.form in formOrder) return
        formOrder[f.form] = f.all().map { it.name }
        kindOf[f.form] = f.all().associate { it.name to it.kind }
    }

    /** The text a pilot would type in this box: in the shape it already shows, or what its name says it holds. */
    private fun textFor(c: WdpControl, current: String): String {
        val n = c.name.lowercase()
        val cur = current.trim()
        // a number, a frequency, a coordinate, a masked field: the same shape with the last digit moved on, which keeps
        // it a value WDP takes (inside the theater, inside the mask)
        if (cur.any { it.isDigit() } && cur.all { it.isDigit() || it in " ,.:'°/+-_" || it in "NSEWYX" }) {
            val i = cur.indexOfLast { it.isDigit() }
            val dgt = cur[i]
            return cur.substring(0, i) + (if (dgt == '9') '8' else dgt + 1) + cur.substring(i + 1)
        }
        if (c.kind == "number") return "12"
        // an empty masked box of the DTC page: what its mask asks for
        com.bmscompanion.app.data.wdp.DtcDesigner.all[c.name]?.mask?.takeIf { it.isNotEmpty() && cur.isEmpty() }?.let { m -> return fromMask(m) }
        return when {
            listOf("callsign", "pilot", "lead", "wing", "element", "missionname", "tanker", "awacs", "jstar", "fac", "name").any { n.contains(it) } &&
                !n.contains("notes") && !n.contains("loc") && !n.contains("uhf") && !n.contains("vhf") && !n.contains("tcn") -> "VIPER 21"
            listOf("uhf", "vhf", "freq", "ils").any { n.contains(it) } -> "251.300"
            listOf("tcn", "tacan").any { n.contains(it) } -> "59Y"
            n.contains("packagenr") || n.contains("txtpackage") -> "4521"
            listOf("fuel", "bingo", "joker", "alow", "msl", "alt", "kias", "deck", "elv", "spd", "temp", "wind", "range", "idm", "lsr", "laser",
                "space", "angle", "armdly", "ba", "ripple", "thr", "mode", "code", "stpt").any { n.contains(it) } -> "4520"
            else -> "Mixed Case 7"
        }
    }

    /** A value that fills a Windows mask: digits where it wants digits, letters where it wants letters, its literals. */
    private fun fromMask(m: String): String {
        val b = StringBuilder()
        var i = 0
        var digit = 1
        while (i < m.length) {
            when (val ch = m[i]) {
                '0', '9', '#' -> { b.append(('0' + digit % 10)); digit++ }
                'L', '?', 'A', 'a', '&', 'C' -> b.append('A')
                '<', '>', '|' -> {}
                '\\' -> { if (i + 1 < m.length) b.append(m[i + 1]); i++ }
                else -> b.append(ch)
            }
            i++
        }
        return b.toString()
    }

    /**
     * Types into one box as described on [WdpTypeTest] and says how it went. [read] gives the wiring's values now;
     * [window] is true for a box in a child window (committed with Tab, since Enter is the window's Apply).
     */
    private fun typeInto(d: Driver, where: String, form: String, c: WdpControl, read: () -> WdpValues, window: Boolean, pngDir: File): Result {
        val key = "$form/${c.name}"
        val kind = when { c.kind == "number" -> "up/down"; c.kind == "combo" -> "combo"; c.multiline -> "text(ml)"; else -> "text" }
        val rect = WdpProbe.rects[key] ?: return Result(where, c.name, kind, "SKIP", "not drawn")
        val before = d.frame()
        val stackBefore = WdpDialogs.stack.toList()
        val current = shownOf(read(), c)
        val drawnBefore = WdpProbe.rects.keys.toSet()
        // what is drawn over the box's middle, from controls the form draws later (on top): a picker an earlier press
        // left open over it is WDP's own behaviour, not a box that cannot be typed in
        fun coveredBy(): String? {
            val order = formOrder[form] ?: return null
            val mine = order.indexOf(c.name)
            return WdpProbe.rects.entries.asSequence()
                .filter { (k, r) -> k.startsWith("$form/") && k != key && r.contains(rect.center) }
                .map { it.key.substringAfter('/') }
                .filter { n -> val i = order.indexOf(n); i in 0 until mine && kindOf[form]?.get(n) in setOf("combo", "button", "list", "grid", "number", "text") }
                .firstOrNull()
        }
        coveredBy()?.let { over ->
            saveBox(pngDir, where, c.name, before, d.frame(), rect)
            return Result(where, c.name, kind, "COVER", "$over is drawn over it (a picker a press on the page shows, as in WDP)")
        }
        // an up/down box too narrow for its number (WDP's 18 px route spinners) is its two arrows: they are what is pressed
        val scale = WdpProbe.scales[form] ?: 1f
        if (c.kind == "number" && rect.width - 15f * scale - 3f < 8f) {
            val v0 = shownOf(read(), c)
            d.click(Offset(rect.right - 7f * scale, rect.top + rect.height * 0.25f))
            val v1 = shownOf(read(), c)
            d.click(Offset(rect.right - 7f * scale, rect.top + rect.height * 0.75f))
            val v2 = shownOf(read(), c)
            saveBox(pngDir, where, c.name, before, d.frame(), rect)
            val ok = v1 != v0 || v2 != v1
            return Result(where, c.name, kind, if (ok) "PASS" else "FAIL",
                "arrows only (${c.w} px wide in WDP, no room for the number): up made '$v0' '$v1', down made it '$v2'")
        }
        d.click(rect.center)
        val opened = WdpDialogs.stack.lastOrNull()?.takeIf { it !in stackBefore }
        if (opened != null) {
            val what = when (opened) { is WdpDialog -> "window ${opened.form} \"${opened.title}\""; is WdpMessage -> "message \"${opened.title}\""; else -> "?" }
            edt { while (WdpDialogs.stack.size > stackBefore.size) WdpDialogs.stack.removeAt(WdpDialogs.stack.lastIndex) }
            d.settle(2)
            saveBox(pngDir, where, c.name, before, d.frame(), rect)
            return Result(where, c.name, kind, "CLICK", "a press opens $what (WDP's Click handler on this box)")
        }
        if (WdpProbe.focused != key) {
            val shownNow = (WdpProbe.rects.keys - drawnBefore).map { it.substringAfter('/') }
            coveredBy()?.let { over ->
                saveBox(pngDir, where, c.name, before, d.frame(), rect)
                return Result(where, c.name, kind, "CLICK", "a press shows $over over it (WDP's Click handler on this box)")
            }
            val under = WdpProbe.rects.entries.filter { (k, r) -> k != key && k.startsWith("$form/") && r.contains(rect.center) }
                .minByOrNull { it.value.width * it.value.height }?.key
            saveBox(pngDir, where, c.name, before, d.frame(), rect)
            return Result(where, c.name, kind, "FAIL", "a press at its centre did not give it the keyboard (focused: ${WdpProbe.focused}; " +
                "smallest control there: $under; shown by the press: $shownNow; events: ${WdpProbe.events.takeLast(4)})")
        }
        WdpProbe.events.clear()
        val t = textFor(c, current)
        d.timing = where.substringBefore(" \"").substringBefore('/')
        var fail: String? = null
        val steps = ArrayList<String>()
        fun shows(): Pair<String, androidx.compose.ui.text.TextRange>? = WdpProbe.boxes[key]?.let { it.text to it.selection }
        var validated: String? = null
        fun expect(what: String, text: String, caret: Int) {
            var b = shows()
            // what the box shows is read after the frame that drew it; a key whose frame has not been drawn yet
            // gets two more frames, as a pilot's screen would draw them
            if (b == null || b.first != text || b.second.start != caret || b.second.end != caret) { d.settle(2); b = shows() }
            if (fail == null && validated == null && b != null && b.first != text && key in PER_KEY && b.first == shownOf(read(), c)) {
                // WDP checks this box as it is typed and puts its own figure back (listed in PER_KEY): that is the box
                validated = "$what made it '${b.first}' (${PER_KEY[key]})"
                return
            }
            if (fail == null && validated == null && (b == null || b.first != text || b.second.start != caret || b.second.end != caret)) {
                fail = "$what: Windows shows '${text.replace("\n", "\\n")}' with the caret at $caret; the box shows '${b?.first?.replace("\n", "\\n")}' caret ${b?.second}" +
                    " (keyboard: ${WdpProbe.focused}; events: ${WdpProbe.events.takeLast(5)})"
            }
            steps += what
        }
        d.selectAll(); d.backspace(); expect("select all, Backspace", "", 0)
        for (i in t.indices) { d.type(t[i]); expect("typed '${t.substring(0, i + 1)}'", t.substring(0, i + 1), i + 1) }
        val back = minOf(3, t.length)
        repeat(back) { d.left() }
        expect("$back x Left", t, t.length - back)
        val number = c.kind == "number"
        // in the middle of a number a pilot types a digit (WDP checks some number boxes as they are typed, the
        // Performance page's wind among them, and puts 0 back for a letter); elsewhere a letter
        val numeric = t.all { it.isDigit() || it in " ,.:'°/+-_" }
        val mid = if (number || numeric) '5' else 'X'
        d.type(mid)
        val withMid = t.substring(0, t.length - back) + mid + t.substring(t.length - back)
        expect("'$mid' typed in the middle", withMid, t.length - back + 1)
        // once WDP has put its own figure back (PER_KEY), the edits that assume the keys went in are not made: the box
        // is committed as it stands
        if (validated == null) { d.backspace(); expect("Backspace in the middle", t, t.length - back) }
        if (number && validated == null) { d.type('X'); expect("a letter in an up/down box is refused", t, t.length - back) }
        if (validated == null) { d.end(); expect("End", t, t.length) }
        if (validated == null) { d.backspace(); expect("Backspace at the end", t.dropLast(1), t.length - 1) }
        if (validated == null) { d.type(t.last()); expect("the last character again", t, t.length) }
        if (c.multiline) { d.enter(); expect("Enter in a box of several lines is a new line", t + "\n", t.length + 1); d.backspace(); expect("Backspace", t, t.length) }
        // commit: Enter on a page (it stays in the box, as in Windows), then Tab away; a window's box with Tab
        var afterEnter = ""
        if (!window && !c.multiline) {
            d.enter()
            val msg = WdpDialogs.stack.lastOrNull()?.takeIf { it !in stackBefore }
            afterEnter = when {
                msg is WdpMessage -> " (Enter: message \"${msg.title}\": ${msg.text.take(60).replace('\n', ' ')})"
                msg is WdpDialog -> " (Enter: opened ${msg.form})"
                WdpProbe.focused == key -> ""
                WdpProbe.rects[key] == null || !WdpProbe.boxes.containsKey(key) -> " (Enter: the box went, as its page wants)"
                else -> ""
            }
            edt { while (WdpDialogs.stack.size > stackBefore.size) WdpDialogs.stack.removeAt(WdpDialogs.stack.lastIndex) }
            d.settle(2)
        }
        if (WdpProbe.focused == key) d.tab()
        val stillFocused = WdpProbe.focused == key
        val msg2 = WdpDialogs.stack.lastOrNull()?.takeIf { it !in stackBefore }
        val leaveNote = when (msg2) {
            is WdpMessage -> " (on leaving: message \"${msg2.title}\": ${msg2.text.take(70).replace('\n', ' ')})"
            is WdpDialog -> " (on leaving: opened ${msg2.form})"
            else -> ""
        }
        edt { while (WdpDialogs.stack.size > stackBefore.size) WdpDialogs.stack.removeAt(WdpDialogs.stack.lastIndex) }
        d.settle(2)
        val after = d.frame()
        saveBox(pngDir, where, c.name, before, after, rect)
        val held = shownOf(read(), c)
        val onScreen = WdpProbe.boxes[key]?.text
        if (fail == null && stillFocused) fail = "Tab did not move the keyboard out of the box (events: ${WdpProbe.events.takeLast(6)})"
        if (fail == null && onScreen != null && WdpProbe.rects.containsKey(key) && onScreen != held) fail = "left, the box shows '$onScreen' but the page holds '$held'"
        if (fail != null) return Result(where, c.name, kind, "FAIL", fail!!)
        val outcome = when {
            validated != null -> "left as '$held'"
            held == t -> "kept '$t'"
            held.trim() == t.trim() -> "kept '$t' (trimmed)"
            else -> "typed '$t', the page made it '$held'"
        }
        return Result(where, c.name, kind, "PASS", "${steps.size} steps as Windows; " + (validated?.let { "$it; then " } ?: "") + "$outcome$afterEnter$leaveNote")
    }

    /** The box before (top) and after (below), a strip of the scene round it, framed. */
    private fun saveBox(dir: File, where: String, name: String, before: Image, after: Image, r: Rect) {
        try {
            val pad = 40f
            val l = (r.left - pad).coerceAtLeast(0f); val t = (r.top - pad / 2).coerceAtLeast(0f)
            val rr = (r.right + pad).coerceAtMost(before.width.toFloat()); val b = (r.bottom + pad / 2).coerceAtMost(before.height.toFloat())
            val w = (rr - l).toInt().coerceAtLeast(1); val h = (b - t).toInt().coerceAtLeast(1)
            val surface = Surface.makeRasterN32Premul(w, h * 2 + 4)
            val c = surface.canvas
            c.clear(0xFF808080.toInt())
            val src = org.jetbrains.skia.Rect.makeLTRB(l, t, rr, b)
            c.drawImageRect(before, src, org.jetbrains.skia.Rect.makeXYWH(0f, 0f, w.toFloat(), h.toFloat()))
            c.drawImageRect(after, src, org.jetbrains.skia.Rect.makeXYWH(0f, h + 4f, w.toFloat(), h.toFloat()))
            val frame = Paint().apply { color = 0xFFFF00FF.toInt(); mode = org.jetbrains.skia.PaintMode.STROKE; strokeWidth = 1f }
            c.drawRect(org.jetbrains.skia.Rect.makeXYWH(r.left - l - 1, r.top - t - 1, r.width + 2, r.height + 2), frame)
            c.drawRect(org.jetbrains.skia.Rect.makeXYWH(r.left - l - 1, h + 4 + r.top - t - 1, r.width + 2, r.height + 2), frame)
            val safe = (where + "__" + name).replace(Regex("[^A-Za-z0-9_.-]"), "_")
            File(dir, "$safe.png").writeBytes(surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes)
            surface.close()
        } catch (_: Throwable) {
        } finally {
            before.close(); after.close()
        }
    }

    // ------------------------------------------------------------------------------------------------ the pilot box

    /**
     * The DTC page's pilot: Change must put the keyboard in the box straight away and the box must be over the
     * pilot's name (WDP's BringToFront and Focus); what is typed must show as typed; Enter must accept it, as WDP's
     * KeyDown does. The fixture answers every name as its own, so a name it does not know is "not found", as WDP says.
     */
    private fun pilotBox(d: Driver, report: StringBuilder, results: ArrayList<Result>, pngDir: File) {
        val s = WdpSession
        edt { s.page = WdpPage.DTC.name; s.dtc.onValue("tabDTC", "tabMain") }
        d.settle(12, 40)
        val change = WdpProbe.rects["cntDTC/btnPilot"]
        if (change == null) { results += Result("DTC pilot (Change)", "txtPilot", "text", "FAIL", "the Change button is not on the page"); return }
        val before = d.frame()
        d.click(change.center)
        d.settle(3)
        val focused = WdpProbe.focused == "cntDTC/txtPilot"
        val box = WdpProbe.rects["cntDTC/txtPilot"]
        val lines = ArrayList<String>()
        lines += "Change pressed: the box is ${if (box != null) "shown" else "NOT shown"} and ${if (focused) "has the keyboard" else "does NOT have the keyboard"}"
        var ok = box != null && focused
        if (ok) {
            d.selectAll()
            for (ch in "VIPER 21") d.type(ch)
            val shown = WdpProbe.boxes["cntDTC/txtPilot"]?.text
            val caret = WdpProbe.boxes["cntDTC/txtPilot"]?.selection
            lines += "typed 'VIPER 21': the box shows '$shown', caret $caret"
            ok = ok && shown == "VIPER 21" && caret?.start == 8
            // the name label must not be over the box: a press in the middle of the box keeps the keyboard there
            d.click(box!!.center)
            val stays = WdpProbe.focused == "cntDTC/txtPilot"
            lines += "a press in the middle of the box ${if (stays) "keeps the keyboard in it" else "does NOT reach it (something is over it)"}"
            ok = ok && stays
            d.end()
            val mid = d.frame()
            d.enter()
            d.settle(4, 30)
            val top = WdpDialogs.stack.lastOrNull()
            val msg = (top as? WdpMessage)?.let { "\"${it.title}\": ${it.text.take(80).replace('\n', ' ')}" }
            d.settle(2)
            val gone = WdpProbe.rects["cntDTC/txtPilot"] == null
            lines += "Enter: " + (if (gone) "accepted (the box closed, as WDP's Accept)" else "the box is still open") + (msg?.let { " and the fixture answers $it" } ?: "")
            ok = ok && gone
            saveBox(pngDir, "DTC_pilot", "txtPilot", before, mid, box)
            edt { WdpDialogs.stack.clear() }
            d.settle(2)
            // the same with the fixture's own name, which it knows: the page loads it and says nothing
            WdpProbe.rects["cntDTC/btnPilot"]?.let { d.click(it.center) }
            d.selectAll()
            for (ch in "Fixture") d.type(ch)
            d.enter(); d.settle(4, 30)
            val quiet = WdpDialogs.stack.isEmpty()
            lines += "typed 'Fixture' + Enter: " + if (quiet) "loaded, no message" else "message: " + ((WdpDialogs.stack.lastOrNull() as? WdpMessage)?.text?.take(80) ?: "a window")
            edt { WdpDialogs.stack.clear() }; d.settle(2)
        } else before.close()
        report.appendLine("== the DTC page's pilot box (Change)")
        lines.forEach { report.appendLine("   $it") }
        report.appendLine()
        results += Result("DTC pilot (Change)", "txtPilot", "text", if (ok) "PASS" else "FAIL", lines.joinToString("; "))
    }

    // ------------------------------------------------------------------------------------------------ the pages

    private val DTC_TABS = listOf(
        "tabMain", "tabSTPT", "tabTargets", "tabLines", "tabThreats", "tabPPTman", "tabIFF", "tabOpen", "tabHarpoon",
        "tabEWS", "tabMFD", "tabRadio", "tabNavOffsets", "tabSystems", "tabWeapons", "tabHarm",
    )

    private fun wiringOf(p: WdpPage): WdpWiring = when (p) {
        WdpPage.TOSS -> WdpSession.toss
        WdpPage.POPUP -> WdpSession.popup
        WdpPage.HADB -> WdpSession.hadb
        WdpPage.DTC -> WdpSession.dtc
        WdpPage.PERFORMANCE -> WdpSession.performance
        WdpPage.BRIEFING, WdpPage.DATACARD, WdpPage.COORDINATION -> WdpSession.dataCard
        // the app's own page: no WDP layout, so no box of WDP's to type in (the loops below leave it out)
        WdpPage.MAP, WdpPage.ATO -> error("the app's own pages (Map, ATO Targets) have no WDP layout")
    }

    /**
     * What some pages show only after a press — the DataCard's take-off spec (pitch and power), a taxi-time spinner,
     * a route spinner — pressed so their boxes are typed in too.
     */
    private val REVEAL = mapOf(
        WdpPage.DATACARD to listOf("lblTOSpec", "lblRwyTaxiTime1", "lblRwyTaxiTime2", "lblCommName1"),
    )

    private fun pages(d: Driver, report: StringBuilder, results: ArrayList<Result>, pngDir: File) {
        val done = HashSet<String>()
        for (p in WdpPage.entries.filter { !it.native }) {
            val form = runBlocking { Repo.wdpForm(p.form) } ?: continue
            learn(form)
            val w = wiringOf(p)
            edt { WdpSession.page = p.name }
            val views: List<String?> = if (p == WdpPage.DTC) DTC_TABS else listOf(null)
            for (tab in views) {
                if (tab != null) edt { w.onValue("tabDTC", tab) }
                val reveals = listOf<String?>(null) + REVEAL[p].orEmpty()
                for (reveal in reveals) {
                    if (reveal != null) edt { w.onClick(reveal) }
                    d.settle(8, 30)
                    d.settle(2)
                    val where = p.label + (tab?.let { "/" + it.removePrefix("tab") } ?: "")
                    val v0 = w.values(p.hiddenHere())
                    for (c in form.all()) {
                        if (!editable(c, v0)) continue
                        val key = "${form.form}/${c.name}"
                        if (key in done) continue
                        if (WdpProbe.rects[key] == null) continue
                        if (!wanted(where, c.name)) continue
                        if (disabled(form, c, v0)) { done += key; results += Result(where, c.name, c.kind, "OFF", "disabled on the page"); continue }
                        done += key
                        results += typeInto(d, where, form.form, c, { w.values(p.hiddenHere()) }, window = false, pngDir = pngDir)
                        edt { WdpDialogs.stack.clear() }
                    }
                }
            }
            // the page's boxes it never showed: listed, so a box that cannot be reached is not silently missed
            val v = w.values(p.hiddenHere())
            val never = form.all().filter { editable(it, v) && "${form.form}/${it.name}" !in done }
            if (never.isNotEmpty() && p != WdpPage.BRIEFING && p != WdpPage.COORDINATION) {
                report.appendLine("note ${p.label}: ${never.size} boxes not shown on the page as the mission leaves it (stacked panels, other pages, shown by a press): " +
                    never.take(40).joinToString(" ") { it.name } + if (never.size > 40) " …" else "")
            }
        }
        edt { WdpSession.dtc.onValue("tabDTC", "tabMain") }
    }

    private fun disabled(form: WdpForm, c: WdpControl, v: WdpValues): Boolean {
        val parent = HashMap<String, String>()
        fun link(x: WdpControl) { for (k in x.children) { parent[k.name] = x.name; link(k) } }
        form.roots.forEach(::link)
        var n: String? = c.name
        while (n != null) { if (v["$n.enabled"] == "false") return true; n = parent[n] }
        return false
    }

    // ------------------------------------------------------------------------------------------------ the windows

    /** One way to open a window: the page, the press, and the message answer that leads to it (if one does). */
    private class Opener(val page: WdpPage, val tab: String?, val press: String, val answer: String?)

    private fun windows(d: Driver, report: StringBuilder, results: ArrayList<Result>, pngDir: File, mission: WdpMission, cartridge: File?) {
        // find them: every press on every page, on a page made for the purpose, and the answers of any question asked
        val found = LinkedHashMap<String, ArrayList<Opener>>()
        fun add(form: String, o: Opener) { val l = found.getOrPut(form) { ArrayList() }; if (l.size < 4) l += o }
        for (p in WdpPage.entries) {
            val form = runBlocking { Repo.wdpForm(p.form) } ?: continue
            val probe = WdpWirings.create(p) ?: continue
            runCatching { WdpWirings.feed(probe, mission, cartridge) }
            val presses = form.all().flatMap { c ->
                when (c.kind) {
                    "grid", "list" -> listOf("${c.name}:open:0", "${c.name}:row:0", "${c.name}:open:1")
                    "button", "label", "picture", "panel", "text", "number", "check", "radio" -> listOf(c.name)
                    else -> emptyList()
                }
            }
            for (press in presses) {
                edt { WdpDialogs.stack.clear(); runCatching { probe.onClick(press) } }
                val top = WdpDialogs.stack.lastOrNull()
                if (top is WdpDialog) add(top.form, Opener(p, null, press, null))
                if (top is WdpMessage) for (b in top.buttons) {
                    edt { WdpDialogs.stack.clear(); runCatching { probe.onClick(press) } }
                    val m = WdpDialogs.stack.lastOrNull() as? WdpMessage ?: continue
                    edt { WdpDialogs.stack.remove(m); runCatching { m.onAnswer?.invoke(b) } }
                    val t2 = WdpDialogs.stack.lastOrNull()
                    if (t2 is WdpDialog) add(t2.form, Opener(p, null, press, b))
                }
            }
            edt { WdpDialogs.stack.clear() }
        }
        WdpWirings.restoreSettings()
        report.appendLine("== child windows found by pressing everything: ${found.size}")
        for ((f, os) in found) report.appendLine("   $f  from " + os.joinToString(", ") { o -> "${o.page.label}: ${o.press}" + (o.answer?.let { " → \"$it\"" } ?: "") })
        report.appendLine()
        for ((f, openers) in found) {
            val form = runBlocking { Repo.wdpForm(f) } ?: continue
            learn(form)
            // does it have anything to type in at all? (a combo may take typing, which its window says when open)
            if (form.all().none { it.kind == "text" && !it.readOnly || it.kind == "number" || it.kind == "combo" }) continue
            edt { WdpDialogs.stack.clear() }
            // the first of its openers that opens it on the Planner's own page as the check has left it
            var o = openers[0]
            fun openWith(op: Opener): WdpDialog? {
                edt { WdpDialogs.stack.clear(); WdpSession.page = op.page.name; runCatching { wiringOf(op.page).onClick(op.press) } }
                if (op.answer != null) {
                    val m = WdpDialogs.stack.lastOrNull() as? WdpMessage ?: return null
                    edt { WdpDialogs.stack.remove(m); runCatching { m.onAnswer?.invoke(op.answer) } }
                }
                Thread.sleep(300)
                d.settle(8, 30)
                return (WdpDialogs.stack.lastOrNull() as? WdpDialog)?.takeIf { it.form == f }
            }
            fun open(): WdpDialog? {
                openWith(o)?.let { return it }
                for (op in openers) openWith(op)?.let { o = op; return it }
                return null
            }
            val dlg = open()
            // what a frame costs here with the window up, and with it closed over the same page (headless, on the processor)
            if (dlg != null) {
                fun ms(): Double { val t0 = System.nanoTime(); repeat(10) { d.frame().close() }; return (System.nanoTime() - t0) / 1e7 }
                val up = ms()
                val saved = edt { val l = WdpDialogs.stack.toList(); WdpDialogs.stack.clear(); l }; d.settle(2)
                val down = ms()
                edt { WdpDialogs.stack.addAll(saved) }; d.settle(3)
                report.appendLine("note $f: a frame takes %.1f ms with the window up, %.1f ms without it".format(up, down))
            }
            if (dlg == null) { results += Result(f, "(window)", "-", "SKIP", "the Planner's page did not open it with " + openers.joinToString { it.press }); continue }
            val boxes = form.all().filter { editable(it, dlg.wiring?.values(dlg.hidden) ?: WdpValues()) }
            if (boxes.isEmpty()) { edt { WdpDialogs.stack.clear() }; d.settle(2); continue }
            val where = "$f \"${dlg.title}\""
            for (c in boxes) {
                var cur = WdpDialogs.stack.lastOrNull() as? WdpDialog
                if (cur == null || cur.form != f) cur = open() ?: break
                val v0 = cur.wiring?.values(cur.hidden) ?: WdpValues()
                if (WdpProbe.rects["$f/${c.name}"] == null) { results += Result(where, c.name, c.kind, "SKIP", "not shown in the window as it opens"); continue }
                if (disabled(form, c, v0)) { results += Result(where, c.name, c.kind, "OFF", "disabled in the window"); continue }
                val now = cur
                results += typeInto(d, where, f, c, { now.wiring?.values(now.hidden) ?: WdpValues() }, window = true, pngDir = pngDir)
            }
            // Enter in the window: its AcceptButton, where it has one
            val cur = (WdpDialogs.stack.lastOrNull() as? WdpDialog)?.takeIf { it.form == f } ?: open()
            if (cur != null) {
                val first = boxes.firstOrNull { WdpProbe.rects["$f/${it.name}"] != null && !it.multiline }
                if (first != null) {
                    d.click(WdpProbe.rects["$f/${first.name}"]!!.center)
                    d.enter(); d.settle(3, 30)
                    val closed = cur !in WdpDialogs.stack
                    val top = WdpDialogs.stack.lastOrNull()
                    val what = when {
                        closed -> "closed the window (its Apply/OK ran)"
                        top is WdpMessage -> "Apply answered with a message \"${top.title}\": ${top.text.take(60).replace('\n', ' ')}"
                        else -> "left the window open"
                    }
                    report.appendLine("note $where: Enter in ${first.name} $what")
                }
                edt { WdpDialogs.stack.clear() }; d.settle(2)
                // Escape: its Cancel
                open()?.let { c2 ->
                    d.escape(); d.settle(2)
                    report.appendLine("note $where: Escape " + if (c2 !in WdpDialogs.stack) "closed it" else "left it open")
                }
            }
            edt { WdpDialogs.stack.clear() }; d.settle(2)
        }
        report.appendLine()
    }

    // ------------------------------------------------------------------------------------------------ the phone

    /**
     * A phone held upright (400 x 860 dp at 2.625): the page fitted whole is small, so two fingers zoom it. The check
     * pinches the DataCard open and demands the page is laid out larger, types into a box of the zoomed page, then
     * on TOSS (a new page opens fitted) drags a slider with one finger and demands it moved.
     */
    private fun phone(report: StringBuilder, results: ArrayList<Result>, pngDir: File, data: MissionData, theater: String?) {
        val density = 2.625f
        val pw = (400 * density).toInt()
        val ph = (860 * density).toInt()
        WdpSession.page = WdpPage.DATACARD.name
        WdpProbe.clear()
        val d = Driver(pw, ph, density) { com.bmscompanion.app.ui.screens.wdp.WdpPlanner(data, theater, Modifier.fillMaxSize()) }
        try {
            d.settle(12, 40)
            val fit = WdpProbe.scales["cntDataCard"] ?: 0f
            val rCard = WdpProbe.rects["cntDataCard/pnlPage_1"] ?: WdpProbe.rects.entries.firstOrNull { it.key.startsWith("cntDataCard/") }?.value
            val c = rCard?.center ?: Offset(pw / 2f, ph / 2f)
            val img0 = d.frame()
            // two fingers 120 px apart, opened to 520 px
            d.touch((0..8).map { i -> val half = 60f + i * 25f; listOf(Offset(c.x - half, c.y), Offset(c.x + half, c.y)) })
            d.settle(3)
            val zoomed = WdpProbe.scales["cntDataCard"] ?: 0f
            val ratio = if (fit > 0f) zoomed / fit else 0f
            val zoomOk = ratio > 2.5f
            results += Result("phone DataCard", "(pinch)", "zoom", if (zoomOk) "PASS" else "FAIL",
                "fitted at %.3f px a pixel, after the pinch %.3f (x%.2f; the fingers opened x4.33)".format(fit, zoomed, ratio))
            val img1 = d.frame()
            File(pngDir, "phone_pinch_before.png").writeBytes(img0.encodeToData(EncodedImageFormat.PNG)!!.bytes)
            File(pngDir, "phone_pinch_after.png").writeBytes(img1.encodeToData(EncodedImageFormat.PNG)!!.bytes)
            img0.close(); img1.close()
            // a box of the zoomed page, well inside the screen: one finger (a mouse press here) still types
            val v = WdpSession.dataCard.values(WdpPage.DATACARD.hiddenHere())
            val form = runBlocking { Repo.wdpForm("cntDataCard") }!!
            val box = form.all().firstOrNull { k ->
                k.kind == "text" && WdpProbe.rects["cntDataCard/${k.name}"]?.let { r -> r.left > 20 && r.top > 300 && r.right < pw - 20 && r.bottom < ph - 200 && r.height > 20 } == true
            }
            if (box != null) results += typeInto(d, "phone DataCard zoomed", "cntDataCard", box, { WdpSession.dataCard.values(WdpPage.DATACARD.hiddenHere()) }, window = false, pngDir = pngDir)
            else results += Result("phone DataCard zoomed", "(a box)", "text", "FAIL", "no box of the zoomed page is inside the screen")
            // TOSS: a new page opens fitted; one finger drags its slider
            edt { WdpSession.page = WdpPage.TOSS.name }
            d.settle(8, 30)
            val tossFit = WdpProbe.scales["cntTOSS"] ?: 0f
            val tform = runBlocking { Repo.wdpForm("cntTOSS") }!!
            val tv0 = WdpSession.toss.values(WdpPage.TOSS.hiddenHere())
            val slider = tform.all().firstOrNull { k -> k.kind == "slider" && WdpProbe.rects["cntTOSS/${k.name}"] != null && tv0["${k.name}.max"] != null }
            if (slider == null) results += Result("phone TOSS", "(slider)", "slider", "FAIL", "no slider drawn")
            else {
                val r = WdpProbe.rects["cntTOSS/${slider.name}"]!!
                val before = tv0[slider.name]
                val y = r.center.y
                val from = if ((before?.toIntOrNull() ?: 0) > ((tv0["${slider.name}.min"]?.toIntOrNull() ?: 0) + (tv0["${slider.name}.max"]?.toIntOrNull() ?: 10)) / 2) r.left + r.width * 0.8f else r.left + r.width * 0.2f
                val to = if (from > r.center.x) r.left + r.width * 0.25f else r.left + r.width * 0.75f
                d.touch((0..10).map { i -> listOf(Offset(from + (to - from) * i / 10f, y)) })
                val after = WdpSession.toss.values(WdpPage.TOSS.hiddenHere())[slider.name]
                val tossAfter = WdpProbe.scales["cntTOSS"] ?: 0f
                results += Result("phone TOSS", slider.name, "slider", if (after != before && tossAfter == tossFit) "PASS" else "FAIL",
                    "one finger dragged it from '$before' to '$after'; the page stayed fitted (%.3f → %.3f px a pixel), so one finger is the page's".format(tossFit, tossAfter))
                results += Result("phone TOSS", "(zoom on a new page)", "zoom", if (tossFit > 0f && tossFit < zoomed) "PASS" else "FAIL",
                    "TOSS opened fitted at %.3f px a pixel (the zoomed DataCard was %.3f)".format(tossFit, zoomed))
            }
        } finally {
            d.close()
            WdpSession.page = WdpPage.DATACARD.name
        }
    }
}
