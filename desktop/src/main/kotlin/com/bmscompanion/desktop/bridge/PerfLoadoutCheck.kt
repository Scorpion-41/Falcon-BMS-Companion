package com.bmscompanion.desktop.bridge

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.ui.screens.wdp.PerformanceWiring
import com.bmscompanion.app.ui.screens.wdp.WdpDialog
import com.bmscompanion.app.ui.screens.wdp.WdpDialogHost
import com.bmscompanion.app.ui.screens.wdp.WdpDialogs
import com.bmscompanion.app.ui.screens.wdp.WdpMessage
import com.bmscompanion.app.ui.screens.wdp.WdpProbe
import com.bmscompanion.app.ui.screens.wdp.plannerTheater
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import javax.swing.SwingUtilities

/**
 * The Performance page's Type list and its Loadout and Charts windows, driven the way a pilot uses them, with a picture
 * after every step — what `--wdpoutcome` cannot do, since it presses WDP's designer controls one at a time from a fresh
 * page, and the Loadout window's cards and store tiles are the app's own drawing.
 *
 * Run from a copy of the desktop jar with its own entry point, APPDATA pointed at a scratch folder (the page keeps its
 * Setup.ini settings in the app's preferences; nothing else is written anywhere but [outDir]):
 * `java -cp … com.bmscompanion.desktop.bridge.PerfLoadoutCheckKt <outDir> [briefing.txt] [cartridge.ini] [F-15 briefing.txt]`
 * with `BMSC_WDP_THEATER` naming the theater as for `--wdpoutcome`.
 */
fun main(args: Array<String>) {
    System.setProperty(DevGuard.PROPERTY, "1")
    Repo.init()
    val out = File(args.getOrElse(0) { "perf-loadout-check" }).also { it.mkdirs() }
    val report = try {
        PerfLoadoutCheck.run(out, args.getOrNull(1)?.let(::File), args.getOrNull(2)?.let(::File), args.getOrNull(3)?.let(::File))
    } catch (e: Throwable) {
        "FAIL: the check threw ${e::class.simpleName}: ${e.message}\n" + e.stackTrace.take(16).joinToString("\n") { "    at $it" }
    }
    File(out, "report.txt").writeText(report)
    println(report)
    kotlin.system.exitProcess(0)
}

internal object PerfLoadoutCheck {
    private var pass = 0
    private var fail = 0
    private val text = StringBuilder()

    private fun ok(cond: Boolean, what: String) {
        if (cond) pass++ else fail++
        text.appendLine((if (cond) "PASS " else "FAIL ") + what)
    }

    private fun info(s: String) { text.appendLine("     $s") }

    fun run(out: File, briefing: File?, cartridge: File?, otherBriefing: File?): String {
        text.appendLine("Performance: Type, the Loadout window and the Charts window, step by step")
        val theater = System.getenv("BMSC_WDP_THEATER")?.let { name -> runBlocking { plannerTheater(Repo.index().theaters, name) } }
        info("theater: ${theater?.name ?: "none"}; briefing: ${briefing?.name ?: "none"}")
        WdpProbe.on = true
        try {
            typeList(briefing, cartridge, theater)
            loadout(out, briefing, cartridge, theater)
            devices(out, briefing, cartridge, theater)
            charts(out, briefing, cartridge, theater)
            handOff(briefing, cartridge, theater)
            if (otherBriefing != null) otherAircraft(out, otherBriefing, theater)
        } finally {
            WdpProbe.on = false
            edt { WdpDialogs.stack.clear(); WdpProbe.clear() }
        }
        text.appendLine()
        text.appendLine("PASS $pass, FAIL $fail")
        text.appendLine(if (fail == 0) "ALL PASS" else "FAIL: $fail of ${pass + fail} checks")
        return text.toString()
    }

    private fun page(briefing: File?, cartridge: File?, theater: com.bmscompanion.app.data.Theater?): PerformanceWiring {
        val data = WdpFixtureMission.data(briefing, cartridge)
        val mission = WdpFixtureMission.mission(data, theater)
        runCatching { Repo.putString(PerformanceWiring.INI_KEY, "") }
        return edt { PerformanceWiring() }.also { p -> runBlocking { p.prepare(mission) } }
    }

    private fun v(p: PerformanceWiring) = edt { p.values(emptyList()) }

    // ------------------------------------------------------------------------------------------------ Type

    private fun typeList(briefing: File?, cartridge: File?, theater: com.bmscompanion.app.data.Theater?) {
        text.appendLine("\n== Type")
        val p = page(briefing, cartridge, theater)
        val v0 = v(p)
        info("Type: ${v0["cboType"]}; engine ${v0["lblPowerPlant_val"]}; load ${v0["lblLoadout_val"]} lb, drag ${v0["lblDrag_Val"]}, gross ${v0["lblGross_Val"]}")
        ok(v0["cboType.enabled"] == "true", "Type can be picked with a mission loaded (cboType.enabled = ${v0["cboType.enabled"]})")
        val items = v0["cboType.items"].orEmpty().split('\n')
        ok(items.size >= 60, "Type lists every F-16 WDP plans for (${items.size} items)")
        val start = v0["cboType"].orEmpty()
        val other = items.firstOrNull { it != start && it.startsWith("F-16C") && (it.contains("52") || it.contains("50")) } ?: items.first { it != start }
        edt { p.onValue("cboType", other) }
        val v1 = v(p)
        info("picked $other: engine ${v1["lblPowerPlant_val"]}; load ${v1["lblLoadout_val"]} lb, drag ${v1["lblDrag_Val"]}, gross ${v1["lblGross_Val"]}, rotate ${v1["lblRotate_Val"]}")
        ok(v1["cboType"] == other, "the pick is on the page (Type = ${v1["cboType"]})")
        ok(v1["lblLoadout_val"] != "0" || v0["lblLoadout_val"] == "0", "the stores came across to the $other (load ${v0["lblLoadout_val"]} → ${v1["lblLoadout_val"]}; WDP's TypeChange would have emptied it)")
        ok(!v1["lblRotate_Val"].isNullOrBlank(), "the figures are worked out for the $other (rotate ${v1["lblRotate_Val"]}, lift-off ${v1["lblLiftOff_Val"]})")
        edt { p.onValue("cboType", start) }
        val v2 = v(p)
        ok(v2["cboType"] == start && v2["lblLoadout_val"] == v0["lblLoadout_val"], "the mission's own jet picked back gets the mission's loadout (load ${v2["lblLoadout_val"]}, was ${v0["lblLoadout_val"]})")
        // a new pick that is not in the list changes nothing
        edt { p.onValue("cboType", "no such jet") }
        ok(v(p)["cboType"] == start, "a name that is not in the list changes nothing")
    }

    private fun otherAircraft(out: File, briefing: File, theater: com.bmscompanion.app.data.Theater?) {
        text.appendLine("\n== A mission flight of another aircraft (${briefing.name})")
        val p = page(briefing, null, theater)
        val v0 = v(p)
        val items = v0["cboType.items"].orEmpty().split('\n')
        info("Type: ${v0["cboType"]}; first items: ${items.take(3)}; load ${v0["lblLoadout_val"]}, gross ${v0["lblGross_Val"]}, rotate '${v0["lblRotate_Val"]}'")
        ok(items.firstOrNull() == v0["cboType"] && !v0["cboType"].orEmpty().contains("F-16"), "the mission's own jet heads the list and is picked (${items.firstOrNull()})")
        ok(v0["lblRotate_Val"].isNullOrBlank(), "no F-16 figures for it")
        edt { p.onValue("cboType", "F-16CM-50") }
        val v1 = v(p)
        ok(v1["cboType"] == "F-16CM-50" && !v1["lblRotate_Val"].isNullOrBlank(), "an F-16 picked against it is planned with its figures (rotate ${v1["lblRotate_Val"]}, engine ${v1["lblPowerPlant_val"]})")
        edt { p.onValue("cboType", items.first()) }
        val v2 = v(p)
        ok(v2["cboType"] == items.first() && v2["lblRotate_Val"].isNullOrBlank() && v2["lblLoadout_val"] == v0["lblLoadout_val"],
            "the mission's jet picked back: no F-16 figures, its own stores again (load ${v2["lblLoadout_val"]})")
        val d = Driver()
        try {
            edt { p.onClick("btnLoadout") }
            d.settle(4)
            d.png(File(out, "other-loadout.png"))
            ok(top()?.form == "fclsLoadout", "its Loadout window opens (${File(out, "other-loadout.png").name})")
            val pic = edt { top()?.wiring?.values(emptyList())?.get("picLoadout") }
            ok(pic != "hidden", "it is drawn from the app's own picture of the ${items.first()}, not WDP's F-16 (picLoadout ${pic ?: "shown, the app's picture over it"})")
            val order = d.keys("planner/Loadout/Station/").mapNotNull { k -> k.substringAfterLast('/').toIntOrNull()?.let { n -> d.rect(k)?.left?.let { n to it } } }
                .sortedBy { it.second }.map { it.first }
            ok(order.size >= 2 && order == order.sortedDescending(), "its stations run in number order across the window, as the F-16's do: $order")
        } finally { edt { WdpDialogs.stack.clear() }; d.close() }
    }

    // ------------------------------------------------------------------------------------------------ the card

    /**
     * The Planner's own page (the one with a scope) hands its figures to the Planner's DataCard itself when its own
     * windows change them, which the Planner's page events do not see: a card printed or sent straight from the
     * Performance page carries the loadout just set.
     */
    private fun handOff(briefing: File?, cartridge: File?, theater: com.bmscompanion.app.data.Theater?) {
        text.appendLine("\n== The DataCard follows the Loadout window's OK (the Planner's own page)")
        val data = WdpFixtureMission.data(briefing, cartridge)
        val mission = WdpFixtureMission.mission(data, theater)
        runCatching { Repo.putString(PerformanceWiring.INI_KEY, "") }
        val card = com.bmscompanion.app.ui.screens.wdp.WdpSession.dataCard
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        val p = edt { PerformanceWiring(scope) }
        runBlocking { card.prepare(mission); p.prepare(mission) }
        edt { com.bmscompanion.app.ui.screens.wdp.WdpHandOff.cardToPerformance(card, p, mission.briefing) }
        val g0 = edt { card.plan.text("lblGrossWgt") }
        ok(g0.isNotBlank() && g0 == v(p)["lblGross_Val"], "the card starts with the page's gross weight ($g0)")
        try {
            edt { p.onClick("btnLoadout") }
            val w = edt { top()?.wiring }
            ok(w != null, "Set opens the Loadout window")
            if (w != null) {
                // station 3, then the first store it takes that is not already there (a full load, and on 7 too), then OK
                edt { w.onClick("lblHpt3") }
                val opened = edt { w.values(emptyList())["lblLoadW"] }
                var row = 0
                while (row < 12 && edt { w.values(emptyList())["lblLoadW"] } == opened) { edt { w.onClick("dgvLoadOut:row:$row") }; row++ }
                val load = edt { w.values(emptyList())["lblLoadW"] }
                edt { w.onClick("btnOK") }
                yes()
                val page = v(p)
                val g1 = edt { card.plan.text("lblGrossWgt") }
                val d1 = edt { card.plan.text("lblDrag") }
                ok(top() == null && page["lblLoadout_val"] == load, "OK hands the new load to the page (${page["lblLoadout_val"]})")
                ok(g1 == page["lblGross_Val"] && g1 != g0 && d1 == page["lblDrag_Val"],
                    "the card has the new gross weight and drag at once, its page never opened ($g0 → $g1, drag $d1)")
            }
        } finally { edt { WdpDialogs.stack.clear() } }
    }

    // ------------------------------------------------------------------------------------------------ Loadout

    private fun top(): WdpDialog? = edt { WdpDialogs.stack.lastOrNull() as? WdpDialog }

    /** Answers the message box on top, if one is open (WDP's question after the Loadout window's OK). */
    private fun answer(a: String) = edt { (WdpDialogs.stack.lastOrNull() as? WdpMessage)?.let { m -> WdpDialogs.stack.remove(m); m.onAnswer?.invoke(a) } }

    /** Yes to WDP's loadout question, when it was asked (a mission is loaded). */
    private fun yes() { answer("Yes") }
    private fun wv(): Map<String, String> = edt { top()?.wiring?.values(emptyList())?.let { w -> KEYS.associateWith { k -> w[k] ?: "" } } ?: emptyMap() }
    private val KEYS = listOf("station", "filter", "both", "status", "stores", "lblLoadW", "lblDragVal", "lblGrossW", "lblFuelExtW", "rbnWingman", "rbnLead", "armed", "query")

    private fun loadout(out: File, briefing: File?, cartridge: File?, theater: com.bmscompanion.app.data.Theater?) {
        text.appendLine("\n== Loadout window")
        val p = page(briefing, cartridge, theater)
        val page0 = v(p)
        val d = Driver()
        var step = 0
        fun shot(name: String): String { val f = File(out, "%02d-%s.png".format(++step, name)); d.png(f); return f.name }
        try {
            edt { p.onClick("btnLoadout") }
            d.settle(6)
            ok(top()?.form == "fclsLoadout", "Set opens the Loadout window (${shot("open")})")
            val w0 = wv()
            info("stores: ${w0["stores"]}; load ${w0["lblLoadW"]}, drag ${w0["lblDragVal"]}, gross ${w0["lblGrossW"]}")
            ok(w0["lblLoadW"] == page0["lblLoadout_val"] && w0["lblDragVal"] == page0["lblDrag_Val"], "the window opens on the page's load and drag (${w0["lblLoadW"]} / ${w0["lblDragVal"]})")
            val cards = d.keys("planner/Loadout/Station/")
            info("station cards: ${cards.map { it.substringAfterLast('/') }}")
            ok(cards.size >= 9, "a card under each hardpoint (${cards.size})")
            val stores0 = d.keys("planner/Loadout/Store/")
            ok(stores0.size >= 10, "every store the jet carries is a tile, none scrolled away (${stores0.size} tiles)")
            // a card's rectangle lies under its hardpoint number
            val hp3 = d.rect("fclsLoadout/lblHpt3"); val c3 = d.rect("planner/Loadout/Station/3")
            ok(hp3 != null && c3 != null && kotlin.math.abs(hp3.center.x - c3.center.x) < 3f && c3.top <= hp3.top + 1f, "station 3's card hangs from its number under the picture")

            // pick station 3, then a bomb for it
            d.clickKey("planner/Loadout/Station/3")
            val w1 = wv()
            ok(w1["station"] == "3", "a tap on station 3 picks it (${shot("station3")})")
            val fits3 = d.keys("planner/Loadout/Store/").map { it.substringAfterLast('/') }
            info("tiles for station 3: ${fits3.size}")
            ok(fits3.size < stores0.size, "with a station picked, only what it carries is offered (${fits3.size} of ${stores0.size})")
            val bomb = fits3.firstOrNull { it.contains("mk-82", true) || it.contains("mk82", true) } ?: fits3.firstOrNull { it.contains("gbu", true) } ?: fits3.first()
            d.clickKey("planner/Loadout/Store/$bomb")
            val w2 = wv()
            info("after $bomb: ${w2["stores"]} — ${w2["status"]}")
            ok(w2["stores"]!!.contains("3:") && w2["stores"]!!.contains("x$bomb") && w2["stores"]!!.split(' ').any { it.startsWith("7:") && it.endsWith("x$bomb") },
                "one tap hangs a full load on 3 and, Both sides, on 7 (${shot("hung")})")
            ok(w2["lblLoadW"] != w1["lblLoadW"] && w2["lblDragVal"] != w1["lblDragVal"], "the weights change at once (load ${w1["lblLoadW"]} → ${w2["lblLoadW"]}, drag ${w1["lblDragVal"]} → ${w2["lblDragVal"]})")
            val n3 = w2["stores"]!!.split(' ').first { it.startsWith("3:") }.substringAfter(':').substringBefore('x').toInt()
            // one fewer
            d.clickKey("planner/Loadout/Minus/3")
            val w3 = wv()
            val n3b = w3["stores"]!!.split(' ').firstOrNull { it.startsWith("3:") }?.substringAfter(':')?.substringBefore('x')?.toInt() ?: 0
            ok(n3b == n3 - 1, "− takes one store off station 3 ($n3 → $n3b) and 7 alike: ${w3["stores"]} (${shot("minus")})")
            // one more than it takes
            repeat(4) { d.clickKey("planner/Loadout/Plus/3") }
            val w4 = wv()
            ok(w4["status"]!!.contains("at most") && top()?.form == "fclsLoadout" && edt { WdpDialogs.stack.none { it is WdpMessage } },
                "+ past the most it takes says so on the window's line, and the window stays: \"${w4["status"]}\" (${shot("plus-limit")})")
            // empty station 7: its pair goes with it
            d.clickKey("planner/Loadout/Clear/7")
            val w5 = wv()
            ok(w5["stores"]!!.split(' ').none { it.startsWith("7:") || it.startsWith("3:") }, "✕ on 7 empties it, and 3 with it (Both sides): ${w5["stores"]} (${shot("clear")})")
            // the other way round: the store first, then a lit station
            d.clickKey("planner/Loadout/Sel/Done")
            ok(wv()["station"] == "", "Done lets the station go")
            d.clickKey("planner/Loadout/Store/$bomb")
            ok(wv()["armed"] == bomb, "a tap on $bomb with no station picked picks the store")
            d.clickKey("planner/Loadout/Station/3")
            val w5b = wv()
            ok(w5b["stores"]!!.split(' ').any { it.startsWith("3:") && it.endsWith("x$bomb") } && w5b["stores"]!!.split(' ').any { it.startsWith("7:") && it.endsWith("x$bomb") },
                "then a tap on station 3 hangs a full load there and, Mirror, on 7: ${w5b["stores"]} (${shot("armed-station")})")
            d.clickKey("planner/Loadout/Sel/Drop")
            d.clickKey("planner/Loadout/Station/3")
            d.clickKey("planner/Loadout/Sel/Clear")
            val w5c = wv()
            ok(w5c["station"] == "3" && w5c["stores"]!!.split(' ').none { it.startsWith("7:") || it.startsWith("3:") }, "Clear station empties 3, and 7 with it: ${w5c["stores"]}")
            // the search box
            edt { (top()?.wiring as? com.bmscompanion.app.ui.screens.wdp.PerfLoadoutWindow)?.search(bomb.take(5)) }
            val found = d.keys("planner/Loadout/Store/").map { it.substringAfterLast('/') }
            ok(found.isNotEmpty() && bomb in found && found.size < fits3.size, "the search box narrows the list to names with \"${bomb.take(5)}\": $found")
            edt { (top()?.wiring as? com.bmscompanion.app.ui.screens.wdp.PerfLoadoutWindow)?.search("") }
            // let the station go, then add air-to-air missiles until there is no room left
            d.clickKey("planner/Loadout/Station/3")
            ok(wv()["station"] == "", "a second tap lets station 3 go")
            d.clickKey("planner/Loadout/Filter/AA")
            val aa = d.keys("planner/Loadout/Store/").map { it.substringAfterLast('/') }
            info("A-A tiles: ${aa.size}")
            ok(aa.isNotEmpty() && wv()["filter"] == "Air-to-air", "Air-to-air narrows the tiles (${aa.size}) (${shot("filter-aa")})")
            val ir = aa.firstOrNull { it.contains("9x", true) } ?: aa.firstOrNull { it.contains("aim-9", true) } ?: aa.first()
            var refused: String? = null
            // a tap on the row picks the store (the stations that take it light up); its + adds one where it fits
            d.clickKey("planner/Loadout/Store/$ir")
            ok(wv()["armed"] == ir, "a tap on $ir with no station picked picks it (${shot("armed")})")
            d.clickKey("planner/Loadout/Store/$ir")
            ok(wv()["armed"] == "", "a second tap lets it go")
            for (i in 0 until 16) {
                d.clickKey("planner/Loadout/Add/$ir")
                val s = wv()["status"].orEmpty()
                if (s.startsWith("No hardpoint left")) { refused = s; break }
            }
            val stack = edt { WdpDialogs.stack.map { (it as? WdpDialog)?.form ?: "message" } }
            ok(refused != null && stack == listOf("fclsLoadout"), "adding $ir until nothing is left: \"$refused\" on the window's own line, the window still open (stack $stack) (${shot("no-room")})")
            info("stores now: ${wv()["stores"]}")
            // one side only
            d.clickKey("planner/Loadout/Both")
            ok(wv()["both"] == "false", "Both sides can be turned off")
            d.clickKey("planner/Loadout/Filter/ALL")
            d.clickKey("planner/Loadout/Station/5")
            val c5 = d.keys("planner/Loadout/Store/").map { it.substringAfterLast('/') }
            val tank = c5.firstOrNull { it.contains("tank", true) || it.contains("300", true) }
            if (tank != null) {
                d.clickKey("planner/Loadout/Store/$tank")
                val w6 = wv()
                ok(w6["stores"]!!.split(' ').any { it.startsWith("5:") && it.endsWith("x$tank") } && w6["lblFuelExtW"] != "0", "the centreline tank on 5: ext fuel ${w6["lblFuelExtW"]} (${shot("tank5")})")
            }
            // a store's facts from the Arsenal
            d.settle(4, 40)
            info("the facts line is drawn for the store last tapped (${shot("facts")})")
            // another aircraft of the flight
            if (d.rect("fclsLoadout/rbnWingman") != null && wv()["rbnWingman"] != "") {
                d.clickKey("fclsLoadout/rbnWingman")
                val w7 = wv()
                info("wingman: ${w7["rbnWingman"]} — ${w7["stores"]}")
                ok(w7["rbnWingman"] == "checked" || w7["rbnWingman"] == "unchecked", "the Wingman radio answers (${w7["rbnWingman"]}) (${shot("wingman")})")
                d.clickKey("fclsLoadout/rbnLead")
            }
            val before = wv()
            d.clickKey("planner/Loadout/Apply")
            // with a mission loaded WDP asks first (fclsLoadout.CloseForm); No keeps the window open, Yes hands the stores over
            val q = edt { WdpDialogs.stack.lastOrNull() as? WdpMessage }
            if (briefing != null) {
                ok(q != null && q.text.startsWith("Loadout will not be changed for your mission") && q.buttons == listOf("Yes", "No", "Cancel"),
                    "OK with a mission asks WDP's question: \"${q?.text?.replace('\n', ' ')}\" ${q?.buttons} (${shot("question")})")
                answer("No")
                ok(top()?.form == "fclsLoadout" && v(p)["lblLoadout_val"] == page0["lblLoadout_val"], "No keeps the window open and the page as it was")
                d.clickKey("planner/Loadout/Apply")
            }
            yes()
            val page1 = v(p)
            ok(top() == null, "OK (Yes to WDP's question when a mission is loaded) closes the window")
            ok(page1["lblLoadout_val"] == before["lblLoadW"] && page1["lblDrag_Val"] == before["lblDragVal"],
                "OK hands the load and drag to the page (${page1["lblLoadout_val"]} / ${page1["lblDrag_Val"]}; gross ${page1["lblGross_Val"]}, rotate ${page1["lblRotate_Val"]})")
            // Cancel puts the loadout back
            edt { p.onClick("btnLoadout") }
            d.settle(4)
            val again = wv()
            ok(again["stores"] == before["stores"], "Set opens again on what OK kept")
            d.clickKey("planner/Loadout/Station/1")
            val tip = d.keys("planner/Loadout/Store/").firstOrNull()?.substringAfterLast('/')
            if (tip != null) d.clickKey("planner/Loadout/Store/$tip")
            d.clickKey("planner/Loadout/Cancel")
            edt { p.onClick("btnLoadout") }
            d.settle(4)
            ok(wv()["stores"] == before["stores"], "Cancel puts the stores back as they were (${shot("after-cancel")})")
            // Clear All
            d.clickKey("fclsLoadout/btnClear")
            ok(wv()["stores"] == "" && wv()["lblLoadW"] == "0", "Clear All empties the jet (load ${wv()["lblLoadW"]}) (${shot("clear-all")})")
            d.clickKey("planner/Loadout/Cancel")
        } finally {
            edt { WdpDialogs.stack.clear() }
            d.close()
        }
    }

    // ------------------------------------------------------------------------------------------------ devices

    /**
     * The Loadout window on a PC, a tablet both ways and a phone: by mouse the store picker is in the window; by finger
     * (or where the window is drawn too small) the window has one **Choose stores** button and the picker opens over it
     * at the device's own sizes, its rows a finger's height, with the stations as a strip of buttons.
     */
    private fun devices(out: File, briefing: File?, cartridge: File?, theater: com.bmscompanion.app.data.Theater?) {
        text.appendLine("\n== The Loadout window on a PC, a tablet and a phone")
        val sizes = listOf(
            listOf("pc-1600x1000", 1600, 1000, 1f, false), listOf("tablet-1138x711", 1138, 711, 2f, true),
            listOf("tablet-711x1138", 711, 1138, 2f, true), listOf("phone-412x915", 412, 915, 2.625f, true),
        )
        for (s in sizes) {
            val label = s[0] as String; val w = s[1] as Int; val h = s[2] as Int; val dens = s[3] as Float; val finger = s[4] as Boolean
            val p = page(briefing, cartridge, theater)
            edt { com.bmscompanion.app.ui.screens.wdp.WdpTouch.seen = finger; WdpProbe.clear() }
            val d = Driver(w, h, dens)
            try {
                edt { p.onClick("btnLoadout") }
                d.settle(6)
                d.png(File(out, "dev-$label-1-window.png"))
                val choose = d.rect("planner/Loadout/Choose")
                if (!finger) {
                    ok(choose == null && d.keys("planner/Loadout/Store/").size >= 10, "$label: the picker is in the window (dev-$label-1-window.png)")
                    continue
                }
                ok(choose != null, "$label by finger: the window offers Choose stores (dev-$label-1-window.png)")
                if (choose == null) continue
                d.clickKey("planner/Loadout/Choose")
                d.settle(4)
                val open = edt { WdpDialogs.stack.lastOrNull() is com.bmscompanion.app.ui.screens.wdp.WdpPanelWindow }
                d.png(File(out, "dev-$label-2-picker.png"))
                val rows = d.keys("planner/Loadout/Store/")
                val rowH = rows.firstOrNull()?.let { d.rect(it) }?.height?.div(dens) ?: 0f
                val strip = d.keys("planner/Loadout/Strip/")
                ok(open && rows.size >= 10 && rowH >= 36f && strip.size >= 9,
                    "$label: Choose stores opens the picker over the window: ${rows.size} stores, rows ${"%.0f".format(rowH)} dp, ${strip.size} station buttons (dev-$label-2-picker.png)")
                // a store found with the search box (on a phone the list scrolls), then a lit station on the strip
                val gbu = rows.map { it.substringAfterLast('/') }.firstOrNull { it.contains("gbu-12", true) } ?: rows.first().substringAfterLast('/')
                edt { ((WdpDialogs.stack.firstOrNull { it is WdpDialog } as? WdpDialog)?.wiring as? com.bmscompanion.app.ui.screens.wdp.PerfLoadoutWindow)?.search("gbu12") }
                d.settle(3)
                d.clickKey("planner/Loadout/Store/$gbu")
                d.png(File(out, "dev-$label-3-armed.png"))
                val lit = edt { (WdpDialogs.stack.firstOrNull { it is WdpDialog } as? WdpDialog)?.wiring?.values(emptyList())?.get("armed") }
                d.clickKey("planner/Loadout/Strip/3")
                val stores = edt { (WdpDialogs.stack.firstOrNull { it is WdpDialog } as? WdpDialog)?.wiring?.values(emptyList())?.get("stores") }.orEmpty()
                d.png(File(out, "dev-$label-4-hung.png"))
                ok(lit == gbu && stores.split(' ').any { it.startsWith("3:") && it.endsWith("x$gbu") },
                    "$label: $gbu picked, then station 3 on the strip hangs it: $stores (dev-$label-4-hung.png)")
                d.clickKey("planner/Loadout/Apply")
                val q = edt { WdpDialogs.stack.lastOrNull() as? WdpMessage }
                ok(briefing == null || q?.text?.startsWith("Loadout will not be changed") == true, "$label: Apply asks WDP's question over the window")
                d.png(File(out, "dev-$label-5-question.png"))
            } finally {
                edt { WdpDialogs.stack.clear(); com.bmscompanion.app.ui.screens.wdp.WdpTouch.seen = false }
                d.close()
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ Charts

    private fun charts(out: File, briefing: File?, cartridge: File?, theater: com.bmscompanion.app.data.Theater?) {
        text.appendLine("\n== Charts window")
        val p = page(briefing, cartridge, theater)
        val v0 = v(p)
        ok(v0["btnChart.enabled"] == "true", "Charts is enabled for ${v0["lblAPT_VAL"]}")
        val d = Driver()
        try {
            edt { p.onClick("btnChart") }
            var foot = ""
            for (i in 0 until 40) {
                d.settle(2, 50)
                foot = edt { top()?.wiring?.values(emptyList())?.get("lblFile") }.orEmpty()
                if (foot.isNotEmpty()) break
            }
            d.settle(6, 50)
            val f1 = File(out, "charts-1.png"); d.png(f1)
            ok(top()?.form == "fclsChart" && foot.contains("diagram", true), "Charts opens on the airport diagram: \"$foot\" (${f1.name})")
            val all = foot.substringAfter('/').substringBefore(' ').toIntOrNull() ?: 0
            info("charts in the window: $all")
            // step to the last one: the instrument charts come after the diagram and the parking charts
            for (i in 1 until all) { d.clickKey("fclsChart/lblFile"); d.settle(2, 30) }
            val lastFoot = edt { top()?.wiring?.values(emptyList())?.get("lblFile") }.orEmpty()
            d.settle(10, 60)
            val f2 = File(out, "charts-last.png"); d.png(f2)
            info("last chart: \"$lastFoot\" (${f2.name})")
            ok(all >= 2, "the window steps through $all charts (diagram, parking, instrument)")
            d.clickKey("fclsChart/btnClose")
            ok(top() == null, "Close closes it")
        } finally {
            edt { WdpDialogs.stack.clear() }
            d.close()
        }
    }

    // ------------------------------------------------------------------------------------------------ the scene

    /** The Planner's dialog host alone, at the scale of a page drawn at one pixel per designer pixel. */
    private class Driver(wDp: Int = 1320, hDp: Int = 900, val density: Float = 1f) {
        private val scene = edt {
            ImageComposeScene((wDp * density).toInt(), (hDp * density).toInt(), Density(density)) {
                Box(Modifier.fillMaxSize().background(Color(0xFF2A2F36))) { WdpDialogHost(pageScale = density) }
            }
        }
        private var nanos = 0L
        private var ms = 1_000L

        fun frame() = edt {
            Snapshot.sendApplyNotifications()
            nanos += 16_666_667L
            scene.render(nanos)
        }

        fun settle(n: Int = 3, sleepMs: Long = 0) = repeat(n) { frame().close(); if (sleepMs > 0) Thread.sleep(sleepMs) }

        fun rect(key: String): Rect? { settle(1); return edt { WdpProbe.rects[key] } }

        fun keys(prefix: String): List<String> { settle(2); return edt { WdpProbe.rects.keys.filter { it.startsWith(prefix) }.sorted() } }

        fun png(f: File) {
            settle(2)
            val img = frame()
            runCatching { f.parentFile?.mkdirs(); f.writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes) }
            img.close()
        }

        fun click(at: Offset) {
            ms += 50
            edt {
                scene.sendPointerEvent(PointerEventType.Move, at, timeMillis = ms)
                scene.sendPointerEvent(PointerEventType.Press, at, timeMillis = ms, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
            }
            settle(1)
            ms += 60
            edt { scene.sendPointerEvent(PointerEventType.Release, at, timeMillis = ms, buttons = PointerButtons(), button = PointerButton.Primary) }
            settle(3)
            ms += 400 // never a double click
        }

        fun clickKey(key: String) {
            val at = rect(key) ?: error("'$key' is not on screen")
            click(at.center)
        }

        fun close() = runCatching { edt { scene.close() } }
    }

    private fun <T> edt(block: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return block()
        var r: Result<T>? = null
        SwingUtilities.invokeAndWait { r = runCatching(block) }
        return r!!.getOrThrow()
    }
}
