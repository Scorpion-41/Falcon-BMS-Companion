package com.bmscompanion.app.data

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import java.util.concurrent.ConcurrentHashMap

/** Loads bundled BMS data from assets lazily and caches it for the app lifetime. */
object Repo {
    private lateinit var app: Context
    private lateinit var prefs: SharedPreferences
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true; explicitNulls = false }
    private val cache = ConcurrentHashMap<String, Deferred<Any?>>()

    fun init(context: Context) {
        app = context.applicationContext
        prefs = app.getSharedPreferences("bmscompanion", Context.MODE_PRIVATE)
        _favorites.value = prefs.getStringSet("favorites", emptySet())!!.toSet()
        selectedTheater.value = prefs.getString("theater", "korea-kto") ?: "korea-kto"
        // warm up the most used datasets in background
        scope.async {
            // Airfield/map pickers only offer main theaters; migrate an old add-on selection to its main theater.
            val idx = index()
            val cur = idx.theaters.firstOrNull { it.id == selectedTheater.value }
            if (cur != null && !cur.primary) cur.mainTheater?.let { m -> kotlinx.coroutines.withContext(Dispatchers.Main) { setTheater(m) } }
            aircraft(); weapons()
        }
    }

    private inline fun <reified T> load(path: String): Deferred<T?> {
        @Suppress("UNCHECKED_CAST")
        return cache.getOrPut(path) {
            scope.async {
                runCatching {
                    app.assets.open(path).use { stream ->
                        val text = stream.readBytes().decodeToString()
                        json.decodeFromString(serializer<T>(), text)
                    }
                }.onFailure { android.util.Log.e("Repo", "load $path", it) }.getOrNull()
            }
        } as Deferred<T?>
    }

    suspend fun index(): DataIndex = load<DataIndex>("data/index.json").await() ?: DataIndex()
    suspend fun aircraft(): List<Aircraft> = load<List<Aircraft>>("data/aircraft.json").await().orEmpty()
    suspend fun weapons(): List<Weapon> = load<List<Weapon>>("data/weapons.json").await().orEmpty()
    suspend fun encyclopedia(): List<EncyEntry> = load<List<EncyEntry>>("data/encyclopedia.json").await().orEmpty()
    suspend fun airportSet(id: String): AirportSet = load<AirportSet>("data/airports/$id.json").await() ?: AirportSet()
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
        val files = withContext(Dispatchers.IO) { app.assets.list("data/curated")?.toList().orEmpty() }
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
    suspend fun geo(mapId: String): GeoLayers? = load<GeoLayers>("data/geo/$mapId.json").await()

    // ---------- images ----------
    // a share of the heap this device allows (old tablets get ~64-128 MB in total), never more than 40 MB
    private val bitmaps = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).coerceIn(8L * 1024 * 1024, 40L * 1024 * 1024).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    suspend fun bitmap(path: String, sample: Int = 1): Bitmap? {
        val key = "$path@$sample"
        bitmaps.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            runCatching { app.assets.open(path).use { BitmapFactory.decodeStream(it, null, opts) } }.getOrNull()?.also { bitmaps.put(key, it) }
        }
    }

    /**
     * A device with little memory for apps (old tablets and phones): maps then load half-size tiles, which look
     * slightly softer but use a quarter of the memory. Forced on with the BMSC_SMALL_TILES debug setting.
     */
    val lowMemory: Boolean by lazy {
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        am.isLowRamDevice || am.memoryClass <= 192 || getInt("small_tiles", 0) == 1
    }

    /** Drops cached images (e.g. when the system asks for memory back). */
    fun trimBitmaps() = bitmaps.evictAll()

    /** Decodes downloaded image bytes (e.g. screenshots from the BMS PC); [sample] > 1 decodes a smaller copy. */
    suspend fun decodeBitmap(bytes: ByteArray, sample: Int = 1): Bitmap? = withContext(Dispatchers.Default) {
        runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) }.getOrNull()
    }

    fun tacrefImagePath(pic: String?) = pic?.let { "img/tacref/${it.lowercase()}.webp" }

    // ---------- preferences ----------
    private val _favorites = MutableStateFlow<Set<String>>(emptySet())
    val favorites: StateFlow<Set<String>> = _favorites
    val selectedTheater = mutableStateOf("korea-kto")

    fun toggleFavorite(id: String) {
        val s = _favorites.value.toMutableSet()
        if (!s.add(id)) s.remove(id)
        _favorites.value = s
        prefs.edit().putStringSet("favorites", s).apply()
    }

    fun setTheater(id: String) {
        selectedTheater.value = id
        prefs.edit().putString("theater", id).apply()
    }

    fun getInt(key: String, def: Int = 0) = prefs.getInt(key, def)
    fun putInt(key: String, v: Int) = prefs.edit().putInt(key, v).apply()
    fun getString(key: String): String? = prefs.getString(key, null)
    fun putString(key: String, v: String?) = prefs.edit().putString(key, v).apply()
    fun getStringSet(key: String): Set<String> = prefs.getStringSet(key, emptySet())!!.toSet()
    fun putStringSet(key: String, v: Set<String>) = prefs.edit().putStringSet(key, v).apply()
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
