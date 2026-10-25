package com.bmscompanion.desktop.bridge

import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.mission.BriefOverview
import com.bmscompanion.app.data.mission.Briefing
import com.bmscompanion.app.data.mission.MissionPrefs
import com.bmscompanion.app.data.mission.MissionSyncClient
import com.bmscompanion.app.data.mission.PcAnswer
import com.bmscompanion.app.data.mission.SyncAnswer
import com.bmscompanion.app.data.mission.SyncItem
import com.bmscompanion.app.data.mission.SyncPost
import com.bmscompanion.app.ui.screens.wdp.DataCardWiring
import com.bmscompanion.app.ui.screens.wdp.DtcWiring
import com.bmscompanion.app.ui.screens.wdp.HadbWiring
import com.bmscompanion.app.ui.screens.wdp.PerformanceWiring
import com.bmscompanion.app.ui.screens.wdp.PlannerPages
import com.bmscompanion.app.ui.screens.wdp.PlannerSessionSource
import com.bmscompanion.app.ui.screens.wdp.PopupWiring
import com.bmscompanion.app.ui.screens.wdp.TossWiring
import com.bmscompanion.app.ui.screens.wdp.WdpMission
import com.bmscompanion.app.ui.screens.wdp.WdpPage
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * `--syncstatetest out.txt` (1.4.2): the Mission section carries on from device to device through the PC
 * (`/api/sync`, [MissionSyncStore], `MissionSyncClient`, `PlannerSessionSource`). Three devices in this process — "PC
 * window", "tablet", "phone" — each with preferences, a DataCard and a TOSS page of its own, talk to the bridge
 * in-process (Bridge.handle, as the PC window does); the store is kept in a scratch folder. The preferences are the
 * devices' own maps here, read and taken by the rules of the app's `PrefSource` ([MissionPrefs]). Writes the attack
 * pages' Setup.ini section in the APPDATA it runs with (put back at the end): run it with a scratch APPDATA.
 */
object SyncStateTest {
    private val BRIEF_A = Briefing(overview = BriefOverview(flight = "Cobra1", mission = "Strike", packageId = "1234"))
    private val BRIEF_B = Briefing(overview = BriefOverview(flight = "Viper3", mission = "SEAD", packageId = "5678"))
    private val FLIGHT_A = PlannerSessionSource.keyOf("Cobra1", 1234, 1, "Korea KTO", null, 0, 1000L)!!
    private val FLIGHT_B = PlannerSessionSource.keyOf("Viper3", 5678, 2, "Korea KTO", null, 0, 1000L)!!

    private class Pages(brief: Briefing) : PlannerPages {
        override val card = DataCardWiring().also { it.onMission(WdpMission(briefing = brief)) }
        override val perf: PerformanceWiring? = null
        override val popup: PopupWiring? = null
        override val hadb: HadbWiring? = null
        override val toss = TossWiring().also { it.onMission(WdpMission(briefing = brief)) }
        override val dtc: DtcWiring? = null
        override var focus: WdpPage? = null
        override fun takeFocus(page: WdpPage?) { focus = page }
    }

    private class Dev(val label: String, var flight: String, brief: Briefing) {
        /** the device's preferences (its prefs file, its localStorage) */
        val prefs = HashMap<String, String?>()
        var meta: String? = null
        var typing: String? = null
        var pages = Pages(brief)
        private val prefSource = object : MissionSyncClient.Source {
            override fun owns(key: String) = !MissionPrefs.excluded(key)
            override fun read(key: String) = MissionSyncClient.Local("", prefs[key])
            override fun adopt(item: SyncItem, active: Boolean): MissionSyncClient.Adopt {
                if (item.key in MissionPrefs.NAV_KEYS && !active) return MissionSyncClient.Adopt.LATER
                prefs[item.key] = item.value
                return MissionSyncClient.Adopt.TAKEN
            }
            override fun localKeys() = prefs.keys.filter { owns(it) }
        }
        private val lazyPages = object : PlannerPages {
            override val card get() = pages.card
            override val perf get() = pages.perf
            override val popup get() = pages.popup
            override val hadb get() = pages.hadb
            override val toss get() = pages.toss
            override val dtc get() = pages.dtc
            override val focus get() = pages.focus
            override fun takeFocus(page: WdpPage?) = pages.takeFocus(page)
        }
        val session = PlannerSessionSource(lazyPages, { flight }, { typing })
        var client = newClient()

        fun newClient() = MissionSyncClient({ meta }, { meta = it }, { s, p -> get(s, p) }, { post(it) }, label).also {
            it.register(prefSource); it.register(session)
        }

        fun hookPages() {
            session.hook(pages.card) { client.changed(it) }
            session.hook(pages.toss) { client.changed(it) }
        }

        init { hookPages() }

        fun set(key: String, v: String?) { prefs[key] = v; client.changed(key) }

        /** A restart: the prefs and the sync's bookkeeping kept, the Planner's pages new (empty), a new client. */
        fun restart(brief: Briefing) { pages = Pages(brief); client = newClient(); hookPages() }

        fun card(box: String): String? = pages.card.typedEntries()[box]?.substringAfter('\u001F')
    }

    private fun call(method: String, query: Map<String, String>, body: String?): PcAnswer<SyncAnswer> {
        val r = Bridge.handle(ApiRequest(method, "/api/sync", query, body?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)))
        val a = runCatching { Bridge.json.decodeFromString(SyncAnswer.serializer(), r.body.decodeToString()) }.getOrNull()
        return PcAnswer(a, if (r.status == 200) null else a?.error ?: "status ${r.status}", r.status)
    }

    private fun get(since: Long, prefix: String?) = call("GET", buildMap { put("since", since.toString()); if (prefix != null) put("prefix", prefix) }, null)
    private fun post(p: SyncPost) = call("POST", emptyMap(), Bridge.json.encodeToString(SyncPost.serializer(), p))

    fun run(): String {
        val out = StringBuilder()
        var fails = 0
        fun ok(c: Boolean, what: String) { if (!c) fails++; out.appendLine((if (c) "PASS " else "FAIL ") + what) }
        val dir = File(System.getProperty("java.io.tmpdir"), "bmsc-syncstate-" + System.nanoTime()).apply { mkdirs() }
        Bridge.startForCheck()
        MissionSyncStore.folderOverride = dir
        MissionSyncStore.reset()
        // each device's TOSS page starts from no saved section (they share this APPDATA's preferences in one process)
        val tossWas = runCatching { Repo.getString(TOSS_INI) }.getOrNull()
        fun freshToss() = runCatching { Repo.putString(TOSS_INI, null) }
        try {
            runBlocking {
                out.appendLine("1. What travels")
                for (k in listOf("map_style", "m_route", "own_jet_size", "fuel_plan", "taxi_chart_night", "files_open", "tgt_removed", "wdpmap_style", "open_kb_view", "mwx_fill", "mission_tab"))
                    ok(!MissionPrefs.excluded(k), "$k syncs")
                for (k in listOf("dash_page", "dash_wide_p1", "dash_narrow_p3", "dash_kb_page", "mfd_arrange", "perf_easy", "mfd_live", "mfd_fps", "kneeboard_text", "kneeboard_shape", "bridge_host", "pc_win_w", "theater", "mission_epoch", "planner_sync", "kb_print_plan"))
                    ok(MissionPrefs.excluded(k), "$k stays the device's own")
                ok("mission_tab" in MissionPrefs.NAV_KEYS, "the Mission tab is navigation (taken on activation only)")

                freshToss(); val pc = Dev("PC", FLIGHT_A, BRIEF_A)
                freshToss(); val tablet = Dev("Android", FLIGHT_A, BRIEF_A)
                freshToss(); val phone = Dev("Browser", FLIGHT_B, BRIEF_B)
                for (d in listOf(pc, tablet, phone)) ok(d.client.poll(), "${d.label} polls the store (in-process)")

                out.appendLine("2. A preference changed on one device is taken by another")
                pc.set("map_style", "satellite")
                pc.set("dash_wide_p1", "MAP,DED")
                pc.set("perf_easy", "1")
                ok(pc.client.push(), "the PC posts its change")
                tablet.client.poll()
                ok(tablet.prefs["map_style"] == "satellite", "the tablet takes the PC's map style: ${tablet.prefs["map_style"]}")
                ok(tablet.prefs["dash_wide_p1"] == null && tablet.prefs["perf_easy"] == null, "the Dashboard's layout and the PC's rendering choice stay the PC's own")
                ok(get(0, null).value?.items?.none { it.key == "dash_wide_p1" || it.key == "perf_easy" } == true, "…and never reach the PC's store")
                tablet.set("fuel_plan", """{"cruise":15}"""); tablet.client.push()
                pc.client.poll()
                ok(pc.prefs["fuel_plan"] == """{"cruise":15}""", "a fuel setting changed on the tablet reaches the PC window")

                out.appendLine("3. The last change wins, by the PC's clock")
                pc.set("own_jet_size", "2")
                Thread.sleep(60)
                tablet.set("own_jet_size", "0")
                pc.client.push(); tablet.client.push()
                pc.client.poll()
                ok(pc.prefs["own_jet_size"] == "0" && tablet.prefs["own_jet_size"] == "0", "the later change (the tablet's) is on both: PC ${pc.prefs["own_jet_size"]}, tablet ${tablet.prefs["own_jet_size"]}")
                pc.client.changed("own_jet_size")
                ok(!pc.client.isPending("own_jet_size"), "a value written again as it was is no change (a page saving all its settings)")

                out.appendLine("4. A DataCard entry and an attack input move with the flight")
                pc.pages.card.onValue("txtJoker", "7100")
                pc.pages.card.onValue("txtBrfNotes1", "Hold at the IP until push")
                pc.pages.toss.onValue("trbG", "5")
                ok(pc.client.hasPending(), "the pages told of the pilot's changes")
                pc.client.push()
                tablet.client.poll()
                ok(tablet.card("txtJoker") == "7100", "the tablet's DataCard has the PC's JOKER: ${tablet.card("txtJoker")}")
                ok(tablet.card("txtBrfNotes1") == "Hold at the IP until push", "…and the Briefing page's line: ${tablet.card("txtBrfNotes1")}")
                ok(tablet.pages.toss.plan.pullingGs == 5, "the tablet's TOSS page pulls the PC's 5 G: ${tablet.pages.toss.plan.pullingGs}")
                phone.client.poll()
                ok(phone.card("txtJoker") == null && phone.pages.toss.plan.pullingGs != 5, "the phone planning another flight takes neither (JOKER ${phone.card("txtJoker")}, ${phone.pages.toss.plan.pullingGs} G)")
                phone.flight = FLIGHT_A
                phone.pages = Pages(BRIEF_A); phone.hookPages()
                phone.client.rescan("@")
                ok(phone.card("txtJoker") == "7100" && phone.pages.toss.plan.pullingGs == 5, "…until it opens that flight: JOKER ${phone.card("txtJoker")}, ${phone.pages.toss.plan.pullingGs} G")

                out.appendLine("5. Where the pilot is: taken only when the device becomes active")
                pc.set("mission_tab", "DASH"); pc.client.push()
                tablet.client.activate()
                Thread.sleep(20)
                tablet.set("mission_tab", "MAP"); tablet.client.push()
                pc.client.poll()
                pc.client.poll()
                ok(pc.prefs["mission_tab"] == "DASH", "the PC window in use keeps its own tab while the tablet moves: ${pc.prefs["mission_tab"]}")
                pc.client.activate()
                ok(pc.prefs["mission_tab"] == "MAP", "brought to the front, it takes the tablet's: ${pc.prefs["mission_tab"]}")

                out.appendLine("6. A box being typed in is not overwritten")
                tablet.typing = "cntDataCard/txtJoker"
                pc.pages.card.onValue("txtJoker", "7300"); pc.client.push()
                tablet.client.poll()
                ok(tablet.card("txtJoker") == "7100", "the tablet typing in JOKER keeps what it shows: ${tablet.card("txtJoker")}")
                tablet.typing = null
                tablet.client.retryWaiting()
                ok(tablet.card("txtJoker") == "7300", "once the box is left it takes the PC's: ${tablet.card("txtJoker")}")

                out.appendLine("7. A restart takes the PC's")
                freshToss()
                tablet.restart(BRIEF_A)
                ok(tablet.card("txtJoker") == null, "after a restart the tablet's card is empty")
                pc.set("map_style", "topo"); pc.client.push()
                tablet.set("m_route", "0")
                tablet.client.poll()
                ok(tablet.card("txtJoker") == "7300" && tablet.pages.toss.plan.pullingGs == 5, "…and takes the entries back from the PC (JOKER ${tablet.card("txtJoker")}, ${tablet.pages.toss.plan.pullingGs} G)")
                ok(tablet.prefs["map_style"] == "topo", "…and the map style changed on the PC meanwhile: ${tablet.prefs["map_style"]}")
                pc.client.poll()
                ok(pc.prefs["m_route"] == "0", "a change the tablet made before linking again is posted, not lost: ${pc.prefs["m_route"]}")
                val fresh = Dev("Android", FLIGHT_A, BRIEF_A).also { it.prefs["map_style"] = "chart" }
                fresh.client.poll()
                ok(fresh.prefs["map_style"] == "topo", "a new device takes the PC's over its own: ${fresh.prefs["map_style"]}")

                out.appendLine("8. Kept on the PC")
                MissionSyncStore.flush()
                MissionSyncStore.reset()
                val back = get(0, null).value?.items.orEmpty()
                ok(File(dir, MissionSyncStore.FILE_NAME).isFile && back.any { it.key == "map_style" && it.value == "topo" } && back.any { it.key == "@card/$FLIGHT_A/txtJoker" },
                    "kept in ${MissionSyncStore.FILE_NAME} and read back (${back.size} items)")
                val bad = Bridge.handle(ApiRequest("POST", "/api/sync", body = "not json".toByteArray()))
                ok(bad.status == 400, "a body that is not a change is refused (${bad.status})")
                ok(get(0, "@card/$FLIGHT_B/").value?.items?.isEmpty() == true, "the prefix narrows a GET (nothing typed for the other flight)")
            }
        } catch (e: Throwable) {
            ok(false, "threw: $e")
        } finally {
            runCatching { Repo.putString(TOSS_INI, tossWas) }
            MissionSyncStore.folderOverride = null
            MissionSyncStore.reset()
            dir.deleteRecursively()
        }
        return "mission sync: ${if (fails == 0) "all passed" else "$fails FAIL"}\n$out"
    }

    private const val TOSS_INI = "wdp_toss_ini"
}
