package com.bmscompanion.app.data

import com.bmscompanion.app.data.airfield.Airfield

import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import java.io.File
import java.io.InputStream
import java.net.JarURLConnection
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

/**
 * PC version of the Android Repo: same API, but the bundled BMS data comes from the classpath
 * (app/src/main/assets is added as resources) and preferences live in %APPDATA%\BMS Companion\pc-app.properties.
 */
object Repo {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true; explicitNulls = false }
    private val cache = ConcurrentHashMap<String, Deferred<Any?>>()

    fun init() {
        loadPrefs()
        _favorites.value = getStringSet("favorites")
        selectedTheater.value = getString("theater") ?: "korea-kto"
        // warm up the most used datasets in background
        scope.async {
            // Airfield/map pickers only offer main theaters; migrate an old add-on selection to its main theater.
            val idx = index()
            val cur = idx.theaters.firstOrNull { it.id == selectedTheater.value }
            if (cur != null && !cur.primary) cur.mainTheater?.let { m -> withContext(Dispatchers.Main) { setTheater(m) } }
            aircraft(); weapons()
        }
    }

    private fun open(path: String): InputStream =
        Repo::class.java.classLoader.getResourceAsStream(path)
            ?: throw java.io.FileNotFoundException(path)

    private inline fun <reified T> load(path: String): Deferred<T?> {
        @Suppress("UNCHECKED_CAST")
        return cache.getOrPut(path) {
            scope.async {
                runCatching {
                    open(path).use { stream -> json.decodeFromString(serializer<T>(), stream.readBytes().decodeToString()) }
                }.onFailure { System.err.println("Repo: load $path failed: $it") }.getOrNull()
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
        val files = withContext(Dispatchers.IO) { listResources("data/curated") }
        return files.filter { it.startsWith("checklist_") }.sortedBy { listOf("checklist_f16cm.json", "checklist_f16cj.json", "checklist_f15c.json").indexOf(it).let { i -> if (i < 0) 99 else i } }.mapNotNull { load<Checklist>("data/curated/$it").await() }
    }

    /** File names directly inside a resource folder, whether running from the build folders or from the packaged jar. */
    private fun listResources(dir: String): List<String> {
        val url = Repo::class.java.classLoader.getResource("$dir/") ?: Repo::class.java.classLoader.getResource(dir) ?: return emptyList()
        return runCatching {
            when (url.protocol) {
                "file" -> File(url.toURI()).list()?.toList().orEmpty()
                // uncached connection: closing a cached JarFile would break the class loader that shares it
                "jar" -> (url.openConnection() as JarURLConnection).apply { useCaches = false }.jarFile.use { jar ->
                    jar.entries().toList().map { it.name }.filter { it.startsWith("$dir/") && it.length > dir.length + 1 }
                        .map { it.removePrefix("$dir/") }.filter { '/' !in it }
                }
                else -> emptyList()
            }
        }.getOrDefault(emptyList())
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
    // charts and map tiles; the map keeps its own visible tiles, so this only has to cover what is reused
    /** The PC always has room for full-size map tiles (see the Android Repo). */
    const val lowMemory = false

    private val BITMAP_BUDGET = (Runtime.getRuntime().maxMemory() / 8).coerceIn(24L * 1024 * 1024, 64L * 1024 * 1024)
    private val bitmaps = object : LinkedHashMap<String, Bitmap>(64, 0.75f, true) {}
    private var bitmapBytes = 0L

    suspend fun bitmap(path: String, sample: Int = 1): Bitmap? {
        val key = "$path@$sample"
        synchronized(bitmaps) { bitmaps[key] }?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                val full = Image.makeFromEncoded(open(path).use { it.readBytes() })
                val img = if (sample > 1) {
                    // thumbnails (e.g. chart previews): keep a downscaled copy only, like Android's inSampleSize
                    val w = (full.width / sample).coerceAtLeast(1)
                    val h = (full.height / sample).coerceAtLeast(1)
                    Surface.makeRasterN32Premul(w, h).use { s ->
                        s.canvas.drawImageRect(full, Rect.makeWH(w.toFloat(), h.toFloat()))
                        s.makeImageSnapshot()
                    }.also { full.close() }
                } else full
                Bitmap(img.toComposeImageBitmap())
            }.getOrNull()?.also { b ->
                synchronized(bitmaps) {
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
        }
    }

    /** Decodes downloaded image bytes (e.g. screenshots from the BMS PC); [sample] > 1 keeps a smaller copy. */
    suspend fun decodeBitmap(bytes: ByteArray, sample: Int = 1): Bitmap? = withContext(Dispatchers.Default) {
        runCatching {
            val full = Image.makeFromEncoded(bytes)
            val img = if (sample > 1) {
                val w = (full.width / sample).coerceAtLeast(1)
                val h = (full.height / sample).coerceAtLeast(1)
                Surface.makeRasterN32Premul(w, h).use { s -> s.canvas.drawImageRect(full, Rect.makeWH(w.toFloat(), h.toFloat())); s.makeImageSnapshot() }.also { full.close() }
            } else full
            Bitmap(img.toComposeImageBitmap())
        }.getOrNull()
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
        putStringSet("favorites", s)
    }

    fun setTheater(id: String) {
        selectedTheater.value = id
        putString("theater", id)
    }

    /** Same folder as the bridge's settings. */
    val settingsFolder: File = File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "BMS Companion")
    private val prefsFile = File(settingsFolder, "pc-app.properties")
    private val prefs = Properties()
    private val stringList = ListSerializer(String.serializer())

    private fun loadPrefs() {
        runCatching { if (prefsFile.exists()) prefsFile.inputStream().use { prefs.load(it) } }
    }

    private fun savePrefs() {
        val snapshot = synchronized(prefs) { Properties().also { it.putAll(prefs) } }
        scope.async {
            synchronized(prefsFile) {
                runCatching {
                    settingsFolder.mkdirs()
                    val tmp = File(settingsFolder, "pc-app.properties.tmp")
                    tmp.outputStream().use { snapshot.store(it, "BMS Companion PC app") }
                    if (!tmp.renameTo(prefsFile)) { prefsFile.delete(); tmp.renameTo(prefsFile) }
                }
            }
        }
    }

    private fun set(key: String, v: String?) {
        synchronized(prefs) { if (v == null) prefs.remove(key) else prefs.setProperty(key, v) }
        savePrefs()
    }

    fun getInt(key: String, def: Int = 0) = synchronized(prefs) { prefs.getProperty(key) }?.toIntOrNull() ?: def
    fun putInt(key: String, v: Int) = set(key, v.toString())
    fun getString(key: String): String? = synchronized(prefs) { prefs.getProperty(key) }
    fun putString(key: String, v: String?) = set(key, v)
    fun getStringSet(key: String): Set<String> =
        getString(key)?.let { runCatching { json.decodeFromString(stringList, it).toSet() }.getOrNull() }.orEmpty()
    fun putStringSet(key: String, v: Set<String>) = set(key, json.encodeToString(stringList, v.toList()))
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
