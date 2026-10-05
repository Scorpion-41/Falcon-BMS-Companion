package com.bmscompanion.desktop.bridge

import com.bmscompanion.app.data.mission.CampIntel
import com.bmscompanion.app.data.mission.CampKind
import com.bmscompanion.app.data.mission.CampSite
import com.bmscompanion.app.data.mission.RawSection
import com.bmscompanion.app.data.mission.TextBlock
import java.nio.charset.Charset
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * The texts of a save's flight that Falcon BMS's printed briefing words out of the campaign itself, made from the save
 * so the Planner's Briefing page is filled for a flight BMS has printed no briefing for.
 *
 * BMS builds its briefing with scripts (the `.b` files in `Data/Campaign`: `Header.b`, `Situate.b`, `RoE.b`, `Emerganc.b`, `End.b`,
 * …) over the campaign's `Strings.txt`. The scripts are logic, not data the app could read at run time (a copy of the
 * install need not have them), so what they do with the save is followed here, token by token, and the words come from
 * the theater's own `Strings.txt`. Checked against the two printed briefings whose saves are known (Korea's Auto Save,
 * Cyborg6, BARCAP, context 22; Hellas WCP's `Save-Day  1 04 21 35`, Ouranos5, DEAD, context 52):
 *
 * - **Situation** (`Situate.b`, campaigns only): the flight's team's ground action — type 4 (offensive) is Strings 890
 *   "Be advised: Starting at #0, our ground forces will be making a major push towards #1." with its time and
 *   objective, type 1 (defensive) 891 "… probably with the intent to take #1." (BMS names the team's own ground
 *   objective there, and prints "target", Strings 168, when it has none — which is what Korea's print says) — then the
 *   package's mission context: Strings 700 + context, its tokens filled as below, and for some contexts a second
 *   sentence (800 + context) or the target's vehicles ("several SA-5 (S-200) missile launchers."). The lists the
 *   scripts add after some headings (`BEST_FEATURES`, `POTENTIAL_TARGETS`) come from feature data the save does not
 *   hold and are left out; "Intelligence reports the highest impact targets are:" (232), which only introduces one,
 *   with them. A TE prints no situation: there it is the team's motto, the text a TE's author writes (what WDP shows).
 * - **Tokens**: `#c0` the target's owner as an adjective (Strings 80 + team: "Albanian"), `#DB0` the target's name (a
 *   unit as "500th Air Defense Battalion", [CampaignArchive.unitName]; `1` is the requesting unit), `#Ls` "4 nm south of
 *   Tirana" (Strings 52 against the nearest city or town), `#Ln` "northwest of Mallipo Town" (53), `#Lg` against the
 *   nearest primary objective (the save's `.pol`, as the support stations are said: "17 nm northeast of Sejong City"),
 *   `#0`/`#1` the script's arguments: `OS OO` the team's air operation (Strings 854 + its type: "… an Offensive Counter
 *   Air operation targeted at the area around") and its objective, `RD` the requesting unit's objective, `RV` its
 *   vehicle, `t` the time on target. A sentence with a token the save cannot fill is left out rather than printed
 *   half-made. Distances are BMS's: whole grid cells (km), over 1.852, cut to whole miles — "4 nm", where feet give 5.
 * - **Station / Target Area** (`Header.b`) for the Mission Objective box, the **Pkg-Mission** line (Strings 900 +
 *   mission), the **rules of engagement** (`RoE.b`: 660 or, for a sweep, 662 and 663; then 661; none in a TE) and the
 *   **emergency procedures** (`Emerganc.b`, `End.b`: distress call, CSAR by mission, the alternate field and where it
 *   is, "Good Luck!").
 * - **Intelligence**: what BMS's Threat Analysis lists along the route is picked when it prints (the save 40 minutes on
 *   had other SAMs spotted), so the Briefing page's Intel is WDP's own: [intel].
 *
 * Nothing here throws; a text that cannot be made is null or empty, and the page then says PRINT brings it.
 */
object CampaignBriefing {

    /** What [of] makes: the texts, each null or empty when the save cannot give it. */
    class Texts(
        val situation: String?,
        val roe: List<String>,
        val emergency: List<TextBlock>,
        /** "Station Area:"/"Time on Station:" (or Target) rows, as the printed briefing's Mission Overview has them */
        val overview: RawSection?,
        /** a target mission's "Target Area" ("4 nm south of Tirana"), the printed briefing's `overview.targetArea` */
        val targetArea: String?,
        val packageMission: String?,
        val intel: CampIntel?,
    )

    /**
     * The texts of flight [f] of [save] ([pkg] its package, [objs] its start file's objectives, [alternate] its alternate
     * landing waypoint: BMS says where the alternate field is from that waypoint's cell, which need not be the field's
     * own — Pyeongtaek's is a cell east of it, and only the waypoint gives the "17 nm" Korea's briefing prints).
     */
    fun of(
        save: CampaignArchive.Save, names: CampaignArchive.Names, f: CampaignArchive.Flight, pkg: CampaignArchive.Package?,
        objs: CampaignArchive.ObjWalk?, alternate: CampaignArchive.Waypoint?,
    ): Texts {
        val w = Words(save, names, f, pkg, objs)
        return Texts(
            situation = attempt(null) { w.situation() },
            roe = attempt(emptyList()) { w.roe() },
            emergency = attempt(emptyList()) { w.emergency(alternate) },
            overview = attempt(null) { w.overview() },
            targetArea = attempt(null) { if (f.mission in TARGET_MISSIONS) w.specific(w.targetCell) else null },
            packageMission = attempt(null) { w.packageMission() },
            intel = attempt(null) { intel(save, names, f) },
        )
    }

    /**
     * WDP's intelligence for [f] (`FillMissionBriefing`): the distinct vehicle names of the units hostile to the
     * flight's team or at war with it (its own record of the team part), not destroyed (WDP's flag 0x20000), sorted —
     * air-defence battalions (sub-type 1) and squadrons by what they fly (8 fighters, 9 fighter-bombers, 3 and 6
     * bombers, 5, 7, 10, 11 and 13 support, 4, 12 and 14 helicopters). Null when the team table was not read.
     */
    fun intel(save: CampaignArchive.Save, names: CampaignArchive.Names, f: CampaignArchive.Flight): CampIntel? {
        val team = save.teams?.firstOrNull { it.n == f.owner } ?: return null
        fun hostile(u: CampaignArchive.CampUnit): Boolean {
            val s = team.stance.getOrNull(u.owner)
            return (s == TeamRelations.HOSTILE || s == TeamRelations.WAR) && u.core.unitFlags and UNIT_DEAD == 0
        }
        fun list(units: List<CampaignArchive.CampUnit>, subs: Set<Int>): List<String> =
            units.filter { u -> hostile(u) && names.ct(u.type)?.subType?.let { it in subs } == true }
                .mapNotNull { names.aircraft(it.type) }.distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)
        val battalions = save.units.filterIsInstance<CampaignArchive.Battalion>()
        val squadrons = save.squadrons
        return CampIntel(
            ground = list(battalions, setOf(1)),
            fighters = list(squadrons, setOf(8)),
            fighterBombers = list(squadrons, setOf(9)),
            bombers = list(squadrons, setOf(3, 6)),
            support = list(squadrons, setOf(5, 7, 10, 11, 13)),
            helos = list(squadrons, setOf(4, 12, 14)),
        )
    }

    /**
     * The enemy's air-defence battalions (unit sub-type 1) and task forces for [f], each where it stands and whether
     * the flight's side has spotted it — hostile and not destroyed by the same test as [intel]. Spotting is kept per
     * side in the unit's `spotted` bits, and the side is the team that **controls** the flight's team: in Korea ROK
     * controls the U.S. team, and on the fixture's Auto Save the U.S. team's own bit marked 0 of 178 sites where ROK's
     * marked 90 (either bit counts). Empty lists when the team table was not read.
     */
    fun sites(save: CampaignArchive.Save, names: CampaignArchive.Names, f: CampaignArchive.Flight): Pair<List<CampSite>, List<CampSite>> {
        val team = save.teams?.firstOrNull { it.n == f.owner } ?: return emptyList<CampSite>() to emptyList()
        val side = (1 shl (team.controller and 7)) or (1 shl (f.owner and 7))
        fun hostile(u: CampaignArchive.CampUnit): Boolean {
            val s = team.stance.getOrNull(u.owner)
            return (s == TeamRelations.HOSTILE || s == TeamRelations.WAR) && u.core.unitFlags and UNIT_DEAD == 0
        }
        fun site(u: CampaignArchive.CampUnit) = CampSite(
            system = names.aircraft(u.type).orEmpty(), name = attempt(null) { CampaignArchive.unitName(save, u) },
            x = u.north, y = u.east, spotted = u.core.spotted and side != 0,
        )
        val air = save.units.filterIsInstance<CampaignArchive.Battalion>()
            .filter { u -> hostile(u) && names.ct(u.type)?.subType == 1 }.map(::site)
        val ships = save.units.filterIsInstance<CampaignArchive.TaskForce>().filter(::hostile).map(::site)
        return air to ships
    }

    /**
     * BMS's `SPECIFIC_LOCATION` for a cell, the words the printed Threat Analysis puts after each site ("2 nm west of
     * Buk-myeon", "near Tirana"): against the nearest city or town of the save's start file [objs], whole cells,
     * miles cut to whole — the same as the situation's `#Ls` ([Words.specific]). [town] finds such a place by name.
     */
    class Places(save: CampaignArchive.Save, private val names: CampaignArchive.Names, objs: CampaignArchive.ObjWalk?) {
        val towns: List<CampaignArchive.Objective> = objs?.objectives.orEmpty().filter { o ->
            o.name.isNotBlank() && names.ct(o.type)?.let { it.cls == CLASS_OBJECTIVE && (it.type == OBJ_CITY || it.type == OBJ_TOWN) } == true
        }
        private fun str(id: Int): String? = names.strings[id]?.let(::cp1252)

        /** The words for cell ([x] east, [y] north), or null when the save has no towns. */
        fun words(x: Int, y: Int): String? {
            val (o, km) = towns.map { it to hypot((x - it.x).toDouble(), (y - it.y).toDouble()) }.minByOrNull { it.second } ?: return null
            val nm = floor(km / KM_PER_NM).toInt()
            if (nm <= 0) return fill(str(S_NEAR) ?: return null, o.name.trim())
            val deg = Math.toDegrees(atan2((x - o.x).toDouble(), (y - o.y).toDouble()))
            val compass = str(S_COMPASS + (((deg / 45.0).roundToInt() % 8) + 8) % 8) ?: return null
            return fill(str(S_NM_OF) ?: return null, nm.toString(), compass, o.name.trim())
        }

        /** The town or city named [name] (case and spacing aside), if the save's start file has one. */
        fun town(name: String): CampaignArchive.Objective? {
            val k = name.trim().lowercase()
            return towns.firstOrNull { it.name.trim().lowercase() == k }
        }
    }

    /** A grid cell, x east and y north: BMS measures the briefing's distances between whole cells. */
    private class Cell(val x: Int, val y: Int)

    /** A unit or objective the briefing talks about: its name, owner, cell and, for a unit, the unit. */
    private class Thing(val name: String?, val owner: Int?, val cell: Cell?, val unit: CampaignArchive.CampUnit?)

    private class Words(
        val save: CampaignArchive.Save, val names: CampaignArchive.Names, val f: CampaignArchive.Flight,
        val pkg: CampaignArchive.Package?, val objs: CampaignArchive.ObjWalk?,
    ) {
        val tactical = save.kind != CampKind.CAMPAIGN
        val team = save.teams?.firstOrNull { it.n == f.owner }

        fun str(id: Int): String? = names.strings[id]?.let(::cp1252)

        fun thing(id: CampaignArchive.VuId?): Thing? {
            if (id == null || id.isNone) return null
            save.unit(id)?.let { u -> return Thing(CampaignArchive.unitName(save, u), u.owner, Cell(u.core.x, u.core.y), u) }
            objs?.get(id)?.let { o -> return Thing(o.name.trim().takeIf { it.isNotEmpty() }, o.owner, Cell(o.x, o.y), null) }
            return null
        }

        /** What the package was planned against: entity 0 of the context strings. */
        val target: Thing? by lazy { thing(pkg?.request?.target) }

        /** Where "the target" is: the target's cell, else the flight's target waypoint (a CAP's station). */
        val targetCell: Cell? by lazy {
            target?.cell ?: f.waypoints.firstOrNull { it.flags and WPF_TARGET != 0L }?.let { Cell(it.gx, it.gy) }
        }

        val towns: List<CampaignArchive.Objective> by lazy {
            objs?.objectives.orEmpty().filter { o ->
                o.name.isNotBlank() && names.ct(o.type)?.let { it.cls == CLASS_OBJECTIVE && (it.type == OBJ_CITY || it.type == OBJ_TOWN) } == true
            }
        }

        val primaries: List<CampaignArchive.Objective> by lazy {
            save.primaryObjectives.orEmpty().mapNotNull { objs?.get(it) }.filter { it.name.isNotBlank() }
        }

        fun nearest(c: Cell, set: List<CampaignArchive.Objective>): Pair<CampaignArchive.Objective, Double>? =
            set.map { it to hypot((c.x - it.x).toDouble(), (c.y - it.y).toDouble()) }.minByOrNull { it.second }

        /** The compass word (Strings 30-37) for [c] as seen from [o]. */
        fun compass(c: Cell, o: CampaignArchive.Objective): String? {
            val deg = Math.toDegrees(atan2((c.x - o.x).toDouble(), (c.y - o.y).toDouble()))
            return str(S_COMPASS + (((deg / 45.0).roundToInt() % 8) + 8) % 8)
        }

        /** "23 nm northwest of Mallipo Town" (Strings 52), "near …" (54) inside a mile. */
        fun far(c: Cell?, set: List<CampaignArchive.Objective>): String? {
            if (c == null) return null
            val (o, km) = nearest(c, set) ?: return null
            val nm = floor(km / KM_PER_NM).toInt()
            if (nm <= 0) return fill(str(S_NEAR) ?: return null, o.name.trim())
            return fill(str(S_NM_OF) ?: return null, nm.toString(), compass(c, o) ?: return null, o.name.trim())
        }

        fun specific(c: Cell?) = far(c, towns)
        fun general(c: Cell?) = far(c, primaries)

        /** "northwest of Mallipo Town" (Strings 53). */
        fun nearestPlace(c: Cell?): String? {
            if (c == null) return null
            val (o, km) = nearest(c, towns) ?: return null
            if (km < 1.0) return fill(str(S_NEAR) ?: return null, o.name.trim())
            return fill(str(S_OF) ?: return null, compass(c, o) ?: return null, o.name.trim())
        }

        fun entity(n: Int): Thing? = when (n) {
            0 -> target
            1 -> thing(pkg?.request?.requester)
            else -> null
        }

        /** A context string with its tokens filled, or null when one of them cannot be. */
        fun expand(t: String, args: List<String?>): String? {
            var failed = false
            val out = TOKEN.replace(t) { m ->
                val g = m.groupValues
                val v: String? = when {
                    g[1].isNotEmpty() -> entity(g[2].toInt())?.name
                    g[3].isNotEmpty() -> entity(g[4].toInt())?.owner?.let { str(S_ADJECTIVE + it) }
                    g[5].isNotEmpty() -> when (g[5]) {
                        "s" -> specific(targetCell)
                        "n" -> nearestPlace(targetCell)
                        "g" -> general(targetCell)
                        else -> null
                    }
                    else -> args.getOrNull(g[6].toInt())
                }
                if (v == null) { failed = true; "" } else v
            }
            return if (failed) null else out
        }

        // ---- the script's arguments

        /** RD: where the requesting unit is ordered to. */
        fun rd(): String? = when (val u = save.unit(pkg?.request?.requester)) {
            is CampaignArchive.Battalion -> thing(u.objective)?.name
            is CampaignArchive.Brigade -> thing(u.objective)?.name
            else -> null
        }

        /** RV: the requesting unit's vehicle. */
        fun rv(): String? = save.unit(pkg?.request?.requester)?.let { names.aircraft(it.type) }

        /** t: the time on target. */
        fun tot(): String? = (pkg?.request?.tot?.takeIf { it > 0 } ?: f.tot.takeIf { it > 0 })?.let { CampaignFiles.hms(it) }

        /** The team's air operation: its offensive one, else its defensive one. */
        fun airAction(): CampaignArchive.AirAction? =
            team?.let { t -> t.offensive.takeIf { it.type in 1..5 } ?: t.defensive.takeIf { it.type in 1..5 } }

        /** OS: "In an effort to obtain air superiority, air command has initiated an Offensive Counter Air operation targeted at the area around". */
        fun os(): String? = airAction()?.let { str(S_OPERATION + it.type) }

        /** OO: the operation's objective. */
        fun oo(): String? = airAction()?.let { thing(it.objective)?.name }

        /** TARGET_VEHICLE_NAME and "missile launchers" or "anti-aircraft guns" (Strings 235, 236). */
        fun vehicleWords(): String? {
            val u = target?.unit ?: return null
            val v = names.aircraft(u.type) ?: return null
            val kind = str(if (names.vehicleCt(u.type)?.subType == SUB_SAM) S_MISSILES else S_GUNS) ?: return null
            return "$v $kind"
        }

        /** ENEMY_SQUADRONS, by name: the squadrons based at the target airbase. */
        fun squadronsAt(): List<String> {
            val at = pkg?.request?.target ?: return emptyList()
            return save.squadrons.filter { it.airbase == at }.mapNotNull { CampaignArchive.unitName(save, it) }.map { "-- $it" }
        }

        // ---- the texts

        fun situation(): String? {
            if (tactical) return motto()
            val paras = ArrayList<String>()
            team?.ground?.let { g ->
                val template = when (g.type) {
                    GROUND_OFFENSIVE -> str(S_OFFENSIVE)
                    GROUND_DEFENSIVE -> str(S_DEFENSIVE)
                    else -> null
                }
                if (template != null) paras += fill(template, CampaignFiles.hms(g.time), thing(g.objective)?.name ?: str(S_TARGET) ?: "target")
            }
            paras += context()
            return paras.joinToString("\n\n").takeIf { it.isNotBlank() } ?: motto()
        }

        /** The paragraphs `Situate.b` prints for the package's mission context. */
        fun context(): List<String> {
            val ctx = pkg?.request?.context?.takeIf { it > 0 } ?: f.missionContext
            if (ctx <= 0) return emptyList()
            val main = str(S_CONTEXT + ctx) ?: return emptyList()
            fun say(vararg args: String?) = expand(main, args.toList())
            val second = str(S_CONTEXT2 + ctx)
            return when (ctx) {
                1, 2, 3, 4 -> listOfNotNull(say(rd()))
                5, 6 -> listOfNotNull(say(rd(), tot()))
                14, 22 -> say()?.let { listOfNotNull(it, second) }.orEmpty()
                18, 20, 23, 24, 25, 26 -> listOfNotNull(say(rv()))
                39 -> listOfNotNull(say()?.let { s -> vehicleWords()?.let { join(s, it, second) } })
                40 -> say()?.let { listOf(it) + squadronsAt() + listOfNotNull(second) }.orEmpty()
                52 -> listOfNotNull(say(os(), oo())?.let { s -> vehicleWords()?.let { join(s, it, second) } })
                in 53..59 -> listOfNotNull(say(os(), oo()))
                7, 8, 15, 16, 17, 19, 27, 30, 31, 32, 35, 36, 37, 38, 41, 44, 45, 46, 47, 48, 49, 50, 51, 60, 61, 62, 63 -> listOfNotNull(say())
                else -> emptyList()
            }
        }

        /** The rules of engagement (`RoE.b`); a TE prints none. */
        fun roe(): List<String> {
            if (tactical) return emptyList()
            val ids = if (f.mission == MISSION_SWEEP) listOf(662, 663) else listOf(660)
            return (ids + 661).mapNotNull { str(it) }
        }

        /** The emergency procedures (`Emerganc.b`, `End.b`), in the blocks the printed briefing's parser makes of them. */
        fun emergency(alternate: CampaignArchive.Waypoint?): List<TextBlock> {
            fun title(id: Int, fallback: String) = (str(id) ?: fallback).trim().trimEnd(':').trim()
            val out = ArrayList<TextBlock>()
            str(172)?.let { out += TextBlock(title(170, "Distress call"), listOf(it)) }
            val csar = when {
                tactical -> 177
                f.mission in CSAR_NEAR_FLOT -> 226
                else -> 227
            }
            str(csar)?.let { out += TextBlock(title(174, "Combat Search and Rescue"), listOf(it)) }
            // ALTERNATE_STRIP_NAME 190 GENERAL_LOCATION ALT_AIRBASE, else 173 "No alternate airfield is currently available."
            val alt = thing(alternate?.target)
            val at = alternate?.let { Cell(it.gx, it.gy) } ?: alt?.cell
            // (Strings.txt is read trimmed: 190 is ", ")
            val line = alt?.name?.let { n -> n + (general(at)?.let { (str(S_COMMA)?.trimEnd() ?: ",") + " " + it } ?: "") } ?: str(173)
            out += TextBlock(title(171, "Alternate airfield"), listOfNotNull(line, str(229), str(256)))
            return out
        }

        /** `Header.b`'s station or target area and its time, as the printed Mission Overview rows. */
        fun overview(): RawSection? {
            val station = f.mission in STATION_MISSIONS
            if (!station && f.mission !in TARGET_MISSIONS) return null
            val rows = ArrayList<List<String>>()
            specific(targetCell)?.let { rows += listOf(if (station) "Station Area:" else "Target Area:", "$it.") }
            f.tot.takeIf { it > 0 }?.let { rows += listOf(if (station) "Time on Station:" else "Time on Target:", CampaignFiles.hms(it) + "z") }
            return if (rows.isEmpty()) null else RawSection("Mission Overview", rows)
        }

        /** `Header.b`'s Pkg-Mission line where it is one string (900 + the package's mission); null where it names the target too. */
        fun packageMission(): String? {
            val m = pkg?.request?.mission ?: return null
            return when (m) {
                in PACKAGE_PLAIN -> str(S_PACKAGE_MISSION + m)
                MISSION_TRAINING -> str(669)
                else -> null
            }
        }

        /** The team's motto (its team record's, else the header's copy), without a converter's stamp. */
        fun motto(): String? {
            val raw = team?.motto?.trim()?.takeIf { it.isNotEmpty() } ?: save.header?.team(f.owner)?.motto?.trim()
            return raw?.replace(STAMP, " ")?.trim()?.takeIf { it.isNotEmpty() }
        }
    }

    /** [t] with `#0`, `#1`… replaced by [args]. */
    private fun fill(t: String, vararg args: String): String =
        Regex("#(\\d)").replace(t) { m -> args.getOrNull(m.groupValues[1].toInt()) ?: m.value }

    /** Sentences run together as the scripts run them: a space between, none before punctuation. */
    private fun join(vararg parts: String?): String =
        parts.filterNotNull().map { it.trim() }.filter { it.isNotEmpty() }.fold("") { acc, p ->
            if (acc.isEmpty()) p else if (p[0] in ".,;:") acc + p else "$acc $p"
        }

    private val CP1252: Charset? = runCatching { Charset.forName("windows-1252") }.getOrNull()

    /** `Strings.txt` is read as ISO-8859-1; its quotation marks ("MAYDAY") are Windows-1252. */
    private fun cp1252(s: String): String =
        if (CP1252 == null || s.none { it in '\u0080'..'\u009F' }) s else String(s.toByteArray(Charsets.ISO_8859_1), CP1252)

    private inline fun <T> attempt(fallback: T, body: () -> T): T = try { body() } catch (_: Throwable) { fallback }

    /** `#DB0`, `#D0`, `#c0`, `#C0`, `#Ls`, `#0`. */
    private val TOKEN = Regex("#(?:(DB|D)(\\d)|([cC])(\\d)|L([a-z])|(\\d))")

    /** A converter's stamp some theaters carry as their motto ("Converted with MC 0.5.5.324"): not a situation. */
    private val STAMP = Regex("Converted with MC [0-9.]+")

    private const val KM_PER_NM = 1.852

    /** A waypoint the flight is tasked at (a CAP's station, a strike's target). */
    private const val WPF_TARGET = 0x1L

    /** WDP's "destroyed" unit flag. */
    private const val UNIT_DEAD = 0x20000

    private const val GROUND_DEFENSIVE = 1
    private const val GROUND_OFFENSIVE = 4
    private const val CLASS_OBJECTIVE = 4
    private const val OBJ_CITY = 8
    private const val OBJ_TOWN = 28
    private const val SUB_SAM = 2
    private const val MISSION_SWEEP = 7
    private const val MISSION_TRAINING = 40

    private const val S_COMPASS = 30
    private const val S_NM_OF = 52
    private const val S_OF = 53
    private const val S_NEAR = 54
    private const val S_ADJECTIVE = 80
    private const val S_TARGET = 168
    private const val S_MISSILES = 235
    private const val S_GUNS = 236
    private const val S_COMMA = 190
    private const val S_CONTEXT = 700
    private const val S_CONTEXT2 = 800
    private const val S_OPERATION = 854
    private const val S_OFFENSIVE = 890
    private const val S_DEFENSIVE = 891
    private const val S_PACKAGE_MISSION = 900

    /** `Emerganc.b`: the missions whose CSAR line is "SAR helos are available within 30 km of FLOT" (226). */
    private val CSAR_NEAR_FLOT = setOf(4, 5, 6, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 27, 28, 33)

    /** `Header.b`: missions with a Station Area and Time on Station, and those with a Target Area and Time on Target. */
    private val STATION_MISSIONS = setOf(1, 2, 3, 4, 5, 6, 7, 8, 19, 20, 22, 23, 24, 25, 26, 27, 28, 31, 34, 35, 37)
    private val TARGET_MISSIONS = setOf(9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 21, 29, 30, 36)

    /** `Header.b`: the package missions whose Pkg-Mission line is Strings 900 + mission alone. */
    private val PACKAGE_PLAIN = setOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 19, 23, 26, 27, 28, 30, 31, 35, 37, 38, 39)
}
