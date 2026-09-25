package com.bmscompanion.app.data

import com.bmscompanion.app.data.airfield.Airfield

import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.bmscompanion.web.httpBytes
import com.bmscompanion.web.httpText
import com.bmscompanion.web.storageGet
import com.bmscompanion.web.storageSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface

/**
 * Browser version of the Android Repo: same API. The bundled BMS data is downloaded on demand from the PC program
 * that serves the page (/assets/...), and preferences live in this browser (localStorage), so each device keeps its own.
 */
object Repo {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true; explicitNulls = false }
    private val cache = HashMap<String, Deferred<Any?>>()

    fun init() {
        loadPrefs()
        _favorites.value = getStringSet("favorites")
        selectedTheater.value = getString("theater") ?: "korea-kto"
        // warm up the most used datasets in background
        scope.async {
            // Airfield/map pickers only offer main theaters; migrate an old add-on selection to its main theater.
            val idx = index()
            val cur = idx.theaters.firstOrNull { it.id == selectedTheater.value }
            if (cur != null && !cur.primary) cur.mainTheater?.let { m -> setTheater(m) }
            aircraft(); weapons()
        }
    }

    private suspend fun open(path: String): String = httpText("/assets/$path", timeoutMs = 120_000)

    private inline fun <reified T> load(path: String): Deferred<T?> {
        @Suppress("UNCHECKED_CAST")
        return cache.getOrPut(path) {
            scope.async {
                runCatching {
                    json.decodeFromString(serializer<T>(), open(path))
                }.onFailure { println("Repo: load $path failed: $it") }.getOrNull()
            }
        } as Deferred<T?>
    }

    suspend fun index(): DataIndex = load<DataIndex>("data/index.json").await() ?: DataIndex()
    suspend fun aircraft(): List<Aircraft> = load<List<Aircraft>>("data/aircraft.json").await().orEmpty()
    suspend fun weapons(): List<Weapon> = load<List<Weapon>>("data/weapons.json").await().orEmpty()
    suspend fun encyclopedia(): List<EncyEntry> = load<List<EncyEntry>>("data/encyclopedia.json").await().orEmpty()
    suspend fun airportSet(id: String): AirportSet = load<AirportSet>("data/airports/$id.json").await() ?: AirportSet()

    /** Which ground chart file each field uses, by campaign objective id (the airport's own id). */
    suspend fun airfieldIndex(setId: String): Map<String, String> = load<Map<String, String>>("data/airfields/$setId.json").await().orEmpty()

    /** One field's ground chart: runways, taxi networks, ramp spots. */
    suspend fun airfield(setId: String, airportId: Int): Airfield? {
        val file = airfieldIndex(setId)[airportId.toString()] ?: return null
        return load<Airfield>("data/airfields/$file.json").await()
    }

    suspend fun radio(id: String): List<RadioEntry> = load<List<RadioEntry>>("data/radio/$id.json").await().orEmpty()
    suspend fun charts(setId: String): Map<String, List<ChartRef>> = load<Map<String, Map<String, List<ChartRef>>>>("data/charts.json").await()?.get(setId).orEmpty()
    suspend fun hotas(id: String): HotasFile? = load<HotasFile>("data/curated/hotas_$id.json").await()
    suspend fun harm(): HarmFile? = load<HarmFile>("data/curated/harm.json").await()
    suspend fun rwr(): RwrFile? = load<RwrFile>("data/curated/rwr.json").await()
    suspend fun comms(): CommsFile? = load<CommsFile>("data/curated/comms.json").await()

    suspend fun threatFiles(): List<ThreatFile> = listOfNotNull(
        load<ThreatFile>("data/curated/threats_airdefense.json").await(),
        load<ThreatFile>("data/curated/threats_air_sea.json").await(),
    )

    suspend fun threats(): List<Threat> = threatFiles().flatMap { it.threats }

    suspend fun checklists(): List<Checklist> {
        val files = runCatching { json.decodeFromString(ListSerializer(String.serializer()), httpText("/api/assets?dir=data/curated")) }
            .getOrDefault(listOf("checklist_f16cm.json", "checklist_f16cj.json", "checklist_f15c.json"))
        return files.filter { it.startsWith("checklist_") }.sortedBy { listOf("checklist_f16cm.json", "checklist_f16cj.json", "checklist_f15c.json").indexOf(it).let { i -> if (i < 0) 99 else i } }.mapNotNull { load<Checklist>("data/curated/$it").await() }
    }

    suspend fun weaponMap(): Map<String, Weapon> = mapCache("weaponMap") { weapons().associateBy { it.key } }
    suspend fun aircraftMap(): Map<String, Aircraft> = mapCache("aircraftMap") { aircraft().associateBy { it.key } }
    suspend fun encyMap(): Map<String, EncyEntry> = mapCache("encyMap") { encyclopedia().associateBy { it.key } }
    suspend fun theater(id: String): Theater? = index().theaters.firstOrNull { it.id == id }
    suspend fun mainTheaters(): List<Theater> = index().theaters.filter { it.primary }

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> mapCache(key: String, build: suspend () -> T): T =
        (cache.getOrPut(key) { scope.async { build() } } as Deferred<T>).await()

    /** Theater display name lookup (synchronous after index loaded). */
    suspend fun theaterNames(): Map<String, String> = index().theaters.associate { it.id to it.name }

    /** Map landmarks (borders, provinces, labels, places) for a map id such as "korea". */
    /** Every Falcon BMS config option: what it does, what it defaults to, which group it belongs in. */
    suspend fun cfgOptions(): CfgCatalog = load<CfgCatalog>("data/cfg/options.json").await() ?: CfgCatalog()

    suspend fun geo(mapId: String): GeoLayers? = load<GeoLayers>("data/geo/$mapId.json").await()

    // ---------- images ----------
    /** Browsers get full-size map tiles; the page would otherwise look soft on high-resolution screens. */
    const val lowMemory = false

    private const val BITMAP_BUDGET = 40L * 1024 * 1024
    private val bitmaps = LinkedHashMap<String, Bitmap>()
    private var bitmapBytes = 0L

    suspend fun bitmap(path: String, sample: Int = 1): Bitmap? {
        val key = "$path@$sample"
        bitmaps[key]?.let { b -> bitmaps.remove(key); bitmaps[key] = b; return b }
        val bytes = httpBytes("/assets/$path") ?: return null
        return decodeBitmap(bytes, sample)?.also { b ->
            bitmaps.put(key, b)?.let { bitmapBytes -= it.byteCount }
            bitmapBytes += b.byteCount
            val it = bitmaps.entries.iterator()
            while (bitmapBytes > BITMAP_BUDGET && it.hasNext()) {
                val e = it.next()
                if (e.key == key) continue
                bitmapBytes -= e.value.byteCount
                it.remove()
            }
        }
    }

    /** Decodes downloaded image bytes (e.g. screenshots from the BMS PC); [sample] > 1 keeps a smaller copy. */
    suspend fun decodeBitmap(bytes: ByteArray, sample: Int = 1): Bitmap? = runCatching {
        val full = Image.makeFromEncoded(bytes)
        val img = if (sample > 1) {
            val w = (full.width / sample).coerceAtLeast(1)
            val h = (full.height / sample).coerceAtLeast(1)
            val s = Surface.makeRasterN32Premul(w, h)
            s.canvas.drawImageRect(full, Rect.makeWH(w.toFloat(), h.toFloat()))
            s.makeImageSnapshot().also { s.close(); full.close() }
        } else full
        Bitmap(img.toComposeImageBitmap())
    }.getOrNull()

    fun tacrefImagePath(pic: String?) = pic?.let { "img/tacref/${it.lowercase()}.webp" }

    // ---------- preferences ----------
    private val _favorites = MutableStateFlow<Set<String>>(emptySet())
    val favorites: StateFlow<Set<String>> = _favorites
    val selectedTheater = mutableStateOf("korea-kto")

    fun toggleFavorite(id: String) {
        val s = _favorites.value.toMutableSet()
        if (!s.add(id)) s.remove(id)
        _favorites.value = s
        putStringSet("favorites", s)
    }

    fun setTheater(id: String) {
        selectedTheater.value = id
        putString("theater", id)
    }

    // ---------- preferences (this browser) ----------
    private const val PREFIX = "bmsc."
    private val stringList = ListSerializer(String.serializer())

    private fun loadPrefs() {}

    fun getInt(key: String, def: Int = 0) = storageGet(PREFIX + key)?.toIntOrNull() ?: def
    fun putInt(key: String, v: Int) = storageSet(PREFIX + key, v.toString())
    fun getString(key: String): String? = storageGet(PREFIX + key)
    fun putString(key: String, v: String?) = storageSet(PREFIX + key, v)
    fun getStringSet(key: String): Set<String> =
        getString(key)?.let { runCatching { json.decodeFromString(stringList, it).toSet() }.getOrNull() }.orEmpty()
    fun putStringSet(key: String, v: Set<String>) = storageSet(PREFIX + key, json.encodeToString(stringList, v.toList()))
}

object Labels {
    val weaponCategory = linkedMapOf(
        "AAM_IR" to "IR Air-to-Air",
        "AAM_RADAR" to "Radar Air-to-Air",
        "AGM" to "Air-to-Ground Missiles",
        "ARM" to "Anti-Radiation",
        "ANTI_SHIP" to "Anti-Ship",
        "BOMB_GP" to "General Purpose Bombs",
        "BOMB_LGB" to "Laser Guided Bombs",
        "BOMB_GUIDED" to "GPS / TV / Glide Bombs",
        "BOMB_CLUSTER" to "Cluster Munitions",
        "BOMB_INCENDIARY" to "Incendiary / FAE",
        "BOMB_SPECIAL" to "Special Purpose",
        "BOMB_NUCLEAR" to "Nuclear",
        "ROCKETS" to "Rockets",
        "GUN" to "Guns",
        "GUN_POD" to "Gun Pods",
        "TARGETING_POD" to "Targeting Pods",
        "ECM_POD" to "ECM Pods",
        "CM_POD" to "Countermeasure Pods",
        "AVIONICS_POD" to "Nav / Datalink / Training Pods",
        "RECON_POD" to "Recon Pods",
        "FUEL_TANK" to "Fuel Tanks",
        "SAM" to "Surface-to-Air",
        "ATGM" to "Anti-Tank",
        "MISSILE_OTHER" to "Other Missiles",
        "OTHER" to "Other",
    )

    val threatCategory = linkedMapOf(
        "SAM" to "SAM Systems",
        "SAM_RADAR" to "SAM Radars",
        "SEARCH_RADAR" to "Search Radars",
        "AAA" to "AAA",
        "MANPADS" to "MANPADS",
        "AIRCRAFT" to "Aircraft",
        "AAM" to "Air-to-Air Missiles",
        "SHIP" to "Ships",
        "OTHER" to "Other",
    )
}
