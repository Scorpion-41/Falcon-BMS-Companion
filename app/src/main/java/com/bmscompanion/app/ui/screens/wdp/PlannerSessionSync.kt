package com.bmscompanion.app.ui.screens.wdp

import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.mission.MissionSync
import com.bmscompanion.app.data.mission.MissionSyncClient
import com.bmscompanion.app.data.mission.SyncItem
import kotlinx.serialization.Serializable

/**
 * **The Planner's pages carry on from device to device** (1.4.2, [MissionSync]): what the pilot typed or set on a page
 * and has not saved yet — the DataCard's boxes and the Briefing page's prose, the Performance page's inputs, the
 * Pop-up, HADB and TOSS pages' inputs (their Setup.ini section, the IP STPT picked, the typed ELEVs), the current attack
 * ([AttackFocus]) and the DTC page's edits — go to the PC a moment after the change, **keyed to the flight they were
 * made for** ([flightKey]: the Planner's flight, else the printed briefing's), and another device planning the same
 * flight takes them; another flight's are never applied ([MissionSyncClient.Adopt.NEVER]; taken when that flight is
 * opened, [flightOpened]).
 *
 * Keys: `@card/<flight>/<box>` (one a box, so two devices typing two boxes both keep theirs), `@perf/<flight>/<input>`,
 * `@popup|@hadb|@toss/<flight>` (the page whole), `@focus/<flight>`, `@dtc/<flight>`, and `@nav` — the Planner's page and
 * window, taken only when the device becomes active ([NavSource]).
 *
 * An entry is never laid over a box being typed in on this device ([MissionSync.typing]): it waits until the box is
 * left. A page not made yet, or still without its mission, takes it when it has one.
 */
object PlannerSessionSync {
    private var registered = false

    /** The pages of this run's Planner ([WdpSession]), as far as they are made. */
    private object LivePages : PlannerPages {
        override val card get() = if (WdpSession.dataCardMadeYet) WdpSession.dataCard else null
        override val perf get() = if (WdpSession.performanceMadeYet) WdpSession.performance else null
        override val popup get() = if (WdpSession.popupMadeYet) WdpSession.popup else null
        override val hadb get() = if (WdpSession.hadbMadeYet) WdpSession.hadb else null
        override val toss get() = if (WdpSession.tossMadeYet) WdpSession.toss else null
        override val dtc get() = if (WdpSession.dtcMadeYet) WdpSession.dtc else null
        override val focus: WdpPage? get() = AttackFocus.profile
        override fun takeFocus(page: WdpPage?) = AttackFocus.take(page)
    }

    val source = PlannerSessionSource(LivePages, { flightKey() }, { MissionSync.typing })

    /** Registers the sources (once; the Planner's state, [PlannerShared.ensure]). */
    fun ensure() {
        if (registered) return
        registered = true
        MissionSync.register(source)
        MissionSync.register(NavSource)
    }

    /** The flight the Planner works on now, as a short key every device works out alike; null with none. */
    fun flightKey(): String? {
        val m = runCatching { PlannerLeftovers.missionNow() }.getOrNull() ?: return null
        return PlannerSessionSource.keyOf(m.callsign, m.packageId, m.flightId, m.theater, m.save, m.seat, m.printed)
    }

    fun hook(w: DataCardWiring) = source.hook(w) { MissionSync.changed(it) }
    fun hook(w: PerformanceWiring) = source.hook(w) { MissionSync.changed(it) }
    fun hook(w: PopupWiring) { source.hook(w) { MissionSync.changed(it) }; hookFocus() }
    fun hook(w: HadbWiring) { source.hook(w) { MissionSync.changed(it) }; hookFocus() }
    fun hook(w: TossWiring) { source.hook(w) { MissionSync.changed(it) }; hookFocus() }
    fun hook(w: DtcWiring) = source.hook(w) { MissionSync.changed(it) }

    private fun hookFocus() {
        if (AttackFocus.onPilotChange == null) AttackFocus.onPilotChange = { flightKey()?.let { MissionSync.changed("@focus/$it") } }
    }

    /** The Planner opened another flight (here, or taken from another device): that flight's entries made elsewhere are taken. */
    fun flightOpened() {
        source.flightChanged()
        MissionSync.rescanSoon("@")
    }

    /** The Planner's page or window changed here ([WdpSession.page], [PlannerWindows]). */
    fun navChanged() = MissionSync.changed(NavSource.KEY)

    /** A box took or left the keyboard on this device; a box left offers again what waited for it. */
    fun typingIn(box: String?) {
        if (MissionSync.typing == box) return
        MissionSync.typing = box
        if (box == null) MissionSync.retrySoon()
    }
}

/** The Planner's pages a [PlannerSessionSource] reads and writes; null for a page not made. */
interface PlannerPages {
    val card: DataCardWiring?
    val perf: PerformanceWiring?
    val popup: PopupWiring?
    val hadb: HadbWiring?
    val toss: TossWiring?
    val dtc: DtcWiring?
    val focus: WdpPage?
    fun takeFocus(page: WdpPage?)
}

/**
 * The Planner pages' entries as a [MissionSyncClient.Source] ([PlannerSessionSync]; `--syncstatetest` makes one for each
 * device it plays, over pages of their own): [flightKey] the flight planned now, [typing] the box with the keyboard.
 */
class PlannerSessionSource(
    private val pages: PlannerPages,
    private val flightKey: () -> String?,
    private val typing: () -> String?,
) : MissionSyncClient.Source {
    @Serializable
    private data class Attack(val ini: String = "", val ip: Int? = null, val elev: String = "")

    @Serializable
    private data class Dtc(val base: String = "", val text: String = "")

    private data class Parsed(val kind: String, val flight: String, val rest: String)

    private fun parse(key: String): Parsed? {
        if (!key.startsWith("@")) return null
        val parts = key.substring(1).split('/', limit = 3)
        val kind = parts.getOrNull(0) ?: return null
        if (kind !in KINDS) return null
        return Parsed(kind, parts.getOrNull(1).orEmpty(), parts.getOrNull(2).orEmpty())
    }

    override fun owns(key: String): Boolean = parse(key) != null
    override val volatile: Boolean get() = true

    private fun local(v: String?) = MissionSyncClient.Local("s", v)

    override fun read(key: String): MissionSyncClient.Local? {
        val p = parse(key) ?: return null
        if (p.flight != flightKey()) return null
        return when (p.kind) {
            "card" -> pages.card?.typedEntries()?.get(p.rest)?.let { local(it) }
            "perf" -> pages.perf?.pilotInputs()?.get(p.rest)?.let { local(it) }
            "popup" -> pages.popup?.let { attack(it.session()) }
            "hadb" -> pages.hadb?.let { attack(it.session()) }
            "toss" -> pages.toss?.let { attack(it.session()) }
            "focus" -> local(pages.focus?.name.orEmpty())
            "dtc" -> pages.dtc?.session()?.let { (b, t) -> local(Repo.json.encodeToString(Dtc.serializer(), Dtc(b, t))) }
            else -> null
        }
    }

    private fun attack(t: Triple<String, Int?, String>) = local(Repo.json.encodeToString(Attack.serializer(), Attack(t.first, t.second, t.third)))

    override fun adopt(item: SyncItem, active: Boolean): MissionSyncClient.Adopt {
        val never = MissionSyncClient.Adopt.NEVER
        val later = MissionSyncClient.Adopt.LATER
        val p = parse(item.key) ?: return never
        // another flight's entries are never laid on this one
        val fk = flightKey() ?: return later
        if (p.flight != fk) return never
        val v = item.value ?: return never
        val t = typing()
        // (if/else rather than a `when` without a subject mixing a null test: the Kotlin/Wasm gotcha in CLAUDE.md)
        val typingAny = t != null
        val typingThis = if (t == null) false else t.endsWith("/" + p.rest)
        fun done(ok: Boolean) = if (ok) MissionSyncClient.Adopt.TAKEN else never
        fun attackOf(): Attack? = runCatching { Repo.json.decodeFromString(Attack.serializer(), v) }.getOrNull()
        return when (p.kind) {
            "card" -> {
                val c = pages.card
                if (c == null || !c.hasMission || typingThis) later else done(c.takeTyped(p.rest, v))
            }
            "perf" -> {
                val c = pages.perf
                if (c == null || !c.hasMission || typingThis) later else done(c.takeInput(p.rest, v))
            }
            "popup" -> {
                val c = pages.popup
                if (c == null || !c.hasMission || typingAny) later else attackOf()?.let { done(c.takeSession(it.ini, it.ip, it.elev)) } ?: never
            }
            "hadb" -> {
                val c = pages.hadb
                if (c == null || !c.hasMission || typingAny) later else attackOf()?.let { done(c.takeSession(it.ini, it.ip, it.elev)) } ?: never
            }
            "toss" -> {
                val c = pages.toss
                if (c == null || !c.hasMission || typingAny) later else attackOf()?.let { done(c.takeSession(it.ini, it.ip, it.elev)) } ?: never
            }
            "focus" -> { pages.takeFocus(WdpPage.entries.firstOrNull { it.name == v }); MissionSyncClient.Adopt.TAKEN }
            "dtc" -> {
                val c = pages.dtc
                val d = runCatching { Repo.json.decodeFromString(Dtc.serializer(), v) }.getOrNull()
                if (c == null || typingAny) later
                else if (d == null) never
                else when (c.takeSession(d.base, d.text)) { true -> MissionSyncClient.Adopt.TAKEN; false -> never; null -> later }
            }
            else -> never
        }
    }

    // ---------------------------------------------------------------- the pages tell of the pilot's changes

    /** what was last told for each card box and Performance input, so a change posts only those that changed */
    private val cardSeen = HashMap<String, String>()
    private val perfSeen = HashMap<String, String>()
    private var seenFor: String? = null

    /** The flight now; another than before forgets what was told. */
    fun flightChanged(): String? {
        val fk = flightKey()
        if (fk != seenFor) { seenFor = fk; cardSeen.clear(); perfSeen.clear() }
        return fk
    }

    fun hook(w: DataCardWiring, changed: (String) -> Unit) {
        w.onPilotChange = {
            val fk = flightChanged()
            if (fk != null) for ((box, e) in w.typedEntries()) if (cardSeen[box] != e) { cardSeen[box] = e; changed("@card/$fk/$box") }
        }
    }

    fun hook(w: PerformanceWiring, changed: (String) -> Unit) {
        w.onPilotChange = {
            val fk = flightChanged()
            if (fk != null) for ((n, x) in w.pilotInputs()) if (perfSeen[n] != x) { perfSeen[n] = x; changed("@perf/$fk/$n") }
        }
    }

    fun hook(w: PopupWiring, changed: (String) -> Unit) { w.onPilotChange = { flightKey()?.let { changed("@popup/$it") } } }
    fun hook(w: HadbWiring, changed: (String) -> Unit) { w.onPilotChange = { flightKey()?.let { changed("@hadb/$it") } } }
    fun hook(w: TossWiring, changed: (String) -> Unit) { w.onPilotChange = { flightKey()?.let { changed("@toss/$it") } } }
    fun hook(w: DtcWiring, changed: (String) -> Unit) { w.onPilotChange = { flightKey()?.let { changed("@dtc/$it") } } }

    companion object {
        private val KINDS = listOf("card", "perf", "popup", "hadb", "toss", "focus", "dtc")

        /** A flight's key: its callsign (letters and digits), package, flight, theater, save, seat and printed briefing. */
        fun keyOf(callsign: String, packageId: Int?, flightId: Int?, theater: String?, save: String?, seat: Int?, printed: Long): String? {
            val cs = callsign.filter { it.isLetterOrDigit() }.lowercase()
            if (cs.isEmpty()) return null
            return sessionHash(listOf(cs, packageId, flightId, theater?.trim()?.lowercase(), save?.trim()?.lowercase(), seat, printed).joinToString("|"))
        }
    }
}

/**
 * Where the pilot is in the Planner (`@nav`): the page on show and the window open — "<page>\n<window>\n<arg>". Another
 * device's is taken only when this device becomes active (the app started or brought to the front, its window focused,
 * the browser tab shown again, the link back), never while it is used. The Planner guide is not followed (it opens by
 * itself once per device).
 */
object NavSource : MissionSyncClient.Source {
    const val KEY = "@nav"

    override fun owns(key: String) = key == KEY

    override fun read(key: String): MissionSyncClient.Local {
        val w = PlannerWindows.open?.takeIf { it != PlannerWindow.GUIDE }
        return MissionSyncClient.Local("s", listOf(WdpSession.page, w?.name.orEmpty(), if (w != null) PlannerWindows.arg.orEmpty() else "").joinToString("\n"))
    }

    override fun adopt(item: SyncItem, active: Boolean): MissionSyncClient.Adopt {
        if (!active) return MissionSyncClient.Adopt.LATER
        val lines = item.value.orEmpty().split('\n')
        val page = lines.getOrNull(0).orEmpty()
        if (WdpPage.entries.any { it.name == page }) WdpSession.page = page
        val w = PlannerWindow.entries.firstOrNull { it.name == lines.getOrNull(1) }
        if (w == null) { if (PlannerWindows.open != null && PlannerWindows.open != PlannerWindow.GUIDE) PlannerWindows.close() }
        else PlannerWindows.show(w, lines.getOrNull(2)?.ifEmpty { null })
        com.bmscompanion.app.ui.screens.mission.MissionSyncNav.goMission = true
        return MissionSyncClient.Adopt.TAKEN
    }
}

/** A short hash of [s] (FNV-1a, 64 bits) in hex: a flight's key, a cartridge's identity. */
internal fun sessionHash(s: String): String {
    var h = -0x340d631b7bdddcdbL
    for (c in s) { h = h xor c.code.toLong(); h *= 0x100000001b3L }
    return h.toULong().toString(16)
}
