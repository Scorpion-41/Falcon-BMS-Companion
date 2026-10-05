package com.bmscompanion.desktop.bridge

import com.bmscompanion.app.data.mission.CartridgeEdit
import com.bmscompanion.app.data.mission.CartridgeState
import com.bmscompanion.app.data.mission.LedgerMission
import com.bmscompanion.app.data.mission.Leftovers
import com.bmscompanion.app.data.mission.MissionData
import com.bmscompanion.app.data.mission.PlanMerge
import com.bmscompanion.app.data.mission.Populated
import com.bmscompanion.app.data.wdp.DtcEdits
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Part 8 of `--missiontest`: a new mission clears by itself what the Planner saved into the cartridge for an earlier
 * flight (docs/DATA-STORES.md, "Starting the next mission"; [ModeSwitchReset.printed]). In EZBoards mode, through the
 * PC's own routes, against the copy:
 *
 * 1. Save to DTC for flight A (a made-up flight, `for=`): a line, two PPTs and a VIP (a nav offset); the ledger
 *    records them for A. A PPT BMS writes (not in the ledger), and one of A's PPTs BMS rewrites.
 * 2. Nothing is drawn as a leftover: `/api/mission` carries none, before or after.
 * 3. **BMS prints flight B** (briefing.txt's time moved after the saves): A's keys still holding A's values are back to
 *    BMS's empty values — the VIP included, the PPT BMS wrote and the one it rewrote left alone — with nothing pressed;
 *    the summary ([SwitchReset.kind] "mission") is on `/api/info` with an Undo; the ledger forgets the keys.
 * 4. **Undo** writes them back exactly.
 * 5. **The same flight printed again** clears nothing; a print of B taken as new keeps B's own key and clears A's
 *    again, though B's print is dated before A's saves (a new mission clears another flight's keys whenever saved).
 *
 * The cartridge and the briefing are put back byte for byte with their times, and the ledger (and the Planner's folder,
 * when this made it) removed, so the copy's hashes still hold.
 */
internal object LeftoversTest {
    private val client = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true; explicitNulls = false }

    fun run(root: File, cart: File?, ok: (Boolean, String) -> Unit, line: (String) -> Unit) {
        line("")
        line("== 8. A new mission clears what the Planner saved for an earlier flight")
        if (cart == null) { ok(false, "the copy has a cartridge to work on"); return }
        val cs = cart.nameWithoutExtension
        val bytes = cart.readBytes()
        val time = cart.lastModified()
        val planner = File(File(root, "User"), PcFileRoutes.PLANNER_FOLDER)
        val plannerWas = planner.isDirectory
        val ledgerFile = File(File(planner, "Ledger"), "$cs.json")
        val ledgerWas = ledgerFile.takeIf { it.isFile }?.readBytes()
        val brief = Bridge.briefingPath?.let(::File)?.takeIf { it.isFile }
        val briefTime = brief?.lastModified() ?: 0L
        var stamp = time
        fun bump() { stamp += 5_000; cart.setLastModified(stamp) }
        fun req(method: String, path: String, query: Map<String, String> = emptyMap(), body: String = ""): Pair<Int, String> {
            val r = Bridge.handle(ApiRequest(method, path, query, body.toByteArray(Charsets.UTF_8)))
            return r.status to r.body.toString(Charsets.UTF_8)
        }
        fun state(r: Pair<Int, String>) = client.decodeFromString(CartridgeState.serializer(), r.second)
        fun mission() = client.decodeFromString(MissionData.serializer(), req("GET", "/api/mission").second)
        fun enc(m: LedgerMission) = client.encodeToString(LedgerMission.serializer(), m)
        fun edits(list: List<CartridgeEdit>) = client.encodeToString(ListSerializer(CartridgeEdit.serializer()), list)
        try {
            ledgerFile.delete()
            val m0 = mission()
            val b = LedgerMission.ofBriefing(m0.briefing, m0.briefingModified, Bridge.install.theater)
            ok(b != null, "the printed briefing names this mission's flight (${b?.label})")
            if (b == null) return
            val a = LedgerMission(callsign = "Leftover9", packageId = 9999, theater = Bridge.install.theater, save = "Test.cam", opened = 1)
            ok(!a.sameFlight(b) && a.sameFlight(a.copy(save = "Test.cam", opened = 2)), "flight A (${a.label}) is another flight than B, and itself")

            // ---- 1. Save to DTC for A
            val lineA = (0..2).map { i -> CartridgeEdit("STPT", "lineSTPT_$i", "${1_000_000 + i * 20_000}.000000, ${900_000 + i * 15_000}.000000, 0.000000") }
            val pptA = listOf(
                CartridgeEdit("STPT", "ppt_0", "1100000.000000, 950000.000000, 0.000000, 98425.000000, 3"),
                CartridgeEdit("STPT", "ppt_1", "1150000.000000, 980000.000000, 0.000000, 60000.000000, 6"),
            )
            val vip = CartridgeEdit("NAV OFFSETS", "VIP", "3,123.4,25000,0")
            val saveA = state(req("POST", "/api/cartridge/save", mapOf("for" to enc(a)), edits(lineA + pptA + vip)))
            bump()
            ok(saveA.error == null && saveA.ledger?.writes?.count { it.mission?.callsign == a.callsign } == 6,
                "Save to DTC for A: written, the ledger holds its 6 keys for A (${saveA.error ?: saveA.message})")
            ok(ledgerFile.isFile, "the ledger is ${ledgerFile.path.removePrefix(root.path)}")
            // a PPT BMS wrote: not in the ledger, so never a leftover
            run {
                val t = String(cart.readBytes(), Charsets.ISO_8859_1)
                cart.writeBytes(DtcEdits.apply(t, listOf(CartridgeEdit("STPT", "ppt_2", "1200000.000000, 990000.000000, 0.000000, 50000.000000, 2"))).toByteArray(Charsets.ISO_8859_1))
                bump()
            }

            // BMS rewrites A's ppt_1 meanwhile: BMS's now, never cleared
            val bmsPpt1 = "1150000.500000, 980100.000000, 0.000000, 60000.000000, 6"
            run {
                val t = String(cart.readBytes(), Charsets.ISO_8859_1)
                cart.writeBytes(DtcEdits.apply(t, listOf(CartridgeEdit("STPT", "ppt_1", bmsPpt1))).toByteArray(Charsets.ISO_8859_1))
                bump()
            }

            // ---- 2. nothing is drawn as a leftover
            ok(mission().leftovers == null, "/api/mission carries no leftovers to draw (the clearing is automatic)")
            ok(req("GET", "/api/mission").second == req("GET", "/api/mission", mapOf("source" to "bms")).second,
                "…and is still byte for byte the Planner's ?source=bms")

            // ---- 3. BMS prints flight B: a new mission
            if (brief == null) { ok(false, "the copy has a printed briefing"); return }
            ModeSwitchReset.forget()
            val printed = System.currentTimeMillis() + 5_000
            brief.setLastModified(printed)
            val before = String(cart.readBytes(), Charsets.ISO_8859_1)
            val r = ModeSwitchReset.printed()
            val after = String(cart.readBytes(), Charsets.ISO_8859_1)
            val diff = DtcEdits.between(before, after)
            val want = setOf("lineSTPT_0", "lineSTPT_1", "lineSTPT_2", "ppt_0", "VIP")
            ok(r != null && r.kind == com.bmscompanion.app.data.mission.SwitchReset.MISSION && r.now?.callsign == b.callsign && r.undo,
                "PRINT of B is a new mission: \"${r?.done?.firstOrNull()}\"")
            ok(diff.map { it.key }.toSet() == want, "the cartridge: exactly A's keys cleared, the VIP among them (${diff.map { it.key }})")
            ok(diff.all { it.value == Leftovers.clearValue(it.section, it.key) }, "…each back to BMS's empty value (${diff.joinToString { "${it.key}=${it.value}" }})")
            ok(Leftovers.keys(after)["STPT\u0000PPT_1"] == bmsPpt1 && Leftovers.keys(after)["STPT\u0000PPT_2"]?.startsWith("1200000") == true,
                "…and left PPT 57 (BMS's since) and PPT 58 (BMS's) alone")
            val inf = client.decodeFromString(com.bmscompanion.app.data.mission.BridgeInfo.serializer(), req("GET", "/api/info").second)
            ok(inf.mission.reset?.at == r?.at && inf.mission.reset?.kind == com.bmscompanion.app.data.mission.SwitchReset.MISSION,
                "/api/info carries the note for every device, with its Undo")
            ok(state(req("GET", "/api/cartridge")).ledger?.writes.orEmpty().none { it.key in want }, "the ledger forgets the cleared keys")
            ok(mission().leftovers == null, "still nothing drawn as a leftover")

            // ---- 4. Undo
            val undo = req("POST", "/api/mission/source/undo", mapOf("at" to "${r?.at}")).first == 200
            val undone = String(cart.readBytes(), Charsets.ISO_8859_1)
            ok(undo && DtcEdits.between(after, undone).map { it.key }.toSet() == want &&
                (lineA + pptA.take(1) + vip).all { e -> Leftovers.keys(undone)[e.section.uppercase() + "\u0000" + e.key.uppercase()]?.let { Leftovers.same(it, e.value!!) } == true },
                "Undo writes A's ${want.size} keys back exactly")

            // ---- 5. the same flight printed again; B's own key
            state(req("POST", "/api/cartridge/save", mapOf("for" to enc(b)), edits(listOf(CartridgeEdit("STPT", "ppt_4", "1220000.000000, 996000.000000, 0.000000, 40000.000000, 2")))))
            bump()
            brief.setLastModified(printed + 10_000)
            val t5 = String(cart.readBytes(), Charsets.ISO_8859_1)
            val r5 = ModeSwitchReset.printed()
            ok(r5 == null && String(cart.readBytes(), Charsets.ISO_8859_1) == t5, "B printed again: the same mission, nothing cleared (A's keys, put back by the Undo, stay)")
            ModeSwitchReset.forgetFlights()
            // printed a minute *before* A's keys were saved: a new mission clears another flight's keys whenever saved
            brief.setLastModified(System.currentTimeMillis() - 60_000)
            val r6 = ModeSwitchReset.printed()
            val t6 = String(cart.readBytes(), Charsets.ISO_8859_1)
            ok(Leftovers.keys(t6)["STPT\u0000PPT_4"]?.startsWith("1220000") == true && r6?.keys?.none { it.key == "ppt_4" } != false,
                "a PRINT never clears the printed flight's own key (B's PPT 60 kept)")
            ok(r6?.keys?.map { it.key }?.toSet() == want && DtcEdits.between(t5, t6).map { it.key }.toSet() == want,
                "…and clears every key of A's again, though they were saved after B was printed (${r6?.keys?.size ?: 0} keys)")
        } catch (e: Throwable) {
            ok(false, "part 8 threw: $e")
        } finally {
            ModeSwitchReset.forget()
            runCatching { brief?.setLastModified(briefTime) }
            runCatching { if (!cart.readBytes().contentEquals(bytes)) cart.writeBytes(bytes); cart.setLastModified(time) }
            runCatching { if (ledgerWas != null) ledgerFile.writeBytes(ledgerWas) else ledgerFile.delete() }
            runCatching { if (!plannerWas) planner.deleteRecursively() else if (ledgerWas == null) ledgerFile.parentFile.delete() }
        }
    }
}
