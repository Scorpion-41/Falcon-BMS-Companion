package com.bmscompanion.app.data.mission

import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.bmscompanion.app.data.Repo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlin.time.TimeSource

/**
 * **The Mission section carries on from device to device** (1.4.2): the PC window, the browser and the Android app keep
 * the Mission section's preferences and the Planner pages' entries on the PC (`GET`/`POST /api/sync`,
 * `MissionSyncStore`), key by key, and the last change wins by the PC's clock. A pilot who changes the map's style on the
 * PC and picks up the tablet finds it changed there; what was typed on the DataCard on the tablet is on the PC's card.
 *
 * A value belongs to a [Source]: the device's preferences ([PrefSource], every key the Mission section writes through
 * `Repo` except [MissionPrefs.excluded]), a Planner page's entries (`@card/…`, registered by the Planner) or where the
 * pilot is (`@nav`, adopted only when the device becomes active: [activated]). A source reads its value and takes
 * another device's ([Source.adopt]); a value it cannot take now ([Adopt.LATER]: a box being typed in, another flight)
 * waits and is offered again.
 *
 * A device posts its change half a second after it ([MissionSync.changed]), polls the PC every two seconds for what
 * changed after the last value it had ([poll]), and on the first poll of a run takes every value the PC has that is
 * newer than its own — or, for what lives only while the app runs ([Source.volatile]: the Planner's entries), every
 * value that differs. A change this device made and could not post yet is never lost: posted later with the PC time of
 * the value it replaced ([SyncChange.seen]), it wins unless the PC has had another change since.
 */
class MissionSyncClient(
    private val metaLoad: () -> String?,
    private val metaSave: (String) -> Unit,
    private val get: suspend (Long, String?) -> PcAnswer<SyncAnswer>,
    private val post: suspend (SyncPost) -> PcAnswer<SyncAnswer>,
    private val by: String = "",
) {
    /** What another device's value did here. */
    enum class Adopt { TAKEN, LATER, NEVER }

    /** A value as this device has it: [type] as [SyncItem.type], [value] null = not set. */
    data class Local(val type: String, val value: String?)

    interface Source {
        /** whether [key] is this source's */
        fun owns(key: String): Boolean
        /** the value here; null: nothing to say for it now (a page's entry of another flight) */
        fun read(key: String): Local?
        /** takes another device's value; [active]: this device is becoming active now ([MissionSyncClient.activate]), where navigation is taken */
        fun adopt(item: SyncItem, active: Boolean): Adopt
        /** what only lives while the app runs: after a restart the PC's value is taken whenever it differs */
        val volatile: Boolean get() = false
        /** the keys this source has values for, to give an empty store (preferences only) */
        fun localKeys(): Collection<String> = emptyList()
    }

    @Serializable
    private data class Meta(
        val cursor: Long = 0,
        val seen: Map<String, Long> = emptyMap(),
        val pending: List<String> = emptyList(),
        val seeded: Boolean = false,
    )

    private val sources = ArrayList<Source>()
    private val seen = HashMap<String, Long>()
    /** keys changed here and not on the PC yet, with when (null: in an earlier run) */
    private val pending = LinkedHashMap<String, TimeSource.Monotonic.ValueTimeMark?>()
    /** keys this device gives an empty store (its own preferences), posted weak */
    private val weak = LinkedHashSet<String>()
    /** values another device made that this one could not take yet */
    private val waiting = LinkedHashMap<String, SyncItem>()
    /** the value the PC and this device last agreed on for a key, in this run */
    private val known = HashMap<String, String?>()
    private var cursor = 0L
    private var seeded = false
    private var loaded = false
    private var firstDone = false
    private var adopting = 0
    /** the newest Planner state time the PC named ([SyncAnswer.plannerAt]) */
    var plannerAt = 0L
        private set
    /** what happened at the last [poll] and [push], for the checks */
    var log: String = ""
        private set

    val adoptingNow: Boolean get() = adopting > 0

    /** true while [activate] runs: the sources take navigation then */
    var activating = false
        private set

    /** The device became active: what waited is offered with navigation allowed, then a poll. False: the PC was not reached. */
    suspend fun activate(): Boolean {
        activating = true
        try {
            retryWaiting()
            return poll()
        } finally { activating = false }
    }

    fun register(s: Source) { if (s !in sources) sources += s }

    private fun sourceOf(key: String): Source? = sources.firstOrNull { it.owns(key) }

    private fun load() {
        if (loaded) return
        loaded = true
        val m = runCatching { metaLoad()?.let { Repo.json.decodeFromString(Meta.serializer(), it) } }.getOrNull() ?: return
        cursor = m.cursor
        seen.putAll(m.seen)
        for (k in m.pending) pending[k] = null
        seeded = m.seeded
    }

    private var savedMeta: String? = null

    private fun persist() {
        runCatching {
            val s = Repo.json.encodeToString(Meta.serializer(), Meta(cursor, seen.toMap(), pending.keys.toList(), seeded))
            if (s != savedMeta) { savedMeta = s; metaSave(s) }
        }
    }

    /** [key] changed here (the pilot did it); ignored while another device's value is being taken. */
    fun changed(key: String) {
        if (adopting > 0) return
        load()
        val src = sourceOf(key) ?: return
        val had = key in pending
        // a value written again as it was (a page that saves all its settings at every change) is no change: posting it
        // would date it now, over another device's change of it not taken here yet
        if (!had && key in known && src.read(key)?.value == known[key]) return
        pending.remove(key)
        pending[key] = TimeSource.Monotonic.markNow()
        weak.remove(key)
        if (!had) persist()
    }

    fun hasPending(): Boolean = pending.isNotEmpty() || weak.isNotEmpty()

    fun isPending(key: String): Boolean = key in pending

    /** Posts every change not on the PC yet; takes the PC's where it holds a newer one. False when the PC could not be reached. */
    suspend fun push(): Boolean {
        load()
        if (pending.isEmpty() && weak.isEmpty()) return true
        val changes = ArrayList<SyncChange>()
        val sent = HashMap<String, Local>()
        for ((k, mark) in pending.toList()) {
            val l = sourceOf(k)?.read(k)
            if (l == null) { pending.remove(k); continue }
            // a change made before this run has no age this device can measure: a week, so it wins only where the
            // PC has had nothing new since the value it replaced
            val age = mark?.elapsedNow()?.inWholeMilliseconds ?: (7L * 24 * 3600 * 1000)
            changes += SyncChange(k, l.value, l.type, age, seen[k] ?: 0L)
            sent[k] = l
        }
        for (k in weak.toList()) {
            if (k in sent) continue
            val l = sourceOf(k)?.read(k) ?: continue
            if (l.value == null) continue
            changes += SyncChange(k, l.value, l.type, 7L * 24 * 3600 * 1000, seen[k] ?: 0L, weak = true)
            sent[k] = l
        }
        if (changes.isEmpty()) { weak.clear(); persist(); return true }
        val a = post(SyncPost(by, changes)).value ?: return false
        val kept = a.kept.toHashSet()
        val back = a.items.associateBy { it.key }
        for (c in changes) {
            val e = back[c.key] ?: continue
            if (c.weak) weak.remove(c.key)
            if (c.key in kept) {
                seen[c.key] = e.at
                known[c.key] = c.value
                waiting.remove(c.key)
                // still the value posted: nothing more to say (else the newer change stays pending)
                if (!c.weak && sourceOf(c.key)?.read(c.key) == sent[c.key]) pending.remove(c.key)
                log += "${c.key} posted; "
            } else {
                // the PC holds a newer change: it is taken, this device's goes
                pending.remove(c.key)
                take(e)
            }
        }
        persist()
        return true
    }

    private fun take(e: SyncItem) {
        val src = sourceOf(e.key) ?: run { seen[e.key] = e.at; return }
        if (src.read(e.key)?.let { it.value == e.value } == true) { seen[e.key] = e.at; known[e.key] = e.value; waiting.remove(e.key); return }
        adopting++
        val r = try { src.adopt(e, activating) } catch (x: Throwable) { Adopt.NEVER } finally { adopting-- }
        when (r) {
            Adopt.TAKEN -> { seen[e.key] = e.at; known[e.key] = e.value; waiting.remove(e.key); log += "${e.key} taken; " }
            Adopt.LATER -> { waiting[e.key] = e; log += "${e.key} waits; " }
            Adopt.NEVER -> { seen[e.key] = e.at; waiting.remove(e.key); log += "${e.key} refused; " }
        }
    }

    /** Offers the values that waited again (a box left, another flight planned, the device active). */
    fun retryWaiting() {
        for (e in waiting.values.toList()) {
            if (e.key in pending) { waiting.remove(e.key); continue }
            take(e)
        }
    }

    /**
     * Brings this device in step with the PC: posts what waits, then takes what changed on the PC after the last value
     * this device had (everything on the first poll of a run). False when the PC could not be reached.
     */
    suspend fun poll(): Boolean {
        load()
        log = ""
        if (!push()) return false
        val first = !firstDone
        val a = get(if (first) 0L else cursor, null).value ?: return false
        plannerAt = a.plannerAt
        retryWaiting()
        val onPc = HashSet<String>()
        for (e in a.items.sortedBy { it.at }) {
            onPc += e.key
            if (e.at > cursor) cursor = e.at
            if (e.key in pending) continue
            val src = sourceOf(e.key) ?: continue
            val had = seen[e.key] ?: 0L
            val newer = e.at > had
            if (newer || (first && src.volatile)) take(e)
            else {
                waiting.remove(e.key)
                if (src.read(e.key)?.value == e.value) known[e.key] = e.value
            }
        }
        if (first) {
            firstDone = true
            // a store that has never had this device's preferences: they are given to it, weak, so the first device
            // to link after an update gives the others what it has (and never over anything the PC has)
            if (!seeded) {
                for (s in sources) for (k in s.localKeys()) if (k !in onPc && k !in pending && s.owns(k)) weak += k
                seeded = true
            }
        }
        persist()
        if (weak.isNotEmpty()) push()
        return true
    }

    /**
     * Takes every value under [prefix] the PC has that differs from this device's (and is not being changed here),
     * however old: the Planner opened another flight, whose entries made on another device were left alone until now
     * ([Adopt.NEVER] while they were another flight's). False when the PC could not be reached.
     */
    suspend fun rescan(prefix: String): Boolean {
        load()
        val a = get(0L, prefix).value ?: return false
        for (e in a.items.sortedBy { it.at }) {
            if (e.key in pending) continue
            take(e)
        }
        persist()
        return true
    }
}

/**
 * Which preferences are the Mission section's and travel between devices ([MissionSync]): every key written through
 * `Repo`, except these — the device's own hardware and display (rendering, the MFD glass's reading, the VR board's
 * print size and shape, the link to the PC, the PC window), **the Dashboard** (its pages, cards and the cards' own
 * choices stay each device's: a phone and a PC lay theirs out differently), what a device keeps for itself (the mission
 * it last saw, the install, the sync's own bookkeeping), what travels another way (the Upd Kneeboard plan and the Steps
 * through `/api/planner/state`, the Mission tab as `@nav`, the attack and Performance pages per flight), and the sections
 * that are not the Mission section (Reference, Checklists, the Bullseye game, Config, the PC's Command section).
 */
object MissionPrefs {
    val EXCLUDED_KEYS: Set<String> = setOf(
        // the device's hardware and display
        "perf_easy", "small_tiles", "mfd_live", "mfd_fps",
        "kneeboard", "kneeboard_night", "kneeboard_text", "kneeboard_shape",
        "bridge_host", "bridge_port",
        // laid out for this device's screen
        "files_text_size", "taxi_sheet_pct", "planner_steps_open",
        // the Dashboard: each device's own (with the dash_ keys below)
        "mfd_arrange",
        // what a device keeps for itself
        "mission_epoch", "bms_install_seen", "planner_sync", "swept_version", "prereq_seen",
        // travels another way
        "kb_print_plan", "planner_steps_state",
        "wdp_popup_ini", "wdp_hadb_ini", "wdp_toss_ini", "wdp_performance_ini", "planner_attack_focus",
        // not the Mission section
        "theater", "favorites", "map_field_labels", "map_tacan_labels", "map_nav_labels",
        "open_threat_pictures", "mission_tab_config",
    )
    val EXCLUDED_PREFIXES: List<String> = listOf(
        "pc_", "dash_", "planner_sync_aside.", "mission_sync", "bullseye_", "cl:", "open_wp_", "cmd_", "open_cmd_", "@",
    )

    fun excluded(key: String): Boolean = key in EXCLUDED_KEYS || EXCLUDED_PREFIXES.any { key.startsWith(it) }

    /**
     * Where the pilot is rather than what they set: another device's value is taken only when this device becomes active
     * ([MissionSync.activating]: the app started or brought to the front, its window focused, the tab shown again, the
     * link back), never while it is being used. With `@nav` (the Planner's page and window).
     */
    val NAV_KEYS: Set<String> = setOf("mission_tab", "weather_tab_page", "kb_view_tab", "files_sel", "planner_attack_page")
}

/**
 * The in-memory holders of preferences that follow another device's change: a holder reads its preference into a
 * [prefState] (or registers with [on]), and [notify] runs its reader again when [MissionSync] has taken another
 * device's value for that key. [stamp] moves at every such change, for composables that read a preference in a
 * `remember` ([rememberKeyed]).
 */
object PrefWatch {
    private val watchers = HashMap<String, MutableList<() -> Unit>>()
    private val prefixWatchers = ArrayList<Pair<String, () -> Unit>>()

    var stamp by mutableIntStateOf(0)
        private set

    fun on(key: String, reload: () -> Unit) { watchers.getOrPut(key) { ArrayList() } += reload }
    fun off(key: String, reload: () -> Unit) { watchers[key]?.remove(reload) }
    fun onPrefix(prefix: String, reload: () -> Unit) { prefixWatchers += prefix to reload }

    fun notify(keys: Collection<String>) {
        if (keys.isEmpty()) return
        val run = LinkedHashSet<() -> Unit>()
        for (k in keys) {
            watchers[k]?.let { run.addAll(it) }
            for ((p, r) in prefixWatchers) if (k.startsWith(p)) run += r
        }
        for (r in run) runCatching { r() }
        stamp++
    }
}

/** A preference held in snapshot state, read by [read] now and again when another device changes [keys] ([PrefWatch]). */
fun <T> prefState(vararg keys: String, read: () -> T): MutableState<T> {
    val s = mutableStateOf(read())
    for (k in keys) PrefWatch.on(k) { s.value = read() }
    return s
}

/** [prefState] for a composable's own `remember`: the reader runs again on another device's change while it is composed. */
@androidx.compose.runtime.Composable
fun <T> rememberPrefState(key: String, read: () -> T): MutableState<T> {
    val s = androidx.compose.runtime.remember(key) { mutableStateOf(read()) }
    val latest = androidx.compose.runtime.rememberUpdatedState(read)
    androidx.compose.runtime.DisposableEffect(key) {
        val r: () -> Unit = { s.value = latest.value() }
        PrefWatch.on(key, r)
        onDispose { PrefWatch.off(key, r) }
    }
    return s
}

/** [prefState] for an Int. */
fun prefIntState(vararg keys: String, read: () -> Int): MutableIntState {
    val s = mutableIntStateOf(read())
    for (k in keys) PrefWatch.on(k) { s.intValue = read() }
    return s
}

/** The device's preferences as a [MissionSyncClient.Source]: every key `Repo` writes, less [MissionPrefs.excluded]. */
object PrefSource : MissionSyncClient.Source {
    override fun owns(key: String) = !MissionPrefs.excluded(key)
    override fun read(key: String): MissionSyncClient.Local = Repo.prefRaw(key).let { MissionSyncClient.Local(it?.first ?: "", it?.second) }
    override fun adopt(item: SyncItem, active: Boolean): MissionSyncClient.Adopt {
        // where the pilot was on another device: only when this one becomes active
        if (item.key in MissionPrefs.NAV_KEYS && !active) return MissionSyncClient.Adopt.LATER
        Repo.adoptPref(item.key, item.type, item.value)
        PrefWatch.notify(listOf(item.key))
        return MissionSyncClient.Adopt.TAKEN
    }
    override fun localKeys(): Collection<String> = Repo.prefKeys().filter { owns(it) }
}

/** The app's own client, the poll loop and the moments a device becomes active. */
object MissionSync {
    private const val META_KEY = "mission_sync_meta"
    /** how often a device asks the PC what changed (live adoption within a few seconds) */
    const val POLL_MS = 2000L
    /** a change is posted this long after it */
    const val POST_DELAY_MS = 500L

    val client = MissionSyncClient(
        metaLoad = { Repo.getString(META_KEY) },
        metaSave = { Repo.putString(META_KEY, it) },
        get = { since, prefix -> MissionLink.syncGet(since, prefix) },
        post = { MissionLink.syncPost(it) },
        by = Repo.deviceKind,
    ).also { it.register(PrefSource) }

    /** Off for the developer checks (SelfTest), which must not take or leave anything in a scratch APPDATA. */
    var enabled = true

    /**
     * The WDP box (form/name) or editor this device is typing in now: another device's value for it waits until it is
     * left ([MissionSyncClient.Adopt.LATER]), and the Planner's flight is not taken meanwhile.
     */
    var typing: String? by mutableStateOf(null)

    /** Told when the PC's Planner state (flight, Upd Kneeboard plan, Steps) is newer than this device last synced. */
    var onPlannerChanged: (suspend () -> Unit)? = null
    private var plannerSeen = 0L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    /** one exchange with the PC at a time (the poll, a post, an activation, a rescan) */
    private val lock = kotlinx.coroutines.sync.Mutex()
    private var postJob: Job? = null
    private var loop: Job? = null
    private var online = false

    fun register(s: MissionSyncClient.Source) = client.register(s)

    /** Called by every `Repo` copy after a preference is written: posted when it is the Mission section's. */
    fun touched(key: String) {
        if (!enabled || client.adoptingNow || MissionPrefs.excluded(key)) return
        changed(key)
    }

    /** [key] changed here; posted to the PC [POST_DELAY_MS] after (nothing is lost while the PC is away). */
    fun changed(key: String) {
        if (!enabled || client.adoptingNow) return
        client.changed(key)
        schedulePost()
    }

    private fun schedulePost() {
        // offline (or a VR board, which never links: MissionSyncWatcher) the change waits for the next poll
        if (!online || postJob?.isActive == true) return
        postJob = scope.launch {
            delay(POST_DELAY_MS)
            lock.withLock { runCatching { client.push() } }
            // changes made while the post was out
            if (client.hasPending() && online) { postJob = null; schedulePost() }
        }
    }

    /** The link to the PC is up or down: the poll runs while it is up. */
    fun linked(up: Boolean) {
        online = up
        if (!enabled) return
        if (up) {
            if (loop?.isActive != true) loop = scope.launch {
                activated()
                while (online) {
                    delay(POLL_MS)
                    pollOnce()
                }
            }
        } else loop?.cancel()
    }

    private suspend fun pollOnce() {
        val ok = lock.withLock { runCatching { client.poll() }.getOrDefault(false) }
        if (!ok) return
        val p = client.plannerAt
        if (p > plannerSeen && typing == null) {
            plannerSeen = p
            onPlannerChanged?.let { runCatching { it() } }
        }
    }

    /**
     * The device became active — the app started or came to the front, its window took the focus, the browser tab was
     * shown again, the link came back: it takes where the pilot was on another device (`@nav`), as well as every value.
     */
    fun activated() {
        if (!enabled || !online) return
        scope.launch {
            lock.withLock { runCatching { client.activate() } }
            plannerSeen = client.plannerAt
            onPlannerChanged?.let { runCatching { it() } }
        }
    }

    /** Offers again what waited (a box was left). */
    fun retrySoon() { scope.launch { lock.withLock { client.retryWaiting() } } }

    /** Takes the PC's values under [prefix] (the Planner opened another flight: its entries made elsewhere). */
    fun rescanSoon(prefix: String) {
        if (!enabled || !online) return
        scope.launch { lock.withLock { runCatching { client.rescan(prefix) } } }
    }
}
