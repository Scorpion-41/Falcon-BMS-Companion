package com.bmscompanion.desktop.bridge

import com.bmscompanion.app.data.mission.CampRef
import com.bmscompanion.app.data.mission.CartridgeEdit
import com.bmscompanion.app.data.mission.CartridgeState
import com.bmscompanion.app.data.mission.LedgerMission
import com.bmscompanion.app.data.mission.Leftovers
import com.bmscompanion.app.data.mission.MissionData
import com.bmscompanion.app.data.wdp.DtcEdits
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Part 8b of `--missiontest`: **BMS's copies of the Planner's items** are cleared at a new mission too (1.3.9;
 * [CartridgeStore.resetForSwitch], docs/DATA-STORES.md "Starting the next mission"). A pilot's sequence, with the files
 * of the copy:
 *
 * 1. The Planner saves for flight A, with save A open (`Save-Day  1 01 34 28.cam`): Line 1 (a closed box), Line 4
 *    (three points), a PPT and a precision target — into the cartridge and save A's mission file.
 * 2. BMS prints flight B: a new mission clears all of it from both files (the rule before 1.3.9).
 * 3. **BMS writes from its DTC memory**, which still holds what the pilot LOADed before: Line 1 exactly, the PPT in BMS's
 *    own format (height 0, range as a float), the target, and Line 4 with a point the pilot added in BMS, into the
 *    cartridge and the mission file BMS's LOAD reads now (the newest save's, here `Auto Save.ini`); the pilot's own Line
 *    2 drawn in BMS; B's own Line 3 saved by the Planner for B and copied by BMS. An unrelated save's mission file gets
 *    the same Line 1.
 * 4. **BMS prints B again, taken as a new mission**: the PPT and the target (BMS's copies, recognised against what the
 *    last reset cleared) are cleared in the cartridge and the LOAD file; every line is kept — Line 1 too, since no log
 *    of past values is kept (Open mission… cleans lines, [ClearLinesTest]) — and so are BMS's route (`target_n` of
 *    action 0 and up) and the unrelated save's file; the ledger keeps what it cleared and has no `history`.
 * 5. Undo puts the copies back where the file still holds the empty value.
 *
 * Every mission file touched is put back byte for byte with its time; the cartridge, the briefing's time and the
 * ledger as [LeftoversTest] does.
 */
internal object LeftoverCopiesTest {
    private val client = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true; explicitNulls = false }
    private const val SAVE_A = "Save-Day  1 01 34 28.cam"
    private const val OTHER_INI = "Save-Day  1 04 11 35.ini"

    fun run(root: File, cart: File?, ok: (Boolean, String) -> Unit, line: (String) -> Unit) {
        line("")
        line("== 8b. A new mission clears BMS's copies of what the Planner saved for an earlier flight")
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
        val theater = Bridge.install.theater
        val camp = runCatching { Theaters.of(Bridge.install)?.let { s -> s.current(theater)?.let { s.campaignDir(it) } } }.getOrNull()
        val kept = HashMap<File, Pair<ByteArray, Long>>()
        fun keep(f: File?) { if (f != null && f.isFile && f !in kept) kept[f] = f.readBytes() to f.lastModified() }
        fun req(method: String, path: String, query: Map<String, String> = emptyMap(), body: String = ""): Pair<Int, String> {
            val r = Bridge.handle(ApiRequest(method, path, query, body.toByteArray(Charsets.UTF_8)))
            return r.status to r.body.toString(Charsets.UTF_8)
        }
        fun state(r: Pair<Int, String>) = client.decodeFromString(CartridgeState.serializer(), r.second)
        fun enc(m: LedgerMission) = client.encodeToString(LedgerMission.serializer(), m)
        fun edits(list: List<CartridgeEdit>) = client.encodeToString(ListSerializer(CartridgeEdit.serializer()), list)
        fun text(f: File) = String(f.readBytes(), Charsets.ISO_8859_1)
        fun put(f: File, list: List<CartridgeEdit>) = f.writeBytes(DtcEdits.apply(text(f), list).toByteArray(Charsets.ISO_8859_1))
        fun held(f: File, key: String) = Leftovers.keys(text(f))["STPT\u0000" + key.uppercase()]
        fun empty(f: File, keys: List<String>) = keys.all { k -> held(f, k)?.let { Leftovers.isEmpty(Leftovers.kindOf("STPT", k)!!.first, it) } != false }
        fun holds(f: File, list: List<CartridgeEdit>) = list.all { e -> held(f, e.key)?.let { Leftovers.sameItem(Leftovers.kindOf("STPT", e.key)!!.first, it, e.value!!) } == true }
        fun pt(n: Double, e: Double) = "%.6f, %.6f, 0.000000".format(n, e)
        try {
            ledgerFile.delete()
            ModeSwitchReset.forget()
            val iniA = camp?.listFiles()?.firstOrNull { it.name.equals(SAVE_A.substringBeforeLast('.') + ".ini", true) }
            val load = MissionDtcFile.verdict().ini?.takeIf { it.isFile }
            val other = camp?.listFiles()?.firstOrNull { it.name.equals(OTHER_INI, true) }
            ok(iniA != null && load != null && other != null && iniA != load && other != load && brief != null,
                "the copy has save A's mission file (${iniA?.name}), the one BMS's LOAD reads now (${load?.name}), another save's (${other?.name}) and a printed briefing")
            if (iniA == null || load == null || other == null || brief == null) return
            listOf(iniA, load, other).forEach(::keep)
            val m0 = client.decodeFromString(MissionData.serializer(), req("GET", "/api/mission").second)
            val b = LedgerMission.ofBriefing(m0.briefing, m0.briefingModified, theater) ?: run { ok(false, "the printed briefing names its flight"); return }
            val a = LedgerMission(callsign = "Leftover9", packageId = 9999, theater = theater, save = SAVE_A, opened = 1)

            // ---- 1. the Planner saves for A with save A open
            val line1 = (0..3).map { i -> CartridgeEdit("STPT", "lineSTPT_$i", pt(1_525_140.625 - i * 30_000.5, 1_567_718.375 + i * 20_000.25)) }
                .let { it + CartridgeEdit("STPT", "lineSTPT_4", it[0].value) }
            val line4 = (18..20).map { i -> CartridgeEdit("STPT", "lineSTPT_$i", pt(1_100_000.0 + i * 1000, 1_700_000.0 + i * 700)) }
            val ppt = CartridgeEdit("STPT", "ppt_0", "1300000.000000, 1000000.000000, -500.000000, 164055.1, SA2")
            val tgt = CartridgeEdit("STPT", "target_14", "1250000.000000, 1050000.000000, -120.000000, -1, Leftover target")
            val planA = line1 + line4 + ppt + tgt
            val savedA = Bridge.cartridge.saveTe(cs, planA, CampRef(theater ?: "", SAVE_A), a)
            ok(savedA.error == null && savedA.mission?.written == true && holds(cart, planA) && holds(iniA, planA),
                "1. Save to DTC for A with ${SAVE_A.trim()} open: Line 1, Line 4, a PPT and a target in the cartridge and ${iniA.name} (${savedA.mission?.reason ?: "written"})")

            // ---- 2. BMS prints B: the Planner's own rows cleared (the rule before 1.3.9)
            brief.setLastModified(System.currentTimeMillis() + 5_000)
            val r1 = ModeSwitchReset.printed()
            ok(r1 != null && empty(cart, planA.map { it.key }) && empty(iniA, planA.map { it.key }),
                "2. PRINT of B: all of A's items cleared from the cartridge and ${iniA.name} (\"${r1?.done?.firstOrNull()}\")")

            // ---- 3. BMS writes its DTC memory (what the pilot LOADed before the reset) into the cartridge and the LOAD file
            val pptBms = CartridgeEdit("STPT", "ppt_0", "1300000.000000, 1000000.000000, 0.000000, 164055.125000, SA2")
            val tgtBms = CartridgeEdit("STPT", "target_14", "1250000.000000, 1050000.000000, -120.000000, -1,   Leftover target")
            val pilotPoint = CartridgeEdit("STPT", "lineSTPT_21", pt(1_131_000.0, 1_721_000.0))
            val pilotLine2 = (6..8).map { i -> CartridgeEdit("STPT", "lineSTPT_$i", pt(1_400_000.0 + i * 3_333.0, 1_600_000.0 - i * 2_222.0)) }
            val line3B = (12..14).map { i -> CartridgeEdit("STPT", "lineSTPT_$i", pt(1_200_000.0 + i * 5_000, 1_650_000.0 + i * 4_000)) }
            val forB = state(req("POST", "/api/cartridge/save", mapOf("for" to enc(b)), edits(line3B)))
            val copies = line1 + line4 + pilotPoint + pptBms + tgtBms + pilotLine2 + line3B
            put(cart, copies)
            put(load, copies)
            put(other, line1)
            val routeBefore = Leftovers.keys(text(load)).filter { (k, v) -> k.startsWith("STPT\u0000TARGET_") && v.split(',').getOrNull(3)?.trim()?.toDoubleOrNull()?.let { it >= 0 } == true }
            val otherBefore = text(other)
            ok(forB.error == null && routeBefore.isNotEmpty(),
                "3. BMS writes its memory into the cartridge and ${load.name}: Line 1, the PPT in BMS's format, the target, Line 4 with a point the pilot added, the pilot's Line 2, B's own Line 3 (the Planner saved it for B); ${other.name} gets Line 1 (${routeBefore.size} route points in ${load.name})")

            // ---- 4. a new mission for B again
            ModeSwitchReset.forgetFlights()
            brief.setLastModified(System.currentTimeMillis() + 10_000)
            val r2 = ModeSwitchReset.printed()
            val gone = listOf(pptBms, tgtBms).map { it.key }
            for ((f, what) in listOf(cart to "the cartridge", load to load.name)) {
                ok(empty(f, gone), "4. $what: the PPT and the target (BMS's copies of A's, in BMS's format) cleared")
                ok(holds(f, line1), "…BMS's copy of A's Line 1 kept (no log of past values: Open mission… cleans lines)")
                ok(holds(f, pilotLine2), "…the pilot's own Line 2 (drawn in BMS) kept")
                ok(holds(f, line3B), "…B's own Line 3 (saved by the Planner for B, copied by BMS) kept")
                ok(holds(f, line4 + pilotPoint), "…Line 4 kept whole: the pilot added a point to it in BMS")
            }
            ok(Leftovers.keys(text(load)).filterKeys { it in routeBefore.keys } == routeBefore, "…BMS's route in ${load.name} untouched (${routeBefore.size} points)")
            ok(text(other) == otherBefore, "…and ${other.name}, neither named by the ledger nor read by LOAD, untouched")
            val keys = r2?.keys.orEmpty()
            ok(r2 != null && keys.any { it.file.equals(load.name, true) && it.kind == Leftovers.PPT } && keys.any { it.file.isEmpty() && it.kind == Leftovers.STEERPOINT } &&
                keys.none { it.kind == Leftovers.LINE },
                "the summary names the copies cleared (${keys.size} keys): \"${r2?.done?.firstOrNull()}\"")
            val led = Bridge.cartridge.ledger(cs)
            ok(led.cleared.any { it.write.file.equals(load.name, true) } && !ledgerFile.readText().contains("\"history\""),
                "the ledger keeps the copies it cleared, for the Undo, and no log of past values (no history)")

            // ---- 5. Undo
            val undo = req("POST", "/api/mission/source/undo", mapOf("at" to "${r2?.at}")).first == 200
            ok(undo && holds(load, listOf(pptBms, tgtBms)) && holds(cart, listOf(pptBms, tgtBms)), "5. Undo puts BMS's copies back")
        } catch (e: Throwable) {
            ok(false, "part 8b threw: $e")
        } finally {
            ModeSwitchReset.forget()
            runCatching { brief?.setLastModified(briefTime) }
            kept.forEach { (f, v) -> runCatching { if (!f.readBytes().contentEquals(v.first)) f.writeBytes(v.first); f.setLastModified(v.second) } }
            runCatching { if (!cart.readBytes().contentEquals(bytes)) cart.writeBytes(bytes); cart.setLastModified(time) }
            runCatching { if (ledgerWas != null) ledgerFile.writeBytes(ledgerWas) else ledgerFile.delete() }
            runCatching { if (!plannerWas) planner.deleteRecursively() else if (ledgerWas == null) ledgerFile.parentFile.delete() }
        }
    }
}
