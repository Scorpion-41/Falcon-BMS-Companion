package com.bmscompanion.desktop.bridge

import com.bmscompanion.app.data.mission.CampKind
import com.bmscompanion.app.data.mission.CampRef
import com.bmscompanion.app.data.mission.CartridgeEdit
import com.bmscompanion.app.data.mission.CartridgeLedger
import com.bmscompanion.app.data.mission.CartridgeLeftovers
import com.bmscompanion.app.data.mission.CartridgeState
import com.bmscompanion.app.data.mission.LedgerCleared
import com.bmscompanion.app.data.mission.LedgerMission
import com.bmscompanion.app.data.mission.LedgerWrite
import com.bmscompanion.app.data.mission.Leftover
import com.bmscompanion.app.data.mission.Leftovers
import com.bmscompanion.app.data.mission.MissionIniResult
import com.bmscompanion.app.data.wdp.DtcEdits
import java.io.File

/**
 * The pilot's data cartridge, for the Planner's DTC page: read it, and write the keys the pilot edited back into it.
 *
 * The cartridge (`User/Config/<callsign>.ini`) is Falcon BMS's own file, and BMS rewrites it whenever the pilot saves
 * the DTC in the UI. The Planner writes it **the way WDP does**: Save to DTC writes it directly, with no backup copy,
 * no question first and nothing to switch on (the pilot's decision of 2026-09-29: the kneeboard pages, the pilot's
 * own saves and the cartridge are all written like this; only Config and Weather keep a copy of the originals). What
 * keeps a save safe:
 *
 * 1. **Only the edited keys, each an absolute value.** The page sends the keys the pilot changed ([DtcEdits]), and
 *    they are applied to the file as it is on disk now. Applying them twice gives the same file as once, so edits
 *    never compound; and because BMS itself rewrites this file between missions, the base is BMS's latest file.
 *    Every key not named survives byte for byte.
 * 2. **Never written in place**: a temporary file beside it, then one atomic move, so a failed write cannot leave half
 *    a cartridge.
 * 3. **Nothing throws.** Falcon BMS is often installed where Windows will not let an ordinary program write; the
 *    reason comes back in [CartridgeState.error] for the page to show.
 *
 * A folder `User/Config/BMS Companion Backup/` that an earlier test build left on the pilot's disk is left exactly as
 * it is, and nothing reads it any more.
 *
 * One other file is written, and only with a save: the steerpoints of a Tactical Engagement the Planner has open go
 * into that TE's own mission file too, the same way ([saveTe]). A campaign start is never written ([CampaignStarts]);
 * the Planner cannot open one in the first place.
 */
class CartridgeStore(
    private val install: BmsInstall,
    /** A stand-in for `User/Config`, so --cartridgetest can run all of this against a copy. */
    private val configOverride: File? = null,
) {
    companion object {
        /**
         * The cartridge keys a Tactical Engagement's own mission file also holds, and the only ones [saveTe] writes
         * there: steerpoints and targets (`target_n`, STPT 1-24 and 81-99), lines (`lineSTPT_n`), PPTs (`ppt_n`)
         * and weapon targets (`wpntarget_n`), all in `[STPT]` (UM p.56, p.75, p.77).
         */
        val TE_KEY = Regex("(target|lineSTPT|ppt|wpntarget)_\\d+", RegexOption.IGNORE_CASE)

        /** The ledger's own JSON: a later build's fields are passed over, never the end of reading it. */
        private val LEDGER_JSON = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false; prettyPrint = true }
    }

    // ---------------------------------------------------------------- where the cartridge is

    private fun configDir(): File? = configOverride ?: install.configDir?.let(::File)

    /**
     * The cartridge for [callsign], or for the pilot BMS has selected. A name is only ever a file in `User/Config`:
     * anything with a path in it is refused, so the page cannot be pointed at another file on the PC.
     */
    private fun fileFor(callsign: String?): File? {
        val dir = configDir() ?: return null
        val name = callsign?.trim()?.takeIf { it.isNotEmpty() } ?: install.callsign?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (name.any { it == '/' || it == '\\' || it == ':' } || name.contains("..")) return null
        return File(dir, "$name.ini")
    }

    // ---------------------------------------------------------------- what the page needs

    fun state(callsign: String? = null, error: String? = null, message: String? = null): CartridgeState =
        attempt({ CartridgeState(callsign = callsign, error = it) }) {
            val f = fileFor(callsign)
            val name = f?.nameWithoutExtension
            val ppt = pptIni()
            if (f == null || !f.isFile) {
                return@attempt CartridgeState(
                    available = false, callsign = name, file = f?.name, pptIni = ppt,
                    error = error ?: if (configDir() == null) "Falcon BMS was not found on this PC."
                    else if (f == null) "BMS has no pilot selected, so there is no cartridge to open."
                    else "There is no cartridge for ${f.nameWithoutExtension} in User\\Config yet: select that pilot in Falcon BMS and save the DTC once.",
                )
            }
            CartridgeState(
                available = true, callsign = name, file = f.name, path = f.path, text = f.readText(Charsets.ISO_8859_1),
                modified = f.lastModified(), pptIni = ppt, error = error, message = message,
                ledger = ledger(name).takeIf { it.writes.isNotEmpty() },
            )
        }


    /**
     * The theater's own PPT types (`Campaign/ppt.ini`: code, range in feet, name), for the page's names and ranges.
     * Read only. The folder is the campaign folder BMS's theater definition gives the theater BMS is on ([Theaters],
     * A5), then `Data\Campaign` — an add-on campaign folder with no `ppt.ini` of its own (Korea 2012's) flies with the
     * base theater's. Guessing the folder from words in its name looked in `Add-On Korea 2012\Campaign` for Korea KTO.
     */
    private fun pptIni(): String? = attempt({ null }) {
        val base = install.baseDir?.let(::File) ?: return@attempt null
        val dirs = listOfNotNull(Theaters.campaignDir(install, install.theater), File(File(base, "Data"), "Campaign"))
        dirs.firstNotNullOfOrNull { d -> d.listFiles { f -> f.isFile && f.name.equals("ppt.ini", true) }?.firstOrNull() }
            ?.readText(Charsets.ISO_8859_1)
    }

    // ---------------------------------------------------------------- writing

    /**
     * Writes [edits] into the cartridge as it is on disk now, directly, as WDP's Save DTC does: no copy is taken first
     * and nothing has to be switched on. A temporary file beside it, then one atomic move.
     */
    fun save(callsign: String?, edits: List<CartridgeEdit>, mission: LedgerMission? = null, ledgered: Boolean = true): CartridgeState = attempt({ state(callsign, it) }) {
        val f = fileFor(callsign)
        if (f == null || !f.isFile) return@attempt state(callsign)
        if (edits.isEmpty()) return@attempt state(callsign, message = "${f.name}: nothing had changed.")
        val before = String(f.readBytes(), Charsets.ISO_8859_1)
        val after = DtcEdits.apply(before, edits)
        if (after == before) return@attempt state(callsign, message = "${f.name}: nothing had changed.")
        writeSafely(f, after.toByteArray(Charsets.ISO_8859_1))
        BridgeLog.info("Save to DTC: ${f.name} written (${edits.size} setting${if (edits.size == 1) "" else "s"})")
        // what was written, and for which mission (the ledger); never in the way of the save itself
        if (ledgered) note(f.nameWithoutExtension) { Leftovers.record(it, f.nameWithoutExtension, edits, mission, System.currentTimeMillis()) }
        state(callsign, message = "${f.name} saved.")
    }

    // ---------------------------------------------------------------- the ledger: what the Planner wrote, for which mission

    /**
     * Where the ledger of [callsign]'s cartridge lives: `<BMS>\User\BMS Companion Planner\Ledger\<callsign>.json`, in
     * the Planner's own folder beside BMS's `User\Config` (the ground rules: the Planner's folder, written directly).
     */
    private fun ledgerFile(callsign: String): File? {
        val user = configDir()?.parentFile ?: return null
        if (callsign.isBlank() || callsign.any { it == '/' || it == '\\' || it == ':' } || callsign.contains("..")) return null
        return File(File(File(user, PcFileRoutes.PLANNER_FOLDER), "Ledger"), "${callsign.trim()}.json")
    }

    /** The ledger of [callsign]'s cartridge; empty when there is none or it cannot be read. Never throws. */
    fun ledger(callsign: String?): CartridgeLedger {
        val name = callsign?.trim()?.takeIf { it.isNotEmpty() } ?: install.callsign?.trim()?.takeIf { it.isNotEmpty() } ?: return CartridgeLedger()
        return attempt({ CartridgeLedger(name) }) {
            val f = ledgerFile(name)?.takeIf { it.isFile } ?: return@attempt CartridgeLedger(name)
            LEDGER_JSON.decodeFromString(CartridgeLedger.serializer(), f.readText(Charsets.UTF_8))
        }
    }

    /** Changes the ledger of [callsign] and writes it (a temporary file, then a move). A failure is logged, never thrown. */
    @Synchronized
    private fun note(callsign: String, change: (CartridgeLedger) -> CartridgeLedger) {
        try {
            val f = ledgerFile(callsign) ?: return
            val root = f.parentFile.parentFile
            // the Planner's folder made here first: laid out as WDP's, with what an earlier build kept copied in
            if (configOverride == null && !root.isDirectory) runCatching { PcFileRoutes.preparePlanner(root) }
            f.parentFile.mkdirs()
            val next = change(ledger(callsign))
            writeSafely(f, LEDGER_JSON.encodeToString(CartridgeLedger.serializer(), next).toByteArray(Charsets.UTF_8))
        } catch (e: Throwable) {
            BridgeLog.info("Save to DTC: the ledger of $callsign could not be written: ${reason(e)}")
        }
    }

    /** What [callsign]'s cartridge still holds that the Planner saved for another flight than [now]. Null for none. */
    fun leftovers(callsign: String?, now: LedgerMission?): CartridgeLeftovers? = attempt({ null }) {
        val f = fileFor(callsign)?.takeIf { it.isFile } ?: return@attempt null
        Leftovers.find(ledger(f.nameWithoutExtension), String(f.readBytes(), Charsets.ISO_8859_1), now)
    }

    /**
     * `POST /api/cartridge/leftovers`: the leftovers for [now], worked out again against the file as it is, and either
     * cleared ([clear]: each key back to BMS's empty value — only those keys, and only while each still holds what the
     * Planner wrote — the same safe write as Save to DTC) or kept for [now] (the ledger's rows given to this mission, so
     * they are not called leftovers again). The answer is the cartridge as it then is, with a message saying what was done.
     */
    fun settleLeftovers(callsign: String?, now: LedgerMission?, clear: Boolean): CartridgeState = attempt({ state(callsign, it) }) {
        val f = fileFor(callsign)
        if (f == null || !f.isFile) return@attempt state(callsign)
        if (now == null) return@attempt state(callsign, "Which mission is flown now was not said, so nothing can be called left over.")
        val left = leftovers(callsign, now)
            ?: return@attempt state(callsign, message = "${f.name} holds nothing the Planner saved for another flight.")
        val name = f.nameWithoutExtension
        val keys = left.items.map { (it.section.uppercase() to it.key.uppercase()) }.toSet()
        if (clear) {
            val saved = save(callsign, Leftovers.clearEdits(left), ledgered = false)
            if (saved.error != null) return@attempt saved
            // the cleared keys leave the ledger: nothing of the Planner's is in them any more
            note(name) { l -> l.copy(writes = l.writes.filterNot { it.file.isEmpty() && (it.section.uppercase() to it.key.uppercase()) in keys }) }
            BridgeLog.info("Cartridge: cleared ${Leftovers.summary(left.items)} left from ${left.from?.label ?: "an earlier mission"}")
            state(callsign, message = "${f.name}: cleared ${Leftovers.summary(left.items)} left from ${left.from?.label ?: "an earlier mission"}.")
        } else {
            note(name) { l -> l.copy(writes = l.writes.map { w -> if (w.file.isEmpty() && (w.section.uppercase() to w.key.uppercase()) in keys) w.copy(mission = now) else w }) }
            BridgeLog.info("Cartridge: kept ${Leftovers.summary(left.items)} for ${now.label}")
            state(callsign, message = "${f.name}: ${Leftovers.summary(left.items)} kept for ${now.label}.")
        }
    }

    // ---------------------------------------------------------------- a switch of mode (SwitchReset)

    /** What [resetForSwitch] did: the cartridge's name, the keys it cleared (a TE's file's too), and what it left and why. */
    class SwitchCleared(val file: String?, val keys: List<Leftover>, val left: List<String>)

    private fun rowId(file: String, section: String, key: String) = file.trim().uppercase() + "\u0001" + section.trim().uppercase() + "\u0000" + key.trim().uppercase()
    private fun diskId(section: String, key: String) = section.trim().uppercase() + "\u0000" + key.trim().uppercase()

    /** A ledger row as a [Leftover], for the summary ([file] "" = the cartridge). */
    private fun itemOf(w: LedgerWrite): Leftover? {
        val (kind, slot) = Leftovers.kindOf(w.section, w.key) ?: return null
        val f = w.value.split(',').map { it.trim() }
        val pos = kind != Leftovers.NAV
        return Leftover(
            section = w.section, key = w.key, value = w.value, kind = kind, slot = slot,
            line = if (kind == Leftovers.LINE && slot in 0..23) slot / 6 + 1 else null,
            x = if (pos) f.getOrNull(0)?.toDoubleOrNull() ?: 0.0 else 0.0, y = if (pos) f.getOrNull(1)?.toDoubleOrNull() ?: 0.0 else 0.0,
            at = w.at, from = w.mission, file = w.file,
        )
    }

    /** Every key of the first `[NAV OFFSETS]` section of [text], as the file spells it, with its value. */
    private fun navKeys(text: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        var inNav = false
        var seen = false
        for (raw in text.split('\n')) {
            val line = raw.trimEnd('\r')
            val s = line.trim(' ', '\t')
            if (s.startsWith("[")) {
                val nm = s.substring(1).substringBefore(']').trim(' ', '\t')
                if (nm.equals("NAV OFFSETS", ignoreCase = true)) { inNav = !seen; seen = true } else inNav = false
                continue
            }
            if (!inNav || s.startsWith(";")) continue
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val k = line.substring(0, eq).trim(' ', '\t')
            if (k.isEmpty() || out.any { it.first.equals(k, ignoreCase = true) }) continue
            out += k to line.substring(eq + 1).trim(' ', '\t')
        }
        return out
    }

    /**
     * The `[NAV OFFSETS]` keys of [text] ([file]: "" = the cartridge, else a TE's `.ini`) to clear at a switch or a new
     * mission, as [LedgerCleared] rows (the value they held, what clears them): every one that places something (a
     * Modesel other than none, an offset not all zeros, any offset aim point), except those in [skip] (cleared by the
     * ledger's own rule already) and, with [keep], what the ledger says the Planner saved for that flight and the file
     * still holds.
     */
    private fun navToClear(text: String, file: String, ledger: com.bmscompanion.app.data.mission.CartridgeLedger, keep: LedgerMission?, skip: Set<String>, at: Long): List<LedgerCleared> =
        navKeys(text).mapNotNull { (k, v) ->
            val id = diskId("NAV OFFSETS", k)
            if (id in skip) return@mapNotNull null
            val oa = k.trim().uppercase().startsWith("OA")
            if (!oa && Leftovers.isEmpty(Leftovers.NAV, v)) return@mapNotNull null
            if (keep != null && ledger.writes.any { w ->
                    w.file.trim().equals(file, ignoreCase = true) && diskId(w.section, w.key) == id && w.mission?.sameFlight(keep) == true && Leftovers.same(v, w.value)
                }) return@mapNotNull null
            LedgerCleared(LedgerWrite(file, "NAV OFFSETS", k, v, at, null), Leftovers.clearValue("NAV OFFSETS", k), at)
        }

    /** The current flight's TE file for the delivery-data sweep: `<save>.ini` of a `.tac`/`.trn` [save], when it exists. */
    private fun teNavFile(save: String?, theater: String?): File? {
        val s = save?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val ext = s.substringAfterLast('.', "").lowercase()
        if (ext != "tac" && ext != "trn") return null
        if (CampaignStarts.byName(s.substringAfterLast('\\').substringAfterLast('/')) != null) return null
        return teFile(theater, teIniName(s))
    }

    /**
     * On a switch of the Mission section's mode or at a new mission (docs/DATA-STORES.md, "What a switch resets"): the
     * Planner's leftovers in [callsign]'s cartridge and in the TEs' own mission files — every mission item the ledger
     * says the Planner wrote (a key that places something) that **still holds exactly the Planner's value** — cleared to
     * BMS's empty value, the same safe write as Clear. [keep] null (a switch of mode): every such key, whatever flight it
     * was saved for and whenever. [keep] a flight (a new mission): every such key saved for **another** flight, whenever
     * (a key saved with no flight known is kept). A key BMS or the pilot wrote since is never touched (it no longer holds
     * the Planner's value). What was cleared stays in the ledger ([CartridgeLedger.cleared] under [switchAt]) for
     * [undoSwitch]. Never throws.
     *
     * **The delivery data goes whoever wrote it** (1.3.8): every `[NAV OFFSETS]` key (Modesel, VIP, VIPPUP, VRP, VRPPUP,
     * OA1-n/OA2-n) that places something is cleared too, in the cartridge and in the current flight's TE file
     * ([teSave], a `.tac`/`.trn`, of [teTheater]), ledger or not — an earlier build, or WDP itself, wrote them before the
     * ledger existed, and BMS's own DTC window never edits them, so they only ever come from a planner. At a new mission
     * the one exception is what the ledger says the Planner saved for [keep] itself and the file still holds.
     */
    fun resetForSwitch(callsign: String?, keep: LedgerMission?, switchAt: Long, teSave: String? = null, teTheater: String? = null): SwitchCleared =
        attempt({ SwitchCleared(null, emptyList(), listOf("The cartridge could not be looked at: $it")) }) {
            val f = fileFor(callsign)?.takeIf { it.isFile } ?: return@attempt SwitchCleared(null, emptyList(), emptyList())
            val name = f.nameWithoutExtension
            val ledger = ledger(name)
            fun leftover(w: LedgerWrite): Boolean {
                val (kind, _) = Leftovers.kindOf(w.section, w.key) ?: return false
                if (Leftovers.isEmpty(kind, w.value)) return false
                if (keep == null) return true
                val m = w.mission ?: return false
                return !m.sameFlight(keep)
            }
            val older = ledger.writes.filter(::leftover)
            val text = String(f.readBytes(), Charsets.ISO_8859_1)
            val disk = Leftovers.keys(text)
            // the cartridge: the keys still holding the Planner's value
            val cart = older.filter { it.file.isEmpty() && disk[diskId(it.section, it.key)]?.let { cur -> Leftovers.same(cur, it.value) } == true }
            // the delivery data the ledger does not cover (or covers for another flight than the one it keeps)
            val navMore = navToClear(text, "", ledger, keep, cart.map { diskId(it.section, it.key) }.toSet(), switchAt)
            val teNav = teNavFile(teSave, teTheater)?.let { te ->
                runCatching { te to navToClear(String(te.readBytes(), Charsets.ISO_8859_1), te.name, ledger, keep, emptySet(), switchAt) }.getOrNull()
            }?.takeIf { it.second.isNotEmpty() }
            if (older.isEmpty() && navMore.isEmpty() && teNav == null) return@attempt SwitchCleared(f.name, emptyList(), emptyList())
            val left = ArrayList<String>()
            DevGuard.refusal(f.path)?.let { return@attempt SwitchCleared(f.name, emptyList(), listOf("${f.name} was not cleared: $it")) }
            val cleared = ArrayList<LedgerCleared>()

            if (cart.isNotEmpty() || navMore.isNotEmpty()) {
                val edits = cart.map { CartridgeEdit(it.section, it.key, Leftovers.clearValue(it.section, it.key)) } +
                    navMore.map { CartridgeEdit(it.write.section, it.write.key, it.wrote) }
                val saved = save(callsign, edits, ledgered = false)
                if (saved.error != null) left += "${f.name} was not cleared: ${saved.error}"
                else { cart.forEach { cleared += LedgerCleared(it, Leftovers.clearValue(it.section, it.key), switchAt) }; cleared += navMore }
            }

            // the current flight's TE file: its delivery data, whoever wrote it
            if (teNav != null) {
                val (te, cs) = teNav
                val guard = DevGuard.refusal(te.path)
                if (guard != null) left += "${te.name} was not cleared: $guard"
                else try {
                    val t = String(te.readBytes(), Charsets.ISO_8859_1)
                    val after = DtcEdits.apply(t, cs.map { CartridgeEdit(it.write.section, it.write.key, it.wrote) })
                    if (after != t) { writeSafely(te, after.toByteArray(Charsets.ISO_8859_1)); cleared += cs }
                } catch (e: Throwable) {
                    left += "${te.name} was not cleared: ${reason(e)}"
                }
            }

            // the TEs' own mission files: every one the Planner wrote into, the same rule
            for ((file, ws) in older.filter { it.file.isNotEmpty() }.groupBy { it.file.trim() }) {
                val te = teFile(ws.first().mission?.theater, file)
                if (te == null) { left += "$file was not found in the campaign folder, so it was left as it is."; continue }
                val guard = DevGuard.refusal(te.path)
                if (guard != null) { left += "$file was not cleared: $guard"; continue }
                val text = String(te.readBytes(), Charsets.ISO_8859_1)
                val held = Leftovers.keys(text)
                val mine = ws.filter { held[diskId(it.section, it.key)]?.let { cur -> Leftovers.same(cur, it.value) } == true }
                if (mine.isEmpty()) continue
                val after = DtcEdits.apply(text, mine.map { CartridgeEdit(it.section, it.key, Leftovers.clearValue(it.section, it.key)) })
                if (after != text) {
                    try {
                        writeSafely(te, after.toByteArray(Charsets.ISO_8859_1))
                        mine.forEach { cleared += LedgerCleared(it, Leftovers.clearValue(it.section, it.key), switchAt) }
                    } catch (e: Throwable) {
                        left += "$file was not cleared: ${reason(e)}"
                    }
                }
            }

            if (cleared.isNotEmpty()) {
                val gone = cleared.map { rowId(it.write.file, it.write.section, it.write.key) }.toSet()
                note(name) { l ->
                    val kept = (l.cleared + cleared).let { all -> val last = all.map { it.at }.distinct().sortedDescending().take(5).toSet(); all.filter { it.at in last } }
                    l.copy(writes = l.writes.filterNot { rowId(it.file, it.section, it.key) in gone }, cleared = kept)
                }
                BridgeLog.info("Mode switch / new mission: cleared ${Leftovers.summary(cleared.mapNotNull { itemOf(it.write) })} the Planner had saved (${f.name})")
            }
            SwitchCleared(f.name, cleared.mapNotNull { itemOf(it.write) }, left)
        }

    /**
     * Undo of a switch's reset ([switchAt]): each key it cleared written back to the Planner's value — only where the
     * file still holds the empty value the switch wrote (BMS or the pilot having written it since makes it theirs) — and
     * its ledger row put back. Answers the keys put back and the sentences for what was not. Never throws.
     */
    fun undoSwitch(callsign: String?, switchAt: Long): SwitchCleared =
        attempt({ SwitchCleared(null, emptyList(), listOf("Nothing was put back: $it")) }) {
            val f = fileFor(callsign)?.takeIf { it.isFile } ?: return@attempt SwitchCleared(null, emptyList(), listOf("There is no cartridge to put anything back into."))
            val name = f.nameWithoutExtension
            val todo = ledger(name).cleared.filter { it.at == switchAt && it.undone == 0L }
            if (todo.isEmpty()) return@attempt SwitchCleared(f.name, emptyList(), listOf("There is nothing of that switch left to put back."))
            DevGuard.refusal(f.path)?.let { return@attempt SwitchCleared(f.name, emptyList(), listOf("Nothing was put back: $it")) }
            val left = ArrayList<String>()
            val back = ArrayList<LedgerCleared>()
            fun holdsEmpty(held: Map<String, String>, c: LedgerCleared): Boolean {
                val cur = held[diskId(c.write.section, c.write.key)]
                return if (c.wrote == null) cur == null else cur != null && Leftovers.same(cur, c.wrote)
            }
            for ((file, cs) in todo.groupBy { it.write.file.trim() }) {
                if (file.isEmpty()) {
                    val held = Leftovers.keys(String(f.readBytes(), Charsets.ISO_8859_1))
                    val ok = cs.filter { holdsEmpty(held, it) }
                    if (ok.size < cs.size) left += "${cs.size - ok.size} of the cartridge's settings changed since the switch and were left as they are."
                    if (ok.isEmpty()) continue
                    val saved = save(callsign, ok.map { CartridgeEdit(it.write.section, it.write.key, it.write.value) }, ledgered = false)
                    if (saved.error != null) left += "${f.name}: ${saved.error}" else back += ok
                } else {
                    val te = teFile(cs.first().write.mission?.theater, file)
                    if (te == null) { left += "$file was not found, so nothing was put back into it."; continue }
                    val guard = DevGuard.refusal(te.path)
                    if (guard != null) { left += "$file: $guard"; continue }
                    val text = String(te.readBytes(), Charsets.ISO_8859_1)
                    val held = Leftovers.keys(text)
                    val ok = cs.filter { holdsEmpty(held, it) }
                    if (ok.size < cs.size) left += "${cs.size - ok.size} of $file's settings changed since the switch and were left as they are."
                    if (ok.isEmpty()) continue
                    try {
                        val after = DtcEdits.apply(text, ok.map { CartridgeEdit(it.write.section, it.write.key, it.write.value) })
                        if (after != text) writeSafely(te, after.toByteArray(Charsets.ISO_8859_1))
                        back += ok
                    } catch (e: Throwable) {
                        left += "$file: ${reason(e)}"
                    }
                }
            }
            val stamp = System.currentTimeMillis()
            note(name) { l ->
                val have = l.writes.associateBy { rowId(it.file, it.section, it.key) }
                val restored = back.map { it.write }.filter { w -> (have[rowId(w.file, w.section, w.key)]?.at ?: Long.MIN_VALUE) < w.at }
                val ids = restored.map { rowId(it.file, it.section, it.key) }.toSet()
                l.copy(
                    writes = l.writes.filterNot { rowId(it.file, it.section, it.key) in ids } + restored,
                    cleared = l.cleared.map { c -> if (c.at == switchAt && c.undone == 0L) c.copy(undone = stamp) else c },
                )
            }
            if (back.isNotEmpty()) BridgeLog.info("Mode switch undone: ${Leftovers.summary(back.mapNotNull { itemOf(it.write) })} put back (${f.name})")
            SwitchCleared(f.name, back.mapNotNull { itemOf(it.write) }, left)
        }

    /** A TE's own mission file [iniName] in the campaign folder of [theater] (exact name, no paths), or null. */
    private fun teFile(theater: String?, iniName: String): File? = attempt({ null }) {
        if (iniName.isBlank() || iniName.any { it == '/' || it == '\\' || it == ':' } || iniName.contains("..")) return@attempt null
        val set = Theaters.of(install) ?: return@attempt null
        val t = theater?.let { set.byName(it) } ?: set.current(install.theater) ?: return@attempt null
        set.campaignDir(t)?.listFiles()?.firstOrNull { it.isFile && it.name.equals(iniName.trim(), ignoreCase = true) }
    }

    // ---------------------------------------------------------------- a Tactical Engagement's own mission file

    /**
     * [save], and the steerpoint edits into a Tactical Engagement's own mission file as well (R3-PLAN A13).
     *
     * In a TE, Falcon BMS loads the TE's own `<name>.ini` (beside the `.tac`, in the theater's campaign folder) over the
     * cartridge, and saves targets, lines and PPTs to both (TM §11.3 Note 1 p.204). A Planner save that reached only the
     * cartridge would be undone as soon as the pilot loaded the TE. So when the Planner has a TE open ([te]: the
     * theater and the file, as the campaign browser lists them), the `[STPT]` edits among [edits] — `target_n`,
     * `lineSTPT_n`, `ppt_n` and `wpntarget_n`, the keys BMS keeps per mission ([TE_KEY]) — are written into that file
     * too. The cartridge is saved first, exactly as [save] does; what happened to the TE's file comes back in
     * [CartridgeState.mission], written or not, with the reason.
     *
     * The TE's file is written **in place and without a backup**, as the real WDP writes it — the TEs and trainings that
     * ship with BMS (`TE_BMS_*`, `TR_BMS_*`) included. What decides whether it is written is only which file it is:
     * - **The TE's own file.** It is named exactly as it is in the theater's campaign folder (no paths), it is a `.tac`
     *   or a `.trn`, it is not a campaign start ([CampaignStarts]; the Planner never opens one), and its own header
     *   says it was saved under that name ([CampaignArchive.Header.saveFile]). A file renamed in Explorer keeps its old
     *   name inside, and it is not certain BMS reads the renamed `.ini` for it.
     * - **Not when another save of the same name was saved later** (`Auto Save.cam` beside an older `Auto Save.tac`):
     *   BMS keeps one `<name>.ini` for both, and it then holds that other save's flight.
     * - **Only the edited keys**, each an absolute value, onto the file as it is now ([DtcEdits.apply], Windows' own
     *   rules): every other byte survives, BMS's formatting included (the leading spaces in target names), and saving
     *   twice changes nothing more. A removed key is not carried over.
     * - A TE that has **no mission file** of its own is flown on the cartridge alone, so none is created.
     * - A temporary file beside it, then one atomic move; nothing throws.
     *
     * A campaign gets nothing but the cartridge. WDP also copied the cartridge's steerpoints into a campaign save's
     * mission file, which in 4.38 zeroes BMS's own route there (R3-PLAN D46).
     */
    fun saveTe(callsign: String?, edits: List<CartridgeEdit>, te: CampRef, for_: LedgerMission? = null): CartridgeState {
        val saved = save(callsign, edits, for_)
        val ini = teIniName(te.file)
        val mission =
            if (saved.error != null) MissionIniResult(ini, false, "Not written, because your cartridge was not saved either.")
            else attempt({ MissionIniResult(ini, false, "$ini was not written: $it Only your cartridge was saved.") }) { writeTe(edits, te) }
        if (mission.written) BridgeLog.info("Save to DTC: ${mission.file} written too (TE ${te.file.trim()}, ${te.theater.trim()})")
        else BridgeLog.info("Save to DTC: ${mission.file} not written: ${mission.reason}")
        // the TE's own file in the ledger too (never a leftover: it is that TE's mission file), for the record
        if (mission.written) saved.callsign?.let { cs ->
            val stpt = edits.filter { e -> e.section.trim().equals("STPT", ignoreCase = true) && TE_KEY.matches(e.key.trim()) }
            note(cs) { Leftovers.record(it, cs, stpt, for_, System.currentTimeMillis(), file = mission.file) }
        }
        return saved.copy(mission = mission, ledger = ledger(saved.callsign).takeIf { it.writes.isNotEmpty() })
    }

    private fun writeTe(edits: List<CartridgeEdit>, te: CampRef): MissionIniResult {
        val name = te.file.trim()
        var ini = teIniName(name)
        fun no(why: String) = MissionIniResult(ini, false, "$why Only your cartridge was saved.")

        // which file: one the theater's own campaign folder holds, by its exact name
        if (name.isEmpty()) return no("No Tactical Engagement was named.")
        if (name.any { it == '/' || it == '\\' || it == ':' } || name.contains("..")) {
            return no("\"$name\" is not the name of a file in a campaign folder.")
        }
        val set = Theaters.of(install) ?: return no("Falcon BMS was not found on this PC.")
        val t = set.byName(te.theater) ?: return no("Falcon BMS has no theater called \"${te.theater.trim()}\".")
        val saves = set.saves(t)
        val file = saves.firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: return no("There is no $name in the campaign folder of ${t.name}.")
        val base = file.nameWithoutExtension
        ini = "$base.ini"
        if (CampaignArchive.kindOfName(file.name) == CampKind.CAMPAIGN) {
            return no("${file.name} is a campaign save, and a campaign keeps steerpoints, lines and PPTs in your cartridge only.")
        }

        // whose file: a TE saved by BMS under this very name, never a campaign start (the Planner does not open one)
        val names = CampaignArchive.names(set, t)
        val save = CampaignArchive.read(file, names.takeIf { it.error == null })
        CampaignStarts.why(save)?.let { return no("$it, so it is never written.") }
        val header = save.header
            ?: return no("${file.name} could not be read (${save.error ?: "it has no campaign header"}), so it is not certain which TE $ini belongs to.")
        val savedAs = header.saveFile.trim()
        if (!savedAs.equals(base.trim(), ignoreCase = true)) {
            return no(
                "${file.name} was saved by BMS as \"$savedAs\" and renamed since, so it is not certain BMS reads $ini for it: " +
                    "open it in BMS, SAVE it under the name you want, and open that.",
            )
        }
        val twin = saves.filter { !it.name.equals(file.name, ignoreCase = true) && it.nameWithoutExtension.equals(base, ignoreCase = true) }
            .filter { it.lastModified() > file.lastModified() }.maxByOrNull { it.lastModified() }
        if (twin != null) {
            return no(
                "$ini is also the mission file of ${twin.name}, saved later under the same name, so it holds that save's " +
                    "flight now: SAVE the TE under a name of its own in BMS and open that.",
            )
        }

        // the file itself
        val iniFile = attempt({ null }) { file.parentFile?.listFiles()?.firstOrNull { it.isFile && it.name.equals(ini, ignoreCase = true) } }
            ?: return MissionIniResult(
                ini, false,
                "${file.name} has no mission file of its own ($ini), so BMS flies it on your cartridge alone: there was nothing more to write.",
            )
        ini = iniFile.name
        DevGuard.refusal(iniFile.path)?.let { return no(it) }
        if (!iniFile.canWrite()) {
            return no("$ini is read-only: clear Read-only in its Properties (Windows Explorer) and save again.")
        }
        val bytes = iniFile.readBytes()
        val before = String(bytes, Charsets.ISO_8859_1)
        val hasStpt = before.lineSequence().any { l ->
            val s = l.trim(' ', '\t', '\r')
            s.startsWith("[") && s.substring(1).substringBefore(']').trim().equals("STPT", ignoreCase = true)
        }
        if (!hasStpt) return no("$ini has no [STPT] section, so it does not look like a TE's mission file.")

        // the steerpoint keys only, each an absolute value, onto the file as it is now
        val stpt = edits.filter { e -> e.value != null && e.section.trim().equals("STPT", ignoreCase = true) && TE_KEY.matches(e.key.trim()) }
        if (stpt.isEmpty()) {
            return MissionIniResult(ini, false, "None of the changes were steerpoints, lines, PPTs or weapon targets, so $ini was left as it is.")
        }
        val after = DtcEdits.apply(before, stpt)
        if (after == before) return MissionIniResult(ini, false, "$ini already held these steerpoints: nothing had changed.")
        writeSafely(iniFile, after.toByteArray(Charsets.ISO_8859_1))
        return MissionIniResult(ini, true, null)
    }

    /** `My TE.tac` → `My TE.ini`: the mission file's name, for the answer, before the file is known. */
    private fun teIniName(file: String): String {
        val last = file.trim().substringAfterLast('\\').substringAfterLast('/')
        val base = if ('.' in last) last.substringBeforeLast('.') else last
        return if (base.isBlank()) "the TE's mission file" else "$base.ini"
    }

    // ---------------------------------------------------------------- doing it safely

    /** New bytes beside the file first, then moved over it in one step: a failed write cannot leave half a cartridge. */
    private fun writeSafely(file: File, bytes: ByteArray) {
        val tmp = File(file.parentFile, file.name + ".bmsc-new")
        try {
            tmp.writeBytes(bytes)
            try {
                java.nio.file.Files.move(
                    tmp.toPath(), file.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                java.nio.file.Files.move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            runCatching { if (tmp.exists()) tmp.delete() }
        }
    }


    private fun <T> attempt(onError: (String) -> T, body: () -> T): T = try {
        body()
    } catch (e: Throwable) {
        onError(reason(e))
    }

    private fun reason(e: Throwable): String = when (e) {
        is java.nio.file.AccessDeniedException ->
            "Windows would not let BMS Companion write to ${e.file}. Falcon BMS is usually installed somewhere that " +
                "needs administrator rights: start BMS Companion as administrator, or install BMS somewhere else."
        is java.io.FileNotFoundException -> "${e.message} could not be opened."
        else -> e.message ?: e::class.java.simpleName
    }
}


/**
 * Falcon BMS's campaign starts: the files each theater's campaigns and TEs begin from, which the Planner never opens
 * and nothing in the program ever writes (the pilot's rule: everything may be written "as long as the original start
 * campaign save is not modified"). Every other save — the pilot's own, and the TEs and trainings that ship with BMS
 * (`TE_BMS_*`, `TR_BMS_*`) — opens and saves as it does in WDP. A save is a **start** when
 * - it has an objectives part (`.obj`): every campaign start and TE template — 169 files on a 4.38 install, and not
 *   one save with flights among them;
 * - it has no flights (Korea TvT's `TvT_Start.cam` is a start without objectives);
 * - its name is a start's: `Save<n>`, `Te_New*`, `Instant`. WDP refused `save0`-`save5` and `te_new` by name only, and
 *   missed `Te_New_Nt`, `Instant` and the Falklands' `Save6` and `Save7` (R3-PLAN D41).
 *
 * Each answer is the reason as a phrase that starts with the file's name, for a sentence ("Save0 is one of Falcon
 * BMS's campaign starts"); null for everything else. The campaign browser asks the same question for its listing.
 */
object CampaignStarts {
    private val NAMES: List<Pair<Regex, (String) -> String>> = listOf(
        Regex("Te_New.*", RegexOption.IGNORE_CASE) to { n -> "$n is Falcon BMS's template for a new TE" },
        Regex("Instant", RegexOption.IGNORE_CASE) to { n -> "$n is Falcon BMS's Instant Action start" },
        Regex("Save\\d+", RegexOption.IGNORE_CASE) to { n -> "$n is one of Falcon BMS's campaign starts" },
    )

    /** By the name alone: why [fileName] (with or without its extension) is a start, or null. */
    fun byName(fileName: String): String? {
        val last = fileName.trim().substringAfterLast('\\').substringAfterLast('/')
        val base = (if ('.' in last) last.substringBeforeLast('.') else last).trim()
        return NAMES.firstOrNull { it.first.matches(base) }?.second?.invoke(base)
    }

    /**
     * Why [file] is a start: by its name, by an objectives part ([hasObj]) or by having no flights ([flights]; null when
     * the units could not be counted, which proves nothing either way).
     */
    fun why(file: File, hasObj: Boolean?, flights: Int?): String? {
        byName(file.name)?.let { return it }
        val base = file.nameWithoutExtension
        if (hasObj == true) return "$base is a start (it holds the theater's objectives and no flights)"
        if (flights == 0) return "$base has no flights, so it is a start rather than a mission"
        return null
    }

    /** The same for a save already read: its directory tells the objectives part, its units the flights. */
    fun why(save: CampaignArchive.Save): String? =
        why(save.file, save.directory?.has("obj"), save.uni?.let { save.flights.size })
}
