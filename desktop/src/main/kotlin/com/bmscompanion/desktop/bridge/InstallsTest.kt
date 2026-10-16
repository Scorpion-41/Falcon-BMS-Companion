package com.bmscompanion.desktop.bridge

import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.mission.BmsInstallList
import com.bmscompanion.app.data.mission.BridgeInfo
import com.bmscompanion.app.ui.screens.mission.BmsInstallSeen
import java.io.File

/**
 * `--installstest out.txt`: the list of Falcon BMS installs and the quick switch (1.4.1, [Installs]).
 *
 * 1. The registry, read only: every `Falcon BMS 4.x` key of both views, and the newest whose folder exists as the default.
 * 2. Settings: step 6 of the migration puts an old `BmsDirOverride` in `BmsInstalls` once, and keeps it active.
 * 3. The routes, with two made-up registry installs ([Installs.extraForCheck]: "Falcon BMS 4.37" and "Falcon BMS 4.36",
 *    folders in the scratch APPDATA) beside the real ones: the list, Add folder (a plain folder, a network path, a
 *    missing one), switching (the folder, the callsign and the theater follow the install's own key; `/api/info` names
 *    it; the map tiles held and the radio log's calls of the last install are dropped), renaming, taking out (a
 *    registry install refused, the active folder taken out falls back on the registry's), back to the registry's newest.
 * 4. DevGuard: every registry install (the real ones and the made-up ones) and a folder inside one is refused, a plain
 *    folder is not, and a write route answered for an active registry install that is not the default is refused.
 *
 * Writes only the settings of the APPDATA it runs under and folders inside it (run it with a scratch APPDATA), and puts
 * the settings back as it found them. Never writes the registry or a BMS folder.
 */
object InstallsTest {
    fun run(): String = buildString {
        fun check(ok: Boolean, what: String) = appendLine((if (ok) "ok   " else "FAIL ") + what)
        appendLine("Falcon BMS installs check (BMS Companion ${com.bmscompanion.app.AppVersion.NAME})")

        appendLine(); appendLine("1. The registry (read only)")
        Installs.extraForCheck = emptyList()
        Installs.forgetRegistry()
        val real = Installs.registry()
        for (r in real) appendLine("   ${r.key}: folder ${if (File(r.dir).isDirectory) "exists" else "missing"}, theater ${r.theater ?: "-"}")
        if (real.isEmpty()) appendLine("   (no Falcon BMS key on this PC)")
        check(real.zipWithNext().all { (a, b) -> ver(a.key) >= ver(b.key) }, "newest version first")
        val def = Installs.default(real)
        check(def == null || File(def.dir).isDirectory, "the default is the newest install whose folder exists (${def?.key ?: "none"})")
        val probe = BmsInstall().apply { refresh(null) }
        check(probe.baseDir.equals(def?.dir, ignoreCase = true) && probe.registryVersion == def?.key,
            "with no install chosen, BmsInstall reads the registry's default (${probe.registryVersion ?: "none"})")

        appendLine(); appendLine("2. Bringing a settings file forward (step 6)")
        val m1 = BridgeSettings.migrate(BridgeSettings(BmsDirOverride = "X:\\Games\\Falcon BMS 4.37\\", SettingsVersion = 5), save = false)
        check(m1.BmsInstalls.map { it.path } == listOf("X:\\Games\\Falcon BMS 4.37") && m1.BmsDirOverride == "X:\\Games\\Falcon BMS 4.37\\" &&
            m1.SettingsVersion == BridgeSettings.CURRENT, "a folder set by hand goes into the list once and stays the active one")
        val m2 = BridgeSettings.migrate(BridgeSettings(SettingsVersion = 5), save = false)
        check(m2.BmsInstalls.isEmpty(), "no folder set by hand: an empty list")
        val m3 = BridgeSettings.migrate(BridgeSettings(BmsDirOverride = "x:\\games\\falcon bms 4.37", BmsInstalls = listOf(BmsInstallSetting("X:\\Games\\Falcon BMS 4.37", "Old")), SettingsVersion = 5), save = false)
        check(m3.BmsInstalls.size == 1 && m3.BmsInstalls[0].label == "Old", "a folder already listed is not listed twice (case and backslash aside)")
        val m4 = BridgeSettings.migrate(BridgeSettings(BmsDirOverride = "X:\\Other", SettingsVersion = BridgeSettings.CURRENT), save = false)
        check(m4.BmsInstalls.isEmpty(), "a file already at the layout is left as it is")

        appendLine(); appendLine("3. The routes")
        val was = Bridge.settings.value
        val started = !Bridge.running
        val root = File(Repo.settingsFolder, "installstest").apply { deleteRecursively(); mkdirs() }
        val d437 = File(root, "Falcon BMS 4.37").apply { File(this, "Data\\TerrData").mkdirs(); File(this, "User\\Config").mkdirs() }
        val d436 = File(root, "Falcon BMS 4.36").apply { File(this, "Data\\TerrData").mkdirs() }
        val plain = File(root, "Just a folder").apply { mkdirs() }
        val reader = File(root, "2026-10-16_120000_xlog.txt").apply { writeText("") }
        try {
            if (started) Bridge.startForCheck()
            Bridge.update(reapply = false) { it.copy(TacviewEnabled = false) }
            Installs.extraForCheck = listOf(
                Installs.Reg("Falcon BMS 4.37", d437.path, "Pilot437", "Theater437"),
                Installs.Reg("Falcon BMS 4.36", d436.path, "Pilot436", "Theater436"),
            )
            Installs.forgetRegistry()
            fun get(): Pair<Int, BmsInstallList?> = Bridge.handle(ApiRequest("GET", "/api/bms/installs")).let { r ->
                r.status to runCatching { Bridge.json.decodeFromString(BmsInstallList.serializer(), r.body.decodeToString()) }.getOrNull()
            }
            fun post(op: String, vararg q: Pair<String, String>): Pair<Int, BmsInstallList?> =
                Bridge.handle(ApiRequest("POST", "/api/bms/installs/$op", q.toMap())).let { r ->
                    r.status to runCatching { Bridge.json.decodeFromString(BmsInstallList.serializer(), r.body.decodeToString()) }.getOrNull()
                }
            fun info(): BridgeInfo? = runCatching { Bridge.json.decodeFromString(BridgeInfo.serializer(), Bridge.handle(ApiRequest("GET", "/api/info")).body.decodeToString()) }.getOrNull()
            val devTheater = !System.getenv(BmsInstall.DEV_THEATER).isNullOrBlank()

            val (s0, l0) = get()
            appendLine("   listed: ${l0?.installs?.joinToString { "${it.id}${if (it.active) " (in use)" else ""}" }}")
            check(s0 == 200 && l0 != null, "GET /api/bms/installs answers 200")
            check(l0?.installs?.any { it.id == "r:Falcon BMS 4.37" && it.registryKey == "Falcon BMS 4.37" && it.exists && it.looksLikeBms && !it.added } == true,
                "a registry install is listed by itself, its key's version as its name, as a BMS folder")
            check(real.all { r -> l0?.installs?.any { it.registryKey == r.key } == true }, "every real registry install is listed (${real.size})")
            check(l0?.installs?.count { it.active } == 1 && l0.active == l0.installs.first { it.active }.id, "one install is marked in use, and it is the one read")
            val wasOverride = was.BmsDirOverride
            if (!wasOverride.isNullOrBlank()) check(l0?.installs?.any { it.active && it.path.equals(wasOverride.trimEnd('\\'), true) } == true,
                "the folder the settings name is the one in use, listed even when it never was added")

            val (sa, la) = post("add", "path" to plain.path)
            val added = la?.installs?.firstOrNull { it.path.equals(plain.path, true) }
            check(sa == 200 && added != null && added.added && !added.looksLikeBms && !added.active, "Add folder: listed, not active, not a BMS folder")
            check(la?.message?.contains("does not look like a Falcon BMS folder") == true, "and the answer says it does not look like one")
            check(Bridge.settings.value.BmsInstalls.any { it.path.equals(plain.path, true) }, "kept in the settings (BmsInstalls)")
            val (sa2, la2) = post("add", "path" to plain.path + "\\")
            check(sa2 == 200 && la2?.installs?.count { it.path.equals(plain.path, true) } == 1, "adding it again lists it once")
            check(post("add", "path" to "\\\\server\\share\\BMS").first == 400, "a network path is refused")
            check(post("add", "path" to File(root, "not there").path).first == 404, "a folder that is not there is refused")

            // a switch to a registry install that is not the default: its own key's callsign and theater
            RadioLog.reader.follow(reader)
            MapTiles.holdForCheck("korea/relief/9/1/1")
            // folders inside the install read until now follow the switch; one outside it is the pilot's and stays
            val base0 = Bridge.install.baseDir
            Bridge.update(reapply = false) { it.copy(EzBoardsDir = base0?.let { b -> "$b\\Tools\\EZBoards" }, KneeboardExporterDir = plain.path) }
            val n0 = Installs.switches
            val (s1, l1) = post("active", "id" to "r:Falcon BMS 4.37")
            check(s1 == 200 && l1?.installs?.firstOrNull { it.active }?.id == "r:Falcon BMS 4.37", "POST active: Falcon BMS 4.37 in use (${l1?.message})")
            check(Bridge.install.baseDir.equals(d437.path, true), "the program reads its folder")
            check(Bridge.install.callsign == "Pilot437" && Bridge.install.registryVersion == "Falcon BMS 4.37", "with its own key's pilot callsign and version")
            if (devTheater) appendLine("   (theater not checked: ${BmsInstall.DEV_THEATER} stands in for curTheater in a developer run)")
            else check(Bridge.install.theater == "Theater437", "and its own key's theater")
            check(Bridge.settings.value.BmsDirOverride.equals(d437.path, true), "kept as the active folder (BmsDirOverride)")
            check(Installs.switches == n0 + 1, "one switch")
            check(base0 == null || Bridge.settings.value.EzBoardsDir == null, "EZBoards in the last install's Tools is not run for this one (${Bridge.settings.value.EzBoardsDir ?: "none"})")
            check(Bridge.settings.value.KneeboardExporterDir.equals(plain.path, true), "a folder outside the last install stays")
            check(MapTiles.heldForCheck() == 0, "the map tiles of the last install are dropped")
            check(RadioLog.reader.file == null, "the radio log's calls of the last install are dropped")
            val i1 = info()
            check(i1?.bms?.install == "Falcon BMS 4.37" && (i1.bms.installs) >= 3 && i1.bms.baseDir.equals(d437.path, true), "/api/info names it (${i1?.bms?.installs} installs)")
            check(post("active", "id" to "r:Falcon BMS 4.37").let { it.first == 200 && it.second?.message?.contains("already") == true } && Installs.switches == n0 + 1,
                "picking it again changes nothing")

            // DevGuard: the active registry install that is not the default is refused for a write route
            val cfg = Bridge.handle(ApiRequest("POST", "/api/cfg/backup"))
            check(cfg.status == 409 && cfg.body.decodeToString().contains(DevGuard.REFUSAL), "a write route for it is refused in a developer run")

            val (sl, ll) = post("label", "id" to "r:Falcon BMS 4.37", "label" to "Old BMS")
            check(sl == 200 && ll?.installs?.firstOrNull { it.id == "r:Falcon BMS 4.37" }?.name == "Old BMS", "renamed: Old BMS")
            check(info()?.bms?.install == "Old BMS", "/api/info says the new name")
            val (_, ll2) = post("label", "id" to "r:Falcon BMS 4.37", "label" to "")
            check(ll2?.installs?.firstOrNull { it.id == "r:Falcon BMS 4.37" }?.name == "Falcon BMS 4.37" &&
                Bridge.settings.value.BmsInstalls.none { it.path.equals(d437.path, true) }, "its own name again, and no row left for it")
            check(post("remove", "id" to "r:Falcon BMS 4.37").first == 409, "a registry install cannot be taken out")

            // a folder of the pilot's: the newest key's callsign, as an override always had
            if (added == null) { check(false, "the added folder is not in the list: the rest cannot run"); return@buildString }
            val (s2, _) = post("active", "id" to added.id)
            val newest = Installs.default()
            check(s2 == 200 && Bridge.install.baseDir.equals(plain.path, true) && Bridge.install.callsign == newest?.callsign,
                "an added folder in use: its folder, the registry default's callsign")
            val (s3, l3) = post("remove", "id" to added.id)
            check(s3 == 200 && l3?.installs?.none { it.path.equals(plain.path, true) } == true && Bridge.settings.value.BmsDirOverride == null,
                "taking out the folder in use: gone from the list, no folder chosen")
            check(Bridge.install.baseDir.equals(newest?.dir, true), "and the registry's newest is read (${newest?.key ?: "none"})")
            check(post("active", "id" to "a:nothing").first == 404, "an id not in the list is refused")

            if (newest != null) {
                post("active", "id" to "r:Falcon BMS 4.36")
                val (s4, l4) = post("active", "id" to "r:" + newest.key)
                check(s4 == 200 && Bridge.settings.value.BmsDirOverride == null && l4?.followsRegistry == true,
                    "back to the registry's newest: followed, not pinned (BmsDirOverride null)")
            }

            appendLine(); appendLine("4. DevGuard: every registry install is refused")
            for (r in Installs.registry()) {
                val why = DevGuard.why(File(r.dir))
                check(why != null && DevGuard.refusal(r.dir)?.startsWith(DevGuard.REFUSAL) == true, "${r.key} refused")
                check(DevGuard.why(File(r.dir, "User\\Config")) != null, "${r.key}\\User\\Config refused")
            }
            check(DevGuard.why(plain) == null, "a plain folder (a copy) is not refused")

            appendLine(); appendLine("5. The notes are the install's own")
            Bridge.update { it.copy(BmsDirOverride = null) }
            val tagDefault = NotesStore.installTag()
            check(tagDefault == "", "the registry's newest install: the key as before 1.4.1 (older notes stay its own)")
            post("active", "id" to "r:Falcon BMS 4.37")
            val tag437 = NotesStore.installTag()
            post("active", "id" to "r:Falcon BMS 4.36")
            val tag436 = NotesStore.installTag()
            check(tag437.startsWith("|@") && tag436.startsWith("|@") && tag437 != tag436, "another install: a key of its own ($tag437, $tag436)")

            appendLine(); appendLine("6. A device drops the Planner's unsaved DTC edits at a switch")
            dropCheck(::check)
        } finally {
            Installs.extraForCheck = emptyList()
            Installs.forgetRegistry()
            RadioLog.reader.follow(null)
            runCatching { Bridge.update { was } }
            if (started) runCatching { Bridge.stop() }
            runCatching { root.deleteRecursively() }
        }
        // (an empty EZBoards folder is filled in from the install read whenever settings are applied: the program's own doing)
        check(Bridge.settings.value.copy(EzBoardsDir = was.EzBoardsDir) == was, "the settings are as they were")
    }

    /** A cartridge held in memory, answering the DTC page as the PC does; [text] is what the install read now holds. */
    private class FakeDtc(@Volatile var text: String) : com.bmscompanion.app.ui.screens.wdp.DtcSource {
        @Volatile var loads = 0
        @Volatile var saves = 0
        override suspend fun load(callsign: String?) = com.bmscompanion.app.data.mission.CartridgeState(
            available = true, callsign = "Check", file = "Check.ini", text = text, modified = (++loads).toLong(), path = "X:\\BMS\\User\\Config\\Check.ini",
        )
        override suspend fun save(callsign: String?, edits: List<com.bmscompanion.app.data.mission.CartridgeEdit>) = load(callsign).also { saves++ }
        override suspend fun harmCodes() = dtcAppHarmCodes()
    }

    /**
     * The device side of a switch: the DTC page with an edit not saved (made on install A's cartridge), then the switch
     * ([BmsInstallSeen] → `WdpSession.dropForInstall`, here [com.bmscompanion.app.ui.screens.wdp.DtcWiring.dropForInstall]
     * on a page of its own): the edit is gone without a question and install B's cartridge is on the page.
     */
    private fun dropCheck(check: (Boolean, String) -> Unit) {
        val base = Bridge.callsignIni?.let(::File)?.takeIf { it.isFile }?.readText(Charsets.ISO_8859_1)
            ?: "[ICP]\r\nBingo_Fuel=2000.000000\r\n"
        val fake = FakeDtc(base)
        val dtc = com.bmscompanion.app.ui.screens.wdp.DtcWiring(fake)
        fun waitLoaded(n: Int): Boolean {
            val until = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < until) {
                var ok = false
                javax.swing.SwingUtilities.invokeAndWait { ok = fake.loads >= n && dtc.model != null }
                if (ok) return true
                Thread.sleep(50)
            }
            return false
        }
        javax.swing.SwingUtilities.invokeAndWait { dtc.dropForInstall() }
        check(waitLoaded(1), "install A's cartridge on the DTC page")
        var edited = false
        javax.swing.SwingUtilities.invokeAndWait { dtc.model?.icp?.bingoFuel = 1234f; edited = dtc.hasUnsavedEdits }
        check(edited, "an edit not saved (bingo 1234)")
        // the PC switched: install B's cartridge
        fake.text = base.replace(Regex("(?m)^Bingo_Fuel=.*$"), "Bingo_Fuel=3456.000000")
        var cleared = false
        // the read may finish within the call (a source that answers at once): then B's cartridge is already on the page
        javax.swing.SwingUtilities.invokeAndWait { dtc.dropForInstall(); cleared = dtc.model == null || (dtc.model?.icp?.bingoFuel != 1234f && !dtc.hasUnsavedEdits) }
        check(cleared, "right after the switch the page holds no edit of A's (nothing, or B's cartridge as read)")
        check(waitLoaded(2), "install B's cartridge read")
        var after = false
        var bingo = 0f
        javax.swing.SwingUtilities.invokeAndWait { after = dtc.hasUnsavedEdits; bingo = dtc.model?.icp?.bingoFuel ?: 0f }
        check(!after && bingo != 1234f, "the edit is gone without a question (bingo now ${bingo.toInt()}, no edits waiting)")
        check(fake.saves == 0, "nothing was saved")
        // the device's own watch: another folder seen once drops, the same folder again does nothing
        BmsInstallSeen.observe("X:\\Install A")
        val n0 = BmsInstallSeen.drops
        BmsInstallSeen.observe("X:\\Install B"); BmsInstallSeen.observe("x:\\install b")
        check(BmsInstallSeen.drops == n0 + 1, "a device acts once on another folder (bms_install_seen), not on the same one again")
    }

    private fun ver(key: String): Int = Regex("(\\d+)\\.(\\d+)").find(key)?.let { it.groupValues[1].toInt() * 1000 + it.groupValues[2].toInt() } ?: 0
}
