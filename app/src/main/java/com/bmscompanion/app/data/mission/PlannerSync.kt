package com.bmscompanion.app.data.mission

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.bmscompanion.app.data.Repo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlin.time.TimeSource

/**
 * **Planning belongs to the PC, not to the device** (1.4.2). A pilot plans on the PC — the Planner's flight, the Upd
 * Kneeboard plan, the Steps — puts the PC window on its server page and flies with a tablet; the tablet used to ask
 * for all of it again. Now each piece is kept on the PC (`GET`/`POST /api/planner/state`, `PlannerStateStore`), every
 * device posts its change, and a device coming to the Planner or to Upd Kneeboard (and whenever it links) takes the
 * PC's piece when it is newer than its own. The PC's own window is a client like any other (in-process).
 *
 * A piece ([Part]) is whatever its owner keeps — its own JSON or text — read with [Part.read] and taken with
 * [Part.adopt]. The owner calls [changed] after every change of its own: **strong** when the pilot made it, **weak**
 * when the device made it by itself (a default plan laid, the Steps following a new mission), so a tablet opening Upd
 * Kneeboard for the first time never pushes its default over the plan made on the PC.
 *
 * **Which is newer** is the PC's clock alone ([PlannerStatePost.age] is measured on this device's steady clock), and a
 * device's own change is never lost silently: when the PC holds a newer piece the device's is kept aside
 * ([aside], [useAside]) and [notice] says so.
 */
class PlannerSyncClient(
    private val metaLoad: () -> String?,
    private val metaSave: (String) -> Unit,
    private val asideLoad: (String) -> String?,
    private val asideSave: (String, String?) -> Unit,
    private val get: suspend () -> PcAnswer<PlannerStateAll>,
    private val post: suspend (PlannerStatePost) -> PcAnswer<PlannerStateAll>,
    private val by: String = "",
) {
    /** A piece of planning: how to read it here and how to take the PC's. [adopt] answers false when it could not. */
    class Part(val read: () -> String?, val adopt: suspend (String) -> Boolean)

    /** What this device knows of one piece: the PC time of the piece it last had; a change of its own not yet on the PC. */
    @Serializable
    data class Meta(val seen: Long = 0, val pending: Boolean = false, val strong: Boolean = false)

    private val parts = LinkedHashMap<String, Part>()
    private val meta = HashMap<String, Meta>()
    private val marks = HashMap<String, TimeSource.Monotonic.ValueTimeMark>()
    /** the pieces brought in step with the PC at least once in this run ([sync]) */
    private val synced = HashSet<String>()
    private var loaded = false
    private var adopting = 0

    /** a sentence about the last piece taken from the PC over a change of this device's own, until [dismiss] */
    var notice by mutableStateOf<String?>(null)
        private set
    /** which piece [notice] is about */
    var noticeOf by mutableStateOf<String?>(null)
        private set

    /** what happened at the last [sync], for the checks */
    var lastSync: String = ""
        private set

    fun register(name: String, part: Part) { parts[name] = part }

    val adoptingNow: Boolean get() = adopting > 0

    private val metaSer = MapSerializer(String.serializer(), Meta.serializer())

    private fun load() {
        if (loaded) return
        loaded = true
        runCatching { metaLoad()?.let { Repo.json.decodeFromString(metaSer, it) } }.getOrNull()?.let { meta.putAll(it) }
    }

    private fun persist() { runCatching { metaSave(Repo.json.encodeToString(metaSer, meta.toMap())) } }

    fun metaOf(name: String): Meta { load(); return meta[name] ?: Meta() }

    /** The owner of [name] changed it; [strong] = the pilot did. Ignored while the PC's piece is being taken. */
    fun changed(name: String, strong: Boolean) {
        if (adopting > 0) return
        load()
        val m = meta[name] ?: Meta()
        meta[name] = m.copy(pending = true, strong = strong || (m.pending && m.strong))
        marks[name] = TimeSource.Monotonic.markNow()
        persist()
    }

    /** Posts [name]'s change; takes the PC's answer. False when the PC could not be reached. */
    suspend fun push(name: String): Boolean {
        load()
        val m = meta[name] ?: return true
        if (!m.pending) return true
        val part = parts[name] ?: return true
        val v = part.read() ?: run { meta[name] = m.copy(pending = false); persist(); return true }
        // a change made before this run (the app closed before it reached the PC) has no age this device can measure:
        // it is posted as a week old, so it wins only where the PC has nothing this device has not seen
        val age = marks[name]?.elapsedNow()?.inWholeMilliseconds ?: (7L * 24 * 3600 * 1000)
        val a = post(PlannerStatePost(name, v, by, age, m.seen, weak = !m.strong))
        val s = a.value ?: return false
        val e = s.entries.firstOrNull { it.name == name } ?: return false
        if (s.kept) {
            // still the same change (nothing else changed it while the post was out)
            val now = meta[name] ?: m
            meta[name] = Meta(seen = e.at, pending = now.pending && part.read() != v, strong = now.strong && part.read() != v)
            persist()
            lastSync += "$name posted; "
        } else {
            // the PC holds a newer one: the device's own goes aside when the pilot made it, and the PC's is taken
            if (m.strong) keepAside(name, v)
            meta[name] = m.copy(pending = false, strong = false)
            persist()
            take(name, e, part)
        }
        return true
    }

    private suspend fun take(name: String, e: PlannerStateEntry, part: Part) {
        val ok = if (part.read() == e.value) true else {
            adopting++
            try { part.adopt(e.value) } catch (x: kotlinx.coroutines.CancellationException) { throw x } catch (_: Throwable) { false } finally { adopting-- }
        }
        if (ok) {
            meta[name] = (meta[name] ?: Meta()).copy(seen = e.at, pending = false, strong = false)
            persist()
            lastSync += "$name taken from the PC; "
        } else lastSync += "$name could not be taken; "
    }

    private fun keepAside(name: String, v: String) {
        runCatching { asideSave(name, v) }
        noticeOf = name
        notice = when (name) {
            PlannerSync.KNEEBOARD -> "Upd Kneeboard: the plan made on another device is newer and was taken. This device's own is kept."
            PlannerSync.FLIGHT -> "The Planner: the flight planned on another device is newer and was taken. This device's own is kept."
            else -> "A newer plan from another device was taken. This device's own is kept."
        }
    }

    /** This device's own piece, kept aside when the PC's was taken; null when none. */
    fun aside(name: String): String? = runCatching { asideLoad(name) }.getOrNull()

    /** Puts this device's own piece back (and on the PC, as the pilot's change). */
    suspend fun useAside(name: String): Boolean {
        val v = aside(name) ?: return false
        val part = parts[name] ?: return false
        adopting++
        val ok = try { part.adopt(v) } catch (x: kotlinx.coroutines.CancellationException) { throw x } catch (_: Throwable) { false } finally { adopting-- }
        if (!ok) return false
        runCatching { asideSave(name, null) }
        dismiss()
        changed(name, strong = true)
        push(name)
        return true
    }

    fun dismiss() { notice = null; noticeOf = null }

    /**
     * Brings [names] in step with the PC: a change of this device's not yet posted is posted (and the PC's taken when
     * it is newer); else the PC's piece is taken when it is newer than the one this device last had. A piece the PC
     * does not have yet is posted as this device has it (weak), so the first device to sync after an update gives the
     * others its plan. Answers false when the PC could not be reached.
     */
    suspend fun sync(names: Collection<String> = parts.keys.toList()): Boolean {
        load()
        lastSync = ""
        val a = get()
        val all = a.value ?: return false
        for (name in names) {
            val part = parts[name] ?: continue
            val m = meta[name] ?: Meta()
            val e = all.entries.firstOrNull { it.name == name }
            // the first sync of this run, nothing changed here yet: a piece this device keeps only while it runs (the
            // Planner's flight) is back on its default after a restart or a reload, while the PC still holds the one
            // this device last had — not newer, so it was never taken again and the devices showed different flights
            val fresh = name !in synced && marks[name] == null
            when {
                m.pending -> push(name)
                e != null && e.at > m.seen -> take(name, e, part)
                e != null && fresh && part.read() != e.value -> take(name, e, part)
                e == null && part.read() != null -> { changed(name, strong = false); push(name) }
            }
            if (e == null || marks[name] != null || part.read() == e.value) synced.add(name)
        }
        return true
    }
}

/** The app's own client and its pieces (registered by their owners: `PlannerMissionState`, `KneeboardPrintSession`, `PlannerSteps`). */
object PlannerSync {
    /** the Planner's open flight (`PlannerMissionState`): save, flight, seat and the Precision STPT answer */
    const val FLIGHT = "planner.flight"
    /** the Upd Kneeboard plan (`KneeboardPrintSession`, the device's `kb_print_plan`) */
    const val KNEEBOARD = "kneeboard.plan"
    /** the Planner's Steps: the BMS steps ticked and what was done, for the mission they were made for */
    const val STEPS = "planner.steps"

    private const val META_KEY = "planner_sync"
    private const val ASIDE_KEY = "planner_sync_aside."

    val client = PlannerSyncClient(
        metaLoad = { Repo.getString(META_KEY) },
        metaSave = { Repo.putString(META_KEY, it) },
        asideLoad = { Repo.getString(ASIDE_KEY + it) },
        asideSave = { n, v -> Repo.putString(ASIDE_KEY + n, v) },
        get = { MissionLink.plannerState() },
        post = { MissionLink.postPlannerState(it) },
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val jobs = HashMap<String, Job>()

    fun register(name: String, part: PlannerSyncClient.Part) = client.register(name, part)

    /** Off for the developer checks (SelfTest), which must not take or leave planning in a scratch APPDATA. */
    var enabled = true

    /** [name] changed here; posted to the PC a moment after the last change (nothing is lost while the PC is away). */
    fun changed(name: String, strong: Boolean) {
        if (!enabled || client.adoptingNow) return
        client.changed(name, strong)
        if (jobs[name]?.isActive == true) return
        jobs[name] = scope.launch {
            delay(600)
            runCatching { client.push(name) }
        }
    }

    /** Brings [names] in step with the PC (see [PlannerSyncClient.sync]); never throws. */
    suspend fun sync(vararg names: String): Boolean = if (!enabled) false else try {
        client.sync(if (names.isEmpty()) listOf(FLIGHT, KNEEBOARD, STEPS) else names.toList())
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Throwable) { false }

    /** Starts [sync] without waiting for it. */
    fun syncSoon(vararg names: String) { scope.launch { sync(*names) } }

    /** Puts this device's own [name] back over the one taken from the PC ([PlannerSyncClient.useAside]). */
    fun useAsideSoon(name: String) { scope.launch { runCatching { client.useAside(name) } } }

    val notice: String? get() = client.notice
}
