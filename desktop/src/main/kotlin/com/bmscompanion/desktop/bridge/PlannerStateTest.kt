package com.bmscompanion.desktop.bridge

import com.bmscompanion.app.data.mission.PcAnswer
import com.bmscompanion.app.data.mission.PlannerStateAll
import com.bmscompanion.app.data.mission.PlannerStatePost
import com.bmscompanion.app.data.mission.PlannerSyncClient
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * `--plannerstatetest out.txt` (1.4.2): planning made on one device is taken by another through the PC
 * (`/api/planner/state`, [PlannerStateStore], `PlannerSyncClient`). Three clients in this process — "PC window",
 * "tablet", "phone" — each with its own memory, talk to the bridge in-process (Bridge.handle, as the PC window does);
 * the store is kept in a scratch folder. Writes nothing else.
 */
object PlannerStateTest {
    private class Dev(val label: String, initial: String?) {
        var value: String? = initial
        var meta: String? = null
        val aside = HashMap<String, String?>()
        val client = PlannerSyncClient(
            metaLoad = { meta }, metaSave = { meta = it },
            asideLoad = { aside[it] }, asideSave = { n, v -> aside[n] = v },
            get = { call("GET", null) },
            post = { call("POST", Bridge.json.encodeToString(PlannerStatePost.serializer(), it)) },
            by = label,
        ).also { c -> c.register(NAME, PlannerSyncClient.Part({ value }) { v -> value = v; true }) }

        fun edit(v: String, strong: Boolean = true) { value = v; client.changed(NAME, strong) }
    }

    private const val NAME = "kneeboard.plan"

    private fun call(method: String, body: String?): PcAnswer<PlannerStateAll> {
        val r = Bridge.handle(ApiRequest(method, "/api/planner/state", body = body?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)))
        val a = runCatching { Bridge.json.decodeFromString(PlannerStateAll.serializer(), r.body.decodeToString()) }.getOrNull()
        return PcAnswer(a, if (r.status == 200) null else a?.error ?: "status ${r.status}", r.status)
    }

    fun run(): String {
        val out = StringBuilder()
        var fails = 0
        fun ok(c: Boolean, what: String) { if (!c) fails++; out.appendLine((if (c) "PASS " else "FAIL ") + what) }
        val dir = File(System.getProperty("java.io.tmpdir"), "bmsc-plannerstate-" + System.nanoTime()).apply { mkdirs() }
        Bridge.startForCheck()
        PlannerStateStore.folderOverride = dir
        PlannerStateStore.reset()
        try {
            runBlocking {
                val pc = Dev("PC", "plan made on the PC")
                val tablet = Dev("Android", "tablet's own default")
                ok(pc.client.sync(listOf(NAME)), "the PC window syncs with the store (in-process)")
                ok(call("GET", null).value?.entries?.singleOrNull()?.value == "plan made on the PC", "an empty store takes the first device's plan (weak)")

                // the tablet links: its default (never changed by the pilot) gives way to the PC's plan
                tablet.edit("tablet's own default", strong = false)
                tablet.client.sync(listOf(NAME))
                ok(tablet.value == "plan made on the PC", "the tablet adopts the plan made on the PC over its own default: '${tablet.value}'")
                ok(tablet.client.notice == null, "no notice: the tablet's default was not the pilot's")

                // the pilot changes it on the PC; the tablet comes to Upd Kneeboard again
                pc.edit("PC plan, second change"); pc.client.push(NAME)
                tablet.client.sync(listOf(NAME))
                ok(tablet.value == "PC plan, second change", "a later change on the PC reaches the tablet")

                // the tablet changes it (posted): the PC window takes it when it next comes to the page
                tablet.edit("tablet's change"); tablet.client.push(NAME)
                pc.client.sync(listOf(NAME))
                ok(pc.value == "tablet's change", "a change on the tablet reaches the PC window")

                // a conflict: the tablet changes it while away, then the PC changes it later; the later one wins and the
                // tablet's is kept aside, with a notice
                tablet.edit("tablet, made offline")
                Thread.sleep(60)
                pc.edit("PC, made after"); pc.client.push(NAME)
                tablet.client.sync(listOf(NAME))
                ok(tablet.value == "PC, made after", "the later change (by the PC's clock) wins: '${tablet.value}'")
                ok(tablet.client.aside(NAME) == "tablet, made offline" && tablet.client.notice != null, "the tablet's own is kept aside and said: ${tablet.client.notice}")
                ok(tablet.client.useAside(NAME) && tablet.value == "tablet, made offline", "Use this device's plan puts it back")
                pc.client.sync(listOf(NAME))
                ok(pc.value == "tablet, made offline", "…and on the PC for every device")

                // the other way: the PC changes, then the tablet changes later while away: the tablet's wins on its sync
                pc.edit("PC, made first"); pc.client.push(NAME)
                Thread.sleep(60)
                tablet.edit("tablet, made later offline")
                tablet.client.sync(listOf(NAME))
                ok(tablet.value == "tablet, made later offline" && call("GET", null).value?.entries?.firstOrNull()?.value == "tablet, made later offline",
                    "a newer change made away from the PC is kept and posted, not lost")

                // a fresh phone: its weak default never replaces what is there
                val phone = Dev("Browser", "phone default")
                phone.edit("phone default", strong = false)
                phone.client.sync(listOf(NAME))
                ok(phone.value == "tablet, made later offline", "a new device's own default never replaces the PC's plan: '${phone.value}'")

                // the tablet restarts (or the browser reloads): a piece kept only while the app runs (the Planner's flight)
                // is back on its default, and the PC's — the very one it last had, so not newer — is taken again
                val restarted = Dev("Android", "tablet's default after a restart").also { it.meta = tablet.meta }
                restarted.client.sync(listOf(NAME))
                ok(restarted.value == "tablet, made later offline", "after a restart the device takes the PC's plan again, though not newer than the one it last had: '${restarted.value}'")

                // kept on disk: read again from the file
                PlannerStateStore.reset()
                ok(call("GET", null).value?.entries?.firstOrNull()?.value == "tablet, made later offline" && File(dir, PlannerStateStore.FILE_NAME).isFile,
                    "kept in ${PlannerStateStore.FILE_NAME} and read back")
                val bad = Bridge.handle(ApiRequest("POST", "/api/planner/state", body = """{"name":"../x","value":"v"}""".toByteArray()))
                ok(bad.status == 400, "a name that is not a piece of planning is refused (${bad.status})")
            }
        } catch (e: Throwable) {
            ok(false, "threw: $e")
        } finally {
            PlannerStateStore.folderOverride = null
            PlannerStateStore.reset()
            dir.deleteRecursively()
        }
        return "planner state: ${if (fails == 0) "all passed" else "$fails FAIL"}\n$out"
    }
}
