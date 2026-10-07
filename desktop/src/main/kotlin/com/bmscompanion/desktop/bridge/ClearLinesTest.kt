package com.bmscompanion.desktop.bridge

import com.bmscompanion.app.data.mission.CartridgeEdit
import com.bmscompanion.app.data.mission.CartridgeState
import com.bmscompanion.app.data.mission.LedgerMission
import com.bmscompanion.app.data.mission.Leftovers
import com.bmscompanion.app.data.mission.MissionData
import com.bmscompanion.app.data.mission.MissionMode
import com.bmscompanion.app.data.mission.MissionSourceInfo
import com.bmscompanion.app.data.mission.PlannerPcSettings
import com.bmscompanion.app.data.mission.SwitchReset
import com.bmscompanion.app.data.wdp.DtcEdits
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Part 8c of `--missiontest`: **Start each opened mission with clean lines, PPTs and Open 1/2 steerpoints** (1.3.9; the Planner's Settings,
 * `BridgeSettings.CleanOpenedMission`, `POST /api/mission/opened?clean=1`, [ModeSwitchReset.cleanOpened],
 * [ModeSwitchReset.openFiles], [CartridgeStore.cleanForOpen]).
 *
 * 0. `GET`/`POST /api/planner/settings` read and set it.
 * 1. Which files: the opened campaign save's mission file (the one BMS's LOAD reads for it); a TE's or a training's
 *    flight none at all (BMS loads a TE's own lines itself); with the setting off none.
 * 2. The pilot's own Lines 1 and 2 (drawn in BMS: no ledger row), a PPT of BMS's, a Recon target and BMS's route are
 *    in the cartridge and the LOAD file; the Planner saved Line 3 and a PPT for flight B; a TE's mission file holds
 *    lines of its own; Open 1 holds two steerpoints of the pilot's (STPT 81 Land, 85) and Open 2 one the Planner saved
 *    for B (STPT 90). **Open mission… of B** (`clean=1`): Lines 1 and 2, BMS's PPT and STPT 81 and 85 cleaned in both
 *    files; B's own Line 3, PPT and STPT 90, the Recon target and the route (STPT 1-24) kept; the TE's file byte for byte; the answer says so (kind
 *    `mission`, so the Planner reads its cartridge again); the Undo puts them back.
 * 3. The same flight planned again (no `clean`), a PRINT of another flight and a switch of mode: nothing of the
 *    pilot's lines or PPTs goes.
 * 4. The setting off: Open mission… cleans nothing.
 *
 * Every file touched is put back byte for byte with its time, and the setting as it was.
 */
internal object ClearLinesTest {
    private val client = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true; explicitNulls = false }

    fun run(root: File, cart: File?, ok: (Boolean, String) -> Unit, line: (String) -> Unit) {
        line("")
        line("== 8c. Start each opened mission with clean lines, PPTs and Open 1/2 steerpoints (the Planner's Settings)")
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
        val settingWas = Bridge.settings.value.CleanOpenedMission
        val theater = Bridge.install.theater
        val set = Theaters.of(Bridge.install)
        val camp = runCatching { set?.let { s -> s.current(theater)?.let { s.campaignDir(it) } } }.getOrNull()
        val kept = HashMap<File, Pair<ByteArray, Long>>()
        fun keep(f: File?) { if (f != null && f.isFile && f !in kept) kept[f] = f.readBytes() to f.lastModified() }
        fun req(method: String, path: String, query: Map<String, String> = emptyMap(), body: String = ""): Pair<Int, String> {
            val r = Bridge.handle(ApiRequest(method, path, query, body.toByteArray(Charsets.UTF_8)))
            return r.status to r.body.toString(Charsets.UTF_8)
        }
        fun setting(r: Pair<Int, String>) = runCatching { client.decodeFromString(PlannerPcSettings.serializer(), r.second) }.getOrNull()
        fun state(r: Pair<Int, String>) = client.decodeFromString(CartridgeState.serializer(), r.second)
        fun source(r: Pair<Int, String>) = runCatching { client.decodeFromString(MissionSourceInfo.serializer(), r.second) }.getOrNull()
        fun enc(m: LedgerMission) = client.encodeToString(LedgerMission.serializer(), m)
        fun edits(list: List<CartridgeEdit>) = client.encodeToString(ListSerializer(CartridgeEdit.serializer()), list)
        fun text(f: File) = String(f.readBytes(), Charsets.ISO_8859_1)
        fun put(f: File, list: List<CartridgeEdit>) = f.writeBytes(DtcEdits.apply(text(f), list).toByteArray(Charsets.ISO_8859_1))
        fun held(f: File, key: String) = Leftovers.keys(text(f))["STPT\u0000" + key.uppercase()]
        fun empty(f: File, keys: List<String>) = keys.all { k -> held(f, k)?.let { Leftovers.isEmpty(Leftovers.kindOf("STPT", k)!!.first, it) } != false }
        // (a steerpoint of any type field by field: sameItem matches only Precision ones, an Open bank point may be Land)
        fun holds(f: File, list: List<CartridgeEdit>) = list.all { e ->
            val kind = Leftovers.kindOf("STPT", e.key)!!.first
            held(f, e.key)?.let { if (kind == Leftovers.STEERPOINT) Leftovers.same(it, e.value!!) else Leftovers.sameItem(kind, it, e.value!!) } == true
        }
        fun pt(n: Double, e: Double) = "%.6f, %.6f, 0.000000".format(n, e)
        fun open(m: LedgerMission, clean: Boolean) = source(req("POST", "/api/mission/opened", mapOf("for" to enc(m)) + (if (clean) mapOf("clean" to "1") else emptyMap())))
        try {
            ledgerFile.delete()
            ModeSwitchReset.forget()

            // ---- 0. the route
            val g0 = setting(req("GET", "/api/planner/settings"))
            val on = setting(req("POST", "/api/planner/settings", mapOf("cleanOpened" to "1")))
            ok(g0 != null && on?.cleanOpened == true && Bridge.settings.value.CleanOpenedMission && setting(req("GET", "/api/planner/settings"))?.cleanOpened == true,
                "0. GET /api/planner/settings answers (cleanOpened ${g0?.cleanOpened}); POST ?cleanOpened=1 turns it on and the PC keeps it")

            // ---- 1. which files
            val m0 = client.decodeFromString(MissionData.serializer(), req("GET", "/api/mission").second)
            val printed = LedgerMission.ofBriefing(m0.briefing, m0.briefingModified, theater) ?: run { ok(false, "the printed briefing names its flight"); return }
            // the printed flight as Open mission… plans it: in the save that holds it
            val b = printed.copy(save = "Auto Save.cam", opened = System.currentTimeMillis())
            val files = ModeSwitchReset.openFiles(b)
            val load = files?.singleOrNull()
            ok(load != null && load.isFile && load.parentFile?.let { camp != null && Theaters.canonical(it) == Theaters.canonical(camp) } == true,
                "1. ${b.label} opened: its campaign save's mission file, the one BMS's LOAD reads (${load?.name ?: files})")
            val teSave = camp?.listFiles()?.filter { it.isFile && (it.name.endsWith(".tac", true) || it.name.endsWith(".trn", true)) && CampaignStarts.byName(it.name) == null }
                ?.firstOrNull { s -> File(s.parentFile, s.nameWithoutExtension + ".ini").let { it.isFile && (load == null || !it.name.equals(load.name, true)) } }
            val teIni = teSave?.let { File(it.parentFile, it.nameWithoutExtension + ".ini") }
            ok(teSave != null && ModeSwitchReset.openFiles(b.copy(save = teSave.name)) == null,
                "…a TE's flight (${teSave?.name}): nothing at all, not even the cartridge (BMS loads a TE's own lines when its map is built)")
            if (load == null || brief == null || teIni == null) return
            keep(load); keep(teIni)

            // ---- 2. Open mission… of B
            val line3B = (12..14).map { i -> CartridgeEdit("STPT", "lineSTPT_$i", pt(1_200_000.0 + i * 5_000, 1_650_000.0 + i * 4_000)) }
            val pptB = CartridgeEdit("STPT", "ppt_2", "1350000.000000, 1100000.000000, 0.000000, 98425.2, SA6")
            // an Open 2 steerpoint the Planner saved for B (STPT 90, Nav)
            val openB = CartridgeEdit("STPT", "target_89", "1320000.000000, 1070000.000000, -40.000000, 0, B nav point")
            val forB = state(req("POST", "/api/cartridge/save", mapOf("for" to enc(b)), edits(line3B + pptB + openB)))
            val line1 = (0..3).map { i -> CartridgeEdit("STPT", "lineSTPT_$i", pt(1_525_140.625 - i * 30_000.5, 1_567_718.375 + i * 20_000.25)) }
                .let { it + CartridgeEdit("STPT", "lineSTPT_4", it[0].value) }
            val line2 = (6..8).map { i -> CartridgeEdit("STPT", "lineSTPT_$i", pt(1_400_000.0 + i * 3_333.0, 1_600_000.0 - i * 2_222.0)) }
            val pptBms = CartridgeEdit("STPT", "ppt_0", "1300000.000000, 1000000.000000, 0.000000, 164055.125000, SA2")
            val recon = CartridgeEdit("STPT", "target_14", "1250000.000000, 1050000.000000, -120.000000, -1, Recon target")
            // the pilot's alternates in Open 1 (STPT 81 Land, STPT 85 Precision), made in BMS or by an earlier mission
            val open1 = listOf(
                CartridgeEdit("STPT", "target_80", "1310000.000000, 1060000.000000, -18.000000, 7, Alternate field"),
                CartridgeEdit("STPT", "target_84", "1290000.000000, 1040000.000000, -250.000000, -1, Old target"),
            )
            val bms = line1 + line2 + line3B + pptBms + pptB + recon + open1 + openB
            put(cart, bms)
            put(load, bms)
            val teLine = (18..20).map { i -> CartridgeEdit("STPT", "lineSTPT_$i", pt(1_100_000.0 + i * 1000, 1_700_000.0 + i * 700)) }
            put(teIni, teLine)
            val teBefore = teIni.readBytes()
            // BMS's route: STPT 1-24 (target_0…23) of a route action, which no clean ever touches
            val routeBefore = Leftovers.keys(text(load)).filter { (k, v) -> k.startsWith("STPT\u0000TARGET_") && (k.substringAfter("TARGET_").toIntOrNull() ?: 99) < 24 && v.split(',').getOrNull(3)?.trim()?.toDoubleOrNull()?.let { it >= 0 } == true }
            ok(forB.error == null, "2. the Planner saved Line 3 and a PPT for B; the pilot's Lines 1 and 2, BMS's PPT and a Recon target in the cartridge and ${load.name}; ${teIni.name} has lines of its own")
            val r1 = open(b, clean = true)?.reset
            val gone = (line1 + line2 + pptBms + open1).map { it.key }
            for ((f, what) in listOf(cart to "the cartridge", load to load.name)) {
                ok(empty(f, gone), "…$what: the pilot's Lines 1 and 2, BMS's PPT and the Open 1 steerpoints (STPT 81, 85) cleaned")
                ok(holds(f, line3B + pptB + openB), "…$what: B's own Line 3, PPT and STPT 90 (the Planner saved them for B) kept")
                ok(holds(f, listOf(recon)), "…$what: the Recon target (target_14, action −1) untouched")
            }
            ok(Leftovers.keys(text(load)).filterKeys { it in routeBefore.keys } == routeBefore, "…BMS's route in ${load.name} untouched (${routeBefore.size} points)")
            ok(teIni.readBytes().contentEquals(teBefore), "…${teIni.name}, a TE's own mission file, untouched")
            val keys = r1?.keys.orEmpty()
            val led = Bridge.cartridge.ledger(cs)
            ok(r1 != null && r1.kind == SwitchReset.MISSION && r1.now?.sameFlight(b) == true && keys.any { it.file.equals(load.name, true) && it.kind == Leftovers.PPT } &&
                keys.any { it.file.isEmpty() && it.kind == Leftovers.LINE } && led.cleared.any { it.write.file.equals(load.name, true) && it.write.mission == null } &&
                led.writes.none { it.mission == null },
                "…the answer says so, for the Planner to read its cartridge again, and the ledger keeps them for the Undo (under no flight): \"${r1?.done?.firstOrNull()}\"")
            val undo = req("POST", "/api/mission/source/undo", mapOf("at" to "${r1?.at}")).first == 200
            ok(r1 != null && keys.any { it.kind == Leftovers.STEERPOINT }, "…the answer counts the steerpoints: \"${r1?.done?.firstOrNull()}\"")
            ok(undo && holds(load, line1 + line2 + pptBms + open1) && holds(cart, line1 + line2 + pptBms + open1), "…and the Undo puts them back")

            // ---- 3. nothing else cleans them
            open(b, clean = false)
            ok(holds(cart, line1 + pptBms) && holds(load, line1 + pptBms), "3. the same flight planned again (Reload, a seat): kept")
            ModeSwitchReset.forgetFlights()
            brief.setLastModified(System.currentTimeMillis() + 5_000)
            ModeSwitchReset.printed()
            ok(holds(cart, line1 + line2 + pptBms) && holds(load, line1 + line2 + pptBms), "…a PRINT (a new mission in EZBoards mode): the pilot's lines and PPTs kept")
            req("POST", "/api/mission/source", mapOf("mode" to MissionMode.WDP))
            req("POST", "/api/mission/source", mapOf("mode" to MissionMode.EZBOARDS))
            val mode = source(req("GET", "/api/mission/source"))?.mode
            ok(holds(cart, line1 + line2 + pptBms) && mode == MissionMode.EZBOARDS, "…a switch of mode and back: kept")

            // ---- 4. the setting off
            val off = setting(req("POST", "/api/planner/settings", mapOf("cleanOpened" to "0")))
            open(b.copy(opened = System.currentTimeMillis()), clean = true)
            ok(off?.cleanOpened == false && ModeSwitchReset.openFiles(b) == null && holds(cart, line1 + line2 + pptBms) && holds(load, line1 + line2 + pptBms),
                "4. off: Open mission… cleans nothing")
        } catch (e: Throwable) {
            ok(false, "part 8c threw: $e")
        } finally {
            ModeSwitchReset.forget()
            runCatching { Bridge.update(reapply = false) { it.copy(CleanOpenedMission = settingWas) } }
            runCatching { brief?.setLastModified(briefTime) }
            kept.forEach { (f, v) -> runCatching { if (!f.readBytes().contentEquals(v.first)) f.writeBytes(v.first); f.setLastModified(v.second) } }
            runCatching { if (!cart.readBytes().contentEquals(bytes)) cart.writeBytes(bytes); cart.setLastModified(time) }
            runCatching { if (ledgerWas != null) ledgerFile.writeBytes(ledgerWas) else ledgerFile.delete() }
            runCatching { if (!plannerWas) planner.deleteRecursively() else if (ledgerWas == null) ledgerFile.parentFile.delete() }
        }
    }
}
