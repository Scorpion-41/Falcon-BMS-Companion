package com.bmscompanion.desktop.bridge

import com.bmscompanion.app.data.mission.CartridgeEdit
import com.bmscompanion.app.data.mission.CartridgeState
import com.bmscompanion.app.data.mission.Leftovers
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/**
 * `--tesavetest <copy of a Data/Campaign> out.txt [more…]`: Save to DTC into a Tactical Engagement's own mission file
 * (R3-PLAN A13, [CartridgeStore.saveTe]), end to end through the route the Planner calls
 * (`POST /api/cartridge/save?callsign=&te=<theater>|<file>`), on a copy.
 *
 * The folder is a copy of a theater's campaign folder **inside a copy of a BMS folder** (the theater definitions have
 * to be above it, as in `<copy>/Data/Campaign`); the BMS folder itself may be given instead. It must be a copy: a
 * folder the developer guard would refuse (one holding `Falcon BMS.exe`, or the registry's install) is refused before
 * anything is touched. Into that copy the check puts what it needs, all made from BMS's own `TE_BMS_03_F-16_DEAD`:
 * - `My DEAD.tac` + `.ini`: the pilot's own TE, as BMS's SAVE under a new name leaves it (the save's header and its
 *   parts carry the new name; the `.ini`'s title too) — the one that must be written;
 * - `Renamed DEAD`: the same TE copied and renamed in Explorer (its header still says `TE_BMS_03_F-16_DEAD`);
 * - `ReadOnly DEAD` (its `.ini` read-only), `NoIni DEAD` (no `.ini`), `Twin DEAD` (a `Twin DEAD.cam` saved later),
 *   and `Copied Template` (BMS's TE template under another name, with an `.ini`: stock by its objectives part);
 * - a test pilot's cartridge `Tesave.ini` (a copy of the pilot cartridge found in `User/Config`), saved to at once as
 *   WDP saves (no backup, nothing to switch on), and a pilot `Tesave2` with no cartridge at all.
 *
 * Then: the user TE gets **exactly** the edited `[STPT]` keys and every other byte is identical (the diff is in the
 * report); non-steerpoint edits and removals stay out of it; saving again changes nothing; nothing is added to
 * `User/Config` (no backup folder, README or record); the TE and the training that ship with BMS are written like the
 * pilot's own, as in WDP (their `.ini` files are put back afterwards, so the copy can be used again); a campaign save's
 * mission file gets the same keys, except a `target_n` that would zero a point of BMS's route there, and no route key
 * enters the ledger (D46; the file is put back); a TE template (a campaign start), the renamed copy, the read-only file, the twin, a file that is not there, a path,
 * an unknown theater and a pilot with no cartridge are refused with their sentences and their files are untouched; a
 * TE with no `.ini` gets none;
 * a real user TE of another theater (Hellas WCP's `SAT 001.tac`) and the real `Auto Save.tac`/`harrier.tac` cases
 * when the copy has them; and a root holding `Falcon BMS.exe` is refused by the route (not one byte changes) and, on
 * a direct call, by the TE writer itself. The settings are put back as they were.
 */
object TeSaveTest {
    private val client = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true; explicitNulls = false }
    private const val PILOT = "Tesave"
    private const val PILOT_NONE = "Tesave2"
    private const val SOURCE = "TE_BMS_03_F-16_DEAD"

    /** The edits a Planner save sends, in DtcSave's own spelling: six the TE's file takes, three it must not. */
    private val EDITS = listOf(
        // a route point moved
        CartridgeEdit("STPT", "target_4", "1100000.000000, 1300000.000000, -0.000000, 0, Not set"),
        // a Recon target, with the two leading spaces BMS writes before a target's name
        CartridgeEdit("STPT", "target_15", "2074151.750000, 132257.500000, -85.029999, -1,  Pulandian Airbase (ZYPD) ZYPD Rwy Sec 20"),
        // a threat ring
        CartridgeEdit("STPT", "ppt_5", "1500000.000000, 1150000.000000, 0.000000, 72913.390625, SA3"),
        // a line
        CartridgeEdit("STPT", "lineSTPT_0", "1400000.000000, 1200000.000000, 0.000000"),
        CartridgeEdit("STPT", "lineSTPT_1", "1420000.000000, 1230000.000000, 0.000000"),
        // a weapon target
        CartridgeEdit("STPT", "wpntarget_0", "1450000.000000, 1180000.000000, 0.000000, -1, Bridge span"),
        // the cartridge's alone
        CartridgeEdit("Radio", "UHF_1", "251000"),
        CartridgeEdit("COMMS", "Comm1", "3"),
        CartridgeEdit("STPT", "wpntarget_99", null),
    )
    private val TE_EDITED = EDITS.filter { it.value != null && it.section == "STPT" }.map { it.key }.toSet()

    fun run(campaign: File, more: List<String>): String = buildString {
        var fails = 0
        fun check(name: String, ok: Boolean, detail: Any? = null) {
            appendLine((if (ok) "PASS " else "FAIL ") + name + (detail?.let { "  | $it" } ?: ""))
            if (!ok) fails++
        }

        // ------------------------------------------------------------ 0. where, and only a copy
        val root = rootOf(campaign)
        if (root == null) {
            appendLine("FAIL no BMS folder above ${campaign.path}: give a copy of a BMS folder's Data/Campaign (with Data/TerrData/TheaterDefinition above it).")
            return@buildString
        }
        DevGuard.why(root)?.let {
            appendLine("FAIL REFUSED: $it. Point this at a copy.")
            return@buildString
        }
        val set = Theaters.at(root)
        val theater = set.owning(campaign) ?: set.all.firstOrNull()
        val dir = set.campaignDir(theater)
        appendLine("BMS folder (a copy): ${root.path}")
        appendLine("theater: ${theater?.name}  campaign folder: ${dir?.path}")
        check("the developer guard is on in a developer check", DevGuard.on, "${DevGuard.PROPERTY}=${System.getProperty(DevGuard.PROPERTY)}")
        if (theater == null || dir == null) {
            appendLine("FAIL no theater owns ${campaign.path}")
            return@buildString
        }
        val tName = theater.name
        val srcTac = file(dir, "$SOURCE.tac")
        val srcIni = file(dir, "$SOURCE.ini")
        if (srcTac == null || srcIni == null) {
            appendLine("FAIL $SOURCE.tac and .ini are not in ${dir.path}: the check makes its user TEs from them.")
            return@buildString
        }
        val config = Theaters.resolveDir(root, "User\\Config") ?: File(root, "User\\Config").also { it.mkdirs() }

        // ------------------------------------------------------------ 1. the fixture, inside the copy
        appendLine()
        appendLine("== 1. the copy's user TEs and test pilots")
        clearReadOnly(dir)
        val made = listOf("My DEAD", "Renamed DEAD", "ReadOnly DEAD", "NoIni DEAD", "Twin DEAD", "Copied Template")
        for (n in made) listOf("tac", "ini", "cam").forEach { e -> file(dir, "$n.$e")?.delete() }
        val names = CampaignArchive.names(set, theater)
        val srcSave = CampaignArchive.read(srcTac, names.takeIf { it.error == null })
        for (n in listOf("My DEAD", "ReadOnly DEAD", "NoIni DEAD", "Twin DEAD")) {
            val err = savedAs(srcTac, File(dir, "$n.tac"), SOURCE, n)
            val back = CampaignArchive.read(File(dir, "$n.tac"), names.takeIf { it.error == null })
            check(
                "$n.tac made as BMS's SAVE would name it: its header says \"${back.header?.saveFile}\", ${back.flights.size} flights " +
                    "(the original ${srcSave.flights.size}), every unit read",
                err == null && back.header?.saveFile == n && back.flights.size == srcSave.flights.size && back.flights.isNotEmpty() &&
                    back.exact && back.errors.isEmpty(),
                err ?: back.error,
            )
        }
        srcTac.copyTo(File(dir, "Renamed DEAD.tac"), overwrite = true)
        val template = file(dir, "Te_New_Nt.tac") ?: file(dir, "Te_New.tac")
        template?.copyTo(File(dir, "Copied Template.tac"), overwrite = true)
        val iniText = srcIni.readText(Charsets.ISO_8859_1)
        template?.let { File(dir, "Copied Template.ini").writeText(iniText, Charsets.ISO_8859_1) }
        for (n in listOf("My DEAD", "ReadOnly DEAD", "Twin DEAD")) {
            File(dir, "$n.ini").writeText(iniText.replaceFirst(Regex("(?m)^title=.*$"), "title=$n"), Charsets.ISO_8859_1)
        }
        srcIni.copyTo(File(dir, "Renamed DEAD.ini"), overwrite = true)
        val twinCam = File(dir, "Twin DEAD.cam")
        srcTac.copyTo(twinCam, overwrite = true)
        twinCam.setLastModified(File(dir, "Twin DEAD.tac").lastModified() + 60_000)
        File(dir, "ReadOnly DEAD.ini").setReadOnly()
        check("ReadOnly DEAD.ini is read-only", !File(dir, "ReadOnly DEAD.ini").canWrite())

        val pilotSource = config.listFiles()?.filter {
            it.isFile && it.name.endsWith(".ini", true) && !it.name.endsWith("_Def.ini", true) &&
                !it.nameWithoutExtension.equals(PILOT, true) && !it.nameWithoutExtension.equals(PILOT_NONE, true) &&
                runCatching { Regex("(?im)^\\s*\\[STPT]").containsMatchIn(it.readText(Charsets.ISO_8859_1)) }.getOrDefault(false)
        }?.maxByOrNull { it.length() }
        val cartridgeText = pilotSource?.readText(Charsets.ISO_8859_1)
            ?: "[STPT]\r\ntarget_0=0.000000, 0.000000, 0.000000, -1, Not set\r\nwpntarget_99=0.000000, 0.000000, 0.000000, -1, \r\n[Radio]\r\nUHF_1=225000\r\n[COMMS]\r\nComm1=1\r\n"
        appendLine("     test pilot cartridge: $PILOT.ini (and $PILOT_NONE, who has none), " +
            (if (pilotSource != null) "a copy of the pilot's own cartridge in the copy's User\\Config" else "made up (no pilot cartridge in the copy)"))
        File(config, "$PILOT.ini").writeText(cartridgeText, Charsets.ISO_8859_1)
        file(config, "$PILOT_NONE.ini")?.delete()
        // nothing may appear in User/Config: no backup folder, no README, no record
        fun configNames() = config.walkTopDown().filter { it != config }.map { it.relativeTo(config).path.lowercase() }.sorted().toList()
        val configBefore = configNames()

        // what must not change: every file of the campaign folder but the ones a check writes on purpose
        val campaignBefore = hashes(dir)
        // the shipped TE's and training's mission files are written by the checks below, and put back at the end
        val shippedInis = listOfNotNull(srcIni, file(dir, "TR_BMS_01_GroundOPS.ini")).associateWith { it.readBytes() }

        // ------------------------------------------------------------ 2. through the route, in-process
        val before = Bridge.settings.value
        val wasRunning = Bridge.running
        val exeRoot = File(root.parentFile, root.name + "-exe-root")
        try {
            Bridge.update { it.copy(BmsDirOverride = root.path, AutoEzBoardsOnPrint = false) }
            Bridge.startForCheck()
            check("the bridge reads the copy", Bridge.install.baseDir?.let { Theaters.canonical(File(it)) } == Theaters.canonical(root),
                Bridge.install.baseDir)

            fun call(method: String, path: String, query: Map<String, String> = emptyMap(), body: String = ""): ApiResponse? = try {
                Bridge.handle(ApiRequest(method, path, query, body.toByteArray(Charsets.UTF_8)))
            } catch (e: Throwable) {
                check("$method $path does not throw", false, "${e::class.java.simpleName}: ${e.message}"); null
            }
            fun text(r: ApiResponse?) = r?.body?.toString(Charsets.UTF_8).orEmpty()
            fun state(r: ApiResponse?) = runCatching { client.decodeFromString(CartridgeState.serializer(), text(r)) }.getOrNull()
            fun error(r: ApiResponse?) = runCatching { ((client.parseToJsonElement(text(r)) as JsonObject)["error"] as? JsonPrimitive)?.content }.getOrNull()
            fun body(edits: List<CartridgeEdit>) = Bridge.json.encodeToString(ListSerializer(CartridgeEdit.serializer()), edits)
            fun save(te: String?, edits: List<CartridgeEdit> = EDITS, pilot: String = PILOT): Pair<ApiResponse?, CartridgeState?> {
                val q = buildMap { put("callsign", pilot); te?.let { put("te", it) } }
                val r = call("POST", "/api/cartridge/save", q, body(edits))
                return r to state(r)
            }


            // -------------------------------------------------------- 3. the user TE: exactly the edited keys
            appendLine()
            appendLine("== 2. Save to DTC with the user TE \"My DEAD\" open")
            val myIni = File(dir, "My DEAD.ini")
            val cart = File(config, "$PILOT.ini")
            val iniBefore = myIni.readBytes()
            val cartBefore = cart.readText(Charsets.ISO_8859_1)
            val (r1, s1) = save("$tName|My DEAD.tac")
            val m1 = s1?.mission
            check("the route answers 200 with the cartridge saved", r1?.status == 200 && s1?.error == null, s1?.message ?: s1?.error ?: error(r1))
            check("mission: My DEAD.ini written", m1?.written == true && m1.file == "My DEAD.ini", m1?.let { "${it.file} written=${it.written} ${it.reason ?: ""}" })
            val iniAfter = myIni.readBytes()
            val diff = lineDiff(iniBefore, iniAfter)
            appendLine("     diff My DEAD.ini (${iniBefore.size} → ${iniAfter.size} bytes; '-' before, '+' after; every other line byte-identical):")
            diff.forEach { (old, new) ->
                appendLine("       - ${old?.trimEnd('\r', '\n') ?: "(none)"}")
                appendLine("       + ${new?.trimEnd('\r', '\n') ?: "(none)"}")
            }
            val changedKeys = diff.mapNotNull { (old, new) -> (new ?: old)?.substringBefore('=')?.trim() }.toSet()
            check("only the planned [STPT] lines changed: ${TE_EDITED.sorted().joinToString()}", changedKeys == TE_EDITED,
                "changed: ${changedKeys.sorted().joinToString()}")
            check("the same number of lines, each edit replaced in place (no line added or lost)",
                splitLines(iniBefore).size == splitLines(iniAfter).size && diff.all { it.first != null && it.second != null })
            check("every other byte is identical (the file equals the original with only those lines swapped)",
                String(iniAfter, Charsets.ISO_8859_1) == swapLines(iniBefore, diff))
            val afterText = String(iniAfter, Charsets.ISO_8859_1)
            check("each written line is key=value exactly as the Planner sent it",
                EDITS.filter { it.key in TE_EDITED }.all { e -> afterText.contains("\r\n${e.key}=${e.value}\r\n") })
            check("BMS's two leading spaces before the target's name survive", afterText.contains(", -1,  Pulandian Airbase (ZYPD)"))
            check("the cartridge's own edits stay out of the TE's file ([Radio] UHF_1, [COMMS] Comm1)",
                !afterText.contains("[Radio]") && !afterText.contains("UHF_1") && !afterText.contains("Comm1"))
            check("a removal is not carried over (wpntarget_99 still in the TE's file)", afterText.contains("\r\nwpntarget_99="))
            check("the [MISSION] title line is untouched", afterText.startsWith("[MISSION]\r\ntitle=My DEAD\r\n"))
            val cartAfter = cart.readText(Charsets.ISO_8859_1)
            check("the cartridge got every edit, its own ones included",
                cartAfter.contains("UHF_1=251000") && cartAfter.contains("Comm1=3") && cartAfter.contains("target_15=2074151.750000") &&
                    (!cartBefore.contains("wpntarget_99=") || !cartAfter.contains("wpntarget_99=")))
            check("the first save went through at once (no backup step), and nothing was added to User/Config",
                configNames() == configBefore, (configNames() - configBefore.toSet()).joinToString())
            check("no temporary file left beside it", dir.listFiles()?.none { it.name.endsWith(".bmsc-new", true) } == true)

            val (_, s2) = save("$tName|My DEAD.tac")
            check("saving the same edits again changes nothing (edits never compound)",
                s2?.mission?.written == false && s2.mission?.reason?.contains("already held") == true && myIni.readBytes().contentEquals(iniAfter),
                s2?.mission?.reason)
            val (_, s3) = save("${tName.lowercase()}|my dead.TAC")
            check("the theater and the file are found without regard to case", s3?.mission?.file == "My DEAD.ini" &&
                s3.mission?.reason?.contains("already held") == true, s3?.mission?.reason)
            val (_, s4) = save("$tName|My DEAD.tac", listOf(CartridgeEdit("Radio", "UHF_2", "252000")))
            check("only non-steerpoint edits: the TE's file is left as it is, with the reason",
                s4?.mission?.written == false && s4.mission?.reason?.contains("None of the changes") == true && myIni.readBytes().contentEquals(iniAfter),
                s4?.mission?.reason)

            // -------------------------------------------------------- 4. refused, and untouched
            appendLine()
            appendLine("== 3. refused with a sentence, the file untouched, the cartridge still saved")
            fun refused(label: String, te: String, words: String, watch: File?, pilot: String = PILOT, cartridgeSaved: Boolean = true) {
                val h0 = watch?.takeIf { it.isFile }?.let(::sha)
                val existed = watch?.exists()
                val (r, s) = save(te, pilot = pilot)
                val m = s?.mission
                val untouched = watch == null || (watch.exists() == existed && (h0 == null || sha(watch) == h0))
                check(
                    "$label: refused (\"$words\"), " + (watch?.let { "${it.name} untouched" } ?: "nothing written") + if (cartridgeSaved) ", cartridge saved" else "",
                    r?.status == 200 && m != null && !m.written && m.reason?.contains(words) == true && untouched &&
                        (if (cartridgeSaved) s?.error == null else s?.error != null),
                    m?.let { "${it.file}: ${it.reason}" } ?: (s?.error ?: error(r)),
                )
            }
            (file(dir, "Te_New_Nt.tac") ?: file(dir, "Te_New.tac"))?.let { refused("a TE template", "$tName|${it.name}", "template for a new TE", File(dir, it.nameWithoutExtension + ".ini")) }
            file(dir, "Copied Template.tac")?.let {
                refused("a template copied under another name (a start by its structure: an objectives part)", "$tName|${it.name}",
                    "Copied Template is a start", File(dir, "Copied Template.ini"))
            }
            val cam = dir.listFiles()?.firstOrNull { it.name.startsWith("Save-Day", true) && it.name.endsWith(".cam", true) }
                ?: file(dir, "Auto Save.cam")
            // a campaign save (D46): BMS's DTC LOAD takes targets, lines and PPTs from its mission file, so the edited
            // keys go there too — but never one that would take away a point of BMS's route; the file is put back after
            val camIni = cam?.let { file(dir, it.nameWithoutExtension + ".ini") }
            if (cam == null || camIni == null) appendLine("     (no campaign save with a mission file in the copy: the campaign case is not shown)")
            else {
                val b0 = camIni.readBytes()
                val camTime = camIni.lastModified()
                val held0 = Leftovers.keys(String(b0, Charsets.ISO_8859_1))
                val placed = (0..23).map { "target_$it" }.firstOrNull { k ->
                    k !in TE_EDITED && held0["STPT\u0000${k.uppercase()}"]?.let { !Leftovers.isEmpty(Leftovers.STEERPOINT, it) } == true
                }
                val zero = placed?.let { CartridgeEdit("STPT", it, "0.000000, 0.000000, 0.000000, -1, Not set") }
                try {
                    val (r, s) = save("$tName|${cam.name}", EDITS + listOfNotNull(zero))
                    val m = s?.mission
                    val b1 = camIni.readBytes()
                    val d = lineDiff(b0, b1)
                    val keys = d.mapNotNull { (o, n) -> (n ?: o)?.substringBefore('=')?.trim() }.toSet()
                    check("a campaign save (${cam.name}): ${camIni.name} written with exactly the planned keys, every other byte identical",
                        r?.status == 200 && s?.error == null && m?.written == true && keys == TE_EDITED && String(b1, Charsets.ISO_8859_1) == swapLines(b0, d),
                        m?.let { "${it.file} written=${it.written} ${it.reason ?: ""}; changed: ${keys.sorted().joinToString()}" } ?: (s?.error ?: error(r)))
                    if (zero != null) {
                        check("  … BMS's route point ${zero.key} it places is not zeroed there (the cartridge still gets the edit)",
                            zero.key !in keys && File(config, "$PILOT.ini").readText(Charsets.ISO_8859_1).contains("\r\n${zero.key}=0.000000, 0.000000, 0.000000, -1, Not set"))
                    } else appendLine("     (the mission file places none of target_0-23 outside the edits: the route guard is not shown)")
                    val led = CartridgeStore(Bridge.install).ledger(PILOT).writes.filter { it.file.equals(camIni.name, true) }.map { it.key }.toSet()
                    check("  … its ledger rows are the lines, PPTs and weapon targets, never a route key",
                        led.isNotEmpty() && led.none { it.startsWith("target_", true) } && led == TE_EDITED.filterNot { it.startsWith("target_", true) }.toSet(),
                        led.sorted().joinToString())
                } finally {
                    val t0 = camTime
                    camIni.writeBytes(b0)
                    camIni.setLastModified(t0)
                }
            }
            refused("a copy renamed in Explorer", "$tName|Renamed DEAD.tac", "renamed since", File(dir, "Renamed DEAD.ini"))
            refused("a read-only mission file", "$tName|ReadOnly DEAD.tac", "is read-only", File(dir, "ReadOnly DEAD.ini"))
            refused("a later save of the same name owns the .ini", "$tName|Twin DEAD.tac", "also the mission file of Twin DEAD.cam", File(dir, "Twin DEAD.ini"))
            file(dir, "Auto Save.tac")?.let { tac ->
                val twin = file(dir, "Auto Save.cam")
                if (twin != null && twin.lastModified() > tac.lastModified()) {
                    refused("the real Auto Save.tac, whose Auto Save.ini the later Auto Save.cam now holds", "$tName|Auto Save.tac",
                        "also the mission file of Auto Save.cam", File(dir, "Auto Save.ini"))
                }
            }
            refused("a file that is not there", "$tName|Nope.tac", "There is no Nope.tac", null)
            refused("a path instead of a name", "$tName|..\\Campaign\\My DEAD.tac", "is not the name of a file", myIni)
            refused("an unknown theater", "Atlantis|My DEAD.tac", "no theater called \"Atlantis\"", myIni)
            refused("a pilot with no cartridge", "$tName|My DEAD.tac", "your cartridge was not saved either", myIni,
                pilot = PILOT_NONE, cartridgeSaved = false)
            val (_, noTe) = save(null)
            check("no te= at all: the cartridge alone, mission null (the old route unchanged)", noTe?.error == null && noTe?.mission == null, noTe?.message)

            appendLine()
            appendLine("== 3b. the TE and the training that ship with BMS: written like the pilot's own, as in WDP")
            val shippedWritten = ArrayList<String>()
            fun shipped(label: String, te: String, ini: File?) {
                val b0 = ini?.takeIf { it.isFile }?.readBytes()
                val (r, s) = save(te)
                val m = s?.mission
                val b1 = ini?.takeIf { it.isFile }?.readBytes()
                val d = if (b0 != null && b1 != null) lineDiff(b0, b1) else emptyList()
                if (m?.written == true && ini != null) shippedWritten += ini.name
                val exact = b0 != null && b1 != null && d.mapNotNull { (o, n) -> (n ?: o)?.substringBefore('=')?.trim() }.toSet() == TE_EDITED &&
                    String(b1, Charsets.ISO_8859_1) == swapLines(b0, d)
                val untouched = b0 == null || b1 == null || b0.contentEquals(b1)
                check(
                    "$label: never refused for shipping with BMS; " +
                        (if (m?.written == true) "${ini?.name} written with exactly the planned keys" else "not written: ${m?.reason}"),
                    r?.status == 200 && s?.error == null && m != null && m.reason?.contains("ships with Falcon BMS") != true &&
                        (if (m.written) exact else untouched),
                    m?.let { "${it.file} written=${it.written} ${it.reason ?: ""}; ${d.size} lines changed" } ?: (s?.error ?: error(r)),
                )
            }
            shipped("$SOURCE.tac (a TE that ships with BMS)", "$tName|$SOURCE.tac", srcIni)
            check("  … it was written (its header names it, and it has an .ini with [STPT])", srcIni.name in shippedWritten)
            file(dir, "TR_BMS_01_GroundOPS.trn")?.let { shipped("${it.name} (a training that ships with BMS)", "$tName|${it.name}", file(dir, "TR_BMS_01_GroundOPS.ini")) }
                ?: appendLine("     (no TR_BMS_01_GroundOPS.trn in the copy: the shipped training case is not shown)")

            appendLine()
            appendLine("== 4. a TE with no mission file of its own")
            for (n in listOfNotNull("NoIni DEAD.tac", file(dir, "harrier.tac")?.name)) {
                val base = n.substringBeforeLast('.')
                val (r, s) = save("$tName|$n")
                val m = s?.mission
                check("$n: nothing to write, and no $base.ini created", r?.status == 200 && m?.written == false &&
                    m.reason?.contains("no mission file of its own") == true && file(dir, "$base.ini") == null, m?.reason)
            }

            // -------------------------------------------------------- 5. another theater's own TE
            appendLine()
            appendLine("== 5. a real user TE of another theater")
            val hellas = set.byName("Hellas WCP")
            val sat = set.campaignDir(hellas)?.let { file(it, "SAT 001.tac") }
            val satIni = set.campaignDir(hellas)?.let { file(it, "SAT 001.ini") }
            if (hellas == null || sat == null || satIni == null) appendLine("     (Hellas WCP's SAT 001.tac/.ini are not in the copy: not shown)")
            else {
                val b0 = satIni.readBytes()
                val (_, s) = save("${hellas.name}|SAT 001.tac")
                val b1 = satIni.readBytes()
                val d = lineDiff(b0, b1)
                check("SAT 001.ini (Hellas WCP) written with exactly the planned keys, every other byte identical",
                    s?.mission?.written == true && d.mapNotNull { (o, n) -> (n ?: o)?.substringBefore('=')?.trim() }.toSet() == TE_EDITED &&
                        String(b1, Charsets.ISO_8859_1) == swapLines(b0, d),
                    s?.mission?.let { "${it.file} written=${it.written} ${it.reason ?: ""}; ${d.size} lines changed" })
            }

            // -------------------------------------------------------- 6. nothing else in the folder moved
            appendLine()
            appendLine("== 6. the rest of the campaign folder")
            val campaignAfter = hashes(dir)
            val changed = (campaignBefore.keys + campaignAfter.keys).filter { campaignBefore[it] != campaignAfter[it] }.sorted()
            val expected = (listOf("My DEAD.ini") + shippedWritten).map { it.lowercase() }.sorted()
            check("in ${dir.name}, only My DEAD.ini and the shipped missions' written .ini files changed", changed.map { it.lowercase() } == expected,
                changed.joinToString())

            // -------------------------------------------------------- 7. a root holding Falcon BMS.exe
            appendLine()
            appendLine("== 7. a root that holds Falcon BMS.exe")
            clearReadOnly(exeRoot)
            if (exeRoot.exists()) exeRoot.deleteRecursively()
            val exeCampaign = File(exeRoot, "Data\\" + dir.relativeTo(set.data).path).also { it.mkdirs() }
            File(root, "Data\\TerrData\\TheaterDefinition").copyRecursively(File(exeRoot, "Data\\TerrData\\TheaterDefinition"), overwrite = true)
            listOf("My DEAD.tac", "My DEAD.ini", "Strings.txt").forEach { n -> file(dir, n)?.copyTo(File(exeCampaign, n), overwrite = true) }
            config.copyRecursively(File(exeRoot, "User\\Config"), overwrite = true)
            File(exeRoot, "Falcon BMS.exe").writeBytes(ByteArray(0))
            val snap0 = hashes(exeRoot, deep = true)
            Bridge.update { it.copy(BmsDirOverride = exeRoot.path) }
            check("the bridge now reads the scratch root", Bridge.install.baseDir?.let { Theaters.canonical(File(it)) } == Theaters.canonical(exeRoot))
            val (rx, _) = save("$tName|My DEAD.tac")
            check("the route refuses it: 409, \"${DevGuard.REFUSAL} …\"", rx?.status == 409 && error(rx)?.startsWith(DevGuard.REFUSAL) == true,
                "${rx?.status} ${error(rx)}")
            check("not one file of that root changed or appeared", hashes(exeRoot, deep = true) == snap0, "${snap0.size} files")
            // the store called directly, past the route: the cartridge is not the TE writer's to guard, the TE's file is
            val exeIni = File(exeCampaign, "My DEAD.ini")
            val exeIniHash = sha(exeIni)
            val direct = CartridgeStore(Bridge.install).saveTe(PILOT, EDITS, com.bmscompanion.app.data.mission.CampRef(tName, "My DEAD.tac"))
            check("called directly, the TE writer refuses it too and leaves My DEAD.ini as it was",
                direct.mission?.written == false && direct.mission?.reason?.contains(DevGuard.REFUSAL) == true && sha(exeIni) == exeIniHash,
                direct.mission?.reason)
        } finally {
            Bridge.update { before }
            if (!wasRunning) Bridge.stop()
            clearReadOnly(dir)
            for ((f, b) in shippedInis) runCatching { if (!f.readBytes().contentEquals(b)) f.writeBytes(b) }
            clearReadOnly(exeRoot)
            if (exeRoot.exists()) exeRoot.deleteRecursively()
        }
        check("the settings are back as they were", Bridge.settings.value == before)

        appendLine()
        appendLine(if (fails == 0) "ALL PASS" else "FAIL: $fails check(s) failed")
    }

    // ---------------------------------------------------------------- the fixture

    /** The BMS folder [f] is in (or is): the first folder up from it with `Data/TerrData/TheaterDefinition/theater.lst`. */
    private fun rootOf(f: File): File? = generateSequence(f.absoluteFile) { it.parentFile }.firstOrNull { up ->
        Theaters.resolveFile(up, "Data\\TerrData\\TheaterDefinition\\theater.lst") != null
    }

    private fun file(dir: File, name: String): File? = dir.listFiles()?.firstOrNull { it.isFile && it.name.equals(name, ignoreCase = true) }

    /**
     * [src] as BMS's SAVE under the name [saveAs] leaves it: the campaign header's `SaveFile` (40 bytes at 2030 of the
     * unpacked `.cmp` part) says [saveAs], and every part is named `<saveAs>.<ext>`. The `.cmp` part is packed again
     * with literals only (every flag bit 1, which the reader takes byte for byte) and put after the old parts, and the
     * directory is written anew after it; the other parts keep their bytes and places. Null when done, else why not.
     * Test fixture only: the program never writes a save.
     */
    private fun savedAs(src: File, dest: File, oldName: String, saveAs: String): String? = try {
        val b = src.readBytes()
        val dirAt = MissionArchive.int32(b, 0)
        val parts = MissionArchive.parts(b)
        val cmp = parts.firstOrNull { it.name.endsWith(".cmp", true) } ?: error("no .cmp part")
        val raw = b.copyOfRange(cmp.at, cmp.at + cmp.length)
        val body = MissionArchive.unpack(raw, 8, MissionArchive.int32(raw, 4)) ?: error("the .cmp part does not unpack")
        val was = String(body, 2030, 40, Charsets.ISO_8859_1).substringBefore('\u0000')
        require(was.equals(oldName, true)) { "SaveFile at 2030 is \"$was\", not \"$oldName\"" }
        val name = saveAs.toByteArray(Charsets.ISO_8859_1)
        require(name.size < 40) { "name too long" }
        for (i in 0 until 40) body[2030 + i] = if (i < name.size) name[i] else 0
        val packed = ByteArrayOutputStream()
        var i = 0
        while (i < body.size) {
            val n = minOf(8, body.size - i)
            packed.write((1 shl n) - 1)
            packed.write(body, i, n)
            i += n
        }
        val cmpNew = ByteArrayOutputStream().apply {
            le32(this, 4 + packed.size()); le32(this, body.size); write(packed.toByteArray())
        }.toByteArray()
        val out = ByteArrayOutputStream()
        out.write(b, 0, dirAt)
        out.write(cmpNew)
        val newDir = dirAt + cmpNew.size
        le32(out, parts.size)
        for (p in parts) {
            val ext = p.name.substringAfterLast('.', "")
            val nm = "$saveAs.$ext".toByteArray(Charsets.US_ASCII)
            out.write(nm.size)
            out.write(nm)
            if (p === cmp) { le32(out, dirAt); le32(out, cmpNew.size) } else { le32(out, p.at); le32(out, p.length) }
        }
        val bytes = out.toByteArray()
        bytes[0] = (newDir and 0xFF).toByte(); bytes[1] = ((newDir shr 8) and 0xFF).toByte()
        bytes[2] = ((newDir shr 16) and 0xFF).toByte(); bytes[3] = ((newDir shr 24) and 0xFF).toByte()
        dest.writeBytes(bytes)
        null
    } catch (e: Throwable) {
        e.message ?: e::class.java.simpleName
    }

    private fun le32(o: ByteArrayOutputStream, v: Int) {
        o.write(v and 0xFF); o.write((v shr 8) and 0xFF); o.write((v shr 16) and 0xFF); o.write((v shr 24) and 0xFF)
    }

    private fun clearReadOnly(dir: File) {
        if (!dir.exists()) return
        dir.walkTopDown().forEach { if (it.isFile && !it.canWrite()) it.setWritable(true) }
    }

    // ---------------------------------------------------------------- comparing

    /** The file's lines, each with its own line ending (so joining them gives the bytes back). */
    private fun splitLines(b: ByteArray): List<String> {
        val t = String(b, Charsets.ISO_8859_1)
        val out = ArrayList<String>()
        var s = 0
        for (i in t.indices) if (t[i] == '\n') { out += t.substring(s, i + 1); s = i + 1 }
        if (s < t.length) out += t.substring(s)
        return out
    }

    /** Line by line, the pairs (before, after) that differ; null where one file has fewer lines. */
    private fun lineDiff(a: ByteArray, b: ByteArray): List<Pair<String?, String?>> {
        val x = splitLines(a)
        val y = splitLines(b)
        return (0 until maxOf(x.size, y.size)).mapNotNull { i ->
            val p = x.getOrNull(i)
            val q = y.getOrNull(i)
            if (p == q) null else p to q
        }
    }

    /** [a] with each line of [diff] swapped for its new text: equal to the new file only if nothing else moved. */
    private fun swapLines(a: ByteArray, diff: List<Pair<String?, String?>>): String {
        val swap = diff.filter { it.first != null }.associate { it.first!! to it.second.orEmpty() }
        return splitLines(a).joinToString("") { swap[it] ?: it }
    }

    private fun sha(f: File): String = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }

    /** name → SHA-256 of the files directly in [dir] (or every file below it, by relative path, with [deep]). */
    private fun hashes(dir: File, deep: Boolean = false): Map<String, String> =
        (if (deep) dir.walkTopDown().filter { it.isFile }.toList() else dir.listFiles()?.filter { it.isFile }.orEmpty())
            .associate { (if (deep) it.relativeTo(dir).path else it.name) to sha(it) }
}
