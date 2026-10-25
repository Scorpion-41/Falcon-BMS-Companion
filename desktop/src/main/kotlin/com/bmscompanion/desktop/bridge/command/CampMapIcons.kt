package com.bmscompanion.desktop.bridge.command

import com.bmscompanion.desktop.bridge.CampaignArchive
import com.bmscompanion.desktop.bridge.Theaters
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Falcon BMS's own map icons, read at run time for the Command section's "BMS icons" look (read only, nothing kept on
 * disk). An objective's icon is its class's `ObjectiveIcon` (`ObjectiveRelatedData\OCD_nnnnn\OCD_nnnnn.XML`), a unit's
 * its class's `UnitIcon` (`Falcon4_UCD.xml`); the number is named in `Art\Main\ImageIds.id` (`ICON_ARMOR<TAB>10010`)
 * and the picture of that name is taken from the team colour's bundle — `{Wht,Grn,Blu,Brn,Orn,Ylw,Red,Gry}{Dark,Lite}`
 * for objectives and ground units, `{Wh,Gn,Bl,Br,Or,Yl,Rd,Gy}Air_<nn|ne|ee|se|ss|sw|ww|nw>` for aircraft by heading —
 * under the theater's `artdir` first, else BMS's own `Data\Art\Resource`. BMS's magenta is transparent; the picture's
 * hot spot ([Icon.cx], [Icon.cy]) is the point placed on the map.
 */
object CampMapIcons {
    class Icon(val w: Int, val h: Int, val cx: Int, val cy: Int, val argb: IntArray)

    private val DARK = listOf("Wht", "Grn", "Blu", "Brn", "Orn", "Ylw", "Red", "Gry")
    private val AIR = listOf("Wh", "Gn", "Bl", "Br", "Or", "Yl", "Rd", "Gy")
    val HEADINGS = listOf("nn", "ne", "ee", "se", "ss", "sw", "ww", "nw")

    class IconSet(private val set: Theaters.TheaterSet, private val t: Theaters.Theater?) {
        private val resourceDirs: List<File> = buildList {
            t?.artDir?.trim()?.trim('"', '\\', '/')?.takeIf { it.isNotEmpty() }?.let { a ->
                Theaters.resolveDir(set.data, "$a\\Art\\Resource")?.let(::add)
                Theaters.resolveDir(set.data, "$a\\Resource")?.let(::add)
            }
            Theaters.resolveDir(set.data, "Art\\Resource")?.let(::add)
        }
        private val ids: Map<Int, String> by lazy {
            val places = buildList {
                t?.artDir?.trim()?.trim('"', '\\', '/')?.takeIf { it.isNotEmpty() }?.let { a ->
                    add("$a\\Art\\Main\\ImageIds.id"); add("$a\\Main\\ImageIds.id")
                }
                add("Art\\Main\\ImageIds.id")
            }
            val f = places.firstNotNullOfOrNull { Theaters.resolveFile(set.data, it) } ?: return@lazy emptyMap()
            val out = HashMap<Int, String>()
            runCatching {
                f.readLines(Charsets.ISO_8859_1).forEach { line ->
                    val parts = line.trim().split(Regex("\\s+"))
                    if (parts.size >= 2) parts[1].toIntOrNull()?.let { out[it] = parts[0] }
                }
            }
            out
        }
        private val ucd: Map<Int, Int> by lazy {
            CampaignArchive.table(set.classFile(t, CampaignArchive.UCD_FILE), "UCD", setOf("UnitIcon"))
                .mapNotNull { (k, v) -> v["UnitIcon"]?.trim()?.toIntOrNull()?.let { k to it } }.toMap()
        }
        /**
         * Why BMS's icons cannot be drawn for this theater (its `ImageIds.id` or every icon bundle missing), worded for
         * the Layers panel; null when they can. The map then draws the app's symbols — never silently.
         */
        val missingWhy: String? by lazy {
            val bundlesThere = resourceDirs.any { d -> d.listFiles()?.any { it.isFile && it.name.endsWith(".idx", true) } == true }
            when {
                ids.isEmpty() && !bundlesThere -> "BMS's icon files (Art\\Main\\ImageIds.id and the Art\\Resource bundles) were not found: the app's symbols are shown."
                ids.isEmpty() -> "BMS's icon list (Art\\Main\\ImageIds.id) was not found: the app's symbols are shown."
                !bundlesThere -> "BMS's icon pictures (the Art\\Resource bundles) were not found: the app's symbols are shown."
                else -> null
            }
        }

        private val ocd = ConcurrentHashMap<Int, Int>()
        private val bundles = ConcurrentHashMap<String, Any>()
        private val icons = ConcurrentHashMap<String, Any>()

        private fun bundle(name: String): ResourceBundle.Bundle? {
            val got = bundles.getOrPut(name.lowercase()) {
                resourceDirs.firstNotNullOfOrNull { d -> d.listFiles()?.firstOrNull { it.isFile && it.name.equals("$name.idx", true) } }
                    ?.let { ResourceBundle.read(it) } ?: Unit
            }
            return got as? ResourceBundle.Bundle
        }

        /** The objective class [ocdIdx]'s icon number (its OCD file's `ObjectiveIcon`); -1 when it has none. */
        private fun objectiveIcon(ocdIdx: Int): Int = ocd.getOrPut(ocdIdx) {
            val id = ocdIdx.toString().padStart(5, '0')
            val roots = listOfNotNull(set.objectDir(t), Theaters.resolveDir(set.data, "TerrData\\Objects"))
            val f = roots.firstNotNullOfOrNull { r -> Theaters.resolveDir(r, "ObjectiveRelatedData\\OCD_$id")?.let { Theaters.resolveFile(it, "OCD_$id.XML") } }
            f?.let { runCatching { Regex("<ObjectiveIcon>\\s*(\\d+)").find(it.readText())?.groupValues?.get(1)?.toIntOrNull() }.getOrNull() } ?: -1
        }

        private fun picture(bundleName: String, image: String): Icon? {
            val key = "$bundleName|$image"
            val got = icons.getOrPut(key) {
                val b = bundle(bundleName) ?: return@getOrPut Unit
                val img = b[image] ?: return@getOrPut Unit
                ResourceBundle.smallImage(b, img, maxPixels = 128 * 128)?.let { Icon(img.width, img.height, img.cx, img.cy, it) } ?: Unit
            }
            return got as? Icon
        }

        /** Objective class [ocdIdx]'s icon in team colour [colour] (0-7), BMS's "Lite" set when [lite]. */
        fun objective(ocdIdx: Int, colour: Int, lite: Boolean = false): Icon? {
            if (ocdIdx < 0) return null
            val name = ids[objectiveIcon(ocdIdx)] ?: return null
            return picture(DARK[colour.coerceIn(0, 7)] + if (lite) "Lite" else "Dark", name)
        }

        /** Unit class [ucdIdx]'s icon in team colour [colour]; an aircraft's drawn heading [heading] (one of [HEADINGS]). */
        fun unit(ucdIdx: Int, colour: Int, air: Boolean, heading: String = "nn"): Icon? {
            val name = ucd[ucdIdx]?.let { ids[it] } ?: return null
            val c = colour.coerceIn(0, 7)
            return if (air) picture(AIR[c] + "Air_" + heading, name) ?: picture(DARK[c] + "Dark", name)
            else picture(DARK[c] + "Dark", name)
        }
    }

    private val sets = ConcurrentHashMap<String, IconSet>()

    /** Theater [t]'s icons (made once per theater and folder). */
    fun forTheater(set: Theaters.TheaterSet, t: Theaters.Theater?): IconSet =
        sets.getOrPut("${set.root.absolutePath}|${t?.appId}") { IconSet(set, t) }

    fun forget() = sets.clear()

    /** The heading name of a true course [deg] (0 = north). */
    fun headingName(deg: Double): String = HEADINGS[(((deg % 360 + 360) % 360 + 22.5) / 45).toInt() % 8]
}
