package com.bmscompanion.desktop.bridge

import com.bmscompanion.app.data.mission.KbFileResult
import com.bmscompanion.app.data.mission.KbOwner
import com.bmscompanion.app.data.mission.LedgerMission
import com.bmscompanion.app.data.mission.Leftovers
import com.bmscompanion.app.data.mission.MissionMode
import com.bmscompanion.app.data.mission.SwitchReset
import java.io.File

/**
 * What a switch of the Mission section's mode resets by itself (docs/DATA-STORES.md, "What a switch resets"), so that
 * no leftover of one mission reaches the next. The rule the pilot asked for: **every** leftover goes at every switch
 * and at every new mission, silently; only what BMS or the pilot changed since the Planner wrote it is never touched.
 *
 * - **The cartridge and the TEs' own mission files** ([CartridgeStore.resetForSwitch]): at a switch, every key the
 *   ledger says the Planner wrote (lines, PPTs, steerpoints and targets, weapon targets, nav offsets) that still holds
 *   the Planner's value, whatever flight it was saved for and whenever — the current flight's too; cleared the way
 *   Clear clears them, kept in the ledger for the Undo route.
 * - **The cockpit kneeboard pages** (unchanged): to EZBoards mode, every half Upd Kneeboard made; to WDP mode, every
 *   half EZBoards or html_brief made — each given Falcon BMS's own shipped page back
 *   ([KneeboardPrint.putShippedHalves]) when it was written before the current flight began ([start]: printed or
 *   opened in the Planner), only where the theater ships one; no file is ever created, each keeps its format.
 * - **WDP mode's snapshot**: kept on a switch to EZBoards mode (it is not shown there); on a switch to WDP mode,
 *   discarded when it is not the current flight's (the Planner's open flight on the switching device, `for=`, else the
 *   printed briefing's), so the Mission section says "not populated yet" instead.
 *
 * The switch never asks first and no device shows anything of it (the pilot asked for no notice); the summary
 * ([SwitchReset]) is still served with the mode for [SHOWN_MS] and the Undo route still answers, for compatibility.
 * Nothing here throws; a developer run writes only into a copy ([DevGuard]).
 *
 * **A new mission** (1.3.8) clears the cartridge and the TEs' files by itself: a PRINT of another flight in EZBoards
 * mode ([printed], from the tick, and once at start), another flight opened in the Planner in WDP mode ([opened],
 * `POST /api/mission/opened`, which also discards another flight's snapshot) or populated ([opened] from the
 * Populate). It clears everything the Planner saved for **any other flight**, whenever (a key saved with no flight
 * known is kept), and never touches the cockpit pages.
 */
object ModeSwitchReset {
    /** How long `/api/info` keeps carrying the last switch's summary, so a device that looks a little later sees it too. */
    const val SHOWN_MS = 30 * 60_000L

    @Volatile private var last: SwitchReset? = null

    /** The last switch's summary while the mode is still the one it switched to, and not older than [SHOWN_MS]. */
    fun shown(mode: String): SwitchReset? = last?.takeIf { it.to == mode && System.currentTimeMillis() - it.at in 0 until SHOWN_MS }

    /** Forgets the last summary (a developer check). */
    internal fun forget() { last = null; lastPrinted = null; lastOpened = null }

    /**
     * Resets what the switch to [to] at [at] owes the pilot: the device's Planner flight is [planner] (`for=`; null when
     * it has none open). Called by [MissionSource] before the mode changes. Never throws.
     */
    fun run(to: String, at: Long, planner: LedgerMission?): SwitchReset {
        val r = try {
            reset(to, at, planner)
        } catch (e: Throwable) {
            BridgeLog.warn("Mode switch: the reset did not run: ${e.message}")
            SwitchReset(at = at, to = to, left = listOf("Nothing was reset: ${e.message ?: e.javaClass.simpleName}"))
        }
        last = r
        // the flight the switch reset for is the one a new-mission check compares the next PRINT or Open with
        if (to == MissionMode.EZBOARDS) r.now?.let { lastPrinted = it } else planner?.let { lastOpened = it }
        return r
    }

    /** Undo of switch [at]'s cartridge keys; the summary as it then is, or null when that switch is not the last. */
    fun undo(at: Long): SwitchReset? {
        val r = last?.takeIf { it.at == at } ?: return null
        if (!r.undo) return r
        val callsign = r.cartridge?.substringBeforeLast('.')
        val back = try {
            Bridge.cartridge.undoSwitch(callsign, at)
        } catch (e: Throwable) {
            CartridgeStore.SwitchCleared(r.cartridge, emptyList(), listOf("Nothing was put back: ${e.message}"))
        }
        val said = (if (back.keys.isNotEmpty()) "Put back ${Leftovers.summary(back.keys)}. LOAD the DTC in BMS for the jet to have them again." else "Nothing was put back.") +
            back.left.joinToString("") { " $it" }
        val next = r.copy(undo = false, undone = System.currentTimeMillis(), undoMessage = said)
        last = next
        return next
    }

    // ------------------------------------------------------------------------------------------------ a new mission

    /** The flight the last new-mission check was made for: EZBoards mode's printed flight, WDP mode's opened one. */
    @Volatile private var lastPrinted: LedgerMission? = null
    @Volatile private var lastOpened: LedgerMission? = null

    /** Forgets which flights were last looked at, as a restart of the program does (a developer check). */
    internal fun forgetFlights() { lastPrinted = null; lastOpened = null }

    /**
     * EZBoards mode: BMS printed a briefing (the tick saw a new `briefing.txt`), or the program started with one. A
     * **new mission** when it is another flight than the last print looked at — a PRINT of the same flight again
     * clears nothing. Clears what the Planner saved for any other flight ([newMission]); the answer, or null when
     * nothing was cleared. Never throws.
     */
    @Synchronized
    fun printed(): SwitchReset? = try {
        val now = LedgerMission.ofBriefing(Bridge.currentBriefing(), Bridge.printedAt(), Bridge.install.theater)
        val prev = lastPrinted
        if (now != null) lastPrinted = now
        if (now == null || (prev != null && prev.sameFlight(now))) null
        else {
            // the TE the printed flight is in (BMS's mission file beside its save, when provably that flight's): its
            // delivery data goes with the cartridge's
            val teSave = safe(null) { MissionDtcFile.current()?.save?.takeIf { it.isNotBlank() } }
            newMission(MissionMode.EZBOARDS, now, null, teSave)
        }
    } catch (e: Throwable) {
        BridgeLog.warn("New mission: the check after PRINT did not run: ${e.message}")
        null
    }

    /**
     * WDP mode: the Planner on a device opened [planner] (Open mission… or Pick a flight; `POST /api/mission/opened`),
     * or is populating it. A **new mission** when it is another flight than the last one opened: what the Planner saved
     * for any other flight is cleared from the cartridge and the TEs' files, and WDP mode's snapshot is discarded when
     * it is another flight's, so nothing of the last mission stays on the Mission map, the VR boards or in their attack
     * ([discardSnapshot] false for a Populate, which replaces it anyway). The answer, or null when nothing was done.
     * Never throws.
     */
    @Synchronized
    fun opened(planner: LedgerMission, discardSnapshot: Boolean = true): SwitchReset? = try {
        if (MissionSource.mode() != MissionMode.WDP) null
        else {
            val prev = lastOpened
            lastOpened = planner
            if (prev != null && prev.sameFlight(planner)) null
            else {
                var dropped: String? = null
                if (discardSnapshot) {
                    val p = safe(null) { MissionSource.populated() }
                    val pm = p?.let { LedgerMission.ofPopulated(it) }
                    if (p != null && pm != null && !pm.sameFlight(planner) && MissionSource.discardSnapshot()) dropped = describe(pm, p.at)
                }
                newMission(MissionMode.WDP, planner, dropped, planner.save)
            }
        }
    } catch (e: Throwable) {
        BridgeLog.warn("New mission: the check after Open mission did not run: ${e.message}")
        null
    }

    /**
     * A new mission's reset: the cartridge and the TEs' own mission files — everything the Planner saved for **any other
     * flight** than [now] that still holds its value, whenever it was saved — never the cockpit pages (the mode's own
     * tool rewrites them). Kept as the last summary (served, drawn by no device; its Undo route kept), only when
     * something was done.
     */
    private fun newMission(mode: String, now: LedgerMission, dropped: String?, teSave: String?): SwitchReset? {
        val at = maxOf(System.currentTimeMillis(), (last?.at ?: 0L) + 1)
        val cart = Bridge.cartridge.resetForSwitch(null, now, at, teSave, now.theater?.takeIf { it.isNotBlank() } ?: Bridge.install.theater)
        // what a Planner before 1.3.8 posted by itself to the VR boards' map goes with the mission (never drawn now)
        runCatching { Bridge.forgetPostedAttack() }
        val done = ArrayList<String>()
        if (dropped != null) done += "WDP mode's last Populate ($dropped) was for another flight and was discarded: populate ${now.label} from the Planner."
        if (cart.keys.isNotEmpty()) {
            val from = cart.keys.groupBy { it.from?.label }.maxByOrNull { it.value.size }?.value?.firstOrNull()?.from
            val files = cart.keys.map { it.file.ifEmpty { cart.file ?: "the cartridge" } }.distinct().joinToString(" and ")
            done += "Cleared ${Leftovers.summary(cart.keys)} the Planner saved for ${from?.label ?: "another flight"} from $files. LOAD the DTC in BMS."
        }
        if (done.isEmpty()) return null
        BridgeLog.info("New mission (${now.label}): " + (done + cart.left).joinToString(" "))
        val r = SwitchReset(
            at = at, to = mode, now = now, keys = cart.keys, cartridge = cart.file, snapshot = dropped,
            done = done, left = cart.left, undo = cart.keys.isNotEmpty(), kind = SwitchReset.MISSION,
        )
        last = r
        return r
    }

    // ---------------------------------------------------------------------------------------------------------------

    private fun reset(to: String, at: Long, planner: LedgerMission?): SwitchReset {
        val install = Bridge.install
        val theater = install.theater
        val printed = safe(0L) { Bridge.printedAt() }
        val brief = safe(null) { LedgerMission.ofBriefing(Bridge.currentBriefing(), printed, theater) }
        // when a flight began: what the cockpit pages are judged by (a page written since is that flight's)
        fun start(m: LedgerMission?): Long {
            if (m == null) return 0L
            val times = listOfNotNull(
                m.printed.takeIf { it > 0 }, m.opened.takeIf { it > 0 },
                brief?.takeIf { b -> b.printed > 0 && b.sameFlight(m) }?.printed,
            )
            return times.minOrNull() ?: 0L
        }
        val done = ArrayList<String>()
        val left = ArrayList<String>()

        // ---- WDP mode's snapshot
        var dropped: String? = null
        var kept: LedgerMission? = null
        if (to == MissionMode.WDP) {
            val p = safe(null) { MissionSource.populated() }
            val pm = p?.let { LedgerMission.ofPopulated(it) }
            val cur = planner ?: brief
            if (p != null && pm != null) {
                // not the current flight's (whenever it was taken): discarded; no current flight known: kept
                if (cur != null && !pm.sameFlight(cur)) {
                    if (MissionSource.discardSnapshot()) {
                        dropped = describe(pm, p.at)
                        done += "WDP mode's last Populate ($dropped) was for another flight and was discarded: populate ${cur.label} from the Planner."
                    } else left += "WDP mode's last Populate (${describe(pm, p.at)}) is another flight's but could not be discarded."
                } else kept = pm
            }
        }
        val now = if (to == MissionMode.WDP) planner ?: kept ?: brief else brief
        val s = start(now)

        // ---- the cartridge and the TEs' own mission files: every leftover of the Planner's, whatever its flight
        // the current flight's TE file too, for its delivery data (the printed flight's TE when it is the briefing's)
        val teSave = now?.save?.takeIf { it.isNotBlank() }
            ?: if (now != null && now === brief) safe(null) { MissionDtcFile.current()?.save?.takeIf { it.isNotBlank() } } else null
        val cart = Bridge.cartridge.resetForSwitch(null, null, at, teSave, now?.theater?.takeIf { it.isNotBlank() } ?: theater)
        runCatching { Bridge.forgetPostedAttack() }
        if (cart.keys.isNotEmpty()) {
            val files = cart.keys.map { it.file.ifEmpty { cart.file ?: "the cartridge" } }.distinct().joinToString(" and ")
            done += "Cleared ${Leftovers.summary(cart.keys)} the Planner had saved from $files. LOAD the DTC in BMS."
        }
        left += cart.left

        // ---- the cockpit kneeboard pages
        val pages = ArrayList<String>()
        val base = install.baseDir
        val gone = if (to == MissionMode.EZBOARDS) setOf(KbOwner.COMPANION) else setOf(KbOwner.EZBOARDS, KbOwner.HTMLBRIEF)
        val whose = if (to == MissionMode.EZBOARDS) "Upd Kneeboard's" else "EZBoards' or html_brief's"
        if (base != null) {
            val place = safe(null) { KneeboardPrint.place(File(base), theater) }
            if (place != null && place.error == null) {
                val noOriginal = ArrayList<Int>()
                val current = ArrayList<Int>()
                val written = ArrayList<String>()
                for (n in 1..KneeboardPrint.PAGES) {
                    val o = KneeboardPrint.ownersOf(place, n) ?: continue
                    val sides = (0..1).filter { (if (it == 0) o.left else o.right) in gone }.toSet()
                    if (sides.isEmpty()) continue
                    val f = place.page(n) ?: continue
                    if (now == null || s <= 0L || safe(Long.MAX_VALUE) { f.lastModified() } >= s) { current += n; continue }
                    if (place.shippedPage(n) == null) { noOriginal += n; continue }
                    val guard = DevGuard.refusal(f.path)
                    if (guard != null) { left += "Page $n was left: $guard"; continue }
                    val r = KneeboardPrint.putShippedHalves(place, n, sides)
                    when (r.status) {
                        KbFileResult.WRITTEN -> {
                            val h = sides.sorted().joinToString(" and ") { if (it == 0) "left" else "right" }
                            pages += "page $n, $h"
                            written += "$n ($h)"
                            BridgeLog.info("Mode switch: BMS's own page put back on ${r.file} ($h)" + (r.reason?.let { " — $it" } ?: ""))
                        }
                        KbFileResult.UNCHANGED -> Unit
                        else -> left += "Page $n was left as it is: ${r.reason ?: "it could not be written."}"
                    }
                }
                if (written.isNotEmpty()) done += "Put Falcon BMS's own cockpit kneeboard back on ${plural(written, "page", "pages")}: " +
                    (if (written.size == 1) "it" else "they") + " held $whose pages from an earlier flight."
                if (noOriginal.isNotEmpty()) left += "${plural(noOriginal.map { "$it" }, "Page", "Pages")} still hold $whose pages: left, as Falcon BMS ships no original of " +
                    "them for ${place.theater?.name ?: "this theater"} to put back."
                if (current.isNotEmpty()) left += "${plural(current.map { "$it" }, "Page", "Pages")} ($whose) " +
                    (if (now == null || s <= 0L) "kept: which flight is current is not known." else "kept: made for ${now.label}.")
            }
        }

        if (done.isEmpty()) done += "Nothing was left to clear."
        BridgeLog.info("Mode switch to $to: " + (done + left).joinToString(" "))
        return SwitchReset(
            at = at, to = to, now = now, keys = cart.keys, cartridge = cart.file, pages = pages, snapshot = dropped,
            done = done, left = left, undo = cart.keys.isNotEmpty(),
        )
    }

    private fun plural(items: List<String>, one: String, many: String): String = when (items.size) {
        0 -> many
        1 -> "$one ${items[0]}"
        else -> "$many " + items.dropLast(1).joinToString(", ") + " and " + items.last()
    }

    private fun describe(m: LedgerMission, at: Long): String =
        m.label + Leftovers.date(at).let { if (it.isEmpty()) "" else ", populated $it" }

    private inline fun <T> safe(fallback: T, body: () -> T): T = try { body() } catch (_: Throwable) { fallback }
}
