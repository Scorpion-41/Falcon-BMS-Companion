package com.bmscompanion.desktop.bridge

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * What a target is in Falcon BMS's own class tables, and which 3-D model the sim draws it with — for the target
 * pictures ([TargetPictures]). Read only, nothing throws.
 *
 * - **A thing with a model** ([Kind]) is a class-table entry (`Falcon4_CT.xml`, numbered from 0 — the key of every
 *   picture) whose `GraphicsNormal` names a model folder: `Models/<n>` under the theater's 3-D data folder, its object
 *   folder, else `Data/TerrData/Objects` ([modelDir]). A vehicle's entry points at the vehicle table
 *   (`Falcon4_VCD.xml`: its name, its radar, the weapons it shows on its slots — `WpnOrHpIdx_n` with bit n of
 *   `VisibleFlags`, each weapon's own entry and model through `Falcon4_WCD.xml`), a building's at the feature table
 *   (`Falcon4_FCD.xml`).
 * - **A system's battalion** ([battalion]) is the unit table's own (`Falcon4_UCD.xml`: `VehicleCtIdx_n` ×
 *   `ElementCount_n`, and `RadarVehicle` the slot of its fire-control radar): an SA-6 battalion is its 2P25 launchers,
 *   the Straight Flush, a Flat Face, the 2T7M transloaders, a command vehicle and trucks — BMS's data, not a hand list.
 * - **The list to add from** ([catalog]): every vehicle of the land and the sea domains and every building, by the
 *   class table's type and sub-type (air defence split into launchers, radars and support by whether the vehicle
 *   carries a weapon and has a radar).
 *
 * The tables are the theater's own (`Theaters.TheaterSet.classFile`), cached by their files' keys.
 */
object TargetModels {
    /** One class-table entry that has a model. [ct] is its number in `Falcon4_CT.xml`. */
    class Kind(
        val ct: Int,
        val name: String,
        val gfx: Int,
        val vehicle: Boolean,
        val category: String,
        val domain: Int,
        val type: Int,
        val subType: Int,
        /** for a vehicle: the model numbers of what it shows on each slot (a launcher's missiles) */
        val slotModels: Map<Int, Int> = emptyMap(),
        val radar: Boolean = false,
        val armed: Boolean = false,
    )

    /** One member of a battalion: what it is, its part ("launcher", "radar", "air defence", "support") and how many. */
    class Member(val kind: Kind, val role: String, val count: Int)

    class Tables(
        val set: Theaters.TheaterSet,
        val theater: Theaters.Theater?,
        val key: String,
        val kinds: Map<Int, Kind>,
        /** unit table rows: radar slot and (vehicle class number, count) per slot */
        val units: List<UnitRow>,
        /** objective class number (an OCD index) by class-table number */
        val objectiveOcd: Map<Int, Int>,
        val unitUcd: Map<Int, Int>,
    )

    class UnitRow(val ucd: Int, val radarSlot: Int?, val slots: List<Pair<Int, Int>>)

    // ---------------------------------------------------------------- categories, in the order the list shows them

    const val SAM = "SAM launchers"
    const val RADAR = "Radars"
    const val AD_SUPPORT = "Air-defence support"
    const val AAA = "Anti-aircraft guns"
    const val TANK = "Tanks"
    const val APC = "APCs and IFVs"
    const val ARTY = "Artillery and rockets"
    const val SSM = "Surface-to-surface missiles"
    const val TRUCK = "Trucks"
    const val INFANTRY = "Infantry"
    const val OTHER = "Other vehicles"
    const val SHIP = "Ships"
    const val BUILDING = "Buildings and structures"
    val CATEGORIES = listOf(SAM, RADAR, AD_SUPPORT, AAA, TANK, APC, ARTY, SSM, TRUCK, INFANTRY, OTHER, SHIP, BUILDING)

    private const val CT_FILE = "Falcon4_CT.xml"
    private const val VCD_FILE = "Falcon4_VCD.xml"
    private const val FCD_FILE = "Falcon4_FCD.xml"
    private const val WCD_FILE = "Falcon4_WCD.xml"
    private const val UCD_FILE = "Falcon4_UCD.xml"
    private const val SLOTS = 16

    // class-table entity types (which table EntityIdx points into)
    private const val E_FEATURE = 1
    private const val E_OBJECTIVE = 3
    private const val E_UNIT = 4
    private const val E_VEHICLE = 5
    private const val E_WEAPON = 6

    private val cache = ConcurrentHashMap<String, Tables>()

    private val CT_FIELDS = setOf("Domain", "Class", "Type", "SubType", "EntityType", "EntityIdx", "GraphicsNormal")
    private val VCD_FIELDS = setOf("Name", "RadarIdx", "VisibleFlags") + (0 until SLOTS).flatMap { listOf("WpnOrHpIdx_$it", "WpnCount_$it") }
    private val UCD_FIELDS = setOf("RadarVehicle") + (0 until SLOTS).flatMap { listOf("VehicleCtIdx_$it", "ElementCount_$it") }

    /** Theater [t]'s tables (the current theater's when null), or null when its class table is missing. */
    fun tables(set: Theaters.TheaterSet, t: Theaters.Theater?): Tables? = try {
        val files = listOf(CT_FILE, VCD_FILE, FCD_FILE, WCD_FILE, UCD_FILE).associateWith { set.classFile(t, it) }
        val ctFile = files[CT_FILE]
        if (ctFile == null) null
        else {
            val key = (files.values.map { it?.let(CampaignArchive::key) ?: "-" } + listOf(set.root.path, t?.name ?: "-")).joinToString("|")
            cache[key] ?: build(set, t, files, key).also { cache[key] = it }
        }
    } catch (e: Throwable) {
        BridgeLog.info("The class tables for the target pictures could not be read: ${e.message}")
        null
    }

    private fun build(set: Theaters.TheaterSet, t: Theaters.Theater?, files: Map<String, File?>, key: String): Tables {
        fun Map<String, String>.int(k: String): Int? = this[k]?.trim()?.toIntOrNull()
        val ct = CampaignArchive.table(files[CT_FILE], "CT", CT_FIELDS)
        val vcd = CampaignArchive.table(files[VCD_FILE], "VCD", VCD_FIELDS)
        val fcd = CampaignArchive.table(files[FCD_FILE], "FCD", setOf("Name"))
        val wcd = CampaignArchive.table(files[WCD_FILE], "WCD", setOf("CtIdx"))
        val ucd = CampaignArchive.table(files[UCD_FILE], "UCD", UCD_FIELDS)

        val units = ucd.entries.sortedBy { it.key }.map { (n, f) ->
            UnitRow(n, f.int("RadarVehicle")?.takeIf { it in 0 until SLOTS },
                (0 until SLOTS).mapNotNull { i -> val c = f.int("ElementCount_$i") ?: 0; val v = f.int("VehicleCtIdx_$i") ?: -1; if (c > 0 && v >= 0) v to c else null })
        }
        val radarVehicles = HashSet<Int>()
        for (u in units) u.radarSlot?.let { s -> u.slots.getOrNull(s)?.first?.let(radarVehicles::add) }

        val kinds = HashMap<Int, Kind>()
        val objectiveOcd = HashMap<Int, Int>()
        val unitUcd = HashMap<Int, Int>()
        for ((n, c) in ct) {
            val entity = c.int("EntityType") ?: continue
            val idx = c.int("EntityIdx") ?: continue
            when (entity) {
                E_OBJECTIVE -> { objectiveOcd[n] = idx; continue }
                E_UNIT -> { unitUcd[n] = idx; continue }
            }
            val gfx = c.int("GraphicsNormal") ?: continue
            if (gfx <= 0) continue
            val domain = c.int("Domain") ?: 0
            val type = c.int("Type") ?: 0
            val sub = c.int("SubType") ?: 0
            when (entity) {
                E_VEHICLE -> {
                    if (domain != 3 && domain != 4 && domain != 7) continue // aircraft are not ground targets
                    val v = vcd[idx] ?: continue
                    val name = v["Name"]?.trim().orEmpty()
                    if (name.isEmpty()) continue
                    val visible = v.int("VisibleFlags") ?: 0
                    val slots = HashMap<Int, Int>()
                    var armed = false
                    for (i in 0 until SLOTS) {
                        val w = v.int("WpnOrHpIdx_$i") ?: continue
                        if (w <= 0) continue
                        val wct = wcd[w]?.int("CtIdx") ?: continue
                        armed = true
                        if (visible and (1 shl i) == 0) continue
                        val wc = ct[wct] ?: continue
                        if (wc.int("EntityType") != E_WEAPON) continue
                        wc.int("GraphicsNormal")?.takeIf { it > 0 }?.let { slots[i] = it }
                    }
                    val radar = (v.int("RadarIdx") ?: 0) > 0 || n in radarVehicles
                    kinds[n] = Kind(n, name, gfx, true, category(domain, type, sub, name, radar, armed), domain, type, sub, slots, radar, armed)
                }
                E_FEATURE -> {
                    val name = fcd[idx]?.get("Name")?.trim().orEmpty()
                    if (name.isEmpty()) continue
                    kinds[n] = Kind(n, name, gfx, false, BUILDING, domain, type, sub)
                }
            }
        }
        return Tables(set, t, key, kinds, units, objectiveOcd, unitUcd)
    }

    private val GENERIC_WORDS = setOf(
        "launcher", "launchers", "missile", "missiles", "radar", "system", "site", "battery", "mobile", "sam", "aaa", "gun",
        "guns", "tel", "telar", "tracked", "wheeled", "towed", "self", "propelled", "air", "defense", "defence", "the", "and",
    )

    /** Towed and self-propelled field guns BMS's class table files with the air defences. */
    private val ARTY_NAMES = Regex("""\b(2A18|D-30|D-20|M-?46|M-?101|M-?102|M-?114|M-?198|M-?777|howitzer)\b""", RegexOption.IGNORE_CASE)
    private val MISSILE_WORDS =Regex("""\b(frog|scud|ss-\d+|hwasong|nodong|lance|atacms|iskander|tochka)\b""", RegexOption.IGNORE_CASE)

    /** The list's category of a vehicle from the class table's domain, type and sub-type. */
    private fun category(domain: Int, type: Int, sub: Int, name: String, radar: Boolean, armed: Boolean): String = when {
        domain == 4 || domain == 7 -> SHIP
        type == 1 -> INFANTRY
        // BMS's class table files a few towed guns with the air defences (sub 2): the 2A18 (D-30) howitzer was
        // listed as a SAM launcher
        sub == 2 && ARTY_NAMES.containsMatchIn(name) -> ARTY
        sub == 2 && armed -> SAM
        sub == 2 && radar -> RADAR
        sub == 2 -> AD_SUPPORT
        sub == 1 -> AAA
        sub == 3 && type == 3 -> TANK
        sub == 7 -> APC
        MISSILE_WORDS.containsMatchIn(name) -> SSM
        type == 2 || sub == 4 || (type == 4 && sub == 3) -> ARTY
        sub == 8 -> TRUCK
        else -> OTHER
    }

    private val folders = ConcurrentHashMap<String, Map<Int, File>>()

    /**
     * The model folders a theater can use, by model number: its 3-D data folder's `Models`, its object folder's, then
     * `Data/TerrData/Objects/Models` — the first that has a number wins. Listed once per set of tables.
     */
    private fun modelFolders(tables: Tables): Map<Int, File> = folders.getOrPut(tables.key) {
        val roots = listOfNotNull(
            tables.set.threeDDataDir(tables.theater), tables.set.objectDir(tables.theater),
            Theaters.resolveDir(tables.set.data, "TerrData\\Objects"),
        ).distinctBy { Theaters.canonical(it) }
        val out = HashMap<Int, File>()
        for (r in roots) {
            val models = Theaters.resolveDir(r, "Models") ?: continue
            val list = try { models.listFiles()?.toList().orEmpty() } catch (_: Throwable) { emptyList() }
            for (d in list) {
                val n = d.name.toIntOrNull() ?: continue
                if (n !in out && d.isDirectory) out[n] = d
            }
        }
        out
    }

    /** The model folder of [gfx]: the theater's 3-D data folder, its object folder, then `Data/TerrData/Objects`. */
    fun modelDir(tables: Tables, gfx: Int): File? = modelFolders(tables)[gfx]

    /** The most detailed model file of [gfx] (`Model_0.bml`), or null. */
    fun modelFile(tables: Tables, gfx: Int): File? = modelDir(tables, gfx)?.let { Theaters.resolveFile(it, "Model_0.bml") }

    /** A vehicle or building by its exact name, then by its system ([com.bmscompanion.app.data.mission.MissionPicture.sameSystem]: "SA-6", "SA-6 (2K12)"). */
    fun byName(tables: Tables, name: String): Kind? {
        val n = name.trim()
        if (n.isEmpty()) return null
        val all = tables.kinds.values.sortedBy { it.ct }
        all.firstOrNull { it.vehicle && it.name.equals(n, true) }?.let { return it }
        val key = com.bmscompanion.app.data.mission.MissionPicture.systemKey(n)
        if (key.isEmpty()) return null
        // prefer what fires (the launcher) among the vehicles of one system
        fun best(list: List<Kind>) = list.sortedWith(compareByDescending<Kind> { it.armed }.thenBy { it.ct }).firstOrNull()
        best(all.filter { it.vehicle && com.bmscompanion.app.data.mission.MissionPicture.systemKey(it.name) == key })?.let { return it }
        all.firstOrNull { !it.vehicle && it.name.equals(n, true) }?.let { return it }
        // another word of the name ("MIM-104 Patriot" → "Patriot", "Gainful (2K12)" → "2K12"), one that says something
        val words = n.split(' ', '(', ')', '/', ',').map { w -> w.lowercase().filter { it.isLetterOrDigit() } }
            .filter { it.length >= 3 && it != key && it !in GENERIC_WORDS }
        for (w in words) best(all.filter { it.vehicle && it.name.split(' ', '(', ')', '/').any { p -> p.lowercase().filter { c -> c.isLetterOrDigit() } == w } })?.let { return it }
        return null
    }

    /**
     * The battalion [system] fields in, by the unit table: the unit with the most kinds of vehicle among those whose
     * first slot is the system, else any that has it; its vehicles in the order a pilot looks for them — the system,
     * the fire-control radar ([UnitRow.radarSlot]), other radars, the other air defences, the rest, trucks last. [max]
     * kinds at most. Just the system itself when no unit has it.
     */
    fun battalion(tables: Tables, system: Kind, max: Int = 8): List<Member> {
        val withIt = tables.units.filter { u -> u.slots.any { it.first == system.ct } }
        val first = withIt.filter { it.slots.firstOrNull()?.first == system.ct }
        val unit = (first.ifEmpty { withIt }).maxByOrNull { u -> u.slots.map { it.first }.distinct().size }
            ?: return listOf(Member(system, if (system.armed) "launcher" else "system", 1))
        val counts = LinkedHashMap<Int, Int>()
        for ((v, c) in unit.slots) counts[v] = (counts[v] ?: 0) + c
        val fireControl = unit.radarSlot?.let { unit.slots.getOrNull(it)?.first }
        fun rank(k: Kind): Int = when {
            k.ct == system.ct -> 0
            k.ct == fireControl -> 1
            k.radar -> 2
            k.category == SAM || k.category == AAA || k.category == AD_SUPPORT -> 3
            k.category == TRUCK -> 5
            else -> 4
        }
        fun role(k: Kind): String = when {
            k.ct == system.ct -> if (k.armed) "launcher" else "system"
            k.radar -> "radar"
            k.category == SAM || k.category == AAA || (k.category == INFANTRY && k.armed) -> "air defence"
            else -> "support"
        }
        return counts.keys.mapNotNull { tables.kinds[it] }
            .sortedWith(compareBy<Kind> { rank(it) }.thenBy { it.ct })
            .take(max)
            .map { Member(it, role(it), counts[it.ct] ?: 1) }
    }

    /** The vehicles of the unit of class-table number [unitCt] (a strike's target battalion), as [battalion] orders them. */
    fun unitMembers(tables: Tables, unitCt: Int, max: Int = 8): List<Member> {
        val ucd = tables.unitUcd[unitCt] ?: return emptyList()
        val unit = tables.units.firstOrNull { it.ucd == ucd } ?: return emptyList()
        val main = unit.slots.firstOrNull()?.first?.let { tables.kinds[it] } ?: return emptyList()
        val withMain = battalion(tables, main, max)
        // the unit's own vehicles, not the best battalion of its first vehicle
        val own = unit.slots.map { it.first }.toSet()
        return withMain.filter { it.kind.ct in own }.ifEmpty { listOf(Member(main, "unit", 1)) }
    }

    /**
     * The buildings of the objective of class-table number [objectiveCt] (`ObjectiveRelatedData/OCD_n/FED_n.XML`), by
     * their feature index; [building] picks one (a strike on one building). Distinct kinds, the objective's order.
     */
    fun objectiveFeatures(tables: Tables, objectiveCt: Int, building: Int? = null, max: Int = 6): List<Kind> {
        val ocd = tables.objectiveOcd[objectiveCt] ?: return emptyList()
        val fed = fedFile(tables, ocd)?.let { CampaignArchive.table(it, "FED", setOf("FeatureCtIdx", "Value")) }.orEmpty()
        val list = fed.keys.sorted().map { fed.getValue(it) }
        fun kind(f: Map<String, String>): Kind? = f["FeatureCtIdx"]?.trim()?.toIntOrNull()?.let { tables.kinds[it] }
        if (building != null && building in list.indices) return listOfNotNull(kind(list[building]))
        // the valuable ones first: the feature value is what the campaign scores a building by
        return list.sortedByDescending { it["Value"]?.trim()?.toIntOrNull() ?: 0 }.mapNotNull(::kind).distinctBy { it.name }.take(max)
    }

    private fun fedFile(tables: Tables, ocd: Int): File? {
        val id = ocd.toString().padStart(5, '0')
        val roots = listOfNotNull(tables.set.threeDDataDir(tables.theater), tables.set.objectDir(tables.theater), Theaters.resolveDir(tables.set.data, "TerrData\\Objects"))
        for (r in roots) {
            val dir = Theaters.resolveDir(r, "ObjectiveRelatedData\\OCD_$id") ?: continue
            Theaters.resolveFile(dir, "FED_$id.XML")?.let { return it }
        }
        return null
    }

    /**
     * Every target to pick from, by category ([CATEGORIES] order), each name once (the lowest class number; the class
     * table repeats a vehicle for each country that fields it), alphabetical. Only those whose model folder exists.
     */
    fun catalog(tables: Tables): List<Pair<String, List<Kind>>> {
        val seen = HashMap<String, Kind>()
        for (k in tables.kinds.values.sortedBy { it.ct }) {
            val id = k.category + "|" + k.name.lowercase()
            if (id !in seen && modelDir(tables, k.gfx) != null) seen[id] = k
        }
        return CATEGORIES.mapNotNull { c ->
            seen.values.filter { it.category == c }.sortedBy { it.name.lowercase() }.takeIf { it.isNotEmpty() }?.let { c to it }
        }
    }
}
