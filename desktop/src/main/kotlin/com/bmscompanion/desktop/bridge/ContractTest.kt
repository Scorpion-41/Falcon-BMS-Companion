package com.bmscompanion.desktop.bridge

import com.bmscompanion.app.data.mission.BridgeInfo
import com.bmscompanion.app.data.mission.CartridgeEdit
import com.bmscompanion.app.data.mission.CartridgeState
import com.bmscompanion.app.data.mission.MissionData
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.security.MessageDigest

/**
 * `--contracttest out.txt <fixture copy> [more…]`: the round-3 contract end to end on this PC, in-process.
 *
 * 1. **JSON**: a `MissionData` as a 1.3.7 PC sends it (no `route`, no `plan`) and as a 1.3.8 PC will (both) decode
 *    with the clients' own settings.
 * 2. **Routes**: every Planner route (`/api/campaign/…`, `/api/plan…`, `/api/kbprint/…`) answers — JSON (or a picture),
 *    501 while it is a stub, never 404, never an exception; `/api/info` carries `planModified`/`routeModified`;
 *    `/api/mission` decodes; `/api/cartridge/save?te=` reaches the TE save.
 * 3. **The developer guard** (R3-PLAN A20): against a scratch root holding an empty `Falcon BMS.exe`, every writing
 *    route is refused and not one byte there changes; against the fixture copy, a cartridge save goes through (written
 *    directly, as WDP writes it) and lands in the copy; the registry's real install is refused.
 *
 * It writes only into [fixture] (which must be a copy: it refuses anything the guard would) and a scratch folder
 * beside it, and puts the settings back as it found them.
 */
object ContractTest {
    private val client = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true; explicitNulls = false }

    fun run(fixture: File, more: List<String>): String = buildString {
        var fails = 0
        fun check(name: String, ok: Boolean, detail: Any? = null) {
            appendLine((if (ok) "PASS " else "FAIL ") + name + (detail?.let { "  | $it" } ?: ""))
            if (!ok) fails++
        }

        appendLine("fixture copy: ${fixture.path}")
        DevGuard.why(fixture)?.let {
            appendLine("FAIL REFUSED: $it. Point this at a copy of the fixture.")
            return@buildString
        }
        check("the developer guard is on in a developer check", DevGuard.on, "${DevGuard.PROPERTY}=${System.getProperty(DevGuard.PROPERTY)}")

        // ------------------------------------------------------------ 1. JSON, old and new
        appendLine()
        appendLine("== 1. MissionData, old and new")
        runCatching {
            val m1 = client.decodeFromString(MissionData.serializer(), OLD_MISSION)
            check("a 1.3.7 MissionData decodes: route and plan null, old fields kept", m1.route == null && m1.plan == null &&
                m1.dtc?.ppts?.firstOrNull()?.rangeNm == 12.0 && m1.briefing?.overview?.flight == "Cyborg6", m1.version)
            val m2 = client.decodeFromString(MissionData.serializer(), NEW_MISSION)
            check("a 1.3.8 MissionData decodes: route and plan", m2.route?.steerpoints?.size == 1 && m2.plan?.present == true &&
                m2.plan?.applied == false && m2.plan?.flight?.briefing?.origin == "save" && m2.plan?.ref?.flight == "12/3",
                "route ${m2.route?.file}, plan ${m2.plan?.state}")
            val again = Bridge.json.encodeToString(MissionData.serializer(), m2)
            check("the PC's encoder and the clients' decoder agree (round trip equal)",
                client.decodeFromString(MissionData.serializer(), again) == m2, "${again.length} chars")
        }.onFailure { check("MissionData JSON", false, "threw ${it::class.java.simpleName}: ${it.message}") }

        // ------------------------------------------------------------ 2. the routes, in-process
        val before = Bridge.settings.value
        val wasRunning = Bridge.running
        try {
            Bridge.update { it.copy(BmsDirOverride = fixture.path, AutoEzBoardsOnPrint = false) }
            Bridge.startForCheck()
            appendLine()
            appendLine("== 2. routes (BMS folder: ${Bridge.install.baseDir})")
            check("the bridge reads the fixture copy", Bridge.install.baseDir?.let { Theaters.canonical(File(it)) } == Theaters.canonical(fixture),
                Bridge.install.baseDir)

            fun call(method: String, path: String, query: Map<String, String> = emptyMap(), body: String = ""): ApiResponse? = try {
                Bridge.handle(ApiRequest(method, path, query, body.toByteArray(Charsets.UTF_8)))
            } catch (e: Throwable) {
                check("$method $path does not throw", false, "${e::class.java.simpleName}: ${e.message}"); null
            }
            fun text(r: ApiResponse?) = r?.body?.toString(Charsets.UTF_8).orEmpty()
            fun jsonOk(r: ApiResponse?) = r != null && runCatching { client.parseToJsonElement(text(r)); true }.getOrDefault(false)
            fun error(r: ApiResponse?) = runCatching { ((client.parseToJsonElement(text(r)) as JsonObject)["error"] as? JsonPrimitive)?.content }.getOrNull()

            val info = call("GET", "/api/info")
            val infoText = text(info)
            check("/api/info carries briefing.planModified and briefing.routeModified",
                info?.status == 200 && infoText.contains("\"planModified\"") && infoText.contains("\"routeModified\""),
                runCatching { client.decodeFromString(BridgeInfo.serializer(), infoText).briefing.let { "plan ${it.planModified}, route ${it.routeModified}" } }.getOrNull())
            val mission = call("GET", "/api/mission")
            val md = runCatching { client.decodeFromString(MissionData.serializer(), text(mission)) }.getOrNull()
            check("/api/mission decodes (route ${md?.route?.let { "present" } ?: "null"}, plan ${md?.plan?.let { "present" } ?: "null"})",
                mission?.status == 200 && md != null, md?.version)

            val probes = mapOf(
                "GET /api/campaign/files" to mapOf("all" to "1"),
                "GET /api/campaign/ato" to mapOf("theater" to "Korea KTO", "file" to "Auto Save.cam"),
                "GET /api/campaign/atotargets" to mapOf("theater" to "Korea KTO", "file" to "Auto Save.cam"),
                "GET /api/campaign/flight" to mapOf("theater" to "Korea KTO", "file" to "Auto Save.cam", "flight" to "1/1"),
                "GET /api/campaign/mapintel" to mapOf("theater" to "Korea KTO", "file" to "Auto Save.cam", "team" to "1"),
                "GET /api/campaign/magvar" to mapOf("theater" to "Korea KTO"),
                "GET /api/kbprint/thumb" to mapOf("n" to "1", "side" to "L", "w" to "256"),
            )
            val routes = CampaignRoutes.ROUTES + PlanStore.ROUTES + KneeboardPrint.ROUTES + MissionSource.ROUTES
            for (route in routes) {
                val (method, path) = route.split(' ', limit = 2)
                // a POST gets an empty body: a built route refuses it in words (400), a stub says it is a stub (501)
                val r = call(method, path, probes[route].orEmpty())
                val picture = r?.contentType?.startsWith("image/") == true
                check(
                    "$route answers ${if (r?.status == 501) "501 (stub)" else r?.status.toString()} with ${if (picture) "a picture" else "JSON"}, not 404",
                    r != null && r.status != 404 && (picture || jsonOk(r)) && (r.status < 500 || r.status == 501),
                    error(r) ?: (if (picture) "${r?.body?.size} bytes" else text(r).take(120)),
                )
            }
            val notOurs = call("GET", "/api/planner")
            check("a path that only starts like /api/plan is not taken by the plan store", notOurs?.status == 404, notOurs?.status)

            // ------------------------------------------------------------ 3. the developer guard
            appendLine()
            appendLine("== 3. the developer guard")
            val cs = Bridge.install.callsign?.trim()?.takeIf { it.isNotEmpty() }
            val cfgDir = Theaters.resolveDir(fixture, "User\\Config")
            val cartridge = cs?.let { c -> cfgDir?.let { File(it, "$c.ini") } }?.takeIf { it.isFile }
            check("the fixture copy has the pilot's cartridge", cartridge != null, "User\\Config\\<callsign>.ini")
            val edit = listOf(CartridgeEdit("STPT", "target_23", "1000.000000, 2000.000000, 0.000000, -1, Contract check"))
            val editBody = Bridge.json.encodeToString(ListSerializer(CartridgeEdit.serializer()), edit)

            // a scratch root that looks like an install: an empty Falcon BMS.exe at its top
            val exeRoot = File(fixture.parentFile, fixture.name + "-exe-root")
            if (exeRoot.exists()) exeRoot.deleteRecursively()
            File(exeRoot, "User\\Config").mkdirs()
            cartridge?.copyTo(File(exeRoot, "User\\Config\\${cartridge.name}"), overwrite = true)
            File(exeRoot, "Falcon BMS.exe").writeBytes(ByteArray(0))
            val snap0 = hashes(exeRoot)
            Bridge.update { it.copy(BmsDirOverride = exeRoot.path) }
            check("the bridge now reads the scratch root", Bridge.install.baseDir?.let { Theaters.canonical(File(it)) } == Theaters.canonical(exeRoot))
            val writes = listOf(
                Triple("POST", "/api/cartridge/save", editBody),
                Triple("POST", "/api/weather/backup", ""), Triple("POST", "/api/weather/restore", ""),
                Triple("POST", "/api/cfg/backup", ""), Triple("POST", "/api/cfg/set", "1"), Triple("POST", "/api/rtt/enable", ""),
                Triple("POST", "/api/ezboards/generate", ""), Triple("POST", "/api/media/delete", "[\"x.png\"]"),
                Triple("POST", "/api/acmi/clear", ""), Triple("POST", "/api/kbprint/file", "{}"), Triple("POST", "/api/kbprint/shipped", ""),
            )
            for ((method, path, body) in writes) {
                val q = if (path == "/api/cfg/set") mapOf("kind" to "user", "key" to "g_bExportRTTTextures") else emptyMap()
                val r = call(method, path, q, body)
                check("refused against a root holding Falcon BMS.exe: $method $path", r?.status == 409 && error(r)?.startsWith(DevGuard.REFUSAL) == true,
                    "${r?.status} ${error(r)}")
            }
            val snap1 = hashes(exeRoot)
            check("not one file of the scratch root changed or appeared", snap0 == snap1, "${snap0.size} files before, ${snap1.size} after")
            val saveCs = runCatching { client.decodeFromString(CartridgeState.serializer(), text(call("POST", "/api/cartridge/save", body = editBody))) }.getOrNull()
            check("a client reads the refusal as the cartridge state's error", saveCs?.error?.startsWith(DevGuard.REFUSAL) == true, saveCs?.error)

            // the fixture copy: the same writes go through, into the copy
            Bridge.update { it.copy(BmsDirOverride = fixture.path) }
            val saved = call("POST", "/api/cartridge/save", body = editBody)
            val savedState = runCatching { client.decodeFromString(CartridgeState.serializer(), text(saved)) }.getOrNull()
            val onDisk = cartridge?.readText(Charsets.ISO_8859_1).orEmpty()
            check("allowed against the fixture copy: cartridge save (no backup first), and the edit is in the copy's file",
                saved?.status == 200 && savedState?.error == null && onDisk.contains("target_23=1000.000000, 2000.000000"),
                savedState?.message ?: savedState?.error)
            val te = call("POST", "/api/cartridge/save", mapOf("te" to "Korea KTO|TE_BMS_03_Airbase Attack.tac"), editBody)
            val teState = runCatching { client.decodeFromString(CartridgeState.serializer(), text(te)) }.getOrNull()
            check("/api/cartridge/save?te= reaches the TE save and answers CartridgeState.mission", te?.status == 200 && teState?.mission != null,
                teState?.mission?.let { "${it.file}: written=${it.written} ${it.reason}" })

            // the real install, by the registry: refused, without a single write being tried
            val real = DevGuard.registryBaseDirs()
            if (real.isEmpty()) appendLine("     (no Falcon BMS install in the registry on this PC: that case is not shown)")
            else check("the registry's real install is refused", real.all { DevGuard.refusal(it.path)?.startsWith(DevGuard.REFUSAL) == true },
                real.joinToString { DevGuard.why(it) ?: "allowed!" })
            check("a folder inside it is refused too", real.all { DevGuard.why(File(it, "User\\Config")) != null })

            exeRoot.deleteRecursively()
        } finally {
            Bridge.update { before }
            if (!wasRunning) Bridge.stop()
        }
        check("the settings are back as they were", Bridge.settings.value == before)

        appendLine()
        appendLine(if (fails == 0) "ALL PASS" else "FAIL: $fails check(s) failed")
    }

    private fun hashes(root: File): Map<String, String> {
        val md = MessageDigest.getInstance("SHA-256")
        return root.walkTopDown().filter { it.isFile }.associate { f ->
            f.relativeTo(root).path to md.digest(f.readBytes()).joinToString("") { "%02x".format(it) }
        }
    }

    /** A MissionData as a 1.3.7 PC sends it: no route, no plan, the old Dtc/DtcPpt shapes (B0a's contract check). */
    private const val OLD_MISSION = """
{"version":"1-2-0","briefingModified":1,"briefing":{"overview":{"flight":"Cyborg6","packageId":"7288"},"steerpoints":[{"n":1,"desc":"Takeoff"}]},
 "dtc":{"modified":2,"steerpoints":[{"n":15,"x":2074151.75,"y":132257.5,"altFt":85.0,"action":-1,"isTarget":true,"name":"Pulandian"}],
        "ppts":[{"n":56,"x":1.0,"y":2.0,"rangeNm":12.0,"name":"SA-3"}],"lines":[],"uhf":[{"ch":1,"freq":"292.30"}],"iff":{"m3":"1234"}},
 "tracks":[]}
"""

    /** A MissionData as a 1.3.8 PC will send it: route and plan, with a save's flight and its Briefing. */
    private const val NEW_MISSION = """
{"version":"1-2-0","briefingModified":1,"briefing":{"overview":{"flight":"Cyborg6"}},
 "dtc":{"ppts":[{"n":57,"x":1520374.5,"y":1119576.75,"rangeNm":0.0,"name":"AWACS","code":"AWC","rangeFt":0.1,"marker":true}],
        "lines":[{"n":31,"x":1.0,"y":2.0,"line":1}],"open":[{"n":81,"x":3.0,"y":4.0,"action":-1,"isTarget":true}],
        "comm":{"comm1":1,"comm2":2,"tacan":"94X","ils":"109.30","ilsCrs":95},"bingoLbs":3500,"ewsNames":["P1","P2"]},
 "route":{"file":"Auto Save.ini","save":"Auto Save.cam","kind":"campaign","modified":9,"steerpoints":[{"n":1,"x":1162752.9,"y":1539950.6,"altFt":42.0,"action":1}],"bullseyeX":2132000.0,"bullseyeY":1016000.0},
 "plan":{"id":1790000000000,"source":"planner","from":"Browser","theater":"korea-kto","callsign":"Jaguar2","packageId":"7301","state":"parked","note":"The printed briefing is for Cyborg6.",
         "ref":{"theater":"Korea KTO","file":"Auto Save.cam","flight":"12/3"},"seat":1,
         "flight":{"row":{"id":"12/3","number":7302,"callsign":"Jaguar2","mission":"Strike","aircraft":"F-16CM-52","count":2,"team":2,"f16":true},
                   "route":[{"n":1,"x":1.0,"y":2.0,"action":17,"desc":"Strike","designated":[null,{"kind":"feature","campId":44,"building":3}]}],
                   "briefing":{"origin":"save","overview":{"flight":"Jaguar2"}}},
         "dtc":{"steerpoints":[{"n":5,"x":1.0,"y":2.0}]},"notInJet":["STPT 5","PPT 57"],"canUndo":true}}
"""
}
