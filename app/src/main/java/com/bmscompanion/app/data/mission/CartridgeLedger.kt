package com.bmscompanion.app.data.mission

import kotlinx.serialization.Serializable

// What the Planner's Save to DTC wrote into the pilot's cartridge, and for which mission — so that what it wrote for
// one mission and is still in the cartridge when the next is flown can be told apart from this mission's own
// (docs/DATA-STORES.md, "Starting the next mission"; docs/PROTOCOL.md, "The cartridge ledger").
//
// Falcon BMS keeps PPTs, lines and precision targets in `User/Config/<callsign>.ini` until something overwrites them
// (UM §5.1.9-5.1.11), and the Planner writes only the keys the pilot edited. So a line drawn for Tuesday's strike
// stays in the cartridge for Wednesday's CAP, in either mode, and nothing in the file says whose it is. The PC keeps a
// ledger beside the Planner's other files (`<BMS>\User\BMS Companion Planner\Ledger\<callsign>.json`): one row per key
// the Planner wrote, with the value and the mission it was written for. A key is a **leftover** when the mission now is
// another flight than the ledger's, and the cartridge still holds exactly the value the Planner wrote — BMS or the
// pilot having changed it since makes it theirs, and it is never touched. Keys BMS wrote are not in the ledger and
// cannot be dated; nothing is said about them — except a **copy** of one of the Planner's items: BMS's DTC memory keeps
// what the pilot last LOADed and writes it into the cartridge and the next save's mission file, so a PPT, target or
// weapon target the ledger still names (its live rows, or what the last few resets cleared) for another flight turning
// up again is the Planner's, wherever it is (1.3.9, [Leftovers.sameItem]); the rest is "Start each opened mission with
// clean lines, PPTs and Open 1/2 steerpoints" ([PlannerPcSettings]). No log of past values is kept. Since 1.3.8 a leftover is never shown or asked
// about: the PC clears it by itself when a new mission begins or the mode is switched ([SwitchReset]).

/**
 * Which mission something was written for, or which mission is being flown now. The flight's [callsign] ("Cyborg6"),
 * its [packageId] (the number the briefing prints) and [flightId] (the campaign's flight number, the briefing's
 * package table), the [theater] (its definition's name, "Korea KTO"), the [save] it was planned from ("Auto Save.cam";
 * null for a printed briefing), the [seat] 0-3, when BMS printed the briefing it came from ([printed], ms) and when the
 * Planner opened it ([opened], ms). Every field may be missing; [sameFlight] only compares what both have.
 */
@Serializable
data class LedgerMission(
    val callsign: String = "",
    val packageId: Int? = null,
    val flightId: Int? = null,
    val theater: String? = null,
    val save: String? = null,
    val seat: Int? = null,
    val printed: Long = 0,
    val opened: Long = 0,
) {
    /**
     * The same flight: callsigns alike (letters and digits, any case), and the package, flight number, theater and
     * save alike wherever both say. With either callsign unknown it cannot be told, which counts as the same — a
     * leftover is only ever named when the two missions are provably different.
     */
    fun sameFlight(o: LedgerMission): Boolean {
        val a = norm(callsign)
        val b = norm(o.callsign)
        if (a.isEmpty() || b.isEmpty()) return true
        if (a != b) return false
        if (packageId != null && o.packageId != null && packageId != o.packageId) return false
        if (flightId != null && o.flightId != null && flightId != o.flightId) return false
        if (!theater.isNullOrBlank() && !o.theater.isNullOrBlank() && norm(theater) != norm(o.theater)) return false
        if (!save.isNullOrBlank() && !o.save.isNullOrBlank() && norm(save) != norm(o.save)) return false
        return true
    }

    /** "Cobra1, package 1234 (Auto Save.cam)" */
    val label: String
        get() = buildString {
            append(callsign.trim().ifEmpty { "another flight" })
            packageId?.let { append(", package $it") }
            save?.trim()?.takeIf { it.isNotEmpty() }?.let { append(" ($it)") }
        }

    companion object {
        private fun norm(s: String?) = s.orEmpty().lowercase().filter { it.isLetterOrDigit() }

        /**
         * The flight a printed briefing is for, as the PC keys it (`CampaignFiles.briefKey`): the overview's callsign,
         * the package number, and the flight number of the package table's row with that callsign. Null for a
         * briefing made from a save (`origin` set) or with no callsign.
         */
        fun ofBriefing(b: Briefing?, printed: Long, theater: String?): LedgerMission? {
            if (b == null) return null
            val cs = b.overview.flight?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val pkg = b.overview.packageId?.trim()?.let { Regex("\\d+").find(it)?.value?.toIntOrNull() }
            val own = b.`package`.firstOrNull { it.callsign.equals(cs, ignoreCase = true) }
            return LedgerMission(
                callsign = cs, packageId = pkg, flightId = own?.flightId?.trim()?.toIntOrNull(),
                theater = theater?.trim()?.takeIf { it.isNotEmpty() }, printed = printed,
            )
        }

        /** WDP mode's snapshot: the flight Populate from Planner took. */
        fun ofPopulated(p: Populated?): LedgerMission? {
            if (p == null) return null
            val cs = p.callsign?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return LedgerMission(
                callsign = cs, packageId = p.packageId?.trim()?.toIntOrNull(), theater = p.theater.trim().takeIf { it.isNotEmpty() },
                save = p.save.trim().takeIf { it.isNotEmpty() }, seat = p.seat, opened = p.at,
            )
        }

        /** A save's flight, as the Planner plans it. */
        fun ofFlight(theater: String?, save: String?, f: CampFlight, seat: Int, opened: Long): LedgerMission =
            LedgerMission(
                callsign = f.row.callsign.trim(), packageId = f.packageNumber.takeIf { it > 0 }, flightId = f.row.number.takeIf { it > 0 },
                theater = theater?.trim()?.takeIf { it.isNotEmpty() }, save = save?.trim()?.takeIf { it.isNotEmpty() }, seat = seat, opened = opened,
            )
    }
}

/**
 * One key the Planner's Save to DTC wrote: into the cartridge ([file] empty) or into a TE's own mission file ([file]
 * = its name, "My TE.ini"), the value it wrote, when ([at], ms) and for which [mission] (null from a device too old to
 * say, which never makes a leftover).
 */
@Serializable
data class LedgerWrite(
    val file: String = "",
    val section: String = "",
    val key: String = "",
    val value: String = "",
    val at: Long = 0,
    val mission: LedgerMission? = null,
)

/**
 * A key a switch of mode cleared ([SwitchReset]): the Planner's [write] as it was in the ledger, what the switch put
 * in its place ([wrote]: BMS's empty value; null = the key was removed), the switch's time [at] (the id **Undo** takes)
 * and when it was undone ([undone], 0 = not). Undo writes [write]'s value back only where the file still holds [wrote].
 */
@Serializable
data class LedgerCleared(
    val write: LedgerWrite = LedgerWrite(),
    val wrote: String? = null,
    val at: Long = 0,
    val undone: Long = 0,
)

/**
 * The ledger of one pilot's cartridge: the last write the Planner made to each key, and what the last few switches of
 * mode cleared ([cleared], for their Undo; the last five resets). Those two are also the only memory a **copy BMS made**
 * of a PPT, target or weapon target is recognised by ([Leftovers.sameItem]): no log of past values is kept (a 1.3.9 test
 * build's `history` field is passed over when an old file is read).
 */
@Serializable
data class CartridgeLedger(
    val callsign: String = "",
    val writes: List<LedgerWrite> = emptyList(),
    val cleared: List<LedgerCleared> = emptyList(),
)

/**
 * A key of the cartridge the Planner wrote for an earlier mission and still holding that value. [kind] is
 * [Leftovers.STEERPOINT] (`target_n`: STPT [slot]), [Leftovers.WEAPON] (`wpntarget_n`), [Leftovers.PPT] (`ppt_n`: PPT
 * [slot] 56-70), [Leftovers.LINE] (`lineSTPT_n`: [slot] the index 0-23, on [line] 1-4) or [Leftovers.NAV] (a `[NAV
 * OFFSETS]` key: VIP/VRP and their offsets, which the Mission section draws as an attack in WDP mode). [x]/[y] are the
 * position the value holds (theater feet, north and east; 0 for a nav offset), so a map can match the point it draws.
 */
@Serializable
data class Leftover(
    val section: String = "",
    val key: String = "",
    val value: String = "",
    val kind: String = "",
    val slot: Int = 0,
    val line: Int? = null,
    val x: Double = 0.0,
    val y: Double = 0.0,
    val at: Long = 0,
    val from: LedgerMission? = null,
    /** "" = the cartridge; else the TE's own mission file it is in ("My TE.ini"), which only a switch of mode clears */
    val file: String = "",
)

/**
 * The leftovers in one pilot's cartridge for the mission [now]: [items], and [sentence] — "2 lines and 3 PPTs the
 * Planner saved for Cobra1, package 1234 on 3 Oct 21:40" — for a page to show. [from] is the mission most of them were
 * written for, [at] the latest write.
 */
@Serializable
data class CartridgeLeftovers(
    val callsign: String = "",
    val items: List<Leftover> = emptyList(),
    val from: LedgerMission? = null,
    val at: Long = 0,
    val now: LedgerMission? = null,
    val sentence: String = "",
)

/** The ledger's rules, shared by the PC (which keeps the ledger and clears) and every device (which draws). */
object Leftovers {
    const val STEERPOINT = "steerpoint"
    const val WEAPON = "weapon"
    const val PPT = "ppt"
    const val LINE = "line"
    const val NAV = "nav"

    private val SLOT = Regex("(target|wpntarget|ppt|lineSTPT)_(\\d+)", RegexOption.IGNORE_CASE)

    /** What a cartridge key is as a mission item, or null for a setting (radios, IFF, EWS, MFD …), which never is one. */
    fun kindOf(section: String, key: String): Pair<String, Int>? {
        val s = section.trim()
        if (s.equals("NAV OFFSETS", ignoreCase = true)) return NAV to 0
        if (!s.equals("STPT", ignoreCase = true)) return null
        val m = SLOT.matchEntire(key.trim()) ?: return null
        val n = m.groupValues[2].toIntOrNull() ?: return null
        return when (m.groupValues[1].lowercase()) {
            "target" -> STEERPOINT to n + 1
            "wpntarget" -> WEAPON to n + 1
            "ppt" -> PPT to n + 56
            "linestpt" -> LINE to n
            else -> null
        }
    }

    private fun fields(v: String): List<String> = v.split(',').map { it.trim() }.dropLastWhile { it.isEmpty() }

    /** A value that places nothing: no position, a nav offset of zeros, Modesel none. */
    fun isEmpty(kind: String, value: String): Boolean {
        val f = fields(value)
        if (f.isEmpty()) return true
        return if (kind == NAV) f.all { it.equals("none", true) || (it.toDoubleOrNull() ?: 1.0) == 0.0 }
        else (f.getOrNull(0)?.toDoubleOrNull() ?: 0.0) == 0.0 && (f.getOrNull(1)?.toDoubleOrNull() ?: 0.0) == 0.0
    }

    /**
     * The same value, as BMS may rewrite it: field by field, numbers to half a foot (BMS writes six decimals where the
     * Planner may write fewer), words without regard to case or spaces.
     */
    fun same(a: String, b: String): Boolean {
        val x = fields(a)
        val y = fields(b)
        if (x.size != y.size) return false
        for (i in x.indices) {
            val p = x[i].toDoubleOrNull()
            val q = y[i].toDoubleOrNull()
            if (p != null && q != null) { if (kotlin.math.abs(p - q) > 0.5) return false }
            else if (!x[i].equals(y[i], ignoreCase = true)) return false
        }
        return true
    }

    /** How far a copy's point may sit from the Planner's: BMS keeps positions as floats (a quarter foot at 2,000,000 ft). */
    const val COPY_FT = 1.0

    private val SPACES = Regex("\\s+")
    private fun norm(s: String) = s.trim().replace(SPACES, " ").lowercase()

    /**
     * The same mission item, as Falcon BMS copies it from its DTC memory into another file (1.3.9): [a] and [b] are two
     * values of a `[STPT]` key of [kind], and both place something at the same point. BMS writes a line point back
     * exactly as the Planner wrote it ("%f, %f, %f"), but a PPT in its own way (seen on a pilot's install: the height
     * written as 0, the range as a float, 164055.1 → 164055.125000, a north of 927309.5625 → 927309.5), so the values are
     * compared as numbers, never as text: north and east within [COPY_FT] (the height is never compared); a PPT's range
     * within a foot and its code; a target's or weapon target's action −1 (a precision target: BMS's route, action 0 and
     * up, never matches) and its name (spaces and case aside; one a prefix of the other from 15 characters, in case BMS
     * shortens a long name). Nav offsets are never compared here.
     */
    fun sameItem(kind: String, a: String, b: String): Boolean {
        val x = a.split(',')
        val y = b.split(',')
        fun num(f: List<String>, i: Int) = f.getOrNull(i)?.trim()?.toDoubleOrNull()
        val ax = num(x, 0) ?: return false
        val ay = num(x, 1) ?: return false
        val bx = num(y, 0) ?: return false
        val by = num(y, 1) ?: return false
        if (ax == 0.0 && ay == 0.0) return false
        if (kotlin.math.abs(ax - bx) > COPY_FT || kotlin.math.abs(ay - by) > COPY_FT) return false
        fun rest(f: List<String>) = norm(f.drop(4).joinToString(","))
        return when (kind) {
            LINE -> true
            PPT -> {
                val ar = num(x, 3) ?: return false
                val br = num(y, 3) ?: return false
                kotlin.math.abs(ar - br) <= maxOf(1.0, kotlin.math.abs(ar) * 1e-6) && rest(x).trimEnd(',').trim() == rest(y).trimEnd(',').trim()
            }
            STEERPOINT, WEAPON -> {
                if (num(x, 3) != -1.0 || num(y, 3) != -1.0) return false
                val p = rest(x)
                val q = rest(y)
                p == q || (minOf(p.length, q.length) >= 15 && (p.startsWith(q) || q.startsWith(p)))
            }
            else -> false
        }
    }

    /** What Clear writes over a leftover: BMS's own empty slot, the zero nav offset, or (an offset aim point) nothing. */
    fun clearValue(section: String, key: String): String? {
        val (kind, _) = kindOf(section, key) ?: return null
        val k = key.trim().uppercase()
        return when (kind) {
            STEERPOINT, WEAPON -> "0.000000, 0.000000, 0.000000, -1, Not set"
            PPT -> "0.000000, 0.000000, 0.000000, 0.000000,"
            LINE -> "0.000000, 0.000000, 0.000000"
            else -> when {
                k == "MODESEL" -> "none"
                k.startsWith("OA") -> null
                else -> "0,0,0,0"
            }
        }
    }

    /**
     * The edits that take every delivery setting out of [text] (1.3.8, at a new mission or a switch of mode): each
     * `[NAV OFFSETS]` key that places something back to its empty value — Modesel none, VIP/VIPPUP/VRP/VRPPUP zeros,
     * the offset aim points (OA1-n/OA2-n) removed — whoever wrote it. Empty when there is nothing to clear.
     */
    fun navClearEdits(text: String?): List<CartridgeEdit> =
        keys(text).entries
            .filter { (id, v) -> id.startsWith("NAV OFFSETS\u0000") && (id.substringAfter('\u0000').startsWith("OA") || !isEmpty(NAV, v)) }
            .map { (id, _) -> id.substringAfter('\u0000').let { k -> CartridgeEdit("NAV OFFSETS", k, clearValue("NAV OFFSETS", k)) } }

    /** Every key of an INI text (first section of a name, first key of a name in it), by section and key in capitals. */
    fun keys(text: String?): Map<String, String> {
        val out = HashMap<String, String>()
        if (text == null) return out
        val seen = HashSet<String>()
        var section: String? = null
        var counting = false
        for (raw in text.split('\n')) {
            val line = raw.trimEnd('\r')
            val s = line.trim(' ', '\t')
            if (s.startsWith("[")) {
                val name = s.substring(1).substringBefore(']').trim(' ', '\t')
                section = name
                counting = seen.add(name.uppercase())
                continue
            }
            if (!counting || section == null || s.startsWith(";")) continue
            val eq = line.indexOf('=')
            if (eq < 0) continue
            val id = id(section, line.substring(0, eq))
            if (id !in out) out[id] = line.substring(eq + 1).trim(' ', '\t')
        }
        return out
    }

    private fun id(section: String, key: String) = section.trim().uppercase() + "\u0000" + key.trim().uppercase()

    /**
     * The ledger after a save: each edit the PC wrote into [file] ("" = the cartridge) recorded with [mission] at [at];
     * a removed key leaves the ledger. Edits from a device that did not say the mission are recorded without one.
     */
    fun record(ledger: CartridgeLedger, callsign: String, edits: List<CartridgeEdit>, mission: LedgerMission?, at: Long, file: String = ""): CartridgeLedger {
        val rows = LinkedHashMap<String, LedgerWrite>()
        for (w in ledger.writes) rows[w.file.uppercase() + "\u0001" + id(w.section, w.key)] = w
        for (e in edits) {
            if (e.section.isBlank() || e.key.isBlank()) continue
            val k = file.uppercase() + "\u0001" + id(e.section, e.key)
            if (e.value == null) rows.remove(k)
            else rows[k] = LedgerWrite(file, e.section.trim(), e.key.trim(), e.value.trim(), at, mission)
        }
        return CartridgeLedger(callsign.ifBlank { ledger.callsign }, rows.values.toList(), ledger.cleared)
    }

    /**
     * The leftovers in [cartridgeText] for the mission [now]: each mission item the ledger says the Planner wrote for
     * another flight, which the cartridge still holds at that value and which places something. Null when there are
     * none, when [now] is unknown (nothing can be called a leftover then) or there is no cartridge.
     */
    fun find(ledger: CartridgeLedger?, cartridgeText: String?, now: LedgerMission?): CartridgeLeftovers? {
        if (ledger == null || cartridgeText == null || now == null) return null
        val disk = keys(cartridgeText)
        val items = ArrayList<Leftover>()
        for (w in ledger.writes) {
            if (w.file.isNotEmpty()) continue
            val m = w.mission ?: continue
            if (m.sameFlight(now)) continue
            val (kind, slot) = kindOf(w.section, w.key) ?: continue
            if (isEmpty(kind, w.value)) continue
            val cur = disk[id(w.section, w.key)] ?: continue
            if (!same(cur, w.value)) continue
            val f = fields(w.value)
            val pos = kind != NAV
            items += Leftover(
                section = w.section, key = w.key, value = w.value, kind = kind, slot = slot,
                line = if (kind == LINE && slot in 0..23) slot / 6 + 1 else null,
                x = if (pos) f.getOrNull(0)?.toDoubleOrNull() ?: 0.0 else 0.0, y = if (pos) f.getOrNull(1)?.toDoubleOrNull() ?: 0.0 else 0.0,
                at = w.at, from = m,
            )
        }
        if (items.isEmpty()) return null
        val from = items.groupBy { it.from?.label }.maxByOrNull { it.value.size }?.value?.firstOrNull()?.from
        val at = items.maxOf { it.at }
        return CartridgeLeftovers(ledger.callsign, items, from, at, now, sentence(items, from, at))
    }

    /** The edits that clear [left]: each key back to BMS's empty value (an offset aim point removed). */
    fun clearEdits(left: CartridgeLeftovers): List<CartridgeEdit> =
        left.items.map { CartridgeEdit(it.section, it.key, clearValue(it.section, it.key)) }

    /** "2 lines, 3 PPTs and the nav offsets (VIP/VRP)" */
    fun summary(items: List<Leftover>): String {
        val parts = ArrayList<String>()
        fun n(c: Int, one: String, many: String) { if (c > 0) parts += if (c == 1) "1 $one" else "$c $many" }
        n(items.filter { it.kind == LINE }.mapNotNull { it.line }.distinct().size, "line", "lines")
        n(items.count { it.kind == PPT }, "PPT", "PPTs")
        n(items.count { it.kind == STEERPOINT }, "steerpoint", "steerpoints")
        n(items.count { it.kind == WEAPON }, "weapon target", "weapon targets")
        if (items.any { it.kind == NAV }) parts += "the nav offsets (VIP/VRP)"
        return when (parts.size) {
            0 -> "nothing"
            1 -> parts[0]
            else -> parts.dropLast(1).joinToString(", ") + " and " + parts.last()
        }
    }

    /** "16 Oct 21:40" in this device's time, or "" */
    fun date(ms: Long): String =
        if (ms <= 0) "" else runCatching { java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.US).format(java.util.Date(ms)) }.getOrDefault("")

    fun sentence(items: List<Leftover>, from: LedgerMission?, at: Long): String =
        "The cartridge still holds ${summary(items)} the Planner saved for ${from?.label ?: "an earlier mission"}" +
            date(at).let { if (it.isEmpty()) "" else " on $it" } + "."
}

/**
 * The Planner's settings that the PC keeps, because the PC acts on them (`GET`/`POST /api/planner/settings`, 1.3.9;
 * shown in the Planner's Settings window on every device). [cleanOpened]: **Start each opened mission with clean lines
 * and PPTs** — when the Planner plans another flight (Open mission… or Pick a flight, `POST /api/mission/opened?clean=1`)
 * the PC takes every HSD line (`lineSTPT_0…23`) and every PPT (`ppt_0…14`) out of the cartridge and out of the campaign
 * mission file BMS's LOAD reads for that flight, whoever made them (never in a TE or a training, never what the
 * Planner saved for that flight, never a target). Never at a PRINT or a switch of mode. On by default; off = nothing is
 * cleaned then.
 */
@Serializable
data class PlannerPcSettings(
    val cleanOpened: Boolean = true,
)

/**
 * What a switch of the Mission section's mode reset by itself (`MissionSourceInfo.reset`; docs/DATA-STORES.md, "What a
 * switch resets"): every leftover of the Planner's in the cartridge and the TEs' files, the other mode's cockpit pages
 * from an earlier flight than the one current now ([now]: the printed briefing's in EZBoards mode, the Planner's open
 * flight in WDP mode), and a snapshot of another flight. Served for compatibility; no device draws it (1.3.8: the
 * pilot asked for no notice).
 *
 * [at] is the switch's time and the id **Undo** takes (`POST /api/mission/source/undo?at=`), [to] the mode switched to.
 * [keys] are the cartridge's (and a TE's mission file's) keys cleared, from [cartridge] ("Viper.ini"); [pages] the
 * cockpit kneeboard halves given Falcon BMS's own page back ("page 2, left"); [snapshot] WDP mode's snapshot discarded
 * (described, null = none). [done] and [left] are the sentences the notice shows: what was reset, and what was left and
 * why. [undo]: the keys can still be written back (pages cannot be undone: they are BMS's own originals); [undone] when
 * they were, with [undoMessage].
 */
@Serializable
data class SwitchReset(
    val at: Long = 0,
    val to: String = "",
    val now: LedgerMission? = null,
    val keys: List<Leftover> = emptyList(),
    val cartridge: String? = null,
    val pages: List<String> = emptyList(),
    val snapshot: String? = null,
    val done: List<String> = emptyList(),
    val left: List<String> = emptyList(),
    val undo: Boolean = false,
    val undone: Long = 0,
    val undoMessage: String? = null,
    /**
     * What made it (added in 1.3.8 with the automatic clearing): [SWITCH], a switch of mode (the default, and what an
     * older PC means), or [MISSION], a new mission — a PRINT of another flight in EZBoards mode, another flight opened
     * or populated in the Planner in WDP mode — which clears only the cartridge (and the current TE's own mission file),
     * never the cockpit pages; [to] is then the mode the mission is in.
     */
    val kind: String = SWITCH,
) {
    /** nothing was changed by the switch */
    val nothing: Boolean get() = keys.isEmpty() && pages.isEmpty() && snapshot == null

    companion object {
        const val SWITCH = "switch"
        const val MISSION = "mission"
    }
}
