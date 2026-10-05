package com.bmscompanion.desktop.bridge

import com.bmscompanion.app.data.mission.BridgeInfo
import com.bmscompanion.app.data.mission.CampAto
import com.bmscompanion.app.data.mission.CampRef
import com.bmscompanion.app.data.mission.CartridgeEdit
import com.bmscompanion.app.data.mission.CartridgeState
import com.bmscompanion.app.data.mission.KbKind
import com.bmscompanion.app.data.mission.KbOwner
import com.bmscompanion.app.data.mission.LedgerMission
import com.bmscompanion.app.data.mission.Leftovers
import com.bmscompanion.app.data.mission.MissionData
import com.bmscompanion.app.data.mission.MissionMode
import com.bmscompanion.app.data.mission.MissionSourceInfo
import com.bmscompanion.app.data.mission.PcAnswer
import com.bmscompanion.app.data.mission.PopulateSend
import com.bmscompanion.app.data.wdp.DtcEdits
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.awt.image.BufferedImage
import java.io.File

/**
 * Part 9 of `--missiontest`: what a switch of mode resets by itself ([ModeSwitchReset]; docs/DATA-STORES.md, "What a
 * switch resets"). Against the copy, through the PC's own routes:
 *
 * 1. In WDP mode: another flight of the save (A') populated; Save to DTC for a made-up flight A (a line, two PPTs, a
 *    VIP, STPT 85); a PPT saved for the printed flight B; a PPT BMS wrote; Upd Kneeboard's pages (page 3 both halves,
 *    page 2's left half beside a right half EZBoards' tag claims).
 * 2. BMS prints flight B (briefing.txt's time moved after all that).
 * 3. **Switch to EZBoards mode**: every key the Planner wrote cleared to BMS's empty values — A's and B's (the current
 *    flight's too: a switch clears every leftover) — and BMS's left alone; page 3 is BMS's shipped file byte for byte,
 *    page 2's left half BMS's again with the right half's owner kept; the snapshot of A' kept; the summary on the
 *    answer and on `/api/info` (served, though no device draws it).
 * 4. **Undo** (the route, kept for compatibility): the keys written back exactly; a second Undo puts back nothing more.
 * 5. **Switch back to WDP mode**: the snapshot of A' (not the current flight's) discarded — "not populated yet".
 * 6. **A same-flight switch** (with the Planner's flight = B, after B's own key and page): B's key cleared all the
 *    same; the page made for B kept (pages follow the earlier-flight rule as before).
 * 7. **A new mission in WDP mode** (`POST /api/mission/opened` with another flight C): A's keys cleared by themselves,
 *    C's own key and BMS's left alone, no page touched; C opened again changes nothing more.
 * 8. **Delivery data no ledger knows** (`[NAV OFFSETS]` written raw: Modesel, VRP, VRPPUP, two aim points): a Populate
 *    of another flight clears all of it before the cartridge is read, so the snapshot, the merged Mission map and
 *    `/api/attack` have no attack; written again, a switch of mode clears it too.
 *
 * The copy's cartridge, briefing time, ledger and pages are put back by the caller ([MissionSourceTest]).
 */
internal object SwitchResetTest {
    private val client = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true; explicitNulls = false }

    fun run(root: File, cart: File?, ok: (Boolean, String) -> Unit, line: (String) -> Unit) {
        line("")
        line("== 9. A switch of mode resets what an earlier flight left")
        if (cart == null) { ok(false, "the copy has a cartridge to work on"); return }
        val cs = cart.nameWithoutExtension
        val planner = File(File(root, "User"), PcFileRoutes.PLANNER_FOLDER)
        val plannerWas = planner.isDirectory
        val ledgerFile = File(File(planner, "Ledger"), "$cs.json")
        val ledgerWas = ledgerFile.takeIf { it.isFile }?.readBytes()
        val brief = Bridge.briefingPath?.let(::File)?.takeIf { it.isFile }
        val briefTime = brief?.lastModified() ?: 0L
        fun req(method: String, path: String, query: Map<String, String> = emptyMap(), body: String = ""): Pair<Int, String> {
            val r = Bridge.handle(ApiRequest(method, path, query, body.toByteArray(Charsets.UTF_8)))
            return r.status to r.body.toString(Charsets.UTF_8)
        }
        fun state(r: Pair<Int, String>) = client.decodeFromString(CartridgeState.serializer(), r.second)
        fun mission() = client.decodeFromString(MissionData.serializer(), req("GET", "/api/mission").second)
        fun info() = client.decodeFromString(BridgeInfo.serializer(), req("GET", "/api/info").second)
        fun source(r: Pair<Int, String>) = PcAnswer.read(r.first, r.second) { client.decodeFromString(MissionSourceInfo.serializer(), it) }
        fun enc(m: LedgerMission) = client.encodeToString(LedgerMission.serializer(), m)
        fun edits(list: List<CartridgeEdit>) = client.encodeToString(ListSerializer(CartridgeEdit.serializer()), list)
        fun text() = String(cart.readBytes(), Charsets.ISO_8859_1)
        fun switch(mode: String, forM: LedgerMission? = null) =
            source(req("POST", "/api/mission/source", listOfNotNull("mode" to mode, forM?.let { "for" to enc(it) }).toMap()))
        try {
            ledgerFile.delete()
            ModeSwitchReset.forget()
            val place = KneeboardPrint.place(root, Bridge.install.theater)
            val pages = place.error == null && place.shipped != null && place.shippedPage(2) != null && place.shippedPage(3) != null &&
                place.page(2)?.isFile == true && place.page(3)?.isFile == true
            line("   kneeboard pages: ${place.folderLabel}, BMS's originals ${if (place.shipped != null) KneeboardPrint.relative(root, place.shipped) else "none"}")

            // ---- 1. in WDP mode: A' populated, A planned and saved, Upd Kneeboard's pages
            val toWdp = switch("wdp")
            ok(toWdp.ok && toWdp.value?.mode == MissionMode.WDP && toWdp.value?.reset != null, "into WDP mode to plan: \"${toWdp.value?.reset?.done?.firstOrNull()}\"")
            val m0 = client.decodeFromString(MissionData.serializer(), req("GET", "/api/mission", mapOf("source" to "bms")).second)
            val b = LedgerMission.ofBriefing(m0.briefing, m0.briefingModified, Bridge.install.theater)
            ok(b != null, "the printed flight B: ${b?.label}")
            if (b == null || brief == null) return
            val ato = runCatching {
                client.decodeFromString(CampAto.serializer(), req("GET", "/api/campaign/ato", mapOf("theater" to "Korea KTO", "file" to "Auto Save.cam")).second)
            }.getOrNull()
            val other = ato?.packages?.flatMap { it.flights }?.firstOrNull { !it.briefed && !it.callsign.equals(b.callsign, true) }
            val pop = other?.let {
                source(req("POST", "/api/mission/populate", body = Bridge.json.encodeToString(PopulateSend.serializer(), PopulateSend(CampRef("Korea KTO", "Auto Save.cam", it.id), seat = 0))))
            }
            ok(pop?.ok == true && pop.value?.populated?.callsign == other.callsign, "flight A' (${other?.callsign}) populated: WDP mode's snapshot")

            val a = LedgerMission(callsign = "Leftover9", packageId = 9999, theater = Bridge.install.theater, save = "Test.cam", opened = 1)
            val planA = (0..2).map { i -> CartridgeEdit("STPT", "lineSTPT_$i", "${1_000_000 + i * 20_000}.000000, ${900_000 + i * 15_000}.000000, 0.000000") } +
                listOf(
                    CartridgeEdit("STPT", "ppt_0", "1100000.000000, 950000.000000, 0.000000, 98425.000000, 3"),
                    CartridgeEdit("STPT", "ppt_1", "1150000.000000, 980000.000000, 0.000000, 60000.000000, 6"),
                    CartridgeEdit("NAV OFFSETS", "VIP", "3,123.4,25000,0"),
                    CartridgeEdit("STPT", "target_84", "1120000.000000, 960000.000000, 0.000000, 0, Leftover target"),
                )
            val savedA = state(req("POST", "/api/cartridge/save", mapOf("for" to enc(a)), edits(planA)))
            val forB = CartridgeEdit("STPT", "ppt_3", "1210000.000000, 995000.000000, 0.000000, 40000.000000, 2")
            state(req("POST", "/api/cartridge/save", mapOf("for" to enc(b)), edits(listOf(forB))))
            cart.writeBytes(DtcEdits.apply(text(), listOf(CartridgeEdit("STPT", "ppt_2", "1200000.000000, 990000.000000, 0.000000, 50000.000000, 2"))).toByteArray(Charsets.ISO_8859_1))
            ok(savedA.error == null, "Save to DTC for A (${savedA.message ?: savedA.error}), a PPT for B, a PPT BMS wrote")
            val gray = BufferedImage(KneeboardPrint.PAGE_W, KneeboardPrint.PAGE_H, BufferedImage.TYPE_INT_RGB).apply { createGraphics().apply { color = java.awt.Color(90, 90, 90); fillRect(0, 0, width, height); dispose() } }
            if (pages) {
                KneeboardPrint.putShipped(place, listOf(2, 3))
                KneeboardPrint.printPage(place, 3, arrayOf(gray, gray), arrayOf(KbKind.COORDINATION_LEFT, KbKind.COORDINATION_RIGHT))
                KneeboardPrint.printPage(place, 2, arrayOf(gray, null), arrayOf(KbKind.DATACARD_LEFT, null))
                // page 2's right half: EZBoards' (the tag says so), which a switch to EZBoards mode must leave
                val f2 = place.page(2)!!
                val by = f2.readBytes()
                f2.writeBytes(Dds.print(by, Dds.header(by), arrayOf(null, null), KneeboardPrint.tag(KneeboardPrint.Owners(KbOwner.COMPANION, KbOwner.EZBOARDS, KbKind.DATACARD_LEFT, null))))
                val o2 = KneeboardPrint.ownersOf(place, 2)
                val o3 = KneeboardPrint.ownersOf(place, 3)
                ok(o2?.left == KbOwner.COMPANION && o2.right == KbOwner.EZBOARDS && o3?.left == KbOwner.COMPANION && o3.right == KbOwner.COMPANION,
                    "Upd Kneeboard's pages: page 2 (${o2?.left}, ${o2?.right}), page 3 (${o3?.left}, ${o3?.right})")
            } else line("   (no BMS original of pages 2 and 3 in this copy: the pages are not checked)")

            // ---- 2. BMS prints flight B
            val printed = maxOf(System.currentTimeMillis(), pop?.value?.populated?.at ?: 0L) + 5_000
            brief.setLastModified(printed)
            ok(mission().mode == MissionMode.WDP, "BMS prints flight B (briefing.txt at ${Leftovers.date(printed)}), after all of that")

            // ---- 3. switch to EZBoards mode
            val beforeText = text()
            val page3Shipped = place.shippedPage(3)?.readBytes()
            val ez = switch("ezboards")
            val r = ez.value?.reset
            val afterText = text()
            val diff = DtcEdits.between(beforeText, afterText)
            val aKeys = planA.map { it.key }.toSet()
            // a switch clears every leftover of the Planner's, the current flight B's included
            val allKeys = aKeys + forB.key
            ok(ez.ok && r != null && r.to == MissionMode.EZBOARDS && r.now?.callsign == b.callsign, "switched to EZBoards mode for ${r?.now?.label}: ${r?.done}")
            r?.left?.forEach { line("   left: $it") }
            ok(diff.map { it.key }.toSet() == allKeys && diff.all { it.value == Leftovers.clearValue(it.section, it.key) },
                "the cartridge: exactly the Planner's keys (A's and B's) back to BMS's empty values (${diff.joinToString { it.key }})")
            ok(Leftovers.keys(afterText)["STPT\u0000PPT_2"]?.startsWith("1200000") == true,
                "the PPT BMS wrote is left alone")
            ok(r?.keys?.map { it.key }?.toSet() == allKeys && r.undo && r.cartridge == cart.name, "the summary names the ${r?.keys?.size} keys, with an Undo: \"${r?.done?.firstOrNull()}\"")
            ok(info().mission.reset?.at == r?.at, "/api/info carries the summary for every device")
            ok(mission().leftovers == null, "the Mission section has no leftovers left to show")
            ok(info().mission.populated?.callsign == other?.callsign, "WDP mode's snapshot is kept on a switch to EZBoards mode")
            if (pages) {
                val p3 = place.page(3)!!.readBytes()
                val o2 = KneeboardPrint.ownersOf(place, 2)
                ok(page3Shipped != null && p3.contentEquals(page3Shipped), "page 3 (Upd Kneeboard's both halves) is BMS's shipped page again, byte for byte")
                ok(o2?.left == KbOwner.BMS && o2.right == KbOwner.EZBOARDS, "page 2: the left half BMS's again, the right half still EZBoards' (${o2?.left}, ${o2?.right})")
                val good = place.shippedPage(2)!!.readBytes()
                val now2 = place.page(2)!!.readBytes()
                val gi = Dds.header(good)
                val ni = Dds.header(now2)
                val hw = gi.width / 2
                val pa = Dds.decode(good, gi, 0, 0, 0, hw, gi.height)
                val pb = Dds.decode(now2, ni, 0, 0, 0, hw, ni.height)
                var sum = 0L
                for (i in pa.indices) for (sh in intArrayOf(0, 8, 16)) sum += kotlin.math.abs(((pa[i] shr sh) and 0xFF) - ((pb[i] shr sh) and 0xFF))
                val mean = sum.toDouble() / (pa.size * 3)
                ok(mean < 6.0 && ni.format == Dds.header(page3Shipped ?: good).format, "…drawn from BMS's own page (mean difference ${"%.2f".format(mean)} per channel), the file's format kept (${ni.format})")
                ok(r?.pages?.containsAll(listOf("page 2, left", "page 3, left and right")) == true, "the summary names the pages put back: ${r?.pages}")
            }

            // ---- 4. Undo
            val undo = source(req("POST", "/api/mission/source/undo", mapOf("at" to "${r?.at}")))
            val undone = text()
            val back = DtcEdits.between(afterText, undone)
            ok(undo.ok && undo.value?.reset?.undone != 0L && undo.value?.reset?.undo == false && back.map { it.key }.toSet() == allKeys &&
                (planA + forB).all { e -> Leftovers.keys(undone)[e.section.uppercase() + "\u0000" + e.key.uppercase()]?.let { Leftovers.same(it, e.value!!) } == true },
                "Undo writes the ${back.size} keys back exactly: \"${undo.value?.reset?.undoMessage}\"")
            ok(state(req("GET", "/api/cartridge")).ledger?.writes.orEmpty().count { it.mission?.callsign == a.callsign } == aKeys.size && mission().leftovers == null,
                "…the ledger holds them for A again, and nothing is drawn as a leftover")
            val again = source(req("POST", "/api/mission/source/undo", mapOf("at" to "${r?.at}")))
            ok(again.ok && text() == undone, "a second Undo puts back nothing more")

            // ---- 5. back to WDP mode: the snapshot of A' is not the current flight's
            val wdp = switch("wdp")
            ok(wdp.ok && wdp.value?.populated == null && wdp.value?.reset?.snapshot != null && mission().awaitingPopulate,
                "back in WDP mode: the snapshot of A' discarded (${wdp.value?.reset?.snapshot}), \"${wdp.value?.line}\"")
            ok(DtcEdits.between(undone, text()).map { it.key }.toSet() == allKeys, "…and the keys put back by the Undo cleared again")

            // ---- 6. a same-flight switch: B's own key cleared all the same, B's own page kept
            val forB4 = CartridgeEdit("STPT", "ppt_4", "1220000.000000, 996000.000000, 0.000000, 40000.000000, 2")
            state(req("POST", "/api/cartridge/save", mapOf("for" to enc(b)), edits(listOf(forB4))))
            // printed for B after B's PRINT (which this check dated a few seconds ahead)
            if (pages) { KneeboardPrint.printPage(place, 3, arrayOf(gray, null), arrayOf(KbKind.DATACARD_LEFT, null)); place.page(3)!!.setLastModified(printed + 1_000) }
            val t6 = text()
            val p6 = place.page(3)?.readBytes()
            val same = switch("ezboards", b.copy(opened = printed))
            val r6 = same.value?.reset
            val d6 = DtcEdits.between(t6, text())
            ok(same.ok && r6 != null && d6.map { it.key }.toSet() == setOf(forB4.key) && d6.all { it.value == Leftovers.clearValue(it.section, it.key) } &&
                r6.keys.map { it.key }.toSet() == setOf(forB4.key),
                "a switch for the same flight B clears B's own Planner key too: \"${r6?.done?.firstOrNull()}\"")
            ok(p6 == null || place.page(3)!!.readBytes().contentEquals(p6),
                "…and keeps the cockpit page made for B" + (r6?.left?.let { if (it.isEmpty()) "" else " / left: $it" } ?: ""))

            // ---- 7. a new mission in WDP mode: another flight opened in the Planner clears by itself, pages untouched
            switch("wdp", b.copy(opened = printed))
            val c = LedgerMission(callsign = "Opened5", packageId = 5555, theater = Bridge.install.theater, save = "Other.cam", opened = 1)
            state(req("POST", "/api/cartridge/save", mapOf("for" to enc(a)), edits(planA)))
            // C's own key, saved before C is opened here: a new mission for C keeps it
            val forC = CartridgeEdit("STPT", "ppt_5", "1230000.000000, 997000.000000, 0.000000, 30000.000000, 2")
            state(req("POST", "/api/cartridge/save", mapOf("for" to enc(c)), edits(listOf(forC))))
            val t7 = text()
            val p7 = place.page(3)?.readBytes()
            val op = source(req("POST", "/api/mission/opened", mapOf("for" to enc(c))))
            val r7 = op.value?.reset
            val d7 = DtcEdits.between(t7, text()).map { it.key }.toSet()
            ok(op.ok && r7 != null && r7.kind == com.bmscompanion.app.data.mission.SwitchReset.MISSION && r7.now?.callsign == c.callsign && d7 == aKeys &&
                Leftovers.keys(text())["STPT\u0000PPT_2"]?.startsWith("1200000") == true && Leftovers.keys(text())["STPT\u0000PPT_5"]?.startsWith("1230000") == true,
                "WDP mode, flight C opened in the Planner: a new mission — A's keys cleared with nothing pressed, C's own and BMS's PPT kept (${d7.size} keys): \"${r7?.done?.firstOrNull()}\"")
            ok(p7 == null || place.page(3)!!.readBytes().contentEquals(p7), "…and no cockpit kneeboard page touched (a new mission never does)")
            val t8 = text()
            val again7 = source(req("POST", "/api/mission/opened", mapOf("for" to enc(c.copy(opened = 2)))))
            ok(again7.ok && text() == t8 && again7.value?.reset?.at == r7?.at, "the same flight opened again: nothing more")

            // ---- 8. delivery data no ledger knows (an earlier build's, WDP's own): cleared at a new mission whoever wrote it
            val nav = listOf(
                CartridgeEdit("NAV OFFSETS", "Modesel", "vrp"),
                CartridgeEdit("NAV OFFSETS", "VRP", "8,137.7,46870,500"),
                CartridgeEdit("NAV OFFSETS", "VRPPUP", "8,137.7,28642,500"),
                CartridgeEdit("NAV OFFSETS", "OA1-8", "167.6,18939,6748"),
                CartridgeEdit("NAV OFFSETS", "OA2-8", "167.6,18939,500"),
            )
            cart.writeBytes(DtcEdits.apply(text(), nav).toByteArray(Charsets.ISO_8859_1))
            ok(Leftovers.navClearEdits(text()).size == nav.size && state(req("GET", "/api/cartridge")).ledger?.writes.orEmpty().none { it.section.equals("NAV OFFSETS", true) },
                "the cartridge holds delivery data no ledger knows: Modesel vrp, VRP, VRPPUP and two offset aim points")
            if (other == null) line("   (no other flight in the copy's save to populate: the delivery-data sweep is not checked)")
            else {
                val p8 = source(req("POST", "/api/mission/populate", body = Bridge.json.encodeToString(PopulateSend.serializer(), PopulateSend(CampRef("Korea KTO", "Auto Save.cam", other.id), seat = 0))))
                val k8 = Leftovers.keys(text())
                ok(p8.ok && Leftovers.navClearEdits(text()).isEmpty() && k8["NAV OFFSETS\u0000MODESEL"] == "none" && k8["NAV OFFSETS\u0000VRP"] == "0,0,0,0" &&
                    k8["NAV OFFSETS\u0000VRPPUP"] == "0,0,0,0" && k8.keys.none { it.startsWith("NAV OFFSETS\u0000OA") },
                    "Populate of another flight (a new mission): all of it cleared before the cartridge is read — Modesel none, VRP/VRPPUP zeros, the aim points gone")
                val m8 = mission()
                val att8 = client.decodeFromString(com.bmscompanion.app.data.mission.AttackOverlay.serializer(), req("GET", "/api/attack").second)
                ok(m8.plan?.attack?.cues.isNullOrEmpty() && att8.cues.isEmpty() && com.bmscompanion.app.data.mission.PlanMerge.merge(m8).attack == null,
                    "…so the snapshot lays out no attack from them: none on the Mission map (merged), none on the VR boards (/api/attack)")
                // and at a switch, the same whoever wrote it
                cart.writeBytes(DtcEdits.apply(text(), nav).toByteArray(Charsets.ISO_8859_1))
                val sw8 = switch("ezboards")
                ok(sw8.ok && Leftovers.navClearEdits(text()).isEmpty() && sw8.value?.reset?.keys.orEmpty().count { it.section.equals("NAV OFFSETS", true) } == nav.size,
                    "a switch of mode clears the same delivery data, ledger or not (${sw8.value?.reset?.keys?.size} keys)")
            }
        } catch (e: Throwable) {
            ok(false, "part 9 threw: $e")
        } finally {
            runCatching { brief?.setLastModified(briefTime) }
            runCatching { if (ledgerWas != null) ledgerFile.writeBytes(ledgerWas) else ledgerFile.delete() }
            runCatching { if (!plannerWas) planner.deleteRecursively() else if (ledgerWas == null) ledgerFile.parentFile.delete() }
            runCatching { if (MissionSource.mode() != MissionMode.EZBOARDS) Bridge.update(reapply = false) { it.copy(MissionSource = MissionMode.EZBOARDS) } }
            ModeSwitchReset.forget()
        }
    }
}
