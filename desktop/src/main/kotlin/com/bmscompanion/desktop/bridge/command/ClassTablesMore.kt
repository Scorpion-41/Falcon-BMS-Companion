package com.bmscompanion.desktop.bridge.command

import com.bmscompanion.desktop.bridge.CampaignArchive
import com.bmscompanion.desktop.bridge.Theaters
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * The class tables the editors need beyond the names the rest of the PC side reads ([CampaignArchive.names]): any
 * `Falcon4_<X>.xml` of a theater with **every** field of each record ([Tables.table]: UCD, VCD, WCD, ACD, RCD, SSD,
 * WLD, FCD, ICD, SWD, RWD, VSD, DDP, RKT, CT), and the per-objective data of `ObjectiveRelatedData\OCD_nnnnn\`: the
 * objective class itself (icon, features, radar feature, detection and damage), its features (FED), its point headers
 * (PHD: runways, taxiways, parking, SAM/AAA sites…) and points (PDX). Falcon BMS 4.38.1 ships these as XML only.
 *
 * Read only, scanned (not parsed — the class table is 8 MB), cached by file path, size and time. Nothing throws: an
 * unreadable file is an empty table.
 */
object ClassTablesMore {
    /** One XML record: its number and fields by tag. */
    class Rec(val num: Int, val fields: Map<String, String>) {
        operator fun get(k: String): String? = fields[k]
        fun int(k: String, default: Int = 0): Int = fields[k]?.trim()?.toIntOrNull() ?: fields[k]?.trim()?.toDoubleOrNull()?.toInt() ?: default
        fun float(k: String, default: Float = 0f): Float = fields[k]?.trim()?.toFloatOrNull() ?: default
        val name: String get() = fields["Name"]?.trim().orEmpty()
    }

    /** An objective class (`OCD_nnnnn.XML`): name, class-table index, icon, radar feature and the rest of its fields. */
    class Ocd(val rec: Rec) {
        val num: Int get() = rec.num
        val name: String get() = rec.name
        val ctIdx: Int get() = rec.int("CtIdx", -1)
        val icon: Int get() = rec.int("ObjectiveIcon")
        val radarFeature: Int get() = rec.int("RadarFeature", 255)
        val dataRate: Int get() = rec.int("DataRate")
    }

    /** A feature of an objective (FED): its class-table index, value (importance), offset (feet, x north) and heading. */
    class Feature(val num: Int, val ctIdx: Int, val value: Int, val offsetX: Float, val offsetY: Float, val offsetZ: Float, val heading: Float)

    /** A point header (PHD): its type (runway, taxi, parking, SAM, AAA…), how many points, the first, runway data. */
    class PtHeader(val num: Int, val objIdx: Int, val type: Int, val pointCount: Int, val firstPt: Int, val data: Float, val runwayNumber: Int, val rec: Rec)

    /** A point (PDX): offset (feet, x north), type, flags, heading. */
    class PtPoint(val num: Int, val offsetX: Float, val offsetY: Float, val offsetZ: Float, val type: Int, val flags: Int, val heading: Float)

    class Tables internal constructor(private val set: Theaters.TheaterSet?, private val theater: Theaters.Theater?) {
        /** The file a table is read from: the theater's `objectdir`, else `Data\TerrData\Objects`. */
        fun file(tag: String): File? = set?.classFile(theater, "Falcon4_$tag.xml")

        /** Every record of table [tag] ("UCD", "SSD"…), by number. */
        fun table(tag: String): Map<Int, Rec> = file(tag)?.let { readTable(it, tag) }.orEmpty()
        fun record(tag: String, num: Int): Rec? = table(tag)[num]

        /** The folder of the per-objective data. */
        val objectiveData: File? by lazy {
            val dirs = listOfNotNull(set?.objectDir(theater), set?.data?.let { File(it, "TerrData/Objects") })
            dirs.map { File(it, "ObjectiveRelatedData") }.firstOrNull { it.isDirectory }
        }

        /** Every objective class, by OCD number (the class-table entry's `EntityIdx`). */
        val ocd: Map<Int, Ocd> by lazy {
            val root = objectiveData ?: return@lazy emptyMap()
            val out = HashMap<Int, Ocd>()
            root.listFiles()?.filter { it.isDirectory && it.name.startsWith("OCD_", ignoreCase = true) }?.forEach { d ->
                val n = d.name.substring(4).toIntOrNull() ?: return@forEach
                val f = perObjective(d, "OCD", n) ?: return@forEach
                readTable(f, "OCD")[n]?.let { out[n] = Ocd(it) }
            }
            out
        }

        fun features(ocdNum: Int): List<Feature> = perObjectiveTable(ocdNum, "FED").values.sortedBy { it.num }.map {
            Feature(it.num, it.int("FeatureCtIdx", -1), it.int("Value"), it.float("OffsetX"), it.float("OffsetY"), it.float("OffsetZ"), it.float("Heading"))
        }

        fun pointHeaders(ocdNum: Int): List<PtHeader> = perObjectiveTable(ocdNum, "PHD").values.sortedBy { it.num }.map {
            PtHeader(it.num, it.int("ObjIdx"), it.int("Type"), it.int("PointCount"), it.int("FirstPtIdx"), it.float("Data"), it.int("RunwayNumber"), it)
        }

        fun points(ocdNum: Int): List<PtPoint> = perObjectiveTable(ocdNum, "PDX", "PD").values.sortedBy { it.num }.map {
            PtPoint(it.num, it.float("OffsetX"), it.float("OffsetY"), it.float("OffsetZ"), it.int("Type"), it.int("Flags"), it.float("Heading"))
        }

        /** A squadron stores template (SSD n): weapon id → count, as a new squadron of that unit class is stocked. */
        fun squadronStores(ssd: Int): Map<Int, Int> = record("SSD", ssd)?.fields.orEmpty()
            .mapNotNull { (k, v) -> if (k.startsWith("WpnStores_")) k.removePrefix("WpnStores_").toIntOrNull()?.let { it to (v.trim().toIntOrNull() ?: 0) } else null }
            .toMap()

        /** A weapon list (WLD n): its name and (weapon id, count) per slot. */
        fun weaponList(wld: Int): Pair<String, List<Pair<Int, Int>>>? = record("WLD", wld)?.let { r ->
            r.name to (0 until 64).mapNotNull { i -> r["WpnIdx_$i"]?.trim()?.toIntOrNull()?.let { it to r.int("WpnCount_$i") } }
        }

        private fun perObjectiveTable(ocdNum: Int, kind: String, tag: String = kind): Map<Int, Rec> {
            val root = objectiveData ?: return emptyMap()
            val d = File(root, "OCD_%05d".format(ocdNum))
            val f = perObjective(d, kind, ocdNum) ?: return emptyMap()
            return readTable(f, tag)
        }

        private fun perObjective(dir: File, kind: String, n: Int): File? {
            val want = "%s_%05d.xml".format(kind, n)
            File(dir, want.uppercase()).takeIf { it.isFile }?.let { return it }
            return dir.listFiles()?.firstOrNull { it.isFile && it.name.equals(want, ignoreCase = true) }
        }
    }

    private val tablesCache = ConcurrentHashMap<String, Tables>()
    private val fileCache = ConcurrentHashMap<String, Map<Int, Rec>>()

    fun forTheater(set: Theaters.TheaterSet?, theater: Theaters.Theater?): Tables {
        val key = (set?.root?.absolutePath ?: "-") + "|" + (theater?.name ?: "-")
        return tablesCache.getOrPut(key) { Tables(set, theater) }
    }

    /** Every `<tag Num="n">` record of [file] with all its child fields. Cached by path, size and time. */
    fun readTable(file: File, tag: String): Map<Int, Rec> {
        val key = CampaignArchive.key(file) + "|" + tag
        fileCache[key]?.let { return it }
        val out = HashMap<Int, Rec>()
        try {
            val text = file.readText(Charsets.UTF_8)
            val open = "<$tag Num=\""
            val close = "</$tag>"
            var i = text.indexOf(open)
            while (i >= 0) {
                val numEnd = text.indexOf('"', i + open.length)
                if (numEnd < 0) break
                val num = text.substring(i + open.length, numEnd).trim().toIntOrNull()
                var end = text.indexOf(close, numEnd)
                if (end < 0) end = text.length
                if (num != null) {
                    val f = LinkedHashMap<String, String>()
                    var j = text.indexOf('<', text.indexOf('>', numEnd) + 1)
                    while (j in 0 until end) {
                        val gt = text.indexOf('>', j)
                        if (gt < 0 || gt > end) break
                        if (text[j + 1] != '/' && text[gt - 1] != '/') {
                            val name = text.substring(j + 1, gt).substringBefore(' ')
                            val endTag = text.indexOf("</$name>", gt)
                            if (endTag in 0..end) { f[name] = unescape(text.substring(gt + 1, endTag)); j = text.indexOf('<', endTag + name.length + 3); continue }
                        }
                        j = text.indexOf('<', gt)
                    }
                    out[num] = Rec(num, f)
                }
                i = text.indexOf(open, end)
            }
        } catch (_: Throwable) {
            // unreadable: an empty table
        }
        fileCache[key] = out
        return out
    }

    private fun unescape(s: String): String =
        if ('&' !in s) s else s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
}
