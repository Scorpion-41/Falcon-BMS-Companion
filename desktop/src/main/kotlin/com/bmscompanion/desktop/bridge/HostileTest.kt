package com.bmscompanion.desktop.bridge

import com.bmscompanion.app.data.mission.BridgeInfo
import com.bmscompanion.app.data.mission.Contact
import com.bmscompanion.app.data.mission.Contacts
import com.bmscompanion.app.data.mission.HostileContacts
import com.bmscompanion.app.data.mission.TacEvent
import com.bmscompanion.app.data.mission.TacviewStatus

/**
 * `--hostiletest out.txt`: hostile contacts from the live feed are off unless the pilot turns them on, and that is
 * enforced at the source ([HostileContacts], `BridgeSettings.ShowHostiles`).
 *
 * 1. [HostileContacts.strip]: own, friendly and neutral stay; hostile aircraft, air defences,
 *    ships, ground units, ejected crews and contacts of no known side go; only the own side's weapon events stay.
 * 2. Settings: a fresh file is off; step 3 of the migration sets an older file off once whatever it held, and leaves a
 *    pilot's "on" alone afterwards.
 * 3. The PC: `POST /api/contacts/hostiles?on=0|1` sets it, `/api/info` reports it, and `GET /api/contacts` (fed a
 *    made-up picture through [Bridge.feedForCheck]) carries no hostile contact while off and the hostile ones while on.
 * 4. A device: a picture is stripped again unless `/api/info` says on (an older PC says nothing: off).
 *
 * Writes only the settings file of the APPDATA it runs under (run it with a scratch APPDATA), and puts the setting back
 * as it found it. Reads nothing of BMS beyond what the bridge reads on start.
 */
object HostileTest {
    private fun c(id: String, kind: String = "air", own: Boolean = false, friendly: Boolean = false, neutral: Boolean = false, coalition: String? = "Enemy") =
        Contact(id = id, kind = kind, x = 1000.0 * id.length, y = 2000.0, own = own, friendly = friendly, neutral = neutral, coalition = coalition, name = id)

    private val picture = Contacts(
        t = 1, connected = true, state = "connected",
        contacts = listOf(
            c("me", own = true, coalition = "Us"), c("wing", friendly = true, coalition = "Us"), c("ally", friendly = true, coalition = "Ally"),
            c("neutralair", neutral = true, coalition = "Neutral"),
            c("bandit"), c("helo", kind = "heli"), c("boat", kind = "ship"), c("tank", kind = "ground"), c("chute", kind = "crew"),
            c("nobody", coalition = null), c("samsite", kind = "sam"), c("ownsam", kind = "sam", friendly = true, coalition = "Us"),
        ),
        events = listOf(TacEvent(1, "hit", "AIM-120C hit", mine = true), TacEvent(2, "hit", "R-77 hit", mine = false)),
    )

    fun run(): String = buildString {
        fun check(ok: Boolean, what: String) = appendLine((if (ok) "ok   " else "FAIL ") + what)
        appendLine("Hostile contacts check (BMS Companion ${com.bmscompanion.app.AppVersion.NAME})")

        appendLine(); appendLine("1. What leaves with the setting off")
        val s = HostileContacts.strip(picture)
        val ids = s.contacts.map { it.id }.toSet()
        appendLine("   kept: ${ids.sorted().joinToString(", ")}")
        check(ids == setOf("me", "wing", "ally", "neutralair", "ownsam"), "own, friendly, neutral and the own air defence stay; the rest go")
        check(s.contacts.none { it.hostile }, "no hostile contact, air defences included")
        check(s.events.map { it.id } == listOf(1L), "only the own side's weapon events stay")
        check(HostileContacts.filter(picture, on = true) == picture, "with it on, the picture is untouched")

        appendLine(); appendLine("2. The setting, and bringing a file forward")
        check(!BridgeSettings().ShowHostiles, "a fresh settings file has it off")
        check(BridgeSettings.CURRENT >= 3, "settings layout ${BridgeSettings.CURRENT} (3 = hostiles off)")
        val old = BridgeSettings.migrate(BridgeSettings(ShowHostiles = true, SettingsVersion = 2), save = false)
        check(!old.ShowHostiles && old.SettingsVersion == BridgeSettings.CURRENT, "a layout-2 file that had it on is set off once")
        val older = BridgeSettings.migrate(BridgeSettings(ShowHostiles = true, SettingsVersion = 0), save = false)
        check(!older.ShowHostiles, "a file from before the numbers is set off too")
        val chosen = BridgeSettings.migrate(BridgeSettings(ShowHostiles = true, SettingsVersion = BridgeSettings.CURRENT), save = false)
        check(chosen.ShowHostiles, "a pilot who turned it on after the step keeps it on")
        val noKey = runCatching {
            kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString(BridgeSettings.serializer(), """{"Port":47474,"SettingsVersion":2}""")
        }.getOrNull()
        check(noKey != null && !noKey.ShowHostiles, "a file without the key reads off")

        appendLine(); appendLine("3. The PC")
        val was = Bridge.settings.value.ShowHostiles
        val started = !Bridge.running
        try {
            if (started) Bridge.startForCheck()
            Bridge.feedForCheck = { picture }
            fun post(on: Boolean) = Bridge.handle(ApiRequest("POST", "/api/contacts/hostiles", mapOf("on" to if (on) "1" else "0")))
            fun contacts(): Contacts? = runCatching {
                Bridge.json.decodeFromString(Contacts.serializer(), Bridge.handle(ApiRequest("GET", "/api/contacts")).body.decodeToString())
            }.getOrNull()
            fun infoSays(): Boolean? = runCatching {
                Bridge.json.decodeFromString(BridgeInfo.serializer(), Bridge.handle(ApiRequest("GET", "/api/info")).body.decodeToString()).tacview.hostiles
            }.getOrNull()

            val r0 = post(false)
            val st0 = runCatching { Bridge.json.decodeFromString(TacviewStatus.serializer(), r0.body.decodeToString()) }.getOrNull()
            check(r0.status == 200 && st0?.hostiles == false, "POST /api/contacts/hostiles?on=0 answers 200 with hostiles false")
            check(infoSays() == false, "/api/info says off")
            val off = contacts()
            appendLine("   off: ${off?.contacts?.map { it.id }?.sorted()?.joinToString(", ")}")
            check(off != null && off.contacts.none { it.hostile }, "GET /api/contacts carries no hostile contact while off")
            check(off != null && off.contacts.any { it.id == "wing" } && off.contacts.any { it.id == "me" }, "and still carries the own side")
            check(off != null && off.events.all { it.mine }, "and only the own side's weapon events")

            val r1 = post(true)
            check(r1.status == 200 && Bridge.settings.value.ShowHostiles, "POST ?on=1 turns it on (kept in the settings)")
            check(infoSays() == true, "/api/info says on")
            val on = contacts()
            appendLine("   on: ${on?.contacts?.map { it.id }?.sorted()?.joinToString(", ")}")
            check(on != null && listOf("bandit", "helo", "boat", "tank", "chute").all { id -> on.contacts.any { it.id == id } }, "GET /api/contacts carries the hostile ones while on")
            check(on != null && on.contacts.none { it.id == "samsite" }, "a hostile air defence at no known site is kept back either way (KnownSams)")
        } finally {
            Bridge.feedForCheck = null
            runCatching { Bridge.update(reapply = false) { it.copy(ShowHostiles = was) } }
            if (started) runCatching { Bridge.stop() }
        }

        appendLine(); appendLine("4. A device")
        check(HostileContacts.filter(picture, HostileContacts.on(null)).contacts.none { it.hostile }, "no answer from the PC: stripped")
        check(HostileContacts.filter(picture, HostileContacts.on(BridgeInfo())).contacts.none { it.hostile }, "an older PC that does not say: stripped")
        check(HostileContacts.filter(picture, HostileContacts.on(BridgeInfo(tacview = TacviewStatus(hostiles = true)))) == picture, "a PC that says on: kept")
    }
}
